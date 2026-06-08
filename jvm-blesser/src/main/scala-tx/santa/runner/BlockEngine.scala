package santa.runner

import scala.util.{Failure, Success, Try}
import io.circe.Json
import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.history.CPreHeader
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.state.{UpcomingStateContext, VotingData}
import org.ergoplatform.settings.{ChainSettings, ChainSettingsReader, ErgoValidationSettings,
  ErgoValidationSettingsUpdate, Parameters}
import org.ergoplatform.wallet.interpreter.ErgoInterpreter

/** The gated block-tier engine: ergo-core composition reproducing the node-module
  * `ErgoState.execTransactions` semantics (equivalence-anchored on block 2666 — see the
  * block-tier spec §4). Same gate + reflection seam as TxEngine: an ergo-core-less build
  * degrades the runner's block arm to not-implemented.
  *
  * v1 scope (pre-proofs): valid = threaded stateless+stateful over all txs; cost = the
  * accumulated total; post_digest = ECHOED from the vector's own block header (the
  * control-row tautology, declared — the ADProofs-verified computed digest lands with
  * the proofs arm). Reject mutations of classes the loop sees (cost, version, per-tx)
  * reject here; proof/digest classes need the proofs arm.
  */
object BlockEngine extends ApiCodecs {
  // FILE path (not classpath), shared with TxEngine — santa-run forks sbt from
  // jvm-blesser/, so the cwd-relative read works for both scopes.
  private val ChainConf = "src/test/resources/chain-testnet.conf"

  final case class Verdict(valid: Boolean, postDigest: Option[String],
                           cost: Option[Long], reason: Option[String])

  private def hex(b: Array[Byte]): String = scorex.util.encode.Base16.encode(b)

  def validate(blockJson: Json, headersJson: Vector[Json], paramsTable: Map[Int, Int],
               boxesBytes: Vector[(String, Array[Byte])]): Verdict = {
    implicit val chainSettings: ChainSettings =
      ChainSettingsReader.read(ChainConf).getOrElse(sys.error(s"chain settings: $ChainConf"))

    val header = blockJson.hcursor.downField("header").focus
      .getOrElse(sys.error("block: no header"))
      .as[Header].fold(e => sys.error(s"header decode: $e"), identity)
    val txs = blockJson.hcursor.downField("blockTransactions").downField("transactions")
      .focus.flatMap(_.asArray).getOrElse(sys.error("block: no transactions"))
      .map(_.as[ErgoTransaction].fold(e => sys.error(s"tx decode: $e"), identity))

    val lastHeaders = headersJson
      .map(_.as[Header].fold(e => sys.error(s"window header decode: $e"), identity))
      .sortBy(-_.height)

    val byBytes: Map[String, ErgoBox] = boxesBytes.map { case (id, bs) =>
      val b = org.ergoplatform.wallet.boxes.ErgoBoxSerializer.parseBytes(bs)
      require(hex(b.id) == id, s"box bytes hash to ${hex(b.id)} != declared $id")
      id -> b
    }.toMap
    val createdOutputs: Map[String, ErgoBox] = txs.flatMap(_.outputs).map(o => hex(o.id) -> o).toMap
    // NOT the panic net: this sys.error is raised inside the per-tx for-comprehension's
    // Try, so a missing box becomes that tx's Failure → Verdict(valid=false) — mirroring
    // the node, where checkBoxExistence failure invalidates the block (txBoxesToSpend).
    // The same Try also captures a Parameters table missing a key the context reads
    // lazily (e.g. 123/BlockVersion) — also a clean reject, not a panic.
    def resolveBox(id: Array[Byte]): ErgoBox = {
      val k = hex(id)
      createdOutputs.get(k).orElse(byBytes.get(k))
        .getOrElse(sys.error(s"box $k in neither block outputs nor vector boxes"))
    }

    // Parameters.parametersTable is Map[Byte, Int] — vector carries string keys.
    val params = new Parameters(header.height,
      paramsTable.map { case (k, v) => k.toByte -> v },
      ErgoValidationSettingsUpdate.empty)
    val preHeader = CPreHeader(header.version, header.parentId, header.timestamp, header.nBits,
      header.height, header.votes, header.powSolution.pk)
    val ctx = UpcomingStateContext(lastHeaders, None, preHeader, chainSettings.genesisStateDigest,
      params, ErgoValidationSettings.initial, VotingData.empty)
    implicit val verifier: ErgoInterpreter = ErgoInterpreter(params)

    // The execTransactions model: stateless then stateful, threaded accumulated cost.
    // A while loop (not Vector.takeWhile, whose predicate is evaluated STRICTLY over the
    // whole vector up front) mirrors the node's `cfor(_ < len && costResult.isValid)`
    // gate: the FIRST failing tx stops the loop, so the reason names the first failure
    // and no tx ever runs in a post-failure state the node would never reach.
    var acc = 0L
    var failure: Option[String] = None
    var i = 0
    while (i < txs.length && failure.isEmpty) {
      val tx = txs(i)
      val step: Try[Long] = for {
        _ <- tx.validateStateless().result.toTry
        toSpend = tx.inputs.map(in => resolveBox(in.boxId)).toIndexedSeq
        data    = tx.dataInputs.map(d => resolveBox(d.boxId)).toIndexedSeq
        newAcc <- tx.validateStateful(toSpend, data, ctx, acc).result.toTry.map(_.toLong)
      } yield newAcc
      step match {
        case Success(newAcc) => acc = newAcc
        case Failure(e)      => failure = Some(s"tx[$i]: ${e.getClass.getName}: ${e.getMessage}")
      }
      i += 1
    }

    failure match {
      case Some(reason) => Verdict(valid = false, postDigest = None, cost = None, reason = Some(reason))
      case None =>
        val postDigest = blockJson.hcursor.downField("header").get[String]("stateRoot").toOption
        Verdict(valid = true, postDigest = postDigest, cost = Some(acc), reason = None)
    }
  }

  /** One `santa-block` vector entry → actuals (shared block result shape). Decode
    * failures = harness self-contradiction → panicked (never aborts the file). */
  def blockEntry(e: Json): (String, Json) = {
    val c    = e.hcursor
    val name = c.get[String]("name").toOption.getOrElse("?")
    try {
      val block   = c.downField("block").focus.getOrElse(sys.error(s"'$name': missing block"))
      val headers = c.downField("headers").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      val table = c.downField("parameters").downField("table").focus
        .flatMap(_.asObject).getOrElse(sys.error(s"'$name': missing parameters.table"))
        .toMap.map { case (k, v) =>
          val id = k.toInt
          // Real Parameters ids live in 1..9 and 120..124 — all < 128. Guard the Byte
          // conversion so a hand-authored id > 127 panics loudly instead of wrapping.
          require(id >= 0 && id <= 127, s"param id $id outside Byte range [0,127]")
          id -> v.as[Int].fold(e => sys.error(s"param $k: $e"), identity)
        }
      val boxes = c.downField("boxes").focus.flatMap(_.asArray).getOrElse(Vector.empty).map { b =>
        val id = b.hcursor.get[String]("boxId").fold(e => sys.error(s"box id: $e"), identity)
        val bs = scorex.util.encode.Base16.decode(
          b.hcursor.get[String]("bytes").fold(e => sys.error(s"box hex: $e"), identity))
          .getOrElse(sys.error(s"box $id: hex decode failed"))
        id -> bs
      }
      val v = validate(block, headers, table, boxes)
      val base = Json.obj(
        "valid"       -> Json.fromBoolean(v.valid),
        "post_digest" -> v.postDigest.map(Json.fromString).getOrElse(Json.Null),
        "cost"        -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "error"       -> Json.Null)
      name -> v.reason.fold(base)(r => base.deepMerge(Json.obj("reason" -> Json.fromString(r))))
    } catch {
      case scala.util.control.NonFatal(t) =>
        name -> Json.obj(
          "valid" -> Json.Null, "post_digest" -> Json.Null, "cost" -> Json.Null,
          "error" -> Json.fromString("panicked"),
          "note"  -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"))
    }
  }
}
