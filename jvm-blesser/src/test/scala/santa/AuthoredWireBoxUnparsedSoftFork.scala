package santa

// Authored wire round-trip witness for the boxId fork — the DIRECT test the `ErgoTree` kind dodges.
//
// The `ErgoTree` wire arm re-serializes a bare tree; a runner can (and vixen does) STRIP the size flag
// to parse the body, which sidesteps the soft-fork wrap entirely. The `Box` kind cannot: it serializes a
// whole ErgoBox through the impl's box->tree path with the size flag INTACT, so an unparseable body hits
// the soft-fork wrap. The JVM stores it as UnparsedErgoTree and echoes the raw propositionBytes (the box
// round-trip is identity); an impl that substitutes a `Const(true)` placeholder + empty constants there
// emits different propositionBytes -> a different boxId. That is the consensus fork, on the exact path
// that computes box identity. See docs/findings/wire-unparsed-soft-fork-boxid.md.
//
// Each entry's bytes_hex is the JVM `ErgoBox.sigmaSerializer` bytes of a box whose script is the unparsed
// soft-fork tree; identity round-trip (no expected_bytes_hex). extract() RE-DERIVES the blessing: the
// tree is genuinely unparsed, the box embeds the raw tree bytes, and the JVM box canonicalize == input.

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate}

object AuthoredWireBoxUnparsedSoftFork {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source   = "santa:authored-unparsed-soft-fork-boxid"
  val Op       = "Box.unparsed_soft_fork_boxid"

  // Deterministic box envelope: zeros txId, index 0, value 1000000, creationHeight 0, no tokens/registers.
  private val zerosTxId = scorex.util.bytesToId(Array.fill(32)(0.toByte))

  private val trees: Seq[(String, String)] = Seq(
    ("0b01fd",     "1-byte body"),
    ("0b03fd0102", "3-byte body"))

  def extract(): Map[String, Json] = {
    val entries = trees.zipWithIndex.map { case ((treeHex, label), i) =>
      val boxHex = VersionContext.withVersions(V3, V3) {
        val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(treeHex).get)
        require(tree.root.isLeft,
          s"$treeHex must be an UnparsedErgoTree (soft-fork) — vector is meaningless otherwise")
        val box = new ErgoBoxCandidate(1000000L, tree, 0).toBox(zerosTxId, 0.toShort)
        val hex = Base16.encode(ErgoBox.sigmaSerializer.toBytes(box))
        require(hex.contains(treeHex),
          s"the box must embed the RAW tree bytes $treeHex (boxId is over these); got $hex")
        val canon = WireCanonicalize.canonicalize("Box", hex, V3, V3)
        require(canon == hex,
          s"JVM must PRESERVE the box round-trip byte-identically (identity); got $canon")
        hex
      }
      Json.obj(
        "name"        -> Json.fromString(s"box-unparsed-soft-fork-$label-boxid#$i".replace(" ", "-")),
        "kind"        -> Json.fromString("Box"),
        "source"      -> Json.fromString(Source),
        "description" -> Json.fromString(
          s"ErgoBox whose script is a size-flagged ErgoTree with unknown opcode 0xfd ($label): the JVM " +
          "stores it as UnparsedErgoTree and re-serializes the box byte-IDENTICAL — the raw tree bytes " +
          "are the box's propositionBytes, so boxId hashes them. Identity round-trip. An impl that " +
          "substitutes a Const(true) placeholder + empty constants on the soft-fork wrap (size flag " +
          "INTACT here, unlike the bare ErgoTree arm) emits different propositionBytes -> different " +
          "boxId -> UTXO-digest/consensus fork."),
        "bytes_hex"   -> Json.fromString(boxHex),
        // expected_bytes_hex OMITTED: identity round-trip (JVM preserves the raw box bytes).
        "version"     -> Json.obj("activated" -> Json.fromInt(3), "ergoTree" -> Json.fromInt(3)))
    }
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "entries"    -> Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireBoxUnparsedSoftFork", extract(), outDir)
}
