package santa

// Authored eval vectors for sigma-rust's santa-probe-and-or-item-by-item (2026-11-18, its queue item 41's round): the rows
// AuthoredEvalAndOrItemByItem (5fd218a) does not vary. Measured first in santa.spike.AndOrItemByItemRoundProbe (local):
// all 16,047 no-context row-activations and all 360 in the request's context as the jars on outcome, class, value and
// cost. THE RULE is AuthoredEvalAndOrItemByItem's; the reply is prompts/drafts/sigma-rust-and-or-item-by-item-reply.md.
// Transaction companion: AuthoredTxAndOrItemByItemRound (the request's whole trees).
//
// What the rows add (sigma-rust's measurement, read in 6.0.7 `trees.scala` / `transformers.scala` and re-measured here):
//  * every PRODUCER of the collection is the same case: a constant, a ConcreteCollection, a
//    ConcreteCollectionBooleanConstant (`85`), a `map`, `slice`, `append` or `filter` result, a tuple of Booleans;
//  * a tuple of three reaches the node as the input, as an item of a collection constant (`ByIndex`), as a pair's side
//    (`SelectField`), from a context variable and from a register at the tuple's own type, but NOT through a `ValDef`
//    or as a lambda's parameter (`Unsupported tuple type`); a collection node with an item of another type is refused
//    at parse (`Invalid type of collection value`);
//  * the NEIGHBOURS: `XorOf` casts the whole array; `Exists` / `ForAll` with the identity lambda stop at the deciding
//    item and add their cost over the LENGTH before the walk; `Filter` reads every item; `||` / `&&` cast the left
//    first and evaluate the right only if it decides nothing; `SigmaAnd` / `SigmaOr` evaluate and cast every item.
//
// THE FILES: per node `<Node>.item_by_item{,_v6}` (santa-eval/v2; AND and OR APPEND to 5fd218a's files, whose entries
// keep their numbers) and `{AND,OR,XorOf}.item_by_item_context{,_v6}` (santa-eval/v6-fullctx: the substConstants-lift
// header and pre-header, the request's SELF (R4..R9: the tuples, the collections, an empty Coll[Int]) and ONE output
// (the request's transaction has one: `OUTPUTS` as the input reads a box), its extension (variables 1 to 8) as RAW
// `Value` bindings on the rows that read a variable). Tree v0 at activated 2 and 3, tree v3 at 3 (the jars answer v1
// and v2 as v0). LEFT OUT: the 879 row-activations 5fd218a's files already hold (the same tree bytes and activated
// version, the same outcome: pinned in the test).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.serialization.SigmaSerializer

object AuthoredEvalAndOrItemByItemRound {
  val Source = "santa:authored-and-or-item-by-item"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-and-or-item-by-item", "rows.tsv")
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  // ── the context ─────────────────────────────────────────────────────────────────────────────────
  private val L = AuthoredEvalSubstConstantsLift
  val SelfHex: String = "c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798640006" +
    "48010401010a0148010401000a010d030510010a480401010a01001000" + "11" * 32 + "00"
  val ExtHex: String = "080148010401010a010248010401000a01030d03050410010a05040a06480401010a0100071000080d0302"
  lazy val Ext: Map[Int, String] = VersionContext.withVersions(3, 3) {
    val bytes = b(ExtHex); val r = SigmaSerializer.startReader(bytes); val n = r.getUByte()
    (0 until n).map { _ =>
      val k = r.getByte().toInt; val s = r.position; sigma.serialization.ValueSerializer.deserialize(r)
      k -> hx(bytes.slice(s, r.position))
    }.toMap.ensuring(_ => r.remaining == 0, "bytes left after the extension")
  }
  val OutputHex: String = AuthoredEvalAvlTreeItemBindReview.OutputHex
  def valueBinding(h: String): Json = Json.obj("kind" -> Json.fromString("Value"), "bytes_hex" -> Json.fromString(h))
  def extOf(r: Row): Map[Int, Json] = if (r.readsVariable) Ext.map { case (k, h) => k -> valueBinding(h) } else Map.empty
  private def extJson(e: Map[Int, Json]): Json = Json.obj(e.toSeq.sortBy(_._1).map { case (k, v) => k.toString -> v }: _*)
  def context(activated: Int, ext: Map[Int, Json]): Json = Json.obj(
    "self_index" -> Json.fromInt(0), "inputs" -> Json.arr(Json.fromString(SelfHex)), "data_inputs" -> Json.arr(),
    "outputs" -> Json.arr(Json.fromString(OutputHex)), "headers" -> Json.arr(Json.fromString(L.HeaderHex(activated))),
    "pre_header_hex" -> Json.fromString(L.preHeaderHex(activated)), "height" -> Json.fromInt(L.Height),
    "extension" -> extJson(ext), "input_extensions" -> Json.arr(extJson(ext)))

  // ── the rows ────────────────────────────────────────────────────────────────────────────────────
  case class Row(part: String, name: String, treeVersion: Int, activated: Int, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String, cls: String) {
    private val f = name.split('|').toVector
    def node: String = f(0)
    def carrier: String = f(1)
    def inContext: Boolean = part == "eval, the request's context"
    def readsVariable: Boolean = inContext && carrier == "var"
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def op: String = s"$node.item_by_item" + (if (inContext) "_context" else "") + (if (activated == 3) "_v6" else "")
    def slug: String = name.replace('|', '-').replace("Coll[", "Coll_").replaceAll("[^A-Za-z0-9.+_=-]", "_")
      .replaceAll("_+", "_")
  }
  def allRows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).filter(_(0).startsWith("eval")).flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Row(c(0), c(1), c(2).toInt, a, c(4), c(5), c(6), c(7), c(9)))
    }
  }
  /** 5fd218a's entries, by (tree, activated version): the row-activations that family already holds. */
  lazy val firstFamily: Map[(String, Int), Json] =
    AuthoredEvalAndOrItemByItem.extract().values.toSeq.flatMap(_.hcursor.downField("entries").as[List[Json]].toOption.get)
      .map(e => (e.hcursor.get[String]("tree_bytes_hex").toOption.get,
        e.hcursor.downField("version").get[Int]("activated").toOption.get) -> e).toMap
  def inFirstFamily(r: Row): Boolean = !r.inContext && firstFamily.contains((r.treeHex, r.activated))
  def rows(): Vector[Row] = allRows().filterNot(inFirstFamily)

  // ── the oracle ──────────────────────────────────────────────────────────────────────────────────
  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

  def parse(treeHex: String, activated: Int, treeVersion: Int): Either[Throwable, Either[Throwable, Unit]] =
    Try(VersionContext.withVersions(activated.toByte, treeVersion.toByte)(sigma.santa.LenientErgoTree.deserialize(b(treeHex)))) match {
      case Failure(t) => Left(t)
      case Success(t) => Right(t.root match { case Left(UnparsedErgoTree(_, err)) => Left(err); case Right(_) => Right(()) })
    }
  def outcomeWith(r: Row, ext: Map[Int, Json]): Outcome = parse(r.treeHex, r.activated, r.treeVersion) match {
    case Left(t)        => RefusedAtParse(t)
    case Right(Left(t)) => RefusedAtParse(t)
    case Right(Right(())) =>
      val res =
        if (r.inContext) EvalCore.evalFullContextOutcome(r.treeHex, 0, Seq(SelfHex), Nil, Seq(OutputHex),
          Seq(L.HeaderHex(r.activated)), L.preHeaderHex(r.activated), Seq(ext), None, r.activated.toByte)._2
        else EvalCore.evalAppliedOutcome(r.treeHex, IntInput, r.activated.toByte)._2
      res match { case Right((v, c)) => Answer(v, c); case Left(t) => ThrowsAtEval(t) }
  }
  def outcome(r: Row): Outcome = outcomeWith(r, extOf(r))
  def text(j: Json): String = AuthoredEvalPreActivation.text(j)
  def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq

  // ── the scripts ─────────────────────────────────────────────────────────────────────────────────
  val LambdaClass = "\\$\\$Lambda\\$[0-9]+(/0x[0-9a-f]+)?"
  /** The root as the parsed tree holds it (sigma-state's own text of the node). */
  def root(r: Row): String = {
    val t = Try(VersionContext.withVersions(3, 3)(sigma.santa.LenientErgoTree.deserialize(b(r.treeHex)).root.toOption.get.toString))
      .toOption.getOrElse("<refused at parse>").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll(LambdaClass, "\\$\\$Lambda")
      .replaceAll("\\s+", " ")
    if (t.length <= 220) t else t.take(220) + "…"
  }
  private def why(t: Throwable): String = {
    val c = chain(t).last
    val m = c match {
      case _: IllegalArgumentException => ""
      case _: ClassCastException => Option(c.getMessage).getOrElse("").replaceAll(" \\(.*$", "").replaceAll(LambdaClass, "\\$\\$Lambda")
      case _ => Option(c.getMessage).getOrElse("").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll(LambdaClass, "\\$\\$Lambda")
    }
    val clean = m.replaceAll("\\s+", " ").trim
    s"${c.getClass.getSimpleName}${if (clean.isEmpty) "" else ": " + (if (clean.length > 140) clean.take(140) + "…" else clean)}"
  }
  def said(o: Outcome): String = o match {
    case Answer(v, cost)   => s"${text(v)} at cost $cost"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }
  /** sigma-rust's class of the row, as the script states it. */
  def label(r: Row): String = r.cls match {
    case "COST" => "COST: the node reads its items up to the deciding one and charges for those it read; a port that " +
      "charges for the whole collection is above this cost."
    case "NEVER READ" => "NEVER READ: the item of another kind is never read (it comes after the deciding item, or " +
      "nothing walks it), so the call answers; a port that casts or checks every item first refuses here."
    case "REFUSED" => "REFUSED: an item of another kind is read, the input is no collection, an item throws, or the " +
      "tree is refused at parse."
    case "VALUE" => "VALUE: a control, or a neighbour node's answer at the jars' cost."
  }
  def script(r: Row, o: Outcome): String =
    s"[${r.name}] `${root(r)}` — ${said(o)}. ${label(r)}  // tree v${r.treeVersion}, activated ${r.activated}"

  // ── the vectors ─────────────────────────────────────────────────────────────────────────────────
  /** Every file: 5fd218a's entries first (AND / OR), then this round's. */
  def extract(): Map[String, Json] = {
    val first = AuthoredEvalAndOrItemByItem.extract()
    val round = rows().groupBy(_.op)
    (first.keySet ++ round.keySet).map { op =>
      val prior = first.get(op).map(_.hcursor.downField("entries").as[List[Json]].toOption.get).getOrElse(Nil)
      val rs = round.getOrElse(op, Vector.empty)
      val entries = prior ++ rs.zipWithIndex.map { case (r, i) =>
        val o = outcome(r)
        val name = s"${r.slug}-a${r.activated}#${prior.size + i}"
        if (r.inContext) {
          val expected = o match {
            case Answer(v, cost) => Json.obj("value" -> v, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
            case _ => Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
          }
          Json.obj("name" -> Json.fromString(name), "script" -> Json.fromString(script(r, o)),
            "tree_bytes_hex" -> Json.fromString(r.treeHex),
            "version" -> Json.obj("activated" -> Json.fromInt(r.activated), "ergoTree" -> Json.fromInt(r.treeVersion)),
            "context" -> context(r.activated, extOf(r)), "expected" -> expected)
        } else o match {
          case Answer(_, _) =>
            SpecExtract.authoredEntryV(op, script(r, o), r.treeHex, name, IntInput, r.activated.toByte, ergoTree = r.treeVersion)
          case _ =>
            SpecExtract.authoredRejectEntryV(op, script(r, o), r.treeHex, name, IntInput, r.activated.toByte, ergoTree = r.treeVersion)
        }
      }
      op -> (
        if (op.contains("_context")) Json.obj("schema" -> Json.fromString("santa-eval/v6-fullctx"), "op" -> Json.fromString(op),
          "blessed_by" -> Json.fromString(Stamp), "source" -> Json.fromString(Source), "entries" -> Json.arr(entries: _*))
        else first.getOrElse(op, SpecExtract.authoredEnvelope(op, Nil, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp))))
          .mapObject(_.add("entries", Json.arr(entries: _*))))
    }.toMap
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalAndOrItemByItemRound", extract(), outDir)
}
