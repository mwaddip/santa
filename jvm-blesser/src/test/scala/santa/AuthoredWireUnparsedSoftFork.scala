package santa

// Authored wire round-trip witness for the unparsed soft-fork ErgoTree boxId fork (vixen's
// `ergo_tree.rs:197` flag). Each entry's bytes_hex is a SIZE-FLAGGED tree whose body is an
// unknown opcode (0xfd): the JVM wraps it as UnparsedErgoTree and STRUCTURALLY re-serializes it
// byte-IDENTICAL (it preserves the declared-size body — there is no parsed structure to re-encode).
// So the round-trip is IDENTITY: expected_bytes_hex is OMITTED (absent => round-trip-to-self).
//
// An impl that instead substitutes a `Const(true)` placeholder + empty constants when wrapping the
// unparseable body diverges -> different propositionBytes -> different boxId -> UTXO-digest fork
// (proven consensus-reachable: SoftForkBoxLivenessSpike showed the JVM box embeds the raw tree bytes,
// so boxId hashes them). See docs/findings/wire-unparsed-soft-fork-boxid.md.
//
// extract() RE-DERIVES the blessing (asserts each input is genuinely UNPARSED and that the JVM
// canonicalize == input) so a sigma-state change that parsed 0xfd, or stopped preserving, fails loud.

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext

object AuthoredWireUnparsedSoftFork {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source   = "santa:authored-unparsed-soft-fork-roundtrip"
  val Op       = "ErgoTree.unparsed_soft_fork_roundtrip"

  // (input hex, short label, body-shape note). header 0x0b = v3 + size bit, no const-seg.
  // 0xfd is an unknown opcode (sigma-state rule 1002) -> ValidationException -> size-present wrap.
  private val cases: Seq[(String, String, String)] = Seq(
    ("0b01fd",     "1-byte body",
      "1-byte declared body (the minimal soft-fork tree)"),
    ("0b03fd0102", "3-byte body",
      "3-byte declared body — the full declared region (fd 01 02) is preserved, not just the opcode"))

  def extract(): Map[String, Json] = {
    val entries = cases.zipWithIndex.map { case ((hex, label, note), i) =>
      VersionContext.withVersions(V3, V3) {
        val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(hex).get)
        require(tree.root.isLeft,
          s"$hex must deserialize to an UnparsedErgoTree (soft-fork) — got a parsed tree; vector is meaningless")
        val canon = WireCanonicalize.canonicalize("ErgoTree", hex, V3, V3)
        require(canon == hex,
          s"JVM must PRESERVE the unparsed tree $hex byte-identically (identity round-trip) — got $canon")
      }
      Json.obj(
        "name"        -> Json.fromString(s"unparsed-soft-fork-$label-roundtrip#$i".replace(" ", "-")),
        "kind"        -> Json.fromString("ErgoTree"),
        "source"      -> Json.fromString(Source),
        "description" -> Json.fromString(
          s"Size-flagged ErgoTree (header 0x0b = v3 + size bit) whose body is unknown opcode 0xfd " +
          s"($note): the JVM wraps it as UnparsedErgoTree and re-serializes byte-IDENTICAL (preserves " +
          "the declared-size body). Identity round-trip. An impl that substitutes a Const(true) " +
          "placeholder + empty constants (the soft-fork forward-compat path) diverges -> different " +
          "propositionBytes -> different boxId -> UTXO-digest/consensus fork."),
        "bytes_hex"   -> Json.fromString(hex),
        // expected_bytes_hex OMITTED: identity round-trip (JVM preserves the raw bytes).
        "version"     -> Json.obj("activated" -> Json.fromInt(3), "ergoTree" -> Json.fromInt(3)))
    }
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireUnparsedSoftFork", extract(), outDir)
}
