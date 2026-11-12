package santa

import scorex.util.bytesToId
import scorex.util.encode.Base16

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.R4

import sigma.{Colls, VersionContext}
import sigma.ast._
import sigma.ast.ErgoTree.ZeroHeader
import sigma.data.AvlTreeData
import sigma.interpreter.ContextExtension
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}

/** A value a script reads from a register or a context variable must be the value a NODE's reader
  * builds from its bytes, not the one `decodeInputConstant` builds from JSON. The two differ in
  * runtime class for `Coll[Short|Int|Long]` — `Colls.fromArray` is `@specialized`, the data
  * deserializer's generic branch is not — and a script can see the class. Pinned per context
  * builder: a builder that skipped the round trip would bind the hand-built value, and no vector
  * would notice until one happened to dispatch on the receiver's class. */
class BindingOffTheWireTest extends munit.FunSuite {

  private val A: Byte = 3

  private def json(s: String): io.circe.Json = io.circe.parser.parse(s).toOption.get

  private val collIntJson = json(
    """{"kind":"Coll","elem":{"tag":"SInt"},"items":[{"kind":"Int","value":1},{"kind":"Int","value":2}]}""")
  private def collInt: EvaluatedValue[_ <: SType] = EvalCore.decodeInputConstant(collIntJson)

  // any tree will do for the builders; this one is getVarFromInput[Boolean](0,11)
  private val tree: ErgoTree =
    sigma.santa.LenientErgoTree.deserialize(Base16.decode("1b0f020300020bdc650cfe027300730101").get)

  /** The reader's class, exactly: `CollOverArray$mcI$sp` (a subclass) is the hand-built shape. */
  private def assertReaderShape(v: Any, where: String): Unit =
    assertEquals(v.getClass.getName, "sigma.data.CollOverArray", s"$where bound the wrong shape")

  test("var 1 (evalApplied's context) binds the reader's Coll[Int]") {
    val ctx = EvalCore.contextWithVar1(tree, A, collInt)
    assertReaderShape(ctx.extension.values(1.toByte).value, "var 1")
  }

  test("SELF's registers and var 1 bind the reader's Coll[Int]") {
    val ctx = EvalCore.contextWithSelfRegistersAndVar1(tree, A, Map(R4 -> collInt), collInt)
    assertReaderShape(ctx.boxesToSpend(0).additionalRegisters(R4).value, "SELF.R4")
    assertReaderShape(ctx.extension.values(1.toByte).value, "var 1")
  }

  test("per-input extensions bind the reader's Coll[Int]") {
    val ctx = EvalCore.contextWithInputExtensions(tree, A, Seq(Map(1.toByte -> collInt)))
    assertReaderShape(ctx.spendingTransaction.inputs(0).extension.values(1.toByte).value, "input 0, var 1")
  }

  test("the top-level extension binds the reader's Coll[Int]") {
    val ctx = EvalCore.contextWithTopExtension(tree, A, Map(1.toByte -> collInt))
    assertReaderShape(ctx.extension.values(1.toByte).value, "var 1")
  }

  test("the full context's input extensions bind the reader's Coll[Int]") {
    val self = new ErgoBox(1000000L, tree, Colls.emptyColl, Map(), bytesToId(Array.fill(32)(0: Byte)), 0.toShort, 0)
    val ctx = EvalCore.fullContext(tree, A, 0, IndexedSeq(self), IndexedSeq.empty, IndexedSeq.empty,
      Colls.emptyColl, EvalCore.dummyPreHeader(0, A), AvlTreeData.dummy,
      Seq(ContextExtension(Map(1.toByte -> collInt))))
    assertReaderShape(ctx.extension.values(1.toByte).value, "SELF's var 1")
    assertReaderShape(ctx.spendingTransaction.inputs(0).extension.values(1.toByte).value, "input 0, var 1")
  }

  test("Coll[Short] and Coll[Long] bind the reader's class too: the gap is all three widths") {
    Seq(
      "Short" -> """{"kind":"Coll","elem":{"tag":"SShort"},"items":[{"kind":"Short","value":1}]}""",
      "Long"  -> """{"kind":"Coll","elem":{"tag":"SLong"},"items":[{"kind":"Long","value":"1"}]}"""
    ).foreach { case (what, j) =>
      val ctx = EvalCore.contextWithVar1(tree, A, EvalCore.decodeInputConstant(json(j)))
      assertReaderShape(ctx.extension.values(1.toByte).value, s"var 1 = Coll[$what]")
    }
  }

  // The tuple-input family's register half rests on this: CoreDataSerializer.deserializeColl gives a
  // collection of NON-pair tuples the item type Coll[Any] (its `case _: STuple` arm).
  test("a Coll of 3-tuples binds with the reader's item type, Coll[Any]") {
    val c = EvalCore.decodeInputConstant(json(
      """{"kind":"Coll","elem":{"tag":"STuple","items":[{"tag":"SInt"},{"tag":"SInt"},{"tag":"SInt"}]},""" +
      """"items":[{"kind":"Tuple","items":[{"kind":"Int","value":1},{"kind":"Int","value":2},{"kind":"Int","value":3}]}]}"""))
    val bound = EvalCore.contextWithVar1(tree, A, c).extension.values(1.toByte).value.asInstanceOf[sigma.Coll[Any]]
    assertEquals[Any, Any](bound.tItem, sigma.collRType(sigma.AnyType))
    assertEquals(bound(0).asInstanceOf[sigma.Coll[Any]].toArray.toList, List(1, 2, 3))
  }

  // getOrElse(5, true) on a Coll[Int]: the default is mistyped, and a SPECIALISED receiver unboxes it
  // to an Int (and throws) where the reader's generic class hands it back. A tree CONSTANT comes off
  // the wire, so it is the node-faithful reference the bound value must answer like.
  test("a script sees it: a bound Coll[Int] answers a mistyped getOrElse as a tree constant does") {
    def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
    def hexOf(obj: Value[_ <: SType]): String = {
      val root = MethodCall(any(obj), SCollectionMethods.getMethodByName("getOrElse"),
        IndexedSeq(any(IntConstant(5)), any(TrueLeaf)), Map.empty)
      Base16.encode(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(new ErgoTree(
        ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, A)), IndexedSeq.empty,
        Right(root.asInstanceOf[sigma.ast.syntax.SigmaPropValue]))))
    }
    val fromConstant = EvalCore.evalEntry(hexOf(IntArrayConstant(Array(1, 2))), A)._2.map(_._1)
    val fromVar1 = EvalCore.evalApplied(
      hexOf(OptionGet(GetVar(1.toByte, SCollection(SInt)).asInstanceOf[Value[SOption[SCollection[SInt.type]]]])),
      collIntJson, A)._2.map(_._1)
    assertEquals(fromConstant, Right(json("""{"kind":"Boolean","value":true}""")), "the reference itself moved")
    assertEquals(fromVar1, fromConstant)
  }

  // ── the Value kind: a binding as the bytes a node reads ──────────────────────────────────

  private def anyV(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def valueHex(v: Value[_ <: SType]): String = VersionContext.withVersions(3, 3) {
    val w = SigmaSerializer.startWriter()
    w.putValue(anyV(v))
    Base16.encode(w.toBytes)
  }
  private def valueJson(hex: String): io.circe.Json = json(s"""{"kind":"Value","bytes_hex":"$hex"}""")

  test("a Value binding of a constant binds exactly what the JSON form of the same value binds") {
    val viaValue = EvalCore.contextWithVar1(tree, A,
      EvalCore.decodeBinding(valueJson(valueHex(IntArrayConstant(Array(1, 2)))))).extension.values(1.toByte)
    val viaJson = EvalCore.contextWithVar1(tree, A, collInt).extension.values(1.toByte)
    assertEquals(viaValue, viaJson)
    assertReaderShape(viaValue.value, "var 1 from Value bytes")
  }

  test("a Value binding may be an evaluated EXPRESSION, as a register or extension value can be") {
    val tup = Tuple(IndexedSeq(IntConstant(1), IntConstant(2), IntConstant(3)).map(anyV))
    val b = EvalCore.decodeBinding(valueJson(valueHex(tup)))
    assert(!b.isInstanceOf[Constant[_]], s"getValue should hand back the Tuple node, got ${b.getClass}")
    assertEquals[SType, SType](b.tpe, STuple(IndexedSeq(SInt, SInt, SInt)))
    assertEquals[SType, SType](EvalCore.contextWithVar1(tree, A, b).extension.values(1.toByte).tpe, b.tpe)
  }

  test("a Value binding a node would refuse is refused: not an EvaluatedValue, or bytes left over") {
    intercept[ClassCastException](EvalCore.decodeBinding(valueJson(
      valueHex(ArithOp(IntConstant(1), IntConstant(2), sigma.serialization.OpCodes.PlusCode)))))
    val e = intercept[RuntimeException](EvalCore.decodeBinding(valueJson(valueHex(IntConstant(7)) + "00")))
    assert(e.getMessage.contains("left after the value"), e.getMessage)
  }

  // Rule 1019 (CheckV6Type) is part of BOTH of a node's readers, at every version — so it applies to a
  // Value binding, and deliberately NOT to the JSON forms, which feed exactly these types into var 1.
  test("a Value binding passes rule 1019, as a node's register and extension readers do; JSON does not") {
    val ubi = UnsignedBigIntConstant(java.math.BigInteger.ONE)
    intercept[sigma.validation.ValidationException](EvalCore.decodeBinding(valueJson(valueHex(ubi))))
    val inTuple = Tuple(IndexedSeq(IntConstant(1), ubi).map(anyV))
    intercept[sigma.validation.ValidationException](EvalCore.decodeBinding(valueJson(valueHex(inTuple))))
    val viaJson = EvalCore.decodeBinding(json("""{"kind":"UnsignedBigInt","value":"1"}"""))
    EvalCore.contextWithVar1(tree, 3, viaJson) // the harness feed still binds one
  }

  // The version a Value is read under is the entry's: an empty Coll[Int => Int] has a function type,
  // which the type reader takes from v3 only.
  test("a Value binding is read under the entry's activated version") {
    val fnColl = ConstantNode(Colls.emptyColl[Any](sigma.AnyType).asInstanceOf[SType#WrappedType],
      SCollection(SFunc(IndexedSeq(SInt), SInt))).asInstanceOf[Value[SType]]
    val b = EvalCore.decodeBinding(valueJson(valueHex(fnColl)))
    EvalCore.contextWithVar1(tree, 3, b) // v6 reads it
    intercept[Throwable](EvalCore.contextWithVar1(tree, 2, b)) // v5 refuses it
  }

  // getVarFromInput[Boolean](0,11) over input 0's extension: a readable Value evaluates, an unreadable
  // one is the entry's errored verdict (it used to escape the decode as panicked).
  test("santa-eval/v3: a Value binding is read inside the verdict, and a refused one is errored") {
    def run(bytesHex: String) = santa.runner.Runner.evalEntry("santa-eval/v3", json(
      s"""{"name":"t","tree_bytes_hex":"1b0f020300020bdc650cfe027300730101",""" +
      s""""inputs":[{"extension":{"11":{"kind":"Value","bytes_hex":"$bytesHex"}}}],""" +
      """"version":{"activated":3,"ergoTree":3},"expected":{"value":null,"cost":null,"error":"errored"}}"""))._2
    val readable = run(valueHex(BooleanConstant(true)))
    assertEquals(readable.hcursor.downField("value").focus,
      Some(json("""{"kind":"Option","value":{"kind":"Boolean","value":true}}""")), readable.noSpaces)
    val refused = run(valueHex(BooleanConstant(true)) + "00")
    assertEquals(refused.hcursor.get[String]("error").toOption, Some("errored"), refused.noSpaces)
  }
}
