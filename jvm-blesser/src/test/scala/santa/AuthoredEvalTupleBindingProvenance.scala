package santa

// Authored eval vectors: where a non-pair tuple — or a collection of them — CAME FROM decides a
// verdict, and the binding now says where (the `{"kind":"Value"}` bytes form, runner contract §4).
// Measured in santa.spike.TupleProvenanceProbe through EvalCore's real binding path; every number
// re-derives the hand-built TupleValuesProbe's exactly (G1 39, getVarFromInput 54, G2/G3 89, the
// register collection 124, V2 81 / V3 119, V4 146).
//
// THE TWO PRODUCERS. A node reads a register or an extension value with `getValue`
// (ErgoBoxCandidate.scala:231, ContextExtension.scala:61 at v6.0.7), which takes a CONSTANT or an
// evaluated EXPRESSION. For the same declared type they build different values:
//   * a non-pair tuple: the constant's data decoder and a `Tuple` expression's `.value` both give a
//     Coll[Any] (values.scala:818-822) — so X1-X3 answer exactly as their constant twins;
//   * a collection of non-pair tuples: the data decoder gives item type Coll[Any]
//     (CoreDataSerializer.deserializeColl), a `ConcreteCollection` expression's `.value` gives
//     stypeToRType of the element — the TUPLE type (values.scala:879-884). Read as a binding, the
//     expression is never `checkType`d, so it does NOT throw the way the same collection built
//     in-script does (Tuple.non_pair_value_gates G10: `Unsupported tuple type`).
// The item type is visible to a comparison: two collections of the same declared type pass the
// builder's SameType check and the comparer bails on differing item types before any item (X5).
// And the deserializer is not consistent with itself: a non-pair tuple NESTED in a pair element gets
// stypeToRType's TupleType, not Coll[Any], so against a pair constant the order inverts (C6/X6).
//
// One op per schema: var 1 (santa-eval/v2), a per-input var (v3), SELF's register (v4). Every tree
// and every binding's bytes are written by the JVM's own serializers from the AST below.

import io.circe.Json
import scorex.util.encode.Base16

import org.ergoplatform.ErgoBox
import sigma.{Colls, VersionContext}
import sigma.ast._
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalTupleBindingProvenance {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source = "santa:authored-eval-tuple-binding-provenance"
  val OpVar = "Tuple.binding_provenance_var"
  val OpInputVar = "Tuple.binding_provenance_input_var"
  val OpRegister = "Tuple.binding_provenance_register"
  val Stamp = "jvm:sigma-state-6.0.7"

  private def vc[T](f: => T): T = VersionContext.withVersions(V3, V3)(f)
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def mkConst[S <: SType](value: Any, tpe: S): Constant[S] =
    Constant(value.asInstanceOf[S#WrappedType], tpe)

  val Tt = STuple(SInt, SInt, SInt)
  /** The tuple (1, 2, 3) as a CONSTANT: its items as a Coll[Any], what the data decoder builds. */
  val TupleConst: Constant[STuple] = mkConst[STuple](Colls.fromArray[Any](Array[Any](1, 2, 3))(sigma.AnyType), Tt)
  /** The same tuple as an evaluated EXPRESSION (opcode 0x86). */
  val TupleExpr: Value[SType] = any(Tuple(IndexedSeq[Value[SType]](IntConstant(1), IntConstant(2), IntConstant(3))))
  /** Coll((1, 2, 3)) as a DATA constant: item type Coll[Any], as CoreDataSerializer builds it. */
  val CollData: Constant[SCollection[SType]] = mkConst[SCollection[SType]](
    { implicit val rt: sigma.data.RType[sigma.Coll[Any]] = sigma.collRType(sigma.AnyType)
      Colls.fromArray[sigma.Coll[Any]](Array(TupleConst.value.asInstanceOf[sigma.Coll[Any]])) },
    SCollection(Tt))
  /** Coll((1, 2, 3)) as a ConcreteCollection EXPRESSION (opcode 0x83). */
  val CollExpr: Value[SType] = any(ConcreteCollection(Array[Value[SType]](TupleConst), Tt))

  /** A binding's bytes, as the JVM's value writer puts them in a register or an extension. */
  def bytesHex(v: Value[_ <: SType]): String = vc {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(any(v), w); Base16.encode(w.toBytes)
  }
  def binding(v: Value[_ <: SType]): Json =
    Json.obj("kind" -> Json.fromString("Value"), "bytes_hex" -> Json.fromString(bytesHex(v)))
  /** A size-flagged v3 tree with its constants INLINE (header 0x0b, as every number here was measured;
    * segregating them would change the costs): the header, the body's size as a VLQ, the body. */
  private[santa] def tree(root: Value[SType]): String = vc {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
    val body = { val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(root, w); w.toBytes }
    val size = { val w = SigmaSerializer.startWriter(); w.putUInt(body.length.toLong); w.toBytes }
    "%02x".format(header: Byte) + Base16.encode(size) + Base16.encode(body)
  }
  private def asColl(v: Value[_ <: SType]): Value[SCollection[SType]] = v.asInstanceOf[Value[SCollection[SType]]]
  private def asOpt(v: Value[_ <: SType]): Value[SOption[SType]] = v.asInstanceOf[Value[SOption[SType]]]

  /** `getVar[(Int, Int, Int)](1).get.size` */
  lazy val TreeVar: String = tree(any(SizeOf(asColl(OptionGet(asOpt(GetVar(1.toByte, Tt)))))))
  /** `getVarFromInput[(Int, Int, Int)](0, 1).get.size` (SContext method 12) */
  lazy val TreeInputVar: String = vc {
    val m0 = SMethod.fromIds(101.toByte, 12.toByte)
    val tv = m0.stype.tpeParams.head.ident
    tree(any(SizeOf(asColl(OptionGet(asOpt(MethodCall(Context, m0.withConcreteTypes(Map(tv -> Tt)),
      IndexedSeq[Value[SType]](ShortConstant(0), ByteConstant(1)), Map(tv -> Tt))))))))
  }
  /** `SELF.R4[(Int, Int, Int)].get.size` */
  lazy val TreeRegTuple: String =
    tree(any(SizeOf(asColl(OptionGet(asOpt(ExtractRegisterAs(Self, ErgoBox.R4, SOption(Tt))))))))
  private def regColl: Value[SCollection[SType]] =
    asColl(OptionGet(asOpt(ExtractRegisterAs(Self, ErgoBox.R4, SOption(SCollection(Tt))))))
  /** `SELF.R4[Coll[(Int, Int, Int)]].get(0).size` */
  lazy val TreeRegColl: String = tree(any(SizeOf(asColl(ByIndex(regColl, IntConstant(0), None)))))
  /** `SELF.R4[Coll[(Int, Int, Int)]].get == Coll((1, 2, 3))`, the right side a constant in the tree */
  lazy val TreeRegEq: String = tree(any(EQ(any(regColl), any(CollData))))
  /** `SELF.R4[Coll[(Int, Int, Int)]].get.zip(Coll(4)) == Coll(((1, 2, 3), 4))` */
  lazy val TreeRegZipEq: String = vc {
    val zip = SMethod.fromIds(12.toByte, 29.toByte)
    val zipped = MethodCall(regColl, zip.specializeFor(SCollection(Tt), IndexedSeq(SCollection(SInt))),
      IndexedSeq[Value[SType]](IntArrayConstant(Array(4))), Map())
    val pairs = mkConst[SCollection[SType]](
      { implicit val rt: sigma.data.RType[(sigma.Coll[Any], Int)] =
          sigma.data.RType.pairRType(sigma.collRType(sigma.AnyType), sigma.IntType)
        Colls.fromArray[(sigma.Coll[Any], Int)](Array((TupleConst.value.asInstanceOf[sigma.Coll[Any]], 4))) },
      SCollection(STuple(Tt, SInt)))
    tree(any(EQ(any(zipped), any(pairs))))
  }

  private val int0 = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def stamped(j: Json): Json = j.mapObject(_.add("blessed_by", Json.fromString(Stamp)))

  def extract(): Map[String, Json] = {
    val sameAsConstant = "identical to its constant twin: `Tuple.value` is the same Coll[Any] the data decoder " +
      "builds (values.scala:818-822)"
    val varEntries = Seq(
      SpecExtract.authoredEntryV(OpVar,
        "[C1] `getVar[(Int, Int, Int)](1).get.size`, var 1 = the tuple CONSTANT (1, 2, 3) — `3` at cost 39. " +
        "CONTROL for X1: a constant reads the same in every reader. (TupleValuesProbe G1.)",
        TreeVar, "getvar-tuple-constant-accept#0", binding(TupleConst), V3, ergoTree = 3),
      SpecExtract.authoredEntryV(OpVar,
        "[X1] the same tree, var 1 = the Tuple EXPRESSION (opcode 0x86) — `3` at 39, " + sameAsConstant + ". " +
        "SEPARATING: a node's extension reader is `getValue` (ContextExtension.scala:61), which takes an " +
        "evaluated expression; a reader that reads only a constant there refuses it.",
        TreeVar, "getvar-tuple-expression-accept#1", binding(TupleExpr), V3, ergoTree = 3))
    val inputEntries = Seq(
      SpecExtract.authoredV3Entry(OpInputVar,
        "[C2] `getVarFromInput[(Int, Int, Int)](0, 1).get.size`, input 0's var 1 = the tuple CONSTANT — `3` " +
        "at cost 54. CONTROL for X2. (TupleValuesProbe's getVarFromInput row.)",
        TreeInputVar, "getvarfrominput-tuple-constant-accept#0", Seq(Map(1.toByte -> binding(TupleConst))), V3),
      SpecExtract.authoredV3Entry(OpInputVar,
        "[X2] the same, input 0's var 1 = the Tuple EXPRESSION — `3` at 54, " + sameAsConstant + ". " +
        "SEPARATING: the per-input extension is read by the same `getValue`.",
        TreeInputVar, "getvarfrominput-tuple-expression-accept#1", Seq(Map(1.toByte -> binding(TupleExpr))), V3))
    def reg(id: String, script: String, tree: String, name: String, r4: Value[_ <: SType]): Json =
      SpecExtract.authoredV4Entry(OpRegister, s"[$id] $script", tree, name, Map(4 -> binding(r4)), int0, V3)
    val registerEntries = Seq(
      reg("C3", "`SELF.R4[(Int, Int, Int)].get.size`, R4 = the tuple CONSTANT — `3` at cost 89. CONTROL for " +
        "X3. (TupleValuesProbe G2.)", TreeRegTuple, "register-tuple-constant-accept#0", TupleConst),
      reg("X3", "the same, R4 = the Tuple EXPRESSION — `3` at 89, " + sameAsConstant + ". SEPARATING: a " +
        "register is read with `getValue` too (ErgoBoxCandidate.scala:231). (G3.)",
        TreeRegTuple, "register-tuple-expression-accept#1", TupleExpr),
      reg("C4", "`SELF.R4[Coll[(Int, Int, Int)]].get(0).size`, R4 = the DATA constant Coll((1, 2, 3)) — `3` at " +
        "cost 124. The data decoder gives a collection of non-pair tuples the item type Coll[Any] " +
        "(CoreDataSerializer.deserializeColl); the SAME collection built in-script throws " +
        "`Unsupported tuple type` (Tuple.non_pair_value_gates G10). CONTROL for X4.",
        TreeRegColl, "register-coll-data-accept#2", CollData),
      reg("X4", "the same, R4 = a ConcreteCollection EXPRESSION of the tuple (opcode 0x83) — `3` at 124. Read " +
        "as a binding, the expression's `.value` is built without the in-script `checkType` " +
        "(values.scala:879-884), so it does not throw like G10, though its item type is the TUPLE type " +
        "(stypeToRType), not Coll[Any]. SEPARATING by provenance.",
        TreeRegColl, "register-coll-expression-accept#3", CollExpr),
      reg("C5", "`SELF.R4[Coll[(Int, Int, Int)]].get == Coll((1, 2, 3))` (a constant in the tree), R4 = the " +
        "DATA constant — `true` at cost 119: both sides carry item type Coll[Any], so the comparison " +
        "reaches the items. CONTROL for X5. (TupleValuesProbe V3.)",
        TreeRegEq, "register-coll-data-eq-accept#4", CollData),
      reg("X5", "the same comparison, R4 = the ConcreteCollection EXPRESSION — `false` at 81. Same declared " +
        "type, so the builder's SameType check passes, but item type TupleType against Coll[Any], and the " +
        "comparer bails on that before any item (38 cheaper than C5). SEPARATING: an implementation that " +
        "does not keep a collection's producer answers `true`. (V2.)",
        TreeRegEq, "register-coll-expression-eq-accept#5", CollExpr),
      reg("C6", "`SELF.R4[Coll[(Int, Int, Int)]].get.zip(Coll(4)) == Coll(((1, 2, 3), 4))`, R4 = the DATA " +
        "constant — `false` at cost 101. THE ORDER INVERTS: the tree's pair constant gets its nested " +
        "tuple's item type from stypeToRType — Pair(TupleType, Int) — because the decoder's Coll[Any] " +
        "case covers only a non-pair tuple ELEMENT, so the data-built register (Pair(Coll[Any], Int) " +
        "once zipped) is the one that differs. CONTROL for X6.",
        TreeRegZipEq, "register-coll-data-zip-eq-accept#6", CollData),
      reg("X6", "the same, R4 = the ConcreteCollection EXPRESSION — `true` at 146: zipped, its item type is " +
        "Pair(TupleType, Int), the tree constant's, so the comparison reaches the items. SEPARATING: the " +
        "producer decides the verdict in the opposite direction to X5. (V4.)",
        TreeRegZipEq, "register-coll-expression-zip-eq-accept#7", CollExpr))
    Map(
      OpVar -> stamped(SpecExtract.authoredEnvelope(OpVar, varEntries, Source)),
      OpInputVar -> stamped(SpecExtract.authoredV3Envelope(OpInputVar, inputEntries, Source)),
      OpRegister -> stamped(SpecExtract.authoredV4Envelope(OpRegister, registerEntries, Source)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalTupleBindingProvenance", extract(), outDir)
}
