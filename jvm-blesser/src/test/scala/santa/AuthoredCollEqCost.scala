package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored Coll-equality JIT-cost vectors (v5/authored) — the compared-count pin.
//
// DataValueComparer charges the per-item equality cost only for the elements
// COMPARED BEFORE the result is determined: equalColls / equalCOA_Prim return the
// compared index `i` to addSeqCost(costKind){…}, which charges costKind.cost(i)
// (CErgoTreeEvaluator.scala:412). A Coll-eq that mismatches early therefore costs
// strictly LESS than one that mismatches late or compares equal — the cost tracks
// compared-count, NOT the full operand length.
//
// This was a live consensus-cost divergence: the exhaustive testnet-v6 validate walk
// found h28931 in0, where ergots (and, on the resulting check, sigma-rust's JIT costing)
// charged the per-item cost on the FULL collection length regardless of short-circuit,
// over-charging on early mismatch (+6 vs the JVM's 604). Both fixed it (ergots
// relation.ts → compared-count; sigma-rust data_value_comparer.rs, jit-costing-final
// 499bd7f5 / eni a7446d09). h28931 itself is a santa-eval/v6-fullctx capture (needs a
// per-runner full-context arm to grade); THIS is the small, directly-gradeable
// santa-eval/v2 repro sigma-rust asked for — same-length Colls mismatching at varying
// indices, value+cost blessed from the JVM eval (EvalCore IS sigma-state 6.0.3, the
// oracle; no spec-declared expected to cross-check, the rebless philosophy). A
// full-length-charging impl gives the SAME (max) cost for every early-mismatch entry
// → it goes red exactly on those.
//
// Two families span the two comparer sub-paths sigma-rust fixed, at the two cost
// granularities the JVM JIT actually uses (probed on the oracle):
//   COA     — Coll[Byte] via ByteArrayConstant (one node holds the whole array):
//             equalCOA_Prim charges primitives in COARSE ~128-element CHUNKS (+2/chunk),
//             so a length-512 array makes the mismatch chunk visible — cost steps
//             20→22→24→26 as the mismatch moves into later chunks (compared-count,
//             chunk-granular). This is the path sigma-rust's "COA chunk-boundary" test pins.
//   generic — Coll[Coll[Byte]] (element type Coll[Byte] is non-primitive): equalColls
//             recurses SMOOTHLY per element (~20/element); single-byte inners isolate the
//             OUTER compared-count → a large, per-element split (compares 1 vs N).
// Closed-constant operands (no context read) so the comparer is the only op that can
// diverge — mirrors AuthoredSigmaPropEq's unequal family, which pins the same short-
// circuit topology for SigmaProp/EcPoint equality. Honest provenance ⇒ v5/authored.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ByteArrayConstant, ConcreteCollection, EQ, ErgoTree, SByte, SCollection, SType, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredCollEqCost {

  /** v5 (activated=2, ergoTree=2) = JitActivationVersion — the comparer's per-item cost is
    * JIT (activation-invariant, like the SigmaProp-eq finding), and v5 groups this with the
    * other authored equality vectors (EQ_of_SigmaProp*). */
  val V2: Byte = VersionContext.JitActivationVersion

  val Source    = "santa:authored-coll-eq-cost"
  val OpCoa     = "Coll.eq_compared_count_coa"
  val OpGeneric = "Coll.eq_compared_count_generic"

  /** Dummy input — the trees are closed (no getVar); authoredEntry requires one. */
  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  // ── COA primitive path: Coll[Byte] via ByteArrayConstant (one node / whole array).
  //    Length 512 so the mismatch crosses the ~128-element COA chunk boundaries. ──
  private val LCoa = 512
  /** base[i] = i (mod 256); a fresh array each call (diffAt flips in place). */
  private def baseBytes: Array[Byte] = Array.tabulate(LCoa)(i => i.toByte)
  /** A copy of base differing from it at EXACTLY index k (EQ(base, _) compares k+1 elems). */
  private def diffAt(k: Int): Array[Byte] = { val a = baseBytes; a(k) = (a(k) ^ 0xFF).toByte; a }

  private def coaTree(a: Array[Byte], b: Array[Byte]): String =
    VersionContext.withVersions(V2, V2) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, EQ(ByteArrayConstant(a), ByteArrayConstant(b))))
    }

  private def coaEntries: Seq[Json] = {
    // mismatch indices spread across the chunks: each later mismatch compares one more
    // chunk → strictly higher cost, while the operands stay the same length (LCoa).
    val specs: Seq[(String, Array[Byte], String)] = Seq(
      ("equal-len512#0",    baseBytes,   s"{ Coll[Byte]($LCoa) == equal } compares all $LCoa (chunk-full), true"),
      ("mismatch-at-0#1",   diffAt(0),   "{ Coll[Byte] eq; operands differ at index 0 } compares 1 (chunk 0), false"),
      ("mismatch-at-150#2", diffAt(150), "{ Coll[Byte] eq; operands differ at index 150 } compares 151, false"),
      ("mismatch-at-300#3", diffAt(300), "{ Coll[Byte] eq; operands differ at index 300 } compares 301, false"),
      ("mismatch-at-450#4", diffAt(450), "{ Coll[Byte] eq; operands differ at index 450 } compares 451, false"))
    specs.map { case (name, b, script) =>
      SpecExtract.authoredEntry(OpCoa, script, coaTree(baseBytes, b), name, dummyInput, V2)
    }
  }

  // ── generic recursive path: Coll[Coll[Byte]] (single-byte inners isolate the OUTER
  //    compared-count; element type Coll[Byte] is non-primitive ⇒ equalColls, not COA) ──
  private val LGen = 8
  private val ByteColl = SCollection(SByte)
  private def baseInners: Seq[Array[Byte]] = (0 until LGen).map(i => Array(i.toByte))
  private def innersDiffAt(k: Int): Seq[Array[Byte]] = baseInners.updated(k, Array((k ^ 0xFF).toByte))

  private def genericTree(a: Seq[Array[Byte]], b: Seq[Array[Byte]]): String =
    VersionContext.withVersions(V2, V2) {
      def mkColl(inners: Seq[Array[Byte]]): Value[SType] =
        ConcreteCollection(inners.toArray.map(x => ByteArrayConstant(x): Value[SType]), ByteColl).asInstanceOf[Value[SType]]
      val root: Value[SType] = EQ(mkColl(a), mkColl(b))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  private def genericEntries: Seq[Json] = {
    val specs: Seq[(String, Seq[Array[Byte]], String)] = Seq(
      ("equal-len8#0",    baseInners,      s"{ Coll[Coll[Byte]]($LGen) == equal } outer compares all $LGen, true"),
      ("mismatch-at-0#1", innersDiffAt(0), "{ Coll[Coll[Byte]] eq; differ at outer index 0 } outer compares 1, false"),
      ("mismatch-at-3#2", innersDiffAt(3), "{ Coll[Coll[Byte]] eq; differ at outer index 3 } outer compares 4, false"),
      ("mismatch-at-7#3", innersDiffAt(7), s"{ Coll[Coll[Byte]] eq; differ at outer index 7 } outer compares $LGen, false"))
    specs.map { case (name, b, script) =>
      SpecExtract.authoredEntry(OpGeneric, script, genericTree(baseInners, b), name, dummyInput, V2)
    }
  }

  /** op -> v2 envelope. */
  def extract(): Map[String, Json] = Map(
    OpCoa     -> SpecExtract.authoredEnvelope(OpCoa, coaEntries, Source),
    OpGeneric -> SpecExtract.authoredEnvelope(OpGeneric, genericEntries, Source))

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v5/authored/ once inspected). */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredCollEqCost", vectors, outDir)
}
