package santa.runner

import scala.util.{Failure, Success}

import io.circe.Json

import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.SigmaSerializer
import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.history.CPreHeader
import org.ergoplatform.modifiers.history.header.{Header, HeaderSerializer}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, ErgoTransactionSerializer}
import org.ergoplatform.nodeView.state.{UpcomingStateContext, VotingData}
import org.ergoplatform.settings.{ChainSettings, ChainSettingsReader, ErgoValidationSettings,
  ErgoValidationSettingsUpdate, Parameters, TestnetLaunchParameters}
import org.ergoplatform.wallet.interpreter.ErgoInterpreter

/** The gated transaction-tier engine: ergo-core's `ErgoTransaction.validateStateful`
  * behind the `SANTA_TX_BLESSER` build gate (ergo-core is not on Maven — publishLocal'd
  * locally or by the CI publish step). Two consumers ride it: the test-scope captured-tx
  * blesser (`CapturedTx`) and rudolph's transaction arm — [[Runner]] looks this object up
  * BY REFLECTION, so an ergo-core-less build still compiles and its tx arm degrades to a
  * faithful `not-implemented`.
  */
object TxEngine extends ApiCodecs {
  // A FILE path (not classpath), shared with the test-scope blesser: santa-run forks sbt
  // from jvm-blesser/, so the cwd-relative read works for both scopes.
  private val ChainConf = "src/test/resources/chain-testnet.conf"

  final case class Verdict(valid: Boolean, cost: Option[Long], reason: Option[String])

  /** Validate one tx. activated = the vector's version.activated (3 for v6);
    * blockVersion = activated + 1. ts/nBits are cosmetic (preHeader). */
  def validate(txJson: Json, inputBoxes: Seq[Json], dataInputBoxes: Seq[Json],
               height: Int, activated: Int, ts: Long = 0L, nBits: Long = 0L): Verdict = {
    implicit val chainSettings: ChainSettings =
      ChainSettingsReader.read(ChainConf).getOrElse(sys.error(s"chain settings: $ChainConf"))
    val tx = txJson.as[ErgoTransaction].fold(e => sys.error(s"tx decode: $e"), identity)
    def box(j: Json): ErgoBox = j.as[ErgoBox].fold(e => sys.error(s"box decode: $e"), identity)
    val boxesToSpend = inputBoxes.map(box).toIndexedSeq
    val dataBoxes = dataInputBoxes.map(box).toIndexedSeq
    val blockVersion = (activated + 1).toByte
    val params = new Parameters(height,
      TestnetLaunchParameters.parametersTable.updated(Parameters.BlockVersion, blockVersion.toInt),
      ErgoValidationSettingsUpdate.empty)
    val preHeader = CPreHeader(blockVersion, Header.GenesisParentId, ts, nBits, height,
      Array.fill(3)(0.toByte), org.ergoplatform.mining.group.generator)
    val ctx = UpcomingStateContext(Seq.empty, None, preHeader, chainSettings.genesisStateDigest,
      params, ErgoValidationSettings.initial, VotingData.empty)
    implicit val verifier: ErgoInterpreter = ErgoInterpreter(params)
    tx.validateStateful(boxesToSpend, dataBoxes, ctx, 0L).result.toTry match {
      case Success(cost) => Verdict(valid = true,  cost = Some(cost.toLong), reason = None)
      case Failure(e)    => Verdict(valid = false, cost = None, reason = Some(s"${e.getClass.getName}: ${e.getMessage}"))
    }
  }

  /** Bytes-anchored validate: tx + boxes from their sigma bytes, under the vector's PROVIDED context
    * (real last `headers` + `preHeader` + `parameters`) rather than a height-synthetic one. The
    * bytes path is the consensus-unambiguous form — it preserves context-extension wire order (which
    * a JSON object key-reorders), so it's what dasher (and the order vector) ride. Parse only is
    * wrapped in the v-context so v6 box trees deserialize; `validateStateful` manages its own. */
  def validateBytes(txHex: String, inputBoxesHex: Seq[String], dataInputBoxesHex: Seq[String],
                    headersHex: Seq[String], preHeader: Json, parameters: Json): Verdict = {
    implicit val chainSettings: ChainSettings =
      ChainSettingsReader.read(ChainConf).getOrElse(sys.error(s"chain settings: $ChainConf"))
    val ph        = preHeader.hcursor
    val version   = ph.get[Int]("version").toOption.map(_.toByte).getOrElse(sys.error("preHeader.version"))
    val timestamp = ph.get[String]("timestamp").toOption.map(_.toLong)
      .orElse(ph.get[Long]("timestamp").toOption).getOrElse(sys.error("preHeader.timestamp"))
    val nBits     = ph.get[Long]("nBits").toOption.getOrElse(sys.error("preHeader.nBits"))
    val height    = ph.get[Int]("height").toOption.getOrElse(sys.error("preHeader.height"))
    val votes     = Base16.decode(ph.get[String]("votes").toOption.getOrElse("000000")).get
    val minerPk   = sigma.serialization.GroupElementSerializer.parse(
      SigmaSerializer.startReader(Base16.decode(ph.get[String]("minerPk").toOption.getOrElse(sys.error("preHeader.minerPk"))).get))

    // parameters are the testnet launch table (the fixtures carry the unchanged launch values); the
    // provided `parameters` are asserted equal at capture time, not re-derived here.
    val _ = parameters
    val blockVersion = version
    val params = new Parameters(height,
      TestnetLaunchParameters.parametersTable.updated(Parameters.BlockVersion, blockVersion.toInt),
      ErgoValidationSettingsUpdate.empty)
    // ErgoStateContext.lastHeaders is NEWEST-first (head == best/tip; see ErgoStateContext.scala:85/113/233),
    // which is exactly headers_hex's order — so no reverse. preHeader.parentId must be the tip = head.
    val headers   = headersHex.map(h => HeaderSerializer.parseBytes(Base16.decode(h).get)).toIndexedSeq
    val parentId  = if (headers.nonEmpty) headers.head.id else Header.GenesisParentId
    val preHdr    = CPreHeader(blockVersion, parentId, timestamp, nBits, height, votes, minerPk)
    val ctx = UpcomingStateContext(headers, None, preHdr, chainSettings.genesisStateDigest,
      params, ErgoValidationSettings.initial, VotingData.empty)

    val (tx, boxesToSpend, dataBoxes) = VersionContext.withVersions(version, version) {
      def box(hex: String): ErgoBox =
        ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(hex).get))
      (ErgoTransactionSerializer.parseBytes(Base16.decode(txHex).get),
        inputBoxesHex.map(box).toIndexedSeq, dataInputBoxesHex.map(box).toIndexedSeq)
    }
    implicit val verifier: ErgoInterpreter = ErgoInterpreter(params)
    tx.validateStateful(boxesToSpend, dataBoxes, ctx, 0L).result.toTry match {
      case Success(cost) => Verdict(valid = true,  cost = Some(cost.toLong), reason = None)
      case Failure(e)    => Verdict(valid = false, cost = None, reason = Some(s"${e.getClass.getName}: ${e.getMessage}"))
    }
  }

  /** One `santa-transaction` vector entry → actuals (the shared tx result shape:
    * `{ valid, cost, error[, reason] }`). A decode failure here is a harness/oracle
    * self-contradiction (the same decode blessed the vector), so it surfaces as
    * `panicked` per the never-panic contract — never aborts the file. */
  def txEntry(e: Json): (String, Json) = {
    val c    = e.hcursor
    val name = c.get[String]("name").toOption.getOrElse("?")
    try {
      val txHex      = c.get[String]("tx_bytes_hex").toOption.getOrElse(sys.error(s"tx entry '$name': missing tx_bytes_hex"))
      val inHex      = c.downField("input_boxes_hex").as[List[String]].getOrElse(Nil)
      val dtHex      = c.downField("data_input_boxes_hex").as[List[String]].getOrElse(Nil)
      val headersHex = c.downField("headers_hex").as[List[String]].getOrElse(Nil)
      val preHeader  = c.downField("preHeader").focus.getOrElse(sys.error(s"tx entry '$name': missing preHeader"))
      val parameters = c.downField("parameters").focus.getOrElse(sys.error(s"tx entry '$name': missing parameters"))
      val v = validateBytes(txHex, inHex, dtHex, headersHex, preHeader, parameters)
      val base = Json.obj(
        "valid" -> Json.fromBoolean(v.valid),
        "cost"  -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "error" -> Json.Null)
      name -> v.reason.fold(base)(r => base.deepMerge(Json.obj("reason" -> Json.fromString(r))))
    } catch {
      case scala.util.control.NonFatal(t) =>
        name -> Json.obj(
          "valid" -> Json.Null,
          "cost"  -> Json.Null,
          "error" -> Json.fromString("panicked"),
          "note"  -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"))
    }
  }
}
