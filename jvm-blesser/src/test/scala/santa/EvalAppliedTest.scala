package santa

import scorex.util.encode.Base16

import io.circe.Json

import sigma.ast.{
  ErgoTree, GetVar, OptionGet, SBigInt, SBoolean, SByte, SCollection, SGroupElement,
  SInt, SLong, SShort, SType, STuple, Value
}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.crypto.CryptoConstants
import sigma.serialization.GroupElementSerializer

/** TDD tests for EvalCore.evalApplied and the SValue input decoder.
  *
  * The core guard here is the END-TO-END eval-back: for each decoder kind, compile
  * the identity script `{ getVar[T](1).get }` to a serialized ErgoTree, then assert
  * `evalApplied(treeHex, inputJson, 3)` round-trips the input value back through
  * decode -> bind-to-var-1 -> eval -> valueToJson. A wrong decode (e.g. Tuple ->
  * Coll[Any], or a nested-coll with the wrong RType) fails these by construction. */
class EvalAppliedTest extends munit.FunSuite {

  // ── identity-tree builder ──────────────────────────────────────────────────
  // Builds the serialized ErgoTree for `{ getVar[T](1).get }` (== OptionGet(GetVar))
  // directly from the AST and the lenient serializer the spike proved out. Pinned to
  // ErgoTree version 3 (full v6), matching `activated = 3` in the assertions.
  private def idTreeHex(tpe: SType, version: Byte = 3): String = {
    val root: Value[SType] = OptionGet(GetVar(1.toByte, tpe)).asInstanceOf[Value[SType]]
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, version))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }

  private def parse(s: String): Json = io.circe.parser.parse(s).toOption.get

  /** Assert: input JSON decodes, binds to var 1, evals through the identity tree, and
    * `valueToJson` of the result equals the input JSON (a full decode/eval round-trip). */
  private def assertEvalBack(tpe: SType, inputJson: Json): Unit = {
    val treeHex = idTreeHex(tpe)
    EvalCore.evalApplied(treeHex, inputJson, activated = 3.toByte) match {
      case (_, Right((valueJson, cost))) =>
        assertEquals(valueJson.noSpaces, inputJson.noSpaces,
          s"eval-back mismatch for $tpe")
        assert(cost > 0L, s"expected positive cost, got $cost")
      case (_, Left(err)) =>
        fail(s"evalApplied returned Left for $tpe: $err")
    }
  }

  // ── Spike-proven case (snag 6 baseline) ────────────────────────────────────
  // Tree: `{ val func = (x: Byte) => Coll[Byte](x); func(getVar[Byte](1).get) }`
  // Input: Byte(-128)  →  expected value: Coll[Byte](-128), cost: 90
  test("evalApplied: Byte(-128) through serialize-Byte function tree (cost 90)") {
    val treeBytesHex = "1b1200dad9010102dc6a03dd01720101e4e30102"
    val inputJson    = parse("""{"kind":"Byte","value":-128}""")
    val result = EvalCore.evalApplied(treeBytesHex, inputJson, activated = 3.toByte)
    result match {
      case (_, Right((valueJson, cost))) =>
        val expectedValue = """{"kind":"Coll","elem":{"tag":"SByte"},"items":[{"kind":"Byte","value":-128}]}"""
        assertEquals(valueJson.noSpaces, expectedValue)
        assertEquals(cost, 90L)
      case (_, Left(err)) =>
        fail(s"evalApplied returned Left: $err")
    }
  }

  // ── End-to-end eval-back, one per decoder kind ─────────────────────────────

  test("eval-back: Boolean(true)") {
    assertEvalBack(SBoolean, parse("""{"kind":"Boolean","value":true}"""))
  }

  test("eval-back: Byte(-128)") {
    assertEvalBack(SByte, parse("""{"kind":"Byte","value":-128}"""))
  }

  test("eval-back: Short(0)") {
    assertEvalBack(SShort, parse("""{"kind":"Short","value":0}"""))
  }

  test("eval-back: Int(42)") {
    assertEvalBack(SInt, parse("""{"kind":"Int","value":42}"""))
  }

  test("eval-back: Long(9000000000)") {
    assertEvalBack(SLong, parse("""{"kind":"Long","value":"9000000000"}"""))
  }

  test("eval-back: BigInt") {
    assertEvalBack(SBigInt, parse("""{"kind":"BigInt","value":"123456789012345678901234567890"}"""))
  }

  test("eval-back: GroupElement (dlog generator)") {
    // Build the input JSON from the actual generator so the round-trip is self-consistent.
    val genHex = Base16.encode(GroupElementSerializer.toBytes(CryptoConstants.dlogGroup.generator))
    val inputJson = parse(s"""{"kind":"GroupElement","bytes_hex":"$genHex"}""")
    assertEvalBack(SGroupElement, inputJson)
  }

  test("eval-back: Coll[Byte]") {
    assertEvalBack(SCollection(SByte),
      parse("""{"kind":"Coll","elem":{"tag":"SByte"},"items":[{"kind":"Byte","value":1},{"kind":"Byte","value":-1}]}"""))
  }

  test("eval-back: Coll[Int] (typed coll)") {
    assertEvalBack(SCollection(SInt),
      parse("""{"kind":"Coll","elem":{"tag":"SInt"},"items":[{"kind":"Int","value":1},{"kind":"Int","value":2}]}"""))
  }

  test("eval-back: nested Coll[Coll[Int]]") {
    assertEvalBack(SCollection(SCollection(SInt)),
      parse("""{"kind":"Coll","elem":{"tag":"SColl","elem":{"tag":"SInt"}},"items":[{"kind":"Coll","elem":{"tag":"SInt"},"items":[{"kind":"Int","value":1},{"kind":"Int","value":2}]}]}"""))
  }

  // This is the regression guard for CRITICAL #1: a Tuple input MUST eval back to a
  // Tuple, not a Coll. The old `Tuple(items)` decode produced {kind:"Coll",...} here.
  test("eval-back: Tuple (Int, Long) pair — guards the silent-Coll bug") {
    assertEvalBack(STuple(IndexedSeq(SInt, SLong)),
      parse("""{"kind":"Tuple","items":[{"kind":"Int","value":7},{"kind":"Long","value":"99"}]}"""))
  }

  // ── Decoder unit round-trips (value-level, no eval) ────────────────────────

  test("decoder round-trip: Int(42) decodes to Constant, valueToJson yields Int(42)") {
    val constant = EvalCore.decodeInputConstant(parse("""{"kind":"Int","value":42}"""))
    assertEquals(EvalCore.valueToJson(constant.value).noSpaces, """{"kind":"Int","value":42}""")
  }

  test("decoder round-trip: Long(9000000000) decodes to Constant, valueToJson yields Long") {
    val constant = EvalCore.decodeInputConstant(parse("""{"kind":"Long","value":"9000000000"}"""))
    assertEquals(EvalCore.valueToJson(constant.value).noSpaces, """{"kind":"Long","value":"9000000000"}""")
  }

  // ── Range-check guards (#3): out-of-range Byte/Short must error, not wrap ───

  test("decoder: out-of-range Byte errors (no silent wrap)") {
    interceptMessage[RuntimeException]("decodeInputConstant Byte: value 200 out of Byte range") {
      EvalCore.decodeInputConstant(parse("""{"kind":"Byte","value":200}"""))
    }
  }

  test("decoder: out-of-range Short errors (no silent wrap)") {
    interceptMessage[RuntimeException]("decodeInputConstant Short: value 40000 out of Short range") {
      EvalCore.decodeInputConstant(parse("""{"kind":"Short","value":40000}"""))
    }
  }
}
