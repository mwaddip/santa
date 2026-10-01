package santa

// Authored wire vectors: a tree whose header version is above the activated script version (sigma-rust's probe request,
// 2026-10-01). All confirmed on the JVM (sigma-state 6.0.6, ergo-core 6.0.6).
//
// 1. Where the JVM compares. deserializeErgoTree reads the header and runs CheckHeaderSizeBit (rule 1012) before its
//    try (`ErgoTreeSerializer.scala:145`, `:217-219`). It then enters VersionContext.withVersions(activated,
//    treeVersion) inside the inner try, before the constants (`:150-155`). VersionContext's constructor requires
//    `activatedVersion < 2 || ergoTreeVersion <= activatedVersion` (`VersionContext.scala:20-21`), and the inner catch
//    rethrows that IllegalArgumentException as a SerializerException, "Tree version (4) is above activated script
//    version (3)" (`:191-193`). That is not a ValidationException, so a size-flagged tree does not degrade (`:197`).
//    - The order is: the size-bit rule, a ValidationException; then the version, a hard failure; then the constants
//      and the body, whose soft failures degrade a size-flagged tree.
//    - A tree nested in a Box constant (in a tree, a register or an extension) is checked the same way. Its
//      SerializerException rejects the whole object, while a nested header without the size bit raises rule 1012,
//      which degrades a size-flagged outer tree.
//    - The version is the header's low three bits; bits 5 to 7 do not hide it.
// 2. Which activated version. The check takes the context's activated version, and applies only from 2. A node parses
//    - a block's transactions under (blockVersion - 1, blockVersion - 1) from block version 4, and outside any version
//      context before that (ergo v6.0.6 `BlockTransactions.scala:184-202`). Outside a context its threads run at the
//      default (1, 1) (`VersionContext.scala:58-61`), where a tree of any version parses;
//    - a peer's transaction under the current block version's (blockVersion - 1, blockVersion - 1)
//      (`ErgoNodeViewSynchronizer.scala:793`);
//    - a box from the UTXO set outside any version context (`UtxoStateReader.scala:122-124`).
//    So a block of version 4 with an output whose tree is above v3 does not parse, while a block of version 3 takes an
//    output with a tree of any version, and only spending that box fails (the transaction vectors
//    tree-version-above-activated).
//
// The v6 files hold Box and Transaction entries under (3, 3) and block sections of version 4. The v5 files hold Box and
// Transaction entries under (2, 2), and block sections of version 3. A BlockTransactions entry's version pair is
// nominal: the section's own block version decides the context.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import sigma.VersionContext
import sigma.ast.ErgoTree
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{ErgoBox, ErgoLikeTransaction}
import org.ergoplatform.modifiers.history.BlockTransactionsSerializer

import RentFixtures._

object AuthoredWireTreeVersion extends BoxTreeWireFixtures {
  val Source  = "santa:authored-tree-version"
  val OpBox   = "Box.tree_version_above_activated"
  val OpTx    = "Transaction.tree_version_above_activated"
  val OpBlock = "BlockTransactions.tree_version_above_activated"
  private val V2: Byte = VersionContext.JitActivationVersion // activated AND ergoTree: the v5 files' context

  private val Above = "is above activated script version"
  private def above(tree: Int, activated: Int) = s"Tree version ($tree) $Above ($activated)"

  /** A box as a Box constant's data: value 1000000, `tree`, height 1, no tokens, no registers, a zero tx id, index 0. */
  private def nestedBox(tree: String): String = hex(Value) + tree + "01" + "00" + "00" + "00" * 32 + "00"
  /** Size-flagged, segregated tree `header`: constant 0 = Box(`nested`), constant 1 = SigmaProp(true), body
    * ConstantPlaceholder(1). */
  private def boxConstTree(header: Int, nested: String): Array[Byte] =
    sizedTree(header, b("02" + "63" + nested + "08d3" + "7301"))
  /** A candidate with the plain tree 00 08 d3 whose R4 is a Box constant (type 63). */
  private def withBoxR4(nested: String): Array[Byte] = Value ++ b("0008d3" + "01" + "00" + "01" + "63" + nested)

  /** The one-input transaction: 01 | box id (32) | proof 00 | extension 00 | data inputs 00 | tokens 00 | outputs 01. */
  private lazy val plainTx = txWith(placeholderBytes)
  /** The plain transaction with input 0's extension = {0: a Box constant}. */
  private def extTx(nested: String): Array[Byte] = {
    require(hex(plainTx.slice(33, 35)) == "0000", s"unexpected transaction layout ${hex(plainTx)}")
    plainTx.take(34) ++ b("01" + "00" + "63" + nested) ++ plainTx.drop(35)
  }

  private def at(e: Json, v: Byte): Json =
    e.mapObject(_.add("version", Json.obj("activated" -> Json.fromInt(v.toInt), "ergoTree" -> Json.fromInt(v.toInt))))

  /** The crafted candidate's tree as the JVM parses it under (`v`, `v`) (a Box's tree, or a Transaction's output 0). */
  private def treeAt(kind: String, bytes: Array[Byte], v: Byte): ErgoTree = VersionContext.withVersions(v, v) {
    val r = SigmaSerializer.startReader(bytes)
    kind match {
      case "Box"         => ErgoBox.sigmaSerializer.parse(r).ergoTree
      case "Transaction" => ErgoLikeTransaction.serializer.parse(r).outputCandidates(0).ergoTree
    }
  }

  /** An accept under (`v`, `v`): identity round-trip, and the tree parsed (`degrade = None`) or degraded by that rule. */
  private def acceptAt(name: String, kind: String, description: String, bytes: Array[Byte], degrade: Option[Int],
                       v: Byte): Json = {
    val in = hex(bytes)
    val out = WireCanonicalize.canonicalize(kind, in, v, v)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself under ($v, $v), got ${out.take(80)}…")
    val got = degradedBy(treeAt(kind, bytes, v))
    require(got == degrade, s"$name: tree degrade rule must be $degrade, got $got")
    at(entry(name, kind, description, in), v)
  }

  /** A reject under (`v`, `v`): the JVM must throw, with every `mention` in the cause chain and no `forbid`. */
  private def rejectAt(name: String, kind: String, description: String, bytes: Array[Byte], mention: Seq[String],
                       v: Byte, forbid: Seq[String] = Nil): Json = {
    val in = hex(bytes)
    Try(WireCanonicalize.canonicalize(kind, in, v, v)) match {
      case Success(out) => sys.error(s"$name: the JVM must REJECT under ($v, $v), but it round-tripped to ${out.take(80)}…")
      case Failure(t) =>
        val chain = causes(t)
        mention.foreach(m => require(chain.exists(_.contains(m)), s"$name: want '$m' in ${chain.mkString(" <- ")}"))
        forbid.foreach(f => require(!chain.exists(_.contains(f)), s"$name: must not fail with '$f': ${chain.mkString(" <- ")}"))
    }
    at(entry(name, kind, description, in, "error" -> Json.fromString("errored")), v)
  }

  // ── block sections ────────────────────────────────────────────────────────────────────────────────
  private val BlockKind = "BlockTransactions"
  /** A block section as the JVM frames it: the header id, VLQ(10,000,000 + block version), the tx count, the tx. */
  private def section(blockVersion: Int, tx: Array[Byte]): Array[Byte] =
    digest("santa:wtv:header") ++ vlqU32(10000000L + blockVersion) ++ vlqU32(1L) ++ tx
  /** The transaction a section holds: one input, and one output whose tree is `tree`. */
  private def txWithTree(tree: String): Array[Byte] = txWith(Value ++ b(tree) ++ Fields)

  /** A section the JVM parses and writes back as it is. `pair` is the entry's nominal version; the tree of the one
    * output parsed (`degrade = None`) or degraded by that rule. `rejectsUnder`: the transaction alone must reject
    * under that context, so the entry tells a section parsed there from one parsed as the JVM parses it. */
  private def blockAccept(name: String, description: String, blockVersion: Int, tree: String, degrade: Option[Int],
                          pair: Byte, rejectsUnder: Option[Byte]): Json = {
    val tx = txWithTree(tree)
    val in = hex(section(blockVersion, tx))
    val out = WireCanonicalize.canonicalize(BlockKind, in, pair, pair)
    require(out == in, s"$name: the JVM must round-trip the section to itself, got ${out.take(80)}…")
    val got = degradedBy(BlockTransactionsSerializer.parseBytes(b(in)).txs.head.outputCandidates(0).ergoTree)
    require(got == degrade, s"$name: tree degrade rule must be $degrade, got $got")
    rejectsUnder.foreach { v =>
      require(Try(WireCanonicalize.canonicalize("Transaction", hex(tx), v, v)).isFailure,
        s"$name: the transaction alone must reject under ($v, $v)")
    }
    at(entry(name, BlockKind, description, in), pair)
  }

  private def blockReject(name: String, description: String, blockVersion: Int, tree: String, mention: String,
                          pair: Byte): Json = {
    val in = hex(section(blockVersion, txWithTree(tree)))
    Try(WireCanonicalize.canonicalize(BlockKind, in, pair, pair)) match {
      case Success(_) => sys.error(s"$name: the JVM must REJECT the section")
      case Failure(t) => require(causes(t).exists(_.contains(mention)), s"$name: want '$mention' in ${causes(t).mkString(" <- ")}")
    }
    at(entry(name, BlockKind, description, in, "error" -> Json.fromString("errored")), pair)
  }

  private def blockEnvelope(es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-wire/v1"),
    "op"         -> Json.fromString(OpBlock),
    "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.6-BlockTransactionsSerializer"),
    "entries"    -> Json.arr(es: _*))

  private def subjectOf(kind: String) = if (kind == "Box") "A bare box" else "A transaction output"

  // ── the v6 files: (3, 3), and block sections of version 4 ─────────────────────────────────────────
  private def v6Entries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
    val (k, subject) = (kind.toLowerCase, subjectOf(kind))
    def cand(tree: String): Array[Byte] = wrap(Value ++ b(tree) ++ Fields)
    def candTree(tree: Array[Byte]): Array[Byte] = wrap(Value ++ tree ++ Fields)
    val outer = s"$subject whose size-flagged, segregated v3 tree has constant 0 = a Box, constant 1 = SigmaProp(true) " +
      "and the body ConstantPlaceholder(1)."
    Seq("0c" -> 4, "0d" -> 5, "0e" -> 6, "0f" -> 7).zipWithIndex.map { case ((h, n), i) =>
      rejectAt(s"$k-tree-v$n-reject#$i", kind,
        s"$subject whose size-flagged tree has header $h, version $n, and the body SigmaProp(true) ($h 02 08 d3). The " +
        "JVM compares a tree's version with the activated script version before it reads the tree's constants " +
        s"(ErgoTreeSerializer.scala:150-155, VersionContext.scala:20). $n is above 3: a SerializerException, not a " +
        "ValidationException, so the size flag does not degrade the tree, and the JVM rejects. An impl without the " +
        "check parses the tree and round-trips the object: the over-accept.",
        cand(h + "0208d3"), Seq(above(n, 3)), V3)
    } ++ Seq(
      acceptAt(s"$k-tree-v3-accept#4", kind,
        s"The control: $subject whose tree is the same at version 3 (0b 02 08 d3), the activated version. It parses. " +
        "Round-trip identity.", cand("0b0208d3"), None, V3),
      rejectAt(s"$k-tree-v4-soft-constant-reject#5", kind,
        s"$subject whose size-flagged, segregated v4 tree (1c 02 08 d3) declares 8 constants, the first of type code " +
        "211, which is no type. The version is compared before any constant is read, so the JVM rejects on the " +
        "version. An impl that reads the constants first meets the soft failure and degrades the tree, and so does " +
        "one without the check: the over-accept.", cand("1c0208d3"), Seq(above(4, 3)), V3),
      acceptAt(s"$k-tree-v3-soft-constant-degrade-accept#6", kind,
        s"The twin: $subject whose tree is the same at version 3 (1b 02 08 d3). The type code fails rule 1018, a " +
        "ValidationException, and the size-flagged tree degrades. Round-trip identity.", cand("1b0208d3"), Some(1018), V3),
      rejectAt(s"$k-tree-v4-unsized-reject#7", kind,
        s"$subject whose tree header is 04: version 4 without the size bit (04 08 d3). CheckHeaderSizeBit (rule 1012, " +
        "ErgoTreeSerializer.scala:219) runs on the header before the version is compared, and here nothing above the " +
        "tree can degrade: the JVM rejects, for the size bit. An impl that takes an unsized tree above version 0 " +
        "round-trips the object: the over-accept. (Nested in a size-flagged tree, the same failure degrades the outer " +
        "tree: #11.)", cand("0408d3"), Seq("ValidationRule(1012"), V3, forbid = Seq(Above)),
      rejectAt(s"$k-tree-v4-high-bits-reject#8", kind,
        s"$subject whose size-flagged tree has header ec: version 4, with bits 5, 6 and 7 set (ec 02 08 d3). The " +
        "version is the header's low three bits, and the other bits do not hide it (tree_header_bits #3 is header e8, " +
        "version 0, which parses): the JVM rejects on the version.", cand("ec0208d3"), Seq(above(4, 3)), V3),
      rejectAt(s"$k-nested-tree-v4-reject#9", kind,
        s"$outer The Box's own tree is v4 (0c 02 08 d3). A Box constant's tree goes through the same deserializer, " +
        "under the same activated version. Its SerializerException is not a ValidationException, so it does not " +
        "degrade the outer tree either: the JVM rejects. An impl that checks only the outermost tree's version " +
        "round-trips the object: the over-accept.",
        candTree(boxConstTree(0x1b, nestedBox("0c0208d3"))), Seq(above(4, 3)), V3),
      acceptAt(s"$k-nested-tree-v3-accept#10", kind,
        s"The twin: $subject with the same outer tree, whose Box's own tree is v3 (0b 02 08 d3). Everything parses. " +
        "Round-trip identity.", candTree(boxConstTree(0x1b, nestedBox("0b0208d3"))), None, V3),
      acceptAt(s"$k-nested-tree-v4-unsized-degrade-accept#11", kind,
        s"$subject with the same outer tree, whose Box's own tree header is 04: version 4 without the size bit " +
        "(04 08 d3). Rule 1012 runs before the version is compared, and before the nested tree's own handler, so its " +
        "ValidationException reaches the outer tree, which is size-flagged and degrades (as tree_nested_degrade #2 " +
        "does for a v1 header). Round-trip identity. An impl that compares the version first raises a hard error and " +
        "rejects: the over-reject.", candTree(boxConstTree(0x1b, nestedBox("0408d3"))), Some(1012), V3),
      rejectAt(s"$k-register-box-tree-v4-reject#12", kind,
        s"$subject with the plain tree 00 08 d3, whose R4 is a Box constant (type 63) whose own tree is v4 " +
        "(0c 02 08 d3). A register's value is read under the same activated version, and nothing here can degrade: " +
        "the JVM rejects.", wrap(withBoxR4(nestedBox("0c0208d3"))), Seq(above(4, 3)), V3),
      acceptAt(s"$k-register-box-tree-v3-accept#13", kind,
        s"The twin: $subject whose R4 is a Box constant whose own tree is v3 (0b 02 08 d3). It parses. Round-trip " +
        "identity.", wrap(withBoxR4(nestedBox("0b0208d3"))), None, V3))
  }

  private def v6ExtensionEntries: Seq[Json] = Seq(
    rejectAt("transaction-extension-box-tree-v4-reject#14", "Transaction",
      "A transaction with one plain output, whose one input carries the extension {0: a Box constant (type 63) whose " +
      "own tree is v4 (0c 02 08 d3)}. An extension's value is read under the same activated version: the JVM rejects.",
      extTx(nestedBox("0c0208d3")), Seq(above(4, 3)), V3),
    acceptAt("transaction-extension-box-tree-v3-accept#15", "Transaction",
      "The twin: the extension's Box has a v3 tree (0b 02 08 d3). It parses. Round-trip identity.",
      extTx(nestedBox("0b0208d3")), None, V3))

  private def v6BlockEntries: Seq[Json] = {
    val parses = "From block version 4 the JVM parses each of a block's transactions under (blockVersion - 1, " +
      "blockVersion - 1) (ergo BlockTransactions.scala:184-202), here (3, 3)."
    Seq("0c0208d3" -> 4, "0f0208d3" -> 7).zipWithIndex.map { case ((tree, n), i) =>
      blockReject(s"block-v4-output-tree-v$n-reject#$i",
        s"A block section of version 4 holding one transaction, whose output's tree is v$n (${tree.take(2)} 02 08 d3). " +
        s"$parses The tree is above the activated version, so the section does not parse and the block is invalid. An " +
        "impl without the check round-trips the section, and so does one that parses a v4 block's transactions under " +
        "(4, 4) when the tree is v4: the over-accept.", 4, tree, above(n, 3), V3)
    } :+ blockAccept("block-v4-output-tree-v3-accept#2",
      s"The control: a block section of version 4 whose transaction's output has a v3 tree (0b 02 08 d3). $parses It " +
      "parses. Round-trip identity.", 4, "0b0208d3", None, V3, rejectsUnder = None)
  }

  def extract(): Map[String, Json] = Map(
    OpBox   -> envelope(OpBox, v6Entries("Box", boxWith)),
    OpTx    -> envelope(OpTx, v6Entries("Transaction", cand => txWith(cand)) ++ v6ExtensionEntries),
    OpBlock -> blockEnvelope(v6BlockEntries))

  // ── the v5 files: (2, 2), and block sections of version 3 ─────────────────────────────────────────
  private def v5Entries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
    val (k, subject) = (kind.toLowerCase, subjectOf(kind))
    def cand(tree: String): Array[Byte] = wrap(Value ++ b(tree) ++ Fields)
    def candTree(tree: Array[Byte]): Array[Byte] = wrap(Value ++ tree ++ Fields)
    val outer = s"$subject whose size-flagged, segregated v2 tree has constant 0 = a Box, constant 1 = SigmaProp(true) " +
      "and the body ConstantPlaceholder(1)."
    Seq(
      rejectAt(s"$k-tree-v3-reject#0", kind,
        s"$subject whose size-flagged tree is v3 (0b 02 08 d3), under (2, 2). The JVM compares a tree's version with " +
        "the activated script version before it reads the tree's constants (ErgoTreeSerializer.scala:150-155, " +
        "VersionContext.scala:20): 3 is above 2, and the JVM rejects, as it rejects v4 under (3, 3). (2, 2) is the " +
        "context a node parsed a peer's transaction under while block version 3 was current (ergo " +
        "ErgoNodeViewSynchronizer.scala:793). A block's own transactions were parsed outside any version context, " +
        "where this tree parses: BlockTransactions.tree_version_above_activated #2 in this directory.",
        cand("0b0208d3"), Seq(above(3, 2)), V2),
      acceptAt(s"$k-tree-v2-accept#1", kind,
        s"The control: $subject whose tree is the same at version 2 (0a 02 08 d3), the activated version. It parses. " +
        "Round-trip identity.", cand("0a0208d3"), None, V2),
      rejectAt(s"$k-tree-v4-reject#2", kind,
        s"$subject whose size-flagged tree is v4 (0c 02 08 d3), under (2, 2): above the activated version, and the " +
        "JVM rejects.", cand("0c0208d3"), Seq(above(4, 2)), V2),
      rejectAt(s"$k-tree-v3-soft-constant-reject#3", kind,
        s"$subject whose size-flagged, segregated v3 tree (1b 02 08 d3) declares 8 constants, the first of type code " +
        "211, under (2, 2). The version is compared before any constant is read, so the JVM rejects on the version. " +
        "Under (3, 3) the same tree degrades (the v6 file's #6).", cand("1b0208d3"), Seq(above(3, 2)), V2),
      rejectAt(s"$k-nested-tree-v3-reject#4", kind,
        s"$outer The Box's own tree is v3 (0b 02 08 d3), under (2, 2). A Box constant's tree goes through the same " +
        "deserializer, under the same activated version, and its failure does not degrade the outer tree: the JVM " +
        "rejects.", candTree(boxConstTree(0x1a, nestedBox("0b0208d3"))), Seq(above(3, 2)), V2),
      acceptAt(s"$k-nested-tree-v2-accept#5", kind,
        s"The twin: $subject with the same outer tree, whose Box's own tree is v2 (0a 02 08 d3). Everything parses. " +
        "Round-trip identity.", candTree(boxConstTree(0x1a, nestedBox("0a0208d3"))), None, V2))
  }

  private def v5BlockEntries: Seq[Json] = {
    val parses = "Below block version 4 the JVM parses a block's transactions outside any version context (ergo " +
      "BlockTransactions.scala:184-202), on a thread at the default (1, 1) (VersionContext.scala:58-61), where a " +
      "tree's version is not compared with anything (:20)."
    val nominal = "The entry's version pair is nominal: the section's own block version decides the context."
    Seq(
      blockAccept("block-v3-output-tree-v4-accept#0",
        s"A block section of version 3 holding one transaction, whose output's tree is v4 (0c 02 08 d3). $parses The " +
        s"section parses. Round-trip identity. $nominal An impl that compares the tree's version with the block's " +
        "activated version, 2, rejects the section: the over-reject. (What fails is spending such a box: the " +
        "transaction vectors tree-version-above-activated.)", 3, "0c0208d3", None, V2, rejectsUnder = Some(V2)),
      blockAccept("block-v3-output-tree-v7-accept#1",
        s"The same with a v7 tree (0f 02 08 d3). $parses Round-trip identity.", 3, "0f0208d3", None, V2,
        rejectsUnder = Some(V2)),
      blockAccept("block-v3-output-tree-v3-accept#2",
        "A block section of version 3 whose transaction's output has a v3 tree (0b 02 08 d3), above the block's " +
        s"activated version, 2. $parses Round-trip identity. The same transaction parsed under (2, 2) rejects " +
        "(Transaction.tree_version_above_activated #0 in this directory).", 3, "0b0208d3", None, V2,
        rejectsUnder = Some(V2)),
      blockAccept("block-v3-output-tree-v4-soft-constant-degrade-accept#3",
        "A block section of version 3 whose transaction's output has the size-flagged, segregated v4 tree 1c 02 08 d3: " +
        s"8 constants declared, the first of type code 211. $parses So the constants are read: the type code fails " +
        "rule 1008 (the rule's number outside a v6 context; 1018 inside one), and the size-flagged tree degrades. " +
        "Round-trip identity.", 3, "1c0208d3", Some(1008), V2, rejectsUnder = Some(V2)),
      blockReject("block-v3-output-tree-v4-unsized-reject#4",
        "A block section of version 3 whose transaction's output has the tree header 04: version 4 without the size " +
        "bit (04 08 d3). CheckHeaderSizeBit (rule 1012) does not depend on the version context: the JVM rejects. An " +
        "impl that skips every header check outside a version context round-trips the section: the over-accept.",
        3, "0408d3", "ValidationRule(1012", V2))
  }

  def extractV5(): Map[String, Json] = Map(
    OpBox   -> envelope(OpBox, v5Entries("Box", boxWith)),
    OpTx    -> envelope(OpTx, v5Entries("Transaction", cand => txWith(cand))),
    OpBlock -> blockEnvelope(v5BlockEntries))

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireTreeVersion", extract(), outDir)
  def writeVectorsV5(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireTreeVersionV5", extractV5(), outDir)
}
