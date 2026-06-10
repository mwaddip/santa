package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored SFunc-arity witnesses (ergots Ask 11 — the checkType-family residual).
// sigma-state supports ONLY unary functions at eval: FuncValue.eval switches on
// args.length==1 (values.scala:1053, "Function must have 1 argument") and Apply.eval
// on args.length==1 (values.scala:1243, "Function application must have 1 argument";
// the in-source comment confirms 0/2+-ary application is unsupported in v4.x/v5.0 by
// design). Both serializers are arity-agnostic (FuncValueSerializer reads numArgs,
// ApplySerializer a plain value seq), so non-unary trees have REAL wire bytes — the
// reject is eval-time, never parse-time. Spike-confirmed (7-tree bracket, outcomes
// identical at activated 2 and 3; version-independent core semantics → pinned on the
// v5 surface like the rest of the checkType family, {activated 2, ergoTree 0}).
//
// Two ops, two distinct seams:
//
//   FuncValue.non_unary_arity — the closure-CREATION gate (FuncValue.eval):
//     funcvalue-2arg-applied-errored#0     { val add=(x:Int,y:Int)=>x+y; add(3,4) }
//     funcvalue-2arg-bound-errored#0       { val add=(x:Int,y:Int)=>x+y; 5 }
//       KEY PIN: BlockValue evaluates ValDefs EAGERLY, so a multi-arg lambda rejects
//       at closure creation even when never applied — the OPPOSITE of the FunDef
//       type-var family (HOF_FunDef_type_var_body), where construct accepts and only
//       apply throws. An apply-time-only gate under-rejects this tree.
//     funcvalue-0arg-errored#0             { val f=()=>5; f() }   (tDom.length=0 side)
//     funcvalue-2arg-lazy-if-accept#0      if(true) 5 else { val add=…; add(3,4) } → Int 5
//       the over-reject guard: the bytes CONTAIN a 2-arg FuncValue; If.eval is lazy,
//       the lambda never evaluates, the tree ACCEPTS. A parse-time arity gate would
//       wrongly reject it (the exact "gating blind risks a new divergence" hazard).
//
//   Apply.non_unary_arity — the APPLICATION gate (Apply.eval), isolated by giving the
//   lambda a legal unary arity so closure creation succeeds first:
//     apply-2-args-on-unary-errored#0      { val inc=(x:Int)=>x+1; inc(3,4) }
//     apply-0-args-on-unary-errored#0      { val inc=(x:Int)=>x+1; inc() }
//     apply-unary-control-accept#0         { val inc=(x:Int)=>x+1; inc(41) } → Int 42
//
// Reachability finding (reported to ergots, not committable): the checkType arm the
// ask cited — SType.isValueOfType's SFunc case (SType.scala:204-205 "Unsupported
// function type") — is UNREACHABLE via wire bytes: FuncValue.eval/Apply.eval throw
// before any value can be checked against a non-unary SFunc type, and TypeSerializer
// has no SFunc type code (a declared SFunc type enters a tree only through FuncValue's
// arg-type reconstruction / a ValDef rhs type). Same shape as the String-EQ
// "Unknown type SString" unreachability finding.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{Apply, ArithOp, BlockValue, FuncValue, If, IntConstant, SInt, SType,
  STypeVar, TrueLeaf, ValDef, ValUse, Value}
import sigma.ast.ErgoTree.ZeroHeader

object AuthoredSFuncArity {

  val Activated: Byte = 2 // v5 / mainnet surface (spike: identical at activated 3)
  val ErgoTreeV0: Int = 0 // segregated-v0 wire forms (header 0x10)
  val Source = "santa:authored-sfunc-arity"

  val OpFuncValue = "FuncValue.non_unary_arity"
  val OpApply     = "Apply.non_unary_arity"

  private val NoTpeArgs = Seq.empty[STypeVar]

  // closed trees (no var read) → the dummy input is ignored
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def add2 = FuncValue(IndexedSeq(2 -> SInt, 3 -> SInt),
    ArithOp(ValUse(2, SInt), ValUse(3, SInt), ArithOp.Plus.opCode))              // (x:Int,y:Int) => x+y
  private def inc1 = FuncValue(IndexedSeq(2 -> SInt),
    ArithOp(ValUse(2, SInt), IntConstant(1), ArithOp.Plus.opCode))               // (x:Int) => x+1
  private def const0 = FuncValue(IndexedSeq.empty[(Int, SType)], IntConstant(5)) // () => 5

  private def applied2Tree: Value[SType] =
    BlockValue(IndexedSeq(ValDef(1, NoTpeArgs, add2)),
      Apply(ValUse(1, add2.tpe), IndexedSeq(IntConstant(3), IntConstant(4))))
  private def bound2Tree: Value[SType] =
    BlockValue(IndexedSeq(ValDef(1, NoTpeArgs, add2)), IntConstant(5))
  private def lazyIfTree: Value[SType] =
    If(TrueLeaf, IntConstant(5), applied2Tree)
  private def zeroArgTree: Value[SType] =
    BlockValue(IndexedSeq(ValDef(1, NoTpeArgs, const0)),
      Apply(ValUse(1, const0.tpe), IndexedSeq.empty))
  private def apply2on1Tree: Value[SType] =
    BlockValue(IndexedSeq(ValDef(1, NoTpeArgs, inc1)),
      Apply(ValUse(1, inc1.tpe), IndexedSeq(IntConstant(3), IntConstant(4))))
  private def apply0on1Tree: Value[SType] =
    BlockValue(IndexedSeq(ValDef(1, NoTpeArgs, inc1)),
      Apply(ValUse(1, inc1.tpe), IndexedSeq.empty))
  private def unaryControlTree: Value[SType] =
    BlockValue(IndexedSeq(ValDef(1, NoTpeArgs, inc1)),
      Apply(ValUse(1, inc1.tpe), IndexedSeq(IntConstant(41))))

  /** Serialize a root as a segregated ErgoTree-v0 wire form (header 0x10). */
  private def hexAtV0(root: Value[SType]): String =
    VersionContext.withVersions(0.toByte, 0.toByte) {
      Base16.encode(sigma.santa.LenientErgoTree.serialize(ZeroHeader, root))
    }

  def extract(): Map[String, Json] = {
    val funcValue = Seq(
      SpecExtract.authoredRejectEntryV(OpFuncValue,
        "{ val add = (x:Int,y:Int) => x+y; add(3,4) }  // JVM: Function must have 1 argument — FuncValue.eval rejects non-unary arity at closure creation",
        hexAtV0(applied2Tree), "funcvalue-2arg-applied-errored#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredRejectEntryV(OpFuncValue,
        "{ val add = (x:Int,y:Int) => x+y; 5 }  // JVM: same error though NEVER APPLIED — BlockValue evals ValDefs eagerly (reject at closure creation, unlike the FunDef type-var family)",
        hexAtV0(bound2Tree), "funcvalue-2arg-bound-errored#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredRejectEntryV(OpFuncValue,
        "{ val f = () => 5; f() }  // JVM: Function must have 1 argument — the tDom.length=0 side of the non-unary gate",
        hexAtV0(zeroArgTree), "funcvalue-0arg-errored#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(OpFuncValue,
        "if (true) 5 else { val add = (x:Int,y:Int) => x+y; add(3,4) }  // ACCEPTS: If.eval is lazy, the 2-arg lambda never evaluates — the reject is eval-time, NOT parse-time (over-reject guard)",
        hexAtV0(lazyIfTree), "funcvalue-2arg-lazy-if-accept#0", dummyInput, Activated, ErgoTreeV0))
    val apply = Seq(
      SpecExtract.authoredRejectEntryV(OpApply,
        "{ val inc = (x:Int) => x+1; inc(3,4) }  // JVM: Function application must have 1 argument — Apply.eval's own arity gate (the unary closure creates fine first)",
        hexAtV0(apply2on1Tree), "apply-2-args-on-unary-errored#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredRejectEntryV(OpApply,
        "{ val inc = (x:Int) => x+1; inc() }  // JVM: Function application must have 1 argument — the zero-args application side",
        hexAtV0(apply0on1Tree), "apply-0-args-on-unary-errored#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(OpApply,
        "{ val inc = (x:Int) => x+1; inc(41) }  // ACCEPTS Int 42 — the unary control: same shape, legal arity (the family pins arity, not the construct)",
        hexAtV0(unaryControlTree), "apply-unary-control-accept#0", dummyInput, Activated, ErgoTreeV0))
    Map(
      OpFuncValue -> SpecExtract.authoredEnvelope(OpFuncValue, funcValue, Source),
      OpApply     -> SpecExtract.authoredEnvelope(OpApply, apply, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredSFuncArity", extract(), outDir)
}
