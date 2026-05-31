package santa

import scorex.util.bytesToId
import scorex.util.encode.Base16

import io.circe.Json

import org.ergoplatform.ErgoBox

import sigma.ast.{
  ErgoTree, GetVar, OptionGet, SBigInt, SBoolean, SBox, SByte, SCollection, SGroupElement,
  SHeader, SInt, SLong, SOption, SShort, SType, STuple, SUnsignedBigInt, Value
}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog
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
    sigma.VersionContext.withVersions(version, version) {   // v6 ctx so code-9 (SUnsignedBigInt) serializes
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }
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

  test("eval-back: UnsignedBigInt") {
    assertEvalBack(SUnsignedBigInt,
      parse("""{"kind":"UnsignedBigInt","value":"123456789012345678901234567890"}"""))
  }

  test("eval-back: UnsignedBigInt (large, near 256-bit)") {
    // 2^255 - 1 — exercises a value that fits UnsignedBigInt but would overflow signed paths
    val v = java.math.BigInteger.TWO.pow(255).subtract(java.math.BigInteger.ONE).toString
    assertEvalBack(SUnsignedBigInt, parse(s"""{"kind":"UnsignedBigInt","value":"$v"}"""))
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

  // ── Option (Task 2): encode Some+None; decode Some only ────────────────────

  // Encode: Some + None (None appears only as eval OUTPUT in the corpus — encode must handle it)
  test("encode: Some(5) and None") {
    assertEquals(EvalCore.valueToJson(Some(5)).noSpaces,
      """{"kind":"Option","value":{"kind":"Int","value":5}}""")
    assertEquals(EvalCore.valueToJson(None).noSpaces,
      """{"kind":"Option","value":null}""")
  }

  // Decode + eval-back: Some round-trips through bind→eval→encode.
  // NOTE: this binds an SOption value as context var 1 — the genuinely novel
  // reconstruction. If sigma-state rejects an Option context var, that is a real
  // finding to surface (do NOT work around it silently).
  test("eval-back: Option Some(5)") {
    assertEvalBack(SOption(SInt), parse("""{"kind":"Option","value":{"kind":"Int","value":5}}"""))
  }

  // None-as-INPUT is unsupported (untyped) — must error loudly, never silently decode.
  // Message-pinned (like the Byte/Short guards above) so the test can't pass for an
  // unrelated throw — a spuriously-passing "must reject" guard would silently weaken it.
  test("decoder: None-as-input errors (untyped, unsupported)") {
    interceptMessage[RuntimeException](
      "decodeInputConstant Option: None-as-input is unsupported (untyped; no element type to reconstruct)") {
      EvalCore.decodeInputConstant(parse("""{"kind":"Option","value":null}"""))
    }
  }

  // ── Box + Header (Stage 2a) ────────────────────────────────────────────────

  /** A trivial-but-valid ErgoBox for the Box round-trip: a P2PK box on the dlog
    * generator, mirroring EvalCore.dummyContext's selfBox shape (positive value,
    * all-zero txid, index 0, creationHeight 0). The proposition is a real ProveDlog
    * (not a constant) so the serialized box is representative. */
  private def trivialBox(): ErgoBox = {
    val tree = ErgoTree.fromSigmaBoolean(ProveDlog(CryptoConstants.dlogGroup.generator))
    new ErgoBox(
      value          = 1000000L,
      ergoTree       = tree,
      transactionId  = bytesToId(Array.fill(32)(0: Byte)),
      index          = 0.toShort,
      creationHeight = 0
    )
  }

  // LOAD-BEARING: proves a BoxConstant survives decode -> bind as ContextExtension
  // var 1 -> toSigmaContext() -> eval -> encode. If this fails, the binding model is
  // wrong for Box (escalate, do NOT work around).
  test("eval-back: Box (P2PK on dlog generator)") {
    val box       = trivialBox()
    val inputJson = parse(s"""{"kind":"Box","bytes_hex":"${Base16.encode(box.bytes)}"}""")
    assertEvalBack(SBox, inputJson)
  }

  // Header round-trip from the upstream v2 (first byte 02) mainnet-header literal
  // (LanguageSpecificationV6.scala). Identity tree returns the header unchanged, so
  // PoW validity is irrelevant — this guards the codec round-trip only.
  test("eval-back: Header (upstream v2 literal)") {
    val headerHex =
      "02ac2101807f0000ca01ff0119db227f202201007f62000177a080005d440896d05d3f80dcff7f5e7f59007294c180808d0158d1ff6ba10000f901c7f0ef87dcfff17fffacb6ff7f7f1180d2ff7f1e24ffffe1ff937f807f0797b9ff6ebdae007e5c8c00b8403d3701557181c8df800001b6d5009e2201c6ff807d71808c00019780f087adb3fcdbc0b3441480887f80007f4b01cf7f013ff1ffff564a0000b9a54f00770e807f41ff88c00240000080c0250000000003bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c0000000000003105"
    val inputJson = parse(s"""{"kind":"Header","bytes_hex":"$headerHex"}""")
    assertEvalBack(SHeader, inputJson)
  }

  // Malformed-hex negative guard: too-short Box bytes must throw loudly, never silently
  // decode a wrong value. The serializer reads past the 1-byte buffer -> BufferUnderflow
  // (verified empirically; message is null so the type is pinned rather than the message).
  // Pinning the type (not bare Throwable) also rules out a regression to the
  // 'Box not yet supported' RuntimeException path passing this guard spuriously.
  test("decoder: malformed Box bytes error loudly (no silent wrong value)") {
    intercept[java.nio.BufferUnderflowException] {
      EvalCore.decodeInputConstant(parse("""{"kind":"Box","bytes_hex":"00"}"""))
    }
  }
}
