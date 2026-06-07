package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `SigmaProp == SigmaProp` JIT-cost vectors (v5/authored).
//
// LanguageSpecificationV5's NEQ "predefined types" features do NOT include SigmaProp
// (verified: vectors/eval/v5/spec carries zero SigmaProp under EQ/NEQ — the SigmaProp
// values present are all constructors / `&&` / `||` / propBytes / serialize), so the
// spec-extracted corpus never exercises the SigmaProp branch of the equality cost path
// (DataValueComparer.equalDataValues → equalSigmaBoolean → equalECPoint). That blind
// spot let sigma-rust ship a flat EQ_PRIM_COST (3) for SigmaProp equality where the JVM
// charges per-node MatchType + EQ_GroupElement (172) per EcPoint — a consensus-cost
// divergence (sigma-rust SANTA_SIGMAPROP_EQ_COST_VECTOR_NEEDED.md). We AUTHOR the vector:
// build `getVar[SigmaProp](1).get == getVar[SigmaProp](1).get` and bless value+cost from
// the JVM eval (EvalCore IS sigma-state 6.0.3 — the oracle; no spec-declared expected to
// cross-check, so the eval is canonical — the rebless philosophy).
//
// Three SigmaProp shapes span the structural cost classes: ProveDlog (1 EcPoint),
// ProveDHTuple (4), CAND (per-node recursion). The input is bound to context var 1 (the
// authored v2 convention; EvalCore.evalApplied); the tree reads it and compares to itself
// — a full structural walk (no short-circuit → deterministic max cost), nothing to
// constant-fold (the operands are context reads), and the comparer is the ONLY op that can
// diverge. Honest provenance ⇒ vectors/eval/v5/authored/. Mirrors AuthoredGetVarFromInput
// (manual AST) + AuthoredSerialize (authored-input bless).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ConcreteCollection, EQ, ErgoTree, GetVar, OptionGet, SOption, SSigmaProp, SString, SType, SigmaPropConstant, TrueLeaf, Tuple, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.crypto.{CryptoConstants, EcPointType}
import sigma.data.{CAND, CSigmaProp, CTHRESHOLD, ProveDHTuple, ProveDlog, SigmaBoolean, TrivialProp}

object AuthoredSigmaPropEq {

  /** Pinned target version: v5 (activated=2, ergoTree=2) = JitActivationVersion. SigmaProp
    * `==` is a v5/mainnet op and its eval cost is activation-invariant (the cross-version
    * finding), so v5 is the honest home — it groups with the v5 equality/sigma vectors. */
  val V2: Byte = VersionContext.JitActivationVersion

  val Source       = "santa:authored-sigmaprop-eq"
  val Op           = "EQ of SigmaProp"
  val OpUnequal    = "EQ of SigmaProp unequal"
  val OpConjecture = "EQ of SigmaProp conjecture mismatch"
  val OpNested     = "EQ of nested SigmaProp conjecture mismatch"

  /** `getVar[T](1).get == getVar[T](1).get`, serialized at v5. The input is bound to context
    * var 1 by EvalCore.evalApplied; reading it (twice, as two independent context reads) and
    * comparing exercises the equality comparer on a self-equal value. The getVar/OptionGet
    * overhead is identical across conformers (already-conformant ops), so the only thing that
    * can diverge in the total cost is the equality comparer — the divergence under test. */
  private def eqSelfTree(typeName: String, elemType: SType): (String, String) =
    VersionContext.withVersions(V2, V2) {
      val l = OptionGet(GetVar(1.toByte, SOption(elemType)))
      val r = OptionGet(GetVar(1.toByte, SOption(elemType)))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
      (s"{ getVar[$typeName](1).get == getVar[$typeName](1).get }",
       Base16.encode(sigma.santa.LenientErgoTree.serialize(header, EQ(l, r))))
    }

  /** SigmaProp input as the authored v2 SValue JSON (inverse of EvalCore's SigmaProp encode:
    * raw_hex = Base16(SigmaBoolean.serializer.toBytes(sigmaTree)) — the general serializer, so
    * every SigmaBoolean variant round-trips). */
  private def sigmaPropJson(sb: SigmaBoolean): Json =
    Json.obj("kind"    -> Json.fromString("SigmaProp"),
             "raw_hex" -> Json.fromString(Base16.encode(SigmaBoolean.serializer.toBytes(sb))))

  private val gen: EcPointType = CryptoConstants.dlogGroup.generator

  // The three SigmaProp structural shapes. Point values are irrelevant to EQ cost — it is
  // EcPoint-count / node-count driven, and `x == x` walks the whole tree regardless of the
  // operands being equal (equalECPoint charges per EcPoint compared, not conditionally).
  private val proveDlog    = ProveDlog(gen)                            // 1 EcPoint
  private val proveDHTuple = ProveDHTuple(gen, gen, gen, gen)          // 4 EcPoints (gv/hv/uv/vv)
  private val cand         = CAND(Seq(ProveDlog(gen), ProveDlog(gen))) // 2 children → per-node recursion

  // Second EC point: gen^2 — distinct from gen, deterministic, same EcPointType.
  private val gen2: EcPointType =
    CryptoConstants.dlogGroup.exponentiate(gen, java.math.BigInteger.valueOf(2))

  /** Closed `EQ(SigmaPropConstant(a), SigmaPropConstant(b))` at v5. Both operands are
    * constants so the comparer walks them at eval without context reads. */
  private def unequalTree(a: SigmaBoolean, b: SigmaBoolean): String =
    VersionContext.withVersions(V2, V2) {
      val root = EQ(SigmaPropConstant(CSigmaProp(a)), SigmaPropConstant(CSigmaProp(b)))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  /** Dummy input — the unequal trees are closed (no getVar); authoredEntry requires one. */
  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** Five unequal-tree entries exercising distinct walk depths.
    * DataValueComparer.equalSigmaBoolean short-circuits at the first mismatch — the DHT
    * mismatch-at-first-point entry stops after 1 EQ_GroupElement; mismatch-at-fourth stops
    * after 4. The JVM-blessed costs PROVE the short-circuit is live. */
  private def unequalEntries: Seq[Json] = {
    val dlogA  = ProveDlog(gen);  val dlogB = ProveDlog(gen2)
    val dhtBase = ProveDHTuple(gen, gen, gen, gen)
    val dhtAtG  = ProveDHTuple(gen2, gen, gen, gen)  // first point differs → short walk
    val dhtAtV  = ProveDHTuple(gen, gen, gen, gen2)  // fourth point differs → full walk
    val candA = CAND(Seq(dlogA, dlogA)); val candB = CAND(Seq(dlogA, dlogB))
    Seq(
      ("dlog-vs-dlog2#0",     dlogA,  dlogB,   "{ pk(g) == pk(g^2) }"),
      ("dlog-vs-dht#1",       dlogA,  dhtBase, "{ pk(g) == dht(g,g,g,g) }"),
      ("dht-mismatch-at-g#2", dhtAtG, dhtBase, "{ dht(g2,g,g,g) == dht(g,g,g,g) }"),
      ("dht-mismatch-at-v#3", dhtAtV, dhtBase, "{ dht(g,g,g,g2) == dht(g,g,g,g) }"),
      ("cand-second-child#4", candA,  candB,   "{ (pkA && pkA) == (pkA && pkB) }")
    ).map { case (name, a, b, script) =>
      SpecExtract.authoredEntry(OpUnequal, script, unequalTree(a, b), name, dummyInput, V2)
    }
  }

  /** Four conjecture-MISMATCH entries pinning the comparer's argument-order ASYMMETRY
    * (the ergots F3 follow-up ask).
    * DataValueComparer.equalSigmaBoolean dispatches on the LEFT value with GUARDED
    * conjecture cases (`case CAND(_) if r.isInstanceOf[CAND]` etc.); a conjecture on the
    * left vs a different variant on the right fails every guard and falls through to
    * `sys.error` — the script eval THROWS. A LEAF on the left (ProveDlog/TrivialProp) vs
    * any different right returns false via the leaf arm's inner `case _`. Reachable from
    * honest script — `(pkA && pkB) == pkA` evals EQ over CAND-vs-ProveDlog runtime values
    * — so throw-vs-false here is a consensus VALUE fork (ergots returned false pre-F3).
    * Spike-verified: both orderings of every pair behave as the source reads; the two
    * accept twins cost envelope + outer MatchType(1) + node MatchType(1) = 4, same as the
    * unequal family's node-type-mismatch class. Reject entries carry the coarse shape
    * (value/cost null) — cost-at-throw is not blessed. */
  private def conjectureEntries: Seq[Json] = {
    val dlogA  = ProveDlog(gen)
    val candAB = CAND(Seq(dlogA, ProveDlog(gen2)))
    val cthAB  = CTHRESHOLD(1, Seq(dlogA, ProveDlog(gen2)))
    val rejects = Set(0, 3)
    Seq(
      ("cand-vs-dlog#0",       candAB,            dlogA,  "{ (pkA && pkB) == pkA }"),
      ("dlog-vs-cand#1",       dlogA: SigmaBoolean, candAB, "{ pkA == (pkA && pkB) }"),
      ("trivial-vs-dlog#2",    TrivialProp(true), dlogA,  "{ sigmaProp(true) == pkA }"),
      ("cthreshold-vs-cand#3", cthAB,             candAB, "{ cthreshold(1, pkA, pkB) == (pkA && pkB) }")
    ).zipWithIndex.map { case ((name, a, b, script), i) =>
      val mk = if (rejects(i)) SpecExtract.authoredRejectEntry _ else SpecExtract.authoredEntry _
      mk(OpConjecture, script, unequalTree(a, b), name, dummyInput, V2)
    }
  }

  /** Closed `EQ` over a one-element `Coll[SigmaProp]` at v5 — the nested-descent family.
    * `DataValueComparer.equalDataValues`'s Coll arm (case 2) has no SigmaProp `EQ_COA`
    * descriptor, so it falls to the generic `equalColls` loop → element-wise
    * `equalDataValues` → the SigmaProp arm → `equalSigmaBoolean`. The direct family's
    * argument-order asymmetry therefore reappears one level down: a conjecture-LEFT element
    * throws, a leaf-LEFT element returns false. */
  private def collTree(a: SigmaBoolean, b: SigmaBoolean): String =
    VersionContext.withVersions(V2, V2) {
      def mkColl(sb: SigmaBoolean): Value[SType] =
        ConcreteCollection(IndexedSeq(SigmaPropConstant(CSigmaProp(sb))), SSigmaProp).asInstanceOf[Value[SType]]
      val root: Value[SType] = EQ(mkColl(a), mkColl(b))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  /** Closed `EQ` over a `(SigmaProp, Boolean)` Tuple at v5 — the Tuple-descent twin.
    * `equalDataValues`'s Tuple arm (case 3) recurses component-wise via `equalDataValues`,
    * so the SigmaProp component reaches `equalSigmaBoolean` with the same asymmetry. */
  private def tupleTree(a: SigmaBoolean, b: SigmaBoolean): String =
    VersionContext.withVersions(V2, V2) {
      def mkTup(sb: SigmaBoolean): Value[SType] =
        Tuple(IndexedSeq[Value[SType]](SigmaPropConstant(CSigmaProp(sb)).asInstanceOf[Value[SType]], TrueLeaf))
          .asInstanceOf[Value[SType]]
      val root: Value[SType] = EQ(mkTup(a), mkTup(b))
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  /** Four nested-container entries pinning the conjecture-mismatch descent through `Coll`
    * and `Tuple` (sigma-rust's develop-only-gap flag: their direct EQ fix doesn't descend
    * into containers, where SigmaProps still compare via derive-PartialEq → `false` instead
    * of throwing). Conjecture-left-nested THROWS (reject), leaf-left-nested returns false
    * (accept) — the direct family's asymmetry, one level down. Spike-verified on the oracle:
    * the colls/tuples are single-element + same-length so the comparer reaches the element
    * compare (no length short-circuit). Expected board effect: green on eni (its Coll/Tuple
    * recursion already throws) · red on develop (the descent gap). Option descends identically
    * (`equalDataValues(opt.get, …)`, case 7) but isn't cleanly constructible as a constant —
    * omitted, same class. */
  private def nestedConjectureEntries: Seq[Json] = {
    val dlogA  = ProveDlog(gen)
    val candAB = CAND(Seq(dlogA, ProveDlog(gen2)))
    val specs = Seq(
      ("coll-cand-vs-dlog#0",  collTree(candAB, dlogA),  true,  "{ Coll[SigmaProp](pkA && pkB) == Coll[SigmaProp](pkA) }"),
      ("coll-dlog-vs-cand#1",  collTree(dlogA, candAB),  false, "{ Coll[SigmaProp](pkA) == Coll[SigmaProp](pkA && pkB) }"),
      ("tuple-cand-vs-dlog#2", tupleTree(candAB, dlogA), true,  "{ (pkA && pkB, true) == (pkA, true) }"),
      ("tuple-dlog-vs-cand#3", tupleTree(dlogA, candAB), false, "{ (pkA, true) == (pkA && pkB, true) }"))
    specs.map { case (name, hex, isReject, script) =>
      if (isReject) SpecExtract.authoredRejectEntry(OpNested, script, hex, name, dummyInput, V2)
      else          SpecExtract.authoredEntry(OpNested, script, hex, name, dummyInput, V2)
    }
  }

  /** op -> v2 envelope: one `==` tree, one entry per SigmaProp shape. */
  def extract(): Map[String, Json] = {
    val (script, treeHex) = eqSelfTree("SigmaProp", SSigmaProp)
    val inputs: Seq[(String, Json)] = Seq(
      "proveDlog"    -> sigmaPropJson(proveDlog),
      "proveDHTuple" -> sigmaPropJson(proveDHTuple),
      "CAND"         -> sigmaPropJson(cand))
    val entries = inputs.zipWithIndex.map { case ((name, in), i) =>
      SpecExtract.authoredEntry(Op, script, treeHex, s"$name#$i", in, V2)
    }
    Map(
      Op           -> SpecExtract.authoredEnvelope(Op, entries, Source),
      OpUnequal    -> SpecExtract.authoredEnvelope(OpUnequal, unequalEntries, Source),
      OpConjecture -> SpecExtract.authoredEnvelope(OpConjecture, conjectureEntries, Source),
      OpNested     -> SpecExtract.authoredEnvelope(OpNested, nestedConjectureEntries, Source))
  }

  /** String-equality reachability probe (the prompt's secondary ask). SString has a
    * DataValueComparer arm and serializes via CoreDataSerializer, but no op/method produces a
    * runtime String and the typer only folds `"+"` on two String constants — so whether a
    * crafted (non-source) tree can carry String to EQ at *eval* is settled empirically here,
    * not by source-reading. Returns the raw evalApplied outcome for
    * `getVar[String](1).get == getVar[String](1).get` on a String input: Right((value,cost))
    * ⇒ reachable (a crafted-tree consensus concern → author it); Left(err) ⇒ eval rejects
    * SString ⇒ unreachable (document the verdict, no vector). */
  def stringEqProbe(): (String, Either[String, (Json, Long)]) = {
    val (script, treeHex) = eqSelfTree("String", SString)
    val input = Json.obj("kind" -> Json.fromString("String"), "value" -> Json.fromString("ab"))
    val (_, outcome) = EvalCore.evalApplied(treeHex, input, V2)
    (script, outcome)
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v5/authored/ once inspected). Delegates to the shared writer. */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredSigmaPropEq", vectors, outDir)
}
