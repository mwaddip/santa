package santa

// Authored eval vectors for sigma-rust's santa-probe-avltree-item-bind-2 (2026-11-17, the addendum to its queue item 32,
// from its whole-branch review): what the first note listed as not measured. Measured first in
// santa.spike.AvlTreeItemBindReviewProbe (local): every row-activation as the jars on outcome, class, value and cost.
// THE RULE is AuthoredEvalAvlTreeItemBind's; the reply is prompts/drafts/sigma-rust-avltree-item-bind-2-reply.md.
// Transaction companion: AuthoredTxAvlTreeItemBindReview.
//
// THE ROWS (src/test/resources/sigma-rust-avltree-item-bind/review/, with the jars' answers `jvm-<set>-act{2,3}.txt`;
// sigma-rust's generator is genrv.py), each the bare root `<tree>.<method>(<argument>, <proof>)`. The jars answer tree
// versions 1 and 2 as 0 at each activated version, so tree v0 is authored at activated 2 and 3 and tree v3 at 3:
//  * `AvlTree.item_bind_context{,_v6}` (santa-eval/v6-fullctx), rows/rv-ctx.txt: a box (SELF, a Box constant,
//    INPUTS, OUTPUTS), a header, the pre-header, the context, Global or a function as the item; a key or an entry
//    read from a register, a context variable, built by `map`, `zip` or behind an `If`. THE CONTEXT: the header and
//    pre-header of the substConstants-lift families, sigma-rust's SELF (`rv-self.hex`: R4 = `(Coll[Byte](1), 5, 0)`,
//    R5 = `Coll((Coll[Byte](3), 5))`, R6 = `Coll[Int](10)`) and one output (its transaction has one: 1,000,000
//    nanoErg to the generator's P2PK, created at 100); its extension (`rv-ext.hex`, variables 1 to 7) as RAW `Value`
//    bindings on the rows that read a variable (`src|var-…`) only. The bind does not read a value's content, so the
//    answers do not depend on the header (pinned in the test); a value holding the pre-header's parent id is ours.
//  * `AvlTree.item_bind_edges{,_v6}` (santa-eval/v2), rows/rv-free.txt: more than two items, an empty key, a key of
//    another length, a tree with 8-byte values, the empty tree, a proof that fails at the second operation, an
//    `insertOrUpdate` that updates, keys and entries from other producers; the ORDER of key and value (an entry
//    whose two sides are different kinds that are no collection: the jars' refusal names the KEY's class, which the
//    eval tier does not grade, only the refusal); and the census rows, `updateMany`'s indexes and `substConstants`'
//    positions as a tuple with an item of another kind.
//
// LEFT OUT: the rows refused at parse, which are not about the bind (`insertOrUpdate` at tree v0: method 16 is
// tree-v3; a `Header` constant at tree v0); the whole trees (the transaction tier carries them); the cost-limit rows
// (both sides refuse at every limit; only the refusal's kind moves, which no tier grades).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.serialization.SigmaSerializer

object AuthoredEvalAvlTreeItemBindReview {
  val Source = "santa:authored-avltree-item-bind"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val Dir: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-avltree-item-bind", "review")
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  def lines(f: String): Vector[String] =
    new String(java.nio.file.Files.readAllBytes(Dir.resolve(f)), "UTF-8").split("\n").toVector.filter(_.trim.nonEmpty)

  // ── the context ─────────────────────────────────────────────────────────────────────────────────
  private val L = AuthoredEvalSubstConstantsLift
  lazy val SelfHex: String = lines("rv-self.hex").head.trim
  lazy val ExtHex: String = lines("rv-ext.hex").head.trim
  /** The extension split into its values' RAW bytes, as the reader takes them. */
  lazy val Ext: Map[Int, String] = VersionContext.withVersions(3, 3) {
    val bytes = b(ExtHex)
    val r = SigmaSerializer.startReader(bytes)
    val n = r.getUByte()
    (0 until n).map { _ =>
      val k = r.getByte().toInt
      val start = r.position
      sigma.serialization.ValueSerializer.deserialize(r)
      k -> hx(bytes.slice(start, r.position))
    }.toMap.ensuring(_ => r.remaining == 0, "bytes left after the extension")
  }
  /** sigma-rust's transaction's one output, under a transaction id of 0x33 x 32, index 0. */
  val OutputHex: String = "c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798640000" +
    "33" * 32 + "00"
  def valueBinding(h: String): Json =
    Json.obj("kind" -> Json.fromString("Value"), "bytes_hex" -> Json.fromString(h))
  def extOf(r: Row): Map[Int, Json] =
    if (r.readsVariable) Ext.map { case (k, h) => k -> valueBinding(h) } else Map.empty
  private def extJson(e: Map[Int, Json]): Json = Json.obj(e.toSeq.sortBy(_._1).map { case (k, v) => k.toString -> v }: _*)
  def context(activated: Int, ext: Map[Int, Json]): Json = Json.obj(
    "self_index"       -> Json.fromInt(0),
    "inputs"           -> Json.arr(Json.fromString(SelfHex)),
    "data_inputs"      -> Json.arr(),
    "outputs"          -> Json.arr(Json.fromString(OutputHex)),
    "headers"          -> Json.arr(Json.fromString(L.HeaderHex(activated))),
    "pre_header_hex"   -> Json.fromString(L.preHeaderHex(activated)),
    "height"           -> Json.fromInt(L.Height),
    "extension"        -> extJson(ext),
    "input_extensions" -> Json.arr(extJson(ext)))

  // ── the rows ────────────────────────────────────────────────────────────────────────────────────
  /** One vendored row at one activated version; `jars` the jars' answer (the answer, then ` @` and the cost counter). */
  case class Row(set: String, name: String, treeVersion: Int, activated: Int, body: String, jars: String) {
    private val f = name.split('|').toVector
    def inContext: Boolean = set == "rv-ctx"
    def method: String = f(0)
    def tree: String = f(1)
    def census: Boolean = method == "census"
    def readsVariable: Boolean = inContext && name.contains("|src|var-")
    def order: Boolean = name.contains("|order|")
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def op: String = "AvlTree.item_bind_" + (if (inContext) "context" else "edges") + (if (activated == 3) "_v6" else "")
    def slug: String = name.replace('|', '-').replace("Coll[", "Coll_").replaceAll("[^A-Za-z0-9.+_=-]", "_")
      .replaceAll("_+", "_")
    def jarsParse: Boolean = jars.startsWith("PARSE-THROWS")
  }

  private def jarsOf(set: String, act: Int): Map[String, String] =
    lines(s"jvm-$set-act$act.txt").map { l => val i = l.indexOf('\t'); l.take(i) -> l.drop(i + 1) }.toMap

  /** Every row at tree v0 (activated 2 and 3) and v3 (activated 3), parse refusals included. */
  def allRows(): Vector[Row] = Seq("rv-ctx", "rv-free").toVector.flatMap { set =>
    val j = Map(2 -> jarsOf(set, 2), 3 -> jarsOf(set, 3))
    lines(s"$set.txt").map(_.split(" ")).filter(p => p(2) == "0" || p(2) == "3").flatMap { p =>
      val tv = p(2).toInt
      (if (tv == 0) Seq(2, 3) else Seq(3)).map(a => Row(set, p(0), tv, a, p(1), j(a)(p(0))))
    }
  }
  /** The rows authored: every one but those refused at parse (not about the bind). */
  def rows(): Vector[Row] = allRows().filterNot(_.jarsParse)

  // ── the oracle ──────────────────────────────────────────────────────────────────────────────────
  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

  def parse(treeHex: String, activated: Int, treeVersion: Int): Either[Throwable, Either[Throwable, Unit]] =
    Try(VersionContext.withVersions(activated.toByte, treeVersion.toByte) {
      sigma.santa.LenientErgoTree.deserialize(b(treeHex))
    }) match {
      case Failure(t) => Left(t)
      case Success(t) => Right(t.root match {
        case Left(UnparsedErgoTree(_, err)) => Left(err)
        case Right(_)                       => Right(())
      })
    }

  def outcomeWith(r: Row, ext: Map[Int, Json]): Outcome = parse(r.treeHex, r.activated, r.treeVersion) match {
    case Left(t)        => RefusedAtParse(t)
    case Right(Left(t)) => RefusedAtParse(t)
    case Right(Right(())) =>
      val res =
        if (r.inContext) EvalCore.evalFullContextOutcome(r.treeHex, 0, Seq(SelfHex), Nil, Seq(OutputHex),
          Seq(L.HeaderHex(r.activated)), L.preHeaderHex(r.activated), Seq(ext), None, r.activated.toByte)._2
        else EvalCore.evalAppliedOutcome(r.treeHex, IntInput, r.activated.toByte)._2
      res match {
        case Right((v, cost)) => Answer(v, cost)
        case Left(t)          => ThrowsAtEval(t)
      }
  }
  def outcome(r: Row): Outcome = outcomeWith(r, extOf(r))

  def text(j: Json): String = AuthoredEvalPreActivation.text(j)
  def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq

  // ── the scripts ─────────────────────────────────────────────────────────────────────────────────
  /** The call's argument as the parsed tree holds it (sigma-state's own text of the node), identity hashes and
    * generated lambda class names masked. */
  def argument(r: Row): String = {
    val t = Try(VersionContext.withVersions(3, 3) {
      sigma.santa.LenientErgoTree.deserialize(b(r.treeHex)).root.toOption.get match {
        case mc: sigma.ast.MethodCall if !r.census => mc.args.head.toString
        case other                                 => other.toString
      }
    }).toOption.getOrElse("<unprintable>").replaceAll("@[0-9a-f]{4,}", "@<hash>")
      .replaceAll(LambdaClass, "\\$\\$Lambda").replaceAll("\\s+", " ")
    if (t.length <= 220) t else t.take(220) + "…"
  }

  def treeText(r: Row): String = r.tree match {
    case "tree"     => "the fixture tree {1: 10, 2: 20} (1-byte keys, every operation allowed)"
    case "other"    => "a tree the proof does not belong to"
    case "flag-off" => "the fixture tree with every operation flag OFF"
    case "vl8"      => "the tree {1: 10, 2: 20} with values of exactly 8 bytes (valueLengthOpt = 8)"
    case "empty"    => "the empty tree (1-byte keys)"
    case t          => t
  }

  /** A generated lambda class's name (`FuncValue$$Lambda$635/0x00007d4c203cc9d0`) changes from run to run. */
  val LambdaClass = "\\$\\$Lambda\\$[0-9]+(/0x[0-9a-f]+)?"
  private def why(t: Throwable): String = {
    val c = chain(t).last
    val m = c match {
      case _: IllegalArgumentException => ""
      case _: ClassCastException       =>
        Option(c.getMessage).getOrElse("").replaceAll(" \\(.*$", "").replaceAll(LambdaClass, "\\$\\$Lambda")
      case _ => Option(c.getMessage).getOrElse("").replaceAll("@[0-9a-f]{4,}", "@<hash>")
        .replaceAll("CAvlTree\\(AvlTreeData\\(.*", "<the tree> …")
    }
    val clean = m.replaceAll("\\s+", " ").trim
    s"${c.getClass.getSimpleName}${if (clean.isEmpty) "" else ": " + (if (clean.length > 140) clean.take(140) + "…" else clean)}"
  }
  def said(o: Outcome): String = o match {
    case Answer(v, cost)   => s"${text(v)} at cost $cost"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }

  /** Where the JVM refuses (AuthoredEvalAvlTreeItemBind's classes), or None for another failure. */
  def refusal(t: Throwable): Option[AuthoredEvalAvlTreeItemBind.Refusal] = Try(AuthoredEvalAvlTreeItemBind.refusal(t)).toOption

  def label(r: Row, o: Outcome): String = {
    val B = AuthoredEvalAvlTreeItemBind
    val orderNote = if (r.order)
      " Both sides are no collection and of different kinds: the KEY is bound first, so the refusal names the key's " +
        "class (the eval tier grades only the refusal)." else ""
    o match {
      case Answer(_, _) if r.name.contains("emptykey") =>
        "ANSWERS: an EMPTY key is a collection, so it passes the bind and the operation answers. A port that refuses " +
          "an empty collection at the bind refuses here."
      case Answer(_, _) =>
        "ANSWERS: every item the call reads is a collection of the declared kind (an item of another kind, if any, is " +
          "never read)."
      case ThrowsAtEval(t) => refusal(t) match {
        case Some(B.NotAColl) => "REFUSED where the item is bound: it is no collection (cast to `Coll`)." + orderNote
        case Some(B.NotAPair) => "REFUSED where the entry is bound: it is no pair (cast to a pair)."
        case Some(B.NotBytes) =>
          "REFUSED at `.toArray`: a key or value is a collection of another item type (cast to `byte[]`)."
        case Some(B.ReflectiveCall) => "REFUSED by the reflective call: an argument of another class."
        case Some(B.ProofFails) => "REFUSED: the proof fails at an operation before the item of another kind is read."
        case None => "REFUSED: the call reads an item of another kind."
      }
      case RefusedAtParse(_) => "PARSE: the tree names a type or method its version does not have."
    }
  }

  def script(r: Row, o: Outcome): String = {
    val call = if (r.census) s"the census row `${r.name}`, its call `${argument(r)}`"
      else s"`tree.${r.method}(<argument>, proof)` on ${treeText(r)}, the argument `${argument(r)}`"
    s"[${r.name}] $call — ${said(o)}. ${label(r, o)}  // tree v${r.treeVersion}, activated ${r.activated}"
  }

  // ── the vectors ─────────────────────────────────────────────────────────────────────────────────
  def extract(): Map[String, Json] =
    rows().groupBy(_.op).map { case (op, rs) =>
      val entries = rs.zipWithIndex.map { case (r, i) =>
        val o = outcome(r)
        val name = s"${r.slug}-a${r.activated}#$i"
        if (r.inContext) {
          val expected = o match {
            case Answer(v, cost) => Json.obj("value" -> v, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
            case _               => Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
          }
          Json.obj(
            "name"           -> Json.fromString(name),
            "script"         -> Json.fromString(script(r, o)),
            "tree_bytes_hex" -> Json.fromString(r.treeHex),
            "version"        -> Json.obj("activated" -> Json.fromInt(r.activated), "ergoTree" -> Json.fromInt(r.treeVersion)),
            "context"        -> context(r.activated, extOf(r)),
            "expected"       -> expected)
        } else o match {
          case Answer(_, _) =>
            SpecExtract.authoredEntryV(op, script(r, o), r.treeHex, name, IntInput, r.activated.toByte, ergoTree = r.treeVersion)
          case _ =>
            SpecExtract.authoredRejectEntryV(op, script(r, o), r.treeHex, name, IntInput, r.activated.toByte,
              ergoTree = r.treeVersion)
        }
      }
      op -> (
        if (rs.head.inContext) Json.obj(
          "schema"     -> Json.fromString("santa-eval/v6-fullctx"),
          "op"         -> Json.fromString(op),
          "blessed_by" -> Json.fromString(Stamp),
          "source"     -> Json.fromString(Source),
          "entries"    -> Json.arr(entries: _*))
        else SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp))))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalAvlTreeItemBindReview", extract(), outDir)
}
