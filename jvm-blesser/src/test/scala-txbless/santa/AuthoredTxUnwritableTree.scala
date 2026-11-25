package santa

// AuthoredTxUnwritableTree — the transaction-tier home of the nine eval entries whose tree holds a CONSTANT of a 0- or
// 1-item tuple type, a type `TypeSerializer` reads and refuses to write (`TypeSerializer.scala:94-95`).
// Request: `~/projects/sigma-rust/prompts/santa-reauthor-unwritable-tree-entries.md` (the user's ruling: re-author,
// not discard). Measured by `santa.spike.UnwritableTreeSpendProbe` (gitignored), on ergo-core 6.0.7:
//
// THE NINE SPLIT BY THEIR ROOT.
//   - A SigmaProp root (4): the tree is parsed, and writing it refuses the type, so a transaction creating such a box
//     has no id (`Invalid Tuple type with less than 2 items`): no box can carry it, and the eval entry's SELF cannot
//     exist. Those four are re-authored here as SPENDS, and their eval entries are deleted.
//   - Any other root (5): a non-SigmaProp root cannot be a script, so the JVM keeps the tree as its bytes and writes
//     them back unchanged. A transaction CREATING such a box is valid (12105, the control's cost) and the box exists,
//     though nothing can spend it. Those five eval entries STAY; the OUTPUTS family below pins the creation.
//
// THE SPEND FORM. The box is guarded by `executeFromVar[SigmaProp](1)` (`DeserializeContext`), which is writable;
// the script arrives as `Coll[Byte]` in the spender's context var 1, so the unwritable type lives only in data — the
// form a node can meet. `DeserializeContext` has no eval handler (the substitution happens in
// `Interpreter.fullReduction`, before reduction), which is why these are spends and not eval entries. The
// serialize row compares with `Coll(2)`, what the write gives: its eval entry compared with `Coll(0)` and was FALSE,
// which a spend could not tell from a throw.
//
// Blessed through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under the storage-rent vectors'
// synthetic context, as every tx family is. `reason` is diagnostic only (runner contract §2) and is not matched.

import io.circe.Json
import org.ergoplatform.ErgoBox
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ByteArrayConstant, ErgoTree}
import sigma.serialization.ErgoTreeSerializer
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxUnwritableTree {
  val SpendPath  = "transaction/v6/authored/unwritable-tree-spend.json"
  val OutputPath = "transaction/v6/authored/non-sigmaprop-root-output.json"
  val SpendOp    = "unwritable-tree-spend"
  val OutputOp   = "non-sigmaprop-root-output"
  val Source     = "santa:authored-unwritable-tree"
  private val Activated = 3
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H

  /** `executeFromVar[SigmaProp](1)` at tree version 3, size-flagged: the spends' box. */
  val CarrierTree = "0b03d40801"

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated.toByte, Activated.toByte)(f)
  private def tree(h: String): ErgoTree = ctx(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(h)))

  /** `(slug, var 1's SigmaProp expression, wantValid, what)`. */
  private val spendRows: Seq[(String, String, Boolean, String)] = Seq(
    ("serialize_one_item_tuple", "d193dc6a03dd01600104020e0102", true,
      "`sigmaProp(Global.serialize(c) == Coll(2))`, `c` a CONSTANT of type `(Int)` holding 1, read from the spender's " +
      "context var 1: **valid**. The write of a tuple VALUE writes its items and no type, so the refusal that keeps " +
      "this tree out of every box never fires here. Replaces the eval entry `serialize-one-item-tuple-accept#0`, " +
      "whose tree no box can carry. **SEPARATING:** an implementation that refuses the one-item type at the read " +
      "refuses a spend the JVM accepts."),
    ("const_one_item_type", "d1938c60010402010402", false,
      "`sigmaProp(c._1 == 1)`, `c` a CONSTANT of type `(Int)`, read from the spender's context var 1: **invalid**, " +
      "`Invalid type returned by evaluator` — `toDslTuple` gives a non-pair its raw `Coll[Any]`, and `_1` does not " +
      "reach an `Int` in it. Replaces the eval entry `const_one_item_type-errored#0`. **SEPARATING THE OTHER WAY:** " +
      "an implementation that answers `_1` lets a spender take the box."),
    ("const_zero_item_type", "d19360006000", false,
      "`sigmaProp(c == c)`, `c` a CONSTANT of type `()`, read from the spender's context var 1: **invalid**, " +
      "`Unsupported tuple type ()`. Replaces the eval entry `const_zero_item_type-errored#1`. **SEPARATING THE " +
      "OTHER WAY:** an implementation that compares the empty tuple lets a spender take the box."),
    ("const_one_item_type_eq_self", "d1936001040260010402", false,
      "`sigmaProp(c == c)`, `c` a CONSTANT of type `(Int)`, read from the spender's context var 1: **invalid**, " +
      "`Unsupported tuple type (SInt$)` — equality refuses the arity of the type, as the `()` row shows it is not " +
      "its emptiness. Replaces the eval entry `const_one_item_type_eq_self-errored#9`. **SEPARATING THE OTHER " +
      "WAY.**"),
    ("control", "d193b1480404040204060406", true,
      "**THE CONTROL** — `sigmaProp(SizeOf((1,2,3)) == 3)` through the same carrier: a writable tuple type, so it " +
      "shows the refusals above are about their scripts and not about the carrier, the variable or the context."))

  /** `(slug, the eval entry's tree, the eval entry)`: the five non-SigmaProp roots, which a box can carry. */
  val OutputTrees: Seq[(String, String, String)] = Seq(
    ("selectfield_non_pair", "08068c6001040a01", "eval/v5/authored/SelectField.non_pair.json selectfield-non-pair-errored#0"),
    ("unit_tuple_size", "0b03b16000", "eval/v6/authored/Tuple.non_pair_value_ops.json unit_tuple_size-accept#45"),
    ("one_item_tuple_size", "0b05b160010402", "eval/v6/authored/Tuple.non_pair_value_ops.json one_item_tuple_size-accept#46"),
    ("update_many_one_item_tuple_updates", "0b11b1dc0c151003020406021001006001040e",
      "eval/v6/authored/Tuple.non_pair_value_ops.json update_many_one_item_tuple_updates-accept#49"),
    ("subst_constants_positions_as_tuple", "0b17740e0d1b0b02040e040ed1937300730160010400100112",
      "eval/v6/authored/Tuple.non_pair_value_type_directed.json subst_constants_positions_as_tuple-errored#7"))

  private def entry(name: String, what: String, bx: ErgoBox, txHex: String, v: TxEngine.Verdict, tv: Int): Json =
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString(Source),
      "description"          -> Json.fromString(what),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
      "preHeader"            -> AuthoredTxStorageRent.preHeader,
      "parameters"           -> AuthoredTxStorageRent.params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(Activated), "ergoTree" -> Json.fromInt(tv)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))

  private def validate(txHex: String, bx: ErgoBox): TxEngine.Verdict =
    TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil,
      AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)

  private def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString(op),
    "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
    "entries"    -> Json.arr(entries: _*))

  def extractSpends(): Json = {
    val carrier = tree(CarrierTree)
    require(ctx(hex(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(carrier))) == CarrierTree,
      "the carrier is in the writer's form")
    envelope(SpendOp, spendRows.zipWithIndex.map { case ((slug, inner, wantValid, what), i) =>
      val bx    = ctx(box(s"santa:uwt:spend:$slug", V, 1, carrier))
      val txHex = ctx(hex(txBytes(tx(Seq(input(bx, ext(1.toByte -> ByteArrayConstant(b(inner))))), Seq(candidate(V, 1))))))
      val v     = validate(txHex, bx)
      require(v.valid == wantValid, s"spend $slug: want valid=$wantValid, got ${v.valid} ${v.reason.getOrElse("")}")
      entry(s"unwritable-tree-spend-$slug#$i", what, bx, txHex, v, Activated)
    })
  }

  def extractOutputs(): Json = {
    val rows = OutputTrees.map { case (slug, treeHex, evalEntry) =>
      (slug, Some(treeHex),
        s"A transaction CREATING a box guarded by the tree of `$evalEntry` (`$treeHex`): **valid**, at the control's " +
        "cost, its output carrying those bytes unchanged. The root is not a SigmaProp, so the JVM keeps the tree as " +
        "its bytes and never re-writes the tuple type in it; the transaction and the box both have ids. Nothing can " +
        "spend the box, but it exists and other scripts can read it. **SEPARATING:** an implementation that rebuilds " +
        "the tree to write it refuses a transaction the JVM accepts.")
    } :+ (("control", None,
      "**THE CONTROL** — the same transaction creating a box guarded by `sigmaProp(true)`: the cost every row above " +
      "matches."))
    envelope(OutputOp, rows.zipWithIndex.map { case ((slug, treeHex, what), i) =>
      val bx     = ctx(box(s"santa:uwt:output:$slug", V, 1))
      val out    = treeHex.map(h => candidate(V, 1, tree(h))).getOrElse(candidate(V, 1))
      val txHex  = ctx(hex(txBytes(tx(Seq(input(bx, ext())), Seq(out)))))
      treeHex.foreach(h => require(txHex.contains(h), s"output $slug: the transaction must carry the tree as given"))
      val v = validate(txHex, bx)
      require(v.valid, s"output $slug: want valid, got ${v.reason.getOrElse("")}")
      // version.ergoTree is the version the SPENT scripts verify under: here only `sigmaProp(true)`, a v0 tree
      entry(s"non-sigmaprop-root-output-$slug#$i", what, bx, txHex, v, 0)
    })
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit =
    Seq(SpendPath -> extractSpends(), OutputPath -> extractOutputs()).foreach { case (p, j) =>
      val path = vectorsRoot.resolve(p)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, j.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
