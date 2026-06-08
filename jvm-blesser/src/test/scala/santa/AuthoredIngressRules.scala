package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored INGRESS-RULE witnesses — three distinct sigma-state ValidationRule seams
// that fire as a tree / template / box / header crosses a version-gated INGRESS
// boundary (deserialize-time, before any reduction). Hand-crafted + JVM-oracle-
// confirmed by the W1 spike on sigma-state 6.0.3 (rudolph control); re-blessed here
// through the SAME EvalCore oracle (fail-loud if any outcome drifts).
//
//   substConstants:version_source_{outer_v2,outer_v3} — a version-SOURCE pair, split into
//     two single-version envelopes (the taxonomy files by activated; one dir is one
//     activated). Pins that a substConstants TEMPLATE's constants parse under the OUTER
//     tree's VersionContext (no inner re-entry at the template's claimed version):
//       5a (reject, ErgoTree v2)  1a15020e081b060128010a730010007473007301830028
//         OUTER v2 tree; the substConstants template claims v3 (1b…) and carries an
//         Option DATA constant (type-code 0x24=36). JVM: ValidationException
//         ValidationRule(1009, …can be serialized) args [36] — the Option type-code is
//         not serializable at the OUTER v2, proving constants are read at the outer
//         version, not the template's inner v3.
//       5b (ACCEPT, ErgoTree v3)  1b15020e081a060128010a730010007473007301830028
//         OUTER v3 tree; template header v2(+size), Option constant. The substitution
//         succeeds and yields the substituted template bytes as a Coll[Byte] (8 bytes);
//         JVM SUCCESS, cost 222. value+cost are blessed from the oracle (not hand-typed).
//
//   Rule1012:header_size_bit  03050101017300
//     header byte 0x03 — version-3 bits set, size bit 0x08 NOT set. For a tree with
//     version > 0 the size bit MUST be set. JVM: ValidationException
//     ValidationRule(1012, For version greater then 0, size bit should be set.) args [3].
//     This fires at HEADER PARSE, before eval; EvalCore.evalApplied deserializes-then-
//     evals, so the parse exception surfaces as the (coarse) reject. ErgoTree v3 (the
//     header nibble is 3; the value arg only stamps the vector envelope).
//
//   Rule1019:check_v6_type    (tree_bytes_hex constructed below — see witness7Hex)
//     a v3 tree carrying a Box CONSTANT whose register R4 is typed Option[Int]. The
//     Option register is only serializable at ErgoTree v3+, so the box is serialized
//     under withVersions(3,3) and embedded as a BoxConstant in a v3 tree. JVM, at box
//     deserialize (register ingress): ValidationException ValidationRule(1019, Check
//     the type has the declared method.) args [Option[SInt]] — CheckV6Type rejects the
//     v6-only Option-register type as it re-enters. ErgoTree v3.
//
// Version stamping mirrors the existing convention (confirmed against where v3-tree
// authored vectors live, e.g. AuthoredOptionSemantics' outputs):
//   • ErgoTree v2 → activated 2 → vectors/eval/v5/authored/  (witness 5a)
//   • ErgoTree v3 → activated 3 → vectors/eval/v6/authored/  (witnesses 5b, 6, 7)
// The trees are version-independent in OUTCOME (the spike confirmed it); they are
// filed by their ErgoTree (header) version per the established convention. Three of
// four are reject (value/cost null, error "errored"); 5b is an accept (value+cost).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext
import sigma.ast.{
  BoxConstant, ConstantNode, ErgoTree, ExtractAmount, SInt, SOption, SType, Value
}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.data.CBox

import org.ergoplatform.ErgoBox
import sigma.serialization.SigmaSerializer

object AuthoredIngressRules {

  val V2: Byte = VersionContext.JitActivationVersion // v5 / mainnet surface (2,2)
  val V3: Byte = VersionContext.V6SoftForkVersion    // v6 (3,3)
  val Source   = "santa:authored-ingress-rules"

  // Witness 5's version-source pair splits into two op-keys — one per ErgoTree/activated
  // version — because the taxonomy files by `activated` (the v5/v6 dir is single-activated;
  // tools/validate path_envelope_guard rejects a mixed-activated file). Same split idiom as
  // AuthoredOptionSemantics (OpNonzeroTag v6 / OpPreV3Gate v5). The two halves are the
  // conceptual pair: each is the OTHER's version source.
  val OpSubstOuterV2 = "substConstants:version_source_outer_v2" // 5a reject, v5/authored
  val OpSubstOuterV3 = "substConstants:version_source_outer_v3" // 5b accept, v6/authored
  val OpRule1012HeaderSize = "Rule1012:header_size_bit"
  val OpRule1019CheckV6    = "Rule1019:check_v6_type"

  // closed trees (no var read) → the dummy input is ignored (the witnesses error/eval
  // before any context-var lookup).
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  // ── Witness 7 construction ──────────────────────────────────────────────────
  // The corpus min-box fixture (Box_properties_equivalence_new_features), reused as
  // the carrier — the same fixture AuthoredSerialize / AuthoredGetReg* draw on.
  private val MinBoxHex =
    "c0843d0b0208d3000000000000000000000000000000000000000000000000000000000000000000000000"

  /** R4 = Some(5) : Option[Int] — the v6-only register type whose re-entry trips Rule-1019.
    * Same constant form as AuthoredOptionSemantics.someIntConst. */
  private def optionIntReg: ConstantNode[SOption[SInt.type]] =
    ConstantNode[SOption[SInt.type]](Some(5), SOption(SInt))

  /** A v3 tree carrying a BoxConstant whose R4 is Option[Int]. The box (with the Option
    * register) is serializable only at ErgoTree v3+, so both the box and the tree are
    * built under withVersions(3,3). Returns the tree_bytes_hex. The root is
    * ExtractAmount(boxConst) — any node that holds the box constant suffices, since the
    * Rule-1019 trip is at the BOX deserialize within tree deserialize, before reduction. */
  private[santa] lazy val witness7Hex: String =
    VersionContext.withVersions(V3, V3) {
      val minBox = ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(MinBoxHex).get))
      val regs: ErgoBox.AdditionalRegisters = Map(ErgoBox.R4 -> optionIntReg)
      val box = new ErgoBox(minBox.value, minBox.ergoTree, minBox.additionalTokens, regs,
        minBox.transactionId, minBox.index, minBox.creationHeight)
      val root: Value[SType] = ExtractAmount(BoxConstant(CBox(box)))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  def extract(): Map[String, Json] = {
    // ── Witness 5 — substConstants version-source pair (exact spike bytes) ──────
    // Split into two single-activated envelopes (5a → v5, 5b → v6); see the op-key note.
    val substOuterV2 = Seq(
      SpecExtract.authoredRejectEntryV(OpSubstOuterV2,
        "{ substConstants(template@v3 with Option const, …) } in an OUTER v2 tree  // JVM: Rule-1009 — Option type-code 36 not serializable at the OUTER v2 (constants read at the outer version, not the template's inner v3)",
        "1a15020e081b060128010a730010007473007301830028", "subst-outer-v2-option-const-errored#0",
        dummyInput, V2, ergoTree = 2))
    val substOuterV3 = Seq(
      SpecExtract.authoredEntryV(OpSubstOuterV3,
        "{ substConstants(template@v2 with Option const, …) } in an OUTER v3 tree  // JVM: SUCCESS — substituted template bytes as Coll[Byte] (8 bytes), cost 222",
        "1b15020e081a060128010a730010007473007301830028", "subst-outer-v3-option-const-accept#0",
        dummyInput, V3, ergoTree = 3))

    // ── Witness 6 — Rule-1012 CheckHeaderSizeBit ────────────────────────────────
    val headerSize = Seq(
      SpecExtract.authoredRejectEntryV(OpRule1012HeaderSize,
        "header 0x03 — version-3 bits set, size bit (0x08) clear  // JVM: Rule-1012 at header parse — for version > 0 the size bit must be set (errors before eval)",
        "03050101017300", "header-v3-no-size-bit-errored#0", dummyInput, V3, ergoTree = 3))

    // ── Witness 7 — Rule-1019 CheckV6Type (constructed box constant) ────────────
    val checkV6 = Seq(
      SpecExtract.authoredRejectEntryV(OpRule1019CheckV6,
        "v3 tree carrying a Box constant whose R4 is Option[Int]  // JVM: Rule-1019 at box deserialize (register ingress) — CheckV6Type rejects the v6-only Option[SInt] register type",
        witness7Hex, "box-r4-option-int-check-v6-errored#0", dummyInput, V3, ergoTree = 3))

    Map(
      OpSubstOuterV2       -> SpecExtract.authoredEnvelope(OpSubstOuterV2, substOuterV2, Source),
      OpSubstOuterV3       -> SpecExtract.authoredEnvelope(OpSubstOuterV3, substOuterV3, Source),
      OpRule1012HeaderSize -> SpecExtract.authoredEnvelope(OpRule1012HeaderSize, headerSize, Source),
      OpRule1019CheckV6    -> SpecExtract.authoredEnvelope(OpRule1019CheckV6, checkV6, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredIngressRules", extract(), outDir)
}
