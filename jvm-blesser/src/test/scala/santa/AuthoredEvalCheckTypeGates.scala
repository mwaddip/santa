package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored eval vectors for sigma-rust's `santa-request-checktype-vectors` (2026-11-07):
// `Value.checkType` at every site that has one, and the four collection HOFs that have no
// element-type gate at all. Three op families, blessed on the 6.0.7 oracle.
//
// WHAT THE JVM DOES. Every evaluated sub-expression's value is checked against its node's
// STATIC type: `Value.checkType` (`values.scala:251-259`) -> `SType.isValueOfType`
// (`SType.scala:187-213`). The check is SHALLOW — only the root constructor counts, so any
// `Coll` satisfies a `Coll[Int]` — and a type it does not list (`SAny`, a type variable,
// `SString`) throws "Unknown type". sigma-rust checks only tuple and function arity, so it
// evaluates trees the JVM refuses. Conversely sigma-rust's `Exists`/`ForAll`/`Map`/`Filter`
// refuse an input whose element type differs from the node's, where the JVM has no such check
// (`transformers.scala:33-46`, `:117-128`, `:155-165`, `:182-192`) and lets the LAMBDA decide,
// once per element (`values.scala:1074`).
//
// HOW THE TREES CARRY THE LIE. An `If` is typed by its TRUE branch (`trees.scala:1351`), and no
// builder checks that the branches agree — `mkIf` just constructs the node
// (`SigmaBuilder.scala:380-383`), in the deserializer's builder too. `If.eval` checks the branch
// it TAKES against that branch's OWN type (`:1360`, `:1364`), so it passes; the next check up the
// tree is the one that throws. Three building blocks, each declared one type and evaluating to
// another:
//
//   X  = if (false) 1 else 2L               declared SInt,       evaluates to the Long 2
//   XL = if (false) 1L else 2               declared SLong,      evaluates to the Int 2
//   CX = if (false) Coll[Int](1) else Coll[Long](2L)   declared Coll[Int], holds a Long
//   CE = if (false) Coll[Int](1) else Coll[Long]()     the same, EMPTY
//
// Because only the runtime value lies — every DECLARED type in these trees agrees — an
// implementation that type-checks declared types at parse cannot reject them. That is the point:
// these vectors exercise the RUNTIME check and nothing else. They are ill-typed, so the compiler
// cannot produce them; each is built from AST nodes here and serialized with constants INLINE
// (no segregation), so the `ConstantPlaceholder` seam already covered by
// `Tuple.checkType_unsupported` is not what fires.
//
// Every row was first measured by `spike/CheckTypeGatesSpike` and came out exactly as the
// request's source reading predicted: 30 of 30, no surprises.
//
//   Value.checkType_sites         13 — one entry per check site, 12 rejects + a well-typed
//                                      control proving the If shape itself evaluates.
//   Value.checkType_lazy_default   6 — the two sites whose default is LAZY from ErgoTree v3 and
//                                      EAGER below it (`ByIndex` `:268`/`:273`,
//                                      `OptionGetOrElse` `:634`/`:640`). The lazy row that never
//                                      reaches its default is the control; the same tree at
//                                      ErgoTree v2 rejects. This family is why the batch sits on
//                                      the v6 surface.
//   Coll.hof_element_type_mismatch 12 — Exists/ForAll/Map/Filter over CX with a lambda declaring
//                                      the RUNTIME element type (accepts), the same with the
//                                      NODE's element type (rejects at the lambda's argument),
//                                      and over CE where no element exists so no check runs
//                                      (accepts — this is what isolates Map's gate, whose own
//                                      result type comes from the mapper's range).
//
// NOTE on grading: the eval tier's reject arm is COARSE (value/cost null, "errored"), and
// `reason` is diagnostic only, so a reject entry cannot say WHICH check fired. It does separate
// an implementation that EVALUATES these trees from one that refuses them, which is what the
// request asks for. The 20 accept entries carry value and cost and are the sharp half.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import org.ergoplatform.ErgoBox
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.ast._

object AuthoredEvalCheckTypeGates {

  val V3: Byte = VersionContext.V6SoftForkVersion // 3 — the activated version of the whole batch
  val V2: Int  = 2                                // the ErgoTree version below the lazy-default switch

  val OpSites  = "Value.checkType_sites"
  val OpLazy   = "Value.checkType_lazy_default"
  val OpGates  = "Coll.hof_element_type_mismatch"
  val Source   = "santa:authored-eval-checktype-gates"

  /** Closed trees: the dummy input bound to var 1 is ignored by every entry. */
  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]

  // ── the lies ────────────────────────────────────────────────────────────────

  /** `if (false) 1 else 2L` — declared SInt, evaluates to the Long 2. */
  private def X: Value[SInt.type] =
    If(FalseLeaf, IntConstant(1), LongConstant(2L).asInstanceOf[Value[SInt.type]])

  /** `if (false) 1L else 2` — declared SLong, evaluates to the Int 2 (the mirror of X, for a
    * site whose checked type is SLong). */
  private def XL: Value[SLong.type] =
    If(FalseLeaf, LongConstant(1L), IntConstant(2).asInstanceOf[Value[SLong.type]])

  /** `if (false) Coll[Int](1) else Coll[Long](2L)` — declared Coll[Int], holds a Long. */
  private def CX: Value[SCollection[SInt.type]] =
    If(FalseLeaf,
      ConcreteCollection(IndexedSeq(IntConstant(1)), SInt),
      ConcreteCollection(IndexedSeq(LongConstant(2L)), SLong)
        .asInstanceOf[Value[SCollection[SInt.type]]])

  /** The same, empty: no element, so no per-element check runs. */
  private def CE: Value[SCollection[SInt.type]] =
    If(FalseLeaf,
      ConcreteCollection(IndexedSeq(IntConstant(1)), SInt),
      ConcreteCollection(IndexedSeq.empty[Value[SLong.type]], SLong)
        .asInstanceOf[Value[SCollection[SInt.type]]])

  /** The honest twin of X, for the control. */
  private def Honest: Value[SInt.type] = If(FalseLeaf, IntConstant(1), IntConstant(2))

  private def emptyInts: Value[SCollection[SInt.type]] =
    ConcreteCollection(IndexedSeq.empty[Value[SInt.type]], SInt)

  private def coll5: Value[SCollection[SInt.type]] =
    ConcreteCollection(IndexedSeq(IntConstant(5)), SInt)

  private def lam(argTpe: SType, body: Value[SType]): Value[SFunc] =
    FuncValue(IndexedSeq(1 -> argTpe), body)

  private val pair = STuple(SInt, SInt)

  // ── serialization ───────────────────────────────────────────────────────────

  /** Serialize `root` as a size-flagged ErgoTree of version `etv`, constants INLINE. The root is
    * not a SigmaProp — the eval tier's trees never are — and both the `SigmaPropValue` typing and
    * `LenientErgoTree.deserialize`'s `checkType = false` make that a non-issue. */
  private def treeHex(root: Value[SType], etv: Int): String =
    VersionContext.withVersions(V3, etv.toByte) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, etv.toByte))
      val tree = new ErgoTree(header, IndexedSeq.empty,
        Right(root.asInstanceOf[sigma.ast.syntax.SigmaPropValue]))
      Base16.encode(sigma.serialization.ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(tree))
    }

  private def reject(op: String, script: String, why: String, root: Value[SType],
                     name: String, etv: Int = V3.toInt): Json =
    SpecExtract.authoredRejectEntryV(op, s"$script  // JVM: $why", treeHex(root, etv), name,
      input, V3, ergoTree = etv)

  private def accept(op: String, script: String, why: String, root: Value[SType],
                     name: String, etv: Int = V3.toInt): Json =
    SpecExtract.authoredEntryV(op, s"$script  // JVM: $why", treeHex(root, etv), name,
      input, V3, ergoTree = etv)

  private val Shallow = "Value.checkType -> SType.isValueOfType compares the value against the " +
    "node's STATIC type; the Long that `if (false) 1 else 2L` returns is not an Int"

  // ── A. one entry per Value.checkType site ───────────────────────────────────

  private def sites: Seq[Json] = Seq(
    accept(OpSites, "{ (if (false) 1 else 2) == 2 }",
      "the control — both branches are Int, so nothing lies and the If shape itself evaluates. " +
      "Green everywhere; a red here means the implementation refuses a mismatched-branch If at " +
      "PARSE, which would make the twelve rejects below green for the wrong reason",
      any(EQ(any(Honest), any(IntConstant(2)))), "control-honest-if-accept#0"),
    reject(OpSites, "{ val x = (if (false) 1 else 2L); true }",
      s"BlockValue checks each ValDef's value (values.scala:1027) — $Shallow. The diagnostic names " +
      "the ValDef node itself, which is what distinguishes this site from the result check below",
      any(BlockValue(IndexedSeq(ValDef(1, Nil, X)), TrueLeaf)), "valdef-errored#1"),
    reject(OpSites, "{ { val y = 1; (if (false) 1 else 2L) } == 2 }",
      s"BlockValue checks its RESULT (values.scala:1034) — $Shallow. The well-typed ValDef keeps " +
      "the :1027 check from firing first",
      any(EQ(any(BlockValue(IndexedSeq(ValDef(1, Nil, IntConstant(1))), X)), any(IntConstant(2)))),
      "block-result-errored#2"),
    reject(OpSites, "{ (if (false) 1 else 2L) == 2 }",
      s"EQ checks its LEFT operand (trees.scala:1206) — $Shallow",
      any(EQ(any(X), any(IntConstant(2)))), "eq-left-errored#3"),
    reject(OpSites, "{ 2 == (if (false) 1 else 2L) }",
      s"EQ checks its RIGHT operand (trees.scala:1208) — $Shallow; the honest left operand passes first",
      any(EQ(any(IntConstant(2)), any(X))), "eq-right-errored#4"),
    reject(OpSites, "{ (if (false) 1 else 2L) != 3 }",
      s"NEQ checks its LEFT operand (trees.scala:1226) — $Shallow",
      any(NEQ(any(X), any(IntConstant(3)))), "neq-left-errored#5"),
    reject(OpSites, "{ (if (true) (if (false) 1 else 2L) else 3) == 2 }",
      s"If checks the branch it TAKES (trees.scala:1360) — $Shallow. The outer If takes its true " +
      "branch, which is the inner If, whose own declared type is Int",
      any(EQ(any(If(TrueLeaf, X, IntConstant(3))), any(IntConstant(2)))), "if-branch-errored#6"),
    reject(OpSites, "{ ((if (false) 1 else 2L), 3)._1 == 2 }",
      s"Tuple checks its FIRST item (values.scala:830) — $Shallow",
      any(EQ(any(SelectField(Tuple(IndexedSeq(any(X), any(IntConstant(3)))), 1)), any(IntConstant(2)))),
      "tuple-item0-errored#7"),
    reject(OpSites, "{ (3, (if (false) 1 else 2L))._2 == 2 }",
      s"Tuple checks its SECOND item (values.scala:833) — $Shallow; the honest first item passes",
      any(EQ(any(SelectField(Tuple(IndexedSeq(any(IntConstant(3)), any(X))), 2)), any(IntConstant(2)))),
      "tuple-item1-errored#8"),
    reject(OpSites, "{ Coll(if (false) 1 else 2L).size == 1 }",
      s"ConcreteCollection checks each item (values.scala:894) — $Shallow. The check runs BEFORE " +
      "the item is stored into the typed array, so this is a type error and not an ArrayStoreException",
      any(EQ(any(SizeOf(ConcreteCollection(IndexedSeq(X), SInt))), any(IntConstant(1)))),
      "coll-item-errored#9"),
    reject(OpSites, "{ { (x: Int) => true }(if (false) 1 else 2L) }",
      "a lambda checks its ARGUMENT on every call (values.scala:1074) — the Long argument is not " +
      "an Int. This site uses the checkType(tpe, value) overload, so the diagnostic carries no " +
      "`expression:` line: that is what tells it apart from the body check",
      any(Apply(lam(SInt, any(TrueLeaf)), IndexedSeq(any(X)))), "lambda-arg-errored#10"),
    reject(OpSites, "{ { (x: Int) => if (false) 1 else 2L }(1) == 2 }",
      s"a lambda checks its BODY's value (values.scala:1080) — $Shallow",
      any(EQ(any(Apply(lam(SInt, any(X)), IndexedSeq(any(IntConstant(1))))), any(IntConstant(2)))),
      "lambda-body-errored#11"),
    reject(OpSites, "{ Coll[Int]().fold(if (false) 1 else 2L, { (p: (Int, Int)) => p._1 }) == 2 }",
      s"Fold checks its ZERO (transformers.scala:227) — $Shallow. The input is empty, so the fold " +
      "operation never runs and the zero check is the only one that can fire",
      any(EQ(any(Fold(emptyInts, X, lam(pair, any(SelectField(ValUse(1, pair), 1))))), any(IntConstant(2)))),
      "fold-zero-errored#12"))

  // ── A10/A11. the two defaults whose laziness is ErgoTree-version-gated ──────

  private def byIndexDefault(coll: Value[SCollection[SInt.type]], cmp: Int): Value[SType] =
    any(EQ(any(ByIndex(coll, IntConstant(0), Some(X))), any(IntConstant(cmp))))

  /** `SELF.R0[Long].getOrElse(XL) == SELF.value` — R0 is the box value, so the Option is always
    * Some and the default is only reached when the site is EAGER. */
  private def optionDefaultSome: Value[SType] =
    any(EQ(any(OptionGetOrElse(ExtractRegisterAs(Self, ErgoBox.R0, SOption(SLong)), XL)),
      any(ExtractAmount(Self))))

  private def lazyDefault: Seq[Json] = Seq(
    reject(OpLazy, "{ Coll[Int]().getOrElse(0, if (false) 1 else 2L) == 2 }",
      s"ByIndex at ErgoTree v3 evaluates its default only when the index is OUT of range, and then " +
      s"checks it (transformers.scala:268) — $Shallow",
      byIndexDefault(emptyInts, 2), "byindex-v3-out-of-range-errored#0"),
    accept(OpLazy, "{ Coll(5).getOrElse(0, if (false) 1 else 2L) == 5 }",
      "the LAZY control — at ErgoTree v3 an in-range index returns the element and never evaluates " +
      "the default, so the ill-typed default is never checked: true. The same tree one version " +
      "lower rejects, which is what proves the laziness",
      byIndexDefault(coll5, 5), "byindex-v3-in-range-accept#1"),
    reject(OpLazy, "{ Coll(5).getOrElse(0, if (false) 1 else 2L) == 5 }",
      s"the SAME tree at ErgoTree v2, where ByIndex's default is EAGER: it is evaluated and checked " +
      s"before the index is even consulted (transformers.scala:273) — $Shallow",
      byIndexDefault(coll5, 5), "byindex-v2-in-range-errored#2", etv = V2),
    reject(OpLazy, "{ SELF.R4[Int].getOrElse(if (false) 1 else 2L) == 2 }",
      s"OptionGetOrElse at ErgoTree v3 evaluates its default only when the Option is None, and then " +
      s"checks it (transformers.scala:634) — $Shallow. R4 is absent on the canonical eval context's " +
      "SELF box, so the Option is None",
      any(EQ(any(OptionGetOrElse(ExtractRegisterAs(Self, ErgoBox.R4, SOption(SInt)), X)), any(IntConstant(2)))),
      "optiongetorelse-v3-none-errored#3"),
    accept(OpLazy, "{ SELF.R0[Long].getOrElse(if (false) 1L else 2) == SELF.value }",
      "the LAZY control — R0 is the box value, so the Option is Some and at ErgoTree v3 the " +
      "ill-typed default is never evaluated: true. sigma-rust has NO check at this site at all, so " +
      "this row and its v2 twin are the pair that shows it",
      optionDefaultSome, "optiongetorelse-v3-some-accept#4"),
    reject(OpLazy, "{ SELF.R0[Long].getOrElse(if (false) 1L else 2) == SELF.value }",
      "the SAME tree at ErgoTree v2, where OptionGetOrElse's default is EAGER: it is evaluated and " +
      "checked even though the Option is Some (transformers.scala:640). Here the lie runs the other " +
      "way — the node is declared SLong and returns an Int",
      optionDefaultSome, "optiongetorelse-v2-some-errored#5", etv = V2),
    accept(OpLazy, "{ SELF.R4[Int].getOrElse(7) == 7 }",
      "the SEPARATING control for entry #3 — the same site and the same absent register, with an " +
      "HONEST default. The JVM returns 7: true, at cost 93. #3 is the only row of this batch that " +
      "blitzen-mwaddip already rejects, and since the fork has no check at this site the reject " +
      "cannot be the type lie; this row says whether the cause is the site itself (red here too) or " +
      "something that only the ill-typed default triggers (green here). Without it, a fix would " +
      "leave #3 green for an unknown reason",
      any(EQ(any(OptionGetOrElse(ExtractRegisterAs(Self, ErgoBox.R4, SOption(SInt)), IntConstant(7))),
        any(IntConstant(7)))), "optiongetorelse-v3-none-control-accept#6"),
    accept(OpLazy, "{ SELF.R4[Int].isDefined == false }",
      "the register read ALONE, with no OptionGetOrElse above it: true, at cost 78. This splits " +
      "what entry #6 leaves open — whether an implementation red on #6 fails at the absent-register " +
      "READ (red here too, in which case check the runner's SELF box before blaming the impl) or at " +
      "getOrElse over a NONE (green here, which localises it to that node, since #4 proves the same " +
      "box and the same node work when the Option is Some)",
      any(EQ(any(OptionIsDefined(ExtractRegisterAs(Self, ErgoBox.R4, SOption(SInt)))), any(FalseLeaf))),
      "selfr4-absent-isdefined-accept#7"))

  // ── B. the four element-type gates ──────────────────────────────────────────

  private def mapSize(coll: Value[SCollection[SInt.type]], argTpe: SType, cmp: Int): Value[SType] =
    argTpe match {
      case SLong => any(EQ(any(SizeOf(MapCollection[SInt.type, SLong.type](coll,
        lam(SLong, any(ValUse(1, SLong)))))), any(IntConstant(cmp))))
      case _ => any(EQ(any(SizeOf(MapCollection[SInt.type, SInt.type](coll,
        lam(SInt, any(ValUse(1, SInt)))))), any(IntConstant(cmp))))
    }

  private def filterSize(coll: Value[SCollection[SInt.type]], argTpe: SType, cmp: Int): Value[SType] =
    any(EQ(any(SizeOf(Filter(coll, lam(argTpe, any(TrueLeaf))))), any(IntConstant(cmp))))

  private val NoGate = "the JVM's Exists/ForAll/Map/Filter have no element-type check at all " +
    "(transformers.scala:33-46, :117-128, :155-165, :182-192); the node declares Coll[Int] and the " +
    "value is a Coll[Long], and only the lambda's own argument check (values.scala:1074) has a say"

  private def gates: Seq[Json] = Seq(
    accept(OpGates, "{ CX.exists({ (x: Long) => true }) }",
      s"$NoGate. The lambda declares the RUNTIME element type, so it passes: true",
      any(Exists(CX, lam(SLong, any(TrueLeaf)))), "exists-runtime-elem-accept#0"),
    accept(OpGates, "{ CX.forall({ (x: Long) => true }) }",
      s"$NoGate. The lambda declares the RUNTIME element type, so it passes: true",
      any(ForAll(CX, lam(SLong, any(TrueLeaf)))), "forall-runtime-elem-accept#1"),
    accept(OpGates, "{ CX.map({ (x: Long) => x }).size == 1 }",
      s"$NoGate. Map's own result element type comes from the MAPPER's range " +
      "(transformers.scala:38), not from the input, so a Long mapper over a Coll[Int]-typed input " +
      "is coherent: true",
      mapSize(CX, SLong, 1), "map-runtime-elem-accept#2"),
    accept(OpGates, "{ CX.filter({ (x: Long) => true }).size == 1 }",
      s"$NoGate. The lambda declares the RUNTIME element type, so it passes: true",
      filterSize(CX, SLong, 1), "filter-runtime-elem-accept#3"),
    reject(OpGates, "{ CX.exists({ (x: Int) => true }) }",
      s"$NoGate — and here the lambda declares the NODE's element type, so its argument check " +
      "rejects the Long element (values.scala:1074). The gate is the lambda's, not the transformer's",
      any(Exists(CX, lam(SInt, any(TrueLeaf)))), "exists-node-elem-errored#4"),
    reject(OpGates, "{ CX.forall({ (x: Int) => true }) }",
      s"$NoGate — the lambda declares the NODE's element type and its argument check rejects the " +
      "Long element (values.scala:1074)",
      any(ForAll(CX, lam(SInt, any(TrueLeaf)))), "forall-node-elem-errored#5"),
    reject(OpGates, "{ CX.map({ (x: Int) => x }).size == 1 }",
      s"$NoGate — the lambda declares the NODE's element type and its argument check rejects the " +
      "Long element (values.scala:1074)",
      mapSize(CX, SInt, 1), "map-node-elem-errored#6"),
    reject(OpGates, "{ CX.filter({ (x: Int) => true }).size == 1 }",
      s"$NoGate — the lambda declares the NODE's element type and its argument check rejects the " +
      "Long element (values.scala:1074)",
      filterSize(CX, SInt, 1), "filter-node-elem-errored#7"),
    accept(OpGates, "{ CE.exists({ (x: Int) => true }) == false }",
      "the input is an EMPTY Coll[Long] under a node declared Coll[Int]: there is no element, so " +
      "the lambda never runs and no check of any kind fires. exists over an empty collection is " +
      "false, so the comparison is true. An implementation that gates on the element type instead " +
      "rejects this with nothing to inspect",
      any(EQ(any(Exists(CE, lam(SInt, any(TrueLeaf)))), any(FalseLeaf))), "exists-empty-accept#8"),
    accept(OpGates, "{ CE.forall({ (x: Int) => true }) }",
      "the input is an EMPTY Coll[Long] under a node declared Coll[Int]: no element, no lambda " +
      "call, no check — and forall over an empty collection is true",
      any(ForAll(CE, lam(SInt, any(TrueLeaf)))), "forall-empty-accept#9"),
    accept(OpGates, "{ CE.map({ (x: Int) => x }).size == 0 }",
      "the input is an EMPTY Coll[Long] under a node declared Coll[Int]: no element, no lambda " +
      "call, no check, and an empty result. This is the row that ISOLATES Map's gate — with no " +
      "element the mapper cannot be the thing that objects",
      mapSize(CE, SInt, 0), "map-empty-accept#10"),
    accept(OpGates, "{ CE.filter({ (x: Int) => true }).size == 0 }",
      "the input is an EMPTY Coll[Long] under a node declared Coll[Int]: no element, no lambda " +
      "call, no check, and an empty result",
      filterSize(CE, SInt, 0), "filter-empty-accept#11"))

  /** These are first blessed on 6.0.7; SpecExtract's envelope carries the older 6.0.3 stamp. */
  private def envelopeOf(op: String, entries: Seq[Json]): Json =
    SpecExtract.authoredEnvelope(op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7")))

  def extract(): Map[String, Json] = Map(
    OpSites -> envelopeOf(OpSites, sites),
    OpLazy  -> envelopeOf(OpLazy, lazyDefault),
    OpGates -> envelopeOf(OpGates, gates))

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalCheckTypeGates", extract(), outDir)
}
