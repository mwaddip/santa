package santa

// Authored wire round-trip witness for the ErgoTree soft-fork degrade-vs-reject boundary — the
// ROUND-TRIP (degrade) side. bytes_hex is a SIZE-FLAGGED v2 tree (header 0x1a = v2 + size +
// const-seg) carrying ONE segregated constant of type SOption[SInt] (typeCode 0x28). The Option
// typecode trips CheckSerializableTypeCode (rule 1009) — a soft-forkable ValidationException — so
// the size flag lets `deserializeErgoTree` skip the declared body and wrap the WHOLE tree as
// UnparsedErgoTree, which re-serializes byte-IDENTICAL. Identity round-trip.
//
// This is the degrade side of the rule-1009 boundary. The REJECT twin — an SHeader-typed constant
// (typeCode 0x68), which rule 1009 does NOT special-case, so a direct SerializerException ESCAPES
// the soft-fork fallback and the JVM REJECTS — lands as a wire reject arm (separate vector).
//
// Distinct from AuthoredWireUnparsedSoftFork (unknown-OPCODE bodies, header 0x0b = v3): the trigger
// here is a non-serializable TYPE CODE in a segregated constant, not an unknown opcode, and the
// header is v2 + seg. Confirmed live on the oracle (SoftForkUnparsedTreeSpike): root=Left/UNPARSED,
// echo on both .bytes and serializeErgoTree.
//
// extract() RE-DERIVES the blessing (asserts genuinely UNPARSED + JVM canonicalize == input) so a
// sigma-state change that parsed the SOption constant, or stopped preserving, fails loud.

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext

object AuthoredWireUnparsedSoftForkOptionConstant {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3 — the soft-fork must be active to degrade
  val ErgoTreeV: Byte = 2                                // header 0x1a encodes ErgoTree version 2
  val Source = "santa:authored-unparsed-soft-fork-option-constant"
  val Op     = "ErgoTree.unparsed_soft_fork_option_constant"

  // header 0x1a = v2 + size bit + const-seg; size 06; 1 segregated constant; type 0x28 (SOption[SInt]);
  // value 01 0a (Some, zigzag(5)); body 73 00. The SOption typecode trips rule 1009 -> soft-fork wrap.
  private val Hex = "1a060128010a7300"

  def extract(): Map[String, Json] = {
    VersionContext.withVersions(Activated, ErgoTreeV) {
      val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(Hex).get)
      require(tree.root.isLeft,
        s"$Hex must deserialize to an UnparsedErgoTree (soft-fork) — got a parsed tree; vector is meaningless")
      val canon = WireCanonicalize.canonicalize("ErgoTree", Hex, Activated, ErgoTreeV)
      require(canon == Hex,
        s"JVM must PRESERVE the unparsed tree $Hex byte-identically (identity round-trip) — got $canon")
    }
    val entry = Json.obj(
      "name"        -> Json.fromString("unparsed-soft-fork-option-constant-roundtrip#0"),
      "kind"        -> Json.fromString("ErgoTree"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(
        "Size-flagged ErgoTree (header 0x1a = v2 + size + const-seg) with one segregated constant of " +
        "type SOption[SInt] (typeCode 0x28), value Some(5), body 73 00: the Option typecode trips " +
        "CheckSerializableTypeCode (rule 1009) — a soft-forkable ValidationException — so the size flag " +
        "wraps the whole tree as UnparsedErgoTree and the JVM re-serializes byte-IDENTICAL. Identity " +
        "round-trip; the DEGRADE side of the rule-1009 boundary (the SHeader-constant REJECT twin is a " +
        "separate wire reject arm). An impl that rejects this (no soft-fork degrade) or substitutes a " +
        "Const(true) placeholder diverges -> different propositionBytes -> different boxId."),
      "bytes_hex"   -> Json.fromString(Hex),
      // expected_bytes_hex OMITTED: identity round-trip (JVM preserves the raw bytes).
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
    SpecExtract.writeStaging("AuthoredWireUnparsedSoftForkOptionConstant", extract(), outDir)
}
