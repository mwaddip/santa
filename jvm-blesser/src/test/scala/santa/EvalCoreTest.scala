package santa

import scorex.util.encode.Base16

import sigma.ast.{
  BoxConstant, HeaderConstant, IntConstant, SBox, SHeader, SInt, SLong, SPreHeader, SSigmaProp, STuple
}
import sigma.crypto.CryptoConstants
import sigma.data.{CSigmaProp, ProveDlog, SigmaBoolean}
import sigma.serialization.{SigmaSerializer}

/** Unit guards for EvalCore encoders that the value/cost cross-check cannot catch
  * (because both the blesser and the runner share the same encoder, an encoder bug
  * is invisible to a self-consistent round-trip). These must be asserted directly. */
class EvalCoreTest extends munit.FunSuite {

  private def parse(s: String): io.circe.Json = io.circe.parser.parse(s).toOption.get

  // Guards the case-ordering fix: STuple <: SCollection, so the `SCollection` case
  // must NOT precede `STuple` (else STuple is unreachable and a tuple type
  // mis-encodes as {tag:"SColl",elem:{tag:"SAny"}}). With the correct ordering this
  // yields the STuple shape with each item's SType.
  test("stypeToJson(STuple(SInt, SLong)) encodes as STuple, not SColl") {
    val json = EvalCore.stypeToJson(STuple(IndexedSeq(SInt, SLong)))
    assertEquals(json.noSpaces, """{"tag":"STuple","items":[{"tag":"SInt"},{"tag":"SLong"}]}""")
  }

  // ── stypeFromJson: SBox / SHeader / SPreHeader type tags (Task 3 part b) ────
  // These tags appear as the `elem` of Coll[Box]/Coll[Header]/Coll[PreHeader] inputs
  // to the collection-op properties (exists/filter/forall/...). Before Task 3 they
  // hard-failed ("unsupported type tag 'SBox'"). SUnknown intentionally stays a skip.

  test("stypeFromJson decodes SBox tag to the SBox object") {
    assertEquals(EvalCore.stypeFromJson(parse("""{"tag":"SBox"}""")), SBox)
  }

  test("stypeFromJson decodes SHeader tag to the SHeader object") {
    assertEquals(EvalCore.stypeFromJson(parse("""{"tag":"SHeader"}""")), SHeader)
  }

  test("stypeFromJson decodes SPreHeader tag to the SPreHeader object") {
    assertEquals(EvalCore.stypeFromJson(parse("""{"tag":"SPreHeader"}""")), SPreHeader)
  }

  // SUnknown is an opaque repr (no element type to reconstruct) and MUST remain a
  // loud skip, never silently decode. Message-pinned so an unrelated throw can't pass.
  test("stypeFromJson rejects SUnknown (stays unsupported)") {
    interceptMessage[RuntimeException](
      "stypeFromJson: unsupported type tag 'SUnknown' — not yet supported") {
      EvalCore.stypeFromJson(parse("""{"tag":"SUnknown","repr":"???"}"""))
    }
  }

  // ── decodeInputConstant: SigmaProp (Task 3 part b) ─────────────────────────
  // Inverse of valueToJson's SigmaProp encode (which emits
  // {kind:"SigmaProp", raw_hex: Base16(SigmaBoolean.serializer.toBytes(sp.sigmaTree))}).
  // Round-trip a real ProveDlog SigmaProp through encode -> decode -> encode and
  // assert byte-identity; also assert the decoded Constant carries SSigmaProp type.

  test("decodeInputConstant: SigmaProp (ProveDlog) round-trips through encode") {
    val sb: SigmaBoolean = ProveDlog(CryptoConstants.dlogGroup.generator)
    val sp: CSigmaProp   = CSigmaProp(sb)
    val encoded = EvalCore.valueToJson(sp)
    // sanity: the encoder really produced the SigmaProp shape we are decoding
    assert(encoded.hcursor.get[String]("kind").toOption.contains("SigmaProp"),
      s"precondition failed — encoder did not emit SigmaProp: ${encoded.noSpaces}")

    val constant = EvalCore.decodeInputConstant(encoded)
    assertEquals(constant.tpe, SSigmaProp: sigma.ast.SType)
    // re-encode the decoded runtime value: must equal the original JSON exactly
    assertEquals(EvalCore.valueToJson(constant.value).noSpaces, encoded.noSpaces)
  }

  // Direct raw_hex decode: build the hex from the serializer the encoder uses, decode,
  // and assert the reconstructed SigmaBoolean is byte-identical when re-serialized.
  test("decodeInputConstant: SigmaProp raw_hex parses to the same SigmaBoolean") {
    val sb: SigmaBoolean = ProveDlog(CryptoConstants.dlogGroup.generator)
    val rawHex = Base16.encode(SigmaBoolean.serializer.toBytes(sb))
    val constant = EvalCore.decodeInputConstant(parse(s"""{"kind":"SigmaProp","raw_hex":"$rawHex"}"""))
    val decodedTree = constant.value.asInstanceOf[CSigmaProp].sigmaTree
    assertEquals(Base16.encode(SigmaBoolean.serializer.toBytes(decodedTree)), rawHex)
  }

  // Malformed SigmaProp bytes must error loudly (no silent wrong value), mirroring the
  // Box/Header malformed-bytes guards. Truncated input under-runs the buffer.
  test("decodeInputConstant: malformed SigmaProp bytes error loudly") {
    intercept[Throwable] {
      EvalCore.decodeInputConstant(parse("""{"kind":"SigmaProp","raw_hex":""}"""))
    }
  }

  // ── decodeColl: Coll[Box] / Coll[Header] (Task 3 part b) ───────────────────
  // The collection-op properties feed Coll[Box]/Coll[Header] inputs. These exercise the
  // new per-element decodeColl branches end-to-end: build a Coll JSON from a real
  // Box/Header, decode it, re-encode, and assert byte-identity.

  test("decodeColl: Coll[Box] decodes and re-encodes byte-identically") {
    val tree = sigma.ast.ErgoTree.fromSigmaBoolean(ProveDlog(CryptoConstants.dlogGroup.generator))
    val box = new org.ergoplatform.ErgoBox(
      value = 1000000L, ergoTree = tree,
      transactionId = scorex.util.bytesToId(Array.fill(32)(0: Byte)),
      index = 0.toShort, creationHeight = 0)
    val boxJson = EvalCore.valueToJson(sigma.data.CBox(box))
    val collJson = parse(s"""{"kind":"Coll","elem":{"tag":"SBox"},"items":[${boxJson.noSpaces}]}""")
    val constant = EvalCore.decodeInputConstant(collJson)
    assertEquals(constant.tpe, sigma.ast.SCollection(SBox): sigma.ast.SType)
    assertEquals(EvalCore.valueToJson(constant.value).noSpaces, collJson.noSpaces)
  }

  test("decodeColl: Coll[Header] decodes and re-encodes byte-identically") {
    // Upstream v2 mainnet-header literal (same one EvalAppliedTest uses for the Header round-trip).
    val headerHex =
      "02ac2101807f0000ca01ff0119db227f202201007f62000177a080005d440896d05d3f80dcff7f5e7f59007294c180808d0158d1ff6ba10000f901c7f0ef87dcfff17fffacb6ff7f7f1180d2ff7f1e24ffffe1ff937f807f0797b9ff6ebdae007e5c8c00b8403d3701557181c8df800001b6d5009e2201c6ff807d71808c00019780f087adb3fcdbc0b3441480887f80007f4b01cf7f013ff1ffff564a0000b9a54f00770e807f41ff88c00240000080c0250000000003bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c0000000000003105"
    val headerJson = parse(s"""{"kind":"Header","bytes_hex":"$headerHex"}""")
    val collJson = parse(s"""{"kind":"Coll","elem":{"tag":"SHeader"},"items":[${headerJson.noSpaces}]}""")
    val constant = EvalCore.decodeInputConstant(collJson)
    assertEquals(constant.tpe, sigma.ast.SCollection(SHeader): sigma.ast.SType)
    assertEquals(EvalCore.valueToJson(constant.value).noSpaces, collJson.noSpaces)
  }

  // Empty Coll[PreHeader]: PreHeader VALUES encode as Opaque (valueToJson doesn't model
  // them), so a populated Coll[PreHeader] can't round-trip — but the TYPE-TAG decode must
  // not crash. An empty items list exercises exactly the SPreHeader branch in decodeColl
  // (which previously hard-failed: "decodeColl: elem type 'SPreHeader' not yet supported").
  test("decodeColl: empty Coll[PreHeader] builds (type-tag decode does not crash)") {
    val collJson = parse("""{"kind":"Coll","elem":{"tag":"SPreHeader"},"items":[]}""")
    val constant = EvalCore.decodeInputConstant(collJson)
    assertEquals(constant.tpe, sigma.ast.SCollection(SPreHeader): sigma.ast.SType)
  }

  // ── isWireEncodable: version-gated input drop (Task 8) ─────────────────────
  // EvalCore binds inputs in-memory (ErgoHeader.sigmaSerializer + a ContextExtension var),
  // which BYPASSES sigma-state's DataSerializer version gate. isWireEncodable re-applies that
  // gate: serialize the decoded constant through DataSerializer at the target ErgoTree version
  // and report whether it succeeds. SHeader is guarded by `ergoTreeVersion >= 3`
  // (DataSerializer.scala:19), so a Header input is NOT a valid v5 (ergoTree=2) wire encoding —
  // EvalCore over-captures it in-memory, and ergots/sigma-rust correctly reject it. The gate
  // drops exactly such cases so the corpus is what every impl can deserialize.

  // Upstream v2 mainnet-header literal (same one the Coll[Header] round-trip above uses).
  private val mainnetHeaderHex =
    "02ac2101807f0000ca01ff0119db227f202201007f62000177a080005d440896d05d3f80dcff7f5e7f59007294c180808d0158d1ff6ba10000f901c7f0ef87dcfff17fffacb6ff7f7f1180d2ff7f1e24ffffe1ff937f807f0797b9ff6ebdae007e5c8c00b8403d3701557181c8df800001b6d5009e2201c6ff807d71808c00019780f087adb3fcdbc0b3441480887f80007f4b01cf7f013ff1ffff564a0000b9a54f00770e807f41ff88c00240000080c0250000000003bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c0000000000003105"

  private def headerConstant: sigma.ast.Constant[SHeader.type] = {
    val bytes  = Base16.decode(mainnetHeaderHex).get
    val header = org.ergoplatform.ErgoHeader.sigmaSerializer.parse(SigmaSerializer.startReader(bytes))
    HeaderConstant(new sigma.data.CHeader(header))
  }

  private def boxConstant: sigma.ast.Constant[SBox.type] = {
    val tree = sigma.ast.ErgoTree.fromSigmaBoolean(ProveDlog(CryptoConstants.dlogGroup.generator))
    val box = new org.ergoplatform.ErgoBox(
      value = 1000000L, ergoTree = tree,
      transactionId = scorex.util.bytesToId(Array.fill(32)(0: Byte)),
      index = 0.toShort, creationHeight = 0)
    BoxConstant(sigma.data.CBox(box))
  }

  test("isWireEncodable: Header input is NOT wire-encodable at v5 (activated=2)") {
    assert(!EvalCore.isWireEncodable(headerConstant, activated = 2),
      "Header should be rejected at ErgoTree v2 — DataSerializer gates SHeader on version >= 3")
  }

  test("isWireEncodable: Header input IS wire-encodable at v6 (activated=3)") {
    assert(EvalCore.isWireEncodable(headerConstant, activated = 3),
      "Header should serialize at ErgoTree v3 — the SHeader arm is enabled there")
  }

  test("isWireEncodable: Box input IS wire-encodable at v5 (activated=2)") {
    assert(EvalCore.isWireEncodable(boxConstant, activated = 2),
      "Box is wire-encodable at v5 — the SBox arm is not version-gated")
  }

  test("isWireEncodable: Int input IS wire-encodable at v5 (activated=2)") {
    assert(EvalCore.isWireEncodable(IntConstant(5), activated = 2),
      "Int is a primitive, wire-encodable at every version")
  }

  // ── SpecExtract.toEntry robustness: encodable-but-undecodable → skip (Task 3 part a) ──
  // An input valueToJson can ENCODE (non-Opaque) but decodeInputConstant cannot re-DECODE
  // must become a graceful Left (skip-and-report), NOT a thrown crash. None-as-input is the
  // canonical example: it encodes as {"kind":"Option","value":null} (passes hasOpaque) yet
  // decodeInputConstant rejects it (untyped). Before the pre-decode guard this routed through
  // evalApplied's Left and tripped the eval-failure sys.error — a hard fail. The guard must
  // classify it as a skip. The tree bytes are irrelevant (the guard fires before eval).
  test("toEntry: encodable-but-undecodable input (None) is skipped, not crashed") {
    val cap = SpecExtract.Capture(
      op = "probe.undecodable", script = "(x: Option[Int]) => x",
      treeBytesHex = "1b1200dad9010102dc6a03dd01720101e4e30102",
      input = None, expectedValue = None,
      verificationCost = None, costDetailsCost = None)
    SpecExtract.toEntry(cap, 0, 3.toByte) match {
      case Left(reason) =>
        assert(reason.contains("input not decodable"),
          s"expected an 'input not decodable' skip reason, got: $reason")
      case Right(_) =>
        fail("expected Left (skip) for an undecodable input, got Right")
    }
  }

  // Guard must NOT alter the happy path: a fully decodable input still re-blesses to Right.
  // (Mirrors the spike's hand-verified Byte(-128) -> Coll[Byte](-128) case used as the V6
  // anchor.) This pins that the pre-decode try doesn't swallow a genuinely-good case.
  test("toEntry: a decodable input still produces Right (guard didn't break the happy path)") {
    val cap = SpecExtract.Capture(
      op = "Global.serialize[Byte]", script = "(x: Byte) => serialize(x)",
      treeBytesHex = "1b1200dad9010102dc6a03dd01720101e4e30102",
      input = -128.toByte,
      expectedValue = sigma.Colls.fromArray(Array((-128).toByte))(sigma.ByteType),
      verificationCost = None, costDetailsCost = None)
    SpecExtract.toEntry(cap, 0, 3.toByte) match {
      case Right(_)     => () // happy path preserved
      case Left(reason) => fail(s"expected Right for a decodable input, got Left: $reason")
    }
  }
}
