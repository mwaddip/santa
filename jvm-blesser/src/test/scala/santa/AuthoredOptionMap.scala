package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `Option.map` (36:7) vectors (v5/authored) — MethodCall-only surface.
//
// Op-form accessors (get / getOrElse / isDefined) are already corpus-covered.
// This file covers the MethodCall HOF: `opt.map(f)` where f is a FuncValue
// (the only way to pass a lambda in a closed ErgoTree).
//
// MapMethod (methodId=7): SFunc(Array(ThisType, SFunc(tT, tR)), SOption(tR), Array(paramT, paramR))
//   FixedCost(JitCost(20)) — Cost 65, verified vs sigma-state 6.0.3 sources:
//     GetVar(10) + MethodCall dispatch(4) + FuncValue creation(5) + MapMethod FixedCost(20)
//     + AddToEnvironment(5, the lambda-arg binding) + ValUse(5) + ConstantPlaceholder(1)
//     + Plus on SInt (TypeBasedCost 15) = 65
//   The None arm (39) short-circuits before the lambda runs: 10+4+5+20 — no env-bind,
//   no body. The Δ26 = AddToEnvironment(5) + ValUse(5) + CP(1) + Plus(15).
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
//   map#none-via-absent-var — GetVar(99): input Int 5 (at var 1, unread) → None (Option{null})
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

  // Absent-var id: any id not bound in the context. 99 is far outside the test inputs
  // (which bind var 1), so GetVar(99, SInt) reliably returns None at eval time.
  private val AbsentVarId: Byte = 99

  // Memoized tree hexes — computed once, pinned by ANCHOR tests.
  private lazy val var1Tree: String  = mapTree(1.toByte)
  private lazy val var99Tree: String = mapTree(AbsentVarId)

  private def intInput(n: Int): Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(n))

  def extract(): Map[String, Json] = {
    val some5  = SpecExtract.authoredEntry(Op, "{ (x: Option[Int]) => x.map({(y: Int) => y + 1}) }",
      var1Tree, "map#some", intInput(5), V2)
    val some42 = SpecExtract.authoredEntry(Op, "{ (x: Option[Int]) => x.map({(y: Int) => y + 1}) }",
      var1Tree, "map#identity-shape", intInput(41), V2)

    // absent-var entry: bind Int 5 at var 1 (unused), tree reads GetVar(AbsentVarId, SInt) → None.
    // Oracle-blessed (sigma-state 6.0.3): returns Option{null} (None), cost 39.
    // If a future JVM version changes this behaviour the ANCHOR tests below will fire — investigate.
    val absentEntry: Json =
      SpecExtract.authoredEntry(Op,
        "{ Option.map over an absent context var }",
        var99Tree, "map#none-via-absent-var", intInput(5), V2)

    val entries = Seq(some5, some42, absentEntry)
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredOptionMap", extract(), outDir)
}
