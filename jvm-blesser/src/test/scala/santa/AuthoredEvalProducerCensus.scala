package santa

// Authored eval vectors for sigma-rust's santa-probe-specialised-patch-array-4 (2026-11-17), the second addendum to
// its queue item 31 (AuthoredEvalPatchArrayClass, AuthoredEvalGenericProducers): R2 read as a register, and the
// producers of every box of a transaction. Measured first in santa.spike.SpecialisedPatchArrayReview2Probe (local);
// the reply is prompts/drafts/sigma-rust-specialised-patch-array-4-reply.md. Transaction companion:
// AuthoredTxProducerCensus.
//
// THE RULE (AuthoredEvalGenericProducers has the first half): on the JVM a collection's runtime class decides what
// reads through it. Two families here.
//  - PAIRS (Coll.generic_producers_pairs): R2 read as a register is built as `Box.tokens` is
//    (`Constant(additionalTokens.map(identity))`, ErgoBoxCandidate): a GENERIC collection of pairs (`CollOverArray` of
//    `Tuple2`), where the same data as a constant, or a `zip`, is a pair of arrays (`PairOfCols`). `getOrElse` /
//    `ByIndex` with a default of another class, `++` with a collection of pairs of another class, `patch`,
//    `startsWith`, `endsWith` with an `indices` argument, `updated`: the class shows. Rows over SELF.R2, SELF.tokens,
//    OUTPUTS(0).tokens and a `zip` control; THE RULE pinned: R2 answers as the tokens do, row for row.
//  - CENSUS (Coll.generic_producers_census): the producers a node holds GENERIC or SPECIALISED, over every box of a
//    transaction: a data input's, an output's and a second input's R1, R3 and expression registers, getVarFromInput,
//    collection expressions two deep, an empty one, one holding an expression and a constant, options, the v6
//    getReg; the controls (constants, a token id, `propositionBytes`, an output's `id`). A Tuple EXPRESSION in a
//    register or variable read at its tuple type throws whatever the call (OptionGet's type check).
//
// THE ROWS: sigma-rust's (src/test/resources/sigma-rust-patch-array-class/rows-producer-{pairs,census}.tsv, its
// ClassProbe2's answers on the 6.0.7 jars beside each as an independent cross-check), each a bare root at tree version
// 0 (activated 2 and 3) or 3 (activated 3). Not authored: a census row whose tree bytes, activated version AND
// outcome an earlier family (Coll.generic_producers, Coll.patch_array_class_context) already holds.
//
// THE CONTEXT (santa-eval/v6-fullctx): the header and pre-header of the substConstants-lift families; sigma-rust's
// transaction (its rows2.env): SELF and a second input, one data input, two outputs, the outputs carrying the
// transaction's id. The two extensions travel only on the rows that read a variable (as `Value` bindings in their RAW
// bytes: the JVM's writer would turn an `83…` of booleans into `85…`); the other rows' transaction has none, and so
// another id.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16

import org.ergoplatform.{DataInput, ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction, Input}
import sigma.VersionContext
import sigma.ast.UnparsedErgoTree
import sigma.interpreter.{ContextExtension, ProverResult}
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalProducerCensus {
  val Source = "santa:authored-generic-producers"
  val Stamp = "jvm:sigma-state-6.0.7"
  private val Res = java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-patch-array-class")
  val PairsFile: java.nio.file.Path = Res.resolve("rows-producer-pairs.tsv")
  val CensusFile: java.nio.file.Path = Res.resolve("rows-producer-census.tsv")
  private val P = AuthoredEvalPatchArrayClass
  private val L = AuthoredEvalSubstConstantsLift

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  // ── the transaction (sigma-rust's rows2.env, built as its gen_review2.py builds it) ───────────────
  private val Pk = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  val P2pkTree: String = "0008cd" + Pk
  private val TrueTree = "00d10101"
  val CcBytes = "83020202010202"                                   // ConcreteCollection[Byte](1, 2)
  private val CcBytes34 = "83020202030204"
  private val CcOnes = "83020202010201"
  private val CcOne = "8301020201"
  val Cc85 = "850201"                                              // ConcreteCollectionBooleanConstant(true, false)
  val Cc83 = "83020101010100"                                      // ConcreteCollection[Boolean](true, false)
  private val CcNested = "83010e" + CcBytes                        // Coll[Coll[Byte]] of one expression
  private val Deep2B = "83011a" + CcNested                         // two deep
  private val Deep2Z = "830119" + "83010d" + Cc83
  private val EmptyB = "830002"                                    // ConcreteCollection[Byte]()
  val Tuple3: String = "8603" + CcBytes + "0402" + "0404"          // (<CcBytes>, 1, 2), a Tuple EXPRESSION
  private val Tuple2 = "8602" + CcBytes + "0402"                   // (<CcBytes>, 1)
  private val Mixed = "83020e" + CcBytes + "0e020102"              // Coll(<CcBytes>, the constant Coll[Byte](1, 2))
  private val CcPk = "832102" + Pk.grouped(2).map("02" + _).mkString
  private val Script = "1001" + "08cd" + Pk + "d17300"
  private val CcScript = f"83${Script.length / 2}%02x02" + Script.grouped(2).map("02" + _).mkString
  private def regs(vs: String*): String = f"${vs.size}%02x" + vs.mkString
  private def tokens(ids: String*): String = f"${ids.size}%02x" + ids.map(_ * 32 + "01").mkString
  private def candidate(tree: String, toks: String, registers: String): String = "c0843d" + tree + "64" + toks + registers
  private def ext(pairs: (Int, String)*): String = f"${pairs.size}%02x" + pairs.map { case (k, v) => f"$k%02x$v" }.mkString

  /** SELF (input 0): the generator's P2PK, created at 100, one token (0x22 x 32), R4 a Tuple expression of three, R5
    * and R6 collection expressions two deep (bytes, booleans), R7 an empty one, R8 a Tuple expression of two, R9 a
    * Coll[Coll[Byte]] expression holding an expression and a constant; transaction 0x11 x 32, index 0. */
  val SelfHex: String =
    candidate(P2pkTree, tokens("22"), regs(Tuple3, Deep2B, Deep2Z, EmptyB, Tuple2, Mixed)) + "11" * 32 + "00"
  /** Input 1: `sigmaProp(true)`, R4 = <CcBytes>; transaction 0x55 x 32, index 0. */
  val In1Hex: String = candidate(TrueTree, tokens(), regs(CcBytes)) + "55" * 32 + "00"
  /** The data input: the P2PK, one token (0x33 x 32), R4 <CcBytes>, R5 `85…`, R6 an `83…` of booleans, R7 a nested
    * expression, R8 the constant Coll[Byte](1, 2); transaction 0x44 x 32, index 1. */
  val DataHex: String =
    candidate(P2pkTree, tokens("33"), regs(CcBytes, Cc85, Cc83, CcNested, "0e020102")) + "44" * 32 + "01"
  /** Output 0: the P2PK, the token, R4 <CcBytes>, R5 `85…`, R6 a nested expression, R7 a Tuple expression of three, R8
    * the constant; output 1: `sigmaProp(true)`. */
  val OutCandidates: Seq[String] = Seq(
    candidate(P2pkTree, tokens("22"), regs(CcBytes, Cc85, CcNested, Tuple3, "0e020102")),
    candidate(TrueTree, tokens(), "00"))
  val Ext0Hex: String = ext(1 -> CcBytes, 2 -> Tuple3, 3 -> Deep2B, 4 -> CcOnes, 5 -> EmptyB, 6 -> Cc85,
    7 -> "0e020102", 8 -> Deep2Z, 10 -> CcOne, 11 -> CcPk, 12 -> CcScript, 13 -> Mixed)
  val Ext1Hex: String = ext(1 -> CcBytes34, 2 -> Tuple3, 3 -> Deep2B, 6 -> Cc85, 7 -> "0e020304", 9 -> Cc83)

  /** An extension's values as their RAW bytes, sliced at the reader's positions. */
  def split(extHex: String): Seq[(Int, String)] = VersionContext.withVersions(3, 3) {
    val bytes = b(extHex)
    val r = SigmaSerializer.startReader(bytes)
    val vs = (0 until r.getUByte()).map { _ =>
      val k = r.getUByte()
      val start = r.position
      ValueSerializer.deserialize(r)
      k -> hx(bytes.slice(start, r.position))
    }
    require(r.remaining == 0, "bytes left after the extension")
    vs
  }
  private def bindings(extHex: String): Map[Int, Json] = split(extHex).map { case (k, h) => k -> P.valueBinding(h) }.toMap
  lazy val Bind0: Map[Int, Json] = bindings(Ext0Hex)
  lazy val Bind1: Map[Int, Json] = bindings(Ext1Hex)
  private def extJson(e: Map[Int, Json]): Json = Json.obj(e.toSeq.sortBy(_._1).map { case (k, v) => k.toString -> v }: _*)

  private def parseBox(h: String): ErgoBox =
    VersionContext.withVersions(3, 3)(ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(b(h))))
  private def parseExt(h: String): ContextExtension =
    VersionContext.withVersions(3, 3)(ContextExtension.serializer.parse(SigmaSerializer.startReader(b(h))))

  /** The transaction a node builds from these parts, with the two extensions or none. */
  final class Tx(val withVars: Boolean) {
    lazy val tx: ErgoLikeTransaction = VersionContext.withVersions(3, 3) {
      val e0 = if (withVars) parseExt(Ext0Hex) else ContextExtension.empty
      val e1 = if (withVars) parseExt(Ext1Hex) else ContextExtension.empty
      new ErgoLikeTransaction(
        IndexedSeq(Input(parseBox(SelfHex).id, new ProverResult(Array.emptyByteArray, e0)),
                   Input(parseBox(In1Hex).id, new ProverResult(Array.emptyByteArray, e1))),
        IndexedSeq(DataInput(parseBox(DataHex).id)),
        OutCandidates.map(o => ErgoBoxCandidate.serializer.parse(SigmaSerializer.startReader(b(o)))).toIndexedSeq)
    }
    lazy val idHex: String = tx.id
    lazy val outputsHex: Seq[String] = VersionContext.withVersions(3, 3)(tx.outputs.map(o => hx(o.bytes)).toSeq)
  }
  val WithVars = new Tx(true)
  val NoVars = new Tx(false)

  def context(activated: Int, withVars: Boolean): Json = {
    val t = if (withVars) WithVars else NoVars
    val (e0, e1) = if (withVars) (extJson(Bind0), extJson(Bind1)) else (Json.obj(), Json.obj())
    Json.obj(
      "self_index"       -> Json.fromInt(0),
      "inputs"           -> Json.arr(Json.fromString(SelfHex), Json.fromString(In1Hex)),
      "data_inputs"      -> Json.arr(Json.fromString(DataHex)),
      "outputs"          -> Json.arr(t.outputsHex.map(Json.fromString): _*),
      "headers"          -> Json.arr(Json.fromString(L.HeaderHex(activated))),
      "pre_header_hex"   -> Json.fromString(L.preHeaderHex(activated)),
      "height"           -> Json.fromInt(L.Height),
      "extension"        -> e0,
      "input_extensions" -> Json.arr(e0, e1))
  }

  // ── the rows ─────────────────────────────────────────────────────────────────────────────────────
  final case class Row(set: String, name: String, treeVersion: Int, activated: Int, body: String, jars: String) {
    private val f = name.split('|')
    def method: String = f(0)
    def receiver: String = f(1)
    def arg: String = if (f.length == 4) f(2) else ""
    def pairs: Boolean = set == "pairs"
    def readsVariable: Boolean = receiver.contains(".var") || receiver.contains("gvfi")
    def treeHex: String =
      if (treeVersion == 0) "00" + body else f"${8 + treeVersion}%02x" + vlq(body.length / 2) + body
    def op: String = (if (pairs) "Coll.generic_producers_pairs" else "Coll.generic_producers_census") +
      (if (activated == 3) "_v6" else "")
    def context: Json = AuthoredEvalProducerCensus.context(activated, readsVariable)
    def tx: Tx = if (readsVariable) WithVars else NoVars
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
    /** The group a row's class is compared in: the same call, argument, tree version and activated version. */
    def group: (String, String, Int, Int) = (method, arg, treeVersion, activated)
    // the jars' line: `<class> <value> @<cost>` or `THROWS(<class>: <message>) @<cost>`
    private val at = jars.lastIndexOf(" @")
    def jarsThrows: Boolean = jars.startsWith("THROWS(")
    def jarsCost: String = jars.drop(at + 2)
    def jarsValue: String = jars.take(at).dropWhile(_ != ' ').drop(1)
    def jarsThrowable: String = jars.drop(7).takeWhile(c => c != ':' && c != ')')
  }

  private def read(set: String, file: java.nio.file.Path): Vector[Row] =
    new String(java.nio.file.Files.readAllBytes(file), "UTF-8").split("\n").toVector.drop(1).filter(_.trim.nonEmpty)
      .map(_.split("\t", -1)).map(c => Row(set, c(0), c(1).toInt, c(2).toInt, c(3), c(4)))
  def rows(): Vector[Row] = read("pairs", PairsFile) ++ read("census", CensusFile)

  // ── the oracle ───────────────────────────────────────────────────────────────────────────────────
  sealed trait Outcome
  final case class Answer(value: Json, cost: Long) extends Outcome
  final case class RefusedAtParse(t: Throwable) extends Outcome
  final case class ThrowsAtEval(t: Throwable) extends Outcome

  /** The row's outcome in its entry's transaction. */
  def outcome(r: Row): Outcome = outcomeIn(r, r.readsVariable)
  /** The row's outcome in the transaction with the extensions (sigma-rust's, whose id its probe printed) or without. */
  def outcomeIn(r: Row, withVars: Boolean): Outcome = P.parse(r.treeHex, r.activated, r.treeVersion) match {
    case Left(t)        => RefusedAtParse(t)
    case Right(Left(t)) => RefusedAtParse(t)
    case Right(Right(())) =>
      val (e0, e1) = if (withVars) (Bind0, Bind1) else (Map.empty[Int, Json], Map.empty[Int, Json])
      val t = if (withVars) WithVars else NoVars
      EvalCore.evalFullContextOutcome(r.treeHex, 0, Seq(SelfHex, In1Hex), Seq(DataHex), t.outputsHex,
        Seq(L.HeaderHex(r.activated)), L.preHeaderHex(r.activated), Seq(e0, e1), None, r.activated.toByte)._2 match {
        case Right((v, cost)) => Answer(v, cost)
        case Left(t)          => ThrowsAtEval(t)
      }
  }
  def expected(o: Outcome): Json = o match {
    case Answer(v, cost) => Json.obj("value" -> v, "cost" -> Json.fromLong(cost), "error" -> Json.Null)
    case _               => Json.obj("value" -> Json.Null, "cost" -> Json.Null, "error" -> Json.fromString("errored"))
  }

  /** The earlier families' entries, by (tree bytes, activated version): their expected outcomes. */
  lazy val earlier: Map[(String, Int), Set[Json]] =
    (AuthoredEvalGenericProducers.extract().values ++
      AuthoredEvalPatchArrayClass.extract().filter(_._1.startsWith("Coll.patch_array_class_context")).values)
      .flatMap(_.hcursor.downField("entries").as[List[Json]].toOption.get).map { e =>
        val c = e.hcursor
        (c.get[String]("tree_bytes_hex").toOption.get, c.downField("version").get[Int]("activated").toOption.get) ->
          c.downField("expected").focus.get
      }.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2).toSet }
  /** A census row an earlier family already holds: the same tree bytes and activated version, the same outcome. */
  def heldEarlier(r: Row, o: Outcome): Boolean = earlier.get((r.treeHex, r.activated)).exists(_(expected(o)))

  /** The rows authored, each with its outcome: all but those an earlier family holds. */
  def authored(): Vector[(Row, Outcome)] = rows().map(r => r -> outcome(r)).filterNot { case (r, o) => heldEarlier(r, o) }

  // ── the class ────────────────────────────────────────────────────────────────────────────────────
  sealed trait Kind
  case object Generic extends Kind
  case object Specialised extends Kind
  case object TupleRead extends Kind
  def kind(r: Row): Kind =
    if (r.pairs) { if (r.receiver == "zip.ctl") Specialised else Generic }
    else if (r.receiver.contains("tuple")) TupleRead
    else if (r.receiver.endsWith(".ctl")) Specialised
    else Generic
  /** A census call that reads the receiver's class against an argument or default of another: not the value, not a
    * patch with the receiver's own type, not `ByIndex` in range at tree version 3 (which does not touch the default). */
  def readsClass(r: Row): Boolean =
    !r.pairs && r.method != "value" && !(r.method == "patch" && r.arg == "ctl0") &&
      !(r.method == "byIndex.in" && r.treeVersion == 3)

  // ── the scripts ──────────────────────────────────────────────────────────────────────────────────
  private val Expr = "a collection EXPRESSION (a ConcreteCollection, whose value is built at an abstract type)"
  private val Const = "a Coll[Byte] CONSTANT read from bytes"
  private val TokenId = "a token id"
  private val Tup = "a Tuple EXPRESSION"
  /** receiver -> (the expression, what a node holds there). */
  val Receiver: Map[String, (String, String)] = Map(
    // pairs
    "SELF.R2" -> ("SELF.R2[Coll[(Coll[Byte], Long)]].get", "R2 read as a register, which a node builds as `Box.tokens` " +
      "(`Constant(additionalTokens.map(identity))`): a GENERIC collection of pairs (`CollOverArray` of `Tuple2`)"),
    "SELF.tokens" -> ("SELF.tokens", "`Box.tokens`, a GENERIC collection of pairs (`CollOverArray` of `Tuple2`)"),
    "OUT0.tokens" -> ("OUTPUTS(0).tokens", "an output's `tokens`, a GENERIC collection of pairs (`CollOverArray` of `Tuple2`)"),
    "zip.ctl" -> ("Coll(Coll[Byte](1)).zip(Coll(1L))", "a collection of pairs built by `zip`, a pair of arrays " +
      "(`PairOfCols`): the class the R2 data constant has"),
    // SELF's registers
    "B.SELF.R4.tuple3._1" -> ("SELF.R4[(Coll[Byte], Int, Int)].get._1", s"the first item of R4, $Tup of three"),
    "B.SELF.R5.deep2(0)(0)" -> ("SELF.R5[Coll[Coll[Coll[Byte]]]].get(0)(0)", s"the innermost of R5, $Expr two deep"),
    "Z.SELF.R6.deep2(0)(0)" -> ("SELF.R6[Coll[Coll[Coll[Boolean]]]].get(0)(0)",
      s"the innermost of R6, $Expr of booleans two deep"),
    "B.SELF.R7.empty" -> ("SELF.R7[Coll[Byte]].get", s"R7, $Expr with no item (`830002`)"),
    "B.SELF.R8.tuple2._1" -> ("SELF.R8[(Coll[Byte], Int)].get._1", s"the first item of R8, $Tup of two"),
    "B.SELF.R9.mixed(0)" -> ("SELF.R9[Coll[Coll[Byte]]].get(0)",
      s"item 0 of R9 (a collection expression holding an expression and a constant): $Expr"),
    "B.SELF.R9.mixed(1).ctl" -> ("SELF.R9[Coll[Coll[Byte]]].get(1)",
      s"item 1 of R9 (a collection expression holding an expression and a constant): $Const"),
    "B.SELF.getReg4.tuple3._1" -> ("SELF.getReg[(Coll[Byte], Int, Int)](4).get._1",
      s"the first item of R4 read by `getReg`, $Tup of three"),
    "B.SELF.getReg1" -> ("SELF.getReg[Coll[Byte]](1).get",
      "R1 read by `getReg`, `ByteArrayConstant(propositionBytes)` built with `toColl`"),
    "B.SELF.R2(0)._1.ctl" -> ("SELF.R2[Coll[(Coll[Byte], Long)]].get(0)._1", s"$TokenId read out of R2"),
    "B.SELF.tokens(0)._1.ctl" -> ("SELF.tokens(0)._1", s"$TokenId read out of `tokens`"),
    // context variables
    "B.var2.tuple3._1" -> ("getVar[(Coll[Byte], Int, Int)](2).get._1", s"the first item of variable 2, $Tup of three"),
    "B.var3.deep2(0)(0)" -> ("getVar[Coll[Coll[Coll[Byte]]]](3).get(0)(0)", s"the innermost of variable 3, $Expr two deep"),
    "Z.var8.deep2(0)(0)" -> ("getVar[Coll[Coll[Coll[Boolean]]]](8).get(0)(0)",
      s"the innermost of variable 8, $Expr of booleans two deep"),
    "B.var5.empty" -> ("getVar[Coll[Byte]](5).get", s"variable 5, $Expr with no item"),
    "B.var13.mixed(0)" -> ("getVar[Coll[Coll[Byte]]](13).get(0)",
      s"item 0 of variable 13 (an expression and a constant): $Expr"),
    "B.var13.mixed(1).ctl" -> ("getVar[Coll[Coll[Byte]]](13).get(1)",
      s"item 1 of variable 13 (an expression and a constant): $Const"),
    "B.var1.getOrElse" -> ("getVar[Coll[Byte]](1).getOrElse(Coll[Byte]())", s"variable 1 through `Option.getOrElse`, $Expr"),
    "B.var99.getOrElse.ctl" -> ("getVar[Coll[Byte]](99).getOrElse(Coll[Byte](1, 2))",
      "an absent variable's default, the constant Coll[Byte](1, 2)"),
    "B.var1.some.get" -> ("Global.some(getVar[Coll[Byte]](1).get).get", s"variable 1 through `Global.some`, $Expr"),
    "B.var7.const.ctl" -> ("getVar[Coll[Byte]](7).get", s"variable 7, $Const"),
    "B.var1.cc" -> ("getVar[Coll[Byte]](1).get", s"variable 1, $Expr `83020202010202`"),
    "Z.var6.cc85" -> ("getVar[Coll[Boolean]](6).get", s"variable 6, $Expr `850201`"),
    // getVarFromInput
    "B.gvfi(0,1)" -> ("getVarFromInput[Coll[Byte]](0, 1).get", s"input 0's variable 1, $Expr"),
    "B.gvfi(1,1)" -> ("getVarFromInput[Coll[Byte]](1, 1).get", s"input 1's variable 1, $Expr"),
    "Z.gvfi(1,6)" -> ("getVarFromInput[Coll[Boolean]](1, 6).get", s"input 1's variable 6, $Expr `850201`"),
    "Z.gvfi(1,9)" -> ("getVarFromInput[Coll[Boolean]](1, 9).get", s"input 1's variable 9, $Expr `83…` of booleans"),
    "B.gvfi(1,2).tuple3._1" -> ("getVarFromInput[(Coll[Byte], Int, Int)](1, 2).get._1",
      s"the first item of input 1's variable 2, $Tup of three"),
    "B.gvfi(1,3).deep2(0)(0)" -> ("getVarFromInput[Coll[Coll[Coll[Byte]]]](1, 3).get(0)(0)",
      s"the innermost of input 1's variable 3, $Expr two deep"),
    "B.gvfi(1,7).const.ctl" -> ("getVarFromInput[Coll[Byte]](1, 7).get", s"input 1's variable 7, $Const"),
    "B.gvfi(0,7).const.ctl" -> ("getVarFromInput[Coll[Byte]](0, 7).get", s"input 0's variable 7, $Const"),
    // the data input
    "B.DATA.R1" -> ("CONTEXT.dataInputs(0).R1[Coll[Byte]].get",
      "a data input's R1 read as a register, `ByteArrayConstant(propositionBytes)` built with `toColl`"),
    "B.DATA.R3._2" -> ("CONTEXT.dataInputs(0).R3[(Int, Coll[Byte])].get._2",
      "the bytes of a data input's R3 read as a register"),
    "B.DATA.R4.cc" -> ("CONTEXT.dataInputs(0).R4[Coll[Byte]].get", s"a data input's R4, $Expr"),
    "Z.DATA.R5.cc85" -> ("CONTEXT.dataInputs(0).R5[Coll[Boolean]].get", s"a data input's R5, $Expr `850201`"),
    "Z.DATA.R6.cc83" -> ("CONTEXT.dataInputs(0).R6[Coll[Boolean]].get", s"a data input's R6, $Expr `83…` of booleans"),
    "B.DATA.R7.nested(0)" -> ("CONTEXT.dataInputs(0).R7[Coll[Coll[Byte]]].get(0)",
      s"the inner collection of a data input's R7, $Expr nested in another"),
    "B.DATA.R8.const.ctl" -> ("CONTEXT.dataInputs(0).R8[Coll[Byte]].get", s"a data input's R8, $Const"),
    "B.DATA.creationInfo._2" -> ("CONTEXT.dataInputs(0).creationInfo._2", "the bytes of a data input's `creationInfo`"),
    "B.DATA.propositionBytes.ctl" -> ("CONTEXT.dataInputs(0).propositionBytes",
      "a data input's `propositionBytes`, a property, not a register"),
    "B.DATA.R2(0)._1.ctl" -> ("CONTEXT.dataInputs(0).R2[Coll[(Coll[Byte], Long)]].get(0)._1",
      s"$TokenId read out of a data input's R2"),
    "B.DATA.getReg1" -> ("CONTEXT.dataInputs(0).getReg[Coll[Byte]](1).get", "a data input's R1 read by `getReg`"),
    // the outputs
    "B.OUT0.R1" -> ("OUTPUTS(0).R1[Coll[Byte]].get", "an output's R1 read as a register"),
    "B.OUT0.R3._2" -> ("OUTPUTS(0).R3[(Int, Coll[Byte])].get._2",
      "the bytes of an output's R3 read as a register (the transaction's id and the index)"),
    "B.OUT0.R4.cc" -> ("OUTPUTS(0).R4[Coll[Byte]].get", s"an output's R4, $Expr"),
    "Z.OUT0.R5.cc85" -> ("OUTPUTS(0).R5[Coll[Boolean]].get", s"an output's R5, $Expr `850201`"),
    "B.OUT0.R6.nested(0)" -> ("OUTPUTS(0).R6[Coll[Coll[Byte]]].get(0)",
      s"the inner collection of an output's R6, $Expr nested in another"),
    "B.OUT0.R7.tuple3._1" -> ("OUTPUTS(0).R7[(Coll[Byte], Int, Int)].get._1",
      s"the first item of an output's R7, $Tup of three"),
    "B.OUT0.R8.const.ctl" -> ("OUTPUTS(0).R8[Coll[Byte]].get", s"an output's R8, $Const"),
    "B.OUT0.R2(0)._1.ctl" -> ("OUTPUTS(0).R2[Coll[(Coll[Byte], Long)]].get(0)._1", s"$TokenId read out of an output's R2"),
    "B.OUT0.tokens(0)._1.ctl" -> ("OUTPUTS(0).tokens(0)._1", s"$TokenId read out of an output's `tokens`"),
    "B.OUT0.creationInfo._2" -> ("OUTPUTS(0).creationInfo._2", "the bytes of an output's `creationInfo`"),
    "B.OUT0.id.ctl" -> ("OUTPUTS(0).id", "an output's `id`"),
    "B.OUT1.R1" -> ("OUTPUTS.slice(1, 2)(0).R1[Coll[Byte]].get", "the second output's R1 read as a register"),
    // the other input
    "B.IN1.R1" -> ("INPUTS(1).R1[Coll[Byte]].get", "a second input's R1 read as a register"),
    "B.IN1.R4.cc" -> ("INPUTS(1).R4[Coll[Byte]].get", s"a second input's R4, $Expr"),
    "B.IN1.R3._2" -> ("INPUTS(1).R3[(Int, Coll[Byte])].get._2", "the bytes of a second input's R3 read as a register"),
    // anchors, measured before in another context
    "B.SELF.R1" -> ("SELF.R1[Coll[Byte]].get", "R1 read as a register, `ByteArrayConstant(propositionBytes)` built with `toColl`"),
    "B.SELF.R3._2" -> ("SELF.R3[(Int, Coll[Byte])].get._2", "the bytes of R3 read as a register"),
    "B.preHeader.parentId" -> ("CONTEXT.preHeader.parentId",
      "the pre-header's field, which ergo-core's PreHeader.toSigma builds with `toColl`"),
    "B.preHeader.votes" -> ("CONTEXT.preHeader.votes",
      "the pre-header's field, which ergo-core's PreHeader.toSigma builds with `toColl`"),
    "B.SELF.propositionBytes.ctl" -> ("SELF.propositionBytes", "`Box.propositionBytes`, a property, not a register"))

  private val PairArg: Map[String, String] = Map(
    "own0" -> "an EMPTY `Coll[(Coll[Byte], Long)]` constant (the receiver's own type)",
    "own1" -> "the constant `Coll((Coll[Byte](1), 1L))` (the receiver's own type)",
    "BZ0" -> "an EMPTY `Coll[(Byte, Boolean)]` constant", "IL0" -> "an EMPTY `Coll[(Int, Long)]` constant",
    "BZzip0" -> "`Coll[Byte]().zip(Coll[Boolean]())`, an EMPTY collection of pairs built by `zip`",
    "Iidx0" -> "`Coll[Int]().indices`, an EMPTY Coll[Int] built by `indices`",
    "tripleB" -> "the tuple constant `(1, 1, 1)` of Bytes (a Coll[Any] over an Object array)",
    "Int" -> "`1` (an Int)", "Bool" -> "`true` (a Boolean)", "pairIL" -> "`(1, 1L)` (an `(Int, Long)`)")
  private val Lie = "if (false) <an empty collection of the receiver's type> else <arg>"

  private def censusArg(r: Row): String = r.arg match {
    case "Iconst0" => "an EMPTY Coll[Int] constant"
    case "ctl0"    => s"an EMPTY Coll[${if (r.receiver.head == 'Z') "Boolean" else "Byte"}] constant (the receiver's own type)"
    case "other"   => if (r.receiver.head == 'Z') "`1.toByte` (a Byte)" else "`true` (a Boolean)"
  }

  def call(r: Row): String = {
    val x = Receiver(r.receiver)._1
    if (r.pairs) {
      val a = PairArg.getOrElse(r.arg, "")
      r.method match {
        case "value"        => s"`$x`"
        case "eq.own1"      => s"`$x == Coll((Coll[Byte](1), 1L))`"
        case "patch"        => s"`$x.patch(0, <arg>, 0)`, the patch $a"
        case "patch.lie"    => s"`$x.patch(0, $Lie, 0)`, <arg> $a"
        case "zip.size"     => s"`$x.zip(<arg>).size`, <arg> $a"
        case "append.right" => s"`$x ++ ($Lie)`, <arg> $a"
        case "append.left"  => s"`($Lie) ++ $x`, <arg> $a"
        case m @ ("startsWith" | "endsWith") => s"`$x.$m(<arg>)`, <arg> $a"
        case "byIndex.in"   => s"`$x(0)` as ByIndex with a default (in range), the default $a"
        case "getOrElse.in" => s"`$x.getOrElse(0, <default>)` (in range), the default $a"
        case "getOrElse.out" => s"`$x.getOrElse(9, <default>)` (out of range), the default $a"
        case "updated"      => s"`$x.updated(0, <item>)`, the item $a"
      }
    } else {
      val a = if (r.arg.isEmpty) "" else censusArg(r)
      val behind = "behind `if (false) <one item of the receiver's> else <it>`"
      r.method match {
        case "value"             => s"`$x`"
        case "patch"             => s"`$x.patch(0, <arg>, 0)`, the patch $a ${if (r.arg == "ctl0") "itself" else behind}"
        case "patch.direct"      => s"`$x.patch(0, <arg>, 0)`, the patch $a itself"
        case "startsWith"        => s"`$x.startsWith(<arg>)`, the argument $a $behind"
        case "startsWith.direct" => s"`$x.startsWith(<arg>)`, the argument $a itself"
        case "endsWith"          => s"`$x.endsWith(<arg>)`, the argument $a $behind"
        case "byIndex.in"        => s"`$x(0)` as ByIndex with a default (in range), the default $a"
        case "getOrElse.in"      => s"`$x.getOrElse(0, <default>)` (in range), the default $a"
        case "getOrElse.out"     => s"`$x.getOrElse(9, <default>)` (out of range), the default $a"
      }
    }
  }

  private def chain(t: Throwable): Seq[Throwable] = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq
  def why(t: Throwable): String = {
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

  private def what(r: Row): String =
    if (r.method.startsWith("getOrElse") || r.method.startsWith("byIndex")) "it hands back the item or the default as it is"
    else if (r.method.startsWith("patch")) "it builds from the patch's items, whatever array holds them"
    else "it compares item by item, whatever array holds them"

  /** The pairs rows' separating groups: where the `zip` control answers otherwise than the generic receivers (which
    * agree with each other in every group: the test pins it). */
  def separatingGroups(all: Seq[(Row, Outcome)]): Set[(String, String, Int, Int)] =
    all.filter(_._1.pairs).groupBy(_._1.group).collect {
      case (g, ros) if ros.map(_._2.isInstanceOf[Answer]).distinct.size > 1 => g
    }.toSet

  def label(r: Row, o: Outcome, separating: Set[(String, String, Int, Int)]): String = {
    val holder = Receiver(r.receiver)._2
    val answers = o.isInstanceOf[Answer]
    val does = if (answers) "answers" else "throws"
    if (r.pairs) {
      val sep = separating(r.group)
      (kind(r), sep) match {
        case (Generic, true) =>
          s"SEPARATING: the receiver is $holder on a node: the call $does, where over a pair of arrays (the `zip` " +
            s"control) it ${if (answers) "throws" else "answers"}. A port that holds it as a pair of arrays does as the control does."
        case (Generic, false) =>
          s"SAME ON BOTH CLASSES: the receiver is $holder on a node: the call $does, as it does over the `zip` control."
        case (_, true) =>
          s"CONTROL OF THE CLASS: the receiver is $holder: the call $does, where over R2 or `tokens` it " +
            s"${if (answers) "throws" else "answers"}."
        case (_, false) =>
          s"CONTROL OF THE CLASS: the receiver is $holder: the call $does, as it does over R2 and `tokens`."
      }
    } else (kind(r), readsClass(r), answers) match {
      case (TupleRead, _, false) =>
        s"THE READ THROWS: the receiver is $holder, a Coll[Any] on the JVM, which OptionGet's type check refuses " +
          "at the tuple type, whatever the call."
      case (Generic, true, true) =>
        s"NODE'S CLASS: the receiver is $holder, a GENERIC collection on a node: ${what(r)}, and the call answers. " +
          "A port that holds it specialised refuses here."
      case (Specialised, true, false) =>
        s"CONTROL OF THE CLASS: the receiver is $holder, a SPECIALISED collection on a node, which reads the " +
          "argument through its own array class: the call throws."
      case (k, false, true) =>
        val why =
          if (r.method == "value") "the value itself"
          else if (r.method == "patch") "a patch of the receiver's own type"
          else "`ByIndex` in range at tree version 3 hands back the item without touching the default"
        s"NOT A CLASS READ ($why): the call answers whatever the class; the receiver is $holder, " +
          s"${if (k == Generic) "GENERIC" else "SPECIALISED"} on a node."
      case (k, rc, a) => sys.error(s"${r.name} a${r.activated}: $k, readsClass=$rc, answers=$a")
    }
  }

  def script(r: Row, o: Outcome, separating: Set[(String, String, Int, Int)]): String =
    s"[${r.name}] ${call(r)} — ${said(o)}. ${label(r, o, separating)}  // tree v${r.treeVersion}, activated ${r.activated}"

  // ── the vectors ──────────────────────────────────────────────────────────────────────────────────
  def extract(): Map[String, Json] = {
    val all = rows().map(r => r -> outcome(r))
    val sep = separatingGroups(all)
    all.filterNot { case (r, o) => heldEarlier(r, o) }.groupBy(_._1.op).map { case (op, ros) =>
      val entries = ros.zipWithIndex.map { case ((r, o), i) =>
        require(!(o match { case Answer(v, _) => v.noSpaces.contains("\"Opaque\""); case _ => false }), s"${r.name}: no value")
        require(!o.isInstanceOf[RefusedAtParse], s"${r.name} a${r.activated}: refused at parse")
        Json.obj(
          "name"           -> Json.fromString(s"${r.slug}-a${r.activated}#$i"),
          "script"         -> Json.fromString(script(r, o, sep)),
          "tree_bytes_hex" -> Json.fromString(r.treeHex),
          "version"        -> Json.obj("activated" -> Json.fromInt(r.activated), "ergoTree" -> Json.fromInt(r.treeVersion)),
          "context"        -> r.context,
          "expected"       -> expected(o))
      }
      op -> Json.obj(
        "schema"     -> Json.fromString("santa-eval/v6-fullctx"),
        "op"         -> Json.fromString(op),
        "blessed_by" -> Json.fromString(Stamp),
        "source"     -> Json.fromString(Source),
        "entries"    -> Json.arr(entries: _*))
    }
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalProducerCensus", extract(), outDir)
}
