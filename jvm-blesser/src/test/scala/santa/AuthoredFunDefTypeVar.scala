package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored FunDef *type-var body* vectors (v6/authored) — ergots P6 request
// `prompts/ergots-v6-fundef-typevar-body.md`, follow-up to the HOF accept vectors.
//
// AuthoredHofV6's committed FunDef accept vector uses a CONCRETE body `(x:Int)=>x`
// (the compiler monomorphizes `id[T]` via the compile-time-only `ApplyTypes` node, so a
// compiled tree never carries a type var in a value position). Hand-built here, a FunDef
// whose lambda arg is typed by the type var ITSELF — `(x:T)=>x` — serializes + deserializes
// fine at v3 but FAILS eval: `RuntimeException: Unknown type T` (the apply path resolves the
// arg's runtime type via `stypeToRType`, which has no mapping for an unresolved `STypeVar`).
// This is an over-accept fork for a dynamically-typed impl (ergots): the runtime value flows
// through, so ergots returns a value where the JVM throws.
//
// The trigger, pinned by a throwaway 4-tree probe (sigma-state 6.0.3) and bracketed below as an
// accept+reject set so a conformer can neither over- nor under-reject:
//   • CONSTRUCT-ONLY is fine — `{ val id[T]={(x:T)=>x}; 5 }` (bound, never applied) → ACCEPT 5.
//   • APPLY throws — and it's the apply-time arg binding, NOT the body: even a body that never
//     reads the type-var arg (`(x:T)=>5`) throws once applied. So the reject keys on
//     "a type-var-typed lambda is APPLIED", independent of the body.
//
// Entries (one op, accept floor then the three apply-rejects):
//   C bound-never-applied   `{ val id[T]={(x:T)=>x}; 5 }`     → ACCEPT Int 5  (construction is fine)
//   A identity applied      `{ val id[T]={(x:T)=>x}; id(7) }` → errored       (the named case)
//   D applied/body-ignores  `{ val id[T]={(x:T)=>5}; id(7) }` → errored       (apply, not body)
//   B type-dependent        `{ val id[T]={(x:T)=>x+x}; id(7)}`→ errored       (x+x same boundary)
//
// All trees are CLOSED → an ignored dummy Int input at var 1 (mirrors AuthoredHofV6 / AuthoredAtLeast).
// Accept via authoredEntry (value+cost), rejects via authoredRejectEntry — mirrors AuthoredDeserializeContext.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{Apply, ArithOp, BlockValue, ErgoTree, FuncValue, IntConstant, SType, STypeVar,
  ValDef, ValUse, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredFunDefTypeVar {

  /** Pinned target version: full v6 (activated=3, ergoTree=3) — SFunc/type-var serialization needs v3. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-fundef-typevar"
  val Op     = "HOF FunDef type-var body"

  private val tT = STypeVar("T")

  /** Serialize a root under v3 (lenient; non-SigmaProp root). Returns (script, hex). Mirrors
    * AuthoredDeserializeContext.hex. */
  private def hex(script: String, root: Value[SType]): (String, String) =
    VersionContext.withVersions(V3, V3) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      (script, Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root)))
    }

  /** Ignored dummy input at var 1 — the trees are closed; authored{,Reject}Entry requires one. */
  private def intInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** C — `{ val id[T] = {(x:T)=>x}; 5 }`: bind a type-var lambda, never apply it. → 5. */
  private def boundNeverApplied: Value[SType] = {
    val poly = FuncValue(IndexedSeq(2 -> tT), ValUse(2, tT))
    BlockValue(IndexedSeq(ValDef(1, Seq(tT), poly)), IntConstant(5))
  }

  /** A — `{ val id[T] = {(x:T)=>x}; id(7) }`: identity applied; body reads the type-var arg. → errored. */
  private def identityApplied: Value[SType] = {
    val poly = FuncValue(IndexedSeq(2 -> tT), ValUse(2, tT))
    BlockValue(IndexedSeq(ValDef(1, Seq(tT), poly)), Apply(ValUse(1, poly.tpe), IndexedSeq(IntConstant(7))))
  }

  /** D — `{ val id[T] = {(x:T)=>5}; id(7) }`: applied, but the body NEVER reads the type-var arg. → errored. */
  private def appliedBodyIgnoresArg: Value[SType] = {
    val poly = FuncValue(IndexedSeq(2 -> tT), IntConstant(5))
    BlockValue(IndexedSeq(ValDef(1, Seq(tT), poly)), Apply(ValUse(1, poly.tpe), IndexedSeq(IntConstant(7))))
  }

  /** B — `{ val id[T] = {(x:T)=>x+x}; id(7) }`: applied, body does arithmetic on the type-var arg. → errored. */
  private def typeDependentApplied: Value[SType] = {
    val poly = FuncValue(IndexedSeq(2 -> tT), ArithOp(ValUse(2, tT), ValUse(2, tT), ArithOp.Plus.opCode))
    BlockValue(IndexedSeq(ValDef(1, Seq(tT), poly)), Apply(ValUse(1, poly.tpe), IndexedSeq(IntConstant(7))))
  }

  def extract(): Map[String, Json] = {
    val (cS, cH) = hex("{ val id[T] = {(x: T) => x}; 5 }", boundNeverApplied)
    val (aS, aH) = hex("{ val id[T] = {(x: T) => x}; id(7) }", identityApplied)
    val (dS, dH) = hex("{ val id[T] = {(x: T) => 5}; id(7) }", appliedBodyIgnoresArg)
    val (bS, bH) = hex("{ val id[T] = {(x: T) => x + x}; id(7) }", typeDependentApplied)
    val entries = Seq(
      SpecExtract.authoredEntry(Op, cS, cH, "bound-never-applied-accept#0", intInput, V3),
      SpecExtract.authoredRejectEntry(Op, aS, aH, "identity-applied-reject#1", intInput, V3),
      SpecExtract.authoredRejectEntry(Op, dS, dH, "applied-body-ignores-arg-reject#2", intInput, V3),
      SpecExtract.authoredRejectEntry(Op, bS, bH, "type-dependent-applied-reject#3", intInput, V3))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). Fails loud on a slug collision. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredFunDefTypeVar.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
