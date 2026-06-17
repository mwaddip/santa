package santa

// Authored wire REJECT arm — the BOX path for the SHeader soft-fork over-accept. The ErgoTree-kind reject
// (AuthoredWireUnparsedSoftForkHeaderConstant) grades impls via their LENIENT tree round-trip
// (sigma_parse_bytes_lenient), which bare develop lacks (its wire ErgoTree arm is not-impl). This Box kind
// drives the STRICT ErgoBox.sigmaSerializer parse (box -> tree) that EVERY impl runs on the boxId path, so
// it GRADES the strict-path over-accept the ErgoTree arm misses (e.g. develop's production sigma_parse).
//
// bytes_hex is a real JVM ErgoBox frame (value/height/tokens/registers/txId/index) whose propositionBytes
// are the SHeader-constant tree (a v2 + size + seg tree with one segregated SHeader constant, typeCode
// 0x68). The box parse reads the propBytes as an ErgoTree and the SHeader DataSerializer throws a direct
// SerializerException (SHeader is not rule-1009-special-cased, so it escapes the soft-fork UnparsedErgoTree
// fallback) -> the JVM REJECTS the box. An impl that degrades the tree to Unparsed accepts a box the JVM
// rejects -> a crafted-bytes boxId/consensus over-accept. This is a santa-wire/v1 REJECT entry.
//
// Construction: mint a real box frame around a parseable (size-flagged, degrading) 0xfd tree, then SWAP its
// propBytes for the SHeader tree — the box parse dies at the tree step, so the version-independent tail is
// reused verbatim. extract() RE-DERIVES the blessing (ErgoBox parse THROWS a SerializerException naming SHeader).

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate}

object AuthoredWireBoxSoftForkHeaderReject {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3
  val ErgoTreeV: Byte = 2                                // the SHeader tree is v2 (header 0x1a)
  val Source = "santa:authored-softfork-box-header-constant-reject"
  val Op     = "Box.softfork_header_constant_reject"

  private val SHeaderTreeHex = AuthoredWireUnparsedSoftForkHeaderConstant.Hex
  private val FrameTreeHex   = "0b01fd" // a parseable (size-flagged, degrading) tree to mint a box frame
  private val zerosTxId      = scorex.util.bytesToId(Array.fill(32)(0.toByte))

  def extract(): Map[String, Json] = {
    // Mint a real JVM box frame (value 1000000 = `c0843d`, height 0, no tokens/registers, zeros txId,
    // index 0) around the degrading 0xfd tree; keep its value + tail and swap in the SHeader tree.
    val frameHex = VersionContext.withVersions(Activated, Activated) {
      val frameTree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(FrameTreeHex).get)
      val frameBox  = new ErgoBoxCandidate(1000000L, frameTree, 0).toBox(zerosTxId, 0.toShort)
      Base16.encode(ErgoBox.sigmaSerializer.toBytes(frameBox))
    }
    require(frameHex.substring(6).startsWith(FrameTreeHex),
      s"frame box propBytes must follow the value VLQ (c0843d, 6 hex chars) — got $frameHex")
    val boxHex = frameHex.substring(0, 6) + SHeaderTreeHex + frameHex.substring(6 + FrameTreeHex.length)

    // Bless: the JVM box parse must THROW on the SHeader tree (escapes the soft-fork fallback), under the
    // SHeader tree's own (activated 3, ergoTree 2) context.
    VersionContext.withVersions(Activated, ErgoTreeV) {
      val (threw, detail) =
        try { ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(boxHex).get)); (false, "parsed — no throw") }
        catch { case t: Throwable => (true, s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}") }
      require(threw && detail.contains("SerializerException") && detail.contains("SHeader"),
        s"ErgoBox parse must be REJECTED by a SerializerException naming SHeader (the SHeader-typed propBytes " +
        s"constant escapes the soft-fork fallback) — got: $detail; reject vector is meaningless")
    }

    val entry = Json.obj(
      "name"        -> Json.fromString("box-softfork-header-constant-reject#0"),
      "kind"        -> Json.fromString("Box"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(
        "ErgoBox whose propositionBytes are a size-flagged ErgoTree with one segregated SHeader constant " +
        "(typeCode 0x68): the box parse reads the propBytes as an ErgoTree and the SHeader DataSerializer " +
        "throws a SerializerException (SHeader is not rule-1009-special-cased, so it escapes the soft-fork " +
        "UnparsedErgoTree fallback) -> the JVM REJECTS the box. The STRICT box->tree path every impl runs for " +
        "boxId, so this grades the strict-sigma_parse over-accept the bare ErgoTree (lenient) arm misses (e.g. " +
        "develop). An impl that degrades the tree to Unparsed accepts a box the JVM rejects -> a crafted-bytes " +
        "boxId/consensus over-accept. Twin of ErgoTree.unparsed_soft_fork_header_constant on the box path."),
      "bytes_hex"   -> Json.fromString(boxHex),
      "error"       -> Json.fromString("errored"),
      // no expected_bytes_hex: a reject has no canonical round-trip output.
      "version"     -> Json.obj(
        "activated" -> Json.fromInt(Activated.toInt),
        "ergoTree"  -> Json.fromInt(ErgoTreeV.toInt)))
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "entries"    -> Json.arr(entry)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireBoxSoftForkHeaderReject", extract(), outDir)
}
