package santa

// Authored wire vectors — where the parser's state lives: on a transaction's reader, never across transactions.
//
// ergo v6.0.6 parses a block's transactions through `ErgoTransactionSerializer.parse`, which wraps the section's
// reader in a fresh `SigmaByteReader` for each transaction (`ErgoTransaction.scala:497-503`,
// `BlockTransactions.scala:187-200`): nesting level 0, empty constant and ValDef type stores. Within one transaction
// the reader is shared by every output, so a ValDef in one output's tree is still in the store when the next output's
// tree parses (`ValUseSerializer` looks the type up there).
//
// - `BlockTransactions.reader_scope` (kind BlockTransactions, the section as the JVM frames it: header id,
//   VLQ(10,000,000 + block version 4), VLQ tx count, the transactions):
//   - tx A's output tree degrades 109 levels deep (the leak of degraded_tree_depth_leak), then tx B has an ordinary
//     output. The JVM accepts, and in the other order too. On ONE shared reader tx B would start at level 109 and fail
//     (the blesser checks this).
//   - tx A's tree defines ValDef(1), tx B's is the bare ValUse(1). The JVM rejects tx B. On one shared reader it
//     would resolve (checked too). The twin: tx B defines its own.
// - `Transaction.valdef_scope`: within one transaction, output 0 defines ValDef(1) and output 1's tree is the bare
//   ValUse(1). The JVM accepts. The reverse order rejects, since the store is still empty when output 0 parses.
//
// Blessed through the JVM's own paths: ergo-core's BlockTransactionsSerializer (via WireCanonicalize's
// BlockTransactions arm) and sigma-state's transaction serializer, under the node's v6 context (3, 3).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BlockValue, ErgoTree, SSigmaProp, SigmaPropConstant, ValDef, ValUse}
import sigma.ast.syntax.SigmaPropValue
import sigma.data.TrivialProp
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import org.ergoplatform.{ErgoBoxCandidate, ErgoLikeTransaction}

import RentFixtures._

object AuthoredWireReaderScope {
  val V3: Byte  = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 parse context
  val OpBlock   = "BlockTransactions.reader_scope"
  val OpTx      = "Transaction.valdef_scope"
  val Source    = "santa:authored-reader-scope"

  private def b(hex: String): Array[Byte] = Base16.decode(hex).get

  /** Size-flagged v3 tree degrading at depth 109: BoolToSigmaProp, 107 LogicalNot, unknown opcode 0xfd. */
  private val DegradingTree: Array[Byte] =
    b("0b") ++ vlqU32(109) ++ b("d1") ++ Array.fill(107)(0xef.toByte) ++ b("fd")
  /** v0 tree BlockValue(ValDef(1, SigmaProp(true)), ValUse(1)). */
  private lazy val DefiningTree: Array[Byte] = VersionContext.withVersions(V3, V3) {
    ErgoTree(ErgoTree.ZeroHeader, IndexedSeq(),
      BlockValue(IndexedSeq(ValDef(1, SigmaPropConstant(TrivialProp.TrueProp))), ValUse(1, SSigmaProp))
        .asInstanceOf[SigmaPropValue]).bytes
  }
  /** v0 tree whose body is the bare ValUse(1). */
  private val UsingTree = b("007201")

  private val PlainTree = b("0008d3")
  private def cand(value: Long, tree: Array[Byte]): Array[Byte] = vlqU32(value) ++ tree ++ b("010000")

  private def replaceUnique(bytes: Array[Byte], from: Array[Byte], to: Array[Byte]): Array[Byte] = {
    val at = bytes.indexOfSlice(from)
    require(at >= 0 && bytes.indexOfSlice(from, at + 1) < 0, s"${hex(from)} must occur exactly once in ${hex(bytes)}")
    bytes.take(at) ++ to ++ bytes.drop(at + from.length)
  }

  /** A one-input tx whose outputs are sigmaProp(true) candidates of `values`, the i-th tree then swapped for `trees(i)`. */
  private def txWithTrees(label: String, outputs: Seq[(Long, Array[Byte])]): Array[Byte] =
    VersionContext.withVersions(V3, V3) {
      val base = txBytes(RentFixtures.tx(Seq(input(box(label, 1000000000L, 1), ext())),
        outputs.map { case (v, _) => candidate(v, 1) }))
      outputs.foldLeft(base) { case (acc, (v, tree)) => replaceUnique(acc, cand(v, PlainTree), cand(v, tree)) }
    }

  /** A block section (version 4) holding `txs`, as the JVM frames it. */
  private def section(txs: Seq[Array[Byte]]): Array[Byte] =
    digest("santa:wrs:header") ++ vlqU32(10000000L + 4) ++ vlqU32(txs.size.toLong) ++ txs.reduce(_ ++ _)

  /** Parse `txs` back to back on ONE SigmaByteReader — what a parser that shares reader state would do. */
  private def onOneReader(txs: Seq[Array[Byte]]): Try[Unit] = Try {
    VersionContext.withVersions(V3, V3) {
      val r = SigmaSerializer.startReader(txs.reduce(_ ++ _))
      txs.foreach(_ => ErgoLikeTransaction.serializer.parse(r))
    }
  }

  private def causes(t: Throwable): List[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList
  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  private def entry(name: String, kind: String, description: String, in: String, extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version): _*)

  private def accept(name: String, kind: String, description: String, bytes: Array[Byte]): Json = {
    val in = hex(bytes)
    val out = WireCanonicalize.canonicalize(kind, in, V3, V3)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself")
    entry(name, kind, description, in)
  }

  private def reject(name: String, kind: String, description: String, bytes: Array[Byte], mention: String): Json = {
    val in = hex(bytes)
    Try(WireCanonicalize.canonicalize(kind, in, V3, V3)) match {
      case Success(_) => sys.error(s"$name: the JVM must REJECT")
      case Failure(t) =>
        require(causes(t).exists(_.contains(mention)), s"$name: want '$mention' in ${causes(t).mkString(" <- ")}")
    }
    entry(name, kind, description, in, "error" -> Json.fromString("errored"))
  }

  def extract(): Map[String, Json] = {
    VersionContext.withVersions(V3, V3) {
      require(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(DegradingTree).root.isLeft,
        "the depth-109 tree must degrade")
    }
    val txDegrading = txWithTrees("santa:wrs:a", Seq(1000000L -> DegradingTree))
    val txPlain     = txWithTrees("santa:wrs:b", Seq(1000000L -> PlainTree))
    val txDefining  = txWithTrees("santa:wrs:a2", Seq(1000000L -> DefiningTree))
    val txUsing     = txWithTrees("santa:wrs:b2", Seq(1000000L -> UsingTree))
    val txDefining2 = txWithTrees("santa:wrs:b3", Seq(1000000L -> DefiningTree))

    // The shared-reader controls: each block entry below answers differently on one reader.
    require(onOneReader(Seq(txDegrading, txPlain)) match {
      case Failure(t) => causes(t).exists(_.contains("nested value deserialization call depth"))
      case Success(_) => false
    }, "on one reader, tx B must fail on the depth tx A left behind")
    require(onOneReader(Seq(txDefining, txUsing)).isSuccess, "on one reader, tx B's ValUse(1) must resolve")

    val blockEntries = Seq(
      accept("block-depth-left-then-plain-accept#0", "BlockTransactions",
        "Block section (version 4) with two transactions. Tx A's only output has the size-flagged v3 tree " +
        "BoolToSigmaProp(LogicalNot^107(0xfd)), which degrades 109 levels deep and leaves those levels on its reader. " +
        "Tx B has an ordinary sigmaProp(true) output. The JVM parses each transaction on a fresh SigmaByteReader, so tx B " +
        "starts at level 0 and the section round-trips. On one shared reader, tx B's plain tree would need levels 110 " +
        "and 111: an impl that shares reader state across transactions rejects the section.",
        section(Seq(txDegrading, txPlain))),
      accept("block-plain-then-depth-left-accept#1", "BlockTransactions",
        "The same two transactions, the ordinary one first: nothing follows the degrade, so even a shared reader " +
        "accepts. The control for #0.",
        section(Seq(txPlain, txDegrading))),
      reject("block-valdef-then-valuse-reject#2", "BlockTransactions",
        "Block section: tx A's output tree is BlockValue(ValDef(1, SigmaProp(true)), ValUse(1)); tx B's is 00 72 01, " +
        "the bare ValUse(1). Tx B parses on a fresh reader whose ValDef store is empty, so ValUse(1) throws " +
        "NoSuchElementException and the JVM rejects the section. On one shared reader tx A's ValDef(1) would resolve " +
        "it: an impl that shares reader state across transactions round-trips the section, the over-accept.",
        section(Seq(txDefining, txUsing)), mention = "NoSuchElementException"),
      accept("block-valdef-then-own-valdef-accept#3", "BlockTransactions",
        "The twin of #2: tx B's tree defines its own ValDef(1). Round-trip identity.",
        section(Seq(txDefining, txDefining2))))

    val txEntries = Seq(
      accept("tx-valdef-out0-valuse-out1-accept#0", "Transaction",
        "One transaction, two outputs: output 0's tree is BlockValue(ValDef(1, SigmaProp(true)), ValUse(1)), output " +
        "1's is 00 72 01, the bare ValUse(1). The outputs share the transaction's reader, and ValDef(1) is still in its " +
        "store when output 1's tree parses, so the JVM accepts. An impl that scopes the ValDef store to one tree rejects " +
        "it: the over-reject.",
        txWithTrees("santa:wrs:one", Seq(1000000L -> DefiningTree, 2000000L -> UsingTree))),
      reject("tx-valuse-out0-valdef-out1-reject#1", "Transaction",
        "The same outputs in the reverse order: output 0's bare ValUse(1) parses before any ValDef(1), so the store is " +
        "empty and the JVM rejects (NoSuchElementException).",
        txWithTrees("santa:wrs:rev", Seq(1000000L -> UsingTree, 2000000L -> DefiningTree)), mention = "NoSuchElementException"))

    def envelope(op: String, blessedBy: String, es: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString(blessedBy),
      "entries"    -> Json.arr(es: _*))
    Map(
      OpBlock -> envelope(OpBlock, "jvm:ergo-core-6.0.6-BlockTransactionsSerializer", blockEntries),
      OpTx    -> envelope(OpTx, "jvm:sigma-state-6.0.6", txEntries))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireReaderScope", extract(), outDir)
}
