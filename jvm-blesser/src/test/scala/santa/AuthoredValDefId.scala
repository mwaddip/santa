package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored ValDef.id wire-bound witness (ergots v6 audit REL-WIRE-ID-01).
//
// The JVM `ValDefSerializer` parses `ValDef.id` with `getUIntExact`, which rejects
// any value above `Int.MaxValue` (it throws ArithmeticException at DESERIALIZE —
// before any eval). ergots parses the id with `readVlqU` and happily binds/evals an
// id like 0x80000000 — so it ACCEPTS a tree the JVM rejects (an over-acceptance
// divergence; not v6-specific, found while auditing the v6 branch).
//
// Oracle-confirmed (spike, sigma-state 6.0.3):
//   id 0x7fffffff (Int.MaxValue) -> getUIntExact accepts -> Int 7   (the accept boundary)
//   id 0x80000000 (max+1)        -> LEFT(ArithmeticException: Int overflow)  (treeVer 0 = deser-stage)
//
// Construction: build `{ val x = 7; x }` with id = Int.MaxValue (the id serializes as
// VLQ `ff ff ff ff 07` for BOTH the ValDef and the matching ValUse), then PATCH both
// runs to `80 80 80 80 08` (= 2147483648) — a same-length byte edit (the over-max id
// can't be an honest Scala Int, so it can only arise from crafted wire bytes). The JVM
// rejects at the ValDef id, before the ValUse is read.
//
// Deliberately NARROW: ValUse.id and FuncValue arg-ids use `getUInt.toInt` (intentional
// wrapping, the JVM documents it) — this witness pins ONLY the `getUIntExact` field.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BlockValue, IntConstant, SInt, SType, ValDef, ValUse, Value}
import sigma.ast.ErgoTree.ZeroHeader
import sigma.santa.LenientErgoTree

object AuthoredValDefId {

  val Activated: Byte = 2 // v5 / mainnet surface; the field is version-independent
  val ErgoTreeV0: Int = 0 // segregated-v0 wire form (header 0x10)
  val Source = "santa:authored-valdef-id"
  val Op = "ValDef.id_int_max_bound"

  // closed tree (no var read) → the dummy input is ignored (matches AuthoredSFuncArity)
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** `{ val x = 7; x }` with the ValDef/ValUse id = `id`. */
  private def valTree(id: Int): Value[SType] =
    BlockValue(IndexedSeq(ValDef(id, Nil, IntConstant(7))), ValUse(id, SInt))

  private def hexAtV0(root: Value[SType]): String =
    VersionContext.withVersions(0.toByte, 0.toByte) {
      Base16.encode(LenientErgoTree.serialize(ZeroHeader, root))
    }

  // id = Int.MaxValue (0x7fffffff): VLQ `ffffffff07` x2 (ValDef.id + ValUse.id)
  val acceptHex: String = hexAtV0(valTree(Int.MaxValue))
  // patch both id runs to 0x80000000 (`8080808008`): same length, no shift
  val rejectHex: String = acceptHex.replace("ffffffff07", "8080808008")

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredEntryV(Op,
        "{ val x = 7; x }  // ValDef.id = Int.MaxValue (0x7fffffff): getUIntExact accepts the inclusive max -> Int 7 (the accept boundary)",
        acceptHex, "valdef-id-int-max-accept#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredRejectEntryV(Op,
        "{ val x = 7; x }  // ValDef.id = 0x80000000 (Int.MaxValue+1): JVM ValDefSerializer reads id with getUIntExact -> ArithmeticException at DESERIALIZE. ergots reads it with readVlqU and ACCEPTS (binds+evals) — the over-acceptance divergence (REL-WIRE-ID-01). ValUse/FuncValue ids use getUInt.toInt (wrapping) and are deliberately NOT bound by this.",
        rejectHex, "valdef-id-overflow-errored#0", dummyInput, Activated, ErgoTreeV0))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredValDefId", extract(), outDir)
}
