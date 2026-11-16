package santa

// Authored eval vectors: ByIndex over a tuple. Found by the node-static-type round (2026-11-16): arkadianet (vixen) and
// ergots (dasher) refuse `(g, true, 1L)(0)` itself, which the JVM answers, and no other vector held such a read.
// Measured first in santa.spike.TupleByIndexProbe (local).
//
// THE RULE, measured on 6.0.7: STuple is an SCollection[SAny], so `t(i)` over a tuple parses and is typed Any. What it
// does at evaluation depends on the tuple's RUNTIME form:
//  - a tuple of three or more items is a Coll[Any] (CoreDataSerializer reads it so): ByIndex reads item i (cost 40 at
//    a constant index), throws ArrayIndexOutOfBoundsException past the last item or below 0, answers the default past
//    the end (`getOrElse`), and SizeOf answers the item count;
//  - a pair is a scala.Tuple2, no Coll: ByIndex and SizeOf throw ClassCastException before the index is read, whatever
//    it is, and so does a pair built by a Tuple node (the JIT builds pairs only: a Tuple node of three items does not
//    evaluate at all, `Tuple.non_pair_arity3`);
//  - the item read is an Any: `==` over two of them parses and throws at evaluation (`Unknown type SAny`, no comparer).
// One version difference: `getOrElse` in range costs 45 at tree version 0 and 40 at tree version 3.
// Each row is authored at tree v0 / activated 2 (`v5`), tree v0 / activated 3 and tree v3 / activated 3 (`_v6`).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalTupleByIndex {
  val Source = "santa:authored-tuple-by-index"
  val Stamp = "jvm:sigma-state-6.0.7"
  val Op = "ByIndex.tuple_item"

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  def intInput(v: Int): Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(v))

  private val G = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  /** Tuple constants (type bytes, then data), and a pair built by a Tuple node. */
  val Tuples: Map[String, String] = Map(
    "(1, true)"            -> ("4001" + "02" + "01"),                  // Pair1(Int), Boolean
    "(1, 2)"               -> ("58" + "02" + "04"),                    // PairSymmetric(Int)
    "((1, 2), true)"       -> ("4958" + "02" + "04" + "01"),           // Pair2(Boolean): ((Int, Int), Boolean)
    "Tuple(1, true)"       -> ("8602" + "0402" + "0101"),              // a Tuple node of two items
    "(g, true, 1L)"        -> ("48070105" + G + "01" + "02"),          // Triple
    "(1, true, 1L)"        -> ("48040105" + "02" + "01" + "02"),
    "((1, 2), true, 1L)"   -> ("48580105" + "02" + "04" + "01" + "02"),
    "(1, true, 1L, 2)"     -> ("5404010504" + "02" + "01" + "02" + "04"),  // Quadruple
    "(1, true, 1L, 2, 3)"  -> ("6005" + "0401050404" + "02" + "01" + "02" + "04" + "06"))  // Tuple, 5 types
  private def idx(i: Int): String = "04" + vlq(if (i >= 0) 2 * i else -2 * i - 1)  // an Int constant (zigzag)
  def byIndex(t: String, i: String, default: Option[String] = None): String =
    "b2" + Tuples(t) + i + default.map("01" + _).getOrElse("00")
  private def body(v: sigma.ast.syntax.SValue): String =
    VersionContext.withVersions(3, 3) { val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(v, w); hx(w.toBytes) }
  private def read(h: String): sigma.ast.syntax.SValue =
    VersionContext.withVersions(3, 3)(ValueSerializer.deserialize(SigmaSerializer.startReader(b(h))))
  /** `t(getVar[Int](1).get)`. */
  def dynamic(t: String): String =
    body(ByIndex(read(Tuples(t)).asInstanceOf[Value[SCollection[SAny.type]]], OptionGet(GetVar(1.toByte, SInt))))

  /** One row: an id, the expression as ErgoScript, its body, var 1's value, and what the row rests on. */
  case class Row(id: String, expr: String, body: String, var1: Int, label: String)

  private val Pair = "PAIR: a pair is a scala.Tuple2 at runtime, no Coll, so the read throws ClassCastException " +
    "before it looks at the index."
  private def item(n: Int) = s"ITEM: a tuple of $n items is a Coll[Any] at runtime; the read answers the item, typed Any."
  private def range(n: Int, i: Int) = s"OUT OF RANGE: index $i of $n items: ArrayIndexOutOfBoundsException."

  val Rows: Seq[Row] = {
    val pairs = Seq(
      Row("P1", "(1, true)(0)", byIndex("(1, true)", idx(0)), 0, Pair),
      Row("P2", "(1, true)(1)", byIndex("(1, true)", idx(1)), 0, Pair),
      Row("P3", "(1, true)(2)", byIndex("(1, true)", idx(2)), 0, Pair + " An index past a pair's end throws the same."),
      Row("P4", "(1, 2)(0)", byIndex("(1, 2)", idx(0)), 0, Pair),
      Row("P5", "(1, 2)(1)", byIndex("(1, 2)", idx(1)), 0, Pair),
      Row("P6", "((1, 2), true)(0)", byIndex("((1, 2), true)", idx(0)), 0, Pair),
      Row("P7", "Tuple(1, true)(0), the pair built by a Tuple node", "b2" + Tuples("Tuple(1, true)") + idx(0) + "00", 0,
        Pair + " A Tuple node builds the same pair."))
    def reads(t: String, n: Int, ids: String) = (-1 to n).map(i =>
      Row(s"$ids$i".replace("-", "m"), s"$t($i)", byIndex(t, idx(i)), 0, if (i < 0 || i >= n) range(n, i) else item(n)))
    val triples = Seq(0, 1, 2).map(i => Row(s"G$i", s"(g, true, 1L)($i)", byIndex("(g, true, 1L)", idx(i)), 0, item(3))) ++
      reads("(1, true, 1L)", 3, "T") ++
      Seq(Row("N0", "((1, 2), true, 1L)(0)", byIndex("((1, 2), true, 1L)", idx(0)), 0,
        "ITEM: the item read is itself a pair (a Tuple2), typed Any."))
    val wider = reads("(1, true, 1L, 2)", 4, "Q") ++ reads("(1, true, 1L, 2, 3)", 5, "F")
    val defaults = Seq(
      Row("D1", "(1, true, 1L).getOrElse(5, 7)", byIndex("(1, true, 1L)", idx(5), Some("040e")), 0,
        "DEFAULT: past the end the read answers the default."),
      Row("D2", "(1, true, 1L).getOrElse(0, 7)", byIndex("(1, true, 1L)", idx(0), Some("040e")), 0,
        "DEFAULT: in range the read answers the item; it costs 45 at tree version 0 and 40 at tree version 3."),
      Row("D3", "(1, true, 1L).getOrElse(-1, 7)", byIndex("(1, true, 1L)", idx(-1), Some("040e")), 0,
        "DEFAULT: below 0 the read answers the default."),
      Row("D4", "(1, true).getOrElse(5, 7)", byIndex("(1, true)", idx(5), Some("040e")), 0,
        Pair + " The default does not help."))
    val dyn = (0 to 3).map(i => Row(s"V$i", s"(1, true, 1L)(getVar[Int](1).get), var 1 = $i", dynamic("(1, true, 1L)"), i,
      if (i < 3) "ITEM: the index from a context variable; the same reads as a constant index." else range(3, i))) :+
      Row("V9", "(1, true)(getVar[Int](1).get), var 1 = 0", dynamic("(1, true)"), 0, Pair)
    val a0 = byIndex("(1, true, 1L)", idx(0))
    val any = "ANY: `==` over two Any values parses and throws at evaluation (`Unknown type SAny`: no comparer for Any)."
    val uses = Seq(
      Row("E1", "(1, true, 1L)(0) == (1, true, 1L)(0)", "93" + a0 + a0, 0, any + " A port that compares the values " +
        "answers true."),
      Row("E2", "(1, true, 1L)(0) == (1, true, 1L)(2)", "93" + a0 + byIndex("(1, true, 1L)", idx(2)), 0, any),
      Row("S3", "(1, true, 1L).size", "b1" + Tuples("(1, true, 1L)"), 0, "SIZE: a tuple of 3 items is a Coll of 3."),
      Row("S5", "(1, true, 1L, 2, 3).size", "b1" + Tuples("(1, true, 1L, 2, 3)"), 0, "SIZE: a tuple of 5 items is a Coll of 5."),
      Row("S2", "(1, true).size", "b1" + Tuples("(1, true)"), 0, "SIZE: " + Pair))
    pairs ++ triples ++ wider ++ defaults ++ dyn ++ uses
  }

  /** The slots each row is authored at: (tree version, activated). */
  val Slots: Seq[(Int, Int)] = Seq((0, 2), (0, 3), (3, 3))
  def treeHex(r: Row, tv: Int): String = if (tv == 0) "00" + r.body else "0b" + vlq(r.body.length / 2) + r.body
  def op(activated: Int): String = if (activated == 3) Op + "_v6" else Op

  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

  def outcome(r: Row, tv: Int, a: Int): Outcome = {
    val t = treeHex(r, tv)
    Try(VersionContext.withVersions(a.toByte, tv.toByte)(sigma.santa.LenientErgoTree.deserialize(b(t)))) match {
      case Failure(e)                     => RefusedAtParse(e)
      case Success(et) if et.root.isLeft  => RefusedAtParse(et.root.left.get.error)
      case Success(_) => EvalCore.evalAppliedOutcome(t, intInput(r.var1), a.toByte)._2 match {
        case Right((v, c)) => Answer(v, c)
        case Left(e)       => ThrowsAtEval(e)
      }
    }
  }

  private def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq
  def innermost(t: Throwable): Throwable = chain(t).last
  private def why(t: Throwable): String = {
    val c = innermost(t)
    val m = Option(c.getMessage).getOrElse("").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll("\\s+", " ").trim
    val cut = if (m.length <= 110) m else m.take(110).reverse.dropWhile(_ != ' ').reverse.trim + "…"
    s"${c.getClass.getSimpleName}${if (cut.isEmpty) "" else ": " + cut}"
  }
  def said(o: Outcome): String = o match {
    case Answer(v, c)      => s"${AuthoredEvalPreActivation.text(v)} at cost $c"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }

  def extract(): Map[String, Json] =
    Seq(2, 3).map { a =>
      val o = op(a)
      val entries = (for { r <- Rows; (tv, sa) <- Slots if sa == a } yield (r, tv)).zipWithIndex.map { case ((r, tv), i) =>
        val res = outcome(r, tv, a)
        val script = s"[${r.id}] `${r.expr}` — ${said(res)}. ${r.label}  // tree v$tv, activated $a"
        val name = s"tuple-by-index-${r.id.toLowerCase}-t$tv-a$a#$i"
        res match {
          case Answer(_, _) => SpecExtract.authoredEntryV(o, script, treeHex(r, tv), name, intInput(r.var1), a.toByte, tv)
          case _ => SpecExtract.authoredRejectEntryV(o, script, treeHex(r, tv), name, intInput(r.var1), a.toByte, tv)
        }
      }
      o -> SpecExtract.authoredEnvelope(o, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalTupleByIndex", extract(), outDir)
}
