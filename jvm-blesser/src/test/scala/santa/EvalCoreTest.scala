package santa

import sigma.ast.{SInt, SLong, STuple}

/** Unit guards for EvalCore encoders that the value/cost cross-check cannot catch
  * (because both the blesser and the runner share the same encoder, an encoder bug
  * is invisible to a self-consistent round-trip). These must be asserted directly. */
class EvalCoreTest extends munit.FunSuite {

  // Guards the case-ordering fix: STuple <: SCollection, so the `SCollection` case
  // must NOT precede `STuple` (else STuple is unreachable and a tuple type
  // mis-encodes as {tag:"SColl",elem:{tag:"SAny"}}). With the correct ordering this
  // yields the STuple shape with each item's SType.
  test("stypeToJson(STuple(SInt, SLong)) encodes as STuple, not SColl") {
    val json = EvalCore.stypeToJson(STuple(IndexedSeq(SInt, SLong)))
    assertEquals(json.noSpaces, """{"tag":"STuple","items":[{"tag":"SInt"},{"tag":"SLong"}]}""")
  }
}
