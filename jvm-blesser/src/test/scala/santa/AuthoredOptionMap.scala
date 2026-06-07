package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `Option.map` (36:7) vectors (v5/authored) — MethodCall-only surface.
//
// Op-form accessors (get / getOrElse / isDefined) are already corpus-covered.
// This file covers the MethodCall HOF: `opt.map(f)` where f is a FuncValue
// (the only way to pass a lambda in a closed ErgoTree).
//
// MapMethod (methodId=7): SFunc(Array(ThisType, SFunc(tT, tR)), SOption(tR), Array(paramT, paramR))
//   FixedCost(JitCost(20)) — probe-blessed cost breakdown:
//     GetVar   JitCost(10) + MethodCall dispatch JitCost(4) +
//     MapMethod JitCost(20) + Plus JitCost(10) + GetVar lookup JitCost(10) +
//     ValUse JitCost(5) + IntConstant JitCost(1) +
//     FuncValue creation JitCost(5) = 65 total (probe-verified)
//
// Construction:
//   receiver = GetVar(1.toByte, SInt)      — the Option[Int] at context var 1
//   lambda   = FuncValue(Vector((2, SInt)), ArithOp(ValUse(2, SInt), IntConstant(1), Plus))
//   mc       = MethodCall.typed[...](receiver, MapMethod, IndexedSeq(lambda),
//                                    Map(STypeVar("T") -> SInt, STypeVar("R") -> SInt))
//   tree serialized inside VersionContext.withVersions(V2, V2) via LenientErgoTree.
//
// 3 entries, single op / single output file (Option.map.json):
//   map#some           — GetVar(1): input Int 5  → Option(6)  (cost 65)
//   map#identity-shape — GetVar(1): input Int 41 → Option(42) (cost 65; pins operand-independence)
//   map#none-via-absent-var — GetVar(99): input Int 5 (at var 1, unread) → oracle decides:
//     • None (Option{null}) ← authoredEntry with null inner
//     • error              ← authoredRejectEntry (if oracle throws on absent var)
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ArithOp, ErgoTree, FuncValue, GetVar, IntConstant, MethodCall, SInt, SOption,
  SOptionMethods, SType, STypeVar, ValUse, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredOptionMap {

  /** Pinned target version: v5 (activated=2, ergoTree=2) — Option.map is v5-present. */
  val V2: Byte = VersionContext.JitActivationVersion

  val Op     = "Option.map"
  val Source = "santa:authored-option-map"

  private val treeHeader: HeaderType =
    ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))

  /** Serialize a root under v5 via the lenient (non-SigmaProp-root) encoder. */
  private def hex(root: Value[_ <: SType]): String =
    VersionContext.withVersions(V2, V2) {
      Base16.encode(sigma.santa.LenientErgoTree.serialize(treeHeader, root))
    }

  /** `{ (x: Option[Int]) => x.map({(y: Int) => y + 1}) }` where x = GetVar(varId, SInt).
    * GetVar returns an Option[SInt] at eval time (Some when var is bound, None when absent). */
  private def mapTree(varId: Byte): String = {
    val receiver: Value[SOption[SInt.type]] = GetVar(varId, SInt)
    val lambda: Value[SType] =
      FuncValue(IndexedSeq(2 -> SInt), ArithOp(ValUse(2, SInt), IntConstant(1), ArithOp.Plus.opCode))
    val mc: Value[SOption[SInt.type]] =
      MethodCall.typed[Value[SOption[SInt.type]]](
        receiver.asInstanceOf[Value[SType]],
        SOptionMethods.MapMethod,
        IndexedSeq(lambda),
        Map(STypeVar("T") -> SInt, STypeVar("R") -> SInt))
    hex(mc)
  }

  // Memoized tree hexes — computed once, pinned by ANCHOR tests.
  private lazy val var1Tree: String  = mapTree(1.toByte)
  private lazy val var99Tree: String = mapTree(99.toByte)

  private def intInput(n: Int): Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(n))

  def extract(): Map[String, Json] = {
    val some5  = SpecExtract.authoredEntry(Op, "{ (x: Option[Int]) => x.map({(y: Int) => y + 1}) }",
      var1Tree, "map#some", intInput(5), V2)
    val some42 = SpecExtract.authoredEntry(Op, "{ (x: Option[Int]) => x.map({(y: Int) => y + 1}) }",
      var1Tree, "map#identity-shape", intInput(41), V2)

    // absent-var entry: bind Int 5 at var 1 (unused), tree reads GetVar(99, SInt) → None at eval.
    // authoredEntry calls evalApplied which runs the JVM oracle.
    // If the oracle returns None (Option{null}) → stays authoredEntry.
    // If the oracle throws → we must use authoredRejectEntry.
    // authoredEntry will sys.error if the oracle errors; we catch that to fall back.
    val absentEntry: Json =
      try {
        SpecExtract.authoredEntry(Op,
          "{ Option.map over an absent context var }",
          var99Tree, "map#none-via-absent-var", intInput(5), V2)
      } catch {
        case t: Throwable if t.getMessage != null && t.getMessage.contains("apply-eval failed") =>
          // Oracle threw on absent var — author as reject.
          SpecExtract.authoredRejectEntry(Op,
            "{ Option.map over an absent context var }",
            var99Tree, "map#none-via-absent-var", intInput(5), V2)
      }

    val entries = Seq(some5, some42, absentEntry)
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredOptionMap", extract(), outDir)
}
