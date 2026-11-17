package santa

// Transaction-tier companion of AuthoredEvalAndOrItemByItem (AND / OR read item by item; THE RULE is there).
//
// Per node, group, class and shape (every ladder row; per tuple shape the first kind; per collection the empty one and
// the one-item one; every expression row; sigma-rust's row) at each of its slots, the tree `sigmaProp(<row>)` (sigma-rust's
// row is one already) guarding a box SPENT through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful): at
// activated 3 (block 4, the storage-rent context) and, for a tree-v0 row, at activated 2 too (AuthoredTxTreeVersion.Block3).
// The rows read no context. A row that answers true (or TrueProp) spends; false or a throw does not. Measured first
// (santa.spike.AndOrItemByItemProbe): every one of the 939 slots agrees as a spend with the eval tier, and every spend
// tree is in the writer's form.
//
// extract() FAILS LOUD unless each verdict is the eval oracle's.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxAndOrItemByItem {
  val Op = "and-or-item-by-item"
  val Source = "santa:authored-and-or-item-by-item"
  val Paths: Map[Int, String] = Map(
    2 -> "transaction/v5/authored/and-or-item-by-item.json",
    3 -> "transaction/v6/authored/and-or-item-by-item.json")
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H
  private val E = AuthoredEvalAndOrItemByItem

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase

  /** One spend: an eval slot and its outcome. */
  final case class Spend(slot: AuthoredEvalAndOrItemByItem.Slot, outcome: AuthoredEvalAndOrItemByItem.Outcome) {
    def row: AuthoredEvalAndOrItemByItem.Row = slot.row
    def name: String = row.name
    def activated: Int = slot.activated
    def root: String = if (row.body.startsWith("d1")) row.body else "d1" + row.body
    def treeHex: String =
      if (slot.treeVersion == 0) "00" + root else f"${8 + slot.treeVersion}%02x" + E.vlq(root.length / 2) + root
    /** What a node does: spends where the eval tier answers true (TrueProp for sigma-rust's row). */
    def spends: Boolean = outcome match {
      case E.Answer(v, _) => E.text(v) == "true" || E.text(v) == "<sigmaprop>"
      case _              => false
    }
  }

  /** The shape a selection keeps: every ladder and expression row, a tuple's shape, a collection's emptiness. */
  def shapeKey(r: AuthoredEvalAndOrItemByItem.Row): String = r.group match {
    case "ladder" | "expr" => r.label
    case "tuple"           => r.label.takeWhile(_ != '|')
    case "coll"            => r.label.dropWhile(_ != '|')
    case _                 => ""
  }

  def spends(): Vector[Spend] = {
    val all = E.slots().map(s => Spend(s, E.outcome(s)))
    val picked = all.map(_.row).distinct.groupBy(r => (r.op, r.group, E.cls(r), shapeKey(r))).values.map(_.head).toSet
    all.filter(s => picked(s.row))
  }

  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  def description(s: Spend): String = {
    val verdict = if (s.spends) "the call answers true and the box SPENDS" else s.outcome match {
      case E.Answer(_, _) => "the call answers false and the box does NOT spend"
      case _              => "the call throws and the box does NOT spend"
    }
    s"[${s.name}] `sigmaProp(<row>)` (tree v${s.slot.treeVersion}) guards the box being SPENT at activated " +
      s"${s.activated}, under nine headers; the row: ${s.row.script} — ${E.said(s.outcome)}: $verdict. ${E.label(s.row)}"
  }

  /** The spend's box, its transaction's bytes, and the JVM's verdict. */
  def validate(s: Spend): (org.ergoplatform.ErgoBox, String, TxEngine.Verdict) = {
    val (a, c) = (s.activated, ctxOf(s.activated))
    val tree = VersionContext.withVersions(3, 3)(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(s.treeHex)))
    require(VersionContext.withVersions(3, 3)(hx(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(tree))) == s.treeHex,
      s"${s.name}: the tree is in the writer's form")
    val bx = VersionContext.withVersions(3, 3)(box(s"santa:aoi:${s.name}:${s.slot.treeVersion}:a$a", V, 1, tree))
    val txHex = VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(input(bx, ext())), Seq(candidate(V, 1))))))
    (bx, txHex, TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil, c.headersHex, c.preHeader,
      AuthoredTxStorageRent.params))
  }

  def extract(): Map[Int, Json] = spends().groupBy(_.activated).map { case (a, ss) =>
    val c = ctxOf(a)
    val entries = ss.zipWithIndex.map { case (s, i) =>
      val (bx, txHex, v) = validate(s)
      require(v.valid == s.spends, s"${s.name} a$a: the eval oracle ${E.said(s.outcome)}, got valid=${v.valid} " +
        v.reason.getOrElse(""))
      Json.obj(
        "name"                 -> Json.fromString(s"and-or-item-by-item-spend-${s.slot.slug}-tv${s.slot.treeVersion}-a$a#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(description(s)),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader"            -> c.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(s.slot.treeVersion)),
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
