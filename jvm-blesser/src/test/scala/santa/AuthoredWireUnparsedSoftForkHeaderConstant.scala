package santa

// Authored wire REJECT arm for the ErgoTree soft-fork degrade-vs-reject boundary — the REJECT side.
// bytes_hex is a SIZE-FLAGGED v2 tree (header 0x1a = v2 + size + const-seg) carrying ONE segregated
// constant of type SHeader (typeCode 0x68 = 104). Unlike the SOption typecode (rule 1009's special
// case → soft-fork degrade), SHeader is NEITHER == OptionTypeCode NOR > LastDataType (111), so rule
// 1009 (CheckSerializableTypeCode) does NOT fire — a direct SerializerException ("Not defined
// DataSerializer for type SHeader") ESCAPES the UnparsedErgoTree fallback and the JVM REJECTS the
// tree, even though it is size-flagged.
//
// The REJECT twin of AuthoredWireUnparsedSoftForkOptionConstant (the degrade side); together they pin
// the exact rule-1009 boundary. Bytes from ergots' sheader-constants-v2-header-literal fixture;
// confirmed live (SoftForkUnparsedTreeSpike): deserialize THREW SerializerException.
//
// This is a santa-wire/v1 REJECT entry: `error: "errored"`, NO expected_bytes_hex (a reject has no
// canonical output). grade_wire's reject arm grades an `errored` actual nice; an impl that PRODUCES
// bytes (degrades to Unparsed + round-trips) is the over-accept (coal).
//
// extract() RE-DERIVES the blessing (asserts LenientErgoTree.deserialize THROWS a SerializerException
// naming SHeader) so a sigma-state change that started accepting/degrading SHeader constants fails loud.

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext

object AuthoredWireUnparsedSoftForkHeaderConstant {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3
  val ErgoTreeV: Byte = 2                                // header 0x1a encodes ErgoTree version 2
  val Source = "santa:authored-unparsed-soft-fork-header-constant"
  val Op     = "ErgoTree.unparsed_soft_fork_header_constant"

  // header 0x1a = v2 + size + seg; size db01 (VLQ 219); 1 constant; type 0x68 (SHeader); <Header value>;
  // body 73 00 (ConstantPlaceholder id 0). SHeader has no DataSerializer and is not rule-1009-special-
  // cased → JVM REJECTS at parse (a SerializerException escapes the soft-fork fallback).
  private val Hex =
    "1adb01016802000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000c0843d0000000000000000000000000000000000000000000000000000000000000000070239b8010000000000000000000000000000000000000000000000000000000000000000000000000000000001000000017300"

  def extract(): Map[String, Json] = {
    VersionContext.withVersions(Activated, ErgoTreeV) {
      val (threw, detail) =
        try { sigma.santa.LenientErgoTree.deserialize(Base16.decode(Hex).get); (false, "deserialized — no throw") }
        catch { case t: Throwable => (true, s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}") }
      require(threw && detail.contains("SerializerException") && detail.contains("SHeader"),
        s"$Hex must be REJECTED at deserialize by a SerializerException naming SHeader (the typecode " +
        s"escapes the soft-fork fallback) — got: $detail; reject vector is meaningless")
    }
    val entry = Json.obj(
      "name"        -> Json.fromString("unparsed-soft-fork-header-constant-reject#0"),
      "kind"        -> Json.fromString("ErgoTree"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(
        "Size-flagged ErgoTree (header 0x1a = v2 + size + const-seg) with one segregated constant of " +
        "type SHeader (typeCode 0x68 = 104): unlike the SOption typecode (rule 1009's special case → " +
        "soft-fork degrade), SHeader is neither == OptionTypeCode nor > LastDataType (111), so rule 1009 " +
        "does NOT fire — a direct SerializerException (\"Not defined DataSerializer for type SHeader\") " +
        "ESCAPES the UnparsedErgoTree fallback and the JVM REJECTS, even size-flagged. The REJECT side of " +
        "the rule-1009 boundary (twin of unparsed_soft_fork_option_constant). An impl that instead " +
        "degrades it to Unparsed (round-trips, produces bytes) OVER-ACCEPTS a tree the JVM rejects."),
      "bytes_hex"   -> Json.fromString(Hex),
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
    SpecExtract.writeStaging("AuthoredWireUnparsedSoftForkHeaderConstant", extract(), outDir)
}
