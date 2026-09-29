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
import sigma.ast.{BoolToSigmaProp, ByIndex, ByteArrayConstant, ByteConstant, ConcreteCollection, Constant, EQ, ErgoTree,
  EvaluatedValue, ExtractBytes, GroupGenerator, Height, IntConstant, Outputs, SByte, SInt, STuple, SType, SigmaAnd,
  SigmaPropConstant, Slice, Tuple}
import sigma.crypto.CryptoConstants.dlogGroup
import sigma.serialization.ErgoTreeSerializer
import sigmastate.crypto.DLogProtocol.DLogProverInput
import org.ergoplatform.{ErgoBox, ErgoLikeTransaction}
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
    entryTx(i, slug, description, hex(plain.take(34) ++ b(extHex) ++ plain.drop(35)), bx, want, because)
  }

  /** Bless the transaction `txHex` spending `bx`: the JVM's verdict must be `want`, its reason must mention `because`. */
  private def entryTx(i: Int, slug: String, description: String, txHex: String, bx: ErgoBox, want: Boolean,
                      because: String): Json = {
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
        want = true)) ++ followUpEntries(note, True, v10, tup12, pair12) ++ outputEntries(True) ++ boundaryEntries
  }

  /** sigma-rust's PR #52 request: verifyOutput's dust and size checks measure ErgoBox.bytes, written at the default
    * (1, 1), where X15's Upcast of a constant is dropped: 2 bytes fewer than at (3, 3). */
  private def boundaryEntries: Seq[Json] = {
    val x15 = "860204027e040205"
    val spentBox = VersionContext.withVersions(V3, V3) { box("santa:ev:boundary", V, 1) }
    def splice(plain: Array[Byte], from: String, to: String): Array[Byte] = {
      val (f, t) = (b(from), b(to))
      val at = plain.indexOfSlice(f)
      require(at >= 0 && plain.indexOfSlice(f, at + 1) < 0, s"$from must occur once")
      plain.take(at) ++ t ++ plain.drop(at + f.length)
    }
    /** Output 0 = `value`, SigmaProp(true), height 1, R4 = X15 (and R5 = `r5` zero bytes when r5 > 0); output 1 takes
      * the change. `len11` is output 0's ErgoBox.bytes length as the node writes it, at (1, 1). */
    def txOf(value: Long, r5: Int, len11: Int): String = {
      val bytes = VersionContext.withVersions(V3, V3) {
        val regs: Map[ErgoBox.NonMandatoryRegisterId, EvaluatedValue[_ <: SType]] =
          if (r5 > 0) Map(ErgoBox.R4 -> IntConstant(0), ErgoBox.R5 -> ByteArrayConstant(Array.fill[Byte](r5)(0)))
          else Map(ErgoBox.R4 -> IntConstant(0))
        val outs = Seq(candidate(value, 1, regs = regs)) ++ (if (V > value) Seq(candidate(V - value, 1)) else Nil)
        val plain = txBytes(tx(Seq(input(spentBox, ext())), outs))
        if (r5 > 0) splice(plain, "0008d3" + "010002" + "0400" + "0e", "0008d3" + "010002" + x15 + "0e")
        else splice(plain, "0008d3" + "010001" + "0400", "0008d3" + "010001" + x15)
      }
      val parsed = VersionContext.withVersions(3, 3) { ErgoLikeTransaction.serializer.fromBytes(bytes) }
      require(parsed.outputs(0).bytes.length == len11, s"output 0 must be $len11 bytes at (1, 1)")
      val at33 = VersionContext.withVersions(3, 3) { ErgoLikeTransaction.serializer.fromBytes(bytes).outputs(0).bytes.length }
      require(at33 == len11 + 2, s"output 0 must be ${len11 + 2} bytes at (3, 3), got $at33")
      hex(bytes)
    }
    val measured = "verifyOutput measures ErgoBox.bytes, which the node writes at the default (1, 1), where an Upcast of " +
      "a constant is written as the constant (ValueSerializer.scala:157-169): two bytes fewer than at (3, 3)."
    val minimum = 48 * 360
    Seq(
      entryTx(47, "x15-output-dust-boundary-accept",
        "Output 0 carries R4 = Tuple(1, Upcast(1, Long)) (86 02 04 02 7e 04 02 05) and is valued at exactly the dust " +
        "minimum; output 1 takes the change. The dust check is value >= minValuePerByte × ErgoBox.bytes.length " +
        s"(ErgoTransaction.scala:171, BoxUtils.scala:41). $measured Output 0 is 48 bytes, not 50, so with " +
        s"minValuePerByte 360 the minimum is $minimum, and output 0 holds $minimum: valid. An impl that measures the " +
        "output at (3, 3) wants 18000 and rejects it as dust.",
        txOf(minimum, 0, 48), spentBox, want = true, because = ""),
      entryTx(48, "x15-output-dust-below-reject",
        s"The same with ${minimum - 1}, one nanoERG below the minimum: invalid (txDust).",
        txOf(minimum - 1, 0, 48), spentBox, want = false, because = "minValuePerByte"),
      entryTx(49, "x15-output-size-4096-accept",
        "Output 0 (value 1000000000, height 1) carries R4 = the same Tuple and R5 = a Coll[Byte] of 4043 zero bytes. " +
        s"$measured So ErgoBox.bytes is exactly 4096 bytes, and 4098 at (3, 3). txBoxSize requires " +
        "out.bytes.length <= MaxBoxSize, 4096 (ErgoTransaction.scala:175): valid. An impl that measures at (3, 3) " +
        "rejects it.",
        txOf(V, 4043, 4096), spentBox, want = true, because = ""),
      entryTx(50, "x15-output-size-4097-reject",
        "The same with R5 one byte longer (4044): 4097 bytes at (1, 1): invalid (txBoxSize).",
        txOf(V, 4044, 4097), spentBox, want = false, because = "Box size should not exceed 4096"))
  }

  /** sigma-rust's third round: an output is written at the block path's (1, 1), while the transaction is read, and its
    * id computed, at a v4 block's (3, 3). */
  private def outputEntries(True: ErgoTree): Seq[Json] = {
    val V1M = 1000000L
    val x15 = "860204027e040205"
    val kept = "0008d3" + "010001" + x15                // output 0 after its value, R4 as received
    val stripped = "0008d3" + "010001" + "860204020402" // the same written below tree v3
    /** proveDlog(pk) && OUTPUTS(0).bytes.slice(3, to) == want */
    def script(to: Int, want: String): ErgoTree = VersionContext.withVersions(V3, V3) {
      val out0 = ByIndex(Outputs, IntConstant(0))
      ErgoTree.withoutSegregation(ErgoTree.ZeroHeader, SigmaAnd(
        BoolToSigmaProp(EQ(Slice(ExtractBytes(out0), IntConstant(3), IntConstant(to)), ByteArrayConstant(b(want)))),
        SigmaPropConstant(AuthoredTxSizedTreeRequests.pk)))
    }
    /** `bx` (value 1000000) spent into one output of 1000000 whose R4 is the raw bytes `r4`. */
    def build(bx: ErgoBox, r4: String, proof: Array[Byte]): Array[Byte] = VersionContext.withVersions(V3, V3) {
      val plain = txBytes(tx(Seq(input(bx, ext(), proof)), Seq(candidate(V1M, 1, regs = Map(ErgoBox.R4 -> IntConstant(0))))))
      val (from, to) = (b("0008d3" + "010001" + "0400"), b("0008d3" + "010001" + r4))
      val at = plain.indexOfSlice(from)
      require(at >= 0 && plain.indexOfSlice(from, at + 1) < 0, "output 0's R4 must occur once")
      plain.take(at) ++ to ++ plain.drop(at + from.length)
    }
    /** Signed as the node's message: the transaction read under a v4 block's context (3, 3). */
    def signed(i: Int, bx: ErgoBox, r4: String): String = {
      val msg = VersionContext.withVersions(3, 3) {
        ErgoLikeTransaction.serializer.fromBytes(build(bx, r4, Array.emptyByteArray)).messageToSign
      }
      require(hex(msg).contains(x15), "the message must keep the Upcast")
      hex(build(bx, r4, AuthoredTxSizedTreeRequests.schnorr(msg, s"santa:evaluated-values:nonce:$i")))
    }
    val boxOf = (label: String, t: ErgoTree) => VersionContext.withVersions(V3, V3) { box(label, V1M, 1, tree = t) }
    val strippedBox = boxOf("santa:ev:x15-stripped", script(15, stripped))
    val keptBox = boxOf("santa:ev:x15-kept", script(17, kept))
    val c2Box = boxOf("santa:ev:c2-out", True)
    val reads = "The node reads a v4 block's transactions under (3, 3) (BlockTransactions.scala:184-202) and computes the " +
      "tx id there, at parse (ErgoTransaction.scala:68): its message keeps the Upcast. It writes output 0 when " +
      "ErgoBox.bytes is first read, in verifyOutput's size checks (ErgoTransaction.scala:163-176), which run before any " +
      "input script and outside any version context: the default (1, 1), where an Upcast of a constant is written as " +
      "the constant (ValueSerializer.scala:157-169). So OUTPUTS(0).bytes, output 0's id and the bytes the state stores " +
      "hold 86 02 04 02 04 02."
    Seq(
      entryTx(44, "x15-output-bytes-stripped-accept",
        "Output 0 (value 1000000, SigmaProp(true), height 1) carries R4 = Tuple(1, Upcast(1, Long)) (86 02 04 02 7e 04 " +
        s"02 05). $reads The spent box's script is proveDlog(pk) && OUTPUTS(0).bytes.slice(3, 15) == 00 08 d3 01 00 01 " +
        "86 02 04 02 04 02, and the input carries a Schnorr proof over the message that keeps the Upcast. Valid.",
        signed(44, strippedBox, x15), strippedBox, want = true, because = ""),
      entryTx(45, "x15-output-bytes-kept-reject",
        "The same transaction, but the script expects OUTPUTS(0).bytes.slice(3, 17) to hold the Upcast (00 08 d3 01 00 " +
        "01 86 02 04 02 7e 04 02 05), with the same kind of valid signature. Invalid. An impl that writes the output " +
        "as received accepts.",
        signed(45, keptBox, x15), keptBox, want = false, because = "Success((false,"),
      entryTx(46, "c2-twin-output-register-reject",
        "Output 0's R4 is an empty Coll[Int => Int] (83 00 70 01 04 04 00), which parses at (3, 3); the spent box is " +
        "sigmaProp(true). verifyOutput then writes output 0 at the default (1, 1), where TypeSerializer has no case for " +
        "a function type (TypeSerializer.scala:111): a MatchError, which fails the block (UtxoState.scala:138-139) and " +
        "invalidates the transaction in the mempool (UtxoStateReader.scala:54-60). Invalid.",
        hex(build(c2Box, "83007001040400", Array.emptyByteArray)), c2Box, want = false, because = "MatchError"))
  }

  /** sigma-rust's follow-ups: where a Tuple node's value fails (T), collections of Tuple nodes and a function element
    * type (C), deserializing these kinds (D), and SContext method 11. */
  private def followUpEntries(note: String, True: ErgoTree, v10: ErgoTree, tup12: EvaluatedValue[_ <: SType],
                              pair12: EvaluatedValue[_ <: SType]): Seq[Json] = {
    val pairT = STuple(SInt, SInt)
    val (node, const) = ("0100" + "860204020404", "0100" + "580204")
    val nodeIs = "Input 0's extension is {0: Tuple(1, 2)} as a Tuple node (86 02 04 02 04 04), whose value is a Coll " +
      "typed as a pair."
    val constIs = "The twin: {0: (1, 2)} as the (Int, Int) constant (58 02 04), a real pair."
    val unchecked = "GetVar, ExtractRegisterAs and OptionGet pass the value on unchecked (transformers.scala:491-494, " +
      ":579-582, :602-605); the first check is at the node that consumes it."
    val checked = "Value.checkType (values.scala:251-260) throws 'Invalid type returned by evaluator'"
    // (slug, tree, node verdict, node reason, description of the script and the node verdict)
    val extRows = Seq(
      ("t1-getvar-isdefined", "00d1e6e30058", true, "",
        s"The script is sigmaProp(getVar[(Int, Int)](0).isDefined). $unchecked isDefined reads nothing: valid."),
      ("t2-getvar-get-neq", "00d194e4e30058580000", false, "Invalid type returned by evaluator",
        s"The script is sigmaProp(getVar[(Int, Int)](0).get != (0, 0)). NEQ checks both operands (trees.scala:1226-1228): " +
        s"$checked on the Coll. Invalid."),
      ("t3-getvar-map-lambda", "00d193e4dc2407e3005801d901015804020402", false, "InvocationTargetException",
        "The script is sigmaProp(getVar[(Int, Int)](0).map({ (p: (Int, Int)) => 1 }).get == 1). A lambda checks its " +
        "argument (values.scala:1074), and Option.map calls it by reflection, so the type error surfaces as an " +
        "InvocationTargetException. Invalid."),
      ("t4-getvar-eq-getvar", "00d193e30058e30058", true, "",
        "The script is sigmaProp(getVar[(Int, Int)](0) == getVar[(Int, Int)](0)). EQ checks both operands, but " +
        "isValueOfType checks only an Option's outer class (SType.scala:199), and the two options compare equal: valid."),
      ("t5-coll-of-getvar-get", "00d193b1830158e4e300580402", false, "Invalid type returned by evaluator",
        "The script is sigmaProp(Coll(getVar[(Int, Int)](0).get).size == 1). A ConcreteCollection checks each item " +
        s"(values.scala:894): $checked. Invalid."),
      ("t8-getvarfrominput-isdefined", "0b0cd1e6dc650cfe020300020058", true, "",
        "The script, a v3 tree, is sigmaProp(CONTEXT.getVarFromInput[(Int, Int)](0, 0).isDefined), input 0 being SELF. " +
        "getVarFromInput returns Some(v.value) when the RType matches (CContext.scala:76-82): valid."))
    val extEntries = extRows.zipWithIndex.flatMap { case ((slug, treeHex, nodeValid, why, desc), i) =>
      Seq(
        entry(15 + 2 * i, s"$slug-node-${if (nodeValid) "accept" else "reject"}", s"$nodeIs $desc $note",
          spent(s"santa:ev:$slug:node", tree(treeHex)), node, want = nodeValid, because = why),
        entry(16 + 2 * i, s"$slug-constant-accept", s"$constIs The same script: valid. $note",
          spent(s"santa:ev:$slug:const", tree(treeHex)), const, want = true))
    }
    val regRows = Seq(
      ("t6-r4-isdefined", "00d1e6c6a70458", true, "",
        s"The script is sigmaProp(SELF.R4[(Int, Int)].isDefined). $unchecked Valid."),
      ("t7-r4-get-neq", "00d194e4c6a70458580000", false, "Invalid type returned by evaluator",
        s"The script is sigmaProp(SELF.R4[(Int, Int)].get != (0, 0)). NEQ checks its operands: $checked. Invalid."))
    val regEntries = regRows.zipWithIndex.flatMap { case ((slug, treeHex, nodeValid, why, desc), i) =>
      Seq(
        entry(27 + 2 * i, s"$slug-node-${if (nodeValid) "accept" else "reject"}",
          s"The spent box's R4 is the Tuple node (1, 2). $desc $note",
          spent(s"santa:ev:$slug:node", tree(treeHex), regs = Map(ErgoBox.R4 -> tup12)), "00", want = nodeValid,
          because = why),
        entry(28 + 2 * i, s"$slug-constant-accept", s"The twin: R4 is the (Int, Int) constant (1, 2). Valid. $note",
          spent(s"santa:ev:$slug:const", tree(treeHex), regs = Map(ErgoBox.R4 -> pair12)), "00", want = true))
    }
    val c1 = "830158860204020404"
    val c2 = "8300700204040400"
    val collOfTuple: EvaluatedValue[_ <: SType] = ConcreteCollection[STuple](Seq(Tuple(IntConstant(1), IntConstant(2))), pairT)
    val collBytes: EvaluatedValue[_ <: SType] = ConcreteCollection[SByte.type](Seq(ByteConstant(1), ByteConstant(1)), SByte)
    val deserReg = tree("00d1d5040100") // sigmaProp(DeserializeRegister(R4, Boolean))
    val collEntries = Seq(
      entry(31, "c1-ext-coll-of-tuple-node-reject",
        s"Input 0's extension is {0: a Coll[(Int, Int)] holding the Tuple node (1, 2)} ($c1), and the spent tree is " +
        "sigmaProp(true), evaluated. toSigmaContext converts it: ConcreteCollection.value copies each item's value into " +
        "an array of the element's class (values.scala:882-885), a Tuple2 array, and storing the Coll throws " +
        s"ArrayStoreException. Invalid. $note",
        spent("santa:ev:c1", True), "0100" + c1, want = false, because = "ArrayStoreException"),
      entry(32, "c1-ext-sigmaprop-root-accept",
        s"The same extension, spending a SigmaPropConstant root (00 08 d3): nothing converts. Valid. $note",
        spent("santa:ev:c1-root", tree("0008d3")), "0100" + c1, want = true),
      entry(33, "c1-r4-read-r5-reject",
        "The spent box's R4 is the same Coll[(Int, Int)] and R5 is Int 1; the script is sigmaProp(SELF.R5[Int].get == 1). " +
        s"The first register read converts R4 too (CBox.scala:77-94): ArrayStoreException. Invalid. $note",
        spent("santa:ev:c1-reg", v10, regs = Map(ErgoBox.R4 -> collOfTuple, ErgoBox.R5 -> IntConstant(1))), "00",
        want = false, because = "ArrayStoreException"),
      entry(34, "c2-ext-coll-of-func-reject",
        s"Input 0's extension is {0: an empty Coll of (Int, Int) => Int} ($c2), which parses at tree v3; the spent tree " +
        "is sigmaProp(true), evaluated. stypeToRType has no case for a function of two arguments " +
        "(Evaluation.scala:51-55), so the conversion throws and the input fails. ergo-core then logs the failed input's " +
        "context as JSON (ErgoTransaction.scala:139-146), re-serializing the extension under the ambient context, the " +
        "default (1, 1) in block validation, where TypeSerializer cannot write a function type (TypeSerializer.scala:111): " +
        "a MatchError. Block validation runs inside Try.flatMap (UtxoState.scala:138-139), so the block fails. Invalid. " +
        note, spent("santa:ev:c2", True), "0100" + c2, want = false, because = "MatchError"),
      entry(35, "c2-ext-coll-of-func-one-arg-accept",
        s"The twin: {0: an empty Coll of Int => Int} (83 00 70 01 04 04 00): stypeToRType converts a one-argument " +
        s"function type. Valid. $note",
        spent("santa:ev:c2-twin", True), "0100" + "83007001040400", want = true),
      entry(36, "c2-ext-sigmaprop-root-accept",
        s"The C2 extension, spending a SigmaPropConstant root: nothing converts, nothing fails. Valid. $note",
        spent("santa:ev:c2-root", tree("0008d3")), "0100" + c2, want = true))
    val deserEntries = Seq(
      entry(37, "d1-r4-coll-byte-node-deserialize-accept",
        "The spent box's R4 is Coll[Byte](1, 1) as a ConcreteCollection node (83 02 02 02 01 02 01); the tree is " +
        "sigmaProp(DeserializeRegister(R4, Boolean)). DeserializeRegister matches any register value and reads " +
        ".value.toArray (ErgoLikeInterpreter.scala:17-36): the bytes 01 01, the constant true. Valid. " + note,
        spent("santa:ev:d1", deserReg, regs = Map(ErgoBox.R4 -> collBytes)), "00", want = true),
      entry(38, "d1-r4-coll-byte-constant-deserialize-accept",
        s"The twin: R4 is the constant Coll[Byte](1, 1) (0e 02 01 01). Valid. $note",
        spent("santa:ev:d1-twin", deserReg, regs = Map(ErgoBox.R4 -> ByteArrayConstant(Array[Byte](1, 1)))), "00",
        want = true),
      entry(39, "d2-r4-group-generator-deserialize-reject",
        "R4 is GroupGenerator, the same tree. Its value is a GroupElement, so the substitution fails, and the " +
        "DeserializeRegister node is left in the tree and evaluated, which throws ('Should be overriden in class " +
        s"sigma.ast.DeserializeRegister'). Invalid. $note",
        spent("santa:ev:d2", deserReg, regs = Map(ErgoBox.R4 -> GroupGenerator)), "00", want = false,
        because = "Should be overriden"),
      entry(40, "d3-ext-coll-byte-node-execute-accept",
        "Input 0's extension is {0: Coll[Byte](1, 1) as a node}; the tree is sigmaProp(executeFromVar[Boolean](0)). " +
        "DeserializeContext takes a value of type Coll[Byte] and reads .value (Interpreter.scala:110-126): true. " +
        s"Valid. $note",
        spent("santa:ev:d3", tree("00d1d40100")), "0100" + "83020202010201", want = true),
      entry(41, "d4-ext-execute-then-convert-reject",
        "Input 0's extension is {0: Tuple(1, HEIGHT), 1: Coll[Byte] 08 d3}; the tree is executeFromVar[SigmaProp](1), " +
        "which deserializes to TrueProp. After the substitution the JVM still evaluates through CErgoTreeEvaluator.eval " +
        "(Interpreter.scala:171-177), which converts every extension value, and var 0 fails Tuple.value's assert. " +
        s"Invalid. $note",
        spent("santa:ev:d4", tree("00d40801")), "02" + "00" + "86020402a3" + "01" + "0e0208d3", want = false,
        because = "AssertionError"),
      entry(42, "d4-ext-execute-accept",
        s"The twin: the same without var 0. Valid. $note",
        spent("santa:ev:d4-twin", tree("00d40801")), "01" + "01" + "0e0208d3", want = true),
      entry(43, "context-getvar-v5-method-reject",
        "The spent tree is sigmaProp(MethodCall(CONTEXT, SContext method 11, [Byte 0]).isDefined), which parses " +
        "(wire: tree_parse_acceptance #23). Method 11 has no Java method (methods.scala:1750-1753), so evaluating it " +
        s"throws NoSuchMethodException for Context.getVar(byte). Invalid. $note",
        spent("santa:ev:m11", tree("00d1e6dc650bfe010200")), "0100" + "0402", want = false,
        because = "NoSuchMethodException"))
    extEntries ++ regEntries ++ collEntries ++ deserEntries
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
