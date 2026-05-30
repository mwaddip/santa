package santa

/** TDD tests for EvalCore.evalApplied and the SValue input decoder. */
class EvalAppliedTest extends munit.FunSuite {

  // ── Spike-proven case (snag 6 baseline) ────────────────────────────────────
  // Tree: `{ val func = (x: Byte) => Coll[Byte](x); func(getVar[Byte](1).get) }`
  // Input: Byte(-128)  →  expected value: Coll[Byte](-128), cost: 90
  test("evalApplied: Byte(-128) through serialize-Byte function tree") {
    val treeBytesHex = "1b1200dad9010102dc6a03dd01720101e4e30102"
    val inputJson    = io.circe.parser.parse("""{"kind":"Byte","value":-128}""").toOption.get
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

  // ── Decoder unit tests: verify non-Byte kinds decode and round-trip ────────

  test("decoder round-trip: Int(42) decodes to Constant, valueToJson yields Int(42)") {
    val inputJson = io.circe.parser.parse("""{"kind":"Int","value":42}""").toOption.get
    val constant  = EvalCore.decodeInputConstant(inputJson)
    val back      = EvalCore.valueToJson(constant.value)
    assertEquals(back.noSpaces, """{"kind":"Int","value":42}""")
  }

  test("decoder round-trip: Long(9000000000) decodes to Constant, valueToJson yields Long") {
    val inputJson = io.circe.parser.parse("""{"kind":"Long","value":"9000000000"}""").toOption.get
    val constant  = EvalCore.decodeInputConstant(inputJson)
    val back      = EvalCore.valueToJson(constant.value)
    assertEquals(back.noSpaces, """{"kind":"Long","value":"9000000000"}""")
  }

  test("decoder round-trip: Coll[Int] decodes to Constant, valueToJson round-trips") {
    val inputJson = io.circe.parser.parse(
      """{"kind":"Coll","elem":{"tag":"SInt"},"items":[{"kind":"Int","value":1},{"kind":"Int","value":2}]}"""
    ).toOption.get
    val constant  = EvalCore.decodeInputConstant(inputJson)
    val back      = EvalCore.valueToJson(constant.value)
    assertEquals(back.noSpaces,
      """{"kind":"Coll","elem":{"tag":"SInt"},"items":[{"kind":"Int","value":1},{"kind":"Int","value":2}]}""")
  }

  test("decoder round-trip: Short(0) decodes correctly") {
    val inputJson = io.circe.parser.parse("""{"kind":"Short","value":0}""").toOption.get
    val constant  = EvalCore.decodeInputConstant(inputJson)
    val back      = EvalCore.valueToJson(constant.value)
    assertEquals(back.noSpaces, """{"kind":"Short","value":0}""")
  }

  test("decoder round-trip: Boolean(true) decodes correctly") {
    val inputJson = io.circe.parser.parse("""{"kind":"Boolean","value":true}""").toOption.get
    val constant  = EvalCore.decodeInputConstant(inputJson)
    val back      = EvalCore.valueToJson(constant.value)
    assertEquals(back.noSpaces, """{"kind":"Boolean","value":true}""")
  }
}
