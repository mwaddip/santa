package santa.runner

import scala.util.{Failure, Success}

import io.circe.Json

import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.history.CPreHeader
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
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

  /** One `santa-transaction` vector entry → actuals (the shared tx result shape:
    * `{ valid, cost, error[, reason] }`). A decode failure here is a harness/oracle
    * self-contradiction (the same decode blessed the vector), so it surfaces as
    * `panicked` per the never-panic contract — never aborts the file. */
  def txEntry(e: Json): (String, Json) = {
    val c    = e.hcursor
    val name = c.get[String]("name").toOption.getOrElse("?")
    try {
      val tx = c.downField("tx").focus.getOrElse(sys.error(s"tx entry '$name': missing tx"))
      val inputBoxes     = c.downField("inputBoxes").values.getOrElse(Vector.empty).toSeq
      val dataInputBoxes = c.downField("dataInputBoxes").values.getOrElse(Vector.empty).toSeq
      val height = c.downField("context").get[Int]("height").toOption
        .getOrElse(sys.error(s"tx entry '$name': missing context.height"))
      val activated = c.downField("version").get[Int]("activated").toOption
        .getOrElse(sys.error(s"tx entry '$name': missing version.activated"))
      val v = validate(tx, inputBoxes, dataInputBoxes, height, activated)
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
