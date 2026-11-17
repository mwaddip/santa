package santa

// Transaction-tier companion of AuthoredEvalAvlTreeItemBind (sigma-rust's santa-probe-avltree-item-bind, 2026-11-17;
// THE RULE is there): the AvlTree data methods with a key, a keys item or an entry of another kind.
//
// For every (method, tree, carrier, outcome class, tree version) of the eval rows, the first row in the table's order:
// the outcome class is the eval oracle's (an answer, or where it refuses: no collection, no pair, a collection of
// another item type, the reflective call, the proof failing first). Each is the tree
// `sigmaProp({ val v = <row>; true })`, true whatever the call answers, at the row's tree version, guarding a box SPENT
// through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful): at activated 3 (block 4, the storage-rent context)
// and, for a tree-v0 row, at activated 2 too (the window re-linked at block 3, AuthoredTxTreeVersion.Block3). The rows
// read no context: the box holds no register or token and the input no extension. A row the JVM answers spends; one it
// refuses does not. Measured first (santa.spike.AvlTreeItemBindProbe): every one of the 4,729 row-activations agrees as
// a spend with the eval tier.
//
// extract() FAILS LOUD unless each verdict is the eval oracle's (an answer spends, a throw does not).

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxAvlTreeItemBind {
  val Op = "avltree-item-bind"
  val Source = "santa:authored-avltree-item-bind"
  val Paths: Map[Int, String] = Map(
    2 -> "transaction/v5/authored/avltree-item-bind.json",
    3 -> "transaction/v6/authored/avltree-item-bind.json")
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H
  private val E = AuthoredEvalAvlTreeItemBind

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  private def treeAt(tv: Int, root: String): String =
    if (tv == 0) "00" + root else f"${8 + tv}%02x" + vlq(root.length / 2) + root

  /** `sigmaProp({ val v = <row>; true })`: BoolToSigmaProp(BlockValue(ValDef(11, <row>)), true). */
  def rootOf(body: String): String = "d1d801d60b" + body + "0101"

  /** The eval oracle's outcome class of a row. */
  def outcomeClass(o: E.Outcome): String = o match {
    case E.Answer(_, _)    => "answers"
    case E.ThrowsAtEval(t) => E.refusal(t).toString
    case o                 => sys.error(s"unexpected: ${E.said(o)}")
  }

  /** One spend: an eval row at one activated version. */
  final case class Spend(row: AuthoredEvalAvlTreeItemBind.Row, outcome: AuthoredEvalAvlTreeItemBind.Outcome) {
    def name: String = row.name
    def activated: Int = row.activated
    def answers: Boolean = outcome.isInstanceOf[E.Answer]
    def treeHex: String = treeAt(row.treeVersion, rootOf(row.body))
    def slug: String = row.slug
    /** The tree as the box writer writes it, which is what a box in a store holds (the writer re-writes from the AST;
      * the kept-tree-bytes round). It differs from the row's bytes where the row writes a `Coll[Coll[<primitive>]]`
      * type in the long form, `0c` + the inner collection's code. */
    def writtenTreeHex: String = VersionContext.withVersions(3, 3)(hx(ErgoTreeSerializer.DefaultSerializer
      .serializeErgoTree(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex)))))
  }

  /** Per (method, tree, carrier, outcome class, tree version), the first row in the table's order, at each activated
    * version of the row. Every row-activation of the table counts here, the charge-order family's too (the transaction
    * tier holds none of them). */
  def spends(): Vector[Spend] = {
    val all = E.allRows().map(r => Spend(r, E.outcome(r)))
    val firstOfName = all.filter(_.activated == 3)
    val picked = firstOfName.groupBy(s => (s.row.method, s.row.tree, s.row.carrier, outcomeClass(s.outcome), s.row.treeVersion))
      .values.map(_.head.name).toSet
    all.filter(s => picked(s.name))
  }

  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  def description(s: Spend): String = {
    val r = s.row
    val why = (s.outcome, r.control) match {
      case (E.Answer(_, _), true)  => "CONTROL: every item well-typed; the call answers and the box SPENDS."
      case (E.Answer(_, _), false) =>
        "SEPARATING ROW: the item of another kind is never read (" +
          (if (r.tree == "flag-off") "the tree's flag is off, so the call answers None first"
           else "the proof does not belong to the tree, so the first entry fails and the call stops with None") +
          "): the call answers and the box SPENDS. A port that reads or casts every item first refuses it."
      case (E.ThrowsAtEval(_), true) =>
        "CONTROL: every item well-typed, but the proof does not belong to the tree, so the first operation fails, the " +
          "call throws and the box does NOT spend."
      case (E.ThrowsAtEval(t), false) =>
        val where = E.refusal(t) match {
          case E.NotAColl if r.method == "remove" => "`operations(i).toArray` casts the key to `Coll`, inside the cost block"
          case E.NotAColl       => "the item is bound and cast to `Coll`, before the operation's cost"
          case E.NotAPair       => "`case (key, value)` casts the entry to a pair, before the operation's cost"
          case E.NotBytes       => "`.toArray` casts a key or value of another item type to `byte[]`, after the operation's cost"
          case E.ReflectiveCall => "the reflective call refuses the key argument of another class"
          case E.ProofFails     => "the first (valid) operation fails before the item of another kind is reached"
        }
        s"SEPARATING ROW: the JVM throws ($where), so the box does NOT spend."
      case (o, _) => sys.error(s"${r.name}: ${E.said(o)}")
    }
    val written = if (s.writtenTreeHex == s.treeHex) "" else
      " The row writes the collection's type in the long form (`0c` + the inner collection's code); the box holds the " +
        "writer's form of the same tree, as a box in a store always does."
    s"[${r.name}] `sigmaProp({ val v = tree.${r.method}(<argument>, proof); true })` (tree v${r.treeVersion}) guards " +
      s"the box being SPENT at activated ${s.activated}, under nine headers; on ${E.treeText(r)}, the argument " +
      s"${E.argument(r)}. $why$written"
  }

  /** The spend's box, its transaction's bytes, and the JVM's verdict. */
  def validate(s: Spend): (org.ergoplatform.ErgoBox, String, TxEngine.Verdict) = {
    val (a, c) = (s.activated, ctxOf(s.activated))
    val tree = VersionContext.withVersions(3, 3)(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(s.treeHex)))
    require(tree.root.isRight, s"${s.name}: the tree parses")
    val bx = VersionContext.withVersions(3, 3)(box(s"santa:aib:${s.name}:a$a", V, 1, tree))
    require(VersionContext.withVersions(3, 3)(hex(bx.bytes)).contains(s.writtenTreeHex), s"${s.name}: the box's tree")
    val txHex = VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(input(bx, ext())), Seq(candidate(V, 1))))))
    (bx, txHex, TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil, c.headersHex, c.preHeader,
      AuthoredTxStorageRent.params))
  }

  def extract(): Map[Int, Json] = spends().groupBy(_.activated).map { case (a, ss) =>
    val c = ctxOf(a)
    val entries = ss.zipWithIndex.map { case (s, i) =>
      val (bx, txHex, v) = validate(s)
      require(v.valid == s.answers, s"${s.name} a$a: the eval oracle ${E.said(s.outcome)}, got valid=${v.valid} " +
        v.reason.getOrElse(""))
      Json.obj(
        "name"                 -> Json.fromString(s"avltree-item-bind-spend-${s.slug}-a$a#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(description(s)),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader"            -> c.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(s.row.treeVersion)),
        "expected"             -> Json.obj(
          "valid"  -> Json.fromBoolean(v.valid),
          "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
          "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
    }
    a -> Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = extract().foreach { case (a, env) =>
    val path = vectorsRoot.resolve(Paths(a))
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
