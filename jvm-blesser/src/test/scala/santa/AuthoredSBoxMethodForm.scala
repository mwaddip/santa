package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored SBox accessor METHOD-form (PropertyCall, 0xdb) pins — ergots f5-batch6
// Ask 19. The JVM catalogues the SBox accessors as PropertyCall methods in
// commonBoxMethods (present from v5Methods, NO version gate), and MethodCall.eval
// evaluates any catalogued FixedCost method via invokeFixed reflection
// (values.scala:1332-1351). So a hand-crafted PropertyCall(99,N) tree EVALUATES
// JVM-side even with no bespoke eval-fn — but ergots/sigma-rust register only the
// op-forms (+ 99:7/8/19), so they throw where the JVM evaluates: a real us-vs-JVM
// fork on hand-crafted method-form trees (mainnet-unreachable — compilers emit the
// op-forms — but full adversarial weight). Convergence: sigma-rust diverges
// identically (registers only 1/7/8/19).
//
// COSTS (spike SBoxMethodFormSpike, oracle-run; never spec-copied): the FULL-TREE
// cost is ergots' node-total (envelope 4 + the method's costKind = the op-form's
// costKind) PLUS ONE — the box receiver's ConstantPlaceholder visit (JitCost 1, the
// lazy path), exactly as the op-form Box.bytes_byte_basis already blesses at 13
// (CP1 + ExtractBytes12). So PropertyCall == op-form + 4 (the envelope):
//   99:1 value            CP1 + 4 + ExtractAmount(8)          = 13  (the control)
//   99:2 propositionBytes CP1 + 4 + ExtractScriptBytes(10)    = 15
//   99:3 bytes            CP1 + 4 + ExtractBytes(12)          = 17
//   99:4 bytesWithoutRef  CP1 + 4 + ExtractBytesWithNoRef(12) = 17
//   99:5 id               CP1 + 4 + ExtractId(12)             = 17
//   99:6 creationInfo     CP1 + 4 + ExtractCreationInfo(16)   = 21
// (ergots' node-total prediction 12/14/16/16/16/20 omits the CP visit.)
//
// CONSTRUCTION: mirrors AuthoredBoxBytesBasis (b1 canonical / b2 generator-GE +
// in-tree splice → non-canonical 0x00‖aa×32, so the deserialized receiver box
// RETAINS the garbage slice). The byte-basis trio (bytes/bytesWithoutRef/id) uses
// the b2-splice box so its method-form VALUES are byte-identical to the op-form
// Box.bytes_byte_basis twins (ergots asked the method-form pin the same
// retained/canonical bases). value/propositionBytes/creationInfo use b1.
// v5 surface, {activated 2, ergoTree 0}; all expecteds oracle-emitted.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import scorex.util.bytesToId
import sigma.VersionContext
import sigma.ast.{BoolToSigmaProp, BoxConstant, ErgoTree, GroupElementConstant, MethodCall,
  SBoxMethods, SMethod, SType, TrueLeaf, Value}
import sigma.ast.ErgoTree.ZeroHeader
import sigma.crypto.CryptoConstants
import sigma.data.CBox
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}
import org.ergoplatform.ErgoBox

object AuthoredSBoxMethodForm {

  val Activated: Byte = 2
  val ErgoTreeV0: Int = 0
  val Source = "santa:authored-sbox-method-form"
  val Op = "Box.accessor_method_form"

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  // Same box family + splice mechanism as AuthoredBoxBytesBasis (the byte-basis twins).
  private val identityPt   = GroupElementSerializer.parse(SigmaSerializer.startReader(Array.fill[Byte](33)(0)))
  private val generatorPt  = CryptoConstants.dlogGroup.generator
  private val generatorHex = Base16.encode(GroupElementSerializer.toBytes(generatorPt))
  private val garbageIdentityHex = "00" + "aa" * 32

  private def splice(hex: String, from: String, to: String): String = {
    val first = hex.indexOf(from)
    if (first < 0) sys.error(s"splice source not found: ${from.take(16)}…")
    if (hex.indexOf(from, first + 1) >= 0) sys.error(s"splice source ambiguous: ${from.take(16)}…")
    hex.replace(from, to)
  }

  private val trueTree = ErgoTree.fromProposition(BoolToSigmaProp(TrueLeaf))
  private def mkBox(r4Point: sigma.crypto.EcPointType) = new ErgoBox(
    value = 1000000L,
    ergoTree = trueTree,
    additionalTokens = sigma.Colls.emptyColl,
    additionalRegisters = Map(ErgoBox.R4 -> GroupElementConstant(r4Point)),
    transactionId = bytesToId(Array.fill[Byte](32)(0x11)),
    index = 0.toShort,
    creationHeight = 0)
  private def b1 = mkBox(identityPt)   // canonical twin
  private def b2 = mkBox(generatorPt)  // the in-tree splice target → garbage retained slice

  private def hexAtV0(root: Value[SType]): String =
    VersionContext.withVersions(0.toByte, 0.toByte) {
      Base16.encode(sigma.santa.LenientErgoTree.serialize(ZeroHeader, root))
    }

  /** PropertyCall(SBox, method) method-form on the given box constant receiver. */
  private def mf(box: ErgoBox, m: SMethod): Value[SType] =
    MethodCall.typed[Value[SType]](BoxConstant(CBox(box)), m, IndexedSeq.empty, Map.empty)

  /** Garbage twin: splice the canonical generator GE → non-canonical, so the deserialized
    * receiver box retains the garbage slice (the byte-basis distinction lives in .bytes/.id). */
  private def garbage(root: Value[SType]): String =
    splice(hexAtV0(root), generatorHex, garbageIdentityHex)

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredEntryV(Op,
        "{ <box>.value }  // PropertyCall(99,1) value method-form — the registered control. " +
          "cost 13 = ConstantPlaceholder(1) + MethodCall envelope(4) + ExtractAmount(8) " +
          "(ergots' node-total 4+8=12 omits the receiver's CP visit).",
        hexAtV0(mf(b1, SBoxMethods.ValueMethod)),
        "value-99-1#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <box>.propositionBytes }  // PropertyCall(99,2). cost 15 = CP(1)+envelope(4)+ExtractScriptBytes(10)",
        hexAtV0(mf(b1, SBoxMethods.PropositionBytesMethod)),
        "propositionBytes-99-2#1", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <garbage-R4 box>.bytes }  // PropertyCall(99,3) — the parse-RETAINED slice (0x00‖aa×32 " +
          "survives); VALUE byte-matches the op-form Box.bytes_byte_basis bytes-garbage-retained twin. " +
          "cost 17 = CP(1)+envelope(4)+ExtractBytes(12)",
        garbage(mf(b2, SBoxMethods.BytesMethod)),
        "bytes-99-3-garbage-retained#2", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <garbage-R4 box>.bytesWithoutRef }  // PropertyCall(99,4) — CANONICAL re-serialization " +
          "(garbage normalized away); VALUE byte-matches the op-form bytesWithoutRef twin. cost 17",
        garbage(mf(b2, SBoxMethods.BytesWithoutRefMethod)),
        "bytesWithoutRef-99-4-garbage-canonical#3", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <garbage-R4 box>.id }  // PropertyCall(99,5) — blake2b256 over the RETAINED slice; " +
          "VALUE byte-matches the op-form id-garbage twin. cost 17",
        garbage(mf(b2, SBoxMethods.IdMethod)),
        "id-99-5-garbage#4", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <box>.creationInfo }  // PropertyCall(99,6) — (creationHeight, ref bytes). " +
          "cost 21 = CP(1)+envelope(4)+ExtractCreationInfo(16)",
        hexAtV0(mf(b1, SBoxMethods.creationInfoMethod)),
        "creationInfo-99-6#5", dummyInput, Activated, ErgoTreeV0))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredSBoxMethodForm", extract(), outDir)
}
