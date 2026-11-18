package santa

// Authored eval vectors for sigma-rust's santa-probe-and-or-item-by-item-2 (2026-11-18, the addendum to the AND / OR
// item-by-item round that AuthoredEvalAndOrItemByItemRound (854bcff) vectored). Measured first in
// santa.spike.AndOrItemByItemRound2Probe (local): all 3,820 no-context row-activations, all 72 in the first request's
// context and all 806 in the addendum's as the jars on outcome, class, value and cost. The reply is
// prompts/drafts/sigma-rust-and-or-item-by-item-2-reply.md. Transaction companion: AuthoredTxAndOrItemByItemRound2 (the
// addendum's whole trees as spends).
//
// What the addendum adds (sigma-rust's measurement on the 6.0.7 jars, re-measured here):
//  * a SEGREGATED tuple of three never reaches the node: `ConstantPlaceholder.eval` checks the constant against its type
//    (6.0.7 `values.scala`), and a tuple of other than two items is no value of its type there, so the reduction throws
//    (`RuntimeException: Unsupported tuple type`); the unsegregated twin (the tuple inline) reduces, and the same tuple
//    INSIDE a segregated collection or pair constant passes the check;
//  * MORE producers of the collection than the first request named: the item of a `slice`, `append`, `updated`, `zip`
//    (either side) or `reverse` result, `ByIndex` with a default in range (tree v3), an `Option` constant's `.get` /
//    `getOrElse` (tree v3), `Global.deserializeTo`, `executeFromVar` / `DeserializeRegister` (the last two whole trees:
//    the transaction companion), and a collection that is no tuple
//    (`AND((false, true, true) ++ Coll(true).map(_ => Global))`);
//  * a carrier for the four kinds with no data form (`Global`, `CONTEXT`, `CONTEXT.preHeader`, a function):
//    `Coll(true).map(_ => <kind>)`; read, it throws (`ClassCastException`); after the deciding item, never read;
//  * the chunk boundaries of AND / OR over ten producers, 65,536 / 65,537 items, nested AND / OR, and the other callers
//    of the counting `addSeqCost` (`indexOf`, `flatMap`, the comparer's two loops, `substConstants`).
//
// THE FILES: AND / OR / XorOf / Exists / ForAll / Filter APPEND to 854bcff's `<Node>.item_by_item{,_v6}` (AND / OR under
// `nest` / `if` / a lambda / `fold` / a `ValDef` / `exists` go to the inner node's file); the context rows APPEND to
// `{AND,OR}.item_by_item_context{,_v6}` (each entry carries its own context: the first request's SELF, or the addendum's
// SELF (one token; R4..R8: `(true, 5, true)`, `(false, 5, true)`, their bytes as `Coll[Byte]`, a `Coll` of both) with
// variables 1, 2, 10..13 as RAW `Value` bindings on the rows whose tree reads a variable); NEW: `EQ.item_by_item_v6`,
// `Coll.indexOf_item_by_item_v6`, `Coll.flatMap_item_by_item_v6`, `SubstConstants.item_by_item_v6` (the jars' rows sit at
// activated 3 only) and `ConstantPlaceholder.segregated_tuple{,_v6}` (the whole trees that read no context, evaluated
// as the eval tier evaluates a tree: fullReduction's placeholders). LEFT OUT: a row-activation the destination file
// already holds (the same tree, activated version and context; the same outcome, pinned), and the whole trees whose
// root is no SigmaProp under the JVM's own parse (the 4-item `ph` rows: refused at parse; only the eval tier's lenient
// parse would evaluate them, to an answer no node gives) — they are in the transaction companion.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import io.circe.parser.parse
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.data.{SigmaBoolean, TrivialProp}
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}

object AuthoredEvalAndOrItemByItemRound2 {
  val Source = "santa:authored-and-or-item-by-item"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-and-or-item-by-item-2", "rows.tsv")
  val VectorsDir: java.nio.file.Path = java.nio.file.Paths.get("..", "vectors", "eval")
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  val R1: AuthoredEvalAndOrItemByItemRound.type = AuthoredEvalAndOrItemByItemRound

  // ── the contexts ────────────────────────────────────────────────────────────────────────────────
  private val L = AuthoredEvalSubstConstantsLift
  val P2pk = "0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  /** The addendum's SELF: 1,000,000 nanoErg, the generator's P2PK, created at 100, one token (`22`*32, 1,000), R4..R8. */
  val Self2: String = "c0843d" + P2pk + "6401" + "22" * 32 + "e80705" +
    "48010401010a0148010401000a010e0748010401010a010e0748010401000a010c4801040102010a01000a01" + "11" * 32 + "00"
  val Ext2Hex: String = "060148010401010a010248010401000a010a0e0748010401010a010b0e0748010401000a010c0e030d02010d0c" +
    "4801040102010a01000a01"
  def extOf(hex: String): Map[Int, String] = VersionContext.withVersions(3, 3) {
    val bytes = b(hex); val r = SigmaSerializer.startReader(bytes); val n = r.getUByte()
    (0 until n).map { _ =>
      val k = r.getByte().toInt; val s = r.position; sigma.serialization.ValueSerializer.deserialize(r)
      k -> hx(bytes.slice(s, r.position))
    }.toMap.ensuring(_ => r.remaining == 0, "bytes left after the extension")
  }
  lazy val Ext2: Map[Int, String] = extOf(Ext2Hex)
  sealed trait Ctx { def selfHex: String; def ext: Map[Int, String] }
  case object NoContext extends Ctx { def selfHex = ""; def ext: Map[Int, String] = Map.empty }
  case object FirstContext extends Ctx { def selfHex: String = R1.SelfHex; def ext: Map[Int, String] = R1.Ext }
  case object AddendumContext extends Ctx { def selfHex: String = Self2; def ext: Map[Int, String] = Ext2 }
  val OutputHex: String = R1.OutputHex
  private def extJson(e: Map[Int, Json]): Json = Json.obj(e.toSeq.sortBy(_._1).map { case (k, v) => k.toString -> v }: _*)
  def context(c: Ctx, activated: Int, ext: Map[Int, Json]): Json = Json.obj(
    "self_index" -> Json.fromInt(0), "inputs" -> Json.arr(Json.fromString(c.selfHex)), "data_inputs" -> Json.arr(),
    "outputs" -> Json.arr(Json.fromString(OutputHex)), "headers" -> Json.arr(Json.fromString(L.HeaderHex(activated))),
    "pre_header_hex" -> Json.fromString(L.preHeaderHex(activated)), "height" -> Json.fromInt(L.Height),
    "extension" -> extJson(ext), "input_extensions" -> Json.arr(extJson(ext)))

  // ── the rows ────────────────────────────────────────────────────────────────────────────────────
  case class Row(part: String, name: String, treeVersion: Int, activated: Int, bytes: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String) {
    val f: Vector[String] = name.split('|').toVector
    def whole: Boolean = part.startsWith("whole tree")
    def ctx: Ctx = part match {
      case "eval, the first request's context" => FirstContext
      case "eval, the addendum's context" | "whole tree, the addendum's context" => AddendumContext
      case _ => NoContext
    }
    def inContext: Boolean = ctx != NoContext
    def treeHex: String =
      if (whole) bytes else if (treeVersion == 0) "00" + bytes else f"${8 + treeVersion}%02x" + R1.vlq(bytes.length / 2) + bytes
    /** The node whose file the row joins: AND / OR under a wrapper go to the inner node's. */
    def node: String = f(0) match {
      case "nest" | "if" | "lambda3" | "exists" | "fold" | "valdef2" => if (f(1).startsWith("AND")) "AND" else "OR"
      case n => n
    }
    def op: String = {
      val base =
        if (whole) "ConstantPlaceholder.segregated_tuple"
        else if (inContext) s"$node.item_by_item_context"
        else node match {
          case "indexOf" => "Coll.indexOf_item_by_item"
          case "flatMap" => "Coll.flatMap_item_by_item"
          case "substConstants" => "SubstConstants.item_by_item"
          case n => s"$n.item_by_item"
        }
      base + (if (activated == 3) "_v6" else "")
    }
    def slug: String = name.replace('|', '-').replace("Coll[", "Coll_").replaceAll("[^A-Za-z0-9.+_=-]", "_").replaceAll("_+", "_")
  }
  def allRows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Row(c(0), c(1), c(2).toInt, a, c(4), c(5), c(6), c(7)))
    }
  }

  /** The JVM's own parse of a whole tree (the SigmaProp root check on): its root parses. */
  def strictRoot(r: Row): Boolean = Try(VersionContext.withVersions(r.activated.toByte, r.treeVersion.toByte)(
    ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(r.treeHex)))).toOption.exists(_.root.isRight)
  /** The eval tier's rows: every eval row, and the whole trees that read no context and whose root the JVM parses. */
  def evalRows(): Vector[Row] = allRows().filter(r => !r.whole || (r.part == "whole tree, no context read" && strictRoot(r)))

  // ── reads a variable (the extension travels only there) ─────────────────────────────────────────
  private def nodes(x: Any): Iterator[Any] = Iterator(x) ++ (x match {
    case _: sigma.ast.SType | _: sigma.ast.SMethod => Iterator.empty
    case p: Product => p.productIterator.flatMap(nodes)
    case s: Iterable[_] => s.iterator.flatMap(nodes)
    case _ => Iterator.empty
  })
  def readsVariable(r: Row): Boolean = r.inContext && Try(VersionContext.withVersions(3, 3)(
    sigma.santa.LenientErgoTree.deserialize(b(r.treeHex)).root.toOption.get)).toOption.exists(root => nodes(root).exists {
      case _: sigma.ast.GetVar[_] | _: sigma.ast.DeserializeContext[_] => true
      case m: sigma.ast.MethodCall => m.method.name == "getVar" || m.method.name == "getVarFromInput"
      case _ => false
    })
  def valueBinding(h: String): Json = R1.valueBinding(h)
  def extFor(r: Row): Map[Int, Json] = if (readsVariable(r)) r.ctx.ext.map { case (k, h) => k -> valueBinding(h) } else Map.empty
  def contextOf(r: Row): Option[Json] = if (r.inContext) Some(context(r.ctx, r.activated, extFor(r))) else None

  // ── the oracle ──────────────────────────────────────────────────────────────────────────────────
  type Outcome = R1.Outcome
  def outcomeWith(r: Row, ext: Map[Int, Json]): Outcome = R1.parse(r.treeHex, r.activated, r.treeVersion) match {
    case Left(t)        => R1.RefusedAtParse(t)
    case Right(Left(t)) => R1.RefusedAtParse(t)
    case Right(Right(())) =>
      val res =
        if (r.inContext) EvalCore.evalFullContextOutcome(r.treeHex, 0, Seq(r.ctx.selfHex), Nil, Seq(OutputHex),
          Seq(L.HeaderHex(r.activated)), L.preHeaderHex(r.activated), Seq(ext), None, r.activated.toByte)._2
        else EvalCore.evalAppliedOutcome(r.treeHex, IntInput, r.activated.toByte)._2
      res match { case Right((v, c)) => R1.Answer(v, c); case Left(t) => R1.ThrowsAtEval(t) }
  }
  def outcome(r: Row): Outcome = outcomeWith(r, extFor(r))
  private val TrueHex = hx(SigmaBoolean.serializer.toBytes(TrivialProp.TrueProp))
  private val FalseHex = hx(SigmaBoolean.serializer.toBytes(TrivialProp.FalseProp))
  /** A value as the jars' rows write it (a trivial SigmaProp as TrueProp / FalseProp). */
  def text(v: Json): String =
    if (v.hcursor.get[String]("kind").toOption.contains("SigmaProp")) v.hcursor.get[String]("raw_hex").toOption.get.toLowerCase match {
      case TrueHex => "TrueProp"; case FalseHex => "FalseProp"; case h => s"<sigmaprop $h>"
    } else R1.text(v)

  // ── the scripts ─────────────────────────────────────────────────────────────────────────────────
  def root(r: Row): String = {
    val t = Try(VersionContext.withVersions(3, 3)(sigma.santa.LenientErgoTree.deserialize(b(r.treeHex)).root.toOption.get.toString))
      .toOption.getOrElse("<refused at parse>").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll(R1.LambdaClass, "\\$\\$Lambda")
      .replaceAll("\\s+", " ")
    if (t.length <= 220) t else t.take(220) + "…"
  }
  def said(o: Outcome): String = o match {
    case R1.Answer(v, cost) => s"${text(v)} at cost $cost"
    case other              => R1.said(other)
  }
  private val SeqCost = "a caller of the counting `addSeqCost` (sigma-state charges for the items the loop walked, a " +
    "chunk at a time), its stop placed at a chunk boundary; a port that charges for the whole collection, or per item, " +
    "is off this cost."
  /** What the row varies, as the addendum describes it. */
  def label(r: Row): String = (r.part, r.f(0), r.f(1)) match {
    case ("whole tree, no context read", _, "ph") => "SEGREGATED: the tuple of three is the tree's constant; " +
      "`ConstantPlaceholder.eval` checks the constant against its type, and a tuple of other than two items is no value " +
      "of its type there, so the reduction throws (`Unsupported tuple type`). A port that reads the placeholder without " +
      "the check, or reduces the tree again with its constants substituted, answers here."
    case ("whole tree, no context read", _, "phColl" | "phPair") => "the tuple of three INSIDE a segregated collection or " +
      "pair constant: the placeholder's check passes (the constant is of its type), and the tree reduces."
    case ("whole tree, no context read", _, "phLadder") => "a segregated `Coll[Boolean]` constant: the placeholder passes, " +
      "and the node reads its items up to the deciding one."
    case ("whole tree, no context read", _, "inline") => "the UNSEGREGATED twin: the tuple inline is a `Constant`, which " +
      "no check meets, and the tree reduces."
    case ("eval, the first request's context", _, _) => "a kind with no data form (`Global`, `CONTEXT`, " +
      "`CONTEXT.preHeader`, a function) as an item, from `Coll(true).map(_ => <kind>)`: read, it throws " +
      "(`ClassCastException`); after the deciding item, it is never read."
    case ("eval, the addendum's context", _, c) => s"the collection from the producer `$c` (in the addendum's SELF and " +
      "extension): what reaches the node is read item by item, a tuple's items as a collection's."
    case (_, "AND" | "OR" | "XorOf", "indices") => "over a `Coll.indices` result (the specialised `Int` class): read, an " +
      "`Int` item is no Boolean (`ClassCastException`); empty, the node answers."
    case (_, "AND" | "OR", "doubled") => "65,536 or 65,537 items, built by doubling: the node reads up to the deciding " +
      "item and charges for the items read (AND 10 + 5 per 32, OR 5 + 5 per 64)."
    case (_, "AND" | "OR", p) => s"items from the producer `$p`, the deciding item placed at a chunk boundary: the node " +
      "reads up to it and charges for the items read (AND 10 + 5 per 32, OR 5 + 5 per 64); a port that charges for the " +
      "whole collection is above this cost."
    case (_, "XorOf", _) => "XorOf casts the whole array: it reads every item, whatever their values."
    case (_, "nest" | "if" | "lambda3" | "exists" | "fold" | "valdef2", _) => s"AND / OR under `${r.f(0)}`: each " +
      "evaluation stops at its own deciding item and charges for the items it read."
    case (_, "EQ" | "indexOf" | "flatMap" | "substConstants", _) => SeqCost
    case (_, "Exists" | "ForAll" | "Filter", _) => "a throwing (`1 / 0`) or a costly (`blake2b256`) lambda after the " +
      "deciding item: Exists / ForAll stop at the deciding item and never call it there; Filter calls it on every item."
  }
  def script(r: Row, o: Outcome): String =
    s"[${r.name}] `${root(r)}` — ${said(o)}. ${label(r)}  // tree v${r.treeVersion}, activated ${r.activated}"

  // ── the vectors ─────────────────────────────────────────────────────────────────────────────────
  def slot(op: String): String = if (op.endsWith("_v6")) "v6" else "v5"
  /** The entries each file held at 854bcff, which this family appends to (a new file: none). The base is these entries,
    * not the whole committed file, so a re-run after this family is committed reproduces it. */
  val Before: Map[String, Int] = Map(
    "AND.item_by_item" -> 1329, "AND.item_by_item_v6" -> 2811, "OR.item_by_item" -> 1331, "OR.item_by_item_v6" -> 2815,
    "XorOf.item_by_item" -> 1018, "XorOf.item_by_item_v6" -> 2189, "Exists.item_by_item_v6" -> 756,
    "ForAll.item_by_item_v6" -> 756, "Filter.item_by_item_v6" -> 756,
    "AND.item_by_item_context" -> 40, "AND.item_by_item_context_v6" -> 80,
    "OR.item_by_item_context" -> 40, "OR.item_by_item_context_v6" -> 80)
  /** The committed file of an op, if any. */
  def committed(op: String): Option[Json] = {
    val p = VectorsDir.resolve(slot(op)).resolve("authored").resolve(SpecExtract.slug(op) + ".json")
    if (java.nio.file.Files.exists(p)) Some(parse(new String(java.nio.file.Files.readAllBytes(p), "UTF-8")).fold(throw _, identity))
    else None
  }
  def entriesOf(j: Json): List[Json] = j.hcursor.downField("entries").as[List[Json]].toOption.get
  /** The entries this family appends to: the first `Before(op)` of the committed file. */
  def base(op: String): List[Json] = {
    val n = Before.getOrElse(op, 0)
    committed(op).toList.flatMap(entriesOf).take(n).ensuring(_.size == n, s"$op: $n committed entries")
  }
  def key(treeHex: String, activated: Int, ctx: Option[Json]): (String, Int, String) =
    (treeHex, activated, ctx.map(_.noSpaces).getOrElse(""))
  def entryKey(e: Json): (String, Int, String) = key(e.hcursor.get[String]("tree_bytes_hex").toOption.get,
    e.hcursor.downField("version").get[Int]("activated").toOption.get, e.hcursor.downField("context").focus.filterNot(_.isNull))
  def rowKey(r: Row): (String, Int, String) = key(r.treeHex, r.activated, contextOf(r))

  /** Per op: the rows it appends, and the rows left out as a row-activation its file (or an earlier row) already holds. */
  def plan(): Map[String, (Vector[Row], Vector[Row])] = evalRows().groupBy(_.op).map { case (op, rs) =>
    val held = collection.mutable.Set[(String, Int, String)]() ++ base(op).map(entryKey)
    val (kept, dropped) = rs.partition(r => held.add(rowKey(r)))
    op -> (kept, dropped)
  }
  def rows(): Vector[Row] = plan().values.flatMap(_._1).toVector

  def entry(op: String, r: Row, n: Int): Json = {
    val o = outcome(r)
    val name = s"${r.slug}-a${r.activated}#$n"
    contextOf(r) match {
      case Some(ctx) =>
        val expected = o match {
          case R1.Answer(v, cost) => Json.obj("value" -> v, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
          case _ => Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
        }
        Json.obj("name" -> Json.fromString(name), "script" -> Json.fromString(script(r, o)),
          "tree_bytes_hex" -> Json.fromString(r.treeHex),
          "version" -> Json.obj("activated" -> Json.fromInt(r.activated), "ergoTree" -> Json.fromInt(r.treeVersion)),
          "context" -> ctx, "expected" -> expected)
      case None => o match {
        case R1.Answer(_, _) =>
          SpecExtract.authoredEntryV(op, script(r, o), r.treeHex, name, IntInput, r.activated.toByte, ergoTree = r.treeVersion)
        case _ =>
          SpecExtract.authoredRejectEntryV(op, script(r, o), r.treeHex, name, IntInput, r.activated.toByte, ergoTree = r.treeVersion)
      }
    }
  }

  /** Every file: its 854bcff entries first, unchanged, then this addendum's. */
  def extract(): Map[String, Json] = plan().map { case (op, (rs, _)) =>
    val prior = committed(op)
    val before = base(op)
    val entries = before ++ rs.zipWithIndex.map { case (r, i) => entry(op, r, before.size + i) }
    op -> (prior match {
      case Some(j) => j.mapObject(_.add("entries", Json.arr(entries: _*)))
      case None if op.contains("_context") => Json.obj("schema" -> Json.fromString("santa-eval/v6-fullctx"),
        "op" -> Json.fromString(op), "blessed_by" -> Json.fromString(Stamp), "source" -> Json.fromString(Source),
        "entries" -> Json.arr(entries: _*))
      case None => SpecExtract.authoredEnvelope(op, Nil, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
        .mapObject(_.add("entries", Json.arr(entries: _*)))
    })
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalAndOrItemByItemRound2", extract(), outDir)
}
