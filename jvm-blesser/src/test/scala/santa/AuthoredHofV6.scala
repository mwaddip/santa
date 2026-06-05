package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored v6 higher-order-lambda vectors (eval/v6/authored) — ergots P6 request
// `prompts/ergots-v6-hof-vectors.md`. Accept arms (value+cost) for:
//
//   Ask 1 — FunDef polymorphic identity (0xd7), confirm+bless at v0/v2/v3.
//   Ask 4 — currying / function-returning-function: Apply(Apply(f,[a]),[b]).
//   Ask 2 — function stored in a Coll[SFunc], indexed out, applied (accept at v6).
//
// (Reject arms — ask 2's v5-reject, ask 5's composite-in-pair v5-reject — and ask 3's SAny-Apply
// investigation are handled separately / reported as findings; see the ergots reply.)
//
// All trees are CLOSED (evaluate to a concrete Int; no context read) → an ignored dummy Int input,
// mirroring AuthoredAtLeast. authoredEntry blesses value+cost via the JVM oracle and fails loud if
// eval rejects. Manual AST; mirrors AuthoredPowHit / AuthoredAtLeast.
//
// Note (ask 1 finding): a FunDef with a *polymorphic body* (x:T)=>x deserializes but fails at eval
// (RuntimeException: Unknown type T — the type var is unresolvable at runtime; the monomorphizing
// ApplyTypes node is compile-time-only, not serializable). The gating vector uses a concrete body —
// still 0xd7/FunDef structurally — which exercises the parse-accept + bind-rhs-ignoring-tpeArgs path.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{Apply, ArithOp, BlockValue, ByIndex, ConcreteCollection, ErgoTree, FuncValue,
  IntConstant, SCollection, SFunc, SInt, SType, STypeVar, ValDef, ValUse, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredHofV6 {

  val Source     = "santa:authored-hof-v6"
  val OpFunDef   = "HOF FunDef polymorphic identity"
  val OpCurrying = "HOF currying Apply of Apply"
  val OpCollFunc = "HOF function in Coll of SFunc"

  private val NoTpeArgs = Seq.empty[STypeVar]

  /** `{ let id[T] = (x:Int)=>x; id(7) }` → Int 7. FunDef (0xd7): tpeArgs=[T], concrete body. */
  private def funDefIdentityTree: Value[SType] = {
    val tT      = STypeVar("T")
    val poly    = FuncValue(IndexedSeq(2 -> SInt), ValUse(2, SInt))  // (x: Int) => x
    val funDef  = ValDef(1, Seq(tT), poly)                          // let id[T] = (x:Int)=>x  (FunDef, 0xd7)
    val applied = Apply(ValUse(1, poly.tpe), IndexedSeq(IntConstant(7)))   // id(7) → 7
    BlockValue(IndexedSeq(funDef), applied)
  }

  /** Ask 4 — `{ val add = (a:Int)=>(b:Int)=>a+b; add(3)(1) }` → Int 4 (curried Apply(Apply(f,[3]),[1])). */
  private def curryingTree: Value[SType] = {
    val inner   = FuncValue(IndexedSeq(3 -> SInt), ArithOp(ValUse(2, SInt), ValUse(3, SInt), ArithOp.Plus.opCode))  // (b)=>a+b
    val add     = FuncValue(IndexedSeq(2 -> SInt), inner)                                    // (a)=>(b)=>a+b
    val addDef  = ValDef(1, NoTpeArgs, add)
    val applied = Apply(Apply(ValUse(1, add.tpe), IndexedSeq(IntConstant(3))), IndexedSeq(IntConstant(1)))
    BlockValue(IndexedSeq(addDef), applied)
  }

  /** Ask 2 — `{ val fs = Coll((x)=>x+1, (x)=>x*2); fs(0)(5) }` → Int 6 (function in Coll[SFunc]). */
  private def collFuncTree: Value[SType] = {
    val inc     = FuncValue(IndexedSeq(2 -> SInt), ArithOp(ValUse(2, SInt), IntConstant(1), ArithOp.Plus.opCode))     // (x)=>x+1
    val dbl     = FuncValue(IndexedSeq(3 -> SInt), ArithOp(ValUse(3, SInt), IntConstant(2), ArithOp.Multiply.opCode)) // (x)=>x*2
    val fnType  = SFunc(IndexedSeq(SInt), SInt)
    val fs      = ConcreteCollection(IndexedSeq(inc, dbl), fnType)
    val fsDef   = ValDef(1, NoTpeArgs, fs)
    val picked  = ByIndex(ValUse(1, SCollection(fnType)), IntConstant(0))   // fs(0) = inc
    val applied = Apply(picked, IndexedSeq(IntConstant(5)))                 // inc(5) → 6
    BlockValue(IndexedSeq(fsDef), applied)
  }

  /** Serialize a root under the given ErgoTree version, lenient (non-SigmaProp root). */
  private def hexOf(root: Value[SType], v: Byte): String =
    VersionContext.withVersions(v, v) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, v))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  /** Ignored dummy input at var 1 — the trees are closed; authoredEntry requires one. */
  private def dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    val V3: Byte = VersionContext.V6SoftForkVersion
    // Ask 1 — FunDef (0xd7) at v6 (activated 3). It ALSO deserializes+evals identically at v0 and v2
    // (confirmed: Int 7, cost 58, only the header byte differs) — reported to ergots as a finding, not
    // committed (the corpus has no v0 dir, and the v5/v6 dirs each pin one activated version).
    val funDefScript = "{ val id[T] = {(x: Int) => x}; id(7) }  // FunDef 0xd7: tpeArgs=[T], concrete body"
    val funDefEntry = SpecExtract.authoredEntry(OpFunDef, funDefScript, hexOf(funDefIdentityTree, V3), "v3#0", dummyInput, V3)
    // Asks 4 + 2 — accept at v6 (activated 3).
    val curryingEntry = SpecExtract.authoredEntry(OpCurrying,
      "{ val add = {(a:Int)=>{(b:Int)=>a+b}}; add(3)(1) }", hexOf(curryingTree, V3), "add(3)(1)#0", dummyInput, V3)
    val collFuncEntry = SpecExtract.authoredEntry(OpCollFunc,
      "{ val fs = Coll({(x:Int)=>x+1},{(x:Int)=>x*2}); fs(0)(5) }", hexOf(collFuncTree, V3), "fs(0)(5)#0", dummyInput, V3)
    // Reject arms (ask 2 v5, ask 5 composite) are NOT committable vectors: serializing an SFunc-bearing
    // tree at v2 throws `MatchError: SFunc` in TypeSerializer (the SFunc type code is gated < v3) — so
    // there are no v2-header bytes to commit; the v5-reject is a serialization-level gate, reported as a
    // finding to ergots (stronger than a deserialize-reject). Ask 3 (SAny-Apply) is likewise reported.
    Map(
      OpFunDef   -> SpecExtract.authoredEnvelope(OpFunDef, Seq(funDefEntry), Source),
      OpCurrying -> SpecExtract.authoredEnvelope(OpCurrying, Seq(curryingEntry), Source),
      OpCollFunc -> SpecExtract.authoredEnvelope(OpCollFunc, Seq(collFuncEntry), Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredHofV6.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
