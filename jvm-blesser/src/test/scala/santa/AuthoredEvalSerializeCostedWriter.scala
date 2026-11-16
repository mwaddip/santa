package santa

// Authored eval vectors for sigma-rust's santa-probe-serialize-costed-writer (2026-11-16): `Global.serialize` charges
// as it writes. Measured first in santa.spike.SerializeCostedWriterProbe; the reply is
// prompts/drafts/sigma-rust-serialize-costed-writer-reply.md.
//
// THE RULE, read in 6.0.7 (methods.scala:1968-1983, SigmaByteWriter.scala:45-174) and measured: the writer of
// `Global.serialize` adds the cost of each put to the evaluator BEFORE it makes the put, the evaluator tests the limit at
// every add, and the writer writes the value BY ITS STATIC TYPE. So:
//  - the cost limit is the only bound on the write. A collection built at evaluation holds references: 2^m items that
//    are all ONE collection of 32,768 bytes cost under 2,000 to build and are 32,768 x 2^m bytes written. The JVM stops
//    at the put that crosses the limit (CostLimitException at about 10,030,000 of 10,000,000), and a result of more than
//    100,000 bytes written within the limit is refused for its length (`Cannot create collection with size N greater
//    than 100000`). A port that writes it all first and charges after holds the whole write before it refuses; one with
//    no limit answers its size.
//  - a value of another class than its static type (`if (false) <valid> else <other>`: `If` checks a branch by its
//    kind only, so the else branch rides under the then branch's type) is refused when the writer reaches it, after
//    what it wrote before; Unit and a None cast nothing and are written. A port that writes the runtime value by its
//    own class, or converts the value before writing, answers there.
//
// THE ROWS: sigma-rust's 920 (src/test/resources/sigma-rust-serialize-costed-writer/rows.tsv, as sent: a bare root
// expression at tree version 3 and activated 3, with the jars' outcome, value and cost and fork cc29855f's answer).
// Every expectation is our oracle's; all 780 rows at the default limit agree with the jars in outcome, value and cost.
// Authored: 780, 763 into five `v6` files and the 17 HELD rows each a bounded file (below). Not authored:
//  - the 140 `limit` rows: each runs under its own cost limit (10 to 200), and the eval schema carries no cost limit
//    (every entry evaluates at the default). With one, they separate nothing on a tier that grades value, cost and
//    `errored`: a write-first port reaches the same total and fails the same check.
// THE 17 HELD rows, whose nested value is more than 64 MiB written or printed in full (2^12 and more inner copies, every
// three-level row, the 32,768 x 32,768 error rows), are BOUNDED FILES: one entry each and a resource budget
// (`SpecExtract.DefaultLimits`, runner-integration.md §3a), so every runner runs each in its own process under that
// budget and a hit grades `exceeded` on that entry alone. Fork cc29855f writes before it charges and takes 1 GB to
// 24 GB on them, aborting on three; upstream sigma-rust has no cost limit at all; the JVM stops at the cost limit
// within its baseline. Every row in the five files, measured one a process under a 3 GB cap on blitzen (fork and
// upstream), vixen and dasher, ends within 0.6 s and 500 MB.
// The trees are OUR writing of each expression (the JVM writes 729 of the 763 back byte-identically; the eight
// `coll-of-options` rows' `Option[Coll[Int]]` type `24 10` is written in its short form `34`), but for the 26
// `String-surrogate` rows, which keep the vendored `ED A0 80`: the reader decodes those three bytes to one U+FFFD and the
// writer writes EF BF BD, so the bytes as sent are what the row is about. The 11 `C` rows past 65,535 bytes and the
// four `E` rows are wrapped in `.size` (see `Row.wrapped`): sent bare, a port that writes them anyway hands back the
// bytes, and its runner's value encoding of 32 MiB, item by item, took upstream sigma-rust's runner to 43 GB.

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.{SByte, SCollection, SizeOf, Value}
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalSerializeCostedWriter {
  val Source = "santa:authored-serialize-costed-writer"
  val Stamp = "jvm:sigma-state-6.0.7"
  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-serialize-costed-writer", "rows.tsv")
  /** A nested value larger than this, written or printed in full, is held: a bounded file of its own (the header). */
  val HeldBytes: Long = 64L << 20
  val DefaultJitLimit: Long = 10000000L

  /** One vendored row: sigma-rust's part, row set and name, the versions, the evaluation's cost limit (jit), the
    * expression's bytes, the jars' measurement (outcome, value text with a collection's first 100 items and then
    * `#<count>`, cost), what fork cc29855f gave, and sigma-rust's reading of what a tier like ours can grade of it. */
  case class Row(part: String, set: String, name: String, treeVersion: Int, activated: Int, limit: Long, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String, forkBase: String, gradeable: String) {
    val f: Array[String] = name.split('|')
    def family: String = f(0)
    def nestedFamily: Boolean = Set("G", "G3", "E", "E3")(family)
    /** The bytes of the nested value written in full: 32,768 bytes times every level's count. */
    def nestedBytes: Option[Long] = if (nestedFamily) Some(f(2).split('x').map(_.toLong).product) else None
    def held: Boolean = nestedBytes.exists(_ > HeldBytes)
    def authored: Boolean = set != "limit"
    def keepsVendored: Boolean = name.contains("|String-surrogate|")
    /** A row the JVM refuses inside `Global.serialize` whose bytes a port that writes them anyway would hand back in
      * full: the nested value of another class (32 MiB at 32,768 x 1,024) and every 65,536-byte write. The vector takes
      * their `.size`, which the JVM never reaches; such a port answers an Int, not the bytes item by item. */
    def wrapped: Boolean = family == "E" || family == "E3" || (family == "C" && f(2) != "65535")
    private def rewrite(wrap: Boolean): String = VersionContext.withVersions(activated.toByte, treeVersion.toByte) {
      val v = ValueSerializer.deserialize(SigmaSerializer.startReader(Base16.decode(body).get))
      val w = SigmaSerializer.startWriter()
      ValueSerializer.serialize(if (wrap) SizeOf(v.asInstanceOf[Value[SCollection[SByte.type]]]) else v, w)
      hx(w.toBytes)
    }
    /** The JVM's writing of the vendored expression. */
    lazy val written: String = rewrite(wrap = false)
    def expr: String = if (keepsVendored) body else rewrite(wrapped)
    def treeHex: String = tree(expr)
    /** The vendored expression as sent, bare: what the jars measured. */
    def bareTreeHex: String = tree(body)
    private def tree(e: String): String = { require(treeVersion == 3, name); "0b" + vlq(e.length / 2) + e }
    def op: String = family match {
      case "A"                     => "Global.serialize_runtime_class"
      case "B"                     => "Global.serialize_no_writer_arm"
      case "C"                     => "Global.serialize_count_range_at_depth"
      case "X"                     => "Global.serialize_tuple_arm"
      case "G" | "G3" | "E" | "E3" => "Global.serialize_shared_nested"
    }
    /** The oracle's outcome on the vector's tree: Right((value, cost)) or Left(the throwable). */
    lazy val outcome: Either[Throwable, (Json, Long)] =
      EvalCore.evalAppliedOutcome(treeHex, IntInput, activated.toByte)._2
    /** The oracle's outcome on the vendored expression, bare (differs from `outcome`'s tree only where `wrapped`). */
    lazy val bareOutcome: Either[Throwable, (Json, Long)] =
      if (!wrapped) outcome else EvalCore.evalAppliedOutcome(bareTreeHex, IntInput, activated.toByte)._2
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_-]", "_")
  }

  def rows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).map(c =>
      Row(c(0), c(1), c(2), c(3).toInt, c(4).toInt, c(5).toLong, c(6), c(7), c(8), c(9), c(10), c(11)))
  }

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq
  def costLimit(o: Either[Throwable, (Json, Long)]): Boolean =
    o.left.toOption.exists(t => chain(t).exists(_.getClass.getSimpleName == "CostLimitException"))
  def resultTooLong(o: Either[Throwable, (Json, Long)]): Boolean =
    o.left.toOption.exists(t => chain(t).exists(c => Option(c.getMessage).exists(_.contains("greater than 100000"))))
  /** The innermost cause: the class and message the refusal is about (outside it, InvocationTargetException). */
  def refusal(t: Throwable): Throwable = chain(t).last

  /** sigma-rust's text form of a value; a collection of more than 100 items as its first 100, then `#<count>`. */
  def jarsText(j: Json): String = {
    val c = j.hcursor
    c.downField("items").as[List[Json]].toOption match {
      case Some(items) if items.size > 100 =>
        items.take(100).map(AuthoredEvalPreActivation.text).mkString("Coll(", ",", ")") + s"#${items.size}"
      case _ => AuthoredEvalPreActivation.text(j)
    }
  }

  /** An identity hash in a message (`CSigmaDslBuilder$@4f4f23b6`) differs run to run: written `@<hash>`, as
    * TxEngine.reasonOf writes it, so a re-bless is byte-identical. */
  private val IdentityHash = """(?<=[\w$;\[])@[0-9a-f]{1,8}(?!\w)""".r
  private def clip(m: String, n: Int): String =
    if (m.length <= n) m else m.take(n).reverse.dropWhile(_ != ' ').reverse.trim + "…"
  private def said(r: Row): String = r.outcome.fold(
    t => {
      val c = refusal(t)
      s"REFUSED (${c.getClass.getSimpleName}${Option(c.getMessage).filter(_.nonEmpty)
        .map(m => s": ${clip(IdentityHash.replaceAllIn(m, "@<hash>"), 140)}").getOrElse("")})"
    },
    { case (v, cost) => val s = jarsText(v); s"${if (s.length > 90) s.take(90) + "…" else s} at cost $cost" })

  /** The static type of each leaf, and the value of another class its `bad` row hands the writer (gen27.py's LEAF). */
  val Leaf: Map[String, (String, String)] = Map(
    "Unit" -> ("Unit", "an Int"), "Boolean" -> ("Boolean", "an Int"), "Byte" -> ("Byte", "an Int"),
    "Short" -> ("Short", "an Int"), "Int" -> ("Int", "a Long"), "Long" -> ("Long", "an Int"),
    "BigInt" -> ("BigInt", "an Int"), "GroupElement" -> ("GroupElement", "an Int"),
    "SigmaProp" -> ("SigmaProp", "an Int"), "UnsignedBigInt" -> ("UnsignedBigInt", "an Int"),
    "String" -> ("String \"ABC\"", "an Int"), "String-empty" -> ("String \"\"", "an Int"),
    "String-surrogate" -> ("String of the bytes ED A0 80 (read as one U+FFFD, written as EF BF BD)", "an Int"),
    "AvlTree" -> ("AvlTree", "an Int"), "Box" -> ("Box", "an Int"),
    "CollByte" -> ("Coll[Byte]", "a Coll[Int]"), "CollBoolean" -> ("Coll[Boolean]", "a Coll[Int]"),
    "CollInt" -> ("Coll[Int]", "a Coll[Long]"), "CollCollByte" -> ("Coll[Coll[Byte]]", "a Coll[Int]"),
    "CollCollByte-inner" -> ("Coll[Coll[Byte]]", "a Coll[Coll[Int]]"),
    "PairIntInt" -> ("(Int, Int)", "an Int"), "PairIntInt-inner" -> ("(Int, Int)", "an (Int, Long)"),
    "OptionInt" -> ("Option[Int]", "an Int"), "OptionInt-inner" -> ("Option[Int]", "an Option[Long]"),
    "OptionInt-none" -> ("Option[Int] (a None)", "a None of Option[Long]"))

  /** Where a carrier puts its leaf (gen27.py's CARRIERS): a constant holding it. */
  val Carrier: Map[String, String => String] = Map(
    "top" -> (t => s"a $t constant"),
    "pair-first" -> (t => s"a ($t, Int) constant"),
    "pair-second" -> (t => s"an (Int, $t) constant"),
    "coll-of-triples-0" -> (t => s"a constant collection of two ($t, Int, Long)"),
    "coll-of-triples-1" -> (t => s"a constant collection of two (Int, $t, Long)"),
    "coll-of-triples-2" -> (t => s"a constant collection of two (Int, Long, $t)"),
    "coll-of-quads-after-bytes" -> (t => s"a constant collection of two (Coll[Byte] of 50, Int, Long, $t)"),
    "option" -> (t => s"a Some($t) constant"),
    "coll-of-3" -> (t => s"a constant Coll[$t] of three"),
    "pair-in-pair" -> (t => s"an (Int, (Long, $t)) constant"),
    "option-of-triple" -> (t => s"a Some((Int, Long, $t)) constant"),
    "coll-of-pairs-3" -> (t => s"a constant collection of three (Int, $t)"),
    "coll-of-triple-in-triple" -> (t => s"a constant collection of two (Int, (Long, Int, $t), Long)"))

  private val NoArm: Map[String, String] = Map(
    "Global" -> "the Global object", "lambda" -> "the function `(x: Int) => x`",
    "Any" -> "`(1, 2, 3)(0)`, typed Any", "Unit-coll" -> "`Coll[Byte](1, 1).map(x => ())`, a Coll[Unit]",
    "Unit" -> "Unit")
  private def noArmCarrier(c: String, v: String): String = c match {
    case "top"           => v
    case "pair-second"   => s"the Tuple node (1, $v)"
    case "pair-first"    => s"the Tuple node ($v, 1)"
    case "after-bytes50" => s"the Tuple node (Coll[Byte] of 50, $v)"
    case "pair-in-pair"  => s"the Tuple node (1, (1L, $v))"
  }
  private val CountCarrier: Map[String, String] = Map(
    "top" -> "c", "pair-second" -> "the Tuple node (1, c)", "pair-first" -> "the Tuple node (c, 1)",
    "some" -> "Some(c)", "coll-item0" -> "Coll(c, b1)", "coll-item1" -> "Coll(b1, c)",
    "coll-item1-of-big" -> "Coll(b5, c)")
  private val CountOrder: Map[String, String] = Map(
    "count-then-cast" -> "the Tuple node (c, x): c of 65,536 bytes, then x a Coll[Long] typed Coll[Int]",
    "cast-then-count" -> "the Tuple node (x, c): x a Coll[Long] typed Coll[Int], then c of 65,536 bytes",
    "noarm-then-count" -> "the Tuple node (Global, c): no writer arm, then c of 65,536 bytes",
    "count-then-noarm" -> "the Tuple node (c, Global): c of 65,536 bytes, then no writer arm")
  private val TupleArm: Map[String, String] = Map(
    "pairs|ok" -> "a constant collection of two (Int, Long)",
    "triples|ok" -> "a constant collection of two (Int, Long, Int)",
    "quads|ok" -> "a constant collection of two (Int, Long, Int, Long)",
    "pairs-under-triples" -> "`if (false) <two (Int, Long, Int)> else <two (Int, Long)>`: a pair under a triple's type",
    "triples-under-pairs" -> "`if (false) <two (Int, Long)> else <two (Int, Long, Int)>`: a triple under a pair's type",
    "quads-under-triples" -> "`if (false) <two (Int, Long, Int)> else <two (Int, Long, Int, Long)>`",
    "triples-under-quads" -> "`if (false) <two (Int, Long, Int, Long)> else <two (Int, Long, Int)>`",
    "colls-under-triples" -> "`if (false) <two (Int, Long, Int)> else <a Coll[Coll[Int]] of two>`: an item that is a collection of Int",
    "colls-under-pairs" -> "`if (false) <two (Int, Long)> else <a Coll[Coll[Int]] of two>`: an item that is a collection of Int",
    "ints-under-triples" -> "`if (false) <two (Int, Long, Int)> else Coll[Int](1, 2)`: an item that is an Int",
    "triples-item-under-coll-of-coll-int" ->
      "`if (false) <a Coll[Coll[Int]] of two> else <two (Int, Int, Int)>`: a triple, a collection of Any, under Coll[Int]",
    "triples-item-under-coll-of-coll-long" ->
      "`if (false) <a Coll[Coll[Long]] of two> else <two (Int, Int, Int)>`: a triple's Int items under Coll[Long]")

  /** What the row does. */
  def what(r: Row): String = {
    val f = r.f
    r.family match {
      case "A" =>
        val (carrier, item, tag) = (f(1), f(2), f(3))
        val shape: String = carrier match {
          case c if Carrier.contains(c) =>
            val (t, other) = Leaf(item)
            if (tag == "ok") s"`Global.serialize` of ${Carrier(c)(t)}"
            else s"`Global.serialize(if (false) <${Carrier(c)(t)}> else <the same holding $other>)`"
          case "coll-of-quad-of-triples" =>
            val k = item.stripPrefix("item")
            if (tag == "ok") "`Global.serialize` of a constant collection of one quadruple of (Int, Long, Int)"
            else s"`Global.serialize(if (false) <one quadruple of (Int, Long, Int)> else <the same with item $k an " +
              "(Int, Long, Long)>)`"
          case "coll-of-colls" =>
            val k = item.stripPrefix("item")
            if (tag == "ok") "`Global.serialize` of the Coll[Coll[Int]] node of four Coll[Int](1, 2)"
            else s"`Global.serialize` of the Coll[Coll[Int]] node of four, item $k `if (false) Coll[Int](1, 2) else " +
              "Coll[Long](1, 2)`"
          case "coll-of-options" =>
            val k = item.stripPrefix("item")
            if (tag == "ok") "`Global.serialize` of the Coll[Option[Coll[Int]]] node of four Some(Coll[Int](1, 2))"
            else s"`Global.serialize` of the Coll[Option[Coll[Int]]] node of four Somes, item $k's holding `if (false) " +
              "Coll[Int](1, 2) else Coll[Long](1, 2)`"
          case after if after.startsWith("after-bytes") =>
            val n = after.stripPrefix("after-bytes")
            val (t, other) = Leaf(item)
            if (tag == "ok") s"`Global.serialize` of the Tuple node (Coll[Byte] of $n, a $t constant)"
            else s"`Global.serialize` of the Tuple node (Coll[Byte] of $n, `if (false) <a $t> else <$other>`): a " +
              "Tuple node checks a collection by its kind only"
        }
        shape
      case "B" => s"`Global.serialize` of ${noArmCarrier(f(1), NoArm(f(2)))}"
      case "C" =>
        val block = "b1 a Coll[Byte] of 1,024 doubled by `++` to b6 of 32,768"
        if (f(1) == "order") s"`Global.serialize` of ${CountOrder(f(2))} ($block)"
        else if (f(2) == "65535") s"`Global.serialize(${CountCarrier(f(1))}).size`, c = b6 ++ b5 ++ … ++ b1 ++ a " +
          s"constant of 1,023: 65,535 bytes ($block)"
        else s"`Global.serialize(${CountCarrier(f(1))})`, c = b6 ++ b6: 65,536 bytes ($block)"
      case "X" => s"`Global.serialize` of ${TupleArm(f.drop(2).mkString("|"))}"
      case "G" | "E" | "G3" | "E3" =>
        val d = f(2).split('x')
        val v =
          if (d.length == 2) s"v = Coll(b) doubled by `++` to ${d(1)} items, every one the same b (b: 32,768 bytes)"
          else s"v = Coll(w) doubled by `++` to ${d(2)} items, every one the same w, itself Coll(b) doubled to ${d(1)} " +
            "items, every one the same b (b: 32,768 bytes)"
        f(1) match {
          case "size-serialize"            => s"`Global.serialize(v).size`, $v"
          case "size-only"                 => s"`v.size`, $v"
          case "serialize-long-is-nested"  => s"`Global.serialize(if (false) 1L else v)`, $v: typed Long"
          case "serialize-bytes-is-nested" => s"`Global.serialize(if (false) Coll[Byte](1) else v)`, $v: typed Coll[Byte]"
        }
    }
  }

  /** What the row's outcome rests on; fails loud where the oracle's outcome is not what the label says. */
  def label(r: Row): String = {
    val o = r.outcome
    def ensure(ok: Boolean): Unit = if (!ok) sys.error(s"${r.name}: ${said(r)} is not what its label says")
    r.family match {
      case "A" if r.f(3) == "ok" => ensure(o.isRight)
        "CONTROL: the valid twin; its cost is every put the writer charges."
      case "A" if o.isRight =>
        ensure(Set("Unit", "OptionInt-none")(r.f(2)))
        "STATIC TYPE: of another class, but this arm casts nothing (Unit writes nothing; a None writes its flag " +
          "alone), so the JVM answers; a port that writes the runtime value answers other bytes here."
      case "A" =>
        "ANOTHER CLASS: the writer writes the value by its static type and refuses where it reaches the other class, " +
          "after what it wrote before; a port that writes the runtime value by its own class, or converts the value " +
          "before writing, answers here."
      case "B" if r.f(2) == "Unit" => ensure(o.isRight); "CONTROL: Unit writes nothing."
      case "B" => ensure(o.isLeft)
        if (r.f(2) == "Unit-coll") "ZERO-WIDTH ITEMS: a Coll[Unit] has no data writer; refused before its length."
        else "NO WRITER ARM: the type has no data writer; refused where the writer reaches it."
      case "C" if r.f(1) == "order" => ensure(o.isLeft)
        "TWO FAILURES: refused at whichever the writer meets first; this tier grades the refusal, not which one."
      case "C" if r.f(2) == "65535" => ensure(o.isRight)
        "CONTROL: 65,535 items fit the count (`putUShort`)."
      case "C" => ensure(o.isLeft)
        "COUNT REFUSED: 65,536 items are past `putUShort`'s 65,535; a port that narrows the count, or writes it modulo " +
          "its width, answers here."
      case "X" => (r.f(1), r.f.drop(2).mkString("|")) match {
        case (_, k) if k.endsWith("|ok") => ensure(o.isRight); "CONTROL: a valid write of the tuple arm."
        case ("coll", "triples-item-under-coll-of-coll-int") => ensure(o.isRight)
          "COLLECTION ARM: a tuple (a collection of Any) under a collection type is written item by item by the " +
            "collection arm, its Int items as the Ints they are: a value."
        case ("coll", _) => ensure(o.isLeft)
          "COLLECTION ARM, ANOTHER CLASS: the collection arm writes the tuple's items by the static type Long; refused."
        case (_, k) if Set("pairs", "triples", "quads")(k.takeWhile(_ != '-')) =>
          ensure(o.isLeft && refusal(o.left.get).isInstanceOf[AssertionError])
          "TUPLE LENGTH: a tuple of another length than its type; the writer's assertion refuses it (an " +
            "AssertionError, which fails the script)."
        case _ => ensure(o.isLeft)
          "NOT A TUPLE: an item that is neither a pair nor a collection of Any; refused (`fromDslTuple`)."
      }
      case "G" | "E" | "G3" | "E3" => r.f(1) match {
        case "size-only" => ensure(o.isRight); "CONTROL: the collection exists, and nothing writes it."
        case "size-serialize" if o.isRight =>
          "CONTROL: written within the cost limit, and the result fits 100,000 bytes."
        case "size-serialize" if resultTooLong(o) =>
          "RESULT TOO LONG: written within the cost limit, but the result is past the 100,000 items of a collection " +
            "built at evaluation; refused for its length."
        case "size-serialize" => ensure(costLimit(o))
          s"COST LIMIT: the write crosses the cost limit at the put that crosses it; a port that writes it all first " +
            s"holds ${r.nestedBytes.get >> 20} MiB before it refuses, and one with no cost limit answers its size."
        case _ => ensure(o.isLeft)
          "ANOTHER CLASS, NESTED: refused at once; a port whose refusal prints the value, or that converts it first, " +
            "does the nested collection's work before it refuses."
      }
    }
  }

  def script(r: Row): String = {
    val wrap = if (r.wrapped) " (as `.size`: the JVM refuses inside `Global.serialize`, and a port that writes it " +
      "anyway answers an Int, not the bytes)" else ""
    s"[${r.name}] ${what(r)}$wrap — ${said(r)}. ${label(r)}  // tree v${r.treeVersion}, activated ${r.activated}"
  }

  /** A held row's own file (a bounded file: one entry, `limits`). */
  def boundedKey(r: Row): String = SpecExtract.slug(s"${r.op}_bounded_${r.slug}")

  private def entry(r: Row, i: Int): Json = {
    val name = s"${r.slug}-a${r.activated}#$i"
    val a = r.activated.toByte
    if (r.outcome.isRight)
      SpecExtract.authoredEntryV(r.op, script(r), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
    else SpecExtract.authoredRejectEntryV(r.op, script(r), r.treeHex, name, IntInput, a, ergoTree = r.treeVersion)
  }
  private def envelope(op: String, entries: Seq[Json]): Json =
    SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))

  /** The five op files (every authored row but the held ones), and one bounded file per held row. */
  def extract(): Map[String, Json] = {
    val rs = rows().filter(_.authored)
    val files = rs.filterNot(_.held).groupBy(_.op).map { case (op, g) =>
      op -> envelope(op, g.zipWithIndex.map { case (r, i) => entry(r, i) })
    }
    files ++ rs.filter(_.held).map(r => boundedKey(r) -> SpecExtract.bounded(envelope(r.op, Seq(entry(r, 0)))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalSerializeCostedWriter", extract(), outDir)
}
