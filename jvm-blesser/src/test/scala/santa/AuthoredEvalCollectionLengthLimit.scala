package santa

// Authored eval vectors for sigma-rust's santa-probe-collection-length-limit (2026-11-18, the fork's queue item 33): the
// sigmastate limit of 100,000 items on a collection built at evaluation (`MaxArrayLength`, 6.0.6 / 6.0.7
// `core/…/sigma/util/package.scala:7`). Measured first in santa.spike.CollectionLengthLimitProbe (local): all 1,686
// no-context row-activations as the jars on outcome, exception class, the count its message names, value and the
// evaluator's counter (at the throw too); the 58 cost-limit row-activations under their own limit; the 64 whole-tree
// row-activations evaluated (a block cost is our JIT cost / 10, rounded down) and spent. The reply is
// prompts/drafts/sigma-rust-collection-length-limit-reply.md. Transaction companion: AuthoredTxCollectionLengthLimit.
//
// The rule, in the forms the rows meet: the collection class's constructor refuses more than 100,000 items
// (`IllegalArgumentException`, `CollsOverArrays.scala:13-14`), and `append` checks the SUM before its copy
// (`safeConcatArrays_v5`, a `RuntimeException`; an empty LEFT side returns the right one unchecked). Six producers can
// return more than they were given (`Append`, `Coll.flatMap`, `Coll.patch`, `Global.serialize`, `SigmaPropBytes`,
// `SubstConstants`); the length-keeping operations pass exactly 100,000 items. A row's collection is built from a ONE-item
// constant by sixteen doublings through `ValDef`s, then a slice of it or `++` a slice, so a row is about 150 bytes.
//
// AUTHORED UNDER `SizeOf`: a row whose root is a collection is authored as that root under `SizeOf` (`b1` before it), its
// value the collection's length. The collection itself (up to 100,000 items, per-item JSON) cannot be an expected value,
// nor an actual: a port without the limit would write 200,000 items back. `SizeOf` evaluates its input first, so a
// refusal is the row's own (the same class, message and counter: pinned), and a value is the length at 14 more (pinned).
// Rows whose root is no collection (the `size` / `unused` / `sizeOf` rows, the whole trees) are their own bytes.
//
// THE FILES (all new; `_v6` at activated 3; a tree-v0 row at activated 2 and 3, a tree-v3 row at 3):
//   Append.length_limit                   `append: node|size|unused`, `both: append`
//   Coll.append_length_limit              `append: method` (the method form `dc0c09`: NoSuchMethodException at any length)
//   Coll.flatMap_length_limit             `grow: flatMap`, `both: flatMap`
//   Coll.patch_length_limit               `grow: patch`, `both: patch`
//   Global.serialize_length_limit_v6      `grow: serialize` (tree v3)
//   SigmaPropBytes.length_limit           `grow: propBytes|node`
//   SigmaProp.propBytes_length_limit      `grow: propBytes|method` (`db0801`: NoSuchMethodException)
//   SubstConstants.length_limit           `grow: subst`
//   Coll.length_kept_at_limit             `keep` (the length-keeping operations on exactly 100,000 items)
//   Coll.declared_count_at_parse          `parse` (the declared counts at the parse sites; refused at parse)
//   Coll.length_limit_whole_trees         `trees` (`sigmaProp(<producer>.size > 0)`, evaluated as the eval tier does)
// LEFT OUT: the cost-limit rows (the eval envelope has no cost-limit input; measured, pinned in the test), and a
// row-activation an earlier row of the same file already holds (the same tree and activated version, the same outcome).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import io.circe.parser.parse
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.{JitCost, SCollection, UnparsedErgoTree}
import sigma.data.{SigmaBoolean, TrivialProp}
import sigma.serialization.SigmaSerializer
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings

object AuthoredEvalCollectionLengthLimit {
  val Source = "santa:authored-collection-length-limit"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-collection-length-limit", "rows.tsv")
  val VectorsDir: java.nio.file.Path = java.nio.file.Paths.get("..", "vectors", "eval")
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  val R1: AuthoredEvalAndOrItemByItemRound.type = AuthoredEvalAndOrItemByItemRound
  /** `SizeOf`'s opcode. */
  val SizeOfCode = "b1"
  /** What `SizeOf` adds to a value's cost (its fixed cost). */
  val SizeOfCost = 14L

  // ── the rows ────────────────────────────────────────────────────────────────────────────────────
  case class Row(part: String, name: String, treeVersion: Int, activated: Int, limit: Option[Long], bytes: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String, fork: String, cls: String) {
    def section: String = name.takeWhile(_ != ':')
    def fields: Vector[String] = name.drop(section.length + 2).split('|').toVector
    def whole: Boolean = part.startsWith("whole tree")
    def header(body: String): String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + R1.vlq(body.length / 2) + body
    /** The row's own tree. */
    def rowTreeHex: String = if (whole) bytes else header(bytes)
    def slug: String = name.replace(": ", "-").replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_").replaceAll("_+", "_")
  }
  def allRows(): Vector[Row] = rowsOf(RowsFile)
  /** A rows file in the first request's 11 columns (the addendum's too), one Row per activated version. */
  def rowsOf(file: java.nio.file.Path): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(file), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).flatMap { c =>
      require(c.length == 11, s"11 columns: ${c.take(2).mkString(" ")}")
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Row(c(0), c(1), c(2).toInt, a, if (c(4).isEmpty) None else Some(c(4).toLong), c(5), c(6), c(7), c(8), c(9), c(10)))
    }
  }
  /** The eval tier's rows: every row but the cost-limit rows. */
  def evalRows(): Vector[Row] = allRows().filter(_.limit.isEmpty)

  /** The row's root parses (the eval tier's lenient root parse) and is a collection. */
  def collectionRooted(r: Row): Boolean = !r.whole && Try(VersionContext.withVersions(r.activated.toByte, r.treeVersion.toByte)(
    sigma.santa.LenientErgoTree.deserialize(b(r.rowTreeHex)))).toOption.flatMap(_.root.toOption).exists(_.tpe.isInstanceOf[SCollection[_]])
  /** The authored tree: a collection-rooted row under `SizeOf`, any other row its own bytes. */
  def treeHex(r: Row): String = if (collectionRooted(r)) r.header(SizeOfCode + r.bytes) else r.rowTreeHex

  def op(r: Row): String = {
    val base = (r.section, r.fields(0)) match {
      case ("append", "method") => "Coll.append_length_limit"
      case ("append", _) | ("both", "append") => "Append.length_limit"
      case ("grow" | "both", "flatMap") => "Coll.flatMap_length_limit"
      case ("grow" | "both", "patch") => "Coll.patch_length_limit"
      case ("grow", "serialize") => "Global.serialize_length_limit"
      case ("grow", "propBytes") => if (r.fields(1) == "method") "SigmaProp.propBytes_length_limit" else "SigmaPropBytes.length_limit"
      case ("grow", "subst") => "SubstConstants.length_limit"
      case ("keep", _) => "Coll.length_kept_at_limit"
      case ("parse", _) => "Coll.declared_count_at_parse"
      case ("trees", _) => "Coll.length_limit_whole_trees"
      case ("flat" | "fan" | "flat2", _) => "Coll.flatMap_length_limit" // the addenda (AuthoredEvalCollectionLengthLimit2 / 3)
    }
    base + (if (r.activated == 3) "_v6" else "")
  }

  // ── the oracle ──────────────────────────────────────────────────────────────────────────────────
  def outcomeOf(hex: String, activated: Int, treeVersion: Int): R1.Outcome = R1.parse(hex, activated, treeVersion) match {
    case Left(t)        => R1.RefusedAtParse(t)
    case Right(Left(t)) => R1.RefusedAtParse(t)
    case Right(Right(())) => EvalCore.evalAppliedOutcome(hex, IntInput, activated.toByte)._2 match {
      case Right((v, c)) => R1.Answer(v, c)
      case Left(t)       => R1.ThrowsAtEval(t)
    }
  }
  def outcome(r: Row): R1.Outcome = outcomeOf(treeHex(r), r.activated, r.treeVersion)

  /** The evaluator's counter where it stopped (at the throw too), under the eval tier's limit or the row's own: what the
    * jars' cost column holds. (outcome, the class chain or the value, the last cause's message, the counter.) */
  final case class Measured(outcome: String, what: String, msg: String, counter: Long)
  def measure(hex: String, activated: Int, treeVersion: Int, limit: Option[Long]): Measured =
    Try(VersionContext.withVersions(activated.toByte, treeVersion.toByte)(sigma.santa.LenientErgoTree.deserialize(b(hex)))) match {
      case Failure(t) => Measured("refused at parse", classes(t), "", 0)
      case Success(tree) => tree.root match {
        case Left(UnparsedErgoTree(_, err)) => Measured("refused at parse", classes(err), lastMessage(err), 0)
        case Right(_) => VersionContext.withVersions(activated.toByte, tree.version) {
          val ctx = EvalCore.contextWithVar1(tree, activated.toByte, EvalCore.decodeBinding(IntInput))
          val lim = limit.map { l => require(l % 10 == 0, s"a limit in tens: $l"); JitCost.fromBlockCost(Math.toIntExact(l / 10)) }
            .getOrElse(JitCost.fromBlockCost(Math.toIntExact(ctx.costLimit)))
          val acc = new CostAccumulator(initialCost = JitCost.fromBlockCost(Math.toIntExact(ctx.initCost)), costLimit = Some(lim))
          Try(CErgoTreeEvaluator.eval(ctx.toSigmaContext(), acc, tree.constants,
            tree.toProposition(replaceConstants = tree.isConstantSegregation && tree.hasDeserialize), DefaultEvalSettings)) match {
            case Success((v, _)) => Measured("value", text(EvalCore.valueToJson(v)), "", acc.totalCost.value.toLong)
            case Failure(t)      => Measured("error", classes(t), lastMessage(t), acc.totalCost.value.toLong)
          }
        }
      }
    }
  def classes(t: Throwable): String = R1.chain(t).map(_.getClass.getSimpleName).mkString("<-")
  def lastMessage(t: Throwable): String = Option(R1.chain(t).last.getMessage).getOrElse("")

  private val TrueHex = hx(SigmaBoolean.serializer.toBytes(TrivialProp.TrueProp))
  private val FalseHex = hx(SigmaBoolean.serializer.toBytes(TrivialProp.FalseProp))
  /** A value as the jars' rows write it: a collection by its length, a trivial SigmaProp as TrueProp / FalseProp. */
  def text(v: Json): String = v.hcursor.get[String]("kind").toOption.getOrElse("") match {
    case "Coll" => s"a collection of ${v.hcursor.downField("items").values.get.size} items"
    case "SigmaProp" => v.hcursor.get[String]("raw_hex").toOption.get.toLowerCase match {
      case TrueHex => "TrueProp"; case FalseHex => "FalseProp"; case h => s"<sigmaprop $h>"
    }
    case _ => AuthoredEvalPreActivation.text(v)
  }

  // ── the scripts ─────────────────────────────────────────────────────────────────────────────────
  def rootText(hex: String): String = {
    val t = Try(VersionContext.withVersions(3, 3)(sigma.santa.LenientErgoTree.deserialize(b(hex)).root.toOption.get.toString))
      .toOption.getOrElse("<refused at parse>").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll(R1.LambdaClass, "\\$\\$Lambda")
      .replaceAll("\\s+", " ")
    if (t.length <= 220) t else t.take(220) + "…"
  }
  private def why(t: Throwable): String = {
    val c = R1.chain(t).last
    val m = Option(c.getMessage).getOrElse("") match {
      case s if c.isInstanceOf[ClassCastException] => s.replaceAll(" \\(.*$", "")
      case s => s
    }
    val clean = m.replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll(R1.LambdaClass, "\\$\\$Lambda").replaceAll("\\s+", " ").trim
    s"${c.getClass.getSimpleName}${if (clean.isEmpty) "" else ": " + (if (clean.length > 140) clean.take(140) + "…" else clean)}"
  }
  def said(o: R1.Outcome): String = o match {
    case R1.Answer(v, cost)   => s"${text(v)} at cost $cost"
    case R1.RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case R1.ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }
  val Rule: String = "sigma-state holds a collection built at evaluation to at most 100,000 items (`MaxArrayLength`): the " +
    "collection class's constructor refuses more (`IllegalArgumentException`), and `append` checks the SUM before its " +
    "copy (`RuntimeException`; an empty left side returns the right one unchecked)."
  /** The producer the row is about, as the language names it. */
  def producer(r: Row): String = (r.section, r.fields(0)) match {
    case ("keep", "blake") => "`blake2b256`"
    case ("keep", "sizeOf") => "`SizeOf`"
    case ("keep", "xorNode") => "`Xor`"
    case ("keep", "xorMethod") => "`Global.xor`"
    case ("keep", "mapToInt") => "`Coll.map` (to an `Int`)"
    case ("keep", p) => s"`Coll.$p`"
    case ("flat" | "fan" | "flat2", _) => "`Coll.flatMap`"
    case ("append", "method") => "`Coll.append`"
    case ("append", _) | (_, "append") => "`Append`"
    case (_, "flatMap") => "`Coll.flatMap`"
    case (_, "patch") => "`Coll.patch`"
    case (_, "serialize") => "`Global.serialize`"
    case (_, "propBytes") => if (r.fields(1) == "method") "`SigmaProp.propBytes`" else "`SigmaPropBytes`"
    case (_, "subst") => "`SubstConstants`"
    case (_, p) => s"`$p`"
  }
  /** What the row varies, in the request's words (sigma-rust's class of the row decides the clause). */
  def label(r: Row): String = (r.section, r.cls) match {
    case ("append", _) if r.fields(0) == "unused" => s"AN UNUSED `val` IS EVALUATED: the `append` is bound, never read, " +
      s"and still refused. $Rule"
    case ("append" | "grow", "ERR/ERR") => "THE METHOD FORM (`Coll.append` `dc0c09`, `SigmaProp.propBytes` `db0801`): it " +
      "parses, and the evaluator finds no such method on the receiver (`NoSuchMethodException`) at any length; a port " +
      "that answers the method form answers here."
    case ("both", _) => "TWO REFUSALS AT ONCE: the result would be past the limit AND the other side's array is of " +
      "another class; a specialised receiver casts the other array first (`ClassCastException`, whatever the lengths), a " +
      "generic receiver's `append` refuses the SUM first, a generic receiver's `patch` stores the items first."
    case ("parse", "PARSE") => "THE DECLARED COUNT of a collection at a parse site (an expression's item count, or a data " +
      "collection's): refused at parse."
    case ("parse", _) => "THE DECLARED COUNT's control: a `Coll[Byte]` constant of 65,535 bytes in full (a data " +
      "collection's count is an unsigned short) parses and answers."
    case ("keep", _) => s"KEPT: ${producer(r)} keeps or shortens a length, and 100,000 items pass through it."
    case ("trees", "REFUSED") => s"A WHOLE TREE `sigmaProp(<producer>.size > 0)` (constants ${r.fields(2)}) at the first " +
      s"refused count: the reduction throws, in reach of any block. $Rule"
    case ("trees", _) => s"A WHOLE TREE `sigmaProp(<producer>.size > 0)` (constants ${r.fields(2)}) at the last accepted " +
      "count: it reduces to TrueProp."
    // the addendum (santa-probe-collection-length-limit-2.md): when `flatMap` refuses, and the fan-out
    case ("fan", _) => "THE FAN-OUT: every lambda call returns ONE shared collection (`v17.flatMap(x => v18)`, 65,536 " +
      "calls), so the result's length grows with the calls at a price that does not; the jars refuse both lengths " +
      s"(131,072 and 4,194,304 items) at the same counter, before `flatMap`'s own cost. $Rule A port without the limit " +
      "holds the whole result before it answers."
    case ("flat", "ERR/ERR") => r.fields(0) match {
      case "long" | "pairNot" => "AN ITEM THE RESULT'S ARRAY CANNOT HOLD is refused at its part, before the next lambda " +
        "runs, even after parts already past the limit (`ClassCastException`; a part holding no pairs under a pair " +
        "result, `ArrayStoreException`): `Coll.flatMap` hands each part to an array builder of the result's item class " +
        "as its lambda returns it (6.0.6 `CollsOverArrays.scala:70-73`). Which refusal, and the counter at it, are the row."
      case "nestL" | "nestR" => "NESTED PAIRS: the left side is built before the right one, so a wrong component inside " +
        "the LEFT side's pairs is refused before the length, one inside the RIGHT side's pairs only when the result is " +
        "within the limit; over it, the length comes first. Which refusal, and the counter at it, are the row."
      case _ => "A RESULT OF PAIRS: a component of another class is refused after the last lambda (`fromBoxedPairs` " +
        "stores both components of every pair before a side is built), the left component before the right one, and " +
        "before the length. Which refusal, and the counter at it, are the row."
    }
    case ("flat", "REFUSED") => "REFUSED FOR ITS LENGTH, LAST: `Coll.flatMap` builds the collection after its last part " +
      s"(`fromArray`), so the length is checked after every part, at a counter without `flatMap`'s own cost. $Rule A port " +
      "without the limit answers here."
    case ("flat", _) => "A CONTROL: every part the lambda returns is of the result's class, within the limit (a part of " +
      "another class sits behind `if (false)`)."
    // the second addendum (santa-probe-collection-length-limit-3.md): the part of another class is a specialised one
    case ("flat2", "ERR/ERR") => "A SPECIALISED PART OF ANOTHER CLASS (a `Coll[Byte]` or `Coll[Boolean]` constant, a " +
      "primitive array; or 3 pairs by `zip`): refused at its part when its items are stored (`ClassCastException`; under " +
      "a pair result, `ArrayStoreException`), over the limit too. A port that judges a part by its class without reading " +
      "its items differs here. Which refusal, and the counter at it, are the row."
    case ("flat2", "REFUSED") => "AN EMPTY PART OF ANOTHER CLASS is taken (no item to store), so the right parts past the " +
      s"limit are refused for the length, last. $Rule A port without the limit answers here."
    case ("flat2", _) => "A CONTROL: every part is taken (of the result's class, or an EMPTY part of another class: no " +
      "item to store), within the limit."
    case (_, "REFUSED") => s"REFUSED FOR ITS LENGTH: ${producer(r)} returns more than it was given, and its result is past " +
      s"100,000 items. $Rule A port without the limit answers here."
    case (_, "VALUE") => s"A CONTROL: ${producer(r)}'s result within 100,000 items (the last accepted count, or a result " +
      "the limit never meets)."
  }
  val Wrapped: String = " AUTHORED UNDER `SizeOf` (`b1` before the row's root): the row's root returns the collection " +
    "itself, too long to state as a value; the collection is built first, so a refusal is the row's own (its class, " +
    "message and counter), and a value is its length at 14 more."
  def script(r: Row, o: R1.Outcome): String =
    s"[${r.name}] `${rootText(treeHex(r))}` — ${said(o)}. ${label(r)}${if (collectionRooted(r)) Wrapped else ""}" +
      s"  // tree v${r.treeVersion}, activated ${r.activated}"

  // ── the vectors ─────────────────────────────────────────────────────────────────────────────────
  def slot(op: String): String = if (op.endsWith("_v6")) "v6" else "v5"
  def committed(op: String): Option[Json] = {
    val p = VectorsDir.resolve(slot(op)).resolve("authored").resolve(SpecExtract.slug(op) + ".json")
    if (java.nio.file.Files.exists(p)) Some(parse(new String(java.nio.file.Files.readAllBytes(p), "UTF-8")).fold(throw _, identity))
    else None
  }
  def entriesOf(j: Json): List[Json] = j.hcursor.downField("entries").as[List[Json]].toOption.get
  def key(r: Row): (String, Int) = (treeHex(r), r.activated)

  /** Per op: the rows it holds (the first of a tree and activated version), and the repeats left out. */
  def plan(): Map[String, (Vector[Row], Vector[Row])] = evalRows().groupBy(op).map { case (o, rs) =>
    val held = collection.mutable.Set[(String, Int)]()
    o -> rs.partition(r => held.add(key(r)))
  }

  def entry(o: String, r: Row, n: Int): Json = {
    val oc = outcome(r)
    val name = s"${r.slug}-a${r.activated}#$n"
    oc match {
      case R1.Answer(_, _) =>
        SpecExtract.authoredEntryV(o, script(r, oc), treeHex(r), name, IntInput, r.activated.toByte, ergoTree = r.treeVersion)
      case _ =>
        SpecExtract.authoredRejectEntryV(o, script(r, oc), treeHex(r), name, IntInput, r.activated.toByte, ergoTree = r.treeVersion)
    }
  }

  def extract(): Map[String, Json] = plan().map { case (o, (rs, _)) =>
    val entries = rs.zipWithIndex.map { case (r, i) => entry(o, r, i) }
    o -> SpecExtract.authoredEnvelope(o, Nil, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
      .mapObject(_.add("entries", Json.arr(entries: _*)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalCollectionLengthLimit", extract(), outDir)
}
