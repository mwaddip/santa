package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored eval vectors for sigma-rust's `santa-probe-scollection-methods-1-10` (2026-11-07):
// a MethodCall (or, at zero arguments, a PropertyCall) to SCollection method ids 1-10 —
// size 1, getOrElse 2, map 3, exists 4, fold 5, forall 6, slice 7, filter 8, append 9, apply 10.
//
// These are the ids the JVM's v5 `SCollection` method table holds and `CheckAndGetMethod`
// resolves; sigma-rust's table starts at id 14, so it refuses all ten at parse with
// `UnknownMethodId`. The wire half of that is pinned by
// `wire/v6/authored/ErgoTree.scollection_methodcall_v5_ids.json` — all ten parse and round-trip
// byte-identically on the JVM. This file is the eval half, and it is NOT uniform:
//
//   FOUR evaluate      size (48), getOrElse (74 either way), map (120), apply (69)
//   SIX throw          exists, forall, slice, filter, append, fold — a plain RuntimeException,
//                      "Cannot find eval method def <name>_eval(...)", because among ids 1-10
//                      only `getOrElse_eval` (methods.scala:839) and `map_eval` (:862) exist
//
// So six of the ten PARSE and then never evaluate: a valid tree whose script fails. An
// implementation that refuses them at parse diverges on the wire arm; one that evaluates them (as
// the dedicated Exists/Fold/Filter/... nodes would) over-accepts here. Matching the JVM means
// parsing all ten and failing on six.
//
// `size` and `apply` evaluate although neither has a `_eval` handler either — they dispatch by
// some other route, which this batch does NOT pin down. The costs are blessed regardless.
//
// `fold` fails TWO different ways and both are entries, because an implementation could get one
// right and the other wrong:
//   - the 2-argument operator its own `FoldMethod` declares (`SFunc(Array(tOV, tIV), tOV)`)
//     throws "Function must have 1 argument" at values.scala:1082-1083;
//   - a 1-argument operator over a pair, the shape the dedicated `Fold` node uses, gets past that
//     and dies at the missing handler instead.
// There is no operator form that makes `fold` evaluate through a MethodCall.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.ast._

object AuthoredEvalSCollectionMethodCall {

  val V3: Byte = VersionContext.V6SoftForkVersion
  val Op     = "Coll.methodcall_v5_ids"
  val Source = "santa:authored-eval-scollection-methodcall"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def coll(ns: Int*): Value[SCollection[SInt.type]] =
    ConcreteCollection(ns.map(IntConstant(_)).toIndexedSeq, SInt)
  private def m(name: String): SMethod = SCollectionMethods.getMethodByName(name)
  private def plus(l: Value[SInt.type], r: Value[SInt.type]): Value[SInt.type] =
    ArithOp(l, r, ArithOp.Plus.opCode)
  private def lam1(body: Value[SType]): Value[SType] = any(FuncValue(IndexedSeq(1 -> SInt), body))

  /** A MethodCall to `name` on `obj`; with no arguments the serializer writes a PropertyCall. */
  private def mc(obj: Value[_ <: SType], name: String, args: Value[SType]*): Value[SType] =
    any(MethodCall(any(obj), m(name), args.toIndexedSeq, Map.empty))

  private val pairT = STuple(SInt, SInt)

  def treeHex(root: Value[SType], etv: Byte, sized: Boolean): String =
    VersionContext.withVersions(V3, etv) {
      val base = ErgoTree.headerWithVersion(ZeroHeader, etv)
      val header: HeaderType = if (sized) ErgoTree.setSizeBit(base) else base
      val tree = new ErgoTree(header, IndexedSeq.empty,
        Right(root.asInstanceOf[sigma.ast.syntax.SigmaPropValue]))
      Base16.encode(sigma.serialization.ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(tree))
    }

  // ── the twelve calls, shared with the wire blesser ───────────────────────────

  def callSize: Value[SType]     = mc(coll(1, 2), "size")
  def callGetOrElseOut: Value[SType] = mc(coll(1, 2), "getOrElse", any(IntConstant(5)), any(IntConstant(9)))
  def callGetOrElseIn: Value[SType]  = mc(coll(1, 2), "getOrElse", any(IntConstant(0)), any(IntConstant(9)))
  def callMap: Value[SType]      = mc(coll(1, 2), "map", lam1(any(plus(ValUse(1, SInt), IntConstant(1)))))
  def callExists: Value[SType]   = mc(coll(1, 2), "exists", lam1(any(GT(ValUse(1, SInt), IntConstant(1)))))
  def callForall: Value[SType]   = mc(coll(1, 2), "forall", lam1(any(GT(ValUse(1, SInt), IntConstant(0)))))
  def callSlice: Value[SType]    = mc(coll(1, 2, 3), "slice", any(IntConstant(1)), any(IntConstant(3)))
  def callFilter: Value[SType]   = mc(coll(1, 2), "filter", lam1(any(GT(ValUse(1, SInt), IntConstant(1)))))
  def callAppend: Value[SType]   = mc(coll(1), "append", any(coll(2)))
  def callApply: Value[SType]    = mc(coll(1, 2), "apply", any(IntConstant(1)))

  /** `fold` with the 2-argument operator its own method signature declares. */
  def callFoldTwoArg: Value[SType] = mc(coll(1, 2), "fold", any(IntConstant(0)),
    any(FuncValue(IndexedSeq(1 -> SInt, 2 -> SInt), any(plus(ValUse(1, SInt), ValUse(2, SInt))))))

  /** `fold` with a 1-argument operator over a pair — the dedicated Fold node's shape. */
  def callFoldPair: Value[SType] = mc(coll(1, 2), "fold", any(IntConstant(0)),
    any(FuncValue(IndexedSeq(1 -> pairT),
      any(plus(SelectField(ValUse(1, pairT), 1).asInstanceOf[Value[SInt.type]],
               SelectField(ValUse(1, pairT), 2).asInstanceOf[Value[SInt.type]])))))

  private def accept(script: String, why: String, root: Value[SType], name: String): Json =
    SpecExtract.authoredEntryV(Op, s"$script  // JVM: $why", treeHex(root, V3, sized = true), name,
      input, V3, ergoTree = 3)

  private def reject(script: String, why: String, root: Value[SType], name: String): Json =
    SpecExtract.authoredRejectEntryV(Op, s"$script  // JVM: $why", treeHex(root, V3, sized = true), name,
      input, V3, ergoTree = 3)

  private val NoHandler = "PARSES and then throws at eval — a plain RuntimeException, " +
    "\"Cannot find eval method def %s_eval(...)\": among ids 1-10 only getOrElse_eval and map_eval " +
    "exist (methods.scala:839, :862). An implementation that refuses this at PARSE diverges on the " +
    "wire arm instead; one that evaluates it through its dedicated node over-accepts here"

  def extract(): Map[String, Json] = {
    val entries = Seq(
      accept("{ Coll(1, 2).size }",
        "id 1, written as a PropertyCall because it takes no arguments: Int 2. Evaluates although " +
        "`size` has no _eval handler — the dispatch route for this one is not pinned by this batch",
        callSize, "size-accept#0"),
      accept("{ Coll(1, 2).getOrElse(5, 9) }",
        "id 2, index OUT of range: the default, Int 9. getOrElse_eval exists (methods.scala:839)",
        callGetOrElseOut, "getorelse-out-of-range-accept#1"),
      accept("{ Coll(1, 2).getOrElse(0, 9) }",
        "id 2, index IN range: the element, Int 1 — the default is not reached",
        callGetOrElseIn, "getorelse-in-range-accept#2"),
      accept("{ Coll(1, 2).map({ (x: Int) => x + 1 }) }",
        "id 3: Coll(2, 3). map_eval exists (methods.scala:862)",
        callMap, "map-accept#3"),
      accept("{ Coll(1, 2).apply(1) }",
        "id 10: Int 2. Evaluates although `apply` has no _eval handler, like `size`",
        callApply, "apply-accept#4"),
      reject("{ Coll(1, 2).exists({ (x: Int) => x > 1 }) }", NoHandler.format("exists"),
        callExists, "exists-errored#5"),
      reject("{ Coll(1, 2).forall({ (x: Int) => x > 0 }) }", NoHandler.format("forall"),
        callForall, "forall-errored#6"),
      reject("{ Coll(1, 2, 3).slice(1, 3) }", NoHandler.format("slice"),
        callSlice, "slice-errored#7"),
      reject("{ Coll(1, 2).filter({ (x: Int) => x > 1 }) }", NoHandler.format("filter"),
        callFilter, "filter-errored#8"),
      reject("{ Coll(1).append(Coll(2)) }", NoHandler.format("append"),
        callAppend, "append-errored#9"),
      reject("{ Coll(1, 2).fold(0, { (acc: Int, x: Int) => acc + x }) }",
        "id 5 with the TWO-argument operator FoldMethod itself declares " +
        "(SFunc(Array(tOV, tIV), tOV)): an InterpreterException, \"Function must have 1 argument\" " +
        "(values.scala:1082-1083). It fails BEFORE the handler lookup, unlike its pair twin",
        callFoldTwoArg, "fold-two-arg-op-errored#10"),
      reject("{ Coll(1, 2).fold(0, { (p: (Int, Int)) => p._1 + p._2 }) }",
        "id 5 with a ONE-argument operator over a pair, the shape the dedicated Fold node uses: " +
        "gets past the arity check and dies at the missing handler instead, " +
        "\"Cannot find eval method def fold_eval(...)\". With #10 this proves NO operator form " +
        "makes fold evaluate through a MethodCall — and the two fail at different places",
        callFoldPair, "fold-pair-op-errored#11"))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalSCollectionMethodCall", extract(), outDir)
}
