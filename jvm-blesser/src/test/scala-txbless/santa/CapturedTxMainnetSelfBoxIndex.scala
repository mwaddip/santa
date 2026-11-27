package santa

// CapturedTxMainnetSelfBoxIndex: the one mainnet spend below the JIT activation whose verdict depends on
// CONTEXT.selfBoxIndex answering -1 there (sigma-state `CContext.selfBoxIndex`, "this used to be a bug in v4.x
// (#603)"). Box 26329c1c... carries the only pre-activation tree that reads selfBoxIndex (SANTA's scan of every
// output in blocks 1..889,855). Tx 9302a298... at height 342,964 (block version 1, activated 0) spends it as
// INPUTS(2) with an EMPTY proof:
//
//   { val pk = proveDlog(SELF.R6[GroupElement].get)
//     sigmaProp(HEIGHT < SELF.R7[Int].get && OUTPUTS.exists { b => b.value >= SELF.R5[Long].get &&
//               b.R4[Int].get == CONTEXT.selfBoxIndex && b.propositionBytes == pk.propBytes }) || pk }
//
// Output 0 carries R4 = -1. So the left arm holds only while selfBoxIndex answers -1, and the empty proof passes.
//
// Two entries:
//  - the CAPTURED spend, which the oracle must accept (chain history), into transaction/v4/captured/;
//  - its AUTHORED twin, with the same bytes and context but the voted block version at 3 (activated 2). There
//    selfBoxIndex answers the real index (2), the proposition reduces to pk, and the empty proof fails. The twin goes
//    into transaction/v5/authored/. It is the replay without the v4.x behaviour, and the blesser requires the
//    rejection to name input 2.
// Every output's creation height (342,935) is the inputs' highest, so the monotonic creation-height rule (block
// version 3 and up) passes on the twin.
//
// Input: docs/findings/mainnet-selfboxindex-342964/capture.json (local-only), built by the capture.py beside it.
// Every byte string in it was verified against its id. A re-bless reaches no node.

import io.circe.Json
import io.circe.parser.parse
import santa.runner.TxEngine

object CapturedTxMainnetSelfBoxIndex {
  val BlessedBy   = "jvm:ergo-core-6.0.8-validateStateful"
  val CapturePath = "../docs/findings/mainnet-selfboxindex-342964/capture.json"
  val TwinBlockVersion = 3

  final case class Capture(j: Json) {
    private val c = j.hcursor
    private def str(k: String): String = c.get[String](k).fold(e => sys.error(s"capture.$k: $e"), identity)
    private def strs(k: String): List[String] = c.downField(k).as[List[String]].fold(e => sys.error(s"capture.$k: $e"), identity)
    val height: Int              = c.get[Int]("height").fold(e => sys.error(s"capture.height: $e"), identity)
    val txId: String             = str("txId")
    val activated: Int           = c.get[Int]("activated").fold(e => sys.error(s"capture.activated: $e"), identity)
    val txHex: String            = str("tx_bytes_hex")
    val inputBoxes: List[String] = strs("input_boxes_hex")
    val dataBoxes: List[String]  = strs("data_input_boxes_hex")
    val headers: List[String]    = strs("headers_hex").take(AuthoredTxStorageRent.ScriptHeaders)
    val preHeader: Json          = c.downField("preHeader").focus.get
    val parameters: Json         = c.downField("parameters").focus.get
  }

  def capture(): Capture = {
    val s = scala.io.Source.fromFile(CapturePath)
    val raw = try s.mkString finally s.close()
    Capture(parse(raw).fold(e => sys.error(s"$CapturePath: $e"), identity))
  }

  private def entry(cap: Capture, name: String, source: String, description: String, preHeader: Json,
                    parameters: Json, activated: Int, v: TxEngine.Verdict): Json =
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString(source),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(cap.txHex),
      "input_boxes_hex"      -> Json.arr(cap.inputBoxes.map(Json.fromString): _*),
      "data_input_boxes_hex" -> Json.arr(cap.dataBoxes.map(Json.fromString): _*),
      "headers_hex"          -> Json.arr(cap.headers.map(Json.fromString): _*),
      "preHeader"            -> preHeader,
      "parameters"           -> parameters,
      "context"              -> Json.obj("height" -> Json.fromInt(cap.height)),
      "version"              -> Json.obj("activated" -> Json.fromInt(activated), "ergoTree" -> Json.fromInt(0)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))

  private def envelope(op: String, e: Json): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString(op),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries"    -> Json.arr(e))

  def blessAll(): Seq[(String, Json)] = {
    val cap = capture()
    require(cap.activated == 0, s"capture: activated ${cap.activated}, expected 0")

    val captured = TxEngine.validateBytes(cap.txHex, cap.inputBoxes, cap.dataBoxes, cap.headers, cap.preHeader, cap.parameters)
    if (!captured.valid)
      sys.error(s"CapturedTxMainnetSelfBoxIndex: oracle REJECTED mainnet tx ${cap.txId}: ${captured.reason.getOrElse("?")}")
    require(captured.cost.isDefined, "captured spend: valid but no cost")

    val twinPreHeader  = cap.preHeader.deepMerge(Json.obj("version" -> Json.fromInt(TwinBlockVersion)))
    val twinParameters = cap.parameters.deepMerge(Json.obj("blockVersion" -> Json.fromInt(TwinBlockVersion)))
    val twin = TxEngine.validateBytes(cap.txHex, cap.inputBoxes, cap.dataBoxes, cap.headers, twinPreHeader, twinParameters)
    if (twin.valid)
      sys.error(s"CapturedTxMainnetSelfBoxIndex: the twin at block version $TwinBlockVersion was ACCEPTED (cost ${twin.cost})")
    val reason = twin.reason.getOrElse(sys.error("twin: rejected without a reason"))
    // ergo's message names the failing input as "<tx id>: #<index> => ..." (ErgoTransaction's verification error).
    require(reason.contains(s"${cap.txId}: #2 => "), s"twin: the rejection does not name input 2: $reason")

    val slug = s"selfboxindex-${cap.height}"
    Seq(
      s"transaction/v4/captured/$slug.json" -> envelope(s"tx:captured:$slug", entry(cap,
        name = s"mainnet-$slug-${cap.txId.take(8)}",
        source = s"mainnet:$slug@${cap.height}:${cap.txId}",
        description =
          s"Mainnet tx ${cap.txId} at height ${cap.height} (block version 1, activated 0). INPUTS(2) carries the only " +
          "pre-activation tree that reads CONTEXT.selfBoxIndex: sigmaProp(HEIGHT < R7 && OUTPUTS.exists(b => b.value >= R5 " +
          "&& b.R4[Int].get == CONTEXT.selfBoxIndex && b.propositionBytes == pk.propBytes)) || pk, with pk = " +
          "proveDlog(R6). Its proof is EMPTY and output 0 carries R4 = -1, so the spend is valid only because " +
          "selfBoxIndex answers -1 below activated 2 (sigma-state CContext.selfBoxIndex, the v4.x bug #603). Twin: " +
          s"transaction/v5/authored/$slug-activated2.json.",
        preHeader = cap.preHeader, parameters = cap.parameters, activated = cap.activated, v = captured)),
      s"transaction/v5/authored/$slug-activated2.json" -> envelope(s"tx:authored:$slug-activated2", entry(cap,
        name = s"$slug-activated2-reject",
        source = s"santa:authored-$slug-activated2",
        description =
          s"The captured mainnet tx ${cap.txId} (transaction/v4/captured/$slug.json) with the same bytes and context, " +
          s"but block version $TwinBlockVersion (parameters and pre-header), so activated 2. selfBoxIndex now answers " +
          "the real index 2, no output's R4 equals it, the proposition reduces to proveDlog(R6), and INPUTS(2)'s empty " +
          "proof fails: invalid. Every output's creation height (342,935) is the inputs' highest, so the monotonic " +
          "creation-height rule passes. This is the replay of the captured spend without the v4.x behaviour.",
        preHeader = twinPreHeader, parameters = twinParameters, activated = TwinBlockVersion - 1, v = twin)))
  }

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
