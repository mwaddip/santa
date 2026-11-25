package santa

// Authored eval vectors that READ SELF's form — the corpus's pin of runner-contract §2's SELF-tree note (the user,
// 2026-11-25/26): a size-flagged tree whose root is not a SigmaProp is held by SELF as a node holds it, as its BYTES;
// a tree a node cannot read keeps the parse.
//
// The rows are sigma-rust's (its SELF-tree trial, 2026-11-26: `santa-self-node-form-surface-rows.md`, the 2026-11-20
// reviewer's rows), copied as data into `src/test/resources/self-node-form-rows/`. Every expectation is the oracle's,
// through rudolph's own per-envelope dispatch (`Runner.actuals`).
//
//   - The five envelopes (v1..v5), the same 11 reads of SELF on two trees: `u-*`, `if (SizeOf((1)) == 1) r else r`
//     with a constant of type `(Int)` the writer refuses, so only the node's form gives SELF an id and bytes (the old
//     oracle threw on them); `w-*`, `if (1 == 1) r else r`, its writable twin.
//   - The span (v2): the size field exact, one byte long, 37 long, one short; bytes after the tree.
//
// LEFT OUT, as trees no box can carry (the class retired on 2026-11-25): the `u-sp-*` rows (a SigmaProp root that
// cannot be written) and the three unsized rows whose parse cannot be written. The trial's one row that put a v4
// field (`selfRegisters`) in a v2 file goes to the v4 file, where the field means something.

import io.circe.Json
import io.circe.parser.parse

object AuthoredEvalSelfNodeForm {
  val Source = "santa:authored-self-node-form"
  /** `(op, resource file)`, one per envelope. */
  val Envelopes: Seq[(String, String)] = (1 to 5).map(n => s"Self.node_form_tree_v$n" -> s"b-v$n")
  val SpanOp = "Self.node_form_tree_span"

  private val readOf = Map(
    "value" -> "`SELF.value`", "id" -> "`SELF.id`", "bytes" -> "`SELF.bytes`", "propbytes" -> "`SELF.propositionBytes`",
    "bwnr" -> "`SELF.bytesWithoutRef`", "in0_eq_self" -> "`INPUTS(0) == SELF`", "selfidx" -> "`CONTEXT.selfBoxIndex`",
    "in0_id" -> "`INPUTS(0).id`", "r4def" -> "`SELF.R4[Int].isDefined`", "in0_r4def" -> "`INPUTS(0).R4[Int].isDefined`",
    "creation" -> "`SELF.creationInfo`")

  private val spanProse = Map(
    "size-true" -> "`SizeOf((1))`, its size field exact: 1.",
    "size-lies-long" -> ("`SizeOf((1))` with the size field one byte LONGER than the tree: a node cannot read the box, " +
      "so there is no node form and SELF holds the parse (one rule, the user 2026-11-26); the entry answers 1."),
    "size-lies-long-by-37" -> "As `size-lies-long`, the size field 37 bytes long: the parse, and 1.",
    "size-lies-short" -> ("`SizeOf((1))` with the size field one byte SHORTER than the tree: the tree evaluated is the " +
      "parse, 1; SELF holds the bytes the size field spans."),
    "size-lies-short-bytes" -> "`SELF.bytes` on the `u` tree with its size field one byte short: SELF's tree is the span.",
    "trailing-00" -> "`SizeOf((1))` with a zero byte after the tree: 1; SELF holds the size field's span, not the byte.",
    "trailing-05-bytes" -> "`SELF.bytes` on the `u` tree with a byte (05) after it: SELF's box holds the span.",
    "trailing-05-creation" -> "`SELF.creationInfo` on the `u` tree with a byte (05) after it.",
    "trailing-05-propbytes" -> "`SELF.propositionBytes` on the `u` tree with a byte (05) after it: the span, without the 05.",
    "trailing-w-propbytes" -> "`SELF.propositionBytes` on the writable `w` tree with a byte (05) after it: the span.",
    "trailing-w-creation" -> "`SELF.creationInfo` on the writable `w` tree with a byte (05) after it.",
    "trailing-05-r4def" -> ("`SELF.R4[Int].isDefined` on the `u` tree with a byte (05) after it, SELF's R4 given: the " +
      "register is SELF's own, whatever form its tree takes."),
    "size-lies-long-writable" -> ("`1 + 1`, a writable body, with the size field one byte LONGER than the tree: a node " +
      "cannot read the box, so there is no node form and SELF holds the parse (one rule, the user 2026-11-26), which can " +
      "be written; the entry answers 2."),
    "size-lies-long-by-37-writable" -> "As `size-lies-long-writable`, the size field 37 bytes long: the parse, and 2.")

  /** Appended 2026-11-26 (sigma-rust's grade at `843fe48`): the lying size field over a body the writer accepts, so a
    * runner with a real box can answer. `9a04020402` is `1 + 1`, 5 bytes; `06` is one byte long, `2a` 37 long. */
  private val writableLying: Seq[Json] = Seq("size-lies-long-writable" -> "06", "size-lies-long-by-37-writable" -> "2a").map {
    case (name, size) => Json.obj(
      "name"           -> Json.fromString(name),
      "tree_bytes_hex" -> Json.fromString(s"0b${size}9a04020402"),
      "version"        -> Json.obj("activated" -> Json.fromInt(3), "ergoTree" -> Json.fromInt(3)),
      "input"          -> Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0)))
  }

  /** Retired 2026-11-26 (sigma-rust's grade at `843fe48`): their body is `SizeOf((1))`, so their SELF is a parse (a node
    * cannot read a lying size field) that cannot be written — a tree no box can carry, the class left out above. The
    * writable rows above keep their fact. Dropped AFTER naming, so every other entry keeps its committed name. */
  val SpanRetired: Set[String] = Set("size-lies-long-accept#1", "size-lies-long-by-37-accept#3")

  private def resource(f: String): Json = {
    val in = getClass.getResourceAsStream(s"/self-node-form-rows/$f.json")
    require(in != null, s"resource self-node-form-rows/$f.json")
    parse(scala.io.Source.fromInputStream(in, "UTF-8").mkString).fold(e => sys.error(s"$f: $e"), identity)
  }
  private def rows(f: String): Vector[Json] = resource(f).hcursor.downField("entries").as[Vector[Json]].toOption.get
  private def base(e: Json): String = e.hcursor.get[String]("name").toOption.get
  /** The row without the trial's empty fields (`expected: null`, `input: null`, no prose). */
  private def clean(e: Json): Json = e.mapObject(_.filter { case (k, j) => k != "expected" && !j.isNull })

  private def prose(b: String): String = b match {
    case _ if b.startsWith("u-") =>
      val r = readOf(b.drop(2))
      s"[$b] `if (SizeOf((1)) == 1) $r else $r` — a size-flagged tree whose root is not a SigmaProp, holding a constant " +
      "of type `(Int)` that the writer refuses: SELF holds it as a node does, as its BYTES (runner-contract §2, the " +
      "SELF-tree note), so the read answers instead of throwing on an unwritable parse."
    case _ if b.startsWith("w-") =>
      val r = readOf(b.drop(2))
      s"[$b] `if (1 == 1) $r else $r` — the writable twin of `u-${b.drop(2)}`: the same read on a tree the writer accepts."
    case _ => s"[$b] ${spanProse(b)}"
  }

  /** Bless `kept` under `schema` through rudolph's dispatch, and give each row its name and prose. */
  private def bless(op: String, schema: String, kept: Seq[Json], retired: Set[String] = Set.empty): Json = {
    val doc = Json.obj("schema" -> Json.fromString(schema), "entries" -> Json.arr(kept.map(clean): _*))
    val actual = santa.runner.Runner.actuals(doc).toMap
    val entries = kept.zipWithIndex.map { case (e, i) =>
      val b = base(e)
      val exp = actual(b)
      require(!exp.hcursor.get[String]("error").toOption.exists(Set("not-implemented", "panicked")), s"$op $b: $exp")
      val verdict = if (exp.hcursor.get[String]("error").toOption.contains("errored")) "errored" else "accept"
      clean(e).mapObject(o => o.add("name", Json.fromString(s"$b-$verdict#$i")).add("script", Json.fromString(prose(b)))
        .add("expected", exp))
    }.filterNot(e => e.hcursor.get[String]("name").toOption.exists(retired))
    Json.obj(
      "schema"     -> Json.fromString(schema),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.7"),
      "source"     -> Json.fromString(Source),
      "entries"    -> Json.arr(entries: _*))
  }

  def extract(): Map[String, Json] = {
    val tb = rows("b-treebytes")
    val v4Extra = tb.filter(base(_) == "trailing-05-v4-r4def")
      .map(_.mapObject(_.add("name", Json.fromString("trailing-05-r4def"))))
    val envelopes = Envelopes.map { case (op, f) =>
      val kept = rows(f).filter(e => (base(e).startsWith("u-") || base(e).startsWith("w-")) && !base(e).startsWith("u-sp-"))
      op -> bless(op, s"santa-eval/v${f.last}", if (f == "b-v4") kept ++ v4Extra else kept)
    }
    val span = tb.filter(e => (base(e).startsWith("size-") || base(e).startsWith("trailing-")) &&
      base(e) != "trailing-05-v4-r4def")
    (envelopes :+ (SpanOp -> bless(SpanOp, "santa-eval/v2", span ++ writableLying, SpanRetired))).toMap
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalSelfNodeForm", extract(), outDir)
}
