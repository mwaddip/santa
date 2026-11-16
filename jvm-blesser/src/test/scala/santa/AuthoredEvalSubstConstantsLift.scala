package santa

// Authored eval vectors for sigma-rust's santa-probe-substconstants-lift (2026-11-16): `substConstants` lifts every ITEM
// of its new values into a constant by the item's runtime class and the version of the tree being EVALUATED, before
// the template is read. Measured first in santa.spike.SubstConstantsLiftProbe (local); the reply is
// prompts/drafts/sigma-rust-substconstants-lift-reply.md. Transaction companion: AuthoredTxSubstConstantsLift.
//
// THE RULE, read in 6.0.7 and measured: CSigmaDslBuilder.substConstants (:206-217) calls `newValues.toArrayOfConstants`
// (eval/Extensions.scala:53), which lifts each item with Platform.liftToConstant (data/jvm/.../Platform.scala:16-63);
// a class with no case, or a case closed at the evaluated tree's version, throws `Cannot liftToConstant`, whatever the
// item's position. A collection item is lifted whole by its item type (`case coll: Coll[a]`). Only then is the template
// read (ErgoTreeSerializer.substituteConstants), under the CURRENT version context: its constants are read at the
// evaluated tree's version, a new value written over a constant must have its type, and the size field is written back
// only from tree version 3 (:369-374).
//
// THE ROWS: sigma-rust's 1,308 eval rows (src/test/resources/sigma-rust-substconstants-lift/rows.tsv, with the jars'
// outcome, value and cost as an independent cross-check), a bare root `substConstants(template, positions, newValues)`
// each, at tree versions 0 to 3 (`00` + body, or the sized header + body). A row is `<where>|<kind>|<position>|v<tree
// version>`. Tree v0..v2 rows go to the v5 files at activated 2 and to the `_v6` files at activated 3, tree-v3 rows to
// the `_v6` files; the `template|…` rows (the template read at the evaluated version) are their own family.
//
// THE CONTEXT (santa-eval/v6-fullctx, the rows read CONTEXT.headers, CONTEXT.preHeader and SELF): one header, the newest
// of the storage-rent window the transaction tier spends under (block version 4 for activated 3; the window re-linked
// at block version 3 for activated 2, AuthoredTxTreeVersion.blockCtx), a pre-header at 1,051,200 whose parent is that
// header and whose version is activated + 1, a P2PK SELF without registers, no context variable, no outputs. Nothing a
// node's transaction read refuses (rule 1019). A value embeds the context only where a header or SELF is written.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.{ErgoTree, UnparsedErgoTree}
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}

object AuthoredEvalSubstConstantsLift {
  val Source = "santa:authored-substconstants-lift"
  val Stamp = "jvm:sigma-state-6.0.7"
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-substconstants-lift", "rows.tsv")

  // ── the context ──────────────────────────────────────────────────────────────────────────────────
  /** CONTEXT.headers(0) per activated version: the newest header of the transaction tier's window at block version
    * activated + 1 (AuthoredTxSubstConstantsLiftTest checks both against AuthoredTxTreeVersion.blockCtx). */
  val HeaderHex: Map[Int, String] = Map(
    2 -> ("03aebae7765c8d1c0349b735d31c1e66fcd2442e632049c461c55fb196c21d2b7e4d41efc816f855d9d6e3cab68be6453b1d7c9a5a81" +
      "5016bcecd68f05509dc194a36723709ba917c6d25a3d9affd01f6caabbee498db075fc83e3ab4ccc4137554594ba1b326d52cb4016a5920da9" +
      "beffc00aa7048e145ac6cbac616c18de7d7e00c0fda7df97317fee7f826c9c972c1988ec589ae8a994a1c5dc3dbf2d5d473a586cf0ec631c3f" +
      "07000000bf9440000000000279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817989225fc410e1f522e"),
    3 -> ("04c1caac521fe75f5af2aeda4f5acd787293f23209b465bed62d1851d2d4994a784d41efc816f855d9d6e3cab68be6453b1d7c9a5a81" +
      "5016bcecd68f05509dc194a36723709ba917c6d25a3d9affd01f6caabbee498db075fc83e3ab4ccc4137554594ba1b326d52cb4016a5920da9" +
      "beffc00aa7048e145ac6cbac616c18de7d7e00c0fda7df97317fee7f826c9c972c1988ec589ae8a994a1c5dc3dbf2d5d473a586cf0ec631c3f" +
      "07000000bf9440000000000279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817989225fc410e1f522e"))
  /** The pre-header's height (HEIGHT), timestamp and nBits: the storage-rent context's. */
  val Height = 1051200
  val Timestamp = 1690001200000L
  val NBits = 117440512L
  private val Generator: Array[Byte] = GroupElementSerializer.toBytes(CryptoConstants.dlogGroup.generator)
  def headerId(activated: Int): Array[Byte] = Blake2b256(b(HeaderHex(activated)))
  def preHeaderHex(activated: Int): String = PreHeaderCodec.encodeHex(PreHeaderCodec.Fields(
    version = (activated + 1).toByte, parentId = headerId(activated), timestamp = Timestamp, nBits = NBits,
    height = Height, minerPk = Generator, votes = Array[Byte](0, 0, 0)))
  /** SELF: a P2PK box (the generator's key) of 1 ERG created at height 1, without registers. */
  val SelfHex: String = VersionContext.withVersions(3, 3) {
    hx(RentFixtures.box("santa:substconstants-lift:self", 1000000000L, 1,
      ErgoTree.fromSigmaBoolean(ProveDlog(CryptoConstants.dlogGroup.generator))).bytes)
  }
  def context(activated: Int): Json = Json.obj(
    "self_index"       -> Json.fromInt(0),
    "inputs"           -> Json.arr(Json.fromString(SelfHex)),
    "data_inputs"      -> Json.arr(),
    "outputs"          -> Json.arr(),
    "headers"          -> Json.arr(Json.fromString(HeaderHex(activated))),
    "pre_header_hex"   -> Json.fromString(preHeaderHex(activated)),
    "height"           -> Json.fromInt(Height),
    "extension"        -> Json.obj(),
    "input_extensions" -> Json.arr(Json.obj()))

  // ── the rows ─────────────────────────────────────────────────────────────────────────────────────
  /** One vendored row at one activated version. `jars*` is sigma-rust's measurement on the 6.0.7 jars, under their own
    * context (one header and a pre-header of their own: a value that writes a header or SELF differs from ours). */
  case class Row(name: String, treeVersion: Int, activated: Int, body: String, jarsOutcome: String, jarsValue: String,
                 jarsCost: String, holdsHeader: Boolean) {
    private val f = name.split('|')
    def where: String = f(0)
    def kind: String = f(1)
    def position: String = if (f.length == 4) f(2) else ""
    def template: Boolean = where == "template"
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def op: String = "SubstConstants." + (if (template) "template_tree_version" else "lift_by_class") +
      (if (activated == 3) "_v6" else "")
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
    /** Where the value embeds this context: a header written from CONTEXT (the jars' column, less the template rows,
      * whose header is a constant of the template's own bytes) or a written SELF. */
    def embedsContext: Boolean = (holdsHeader && !template) || (kind == "self" && Set("own", "one-item")(position))
  }

  def rows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).filter(_(0) == "eval").flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Row(c(1), c(2).toInt, a, c(4), c(5), c(6), c(7), c(9) == "yes"))
    }
  }

  private def b(h: String): Array[Byte] = Base16.decode(h).get
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
      sigma.santa.LenientErgoTree.deserialize(b(treeHex))
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
      EvalCore.evalFullContextOutcome(r.treeHex, 0, Seq(SelfHex), Nil, Nil, Seq(HeaderHex(r.activated)),
        preHeaderHex(r.activated), Seq(Map.empty), None, r.activated.toByte)._2 match {
        case Right((v, cost)) => Answer(v, cost)
        case Left(t)          => ThrowsAtEval(t)
      }
  }

  /** A value as the jars' table writes it: the returned bytes as hex. */
  def shown(v: Json): String = {
    val c = v.hcursor
    c.downField("items").as[List[Json]].toOption
      .map(xs => hx(xs.map(_.hcursor.get[Int]("value").getOrElse(sys.error(s"not a byte: ${v.noSpaces}")).toByte).toArray))
      .getOrElse(sys.error(s"not a Coll[Byte]: ${v.noSpaces}"))
  }

  def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq
  /** A failure as the script states it: the innermost cause's class and message, identity hashes masked. */
  private def why(t: Throwable): String = {
    val c = chain(t).last
    val m = Option(c.getMessage).getOrElse("").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll("\\s+", " ").trim
    s"${c.getClass.getSimpleName}${if (m.isEmpty) "" else ": " + clip(m, 120)}"
  }
  private def clip(m: String, n: Int): String =
    if (m.length <= n) m else m.take(n).reverse.dropWhile(_ != ' ').reverse.trim + "…"
  def said(o: Outcome): String = o match {
    case Answer(v, cost)   =>
      val h = shown(v)
      s"the bytes ${if (h.length <= 64) h else h.take(64) + s"… (${h.length / 2} bytes)"} at cost $cost"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }

  // ── the scripts ──────────────────────────────────────────────────────────────────────────────────
  private val Where: Map[String, String] = Map(
    "item"                  -> "an item of the new values",
    "in-coll"               -> "inside a collection that is an item of the new values",
    "in-coll2"              -> "two collections deep inside an item of the new values",
    "tuple-const"           -> "the middle item K of a tuple constant `(1, K, 2)` given as the new values",
    "tuple-const-items"     -> "the items of a tuple constant given as the new values",
    "if-tuple-const-items"  -> "the items of a tuple constant behind an `if`, given as the new values",
    "own-empty"             -> "a collection written over an EMPTY collection constant of its own type",
    "new-values"            -> "the new values themselves",
    "no-items"              -> "no item at all (empty new values)",
    "twice"                 -> "two positions naming one constant",
    "template"              -> "the template")
  private val Kind: Map[String, String] = Map(
    "boolean" -> "a Boolean", "byte" -> "a Byte", "short" -> "a Short", "int" -> "an Int", "long" -> "a Long",
    "bigint" -> "a BigInt", "group" -> "a GroupElement", "sigmaprop" -> "a SigmaProp", "tree" -> "an AvlTree",
    "self" -> "SELF (a Box)", "unit" -> "a Unit", "string" -> "a String", "pair" -> "a pair",
    "tuple3" -> "a three-item tuple", "option" -> "an absent option (`getVar[Int](9)`)",
    "hdr" -> "a Header (`CONTEXT.headers(0)`)", "headers" -> "`CONTEXT.headers`", "ph" -> "`CONTEXT.preHeader`",
    "ubi" -> "an UnsignedBigInt (the constant `090107`)", "context" -> "`CONTEXT`", "global" -> "`Global`",
    "pair-int-ph" -> "a pair (Int, PreHeader)", "pair-int-hdr" -> "a pair (Int, Header)",
    "option-ph" -> "an absent `getVar[PreHeader](9)`", "some-ph" -> "`Global.some(CONTEXT.preHeader)`",
    "lambda" -> "a function (built by `map`)", "coll-int" -> "a Coll[Int]",
    "coll-string-const" -> "a Coll[String] constant", "pair-const" -> "a pair constant",
    "pair-node-ph" -> "a pair node holding the pre-header",
    "hdr-const" -> "a Header constant", "option-const" -> "an option constant",
    "ubi-consts" -> "UnsignedBigInt constants", "ubi-const" -> "an UnsignedBigInt constant",
    "sized-v3-longs" -> "Long constants under a sized v3 header", "sized-v1-int" -> "an Int under a sized v1 header",
    "sized-v2-int" -> "an Int under a sized v2 header", "unsized-v0-int" -> "an Int under an unsized v0 header",
    "tree-then-tree" -> "two AvlTree items")
  private val Position: Map[String, String] = Map(
    "pos7"           -> "at position 7 of a one-constant template (not written)",
    "pos789"         -> "at positions 7, 8, 9 of a one-constant template (not written)",
    "nocon"          -> "at position 0 of a template without constants (not written)",
    "other"          -> "over an AvlTree constant (another type)",
    "own"            -> "over a constant of its own type (written)",
    "one-item"       -> "holding one item",
    "no-item"        -> "holding no item",
    "template-alone" -> "(the template alone, nothing substituted)",
    "tree-template"  -> "over an AvlTree template",
    "int-written"    -> "an Int written over its Int constant",
    "nothing"        -> "nothing substituted")
  private def text(m: Map[String, String], k: String): String = m.getOrElse(k, s"`$k`")

  def label(r: Row, o: Outcome): String = {
    val msgs = o match { case ThrowsAtEval(t) => chain(t).map(x => Option(x.getMessage).getOrElse("")); case _ => Nil }
    o match {
      case _ if r.template => o match {
        case Answer(_, _) => "TEMPLATE: read and written back under the evaluated tree's version (a size field only " +
          "from version 3)."
        case _            => "TEMPLATE: its constants are read at the evaluated tree's version, where this one has no reading."
      }
      case RefusedAtParse(_) => "PARSE: the tree names a type or method its version does not have."
      case Answer(_, _) if r.embedsContext => "WRITTEN: the value embeds this context's bytes."
      case Answer(_, _) => "LIFTED: every item has a case at this tree version."
      case ThrowsAtEval(_) if msgs.exists(_.contains("Cannot liftToConstant")) =>
        "LIFT REFUSES: no case for this class at this tree version; the lift runs before the template is read, " +
          "whatever the position."
      case ThrowsAtEval(_) if msgs.exists(_.contains("expected new constant to have the same")) =>
        "TYPE CHECK: the lift passed; a written value must have the type of the constant it replaces."
      case ThrowsAtEval(_) => "THROWS: see the reason."
    }
  }

  def script(r: Row, o: Outcome): String = {
    val what = if (r.template) s"a template holding ${text(Kind, r.kind)}, ${text(Position, r.position)}"
               else s"${text(Kind, r.kind)} as ${text(Where, r.where)}" +
                 (if (r.position.isEmpty) "" else s", ${text(Position, r.position)}")
    s"[${r.name}] `substConstants(template, positions, newValues)` with $what — ${said(o)}. ${label(r, o)}" +
      s"  // tree v${r.treeVersion}, activated ${r.activated}"
  }

  // ── the vectors ──────────────────────────────────────────────────────────────────────────────────
  private def entry(r: Row, i: Int): Json = {
    val o = outcome(r)
    val expected = o match {
      case Answer(v, cost) => Json.obj("value" -> v, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
      case _               => Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
    }
    Json.obj(
      "name"           -> Json.fromString(s"${r.slug}-a${r.activated}#$i"),
      "script"         -> Json.fromString(script(r, o)),
      "tree_bytes_hex" -> Json.fromString(r.treeHex),
      "version"        -> Json.obj("activated" -> Json.fromInt(r.activated), "ergoTree" -> Json.fromInt(r.treeVersion)),
      "context"        -> context(r.activated),
      "expected"       -> expected)
  }

  def extract(): Map[String, Json] =
    rows().groupBy(_.op).map { case (op, rs) =>
      op -> Json.obj(
        "schema"     -> Json.fromString("santa-eval/v6-fullctx"),
        "op"         -> Json.fromString(op),
        "blessed_by" -> Json.fromString(Stamp),
        "source"     -> Json.fromString(Source),
        "entries"    -> Json.arr(rs.zipWithIndex.map { case (r, i) => entry(r, i) }: _*))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalSubstConstantsLift", extract(), outDir)
}
