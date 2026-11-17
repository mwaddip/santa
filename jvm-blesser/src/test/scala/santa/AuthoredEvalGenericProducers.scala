package santa

// Authored eval vectors for sigma-rust's santa-probe-specialised-patch-array-2 (2026-11-17), the addendum to its queue
// item 31 (AuthoredEvalPatchArrayClass): the producers a node holds GENERIC, and what reads through a receiver's class
// on them. Measured first in santa.spike.SpecialisedPatchArrayProbe (local); the reply is
// prompts/drafts/sigma-rust-specialised-patch-array-2-reply.md. Transaction companion: AuthoredTxGenericProducers.
//
// THE RULE: on the JVM a collection's runtime class decides `patch` (a specialised one casts the patch's array),
// `getOrElse` / `ByIndex` with a default not of the item's class (a generic one hands back the item or the default as
// it is; a specialised one unboxes, and throws), and `startsWith` / `endsWith` with a collection held over another
// array. A node holds GENERIC: the pre-header's `parentId` and `votes` (ergo-core's PreHeader.toSigma, `toColl`); R1
// read as a register (`ByteArrayConstant(propositionBytes)`) and the bytes of R3 read as a register; a `Coll[Byte]` or
// `Coll[Boolean]` collection EXPRESSION (`83…`, `85…`) in a register or a context variable, also nested, sliced or read
// by `getReg` (ConcreteCollection.value builds at an abstract type). It holds SPECIALISED: a header's fields, the
// pre-header's `minerPk.getEncoded`, a collection CONSTANT in a register or variable, `SELF.propositionBytes`, a token
// id: on those the same calls throw (the controls of the class).
//
// THE ROWS: sigma-rust's 1,486 (src/test/resources/sigma-rust-patch-array-class/rows-generic-producers.tsv, the jars'
// outcome, value and cost as an independent cross-check), each a bare root at tree version 0 (activated 2 and 3) or 3
// (activated 3). A row is `<set>|<method>|<receiver>|<argument>|v<n>`: `pre|…` under the first request's context,
// `rv-…` under its rv-rc context. Methods: `patch` (the argument behind `if (false) <one item> else <it>`),
// `patch.direct` (the argument itself), `patch.r1` (one item replaced), `startsWith`, `endsWith`, `getOrElse.{in,out}`
// and `byIndex.{in,out}` (index 0 or 9, the default the argument itself). An argument ending `.direct` is the argument
// itself. Not authored here: the 64 `pre|…` rows the first family already holds (the same bytes, tree version and
// context: Coll.patch_array_class_context), and the rows that answer `()` (a Unit default handed back), which no
// expected value can say and no wrapping can carry (a ValDef of the call's type variable throws `Unknown type IV`).
//
// THE CONTEXT (santa-eval/v6-fullctx): the header and pre-header of the substConstants-lift families; SELF is the first
// family's (`pre|…`) or sigma-rust's rv-rc box (`rv-…`: R4 a `Coll[Byte]` expression, R5 `85…`, R6 an `83…` of
// booleans, R7 a nested expression, R8 a `Coll[Int]` expression, R9 a `Coll[Byte]` constant, one token), read from its
// bytes; the rv-rc extension's five variables, each as its RAW bytes (`Value` bindings: the JVM's writer would turn an
// `83…` of booleans into `85…`), only on the rows that read a variable.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalGenericProducers {
  val Source = "santa:authored-generic-producers"
  val Stamp = "jvm:sigma-state-6.0.7"
  val RowsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-patch-array-class", "rows-generic-producers.tsv")
  private val P = AuthoredEvalPatchArrayClass
  private val L = AuthoredEvalSubstConstantsLift

  // ── the rv-rc context (sigma-rust's rv-rc.env) ───────────────────────────────────────────────────
  /** SELF: 1,000,000 nanoErg, the generator's P2PK, created at 100, one token (0x22 x 32, amount 1), R4 = the
    * expression `83020202010202`, R5 `850201`, R6 `83020101010100`, R7 `83010e83020202010202`, R8 `83020404020404`
    * (a Coll[Int] expression), R9 the constant `0e020102`; transaction id 0x11 x 32, index 0. */
  val RvSelfHex: String = "c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817986401" +
    "22" * 32 + "01" + "06" + "83020202010202" + "850201" + "83020101010100" + "83010e83020202010202" +
    "83020404020404" + "0e020102" + "11" * 32 + "00"
  /** The rv-rc extension, as sigma-rust sent it. */
  val RvExtHex = "050183020202010202028502010383010e83020202010202040e0201020583020101010100"
  /** Its five values' RAW bytes, sliced at the reader's positions (re-writing would turn var 5's `83…` into `85…`). */
  lazy val RvExt: Map[Int, String] = VersionContext.withVersions(3, 3) {
    val bytes = Base16.decode(RvExtHex).get
    val r = SigmaSerializer.startReader(bytes)
    val n = r.getUByte()
    val vs = (0 until n).map { _ =>
      val k = r.getByte().toInt
      val start = r.position
      ValueSerializer.deserialize(r)
      k -> hx(bytes.slice(start, r.position))
    }.toMap
    require(r.remaining == 0, "bytes left after the extension")
    vs
  }
  lazy val RvBindings: Map[Int, Json] = RvExt.map { case (k, h) => k -> P.valueBinding(h) }

  // ── the rows ─────────────────────────────────────────────────────────────────────────────────────
  case class Row(part: String, name: String, treeVersion: Int, activated: Int, body: String,
                 jarsOutcome: String, jarsValue: String, jarsCost: String, grade: String) {
    private val f = name.split('|')
    def set: String = f(0)
    def method: String = f(1)
    def receiver: String = f(2)
    def arg: String = f(3)
    def rv: Boolean = set.startsWith("rv-")
    def readsExtension: Boolean = receiver.contains(".var")
    def selfHex: String = if (rv) RvSelfHex else P.SelfHex
    def ext: Map[Int, Json] = if (readsExtension) RvBindings else Map.empty
    def context: Json = P.contextWith(activated, selfHex, ext)
    def generic: Boolean = Holder(receiver)._1
    def item: Boolean = method.startsWith("getOrElse") || method.startsWith("byIndex")
    def direct: Boolean = method.endsWith(".direct") || arg.endsWith(".direct")
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def op: String = "Coll.generic_producers" + (if (activated == 3) "_v6" else "")
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
    /** A value holding OUR header's bytes (sigma-rust's harness has its own header). */
    def holdsOurHeader: Boolean = receiver == "B.preHeader.parentId" || receiver == "B.header.parentId"
  }

  def rows(): Vector[Row] = {
    val lines = new String(java.nio.file.Files.readAllBytes(RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Row(c(0), c(1), c(2).toInt, a, c(4), c(5), c(6), c(7), c(9)))
    }
  }

  /** The first family's context rows, by (bytes, tree version): a `pre|…` row among them is already vectored there. */
  lazy val roundOne: Set[(String, Int)] =
    AuthoredEvalPatchArrayClass.rows().filter(_.inContext).map(r => (r.body, r.treeVersion)).toSet
  def inRoundOne(r: Row): Boolean = !r.rv && roundOne((r.body, r.treeVersion))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  // ── the oracle ───────────────────────────────────────────────────────────────────────────────────
  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

  def outcome(r: Row): Outcome = P.parse(r.treeHex, r.activated, r.treeVersion) match {
    case Left(t)        => RefusedAtParse(t)
    case Right(Left(t)) => RefusedAtParse(t)
    case Right(Right(())) =>
      EvalCore.evalFullContextOutcome(r.treeHex, 0, Seq(r.selfHex), Nil, Nil, Seq(L.HeaderHex(r.activated)),
        L.preHeaderHex(r.activated), Seq(r.ext), None, r.activated.toByte)._2 match {
        case Right((v, cost)) => Answer(v, cost)
        case Left(t)          => ThrowsAtEval(t)
      }
  }
  /** An answer no expected value can say (a Unit handed back: the encoder's `Opaque`). */
  def unsayable(o: Outcome): Boolean = o match {
    case Answer(v, _) => v.noSpaces.contains("\"Opaque\"")
    case _            => false
  }

  /** The rows authored: all but those the first family holds and those whose answer has no encoding. */
  def authored(): Vector[(Row, Outcome)] =
    rows().filterNot(inRoundOne).map(r => r -> outcome(r)).filterNot { case (_, o) => unsayable(o) }

  // ── the scripts ──────────────────────────────────────────────────────────────────────────────────
  val Receiver: Map[String, String] = Map(
    "B.preHeader.parentId" -> "CONTEXT.preHeader.parentId", "B.preHeader.votes" -> "CONTEXT.preHeader.votes",
    "B.header.parentId" -> "CONTEXT.headers(0).parentId",
    "B.preHeader.minerPk.getEncoded" -> "CONTEXT.preHeader.minerPk.getEncoded",
    "B.R4.cc" -> "SELF.R4[Coll[Byte]].get", "B.R4.cc.getReg" -> "SELF.getReg[Coll[Byte]](4).get",
    "B.R4.cc.slice" -> "SELF.R4[Coll[Byte]].get.slice(0, 2)", "Z.R5.cc85" -> "SELF.R5[Coll[Boolean]].get",
    "Z.R6.cc83" -> "SELF.R6[Coll[Boolean]].get", "B.R7.nested.inner" -> "SELF.R7[Coll[Coll[Byte]]].get(0)",
    "B.R9.const.ctl" -> "SELF.R9[Coll[Byte]].get", "B.var1.cc" -> "getVar[Coll[Byte]](1).get",
    "Z.var2.cc85" -> "getVar[Coll[Boolean]](2).get", "B.var3.nested.inner" -> "getVar[Coll[Coll[Byte]]](3).get(0)",
    "B.var4.const.ctl" -> "getVar[Coll[Byte]](4).get", "Z.var5.cc83" -> "getVar[Coll[Boolean]](5).get",
    "B.SELF.R1" -> "SELF.R1[Coll[Byte]].get", "B.SELF.getReg1" -> "SELF.getReg[Coll[Byte]](1).get",
    "B.SELF.R3._2" -> "SELF.R3[(Int, Coll[Byte])].get._2",
    "B.SELF.R2(0)._1" -> "SELF.R2[Coll[(Coll[Byte], Long)]].get(0)._1",
    "B.SELF.propositionBytes.ctl" -> "SELF.propositionBytes")

  private val Expr = "a collection EXPRESSION (a ConcreteCollection, whose value is built at an abstract type)"
  /** Whether a node holds the receiver GENERIC, and what it is. */
  val Holder: Map[String, (Boolean, String)] = Map(
    "B.preHeader.parentId" -> (true, "the pre-header's field, which ergo-core's PreHeader.toSigma builds with `toColl`"),
    "B.preHeader.votes" -> (true, "the pre-header's field, which ergo-core's PreHeader.toSigma builds with `toColl`"),
    "B.header.parentId" -> (false, "a header's field, read from the header's bytes"),
    "B.preHeader.minerPk.getEncoded" -> (false, "the group element's encoding"),
    "B.R4.cc" -> (true, s"R4, $Expr `83020202010202`"), "B.R4.cc.getReg" -> (true, s"R4, $Expr, read by `getReg`"),
    "B.R4.cc.slice" -> (true, s"a slice of R4, $Expr"), "Z.R5.cc85" -> (true, s"R5, $Expr `850201`"),
    "Z.R6.cc83" -> (true, s"R6, $Expr `83020101010100`"),
    "B.R7.nested.inner" -> (true, s"the inner collection of R7, $Expr nested in another"),
    "B.R9.const.ctl" -> (false, "R9, a Coll[Byte] CONSTANT read from bytes"),
    "B.var1.cc" -> (true, s"variable 1, $Expr `83020202010202`"), "Z.var2.cc85" -> (true, s"variable 2, $Expr `850201`"),
    "B.var3.nested.inner" -> (true, s"the inner collection of variable 3, $Expr nested in another"),
    "B.var4.const.ctl" -> (false, "variable 4, a Coll[Byte] CONSTANT read from bytes"),
    "Z.var5.cc83" -> (true, s"variable 5, $Expr `83020101010100`"),
    "B.SELF.R1" -> (true, "R1 read as a register, `ByteArrayConstant(propositionBytes)` built with `toColl`"),
    "B.SELF.getReg1" -> (true, "R1 read by `getReg`, `ByteArrayConstant(propositionBytes)` built with `toColl`"),
    "B.SELF.R3._2" -> (true, "the bytes of R3 read as a register (the creation info's transaction id and index)"),
    "B.SELF.R2(0)._1" -> (false, "a token id"),
    "B.SELF.propositionBytes.ctl" -> (false, "`Box.propositionBytes`, a property, not a register"))

  private val ItemType: Map[Char, String] = Map('B' -> "Byte", 'Z' -> "Boolean", 'I' -> "Int", 'L' -> "Long",
    'S' -> "Short", 'N' -> "BigInt", 'P' -> "(Int, Long)", 'C' -> "Coll[Byte]", 'Q' -> "(Byte, Boolean)")
  private val ItemValue: Map[Char, String] = Map('B' -> "1.toByte", 'Z' -> "true", 'I' -> "1", 'L' -> "1L",
    'S' -> "1.toShort", 'N' -> "1.toBigInt", 'P' -> "(1, 1L)", 'C' -> "Coll[Byte](1)", 'U' -> "()")
  private val Producer: Seq[(String, String)] = Seq(
    "zipmap" -> "built by `zip` of two `map`-built sides", "zip" -> "built by `zip` of two constants",
    "const" -> "constant", "map" -> "built by `map`", "idx" -> "built by `indices` of a Coll[Int] constant",
    "inner" -> "read out of a nested constant")
  private def items(n: Int): String = n match { case 0 => "an EMPTY"; case 1 => "a one-item"; case 3 => "a three-item" }

  /** The argument, as the script states it. */
  def argText(r: Row): String = {
    val a = r.arg.stripSuffix(".direct")
    val how = if (r.direct) "as the argument itself" else "behind `if (false) <one item of the receiver's> else <it>`"
    if (a.startsWith("item")) s"`${ItemValue(a(4))}` (${if (a(4) == 'U') "Unit" else ItemType(a(4))}), $how"
    else if (a.startsWith("triple"))
      s"the tuple constant `(1, 1, 1)` of ${ItemType(a(6))}s (a Coll[Any] over an Object array), $how"
    else {
      val (src, srcText) = Producer.find { case (k, _) => a.drop(1).startsWith(k) }
        .getOrElse(sys.error(s"${r.name}: unknown argument $a"))
      s"${items(a.drop(1 + src.length).toInt)} Coll[${ItemType(a.head)}] $srcText, $how"
    }
  }

  def call(r: Row): String = {
    val x = Receiver(r.receiver)
    r.method match {
      case "patch" | "patch.direct" => s"`$x.patch(0, <arg>, 0)`, the patch ${argText(r)}"
      case "patch.r1"               => s"`$x.patch(0, <arg>, 1)` (one item replaced), the patch ${argText(r)}"
      case m @ ("startsWith" | "endsWith") => s"`$x.$m(<arg>)`, the argument ${argText(r)}"
      case "getOrElse.in"  => s"`$x.getOrElse(0, <default>)` (in range), the default ${argText(r)}"
      case "getOrElse.out" => s"`$x.getOrElse(9, <default>)` (out of range), the default ${argText(r)}"
      case "byIndex.in"    => s"`$x(0)` as ByIndex with a default (in range), the default ${argText(r)}"
      case "byIndex.out"   => s"`$x(9)` as ByIndex with a default (out of range), the default ${argText(r)}"
    }
  }

  private def what(r: Row): String =
    if (r.item) "it hands back the item or the default as it is"
    else if (r.method.startsWith("patch")) "it builds from the patch's items, whatever array holds them"
    else "it compares item by item, whatever array holds them"

  def label(r: Row, o: Outcome): String = (o, r.generic) match {
    case (Answer(_, _), true) =>
      s"NODE'S CLASS: the receiver is ${Holder(r.receiver)._2}, a GENERIC collection on a node: ${what(r)}, and the " +
        "call answers. A port that holds it specialised refuses here."
    case (ThrowsAtEval(_), false) =>
      s"CONTROL OF THE CLASS: the receiver is ${Holder(r.receiver)._2}, a SPECIALISED collection on a node, which " +
        "reads the argument through its own array class: the call throws."
    case _ => sys.error(s"${r.name}: ${o.getClass.getSimpleName} on a ${if (r.generic) "generic" else "specialised"} receiver")
  }

  private def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq
  private def why(t: Throwable): String = {
    val c = chain(t).last
    val m = Option(c.getMessage).getOrElse("").replaceAll("@[0-9a-f]{4,}", "@<hash>").replaceAll("\\s+", " ").trim
    s"${c.getClass.getSimpleName}${if (m.isEmpty) "" else ": " + (if (m.length <= 140) m else m.take(140) + "…")}"
  }
  def said(o: Outcome): String = o match {
    case Answer(v, cost) =>
      val t = P.text(v)
      s"${if (t.length <= 120) t else t.take(120) + "…"} at cost $cost"
    case RefusedAtParse(t) => s"REFUSED AT PARSE (${why(t)})"
    case ThrowsAtEval(t)   => s"THROWS AT EVALUATION (${why(t)})"
  }

  def script(r: Row, o: Outcome): String =
    s"[${r.name}] ${call(r)} — ${said(o)}. ${label(r, o)}  // tree v${r.treeVersion}, activated ${r.activated}"

  // ── the vectors ──────────────────────────────────────────────────────────────────────────────────
  def extract(): Map[String, Json] =
    authored().groupBy(_._1.op).map { case (op, ros) =>
      val entries = ros.zipWithIndex.map { case ((r, o), i) =>
        val expected = o match {
          case Answer(v, cost) => Json.obj("value" -> v, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
          case _               => Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
        }
        Json.obj(
          "name"           -> Json.fromString(s"${r.slug}-a${r.activated}#$i"),
          "script"         -> Json.fromString(script(r, o)),
          "tree_bytes_hex" -> Json.fromString(r.treeHex),
          "version"        -> Json.obj("activated" -> Json.fromInt(r.activated), "ergoTree" -> Json.fromInt(r.treeVersion)),
          "context"        -> r.context,
          "expected"       -> expected)
      }
      op -> Json.obj(
        "schema"     -> Json.fromString("santa-eval/v6-fullctx"),
        "op"         -> Json.fromString(op),
        "blessed_by" -> Json.fromString(Stamp),
        "source"     -> Json.fromString(Source),
        "entries"    -> Json.arr(entries: _*))
    }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalGenericProducers", extract(), outDir)
}
