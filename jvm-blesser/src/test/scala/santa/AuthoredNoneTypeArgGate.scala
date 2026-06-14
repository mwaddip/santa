package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored pre-V3 dead-branch v6-construct gate (ergots v6 audit V6-PROPERTY-TYPEARG-GATE-01).
//
// A pre-V3 ErgoTree carrying a v6 `PropertyCall` (`Global.none[UnsignedBigInt]`, typeId
// 106 / methodId 10, with an explicit `SUnsignedBigInt` type tail) in a DEAD branch.
// The JVM validates the whole tree at DESERIALIZE — including the never-evaluated
// branch — and rejects it (the v6 `none` method is not declared pre-v3). ergots' pre-V3
// whole-tree type gate walks `MethodCall.explicitTypeArgs` but NOT
// `PropertyCall.explicitTypeArgs`, so it misses the dead-branch construct and ACCEPTS —
// an over-acceptance divergence.
//
// Oracle-confirmed (spike, sigma-state 6.0.3):
//   ergoTree v3 (v6 active): If(true, true, none[UBI]) -> Boolean true @ 12 (dead branch, If is lazy)
//   SAME bytes, header flipped to v2: LEFT(ValidationException rule 1011 "type has no declared method")
//
// Note on the reject MECHANISM: the JVM rejects via the method-version gate (none@10 is
// not declared at ErgoTree v2), caught whole-tree at deserialize. The audit hypothesised
// the type-embeddability gate (SUnsignedBigInt not embeddable pre-v3); the two are coupled
// in `none[UBI]` (both v6) and can't be isolated (none is the only PropertyCall with a wire
// type tail, and it's v6). Either way the observable divergence — JVM rejects the pre-V3
// dead-branch v6 PropertyCall, ergots accepts it — is exactly what the audit reports.
//
// Construction: build the dead-branch If in a v6 VersionContext (none / the UBI tail are
// v6-gated on write), serialize at v3, then flip the header version 3->2 (low 3 bits of
// byte0; size + segregation bits preserved) — the v2-declared tree the JVM can't honestly
// emit but can deserialize-then-reject.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{Global, If, MethodCall, SBoolean, SMethod, SType, STypeVar, SUnsignedBigInt, TrueLeaf, Value}
import sigma.ast.ErgoTree.{ZeroHeader, headerWithVersion, setSizeBit}
import sigma.santa.LenientErgoTree

object AuthoredNoneTypeArgGate {

  val Activated: Byte = 3 // a v6-activated node receiving a v2-declared tree
  val Source = "santa:authored-none-pre-v3-gate"
  val Op = "Global.none_pre_v3_dead_branch"

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `if (true) true else Global.none[UnsignedBigInt]` serialized at ErgoTree v3.
    * The cast bypasses Scala's If branch-type check; serialization dispatches on the
    * value's opcode (the none[UBI] PropertyCall + its UBI type tail are written). */
  private def deadBranchHexV3: String =
    VersionContext.withVersions(3.toByte, 3.toByte) {
      val none = MethodCall(Global, SMethod.fromIds(106.toByte, 10.toByte),
        IndexedSeq.empty, Map(STypeVar("T") -> SUnsignedBigInt))
      val root: Value[SType] =
        If(TrueLeaf, TrueLeaf, none.asInstanceOf[Value[SBoolean.type]]).asInstanceOf[Value[SType]]
      val header = setSizeBit(headerWithVersion(ZeroHeader, 3.toByte))
      Base16.encode(LenientErgoTree.serialize(header, root))
    }

  val acceptHexV3: String = deadBranchHexV3
  // flip header version 3 -> 2 (keep size + segregation bits): the SAME bytes now declare ErgoTree v2
  val rejectHexV2: String = {
    val b = Base16.decode(acceptHexV3).get
    b(0) = ((b(0) & 0xF8) | 0x02).toByte
    Base16.encode(b)
  }

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredEntryV(Op,
        "if (true) true else Global.none[UnsignedBigInt]  // ErgoTree v3 (v6 active): the dead none[UBI] branch is legal; If is lazy -> Boolean true. The accept control.",
        acceptHexV3, "none-ubi-dead-branch-v3-accept#0", dummyInput, Activated, 3),
      SpecExtract.authoredRejectEntryV(Op,
        "if (true) true else Global.none[UnsignedBigInt]  // SAME bytes, header flipped to ErgoTree v2: the v6 none[UBI] PropertyCall in the dead branch is not valid pre-v3. JVM rejects at DESERIALIZE (whole-tree, incl. the dead branch — rule 1011); ergots' pre-V3 gate misses PropertyCall.explicitTypeArgs and ACCEPTS (V6-PROPERTY-TYPEARG-GATE-01).",
        rejectHexV2, "none-ubi-dead-branch-v2-errored#0", dummyInput, Activated, 2))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredNoneTypeArgGate", extract(), outDir)
}
