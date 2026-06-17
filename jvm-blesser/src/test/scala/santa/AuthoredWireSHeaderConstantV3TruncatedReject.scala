package santa

// Authored wire REJECT arm — the TRUNCATED-Header-constant sibling over-accept (sigma-rust vector ask (b),
// 2026-06-17, off sigma-rust-header-pk-on-curve-overaccept-response.md §3b). Proves the C-lite degrade-gate
// fix beyond the EC-point case.
//
// This is the VALID v3 SHeader accept tree with the last 22 bytes dropped — cutting MID-FIELD into the
// Header constant's AutolykosSolution pk (the pk+nonce are the last 41 bytes of the Header value). The
// Header constant deserialize (ErgoHeader.sigmaSerializer.parse) runs out of bytes and throws an EOF/underflow
// — a HARD, non-ValidationException error — so the JVM REJECTS (it does NOT degrade: truncation is not a
// soft-forkable ValidationException, unlike the position-limit rule-1014 case which DOES degrade).
//
// eni pre-fix OVER-ACCEPTS: the size-flagged ErgoTree::parse_with degrade gate swallows the truncation error
// and echoes the raw (truncated) bytes (sigma-rust's audit); post-C-lite eni rejects. santa-wire/v1 REJECT
// entry: error "errored", no expected_bytes_hex.
//
// NOTE the JVM mislabels the EOF — the inner catch re-labels the underlying IllegalArgumentException as a
// "tree version" SerializerException (same cosmetic wart as the malformed-pk reject). The bless asserts the
// LOAD-BEARING property instead: the VALID base accepts, the truncated tree THROWS, and the throw is NOT a
// ValidationException (so the JVM rejects rather than degrades — the (b)-vs-(c) discriminator).
//
// extract() RE-DERIVES the differential so a sigma-state change that started degrading truncated constants,
// or stopped accepting the valid base, fails loud.

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext

object AuthoredWireSHeaderConstantV3TruncatedReject {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3
  val ErgoTreeV: Byte = VersionContext.V6SoftForkVersion // 3
  val Source = "santa:authored-sheader-constant-v3-truncated-reject"
  val Op     = "ErgoTree.sheader_constant_v3_truncated_reject"

  private val ValidHex = AuthoredWireSHeaderConstantV3Accept.Hex // 1bdb01 (header + size VLQ 219) + 219-byte content
  private val DropBytes = 22 // drop the last 22 content bytes → cuts mid-field into the Header constant's pk
  // Truncate the CONTENT and rewrite the size VLQ to MATCH the new length (db01=219 -> c501=197). The size MUST
  // match: otherwise a lenient impl's degrade-SKIP EOFs on a declared size that exceeds the bytes and it rejects
  // for the wrong reason (FALSE-GREEN). With the size matching, the skip succeeds → the impl degrades + echoes
  // (the over-accept), while the JVM still EOFs in the Header constant parse (the structure exceeds the bytes).
  private val Content    = ValidHex.substring(6) // after 1b + db01 (3 bytes = 6 hex)
  private val NewContent = Content.substring(0, Content.length - DropBytes * 2)
  private def vlq(n: Int): String = {
    val w = sigma.serialization.SigmaSerializer.startWriter(); w.putUInt(n.toLong)
    scorex.util.encode.Base16.encode(w.toBytes)
  }
  val Hex = "1b" + vlq(NewContent.length / 2) + NewContent

  private def isValidationException(t: Throwable): Boolean =
    t.getClass.getName.contains("ValidationException") ||
      Option(t.getCause).exists(c => c.getClass.getName.contains("ValidationException"))

  def extract(): Map[String, Json] = {
    VersionContext.withVersions(Activated, ErgoTreeV) {
      val valid = sigma.santa.LenientErgoTree.deserialize(Base16.decode(ValidHex).get)
      require(valid.root.isRight,
        s"VALID base $ValidHex must parse (root=Right) — the truncation differential is meaningless otherwise")
      val (threw, isVE, detail) =
        try { sigma.santa.LenientErgoTree.deserialize(Base16.decode(Hex).get); (false, false, "deserialized — no throw") }
        catch { case t: Throwable => (true, isValidationException(t), s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}") }
      require(threw && !isVE,
        s"$Hex (valid base truncated by $DropBytes bytes) must be REJECTED by a non-ValidationException (a hard EOF, " +
        s"NOT a soft-forkable degrade) — got threw=$threw isValidationException=$isVE ($detail)")
    }
    val entry = Json.obj(
      "name"        -> Json.fromString("sheader-constant-v3-truncated-reject#0"),
      "kind"        -> Json.fromString("ErgoTree"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(
        "v3 SHeader-as-constant (header 0x1b) with the Header constant truncated 22 bytes mid-pk and the size " +
        "VLQ rewritten to match (so a lenient degrade-skip echoes rather than EOF-rejecting for the wrong reason). " +
        "The Header deserialize runs out of bytes and throws a hard " +
        "EOF/underflow (a non-ValidationException) so the JVM REJECTS — it does NOT degrade (truncation is not " +
        "soft-forkable, unlike the position-limit rule-1014 case). The sibling of the malformed-pk reject on " +
        "the same size-flagged ErgoTree-constant degrade gate. An impl whose lenient ErgoTree degrade swallows " +
        "the truncation error and echoes the raw bytes (e.g. sigma-rust pre-C-lite) OVER-ACCEPTS a tree the JVM " +
        "rejects -> crafted-bytes over-accept."),
      "bytes_hex"   -> Json.fromString(Hex),
      "error"       -> Json.fromString("errored"),
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
    SpecExtract.writeStaging("AuthoredWireSHeaderConstantV3TruncatedReject", extract(), outDir)
}
