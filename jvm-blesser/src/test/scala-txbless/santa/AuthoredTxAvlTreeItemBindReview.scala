package santa

// Transaction-tier companion of AuthoredEvalAvlTreeItemBindReview (sigma-rust's santa-probe-avltree-item-bind-2).
//
// Two kinds of spend, through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under nine headers, at
// activated 3 (block 4) and, for a tree below version 3, at activated 2 (AuthoredTxTreeVersion.Block3):
//  * `tree`: sigma-rust's whole trees (review/rv-trees.txt, `sigmaProp(<call>.size == 1)` / `sigmaProp(<call>.isDefined)`
//    at tree versions 0 to 3), each in a P2PK-free box; the jars' `fullReduction` (jvm-rv-trees-act{2,3}.txt) is
//    TrueProp exactly where the box spends. Left out: the two tree-v0 `insertOrUpdate` trees, which no box can hold
//    (method 16 is tree-v3, and an unsized tree cannot degrade), and a tree above the activated version (the
//    tree-version-above-activated families hold that rule).
//  * `row`: per set, method, tree, carrier and outcome class the first eval row, at each of its slots, as the tree
//    `sigmaProp({ val v = <row>; true })`. A context row's box is sigma-rust's SELF with that tree spliced in (its
//    registers as their raw bytes), and the input's extension its raw bytes (variables 1 to 7); a context-free row's
//    box holds nothing else. Measured first (santa.spike.AvlTreeItemBindReviewProbe): all 1,529 row spends agree with
//    the eval tier.
//
// extract() FAILS LOUD unless each verdict is the jars' (a tree) or the eval oracle's (a row).

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxAvlTreeItemBindReview {
  val Op = "avltree-item-bind-review"
  val Source = "santa:authored-avltree-item-bind"
  val Paths: Map[Int, String] = Map(
    2 -> "transaction/v5/authored/avltree-item-bind-review.json",
    3 -> "transaction/v6/authored/avltree-item-bind-review.json")
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H
  private val E = AuthoredEvalAvlTreeItemBindReview

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def treeAt(tv: Int, root: String): String =
    if (tv == 0) "00" + root else f"${8 + tv}%02x" + E.vlq(root.length / 2) + root
  private def written(treeHex: String): String = VersionContext.withVersions(3, 3)(hx(ErgoTreeSerializer.DefaultSerializer
    .serializeErgoTree(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex)))))

  sealed trait Spend {
    def name: String; def activated: Int; def treeVersion: Int; def treeHex: String; def spends: Boolean
    def slug: String; def description: String
  }

  /** A whole tree of sigma-rust's; `jars` its fullReduction at this activated version. */
  final case class TreeSpend(name: String, treeHex: String, activated: Int, jars: String) extends Spend {
    def treeVersion: Int = Integer.parseInt(treeHex.take(2), 16) & 0x07
    def spends: Boolean = jars.contains("TrueProp")
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
    def description: String = {
      val kind = if (name.contains("getMany")) "`sigmaProp(<call>.size == 1)`" else "`sigmaProp(<call>.isDefined)`"
      val call = name.split('|')(1)
      val read = if (jars.startsWith("[UNPARSED]"))
        " The tree is SIZED and names a method its version does not have, so it is kept unparsed and its spend fails."
        else ""
      s"[$name] sigma-rust's whole tree $kind over the call `$call` (tree v$treeVersion) guards the box being SPENT at " +
        s"activated $activated, under nine headers; the jars' fullReduction: ${jars.replaceAll(" \\(.*$", "…").take(120)}. " +
        s"The box ${if (spends) "SPENDS" else "does NOT spend"}.$read"
    }
  }

  /** An eval row as `sigmaProp({ val v = <row>; true })`. */
  final case class RowSpend(row: AuthoredEvalAvlTreeItemBindReview.Row, outcome: AuthoredEvalAvlTreeItemBindReview.Outcome)
      extends Spend {
    def name: String = row.name
    def activated: Int = row.activated
    def treeVersion: Int = row.treeVersion
    def treeHex: String = treeAt(row.treeVersion, "d1d801d60b" + row.body + "0101")
    def spends: Boolean = outcome.isInstanceOf[E.Answer]
    def slug: String = row.slug
    def description: String = {
      val ctx = if (row.inContext) " The box is sigma-rust's SELF (R4 = `(Coll[Byte](1), 5, 0)`, R5 = " +
        "`Coll((Coll[Byte](3), 5))`, R6 = `Coll[Int](10)`) with this tree, and the input's extension its variables 1 to 7."
        else ""
      s"[${row.name}] `sigmaProp({ val v = <row>; true })` (tree v${row.treeVersion}) guards the box being SPENT at " +
        s"activated ${row.activated}, under nine headers; the row: ${E.script(row, outcome).dropWhile(_ != ']').drop(2)
          .takeWhile(_ != '—').trim} — ${E.said(outcome)}. The box ${if (spends) "SPENDS" else "does NOT spend"}. " +
        s"${E.label(row, outcome)}$ctx" +
        (if (written(treeHex) == treeHex) "" else " The row writes a collection's type in the long form (`0c` + the " +
          "item's code); the box holds the writer's form of the same tree, as a box in a store always does.")
    }
  }

  def treeSpends(): Vector[TreeSpend] = {
    val j = Map(2 -> jars(2), 3 -> jars(3))
    E.lines("rv-trees.txt").map(_.split(" ")).flatMap { p =>
      val t = TreeSpend(p(0), p(1), 3, j(3)(p(0)))
      Seq(2, 3).map(a => t.copy(activated = a, jars = j(a)(p(0))))
        .filter(s => s.treeVersion <= s.activated && !s.jars.startsWith("PARSE-THROWS"))
    }
  }
  private def jars(a: Int): Map[String, String] =
    E.lines(s"jvm-rv-trees-act$a.txt").map { l => val i = l.indexOf('\t'); l.take(i) -> l.drop(i + 1) }.toMap

  /** The outcome class a selection keeps. */
  def outcomeClass(o: AuthoredEvalAvlTreeItemBindReview.Outcome): String = o match {
    case E.Answer(_, _)    => "answers"
    case E.ThrowsAtEval(t) => E.refusal(t).map(_.toString).getOrElse("other")
    case _                 => "parse"
  }
  def carrier(r: AuthoredEvalAvlTreeItemBindReview.Row): String =
    if (r.inContext) r.name.split('|')(2) else if (r.order) "order" else if (r.census) "census" else "case"

  def rowSpends(): Vector[RowSpend] = {
    val all = E.rows().map(r => RowSpend(r, E.outcome(r)))
    val picked = all.filter(_.activated == 3)
      .groupBy(s => (s.row.set, s.row.method, s.row.tree, carrier(s.row), outcomeClass(s.outcome), s.row.treeVersion))
      .values.map(_.head.name).toSet
    all.filter(s => picked(s.name))
  }

  def spends(): Vector[Spend] = treeSpends() ++ rowSpends()

  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  private val P2pkTree = "0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

  /** The spend's input box (hex), its transaction's bytes, and the JVM's verdict. */
  /** The tree the box holds: the writer's form, as a box in a store always holds (the census `substConstants` rows
    * write their new values' type `Coll[SigmaProp]` in the long form `0c08`, which the writer writes `14`). */
  def boxTree(s: Spend): String = written(s.treeHex)

  def validate(s: Spend): (String, String, TxEngine.Verdict) = {
    val c = ctxOf(s.activated)
    val (boxHex, txHex) = s match {
      case r: RowSpend if r.row.inContext =>
        require(E.SelfHex.startsWith("c0843d" + P2pkTree), "SELF layout")
        val raw = b("c0843d" + boxTree(s) + E.SelfHex.drop(6 + P2pkTree.length))
        val bx = VersionContext.withVersions(3, 3)(org.ergoplatform.ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(raw)))
        require(java.util.Arrays.equals(bx.id, scorex.crypto.hash.Blake2b256(raw)), s"${s.name}: box id")
        val e = VersionContext.withVersions(3, 3)(sigma.interpreter.ContextExtension.serializer.parse(SigmaSerializer.startReader(b(E.ExtHex))))
        val w = { val wr = SigmaSerializer.startWriter(); VersionContext.withVersions(3, 3)(sigma.interpreter.ContextExtension.serializer.serialize(e, wr)); hx(wr.toBytes) }
        val txw = VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(org.ergoplatform.Input(bx.id,
          new sigma.interpreter.ProverResult(Array.emptyByteArray, e))), Seq(candidate(1000000L, 100))))))
        require(txw.indexOf(w) >= 0 && txw.indexOf(w) == txw.lastIndexOf(w), s"${s.name}: the extension in the tx")
        (hx(raw), txw.replace(w, E.ExtHex))
      case _ =>
        val tree = VersionContext.withVersions(3, 3)(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(s.treeHex)))
        val bx = VersionContext.withVersions(3, 3)(box(s"santa:aib-rv:${s.name}:${s.treeVersion}:a${s.activated}", V, 1, tree))
        (hex(bx.bytes), VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(input(bx, ext())), Seq(candidate(V, 1)))))))
    }
    (boxHex, txHex, TxEngine.validateBytes(txHex, Seq(boxHex), Nil, c.headersHex, c.preHeader, AuthoredTxStorageRent.params))
  }

  def extract(): Map[Int, Json] = spends().groupBy(_.activated).map { case (a, ss) =>
    val c = ctxOf(a)
    val entries = ss.zipWithIndex.map { case (s, i) =>
      val (boxHex, txHex, v) = validate(s)
      require(v.valid == s.spends, s"${s.name} a$a: expected spends=${s.spends}, got valid=${v.valid} ${v.reason.getOrElse("")}")
      val kind = s match { case _: TreeSpend => "tree"; case _ => "row" }
      Json.obj(
        "name"                 -> Json.fromString(s"avltree-item-bind-review-$kind-${s.slug}-a$a#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(s.description),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(Json.fromString(boxHex)),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader"            -> c.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(s.treeVersion)),
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
