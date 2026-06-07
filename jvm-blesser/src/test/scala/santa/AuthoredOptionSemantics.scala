package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored SOption-semantics vectors — ergots f4 epilogue ask 2 (a + c).
// Base tree: a single SOption[SInt] = Some(5) DATA constant, serialized at ErgoTree
// v3 → `1b060128010a7300` (header | size | numConst | type 28 | Option-tag 01 | Int 0a | body).
//
//   2a — nonzero Option tag. JVM `VLQReader.getOption`: ANY nonzero tag = Some (reads
//     the payload). Patch the tag byte 01→02 (2801→2802). Expected JVM: Some(Int 5),
//     cost 1 — pins that tag 0x02 is still Some. (ergots parse-svalue.ts may already
//     accept tag≥2 → this likely confirms rather than diverges.)
//
//   2c — pre-v3 Option DATA constant gate. The JVM rejects Option DATA constants in
//     ErgoTree < v3 (DataSerializer `isV3OrLaterErgoTreeVersion` gate — ValidationRule
//     1009). The constant can't even be SERIALIZED pre-v3, so the vector hand-patches
//     the v3 header byte to v2 (1b→1a). Expected JVM: errored (parse-reject at decode).
//     ergots parses Option constants at any version → over-accept fork.
//
// 2a is v6 (activated 3, ergoTree 3); 2c is v5 (activated 2, ergoTree 2 — the gate is
// on the tree's ergoTree version). Ask 2b (DeserializeRegister tag≥2) is NOT here: it
// needs raw register bytes the harness can't yet express — flagged to ergots.
// JVM-oracle-blessed (fail-loud on drift).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ConstantNode, ErgoTree, SInt, SOption, SType, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredOptionSemantics {

  val V2: Byte = VersionContext.JitActivationVersion // v5 (2,2)
  val V3: Byte = VersionContext.V6SoftForkVersion    // v6 (3,3)
  val Source   = "santa:authored-option-semantics"

  val OpNonzeroTag = "SOption.nonzero_data_tag"
  val OpPreV3Gate  = "SOption.pre_v3_data_constant"

  private def serializeAt(v: Byte, root: Value[SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, v))
    VersionContext.withVersions(v, v) { Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root)) }
  }
  private def someIntConst: Value[SType] = ConstantNode[SOption[SInt.type]](Some(5), SOption(SInt))

  // base v3 serialization of Some(5):SOption[SInt]; both asks derive from it by a 1-byte patch.
  private lazy val baseV3: String = {
    val hex = serializeAt(V3, someIntConst)
    if (!hex.contains("2801"))
      sys.error(s"AuthoredOptionSemantics: expected the Option type+Some-tag '2801' in $hex")
    hex
  }

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    val tag2Hex   = baseV3.replace("2801", "2802")          // Option tag 01 → 02
    val preV3Hex  = "1a" + baseV3.substring(2)               // header v3 (1b) → v2 (1a)

    val nonzero = Seq(
      SpecExtract.authoredEntry(OpNonzeroTag,
        "{ SOption[SInt] DATA constant with Option tag 0x02 }  // JVM getOption: any nonzero = Some → Some(5)",
        tag2Hex, "option-tag-02-some#0", dummyInput, V3))
    val preV3 = Seq(
      SpecExtract.authoredRejectEntry(OpPreV3Gate,
        "{ SOption[SInt] Some DATA constant in an ErgoTree-v2 tree }  // JVM rejects Option data pre-v3 (DataSerializer gate)",
        preV3Hex, "option-const-pre-v3-errored#0", dummyInput, V2))

    Map(
      OpNonzeroTag -> SpecExtract.authoredEnvelope(OpNonzeroTag, nonzero, Source),
      OpPreV3Gate  -> SpecExtract.authoredEnvelope(OpPreV3Gate, preV3, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredOptionSemantics", extract(), outDir)
}
