package santa

// ─────────────────────────────────────────────────────────────────────────────
// CapturedTxErgots — bless the ergots-session stateful fixtures into
// `santa-transaction/v1` vectors. Three testnet accept-vectors:
//
//   getvarfrominput-92847 — the headline: input0's script reads ANOTHER input's
//     context variable (SContext.getVarFromInput). validateStateful must populate
//     `inputExtensions` from the tx's per-input spending proofs; ergots' bug here
//     (OptionGet:None) is a tx-tier context-construction fault eval/wire can't catch.
//   multi-input-10-402900 / multi-input-3-402800 — baseline multi-input accepts
//     (10 / 3 signed inputs), pinning the per-input verify loop.
//
// Source = the ergots stateful fixtures (hex), captured under docs/findings/. We
// decode the hex to the node-API JSON TxEngine consumes (ApiCodecs, symmetric with
// its Decoder), then drive TxEngine.validate at the seed height / activated=3 — the
// SAME height-synthetic context the original 4 seeds use (empty headers, synthetic
// preHeader, testnet launch params). All three are chain-valid, so all bless `accept`;
// FAIL-LOUD on a reject (a capture/recipe or context-model fault, never a vector).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import io.circe.syntax._
import io.circe.parser.parse

import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.SigmaSerializer
import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.mempool.ErgoTransactionSerializer

object CapturedTxErgots extends ApiCodecs {
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-validateStateful"

  /** A seed: its vector slug, its findings sub-dir, the capture height, and the
    * activated script version (all three are blockVersion-4 → activated 3). */
  private final case class Seed(slug: String, dir: String, height: Int, activated: Int = 3, ergoTree: Int = 3)

  private val Seeds: Seq[Seed] = Seq(
    Seed("getvarfrominput-92847",  "testnet-getvarfrominput", 92847),
    Seed("multi-input-10-402900",  "testnet-multi-input-10",  402900),
    Seed("multi-input-3-402800",   "testnet-multi-input-3",   402800))

  private def readFile(p: String): String = {
    val s = scala.io.Source.fromFile(p); try s.mkString finally s.close()
  }

  /** Read one seed's hex fixture, decode to node-API JSON, drive TxEngine, bless. */
  def blessSeed(seed: Seed): (String, Json) = {
    val path = s"../docs/findings/${seed.dir}/ergots-stateful-fixture.json"
    val fx = parse(readFile(path)).fold(e => sys.error(s"[${seed.slug}] fixture parse: $e"), identity)
    val c  = fx.hcursor
    val txHex   = c.get[String]("txBytesHex").fold(e => sys.error(s"[${seed.slug}] txBytesHex: $e"), identity)
    val inHexes = c.downField("inputBoxesHex").as[List[String]].fold(e => sys.error(s"[${seed.slug}] inputBoxesHex: $e"), identity)
    val dtHexes = c.downField("dataInputBoxesHex").as[List[String]].getOrElse(Nil)

    val (txJson, inBoxes, dtBoxes) =
      VersionContext.withVersions(seed.ergoTree.toByte, seed.ergoTree.toByte) {
        def boxJson(hex: String): Json =
          ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(hex).get)).asJson
        val tx = ErgoTransactionSerializer.parseBytes(Base16.decode(txHex).get)
        (tx.asJson, inHexes.map(boxJson), dtHexes.map(boxJson))
      }

    val verdict = santa.runner.TxEngine.validate(txJson, inBoxes, dtBoxes, seed.height, seed.activated)
    if (!verdict.valid)
      sys.error(s"CapturedTxErgots[${seed.slug}]: oracle REJECTED a chain-valid tx — synthetic context " +
        s"insufficient or recipe bug. reason=${verdict.reason.getOrElse("<none>")}")
    val cost = verdict.cost.getOrElse(sys.error(s"[${seed.slug}] valid:true but no cost"))

    val entry = Json.obj(
      "name"           -> Json.fromString(seed.slug),
      "source"         -> Json.fromString(s"testnet:${seed.slug}@${seed.height}"),
      "tx"             -> txJson,
      "inputBoxes"     -> Json.arr(inBoxes: _*),
      "dataInputBoxes" -> Json.arr(dtBoxes: _*),
      "context"        -> Json.obj("height" -> Json.fromInt(seed.height)),
      "version"        -> Json.obj("activated" -> Json.fromInt(seed.activated), "ergoTree" -> Json.fromInt(seed.ergoTree)),
      "expected"       -> Json.obj("valid" -> Json.fromBoolean(true), "cost" -> Json.fromLong(cost), "reason" -> Json.Null))
    val env = Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString(s"tx:captured:${seed.slug}"),
      "blessed_by" -> Json.fromString(BlessedBy),
      "entries"    -> Json.arr(entry))
    seed.slug -> env
  }

  def blessAll(): Seq[(String, Json)] = Seeds.map(blessSeed)

  def writeVectors(outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    blessAll().foreach { case (slug, env) =>
      java.nio.file.Files.write(outDir.resolve(s"$slug.json"),
        env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
