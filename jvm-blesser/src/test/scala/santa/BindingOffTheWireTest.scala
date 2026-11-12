package santa

import scorex.util.bytesToId
import scorex.util.encode.Base16

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.R4

import sigma.Colls
import sigma.ast._
import sigma.ast.ErgoTree.ZeroHeader
import sigma.data.AvlTreeData
import sigma.interpreter.ContextExtension
import sigma.serialization.ErgoTreeSerializer

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
}
