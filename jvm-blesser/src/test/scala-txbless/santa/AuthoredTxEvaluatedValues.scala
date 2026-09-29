package santa

// AuthoredTxEvaluatedValues — the transaction part of sigma-rust's evaluated-values probe (2026-09-29). Blessed through
// TxEngine.validateBytes (ergo-core 6.0.6 validateStateful) under the storage-rent vectors' synthetic context
// (AuthoredTxStorageRent: ten v4 headers below H = 1051200, the launch parameters).
//
// Extension and register values that are EvaluatedValues but not Constants parse (wire: extension_evaluated_values,
// register_evaluated_values). At evaluation:
// - toSigmaContext converts every extension value up front, `stypeToRType(v.tpe)` and `v.value`
//   (`ErgoLikeContext.scala:158-161`). `Tuple.value` casts each item to EvaluatedValue with an assert (`values.scala:819`,
//   `CollectionUtil.scala:188-193`), so Tuple(1, HEIGHT) throws an AssertionError whether a script reads it or not. A
//   SigmaPropConstant root is never evaluated (`Interpreter.scala:210-217`), so nothing is converted.
// - A Tuple node's value is a Coll[Any], while stypeToRType of a 2-item STuple is a pair type (`Evaluation.scala:37-40`):
//   getVar[(Int, Int)] hands the evaluator the Coll, and its type check throws ("Invalid type returned by evaluator",
//   `values.scala:233-248`). The (Int, Int) constant is a real pair. A 3-item Tuple converts (`Evaluation.scala:41-48`).
// - Registers convert the same way, all at once on the box's first register read (`CBox.scala:28`, `:77-94`): a box
//   whose R4 is Tuple(1, HEIGHT) fails any register read, and SELF.R4[(Int, Int)] of a Tuple node fails as getVar does.
// - Storage rent: var 127 = TrueLeaf makes the Short cast throw inside the Try, so verify falls back to the script
//   (`ErgoInterpreter.scala:77-84`).

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{Constant, ErgoTree, EvaluatedValue, Height, IntConstant, SInt, STuple, SType, Tuple}
import sigma.crypto.CryptoConstants.dlogGroup
import sigma.serialization.ErgoTreeSerializer
import sigmastate.crypto.DLogProtocol.DLogProverInput
import org.ergoplatform.ErgoBox
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxEvaluatedValues {
  val SpendPath = "transaction/v6/authored/evaluated-values-spend.json"
  private val V3: Byte = VersionContext.V6SoftForkVersion
  private val Activated = 3
  private val ErgoTreeV = 0
  private val V = 1000000000L // the spent box's value
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def tree(h: String): ErgoTree = VersionContext.withVersions(V3, V3) {
    ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(h))
  }
  private def spent(label: String, t: ErgoTree, height: Int = 1,
                    regs: Map[ErgoBox.NonMandatoryRegisterId, EvaluatedValue[_ <: SType]] = Map()): ErgoBox =
    VersionContext.withVersions(V3, V3) { box(label, V, height, tree = t, regs = regs) }

  private def validate(txHex: String, inputs: Seq[ErgoBox]): TxEngine.Verdict =
    TxEngine.validateBytes(txHex, inputs.map(x => hex(x.bytes)), Nil, AuthoredTxStorageRent.headersHex,
      AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)

  /** Spend `bx` into one output created at `outHeight`, with no proof and input 0's extension the raw bytes `extHex`
    * (spliced in: the serializer writes TrueLeaf as 01 01, never 7f). The JVM's verdict must be `want`, and its reason
    * must mention `because`. */
  private def entry(i: Int, slug: String, description: String, bx: ErgoBox, extHex: String, want: Boolean,
                    because: String = "", outHeight: Int = 1): Json = {
    val plain = VersionContext.withVersions(V3, V3) {
      txBytes(tx(Seq(input(bx, ext())), Seq(candidate(bx.value, outHeight))))
    }
    require(hex(plain.slice(33, 35)) == "0000", s"unexpected transaction layout ${hex(plain)}")
    val txHex = hex(plain.take(34) ++ b(extHex) ++ plain.drop(35))
    val v = validate(txHex, Seq(bx))
    require(v.valid == want, s"$slug#$i: want valid=$want, got valid=${v.valid} ${v.reason.getOrElse("")}")
    require(v.reason.getOrElse("").contains(because), s"$slug#$i: the reason must mention '$because': ${v.reason}")
    Json.obj(
      "name"                 -> Json.fromString(s"$slug#$i"),
      "source"               -> Json.fromString("santa:authored-tx-evaluated-values:spend"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
      "preHeader"            -> AuthoredTxStorageRent.preHeader,
      "parameters"           -> AuthoredTxStorageRent.params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(Activated), "ergoTree" -> Json.fromInt(ErgoTreeV)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  private def spendEntries: Seq[Json] = {
    val note = "The input spends a box of value 1000000000 into one output of the same value, with no proof."
    val True   = tree("00d10101")                 // sigmaProp(true), evaluated
    val v3     = tree("00d1938ce4e30058010402")   // sigmaProp(getVar[(Int, Int)](0).get._1 == 1)
    val v9     = tree("00d1938ce4c6a70458010402") // sigmaProp(SELF.R4[(Int, Int)].get._1 == 1)
    val v10    = tree("00d193e4c6a705040402")     // sigmaProp(SELF.R5[Int].get == 1)
    val tup12: EvaluatedValue[_ <: SType] = Tuple(IntConstant(1), IntConstant(2))
    val tup1H: EvaluatedValue[_ <: SType] = Tuple(IntConstant(1), Height)
    val pair12: EvaluatedValue[_ <: SType] = Constant[SType]((1, 2).asInstanceOf[SType#WrappedType], STuple(SInt, SInt))
    val pk = DLogProverInput(new java.math.BigInteger(1, digest("santa:evaluated-values:p2pk")).mod(dlogGroup.order))
      .publicImage
    val p2pk = VersionContext.withVersions(V3, V3) {
      box("santa:ev:v8-p2pk", V, 0, tree = ErgoTree.fromSigmaBoolean(ErgoTree.ZeroHeader, pk))
    }
    val converts = "toSigmaContext converts every extension value before the script runs (ErgoLikeContext.scala:158-161)"
    Seq(
      entry(0, "v1-ext-tuple-height-evaluated-reject",
        s"Input 0's extension is {0: Tuple(1, HEIGHT)} (86 02 04 02 a3), which parses, and the spent tree is " +
        s"sigmaProp(true) (00 d1 01 01), which is evaluated. $converts: Tuple.value casts each item to EvaluatedValue " +
        "with an assert (values.scala:819), and HEIGHT fails it: an AssertionError, although the script never reads " +
        s"the variable. Invalid. An impl that converts lazily, or not at all, accepts. $note",
        spent("santa:ev:v1", True), "0100" + "86020402a3", want = false, because = "AssertionError"),
      entry(1, "v2-ext-tuple-height-sigmaprop-root-accept",
        "The same extension, but the spent tree is the SigmaProp constant TrueProp (00 08 d3): a SigmaPropConstant root " +
        s"is never evaluated (Interpreter.scala:210-217), so nothing is converted. Valid. $note",
        spent("santa:ev:v2", tree("0008d3")), "0100" + "86020402a3", want = true),
      entry(2, "v3-ext-tuple-node-as-pair-reject",
        "Input 0's extension is {0: Tuple(1, 2)} as a Tuple node (86 02 04 02 04 04); the script is " +
        "sigmaProp(getVar[(Int, Int)](0).get._1 == 1). The node's value is a Coll[Any], but stypeToRType of a 2-item " +
        "STuple is a pair type (Evaluation.scala:37-40), so getVar hands the evaluator a Coll where a pair is typed, " +
        "and its type check throws ('Invalid type returned by evaluator', values.scala:233-248). Invalid. An impl that " +
        s"reads the node as a real pair accepts. $note",
        spent("santa:ev:v3", v3), "0100" + "860204020404", want = false, because = "Invalid type returned by evaluator"),
      entry(3, "v3-ext-pair-constant-accept",
        "The twin: the same script, with {0: (1, 2)} as an (Int, Int) constant (58 02 04), a real pair: _1 is 1. " +
        s"Valid. $note", spent("santa:ev:v3-twin", v3), "0100" + "580204", want = true),
      entry(4, "v4-ext-group-generator-accept",
        "Input 0's extension is {0: GroupGenerator} (82); the script is sigmaProp(getVar[GroupElement](0).get == " +
        s"groupGenerator). GroupGenerator is an EvaluatedValue whose value is the generator. Valid. $note",
        spent("santa:ev:v4", tree("00d193e4e3000782")), "0100" + "82", want = true),
      entry(5, "v5-ext-coll-int-node-accept",
        "Input 0's extension is {0: Coll[Int](1, 2)} as a ConcreteCollection node (83 02 04 04 02 04 04); the script " +
        s"is sigmaProp(getVar[Coll[Int]](0).get.size == 2). Valid. $note",
        spent("santa:ev:v5", tree("00d193b1e4e300100404")), "0100" + "83020404020404", want = true),
      entry(6, "v6-ext-trueleaf-accept",
        "Input 0's extension is {0: TrueLeaf} as the opcode 7f; the script is sigmaProp(getVar[Boolean](0).get). " +
        s"TrueLeaf is the constant true. Valid. $note",
        spent("santa:ev:v6", tree("00d1e4e30001")), "0100" + "7f", want = true),
      entry(7, "v7-ext-tuple-three-accept",
        "Input 0's extension is {0: a 3-item Tuple (1, 2, 3)} (86 03 04 02 04 04 04 06), and the spent tree is " +
        s"sigmaProp(true), evaluated. $converts, and a Tuple of any arity converts (Evaluation.scala:41-48). Valid. $note",
        spent("santa:ev:v7", True), "0100" + "8603040204040406", want = true),
      entry(8, "v8-rent-var127-trueleaf-script-accept",
        "Storage rent: the spent box (sigmaProp(true)) was created at height 0, so it is rent-eligible at H; the proof " +
        "is empty and var 127 is TrueLeaf (7f). The rent path's Short cast throws inside its Try, so verify falls " +
        "back to the script (ErgoInterpreter.scala:77-84), which accepts. The output is created at H - 1, which a " +
        "recreation would violate. Valid.",
        spent("santa:ev:v8", True, height = 0), "01" + "7f" + "7f", want = true, outHeight = H - 1),
      entry(9, "v8-rent-var127-trueleaf-p2pk-reject",
        "The same rent-eligible spend of a P2PK box: the fallback verifies the script, which needs a proof, and the " +
        "proof is empty. Invalid.",
        p2pk, "01" + "7f" + "7f", want = false, because = "Success((false,", outHeight = H - 1),
      entry(10, "v9-r4-tuple-node-as-pair-reject",
        "The spent box's R4 is Tuple(1, 2) as a Tuple node (86 02 04 02 04 04); the script is " +
        "sigmaProp(SELF.R4[(Int, Int)].get._1 == 1). Registers convert like extension values (CBox.scala:77-94): the " +
        "node's value is a Coll typed as a pair, and the evaluator's type check throws. Invalid. An impl that reads the " +
        s"node as a real pair accepts. $note",
        spent("santa:ev:v9", v9, regs = Map(ErgoBox.R4 -> tup12)), "00", want = false,
        because = "Invalid type returned by evaluator"),
      entry(11, "v9-r4-pair-constant-accept",
        s"The twin: the same script, with R4 the (Int, Int) constant (1, 2). Valid. $note",
        spent("santa:ev:v9-twin", v9, regs = Map(ErgoBox.R4 -> pair12)), "00", want = true),
      entry(12, "v10-r4-tuple-height-read-r5-reject",
        "The spent box's R4 is Tuple(1, HEIGHT) (86 02 04 02 a3) and R5 is Int 1; the script is " +
        "sigmaProp(SELF.R5[Int].get == 1). The box's registers convert all at once on its first register read " +
        "(CBox.scala:28), so reading R5 converts R4 too, and HEIGHT fails Tuple.value's assert. Invalid. An impl that " +
        s"converts registers one at a time accepts. $note",
        spent("santa:ev:v10", v10, regs = Map(ErgoBox.R4 -> tup1H, ErgoBox.R5 -> IntConstant(1))), "00", want = false,
        because = "AssertionError"),
      entry(13, "v10-r4-tuple-pair-read-r5-accept",
        s"The twin: R4 is Tuple(1, 2), which converts; reading R5 gives 1. Valid. $note",
        spent("santa:ev:v10-twin", v10, regs = Map(ErgoBox.R4 -> tup12, ErgoBox.R5 -> IntConstant(1))), "00",
        want = true),
      entry(14, "v10-r4-tuple-height-no-read-accept",
        "The box of #12 (R4 = Tuple(1, HEIGHT)) guarded by sigmaProp(true), which reads no register: nothing converts " +
        s"the registers, so the box spends. Valid. $note",
        spent("santa:ev:v10-noread", True, regs = Map(ErgoBox.R4 -> tup1H, ErgoBox.R5 -> IntConstant(1))), "00",
        want = true))
  }

  def blessAll(): Seq[(String, Json)] = Seq(SpendPath -> Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString("tx:authored:evaluated-values-spend"),
    "blessed_by" -> Json.fromString(AuthoredTxStorageRent.BlessedBy),
    "entries"    -> Json.arr(spendEntries: _*)))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
