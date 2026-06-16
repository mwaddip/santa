package santa

// Authored wire round-trip witness for the STypeVar UTF-8 byte-exactness fork (wire complement of
// AuthoredSTypeVarNameUtf8). Each entry's bytes_hex is the raw spliced (non-canonical) tree; its
// expected_bytes_hex is the JVM STRUCTURAL canonical (serializeErgoTree, re-encoding the lossy
// name). Non-identity round-trip — see docs/specs/wire-roundtrip-nonidentity.md.

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext
import sigma.ast.{BlockValue, ErgoTree, FuncValue, IntConstant, STypeVar, SType, ValDef, ValUse, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredWireStypeVarUtf8Roundtrip {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source   = "santa:authored-stypevar-name-utf8-roundtrip"
  val Op       = "STypeVar.name_utf8_roundtrip"

  private def boundNeverApplied(name: String): Value[SType] = {
    val tv = STypeVar(name)
    val poly = FuncValue(IndexedSeq(2 -> tv), ValUse(2, tv))
    BlockValue(IndexedSeq(ValDef(1, Seq(tv), poly)), IntConstant(5))
  }

  private def hexV3(root: Value[SType]): String =
    VersionContext.withVersions(V3, V3) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  /** Same-length valid placeholder, then splice `invalid` into both `0x67 <len> <name>` regions. */
  private def splicedHex(invalid: Array[Byte]): String = {
    val L = invalid.length
    val out = Base16.decode(hexV3(boundNeverApplied("a" * L))).get.clone()
    var i = 0; var count = 0
    while (i < out.length - 1) {
      if ((out(i) & 0xff) == 0x67 && (out(i + 1) & 0xff) == L) {
        System.arraycopy(invalid, 0, out, i + 2, L); count += 1; i += 2 + L
      } else i += 1
    }
    require(count == 2, s"expected 2 STypeVar name regions for L=$L, spliced $count")
    Base16.encode(out)
  }

  private val cases: Seq[(String, String)] = Seq(
    "ff"     -> "single invalid byte",
    "e282"   -> "truncated 3-byte sequence",
    "c080"   -> "overlong NUL encoding",
    "eda080" -> "UTF-16 surrogate (ill-formed in UTF-8) — the JVM-vs-Rust fork (JVM 1 / Rust 3)",
    "61ff62" -> "valid / invalid / valid")

  def extract(): Map[String, Json] = {
    val entries = cases.zipWithIndex.map { case ((hex, desc), i) =>
      val input    = splicedHex(Base16.decode(hex).get)
      val expected = WireCanonicalize.canonicalize("ErgoTree", input, V3, V3)
      Json.obj(
        "name"               -> Json.fromString(s"name-utf8-$hex-roundtrip#$i"),
        "kind"               -> Json.fromString("ErgoTree"),
        "source"             -> Json.fromString(Source),
        "description"        -> Json.fromString(
          s"ErgoTree carrying STypeVar name = invalid-UTF-8 [$hex] ($desc): JVM structurally " +
          "re-serializes the lossy-decoded name to canonical UTF-8 (U+FFFD). Non-identity round-trip; " +
          "sigma-rust from_utf8_lossy diverges on eda080 (3 vs 1) -> script-hash fork."),
        "bytes_hex"          -> Json.fromString(input),
        "expected_bytes_hex" -> Json.fromString(expected),
        "version"            -> Json.obj("activated" -> Json.fromInt(3), "ergoTree" -> Json.fromInt(3)))
    }
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireStypeVarUtf8Roundtrip", extract(), outDir)
}
