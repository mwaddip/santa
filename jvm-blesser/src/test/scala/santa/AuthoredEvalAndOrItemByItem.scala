package santa

// Authored eval vectors: `AND` and `OR` read their collection ITEM BY ITEM and stop at the deciding item, charging for
// the items they read. sigma-rust's finding (its queue item 41, 2026-11-17, announced in the last section of
// santa-probe-avltree-item-bind.md; no request of its own): fork `ac399ee2` casts the whole collection and charges for
// its length. Authored at the user's word (2026-11-17). Measured first in santa.spike.AndOrItemByItemProbe (local).
//
// THE RULE, read in 6.0.7 (data/shared/src/main/scala/sigma/ast/trees.scala, `case class OR` / `case class AND`):
//   val inputV = input.evalTo[Coll[Boolean]](env); var i = 0
//   E.addSeqCost(costKind, opDesc) { () => while (i < len && <undecided>) { res = res op inputV(i); i += 1 }; i }
// so the cost is the PerItemCost of the number of items READ (AND: base 10, 5 per chunk of 32; OR: base 5, 5 per chunk
// of 64), and an item after the deciding one is never read: a 3-tuple constant (a `Coll[Any]` at runtime) holding a
// value of another kind there answers, while one read before the decision throws ClassCastException (`inputV(i)`
// unboxed to a Boolean). The input itself must be a collection: a pair constant (a `Tuple2` at runtime) throws at
// `inputV.length`. A collection of another item type throws at its first read, and an EMPTY one is never read.
// A `ConcreteCollection` evaluates EVERY item before AND / OR sees the collection (values.scala, its `eval`), so an
// item after the deciding one is still evaluated and charged, and a throw there throws; `||` / `&&` (BinOr / BinAnd)
// are the lazy forms (the contrast rows).
//
// THE ROWS, each the bare root at tree version 0 (unsized, authored at activated 2 and 3) and at tree version 3 (`0b` +
// size, activated 3):
//   * `ladder`: a `Coll[Boolean]` constant of length L whose only deciding item is at index k (or none), lengths and
//     indices around the chunk boundaries (32 for AND, 64 for OR);
//   * `tuple`: a 3-tuple (or 5-tuple) constant of Booleans with one item of another kind, before or after the deciding
//     one; `pair`: a pair constant; `coll`: a collection constant of another item type, empty or not;
//   * `expr`: a `ConcreteCollection` holding an expression after (or before) the deciding item: one that throws
//     (`1 / 0 == 1`), one that costs (`blake2b256` of 1,024 bytes), and the lazy `||` / `&&` beside them;
//   * sigma-rust's own row, `sigmaProp(OR((true, 5, true)))` (`00d19748010401010a01`).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.serialization.SigmaSerializer

object AuthoredEvalAndOrItemByItem {
  val Source = "santa:authored-and-or-item-by-item"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  /** Booleans as the writer packs them (`putBits`): bit i of the run in byte i / 8, least significant bit first. */
  def bits(bs: Seq[Boolean]): String = {
    val bytes = Array.fill((bs.length + 7) / 8)(0)
    bs.zipWithIndex.foreach { case (b, i) => if (b) bytes(i / 8) |= 1 << (i % 8) }
    bytes.map(b => f"$b%02x").mkString
  }

  // ── the nodes ───────────────────────────────────────────────────────────────────────────────────
  val AndCode = "96"; val OrCode = "97"
  def opCode(op: String): String = if (op == "AND") AndCode else OrCode
  /** The value that decides `op` (AND: false, OR: true), and the one that leaves it undecided. */
  def deciding(op: String): Boolean = op == "OR"
  def neutral(op: String): Boolean = !deciding(op)
  private def bool(b: Boolean): String = if (b) "01" else "00"
  def collBool(bs: Seq[Boolean]): String = "0d" + vlq(bs.length) + bits(bs)

  /** An item of another kind: its type, its data, and how a script writes it. */
  final case class Kind(name: String, tpe: String, data: String, text: String)
  private val G = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  val Kinds: Seq[Kind] = Seq(
    Kind("Byte0", "02", "00", "0.toByte"), Kind("Byte1", "02", "01", "1.toByte"),
    Kind("Short", "03", "0a", "5.toShort"), Kind("Int1", "04", "02", "1"), Kind("Int5", "04", "0a", "5"),
    Kind("Long", "05", "0a", "5L"), Kind("BigInt", "06", "0105", "5.toBigInt"),
    Kind("GroupElement", "07", G, "g"), Kind("SigmaProp", "08", "cd" + G, "proveDlog(g)"),
    Kind("Unit", "62", "", "()"),
    Kind("Coll[Byte]", "0e", "0101", "Coll[Byte](1)"), Kind("Coll[Boolean]", "0d", "0101", "Coll(true)"),
    Kind("(Int,Int)", "58", "0a0a", "(5, 5)"))

  /** A tuple constant of 3 or 5 items, each a Boolean or the kind. */
  private def tuple(items: Seq[Either[Boolean, Kind]]): String = {
    val ts = items.map(_.fold(_ => "01", _.tpe)).mkString
    val ds = items.map(_.fold(bool, _.data)).mkString
    items.length match {
      case 3 => "48" + ts + ds
      case 5 => "60" + "05" + ts + ds
      case n => sys.error(s"no $n-tuple here")
    }
  }

  // ── the expressions ─────────────────────────────────────────────────────────────────────────────
  /** A Boolean constant as the writer writes it (`01` + its value; not the legacy one-byte `7f` / `80`). */
  val TrueLeaf = "0101"; val FalseLeaf = "0100"
  /** `1 / 0 == 1`: throws ArithmeticException. */
  val DivZero = "93" + "9d" + "0402" + "0400" + "0402"
  /** `1 / 1 == 1`: true. */
  val DivOne = "93" + "9d" + "0402" + "0402" + "0402"
  /** `blake2b256(<1,024 bytes of 0x07>).size == 32`: true, and costly. */
  val Blake = "93" + "b1" + "cb" + "0e" + vlq(1024) + "07" * 1024 + "0440"
  def concrete(items: Seq[String]): String = "83" + vlq(items.length) + "01" + items.mkString
  def leaf(b: Boolean): String = if (b) TrueLeaf else FalseLeaf
  val BinOr = "ec"; val BinAnd = "ed"

  // ── the rows ────────────────────────────────────────────────────────────────────────────────────
  /** One row: the node (`AND` / `OR`, or the lazy contrast), its group, and the root's body. */
  final case class Row(op: String, group: String, label: String, body: String, script: String) {
    def name: String = s"$op|$group|$label"
  }

  val Lengths: Seq[Int] = Seq(0, 1, 2, 32, 33, 64, 65, 100, 128, 129, 256)
  def ladder(op: String): Seq[Row] = Lengths.flatMap { len =>
    val ks = (Seq(-1, 0, 1, 31, 32, 63, 64) :+ (len - 1)).filter(k => k < len).distinct
    ks.map { k =>
      val bs = (0 until len).map(i => if (i == k) deciding(op) else neutral(op))
      val where = if (k < 0) s"no deciding item (every one ${neutral(op)})" else
        s"the only deciding item (${deciding(op)}) at index $k, every other ${neutral(op)}"
      Row(op, "ladder", s"L$len|${if (k < 0) "none" else s"k$k"}", opCode(op) + collBool(bs),
        s"`$op(<a Coll[Boolean] constant of $len items>)`, $where")
    }
  }

  /** The tuple shapes: d the deciding Boolean, n the neutral one, K the item of another kind. */
  val TupleShapes: Seq[String] = Seq("dKn", "ndK", "nKd", "Kdn", "nnK")
  def tuples(op: String): Seq[Row] = {
    def item(c: Char, k: Kind): Either[Boolean, Kind] = c match {
      case 'd' => Left(deciding(op)); case 'n' => Left(neutral(op)); case 'K' => Right(k)
    }
    def text(shape: String, k: Option[Kind]): String =
      shape.map { case 'd' => deciding(op).toString; case 'n' => neutral(op).toString; case 'K' => k.get.text }
        .mkString("(", ", ", ")")
    val withKinds = for { k <- Kinds; s <- TupleShapes } yield
      Row(op, "tuple", s"$s|${k.name}", opCode(op) + tuple(s.map(item(_, k))),
        s"`$op(${text(s, Some(k))})`, the argument a ${s.length}-tuple constant (a Coll[Any] at runtime)")
    val controls = Seq("dnn", "nnn", "nnd", "ndn").map { s =>
      Row(op, "tuple", s"$s|Boolean", opCode(op) + tuple(s.map(item(_, Kinds.head))),
        s"`$op(${text(s, None)})`, the argument a 3-tuple constant of Booleans (a Coll[Any] at runtime)")
    }
    val five = Seq("nnndK", "nnnnK").map { s =>
      val k = Kinds.find(_.name == "Int5").get
      Row(op, "tuple", s"$s|Int5", opCode(op) + tuple(s.map(item(_, k))),
        s"`$op(${text(s, Some(k))})`, the argument a 5-tuple constant (a Coll[Any] at runtime)")
    }
    withKinds ++ controls ++ five
  }

  def pairs(op: String): Seq[Row] = {
    val (d, n) = (deciding(op), neutral(op))
    Seq(
      ("dd", "55" + bool(d) + bool(d), s"($d, $d)"), ("nn", "55" + bool(n) + bool(n), s"($n, $n)"),
      ("dn", "55" + bool(d) + bool(n), s"($d, $n)"),
      ("dInt5", "3d04" + bool(d) + "0a", s"($d, 5)"), ("Int5d", "4001" + "0a" + bool(d), s"(5, $d)")
    ).map { case (l, c, t) =>
      Row(op, "pair", l, opCode(op) + c, s"`$op($t)`, the argument a PAIR constant (a Tuple2 at runtime)")
    }
  }

  def colls(op: String): Seq[Row] = Seq(
    ("Coll[Byte]", "0e", "01", "1.toByte"), ("Coll[Short]", "0f", "0a", "5.toShort"), ("Coll[Int]", "10", "0a", "5"),
    ("Coll[Long]", "11", "0a", "5L"), ("Coll[Coll[Byte]]", "1a", "0101", "Coll[Byte](1)"),
    ("Coll[(Int,Int)]", "0c58", "0a0a", "(5, 5)")
  ).flatMap { case (k, t, one, text) =>
    Seq(
      Row(op, "coll", s"$k|empty", opCode(op) + t + "00", s"`$op($k())`, an EMPTY collection constant of another item type"),
      Row(op, "coll", s"$k|one", opCode(op) + t + "01" + one,
        s"`$op($k($text))`, a one-item collection constant of another item type"))
  }

  def exprs(op: String): Seq[Row] = {
    val (d, n) = (leaf(deciding(op)), leaf(neutral(op)))
    val (dt, nt) = (deciding(op).toString, neutral(op).toString)
    val cc = Seq(
      ("d-divzero", Seq(d, DivZero), s"Coll($dt, 1 / 0 == 1)"),
      ("d-divone", Seq(d, DivOne), s"Coll($dt, 1 / 1 == 1)"),
      ("d-blake", Seq(d, Blake), s"Coll($dt, blake2b256(<1,024 bytes>).size == 32)"),
      ("n-divzero", Seq(n, DivZero), s"Coll($nt, 1 / 0 == 1)"),
      ("divzero-d", Seq(DivZero, d), s"Coll(1 / 0 == 1, $dt)"),
      ("d-n-divzero", Seq(d, n, DivZero), s"Coll($dt, $nt, 1 / 0 == 1)"))
    val lazyOp = if (op == "OR") ("BinOr", BinOr, "||") else ("BinAnd", BinAnd, "&&")
    cc.map { case (l, items, t) =>
      Row(op, "expr", l, opCode(op) + concrete(items), s"`$op($t)`, the argument a collection EXPRESSION (ConcreteCollection)")
    } ++ Seq(
      Row(lazyOp._1, "expr", "d-divzero", lazyOp._2 + d + DivZero, s"`$dt ${lazyOp._3} (1 / 0 == 1)`, the lazy binary form"),
      Row(lazyOp._1, "expr", "d-blake", lazyOp._2 + d + Blake,
        s"`$dt ${lazyOp._3} (blake2b256(<1,024 bytes>).size == 32)`, the lazy binary form"))
  }

  /** sigma-rust's row, verbatim: `sigmaProp(OR((true, 5, true)))`. */
  val SigmaRustRow: Row = Row("OR", "sigma-rust", "sigmaProp(true,5,true)", "d19748010401010a01",
    "`sigmaProp(OR((true, 5, true)))`, sigma-rust's row (`00d19748010401010a01`), the argument a 3-tuple constant")

  def baseRows(): Seq[Row] =
    Seq("AND", "OR").flatMap(op => ladder(op) ++ tuples(op) ++ pairs(op) ++ colls(op) ++ exprs(op)) :+ SigmaRustRow

  /** A row at one (tree version, activated version). */
  final case class Slot(row: Row, treeVersion: Int, activated: Int) {
    def treeHex: String =
      if (treeVersion == 0) "00" + row.body else f"${8 + treeVersion}%02x" + vlq(row.body.length / 2) + row.body
    /** The file: the node's (the contrast rows go with the node they contrast), v5 or v6. */
    def op: String = {
      val node = row.op match { case "BinOr" => "OR"; case "BinAnd" => "AND"; case o => o }
      s"$node.item_by_item" + (if (activated == 3) "_v6" else "")
    }
    def slug: String = row.name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_").replaceAll("_+", "_")
  }
  def slots(): Vector[Slot] = baseRows().toVector.flatMap(r => Seq(Slot(r, 0, 2), Slot(r, 0, 3), Slot(r, 3, 3)))

  // ── the oracle ──────────────────────────────────────────────────────────────────────────────────
  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

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

  def outcome(s: Slot): Outcome = parse(s.treeHex, s.activated, s.treeVersion) match {
    case Left(t)        => RefusedAtParse(t)
    case Right(Left(t)) => RefusedAtParse(t)
    case Right(Right(())) =>
      EvalCore.evalAppliedOutcome(s.treeHex, IntInput, s.activated.toByte)._2 match {
        case Right((v, cost)) => Answer(v, cost)
        case Left(t)          => ThrowsAtEval(t)
      }
  }

  def text(j: Json): String = AuthoredEvalPreActivation.text(j)
  def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq

  // ── the labels ──────────────────────────────────────────────────────────────────────────────────
  /** AND / OR's PerItemCost: (base, chunk size). */
  def chunkSize(op: String): Int = if (op == "AND") 32 else 64
  /** PerItemCost's chunk count, as the JVM computes it: `(n - 1) / chunkSize + 1` in Int division, so 0 items is 1. */
  def chunks(op: String, n: Int): Int = (n - 1) / chunkSize(op) + 1
  /** A ladder row: its length, the deciding index (or -1), and the number of items the JVM reads. */
  def ladderOf(r: Row): (Int, Int, Int) = {
    val Array(l, k) = r.label.split('|')
    val len = l.drop(1).toInt
    val idx = if (k == "none") -1 else k.drop(1).toInt
    (len, idx, if (idx < 0) len else idx + 1)
  }

  /** The row's class: what the vector pins. */
  def cls(r: Row): String = (r.group, r.label) match {
    case ("ladder", _) =>
      val (len, _, read) = ladderOf(r)
      if (chunks(r.op, read) != chunks(r.op, len)) "CHUNKS" else "CONTROL"
    case ("tuple", l) =>
      val shape = l.takeWhile(_ != '|')
      val k = shape.indexOf('K')
      val d = shape.indexOf('d')
      if (k < 0) "CONTROL" else if (d >= 0 && d < k) "NEVER READ" else "MOVE"
    case ("pair", _) => "MOVE"
    case ("coll", l) => if (l.endsWith("|empty")) "NEVER READ" else "MOVE"
    case ("expr", _) if r.op.startsWith("Bin") => "CONTRAST"
    case ("expr", l) => if (Set("n-divzero", "divzero-d")(l)) "CONTROL" else "EAGER"
    case ("sigma-rust", _) => "NEVER READ"
  }

  def label(r: Row): String = cls(r) match {
    case "CHUNKS" =>
      val (len, _, read) = ladderOf(r)
      s"CHUNKS: the JVM reads $read of the $len items (up to the deciding one) and charges ${chunks(r.op, read)} " +
        s"chunk(s) of ${chunkSize(r.op)}; charging for the whole collection is ${chunks(r.op, len)}."
    case "CONTROL" if r.group == "ladder" =>
      val (len, _, read) = ladderOf(r)
      s"CONTROL: the JVM reads $read of the $len items; the whole collection is the same number of chunks of " +
        s"${chunkSize(r.op)}."
    case "CONTROL" if r.group == "tuple" => "CONTROL: every item a Boolean; the call reads up to the deciding one."
    case "CONTROL" =>
      "CONTROL: the throwing item comes before the decision, so every reading of the collection throws."
    case "NEVER READ" if r.group == "coll" =>
      s"NEVER READ: the collection is empty, so no item is read and the call answers ${neutral(r.op)}, whatever the " +
        "item type. A port that checks the item type first refuses here."
    case "NEVER READ" =>
      "NEVER READ: the item of another kind comes after the deciding item, so the loop stops before it and the call " +
        "answers. A port that casts or checks the whole collection first refuses here."
    case "MOVE" if r.group == "pair" =>
      "MOVE: a pair is a Tuple2 on the JVM, no collection: `inputV.length` throws ClassCastException whatever its items."
    case "MOVE" =>
      "MOVE: the loop reaches the item of another kind before any deciding item, and unboxing it to a Boolean " +
        "throws ClassCastException."
    case "EAGER" =>
      "EAGER: a ConcreteCollection evaluates (and charges) EVERY item before AND / OR reads it, so the item after the " +
        "deciding one is evaluated even though the loop never reads it. A port that evaluates the items lazily " +
        "answers (or charges less) here."
    case "CONTRAST" =>
      "CONTRAST: the binary form is lazy, so its right side is never evaluated (BinOr / BinAnd), unlike the same " +
        "items in a collection."
  }

  /** A failure as the script states it: the innermost class and message, a ClassCastException's module and loader
    * note dropped. */
  private def why(t: Throwable): String = {
    val c = chain(t).last
    val m = Option(c.getMessage).getOrElse("") match {
      case m if c.isInstanceOf[ClassCastException] => m.replaceAll(" \\(.*$", "")
      case m => m
    }
    s"${c.getClass.getSimpleName}${if (m.isEmpty) "" else ": " + m}"
  }
  def said(o: Outcome): String = o match {
    case Answer(v, cost)   => s"${text(v)} at cost $cost"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }
  def script(s: Slot, o: Outcome): String =
    s"[${s.row.name}] ${s.row.script} — ${said(o)}. ${label(s.row)}  // tree v${s.treeVersion}, activated ${s.activated}"

  // ── the vectors ─────────────────────────────────────────────────────────────────────────────────
  def extract(): Map[String, Json] =
    slots().groupBy(_.op).map { case (op, ss) =>
      val entries = ss.zipWithIndex.map { case (s, i) =>
        val o = outcome(s)
        val name = s"${s.slug}-tv${s.treeVersion}-a${s.activated}#$i"
        o match {
          case Answer(_, _) =>
            SpecExtract.authoredEntryV(op, script(s, o), s.treeHex, name, IntInput, s.activated.toByte, ergoTree = s.treeVersion)
          case _ =>
            SpecExtract.authoredRejectEntryV(op, script(s, o), s.treeHex, name, IntInput, s.activated.toByte,
              ergoTree = s.treeVersion)
        }
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalAndOrItemByItem", extract(), outDir)
}
