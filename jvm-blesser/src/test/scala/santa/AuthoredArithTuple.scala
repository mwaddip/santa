package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored regression pins for two behavioral divergences that sigma-rust RESOLVED
// on 2026-05-31 (docs/findings/eval-jvm-vs-sigma-rust.md). They were discovered
// 2026-05-30, routed via the node session's arith-coercion-sweep, fixed, but never
// committed as vectors — so nothing guarded the fix. These pin the JVM truth so a
// future regression on ANY conformer goes red.
//
//   ArithOp.numeric_kind_mismatch  tree 009a04020504
//     mismatched-numeric binop (Int/Long). JVM COERCES to the wider type → Long 3,
//     cost 35. sigma-rust used to reject (bin-op-kind-mismatch); fixed by eni
//     `99a6cfeb` ("coerce mismatched-numeric arithmetic operands to the wider type").
//
//   Tuple.non_pair_arity3          tree 0086030101020703a413
//     flat arity-3 Tuple(TrueLeaf, Byte 7, Short 1234). JVM REJECTS ("Invalid
//     tuple" — sigma-state models tuples as nested pairs). sigma-rust used to accept
//     a flat N-ary tuple; fixed by eni `45b901a0` ("reject non-pair tuples at eval").
//
// Hand-crafted WIRE forms: a mismatched binop and a flat triple cannot be built via
// the normal AST (it rejects them at construction — which is why they are wire-level
// findings), so the exact finding bytes are used verbatim. Header is ErgoTree v0
// (0x00), so version = {activated 2, ergoTree 0}; the behavior is version-independent
// (spike: identical at activated 2 and 3), pinned on the broad v5/mainnet surface.
// Both re-blessed by the JVM oracle (fail-loud if the outcome drifts).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredArithTuple {

  val Activated: Byte = 2 // v5 / mainnet surface
  val ErgoTreeV0: Int = 0 // the finding trees' native header
  val Source = "santa:authored-arith-tuple"

  val OpArith = "ArithOp.numeric_kind_mismatch"
  val OpTuple = "Tuple.non_pair_arity3"

  // closed trees (inline constants, no var read) → the dummy input is ignored
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    val arith = Seq(
      SpecExtract.authoredEntryV(OpArith,
        "{ Int + Long (mismatched numeric kinds) }  // JVM coerces to the wider type → Long 3",
        "009a04020504", "int_long_coerced#0", dummyInput, Activated, ErgoTreeV0))
    val tuple = Seq(
      SpecExtract.authoredRejectEntryV(OpTuple,
        "{ (true, 7.toByte, 1234.toShort) as a flat arity-3 Tuple }  // JVM rejects: sigma-state tuples are nested pairs",
        "0086030101020703a413", "flat_triple-errored#0", dummyInput, Activated, ErgoTreeV0))
    Map(
      OpArith -> SpecExtract.authoredEnvelope(OpArith, arith, Source),
      OpTuple -> SpecExtract.authoredEnvelope(OpTuple, tuple, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredArithTuple", extract(), outDir)
}
