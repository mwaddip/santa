package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored GE canonical-bytes witnesses (ergots Ask 16) — three families pinning
// GroupElementSerializer.parse semantics (core/.../GroupElementSerializer.scala:35-42:
// lead != 0 → decodePoint curve-validates; lead == 0 → identity point, bytes 1..32
// DISCARDED; serialize always emits canonical) and the byte-vs-value identity bases.
// All spike-confirmed; v5 families identical at activated 2 and 3.
//
//   GroupElement.canonical_bytes (v5, {activated 2, ergoTree 0}):
//     invalid-point constants (0x02 ‖ ff×32 — x not a field element) fail at tree
//     DESERIALIZE — even in a never-taken branch (parse-time, version-independent).
//     NOTE the JVM's reject DIAGNOSTIC is misleading: deserializeErgoTree blanket-wraps
//     any IllegalArgumentException as SerializerException("Tree version (0) is above
//     activated script version (1)") — ErgoTreeSerializer.scala:190-193 — with the real
//     cause ("x value invalid for SecP256K1FieldElement") chained beneath. Verdict
//     errored either way. Garbage-identity constants (0x00 ‖ aa×32) PARSE — they decode
//     to the identity point: EQ against the canonical identity → true; getEncoded
//     re-serializes canonically (33 zeros).
//
//   Box.eq_id_basis (v5): two box constants identical except R4 = canonical identity
//     vs garbage identity (spliced in the SERIALIZED TREE — in-code construction would
//     re-normalize on serialize). ErgoBox parse retains the input slice as _bytes
//     (ErgoBox.scala:214-226) and id = Blake2b256(bytes) — so EQ(box1, box2) → FALSE
//     (byte basis) while EQ(box1.R4[GE].get, box2.R4[GE].get) → TRUE (value basis).
//     EQ(box1, box1) → true pins the flat EQ_Box comparer cost
//     (DataValueComparer.scala:56, FixedCost(6)) — the Ask-17 Box shape.
//
//   Global.deserializeTo_Header_id_basis (v6, {activated 3, ergoTree 3}): header BYTES
//     as Coll[Byte] constants (data — spliced as plain arrays), minerPk = canonical vs
//     garbage identity. Both deserializeTo[Header] ACCEPT; EQ(h1, h2) → FALSE (CHeader
//     equality is id-based; id = Blake2b256 over the RETAINED input slice,
//     ErgoHeader.scala:133-140/:167-180); EQ(h1.minerPk, h2.minerPk) → TRUE;
//     garbage .minerPk.getEncoded → canonical 33 zeros; invalid pk (0x02 ‖ ff×32) →
//     eval-ERRORED (decodePoint inside deserializeTo; surfaces as
//     InvocationTargetException via the method's reflection seam). EQ(h1, h1) → true
//     pins flat EQ_Header (DataValueComparer Fixed(6)) — the Ask-17 Header shape.
//
// The base header bytes are entry #1 of vectors/eval/v6/spec/Global.deserializeTo_header
// .json (a real v1 header), embedded verbatim so this blesser has no cross-file build
// coupling; its minerPk (03be7ad7…bb66) is located and spliced by value.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import scorex.util.bytesToId
import sigma.VersionContext
import sigma.ast.{BoolToSigmaProp, BoxConstant, ByteArrayConstant, EQ, ErgoTree,
  ExtractRegisterAs, Global, GroupElementConstant, If, IntConstant, MethodCall, OptionGet,
  SBox, SGlobalMethods, SGroupElement, SGroupElementMethods, SHeader, SHeaderMethods,
  SOption, SType, STypeVar, TrueLeaf, Value}
import sigma.ast.ErgoTree.ZeroHeader
import sigma.crypto.CryptoConstants
import sigma.data.CBox
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}
import org.ergoplatform.ErgoBox

object AuthoredGeCanonical {

  val ActivatedV5: Byte = 2
  val V3: Byte = VersionContext.V6SoftForkVersion // 3
  val Source = "santa:authored-ge-canonical"

  val OpGe     = "GroupElement.canonical_bytes"
  val OpBox    = "Box.eq_id_basis"
  val OpHeader = "Global.deserializeTo_Header_id_basis"

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private val identityPt   = GroupElementSerializer.parse(SigmaSerializer.startReader(Array.fill[Byte](33)(0)))
  private val generatorPt  = CryptoConstants.dlogGroup.generator
  private val generatorHex = Base16.encode(GroupElementSerializer.toBytes(generatorPt))
  private val garbageIdentityHex = "00" + "aa" * 32
  private val invalidPointHex    = "02" + "ff" * 32

  /** Replace `from` (exactly one occurrence, same length) with `to` in a hex string. */
  private def splice(hex: String, from: String, to: String): String = {
    val first = hex.indexOf(from)
    if (first < 0) sys.error(s"splice source not found: ${from.take(20)}…")
    if (hex.indexOf(from, first + 1) >= 0) sys.error(s"splice source ambiguous: ${from.take(20)}…")
    if (from.length != to.length) sys.error("splice must preserve length")
    hex.replace(from, to)
  }

  private def hexAt(root: Value[SType], v: Byte): String =
    VersionContext.withVersions(v, v) {
      val header = if (v == 0) ZeroHeader
                   else ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, v))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  // ── GroupElement.canonical_bytes trees (the generator constant is the splice target) ──
  private def eqTree: Value[SType] =
    EQ(GroupElementConstant(identityPt), GroupElementConstant(generatorPt))
  private def deadBranchTree: Value[SType] =
    If(TrueLeaf, IntConstant(5),
      If(EQ(GroupElementConstant(identityPt), GroupElementConstant(generatorPt)),
        IntConstant(6), IntConstant(7)))
  private def getEncodedTree: Value[SType] =
    MethodCall.typed[Value[SType]](GroupElementConstant(generatorPt),
      SGroupElementMethods.GetEncodedMethod, IndexedSeq.empty, Map.empty)

  // ── Box.eq_id_basis (R4 = generator in b2 is the splice target) ─────────────
  private val trueTree = ErgoTree.fromProposition(BoolToSigmaProp(TrueLeaf))
  private def mkBox(r4Point: sigma.crypto.EcPointType) = new ErgoBox(
    value = 1000000L,
    ergoTree = trueTree,
    additionalTokens = sigma.Colls.emptyColl,
    additionalRegisters = Map(ErgoBox.R4 -> GroupElementConstant(r4Point)),
    transactionId = bytesToId(Array.fill[Byte](32)(0x11)),
    index = 0.toShort,
    creationHeight = 0)
  private def b1 = mkBox(identityPt)
  private def b2 = mkBox(generatorPt)
  private def regGet(b: Value[SType]) =
    OptionGet(ExtractRegisterAs(b.asInstanceOf[Value[SBox.type]], ErgoBox.R4, SOption(SGroupElement)))
  private def boxEqTree: Value[SType]   = EQ(BoxConstant(CBox(b1)), BoxConstant(CBox(b2)))
  private def boxRegTree: Value[SType]  = EQ(regGet(BoxConstant(CBox(b1))), regGet(BoxConstant(CBox(b2))))
  private def boxCtrlTree: Value[SType] = EQ(BoxConstant(CBox(b1)), BoxConstant(CBox(b1)))

  // ── Global.deserializeTo_Header_id_basis ────────────────────────────────────
  /** Entry #1 of vectors/eval/v6/spec/Global.deserializeTo_header.json (real v1 header). */
  private val baseHeaderHex =
    "010000000000000000000000000000000000000000000000000000000000000000766ab7a313cd2fb66d135b0be6662aa02dfa8e5b17342c05a04396268df0bfbb93fb06aa44413ff57ac878fda9377207d5db0e78833556b331b4d9727b3153ba18b7a08878f2a7ee4389c5a1cece1e2724abe8b8adc8916240dd1bcac069177303f1f6cee9ba2d0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8060117650100000003be7ad70c74f691345cbedba19f4844e7fc514e1188a7929f5ae261d5bb00bb6602da9385ac99014ddcffe88d2ac5f28ce817cd615f270a0a5eae58acfb9fd9f6a0000000030151dc631b7207d4420062aeb54e82b0cfb160ff6ace90ab7754f942c4c3266b"
  private val basePkHex = "03be7ad70c74f691345cbedba19f4844e7fc514e1188a7929f5ae261d5bb00bb66"
  private def headerBytes(pkHexTo: String): Array[Byte] =
    Base16.decode(splice(baseHeaderHex, basePkHex, pkHexTo)).get

  private def desTo(bytes: Array[Byte]): Value[SType] =
    MethodCall.typed[Value[SType]](Global, SGlobalMethods.deserializeToMethod,
      IndexedSeq(ByteArrayConstant(bytes)), Map(STypeVar("T") -> SHeader))
  private def minerPk(h: Value[SType]) =
    MethodCall.typed[Value[SType]](h, SHeaderMethods.minerPkMethod, IndexedSeq.empty, Map.empty)
  private def getEncoded(ge: Value[SType]) =
    MethodCall.typed[Value[SType]](ge, SGroupElementMethods.GetEncodedMethod, IndexedSeq.empty, Map.empty)

  def extract(): Map[String, Json] = {
    val v5 = ActivatedV5
    // GroupElement.canonical_bytes
    val geEq        = hexAt(eqTree, 0)
    val geDead      = hexAt(deadBranchTree, 0)
    val geEnc       = hexAt(getEncodedTree, 0)
    val ge = Seq(
      SpecExtract.authoredRejectEntryV(OpGe,
        "if (true) 5 else <tree carrying GE constant 0x02‖ff×32> — JVM: fails at DESERIALIZE though the branch is dead (decodePoint: 'x value invalid for SecP256K1FieldElement', blanket-wrapped in a misleading version-message SerializerException)",
        splice(geDead, generatorHex, invalidPointHex), "invalid-point-dead-branch-errored#0", dummyInput, v5, 0),
      SpecExtract.authoredRejectEntryV(OpGe,
        "{ identityGE == <invalid GE 0x02‖ff×32> }  // JVM: same parse-time reject in a live expression",
        splice(geEq, generatorHex, invalidPointHex), "invalid-point-eq-errored#1", dummyInput, v5, 0),
      SpecExtract.authoredEntryV(OpGe,
        "{ identityGE == <garbage-identity GE 0x00‖aa×32> }  // true — lead 0x00 ⇒ identity point, bytes 1..32 DISCARDED at parse",
        splice(geEq, generatorHex, garbageIdentityHex), "garbage-identity-eq-true#2", dummyInput, v5, 0),
      SpecExtract.authoredEntryV(OpGe,
        "if (true) 5 else <tree carrying garbage-identity GE> — accepts: the non-canonical IDENTITY encoding parses fine (only non-zero leads curve-validate)",
        splice(geDead, generatorHex, garbageIdentityHex), "garbage-identity-dead-branch-accept#3", dummyInput, v5, 0),
      SpecExtract.authoredEntryV(OpGe,
        "{ <garbage-identity GE>.getEncoded }  // canonical 33 zeros — serialize always re-emits canonical",
        splice(geEnc, generatorHex, garbageIdentityHex), "garbage-identity-getEncoded#4", dummyInput, v5, 0),
      SpecExtract.authoredEntryV(OpGe,
        "{ generatorGE.getEncoded }  // control: the canonical compressed generator bytes",
        geEnc, "generator-getEncoded-control#5", dummyInput, v5, 0))
    // Box.eq_id_basis
    val box = Seq(
      SpecExtract.authoredEntryV(OpBox,
        "{ box1 == box2 } — twins identical except R4: canonical identity vs garbage identity (spliced) → FALSE: box identity is the BYTE basis (id = Blake2b256 over the parse-retained slice)",
        splice(hexAt(boxEqTree, 0), generatorHex, garbageIdentityHex), "box-eq-byte-basis-false#0", dummyInput, v5, 0),
      SpecExtract.authoredEntryV(OpBox,
        "{ box1.R4[GroupElement].get == box2.R4[GroupElement].get } → TRUE: both registers DECODE to the identity point (the value basis)",
        splice(hexAt(boxRegTree, 0), generatorHex, garbageIdentityHex), "register-eq-value-basis-true#1", dummyInput, v5, 0),
      SpecExtract.authoredEntryV(OpBox,
        "{ box1 == box1 } → true — pins the flat EQ_Box comparer (DataValueComparer Fixed(6); the Ask-17 Box shape)",
        hexAt(boxCtrlTree, 0), "box-eq-control-true#2", dummyInput, v5, 0))
    // Global.deserializeTo_Header_id_basis (v6)
    val h1 = headerBytes("00" * 33)
    val h2 = headerBytes(garbageIdentityHex)
    val hb = headerBytes(invalidPointHex)
    val header = Seq(
      SpecExtract.authoredEntryV(OpHeader,
        "{ deserializeTo[Header](h-idPk) == deserializeTo[Header](h-garbagePk) } — twins differing only in the minerPk ENCODING → FALSE: CHeader equality is id-based, id = Blake2b256 over the retained input slice",
        hexAt(EQ(desTo(h1), desTo(h2)), V3), "header-eq-id-basis-false#0", dummyInput, V3, V3.toInt),
      SpecExtract.authoredEntryV(OpHeader,
        "{ h1.minerPk == h2.minerPk } → TRUE: both pks decode to the identity point (the value basis)",
        hexAt(EQ(minerPk(desTo(h1)), minerPk(desTo(h2))), V3), "minerpk-eq-value-basis-true#1", dummyInput, V3, V3.toInt),
      SpecExtract.authoredEntryV(OpHeader,
        "{ deserializeTo[Header](h-garbagePk).minerPk.getEncoded } → canonical 33 zeros (deserializeTo ACCEPTS the garbage-identity pk; serialize normalizes)",
        hexAt(getEncoded(minerPk(desTo(h2))), V3), "garbage-pk-getEncoded#2", dummyInput, V3, V3.toInt),
      SpecExtract.authoredRejectEntryV(OpHeader,
        "{ deserializeTo[Header](h-invalidPk 0x02‖ff×32) … } → errored at EVAL (decodePoint curve-validation inside deserializeTo)",
        hexAt(getEncoded(minerPk(desTo(hb))), V3), "invalid-pk-errored#3", dummyInput, V3, V3.toInt),
      SpecExtract.authoredEntryV(OpHeader,
        "{ deserializeTo[Header](h1) == deserializeTo[Header](h1) } → true — pins the flat EQ_Header comparer (DataValueComparer Fixed(6); the Ask-17 Header shape)",
        hexAt(EQ(desTo(h1), desTo(h1)), V3), "header-eq-control-true#4", dummyInput, V3, V3.toInt))
    Map(
      OpGe     -> SpecExtract.authoredEnvelope(OpGe, ge, Source),
      OpBox    -> SpecExtract.authoredEnvelope(OpBox, box, Source),
      OpHeader -> SpecExtract.authoredEnvelope(OpHeader, header, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredGeCanonical", extract(), outDir)
}
