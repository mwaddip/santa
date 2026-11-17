package santa

// Authored eval vectors for sigma-rust's santa-probe-avltree-item-bind (2026-11-17, its queue item 32): the AvlTree data
// methods with a key, a keys item or an entry of another kind. Measured first in santa.spike.AvlTreeItemBindProbe
// (local): all 4,729 row-activations as the jars on outcome, value and cost. The reply is
// prompts/drafts/sigma-rust-avltree-item-bind-reply.md. Transaction companion: AuthoredTxAvlTreeItemBind.
//
// THE RULE, read in 6.0.7 (interpreter/shared/.../CErgoTreeEvaluator.scala) and measured. The parser does not check a
// method call's argument against the signature, so a keys or entries argument can be a 3-tuple constant (a `Coll[Any]`
// at runtime) or a collection constant of another item type, and the evaluator meets the item where it first uses it:
//  * `getMany` binds each key in `keys.map { key => … }` (:118), before the lookup's cost: an item that is no
//    collection throws `ClassCastException … sigma.Coll` there;
//  * `insert`, `update`, `insertOrUpdate` bind each entry in `forall { case (key, value) => … }` (:140, :178, :209),
//    before the operation's cost: an entry that is no pair throws `… scala.Tuple2`, a pair whose key or value is no
//    collection throws `… sigma.Coll`;
//  * every method reads a key or value with `.toArray` INSIDE the cost block, so a collection of another item type
//    throws `… cannot be cast to class [B` after the operation's cost;
//  * `remove` reads `operations(i).toArray` inside the cost block (:240-244), so a key that is no collection throws
//    there, after the cost;
//  * `contains` and `get` take the key as the call's own argument: one of another class is refused by the reflective
//    call (`IllegalArgumentException`), a collection of another item type at `key.toArray` (`[B`).
// And where an item is never read, the call ANSWERS whatever the item is: the tree's flag is off (`insert`, `update`,
// `insertOrUpdate`, `remove` answer None before anything is read), or the proof does not belong to the tree and the
// item is the SECOND: `update` and `insertOrUpdate` stop at the first failed entry with None, `insert` does from tree
// version 3 (below it the first entry throws `Incorrect insert`); `getMany` throws at the first key, `remove` reads
// every key.
//
// THE ROWS: sigma-rust's 3,377 (src/test/resources/sigma-rust-avltree-item-bind/rows.tsv, with the jars' outcome, value
// and cost as an independent cross-check; generator gen32.py on its side), each the bare root
// `<tree>.<method>(<argument>, <proof>)` at tree version 0 (unsized, `00` + body; authored at activated 2 and 3, which
// answer alike) or 3 (`0b` + size + body, activated 3). A row is `<method>|<tree>|<carrier>|[<side>|]<kind>|v<tv>`:
// `tree` the fixture tree {1: 10, 2: 20} under 1-byte keys with every operation allowed and a valid proof of the call's
// operation, `other` a tree the proof does not belong to, `flag-off` the fixture tree with its flags off; the carrier
// `t1` / `t2` a 3-tuple constant with the item first / second (after a valid one, then `0`), `c` a one-item collection
// constant of the item's own type, `key` the item as `contains` / `get`'s key argument, `control` / `empty` well-typed;
// for an entry, `side` says what the item replaces: `nopair` the whole entry, `key`, `value`, `both`, or `mixed` (one
// side `Coll[Int]`, the other `Int`). Tree-v0 rows go to `AvlTree.item_bind` at activated 2 and to `_v6` at
// activated 3, tree-v3 rows to `_v6`.
//
// LEFT OUT: the 8 row-activations whose tree (and activated version) AvlTree.method_call_charge_order already holds,
// with the same outcome (AuthoredEvalAvlChargeOrder; checked in the test).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.serialization.SigmaSerializer

object AuthoredEvalAvlTreeItemBind {
  val Source = "santa:authored-avltree-item-bind"
  val Stamp = "jvm:sigma-state-6.0.7"
  val Op5 = "AvlTree.item_bind"
  val Op6 = "AvlTree.item_bind_v6"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-avltree-item-bind", "rows.tsv")

  // ── the rows ─────────────────────────────────────────────────────────────────────────────────────
  /** One vendored row at one activated version. `jars*` is sigma-rust's measurement on the 6.0.7 jars; `what` its
    * class of the row. */
  case class Row(name: String, treeVersion: Int, activated: Int, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String, what: String) {
    private val f = name.split('|').toVector
    def method: String = f(0)
    def tree: String = f(1)
    def carrier: String = f(2)
    /** The entry methods' side, if the row has one. */
    def side: Option[String] = if (f.size == 6) Some(f(3)) else None
    /** The item's kind (`mixed` rows: the pair), or None for a control. */
    def kind: Option[String] = if (control) None else Some(f(f.size - 2))
    def control: Boolean = carrier == "control" || carrier == "empty"
    def entries: Boolean = Set("insert", "update", "insertOrUpdate")(method)
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def op: String = if (activated == 3) Op6 else Op5
    def slug: String = name.replace('|', '-').replace("Coll[", "Coll_").replace("Option[", "Option_")
      .replaceAll("[^A-Za-z0-9.+_=-]", "_").replaceAll("_+", "_")
  }

  def allRows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Row(c(1), c(2).toInt, a, c(4), c(5), c(6), c(7), c(9)))
    }
  }

  /** The (tree, activated version) pairs AvlTree.method_call_charge_order already vectors. */
  lazy val chargeOrder: Map[(String, Int), AuthoredEvalAvlChargeOrder.Row] =
    AuthoredEvalAvlChargeOrder.Rows.map(r => (r.treeHex, r.activated.toInt) -> r).toMap
  def inChargeOrder(r: Row): Boolean = chargeOrder.contains((r.treeHex, r.activated))

  /** The rows authored here: every row-activation but those the charge-order family holds. */
  def rows(): Vector[Row] = allRows().filterNot(inChargeOrder)

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  // ── the oracle ───────────────────────────────────────────────────────────────────────────────────
  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

  /** The tree read under the row's own (activated, tree version), as a node reads a script. */
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

  /** sigma-rust's text form of a value (an AvlTree is `<tree>`). */
  def text(j: Json): String = AuthoredEvalPreActivation.text(j)

  def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq
  private def innermost(t: Throwable): Throwable = chain(t).last
  private def msg(t: Throwable): String = Option(t.getMessage).getOrElse("")

  /** Where the JVM refuses, read off the innermost failure. */
  sealed trait Refusal
  case object NotAColl extends Refusal     // `… cannot be cast to class sigma.Coll`
  case object NotAPair extends Refusal     // `… cannot be cast to class scala.Tuple2`
  case object NotBytes extends Refusal     // `… cannot be cast to class [B`
  case object ReflectiveCall extends Refusal // IllegalArgumentException at the reflective call
  case object ProofFails extends Refusal   // InterpreterException: the first operation fails
  def refusal(t: Throwable): Refusal = {
    val c = innermost(t)
    val m = msg(c)
    c match {
      case _: ClassCastException if m.contains("cannot be cast to class sigma.Coll ")   => NotAColl
      case _: ClassCastException if m.contains("cannot be cast to class scala.Tuple2 ") => NotAPair
      case _: ClassCastException if m.contains("cannot be cast to class [B ")            => NotBytes
      case _: IllegalArgumentException                                                  => ReflectiveCall
      case _ if c.getClass.getSimpleName == "InterpreterException" &&
        (m.startsWith("Tree proof is incorrect") || m.startsWith("Incorrect insert"))   => ProofFails
      case _ => sys.error(s"an unclassified failure: ${c.getClass.getName}: ${m.take(200)}")
    }
  }

  /** A failure as the script states it: the innermost cause's class and message, reproducibly. The reflective call's
    * IllegalArgumentException says `argument type mismatch` before HotSpot's reflection inflation and
    * `java.lang.ClassCastException@<hash>` after it, so its message is left out; a ClassCastException's module and
    * loader note is dropped; the tree an InterpreterException prints is elided. */
  private def why(t: Throwable): String = {
    val c = innermost(t)
    val m = c match {
      case _: IllegalArgumentException => ""
      case _: ClassCastException       => msg(c).replaceAll(" \\(.*$", "")
      case _ => msg(c).replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll("CAvlTree\\(AvlTreeData\\(.*", "<the tree> …")
    }
    val clean = m.replaceAll("\\s+", " ").trim
    s"${c.getClass.getSimpleName}${if (clean.isEmpty) "" else ": " + clip(clean, 140)}"
  }
  private def clip(m: String, n: Int): String =
    if (m.length <= n) m else m.take(n).reverse.dropWhile(_ != ' ').reverse.trim + "…"
  def said(o: Outcome): String = o match {
    case Answer(v, cost)   => s"${text(v)} at cost $cost"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }

  // ── the scripts ──────────────────────────────────────────────────────────────────────────────────
  val Item: Map[String, String] = Map(
    "Boolean" -> "true", "Byte" -> "5.toByte", "Short" -> "5.toShort", "Int" -> "5", "Long" -> "5L",
    "BigInt" -> "5.toBigInt", "GroupElement" -> "g", "SigmaProp" -> "proveDlog(g)",
    "UnsignedBigInt" -> "5.toUnsignedBigInt", "AvlTree" -> "<the fixture tree>", "Unit" -> "()", "String" -> "\"a\"",
    "Option[Int]" -> "Some(5)", "(Int,Int)" -> "(5, 5)",
    "(Coll[Byte],Coll[Byte])" -> "(Coll[Byte](1), Coll[Byte](9))", "Coll[Int]" -> "Coll(5)",
    "Coll[Boolean]" -> "Coll(true)", "Coll[Long]" -> "Coll(5L)", "Coll[Coll[Byte]]" -> "Coll(Coll[Byte](1))",
    "Coll[(Int,Int)]" -> "Coll((5, 5))", "(Int,Int,Int)" -> "(5, 5, 5)")
  private def typeOf(kind: String): String = kind.replace(",", ", ")

  /** (key, value) of the call's own valid entry, and the proof's operation. */
  private val Entry: Map[String, (String, String)] = Map(
    "insert" -> ("Coll[Byte](3)", "longToByteArray(30L)"), "update" -> ("Coll[Byte](1)", "longToByteArray(11L)"),
    "insertOrUpdate" -> ("Coll[Byte](3)", "longToByteArray(30L)"))
  private val ProofOf: Map[String, String] = Map(
    "contains" -> "a lookup of key 1", "get" -> "a lookup of key 1", "getMany" -> "lookups of keys 1 and 2",
    "remove" -> "the removal of key 1", "insert" -> "the insertion of key 3 -> 30",
    "update" -> "the update of key 1 -> 11", "insertOrUpdate" -> "the insertion of key 3 -> 30")

  /** The wrong thing an entry carrier holds. */
  private def wrongEntry(r: Row): String = {
    val (k, v) = Entry(r.method)
    (r.side.get, r.kind.get) match {
      case ("nopair", x)                    => Item(x)
      case ("key", x)                       => s"(${Item(x)}, $v)"
      case ("value", x)                     => s"($k, ${Item(x)})"
      case ("both", x)                      => s"(${Item(x)}, ${Item(x)})"
      case ("mixed", "key-Coll[Int]-value-Int") => "(Coll(5), 5)"
      case ("mixed", "key-Int-value-Coll[Int]") => "(5, Coll(5))"
      case other                            => sys.error(s"${r.name}: unknown side $other")
    }
  }
  private def wrongType(r: Row): String = (r.side, r.kind.get) match {
    case (Some("nopair"), x)             => typeOf(x)
    case (None, x)                       => typeOf(x)
    case (Some("key"), x)              => s"(${typeOf(x)}, Coll[Byte])"
    case (Some("value"), x)              => s"(Coll[Byte], ${typeOf(x)})"
    case (Some("both"), x)               => s"(${typeOf(x)}, ${typeOf(x)})"
    case (Some("mixed"), "key-Coll[Int]-value-Int") => "(Coll[Int], Int)"
    case (Some("mixed"), _)              => "(Int, Coll[Int])"
    case other                           => sys.error(s"${r.name}: $other")
  }

  /** The call's argument, as the script states it. */
  def argument(r: Row): String = {
    val valid: String =
      if (r.entries) { val (k, v) = Entry(r.method); s"($k, $v)" }
      else "Coll[Byte](1)"
    val wrong = if (r.control) "" else if (r.entries) wrongEntry(r) else Item(r.kind.get)
    (r.method, r.carrier) match {
      case ("contains" | "get", "control") => "Coll[Byte](1)"
      case ("contains" | "get", "key")     => s"$wrong (a ${typeOf(r.kind.get)}, as the key argument itself)"
      case ("getMany", "control")          => "Coll(Coll[Byte](1), Coll[Byte](2))"
      case ("remove", "control")           => "Coll(Coll[Byte](1))"
      case (_, "control")                  => s"Coll($valid)"
      case (_, "empty")                    => if (r.entries) "Coll[(Coll[Byte], Coll[Byte])]()" else "Coll[Coll[Byte]]()"
      case (_, "t1") => s"($wrong, $valid, 0) (a 3-tuple constant, a Coll[Any] at runtime, the item of another kind FIRST)"
      case (_, "t2") =>
        s"($valid, $wrong, 0) (a 3-tuple constant, a Coll[Any] at runtime, the item of another kind SECOND, after a valid one)"
      case (_, "c")  => s"Coll[${wrongType(r)}]($wrong) (a one-item collection constant of the item's own type)"
      case other     => sys.error(s"${r.name}: unknown carrier $other")
    }
  }

  def treeText(r: Row): String = r.tree match {
    case "tree"     => s"the fixture tree {1: 10, 2: 20} (1-byte keys, every operation allowed) with a valid proof of ${ProofOf(r.method)}"
    case "other"    => s"a tree the proof (of ${ProofOf(r.method)} on the fixture tree) does not belong to"
    case "flag-off" => s"the fixture tree with every operation flag OFF (the proof of ${ProofOf(r.method)})"
  }

  def label(r: Row, o: Outcome): String = (o, r.control) match {
    case (Answer(_, _), true) => "CONTROL: every item well-typed; the call answers."
    case (ThrowsAtEval(_), true) =>
      "CONTROL: every item well-typed, but the proof does not belong to the tree, so the first operation fails and " +
        "the call throws (a lookup's `Tree proof is incorrect`; `insert` below tree version 3, `Incorrect insert`)."
    case (Answer(_, _), false) if r.tree == "flag-off" =>
      "NEVER READ: the tree's flag for this operation is off, so the call answers None before it reads any item: the " +
        "item of another kind is never bound. A port that checks or casts every item first refuses here."
    case (Answer(_, _), false) =>
      "NEVER READ: the proof does not belong to the tree, so the FIRST (valid) entry's operation fails and the call " +
        "stops there with None (`forall` breaks); the second item, of another kind, is never bound. A port that reads " +
        "every item first refuses here."
    case (ThrowsAtEval(t), false) => refusal(t) match {
      case NotAColl if r.method == "remove" =>
        "MOVE: the key is no collection; `remove` reads `operations(i).toArray` inside the cost block (after the " +
          "removal's cost) and the cast to `Coll` throws."
      case NotAColl if r.method == "getMany" =>
        "MOVE: the key is no collection; `getMany` binds each key in `keys.map { key => … }`, before the lookup's " +
          "cost, and the cast to `Coll` throws there."
      case NotAColl =>
        "MOVE: a side of the entry is no collection; the entry is bound in `forall { case (key, value) => … }`, " +
          "before the operation's cost, and the cast to `Coll` throws there, whatever the other side is."
      case NotAPair =>
        "MOVE: the entry is no pair; `case (key, value)` casts it to a pair, before the operation's cost, and throws."
      case NotBytes =>
        "MOVE: a key or value is a collection of another item type; the cast to `Coll` passes, and `.toArray` inside " +
          "the cost block (after the operation's cost) is cast to `byte[]` and throws."
      case ReflectiveCall =>
        "MOVE: the key argument is of another class; the method is reached by reflection, which refuses the argument " +
          "before the body runs (IllegalArgumentException)."
      case ProofFails =>
        "MOVE: the proof does not belong to the tree, so the FIRST (valid) operation fails and the call throws before " +
          "the item of another kind is reached."
    }
    case (RefusedAtParse(_), _) => "PARSE: the tree names a type or method its version does not have."
  }

  def script(r: Row, o: Outcome): String =
    s"[${r.name}] `tree.${r.method}(<argument>, proof)` on ${treeText(r)}, the argument ${argument(r)} — " +
      s"${said(o)}. ${label(r, o)}  // tree v${r.treeVersion}, activated ${r.activated}"

  // ── the vectors ──────────────────────────────────────────────────────────────────────────────────
  def extract(): Map[String, Json] =
    rows().groupBy(_.op).map { case (op, rs) =>
      val entries = rs.zipWithIndex.map { case (r, i) =>
        val o = outcome(r)
        val name = s"${r.slug}-a${r.activated}#$i"
        o match {
          case Answer(_, _) =>
            SpecExtract.authoredEntryV(op, script(r, o), r.treeHex, name, IntInput, r.activated.toByte, ergoTree = r.treeVersion)
          case _ =>
            SpecExtract.authoredRejectEntryV(op, script(r, o), r.treeHex, name, IntInput, r.activated.toByte,
              ergoTree = r.treeVersion)
        }
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalAvlTreeItemBind", extract(), outDir)
}
