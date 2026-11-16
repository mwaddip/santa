package santa

// Authored eval vectors for sigma-rust's santa-probe-node-static-type (2026-11-16): `Exponentiate` (9f) and
// `MultiplyGroup` (a0) are typed GroupElement whatever their operands. Measured first in
// santa.spike.NodeStaticTypeProbe (local); the reply is prompts/drafts/sigma-rust-node-static-type-reply.md. Wire and
// transaction companions: AuthoredWireNodeStaticType, AuthoredTxNodeStaticType.
//
// THE RULE, read in 6.0.7 and measured: both node classes mix in NotReadyValueGroupElement (trees.scala, values.scala),
// so their `tpe` is the constant SGroupElement. The deserializer builds them over operands of any type and nothing
// checks the operand until evaluation casts its VALUE to a group element. Every reader of the node's type sees
// GroupElement: the builder's checks of an enclosing node (equality and comparison operands, arithmetic, BitOp, a
// ConcreteCollection item, an Upcast/Downcast input), a ValDef's type, an If's branch, a tuple item, Fold's zero, the
// default of getOrElse/ByIndex, a lambda's result, Global.serialize's argument. A port that derives the node's type
// from its left operand agrees with sigmastate only while that operand is statically a group element.
//
// THE ROWS: sigma-rust's 1,644 (src/test/resources/sigma-rust-node-static-type/rows.tsv, with the jars' outcome, value
// and cost as an independent cross-check), a bare root expression each, at tree version 0 (unsized, `00` + body; the
// jars give the same answer at activated 2 and 3, and so does our oracle: authored at both) and tree version 3 (`0b` +
// size + body, activated 3). A row is `<slot|hand>|<consumer>|<X>|[twin|]v<tree version>`:
//  - X is the node over its left operand x: `exp.*` is `Exponentiate(x, 2)` (BigInt 2), `mul.*` is `MultiplyGroup(x,
//    g)`, g the generator; x is `(g, true, 1L)(0)` (anyge: an item of a tuple constant, typed Any, a group element at
//    evaluation), the Int constant 1 (int), `(1, true, 1L)(0)` (anyint: typed Any, an Int), or g itself (ctl: the
//    control, where every reading of the type agrees);
//  - `slot|<form>.op<i>`: X as operand i of another node, its other operands valid for it; `hand|<consumer>`: X in a
//    consumer written so that a group element fits;
//  - `twin`: the row as `if (true) true else { val v = <row>; false }`, true exactly where the row parses.
// The bytes are the rows' as sent. The JVM writes 24 of the parsed rows back differently (a FunDef with no type
// arguments as a ValDef, Fold's lambda argument type `3c 07 04` as `43 04`), neither the point of the row: the vectors
// keep the bytes every X of one consumer shares. Every expectation is our oracle's; `refused at parse` and `throws at
// evaluation` are one reject on this tier (the twins carry the parse outcome). Tree-v0 rows go to the v5 files at
// activated 2 and to the `_v6` files at activated 3, tree-v3 rows to the `_v6` files.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.serialization.SigmaSerializer

object AuthoredEvalNodeStaticType {
  val Source = "santa:authored-node-static-type"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-node-static-type", "rows.tsv")

  /** The generator's encoding, and the four left operands' bytes (x). */
  val G = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  val X: Map[String, String] = Map(
    "anyge"  -> ("b248070105" + G + "0102" + "0400" + "00"),
    "anyint" -> ("b248040105" + "02" + "0102" + "0400" + "00"),
    "int"    -> "0402",
    "ctl"    -> ("07" + G))
  /** The node over x, as the rows write it: `Exponentiate(x, 2)` or `MultiplyGroup(x, g)`. */
  def nodeBytes(node: String, x: String): String = node match {
    case "exp" => "9f" + X(x) + "060102"
    case "mul" => "a0" + X(x) + "07" + G
  }

  /** One vendored row at one activated version. `jars*` is sigma-rust's measurement on the 6.0.7 jars. */
  case class Row(part: String, name: String, treeVersion: Int, activated: Int, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String) {
    private val f = name.split('|')
    def kind: String = f(0)
    def consumer: String = f(1)
    def x: String = f(2)
    def node: String = x.takeWhile(_ != '.')
    def operand: String = x.dropWhile(_ != '.').drop(1)
    def twin: Boolean = f(3) == "twin"
    /** The row this twin wraps: the same name without `twin|`. */
    def base: String = name.replace("|twin|", "|")
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def op: String = (if (node == "exp") "Exponentiate" else "MultiplyGroup") + ".static_type" +
      (if (activated == 3) "_v6" else "")
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
  }

  def rows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).filter(_(0).startsWith("eval")).flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Row(c(0), c(1), c(2).toInt, a, c(4), c(5), c(6), c(7)))
    }
  }

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** The oracle's outcome: a value and its cost, a refusal while the tree is read, or a throw while it is evaluated. */
  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

  /** The tree read under the row's own (activated, tree version), as a node reads a script: Left when it throws,
    * Right(Left) when a sized tree degrades, Right(Right(())) when it parses. */
  def parse(treeHex: String, activated: Int, treeVersion: Int): Either[Throwable, Either[Throwable, Unit]] =
    Try(VersionContext.withVersions(activated.toByte, treeVersion.toByte) {
      sigma.santa.LenientErgoTree.deserialize(Base16.decode(treeHex).get)
    }) match {
      case Failure(t) => Left(t)
      case Success(t) => Right(t.root match {
        case Left(UnparsedErgoTree(_, err)) => Left(err)
        case Right(_)                       => Right(())
      })
    }

  def outcome(r: Row): Outcome = parse(r.treeHex, r.activated, r.treeVersion) match {
    case Left(t)        => RefusedAtParse(t)
    case Right(Left(t)) => RefusedAtParse(t)
    case Right(Right(())) =>
      EvalCore.evalAppliedOutcome(r.treeHex, IntInput, r.activated.toByte)._2 match {
        case Right((v, cost)) => Answer(v, cost)
        case Left(t)          => ThrowsAtEval(t)
      }
  }

  /** sigma-rust's text form of a value. */
  def text(j: Json): String = AuthoredEvalPreActivation.text(j)

  private def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq
  /** A failure as the script states it: the innermost cause's class and message, identity hashes masked (a
    * `Constraints$SameType2$@3d96f1b8` would change with every JVM). */
  private def why(t: Throwable): String = {
    val c = chain(t).last
    val m = Option(c.getMessage).getOrElse("").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll("\\s+", " ").trim
    s"${c.getClass.getSimpleName}${if (m.isEmpty) "" else ": " + clip(m, 140)}"
  }
  private def clip(m: String, n: Int): String =
    if (m.length <= n) m else m.take(n).reverse.dropWhile(_ != ' ').reverse.trim + "…"
  def said(o: Outcome): String = o match {
    case Answer(v, cost)   => s"${text(v)} at cost $cost"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }

  private val Operand: Map[String, String] = Map(
    "anyge"  -> "x = `(g, true, 1L)(0)`, an item of a tuple constant: typed Any, the generator at evaluation",
    "anyint" -> "x = `(1, true, 1L)(0)`: typed Any, an Int at evaluation",
    "int"    -> "x = the Int constant 1",
    "ctl"    -> "x = the generator g, a GroupElement constant")
  private def nodeText(r: Row): String =
    if (r.node == "exp") "`Exponentiate(x, 2)`" else "`MultiplyGroup(x, g)`"
  private def where(r: Row): String = r.kind match {
    case "slot" =>
      val i = r.consumer.lastIndexOf(".op")
      s"as operand ${r.consumer.drop(i + 3)} of the `${r.consumer.take(i)}` node (its other operands valid for it)"
    case _ => s"in `${r.consumer}` (written so that a group element fits)"
  }

  def label(r: Row): String =
    if (r.operand == "ctl") "CONTROL: the left operand is a group element, so every reading of the node's type agrees."
    else s"STATIC TYPE: the node is GroupElement whatever x is; a port that types it by its left operand reads " +
      s"${if (r.operand == "int") "Int" else "Any"} here."

  def script(r: Row): String = {
    val what = s"${nodeText(r)}, ${Operand(r.operand)}, ${where(r)}"
    val shown = if (r.twin) s"the parse twin `if (true) true else { val v = <row>; false }` of the row: $what" else what
    s"[${r.name}] $shown — ${said(outcome(r))}. ${label(r)}  // tree v${r.treeVersion}, activated ${r.activated}"
  }

  def extract(): Map[String, Json] =
    rows().groupBy(_.op).map { case (op, rs) =>
      val entries = rs.zipWithIndex.map { case (r, i) =>
        val name = s"${r.slug}-a${r.activated}#$i"
        val a = r.activated.toByte
        outcome(r) match {
          case Answer(_, _) =>
            SpecExtract.authoredEntryV(op, script(r), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
          case _ =>
            SpecExtract.authoredRejectEntryV(op, script(r), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
        }
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalNodeStaticType", extract(), outDir)
}
