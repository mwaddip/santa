package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored STypeVar name UTF-8 leniency witness (sigma-rust vector ask, 2026-06-15).
//
// The 3rd STypeVar Rust↔JVM deserialize fork, beyond ask-23's LENGTH bound. The JVM
// `TypeSerializer.deserialize` reads the name as `new String(r.getBytes(len), UTF_8)` —
// a LOSSY decode that NEVER throws: malformed bytes become U+FFFD. So a type-var name
// carrying non-UTF-8 bytes ACCEPTS on the JVM, while sigma-rust (`new_from_bytes` ->
// UTF-8 check) and ergots (`TextDecoder fatal:true`) STRICT-REJECT it — a consensus fork.
//
// These witnesses test PARSE-ACCEPTANCE only: the type-var name is erased at eval, so the
// tree returns Int 5 @ cost 13 regardless of the name bytes. (The deeper question —
// whether an impl's lossy decode produces byte-identical canonical UTF-8 to the JVM's
// U+FFFD substitution — is the wire ROUND-TRIP vector, not this eval one.)
//
// Oracle-confirmed (StypeVarUtf8Spike, sigma-state 6.0.3, Java 17): all 5 sequences below
// ACCEPT -> Int 5 @ cost 13.
//
// STypeVar(String) always serializes VALID UTF-8, so the invalid name bytes are spliced
// into both serialized `0x67 <len> <name>` regions of the "a"*len base tree (the
// nTpeArgs-128 splice idiom), then deserialized (the JVM lossy-decodes).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{BlockValue, ErgoTree, FuncValue, IntConstant, STypeVar, SType, ValDef, ValUse, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredSTypeVarNameUtf8 {

  /** Pinned target: full v6 (activated=3, ergoTree=3) — type-var serialization needs v3. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-stypevar-name-utf8"
  val Op     = "STypeVar.name_utf8_leniency"

  /** Closed tree (bound-never-applied) → the dummy input is ignored. */
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `{ val f[T] = {(x: T) => x}; 5 }` with the type-var name = `name`. */
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

  /** Build with a same-length valid placeholder, then splice `invalid` into both
    * `0x67 <len> <name>` STypeVar name regions. The length byte is preserved (the splice
    * is byte-for-byte same length), so the tree's size bit stays valid. */
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

  /** (name-bytes hex, human description) — the sigma-rust ask's suggested sequences. */
  private val cases: Seq[(String, String)] = Seq(
    "ff"     -> "single invalid byte",
    "e282"   -> "truncated 3-byte sequence",
    "c080"   -> "overlong NUL encoding",
    "eda080" -> "UTF-16 surrogate (ill-formed in UTF-8)",
    "61ff62" -> "valid / invalid / valid")

  def extract(): Map[String, Json] = {
    val entries = cases.zipWithIndex.map { case ((hex, desc), i) =>
      SpecExtract.authoredEntry(Op,
        s"{ val f[T] = {(x: T) => x}; 5 }  // STypeVar name = invalid-UTF-8 [$hex] ($desc): JVM " +
        "TypeSerializer lossy-decodes (new String(_, UTF_8) never throws) -> name accepted as U+FFFD " +
        "-> Int 5 (name erased at eval). sigma-rust/ergots strict-reject non-UTF-8 -> errored. The " +
        "CHARSET fork, distinct from ask-23's length bound; byte-exactness of the U+FFFD canonical form " +
        "is the wire round-trip, not this accept vector.",
        splicedHex(Base16.decode(hex).get), s"name-utf8-$hex-accept#$i", dummyInput, V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredSTypeVarNameUtf8", extract(), outDir)
}
