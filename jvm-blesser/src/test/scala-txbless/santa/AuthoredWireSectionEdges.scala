package santa

// ergots' version-context requests, item 9 (2026-11-27): the block transactions section parser's edges, beyond the
// twelve BlockTransactions entries the tier held. ergo v6.0.8 `BlockTransactions.scala`:
//  - the section: the header id (32 bytes), then a VLQ that is a transaction count when it is at most 10,000,000 (block
//    version 1, no marker) and 10,000,000 + the block version above that, followed by the count (`:163-182`);
//  - the writer puts the marker only for a block version above 1 (`:142-147`);
//  - `BlockTransactions` asserts at least one transaction (`:42`);
//  - `ErgoSerializer.parseBytes` reads what it needs and leaves the rest (avldb `ErgoSerializer.scala:27-30`);
//  - from block version 4 each transaction is read under (blockVersion - 1, blockVersion - 1), except in one testnet
//    block, header id 3f5a4acb…9398, read under (1, 1) (`:187-197`: "public testnet bug with v7 tree included in v4
//    block"); the writer takes (blockVersion, blockVersion) from block version 3 (`:148-159`).
// Every entry's version pair is nominal, (3, 3): the section's own block version decides the context.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import RentFixtures._

object AuthoredWireSectionEdges extends BoxTreeWireFixtures {
  val Source    = "santa:authored-section-edges"
  val OpFormat  = "BlockTransactions.section_format_edges"
  val OpTestnet = "BlockTransactions.testnet_read_context"
  val TestnetHeaderId = "3f5a4acbdfd76a97f2fdf387559c2a67b4ea5f9e9bcf66ef079cde766c6e9398"
  private val BlockKind = "BlockTransactions"

  private lazy val plainTx = txWith(placeholderBytes)                     // one input; one output, SigmaProp(true)
  private lazy val v7Tx    = txWith(Value ++ b("0f0208d3") ++ Fields)      // its output's tree is v7
  private lazy val header  = digest("santa:wse:header")

  private def marked(blockVersion: Int, count: Long, txs: Array[Byte], id: Array[Byte] = header): Array[Byte] =
    id ++ vlqU32(10000000L + blockVersion) ++ vlqU32(count) ++ txs
  private def unmarked(count: Long, txs: Array[Byte]): Array[Byte] = header ++ vlqU32(count) ++ txs

  private def canon(in: String): String = WireCanonicalize.canonicalize(BlockKind, in, V3, V3)

  private def identity(name: String, description: String, bytes: Array[Byte]): Json = {
    val in = hex(bytes)
    val out = canon(in)
    require(out == in, s"$name: the JVM must write the section back as it is, got ${out.take(80)}…")
    entry(name, BlockKind, description, in)
  }
  private def rewrite(name: String, description: String, bytes: Array[Byte], want: Array[Byte]): Json = {
    val (in, w) = (hex(bytes), hex(want))
    require(in != w, s"$name: a rewrite must change the bytes")
    val out = canon(in)
    require(out == w, s"$name: the JVM must write ${w.take(80)}…, got ${out.take(80)}…")
    entry(name, BlockKind, description, in, "expected_bytes_hex" -> Json.fromString(w))
  }
  private def refuse(name: String, description: String, bytes: Array[Byte], mention: String): Json = {
    val in = hex(bytes)
    Try(canon(in)) match {
      case Success(out) => sys.error(s"$name: the JVM must REJECT the section, but wrote ${out.take(80)}…")
      case Failure(t)   => require(causes(t).exists(_.contains(mention)), s"$name: want '$mention' in ${causes(t).mkString(" <- ")}")
    }
    entry(name, BlockKind, description, in, "error" -> Json.fromString("errored"))
  }

  private val oneTx = "The transaction has one input and one output of 1000000 whose tree is SigmaProp(true)."

  private def formatEntries: Seq[Json] = Seq(
    refuse("section-count-zero-reject#0",
      "A section of version 4 whose transaction count is 0: the header id, the marker 10,000,004, the count 0, nothing " +
      "after. The parse reads no transaction, and BlockTransactions asserts at least one (BlockTransactions.scala:42): " +
      "the JVM rejects.", marked(4, 0, Array.emptyByteArray), "at least 1 coinbase-like transaction"),
    refuse("section-first-value-10000000-count-reject#1",
      "The first value after the header id is exactly 10,000,000. It is not above 10,000,000, so it is no marker: it is " +
      s"the transaction count, at block version 1 (BlockTransactions.scala:176-182). One transaction follows. $oneTx The " +
      "parse wants 10,000,000 and runs out of input at the second: the JVM rejects.",
      unmarked(10000000L, plainTx), "BufferUnderflow"),
    rewrite("section-marker-10000001-version-1-rewrite#2",
      "The first value is 10,000,001: a marker, block version 1, then the count 1 and one transaction. " +
      s"$oneTx The section parses at block version 1, and the writer puts a marker only above version 1 " +
      "(BlockTransactions.scala:142-147), so it writes the section back without it: the header id, the count, the " +
      "transaction. Non-identity round trip.", marked(1, 1, plainTx), unmarked(1, plainTx)),
    rewrite("section-trailing-bytes-ignored-rewrite#3",
      s"A section of version 4 with one transaction, then two bytes more (01 02). $oneTx parseBytes reads what the " +
      "section needs and leaves the rest (avldb ErgoSerializer.scala:27-30), so the trailing bytes are ignored, and the " +
      "section is written back without them.", marked(4, 1, plainTx) ++ b("0102"), marked(4, 1, plainTx)),
    identity("section-version-1-no-marker-accept#4",
      s"A section of version 1: the header id, the count 1 (no marker) and one transaction. $oneTx Round-trip identity.",
      unmarked(1, plainTx)),
    identity("section-version-2-accept#5",
      s"A section of version 2: the marker 10,000,002, the count 1, one transaction, read and written outside any " +
      s"version context. $oneTx Round-trip identity.", marked(2, 1, plainTx)),
    identity("section-version-5-accept#6",
      s"A section of version 5: the marker 10,000,005, the count 1, one transaction, read under (4, 4) and written " +
      s"under (5, 5) (BlockTransactions.scala:148-159, 184-197). $oneTx Round-trip identity.", marked(5, 1, plainTx)))

  private def testnetEntries: Seq[Json] = {
    val v7 = "a section of version 4 holding one transaction whose output's tree is v7 (0f 02 08 d3)"
    Seq(
      identity("testnet-header-v4-output-tree-v7-accept#0",
        s"The header id is $TestnetHeaderId, the one testnet block ergo reads under (1, 1) (BlockTransactions.scala:" +
        s"187-193): $v7. At (1, 1) a tree's version is compared with nothing (sigma-state VersionContext.scala:20), so " +
        "the section parses, and the writer writes it back. Round-trip identity.",
        marked(4, 1, v7Tx, Base16.decode(TestnetHeaderId).get)),
      refuse("other-header-v4-output-tree-v7-reject#1",
        s"The control: $v7, under any other header id. Its transaction is read under (3, 3), and the tree is above " +
        "activated 3: the JVM rejects ('Tree version (7) is above activated script version (3)').",
        marked(4, 1, v7Tx), "Tree version (7) is above activated script version (3)"))
  }

  /** The testnet file's verdicts turn on the version context the section's transactions are read under. */
  private def activationRule(env: Json): Json = env.mapObject(o => io.circe.JsonObject.fromIterable(o.toList.flatMap {
    case kv @ ("blessed_by", _) => Seq(kv, "activation_rule" -> Json.True)
    case kv                     => Seq(kv)
  }))

  def extract(): Map[String, Json] = Map(
    OpFormat  -> envelope(OpFormat, formatEntries),
    OpTestnet -> activationRule(envelope(OpTestnet, testnetEntries)))

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireSectionEdges", extract(), outDir)
}
