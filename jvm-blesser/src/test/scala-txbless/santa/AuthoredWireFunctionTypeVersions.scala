package santa

// ergots' version-context requests, items 5 and 4's parse half (2026-11-27): where a function type is read, by the
// version context. The value is the constant empty Coll[Int => Int], 0c 70 01 04 04 00 00.
//
// TypeSerializer (sigma-state v6.0.7) reads the function type code 0x70 only when the context's TREE version is at
// least 3 (`TypeSerializer.scala:215`). Otherwise the code falls to the unknown-type branch, whose rule is chosen by
// the ACTIVATED version (`:228-236`): 1018 (CheckTypeCodeV6) from activated 3, 1008 (CheckTypeCode) below.
//
// 1. Transaction.function_type_version_pairs (v6, v5, v4: one file per wire slot): a transaction whose input carries
//    {0: that value}, under seven pairs ((4, 4) has no wire slot and waits on the wire tier's any/ decision). A
//    node reads a transaction under an equal pair: a block's under (blockVersion - 1, blockVersion - 1) from block
//    version 4 and under the default (1, 1) below it (ergo `BlockTransactions.scala:184-202`); a peer's under the
//    current block's pair. The unequal pairs (3, 0), (3, 2) and (1, 3) are not pairs a transaction is read under. They
//    are the pairs a tree's own constants meet (deserializeErgoTree runs under (activated, the tree's version)), and
//    they show which half of the pair decides what.
// 2. BlockTransactions.function_type_versions: the node's own path. A section of version 3 is read under (1, 1), so
//    the value is refused at the parse (rule 1008), in an output's register as in an extension. A section of version
//    4 is read under (3, 3) and written back under (4, 4) (`BlockTransactions.scala:142-159`: from block version 3 the
//    writer takes (blockVersion, blockVersion)), so it round-trips. (Validating that transaction is another matter:
//    verifyOutput writes an output at (1, 1), where a function type cannot be written. See the transaction vectors
//    evaluated-values-spend #63-#65.)

import scala.util.{Failure, Success, Try}

import io.circe.Json

import RentFixtures._

object AuthoredWireFunctionTypeVersions extends BoxTreeWireFixtures {
  val Source  = "santa:authored-function-type-versions"
  val OpTx    = "Transaction.function_type_version_pairs"
  val OpBlock = "BlockTransactions.function_type_versions"
  val Func    = "0c700104040000" // the constant: an empty Coll[Int => Int]

  /** The one-input transaction: 01 | box id (32) | proof 00 | extension 00 | data inputs 00 | tokens 00 | outputs 01. */
  private lazy val plainTx = txWith(placeholderBytes)
  /** The plain transaction with input 0's extension = {0: the function-typed value}. */
  private lazy val extTx: Array[Byte] = {
    require(hex(plainTx.slice(33, 35)) == "0000", s"unexpected transaction layout ${hex(plainTx)}")
    plainTx.take(34) ++ b("01" + "00" + Func) ++ plainTx.drop(35)
  }
  /** The plain transaction whose output's R4 is the function-typed value. */
  private lazy val outTx: Array[Byte] = txWith(Value ++ b("0008d3" + "01" + "00" + "01" + Func))

  private def pair(e: Json, activated: Int, tree: Int): Json =
    e.mapObject(_.add("version", Json.obj("activated" -> Json.fromInt(activated), "ergoTree" -> Json.fromInt(tree))))

  private def acceptAt(name: String, kind: String, description: String, bytes: Array[Byte], a: Int, t: Int): Json = {
    val in = hex(bytes)
    val out = WireCanonicalize.canonicalize(kind, in, a.toByte, t.toByte)
    require(out == in, s"$name: the JVM must round-trip to itself under ($a, $t), got ${out.take(80)}…")
    pair(entry(name, kind, description, in), a, t)
  }

  private def rejectAt(name: String, kind: String, description: String, bytes: Array[Byte], mention: String,
                       a: Int, t: Int): Json = {
    val in = hex(bytes)
    Try(WireCanonicalize.canonicalize(kind, in, a.toByte, t.toByte)) match {
      case Success(out) => sys.error(s"$name: the JVM must REJECT under ($a, $t), but it round-tripped to ${out.take(80)}…")
      case Failure(e)   => require(causes(e).exists(_.contains(mention)), s"$name: want '$mention' in ${causes(e).mkString(" <- ")}")
    }
    pair(entry(name, kind, description, in, "error" -> Json.fromString("errored")), a, t)
  }

  private val value = "Input 0's extension is {0: the constant empty Coll[Int => Int]} (0c 70 01 04 04 00 00)."
  private val reads = "TypeSerializer reads the function type code 0x70 only when the context's tree version is at least " +
    "3 (TypeSerializer.scala:215 at v6.0.7)."
  private val unknown = "Otherwise the code falls to the unknown-type branch, whose rule the activated version picks " +
    "(:228-236): 1018 from activated 3, 1008 below."
  private val unequal = "A node reads a transaction under an equal pair; this unequal one is the pair a tree's own " +
    "constants meet, and the entry pins which half decides."

  /** The transaction entries by wire slot: v6 (activated 3), v5 (2), v4 (0 or 1). (4, 4), a v5 block's pair, has no
    * wire slot; it waits on the wire tier's `any/` decision. */
  private def txV6: Seq[Json] = Seq(
    acceptAt("tx-ext-func-3-3-accept#0", "Transaction",
      s"$value Under (3, 3), a v4 block's pair: $reads It parses, and the writer writes it back under the same pair. " +
      "Round-trip identity.", extTx, 3, 3),
    rejectAt("tx-ext-func-3-0-reject#1", "Transaction",
      s"$value Under (3, 0): $reads Tree version 0 is not, so the code reaches the unknown-type branch, and activated 3 " +
      s"picks rule 1018. The JVM rejects. $unequal", extTx, "1018", 3, 0),
    rejectAt("tx-ext-func-3-2-reject#2", "Transaction",
      s"$value Under (3, 2): tree version 2 is below 3, so rule 1018 again. The JVM rejects. $unequal", extTx, "1018", 3, 2))
  private def txV5: Seq[Json] = Seq(
    rejectAt("tx-ext-func-2-2-reject#0", "Transaction",
      s"$value Under (2, 2), a peer's transaction while block version 3 was current: $reads $unknown Rule 1008: the JVM " +
      "rejects.", extTx, "1008", 2, 2))
  private def txV4: Seq[Json] = Seq(
    rejectAt("tx-ext-func-1-1-reject#0", "Transaction",
      s"$value Under (1, 1), the default a block below version 4 is read under: $reads $unknown Rule 1008: the JVM " +
      "rejects.", extTx, "1008", 1, 1),
    rejectAt("tx-ext-func-0-0-reject#1", "Transaction",
      s"$value Under (0, 0): rule 1008, the JVM rejects.", extTx, "1008", 0, 0),
    acceptAt("tx-ext-func-1-3-accept#2", "Transaction",
      s"$value Under (1, 3): the tree version alone decides the read, and it is 3, so it parses although the activated " +
      s"version is 1. Round-trip identity. $unequal", extTx, 1, 3))

  // ── the node's own path: block sections ──────────────────────────────────────────────────────────────
  private val BlockKind = "BlockTransactions"
  private def section(blockVersion: Int, tx: Array[Byte]): Array[Byte] =
    digest("santa:wftv:header") ++ vlqU32(10000000L + blockVersion) ++ vlqU32(1L) ++ tx
  private val nominal = "The entry's version pair is nominal: the section's own block version decides the context."

  /** (the v3 sections, in the v5 slot at the nominal (2, 2); the v4 sections, in the v6 slot at (3, 3)) */
  private def blockEntries: (Seq[Json], Seq[Json]) = {
    val v3 = "A block section of version 3 is read outside any version context (BlockTransactions.scala:184-202), at " +
      "the default (1, 1), where the function type code is unknown: rule 1008. The section does not parse."
    val v4 = "A block section of version 4 is read under (3, 3), where the function type parses, and written back under " +
      "(4, 4) (BlockTransactions.scala:142-159). Round-trip identity."
    (Seq(
      rejectAt("block-v3-output-register-func-reject#0", BlockKind,
        s"The section holds one transaction whose output's R4 is the constant empty Coll[Int => Int] (0c 70 01 04 04 00 " +
        s"00). $v3 $nominal", section(3, outTx), "1008", 2, 2),
      rejectAt("block-v3-extension-func-reject#1", BlockKind,
        s"The section holds one transaction whose input carries {0: the same value}. $v3 $nominal",
        section(3, extTx), "1008", 2, 2)), Seq(
      acceptAt("block-v4-extension-func-accept#0", BlockKind,
        s"The section holds one transaction whose input carries {0: the same value}. $v4 $nominal",
        section(4, extTx), 3, 3),
      acceptAt("block-v4-output-register-func-accept#1", BlockKind,
        s"The section holds one transaction whose output's R4 is that value. $v4 Validating the transaction fails, " +
        s"though: verifyOutput writes the output at (1, 1) (transaction vectors evaluated-values-spend #63). $nominal",
        section(4, outTx), 3, 3)))
  }

  /** These files' verdicts turn on the version context, and the envelope says so (`activation_rule`). */
  private def activationRule(env: Json): Json = env.mapObject(o => io.circe.JsonObject.fromIterable(o.toList.flatMap {
    case kv @ ("blessed_by", _) => Seq(kv, "activation_rule" -> Json.True)
    case kv                     => Seq(kv)
  }))

  /** slot -> op -> envelope. */
  def extract(): Map[String, Map[String, Json]] = {
    val (blockV3, blockV4) = blockEntries
    def env(op: String, es: Seq[Json]): Json = activationRule(envelope(op, es))
    Map(
      "v6" -> Map(OpTx -> env(OpTx, txV6), OpBlock -> env(OpBlock, blockV4)),
      "v5" -> Map(OpTx -> env(OpTx, txV5), OpBlock -> env(OpBlock, blockV3)),
      "v4" -> Map(OpTx -> env(OpTx, txV4)))
  }

  /** Staging: `<outDir>/<slot>/<op>.json`. */
  def writeVectors(outDir: java.nio.file.Path): Unit =
    extract().foreach { case (slot, ops) =>
      SpecExtract.writeStaging(s"AuthoredWireFunctionTypeVersions/$slot", ops, outDir.resolve(slot))
    }
}
