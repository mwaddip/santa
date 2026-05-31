package santa

// ─────────────────────────────────────────────────────────────────────────────
// V6 vector extractor (Phase 2, Stage 1).
//
// Emits canonical `santa-eval/v2` vectors from sigma-state's executable language
// spec `LanguageSpecificationV6`. For each
// deterministic (input, Expected) case the spec declares, it captures the
// feature's function ErgoTree, the input as SValue JSON, and V6's expected value
// + JIT cost, grouped by property → one vectors/eval/<op>.json per property.
//
// Driving the spec (NOT via sbt's scalatest discovery — that runs all 279
// inherited registrations across every (activated, ergoTree) version pair and
// crashes on v6-only ops at v0):
//   • subclass LanguageSpecificationV6 and pin the version sets to V3 only, so the
//     framework's own per-test version loop (CrossVersionProps.property →
//     forEachScriptAndErgoTreeVersion) iterates only the (3,3) pair;
//   • override verifyCases + verifyCasesMany to CAPTURE each case (no super → no
//     prove/verify; skip the `preGeneratedSamples` random tail → determinism);
//   • run each registered property BY NAME through scalatest's engine
//     (`runTest`), with a silent reporter that records (not throws) failures, so
//     an inline-assertion property (equivalence checks on context/box features we
//     skip anyway) can't abort the whole extraction.
//
// Cost (snag 1): V6's `Expected` overloads carry cost in THREE different,
// non-equivalent shapes (verified empirically against the spec):
//   • `verificationCost: Option[Int]` — the full Interpreter.verify() cost
//     (includes proof verification + version-dependent deserialization). For
//     substConstants this is 2065 while the pure eval cost is 351 — a DIFFERENT
//     quantity, so NOT the cost an eval-tier vector wants.
//   • a `CostDetails` whose `.cost` is the summed JIT trace — usually equals the
//     eval cost, but is a HAND-AUTHORED literal the framework only enforces via its
//     `testCases` path (not `verifyCases`), so it can be slightly stale (e.g.
//     Coll.getOrElse: literal 105 vs real eval 101), or a `ZeroCost` placeholder.
//   • or NEITHER (the bare `new Expected(ExpectedResult(value, None))`, used by the
//     bulk of V6 success cases — the spec pins no cost for them).
// The v2 schema defines `expected.cost` as the RAW JIT EVAL COST of applying the
// function to the input — exactly what EvalCore.evalApplied returns (the SAME
// `CErgoTreeEvaluator` the spec's checkEquality uses), and the only number the
// Task-4 cross-check can reproduce. So we EMIT the eval cost universally. We hard-
// guard the VALUE (eval value must equal the spec's expected value — a silent wrong
// value is the cardinal sin) and SURFACE any case where the eval cost diverges from
// a spec cost field as a diagnostic (the 4 such cases are all version-/lazy-
// dependent edge ops; see ExtractResult.costDiagnostics).
// ─────────────────────────────────────────────────────────────────────────────

import scala.collection.mutable

import scorex.util.encode.Base16

import io.circe.Json

import org.scalatest.{Args, Reporter, Tracker}
import org.scalatest.events.{Event, TestFailed}

import sigma.{Context, VersionContext}
import sigma.ast.ErgoTree
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.eval.CostDetails

import sigma.LanguageSpecificationV6

/** The captured-and-encoded extraction result for the whole spec. */
final case class ExtractResult(
    vectors: Map[String, Json],          // op (property name) -> v2 envelope Json
    captured: Int,                       // entries emitted
    skippedUnsupported: Int,             // feature not supported at V3
    skippedError: Int,                   // expected value is a Failure
    skippedContext: Int,                 // input is a Context (Stage 2b; Box/Header now captured)
    skippedUnsupportedKind: Int,         // input/value of a kind valueToJson can't encode
    unsupportedKindReasons: Seq[String], // distinct "op: kind not encodable" detail lines
    skippedContextReasons: Seq[String],  // distinct "op: input=<class> | <script>" for Context skips
    costDiagnostics: Seq[String],        // DIAGNOSTIC: cases where eval cost != a spec cost field
    propertyFailures: Seq[String])       // properties whose body threw at V3 (logged, not fatal)

object V6Extractor {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). */
  val V3: Byte = VersionContext.V6SoftForkVersion

  /** Filesystem-safe slug for an op name (property name) → vector filename stem. */
  def slug(op: String): String =
    op.trim
      .replaceAll("""\[""", "_").replaceAll("""\]""", "")
      .replaceAll("""[^A-Za-z0-9._]+""", "_")
      .replaceAll("_+", "_")
      .stripPrefix("_").stripSuffix("_")

  /** One captured case, pre-encoding (raw runtime types from the spec). */
  private final case class Capture(
      op: String,
      script: String,
      treeBytesHex: String,
      input: Any,
      expectedValue: Any,
      verificationCost: Option[Int], // Expected.verificationCost for the pinned version (verify-path cost)
      costDetailsCost: Option[Int])  // CostDetails.cost = summed JIT trace (the eval cost the spec pins)

  /** Silent reporter that records test failures instead of printing/throwing. */
  private final class RecordingReporter extends Reporter {
    val failures: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    override def apply(event: Event): Unit = event match {
      case tf: TestFailed =>
        failures += s"${tf.testName}: ${tf.throwable.map(EvalCore.errClass).getOrElse(tf.message)}"
      case _ => ()
    }
  }

  // ── The capturing tap ────────────────────────────────────────────────────────
  private final class Tap extends LanguageSpecificationV6 {
    // Pin the framework's version loops to V3 ONLY. CrossVersionProps.property runs
    // forEachScriptAndErgoTreeVersion(activatedVersions, ergoTreeVersions); with both
    // == Seq(3) only the (3,3) pair executes (and only ErgoTree v <= activated, so
    // exactly (3,3)). This removes the v6-op-at-v0 crash without per-property edits.
    override protected val activatedVersions: Seq[Byte] = Array(V3)
    override val ergoTreeVersions: Seq[Byte] = Array(V3)

    val captures: mutable.ArrayBuffer[Capture] = mutable.ArrayBuffer.empty
    var skippedUnsupported = 0
    var skippedError = 0
    var skippedContext = 0
    val skippedContextReasons: mutable.LinkedHashSet[String] = mutable.LinkedHashSet.empty[String]
    private val seenContextTreeClasses: mutable.Set[String] = mutable.Set.empty[String]

    /** The property currently running (set by the driver before runTest). */
    var currentOp: String = "?"

    private def isContextOnly(input: Any): Boolean =
      input.isInstanceOf[Context]

    // Signature MUST match SigmaDslTesting.verifyCases exactly (context-bound
    // Ordering : Arbitrary : ClassTag); defaults must NOT be re-declared here.
    override def verifyCases[A: Ordering : org.scalacheck.Arbitrary : scala.reflect.ClassTag, B](
        cases: Seq[(A, Expected[B])],
        f: Feature[A, B],
        printTestCases: Boolean,
        failOnTestVectors: Boolean,
        preGeneratedSamples: Option[Seq[A]]): Unit = {

      val vc = VersionContext.current

      // Skip whole feature if unsupported at the pinned version (snag 3).
      if (!f.isSupportedIn(vc)) {
        skippedUnsupported += cases.size
        return
      }

      // newF builds (and memoizes) the CompiledFunc; compiledTree is the CLOSED
      // function wrapper { val func = <script>; func(getVar[A](1).get) }. Its root
      // type is B (usually NOT SigmaProp) → SANTA's lenient SERIALIZE path.
      val compiledTree = f.newF.compiledTree
      val header: HeaderType =
        ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, ergoTreeVersionInTests))
      val treeHex = Base16.encode(sigma.santa.LenientErgoTree.serialize(header, compiledTree))

      cases.foreach { case (input, expected) =>
        // Per-version expected value + cost (the same accessor the framework uses,
        // LanguageSpecificationBase.testCases:97).
        val (expRes, expDetails): (ExpectedResult[B], Option[CostDetails]) =
          expected.newResults(ergoTreeVersionInTests)

        if (isContextOnly(input)) {
          skippedContext += 1                       // Stage-2 (snag 5)
          val cls = input.getClass.getSimpleName
          skippedContextReasons += s"$currentOp: input=$cls | ${f.script}"
          // One-time AST dump per distinct input runtime class, to reveal whether the
          // compiled tree binds the arg to var 1 (ValUse/getVar) or reads context roots
          // (Height/Inputs/Self/Outputs/dataInputs). Diagnostic only.
          if (seenContextTreeClasses.add(cls))
            System.err.println(s"[ctx-tree] $currentOp ($cls) script=${f.script}\n          tree=$compiledTree")
        } else if (expRes.value.isFailure) {
          skippedError += 1                         // error-expected (snag 3)
        } else {
          captures += Capture(
            op = currentOp,
            script = f.script,
            treeBytesHex = treeHex,
            input = input,
            expectedValue = expRes.value.get,
            verificationCost = expRes.verificationCost,
            costDetailsCost = expDetails.map(_.cost.value))
        }
      }
      // (intentionally skip `test(preGeneratedSamples, f, printTestCases)`)
    }

    // verifyCasesMany fans one case-set over many features (snag 4). Not used in
    // V6, but tapped for completeness: delegate each feature to verifyCases.
    override def verifyCasesMany[A: Ordering : org.scalacheck.Arbitrary : scala.reflect.ClassTag, B](
        cases: Seq[(A, Expected[B])],
        features: Seq[Feature[A, B]],
        printTestCases: Boolean,
        failOnTestVectors: Boolean,
        preGeneratedSamples: Option[Seq[A]]): Unit =
      features.foreach(f => verifyCases(cases, f, printTestCases, failOnTestVectors, preGeneratedSamples))

    /** Run every registered property by name (scalatest `runTest` is protected, so
      * the loop lives here, inside the suite). Each runs under the V3-pinned version
      * loop with the capturing tap; failures go to the recording reporter. Returns
      * the reporter's recorded failures. */
    def runAllProperties(): Seq[String] = {
      val reporter = new RecordingReporter
      val tracker = new Tracker
      // CompilerCrossVersionProps registers each property TWICE: once normally and
      // once as "<name>_MCLowering" (method-call lowering disabled). The two produce
      // the same Stage-1 cases; extract only the canonical (non-_MCLowering) variant
      // to keep the corpus deterministic and free of duplicates.
      testNames.toSeq.sorted.filterNot(_.endsWith("_MCLowering")).foreach { name =>
        currentOp = name
        try runTest(name, Args(reporter, tracker = tracker))
        catch {
          // runTest reports failures to the reporter; a throw here is an
          // engine-level surprise — record and continue (don't abort extraction).
          case t: Throwable => reporter.failures += s"$name (threw): ${EvalCore.errClass(t)}"
        }
      }
      reporter.failures.toSeq
    }
  }

  // ── Encoding a capture → a v2 entry ───────────────────────────────────────────
  /** True if the encoded SValue JSON contains an Opaque kind ANYWHERE — top-level
    * OR nested inside Coll `items` / Tuple `items`. A top-level-only check misses
    * e.g. a Tuple(GroupElement, UnsignedBigInt) whose second item is Opaque; the
    * input decoder would then fail mid-eval. Recurse so such cases are skipped. */
  private def hasOpaque(j: Json): Boolean = {
    val c = j.hcursor
    c.downField("kind").as[String].toOption.contains("Opaque") || {
      c.downField("items").as[List[Json]].toOption.exists(_.exists(hasOpaque))
    }
  }

  /** Encode one capture into a v2 entry, deriving/validating its cost via the eval.
    *
    * `entryIndex` is the 0-based position within this property's emitted entries;
    * it is appended to `name` to guarantee uniqueness within a vector — multi-
    * feature properties share an `input` (and therefore `input.toString`), so a
    * bare `c.input.toString` repeats across entries. A consumer keying by `name`
    * must never collide within a single op's entry list.
    *
    * Returns `Left(reason)` ONLY for an unsupported-kind input/value — valueToJson
    * emits {kind:"Opaque"} for a kind it doesn't model; we must never bake a guessed
    * encoding into a vector, so such a case is skipped-and-reported (not emitted).
    * Genuine bad-vector conditions (eval failure on an encodable input, or a
    * value/cost MISMATCH between the spec and the eval) are fail-loud `sys.error`s —
    * the whole point of the suite is that a silent wrong value can never ship. */
  private def toEntry(c: Capture, entryIndex: Int): Either[String, (Json, Option[String])] = {
    val inputJson = EvalCore.valueToJson(c.input)
    val valueJson = EvalCore.valueToJson(c.expectedValue)

    // Escalation: refuse to emit an Opaque (unsupported Stage-1 kind), incl. one
    // nested in a Coll/Tuple. Skip + report (never bake a guessed encoding).
    if (hasOpaque(inputJson))
      return Left(s"${c.op}: input kind not encodable — ${inputJson.noSpaces}")
    if (hasOpaque(valueJson))
      return Left(s"${c.op}: expected-value kind not encodable — ${valueJson.noSpaces}")

    // Re-bless via SANTA's apply-eval (the SAME CErgoTreeEvaluator the spec uses).
    // Pinned to activated=3 (the tree itself carries ergoTree version 3).
    val (_, outcome) = EvalCore.evalApplied(c.treeBytesHex, inputJson, activated = V3)
    val (evalValue, evalCost) = outcome match {
      case Right(vc) => vc
      case Left(err) =>
        sys.error(s"V6Extractor: apply-eval failed for op '${c.op}' (${c.script}) " +
          s"on input ${inputJson.noSpaces}: $err")
    }

    // The eval value MUST match the spec's expected value (canonical-by-construction).
    if (evalValue.noSpaces != valueJson.noSpaces)
      sys.error(s"V6Extractor: VALUE MISMATCH for op '${c.op}' (${c.script}): " +
        s"spec=${valueJson.noSpaces} vs eval=${evalValue.noSpaces}")

    // DIAGNOSTIC: emit the eval cost (the v2-defined "raw JIT eval cost"); record
    // any case where a spec cost field disagrees with it, to characterize which
    // field (CostDetails.cost vs verificationCost) tracks the eval cost.
    val diag: Option[String] = {
      val dCD = c.costDetailsCost.filter(_.toLong != evalCost)
        .map(v => s"CostDetails.cost=$v")
      val dVC = c.verificationCost.filter(_.toLong != evalCost)
        .map(v => s"verificationCost=$v")
      val mism = (dCD ++ dVC).toSeq
      if (mism.isEmpty) None
      else Some(s"${c.op} [${c.script}] input=${inputJson.noSpaces}: evalCost=$evalCost vs ${mism.mkString(", ")}")
    }

    Right((Json.obj(
      "name"           -> Json.fromString(s"${c.input.toString}#$entryIndex"),
      "script"         -> Json.fromString(c.script),
      "tree_bytes_hex" -> Json.fromString(c.treeBytesHex),
      "input"          -> inputJson,
      "version"        -> Json.obj("activated" -> Json.fromInt(V3.toInt),
                                   "ergoTree"  -> Json.fromInt(V3.toInt)),
      "expected"       -> Json.obj("value" -> valueJson,
                                   "cost"  -> Json.fromLong(evalCost),
                                   "error" -> Json.Null)), diag))
  }

  /** Wrap a property's entries in the v2 envelope. */
  private def envelope(op: String, entries: Seq[Json]): Json =
    Json.obj(
      "schema"     -> Json.fromString("santa-eval/v2"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "source"     -> Json.fromString("sigma-state:LanguageSpecificationV6"),
      "entries"    -> Json.arr(entries: _*))

  /** Drive the spec, capture every Stage-1 success case, encode to v2 vectors.
    * Pure (no file IO) so tests can assert on the result; `writeVectors` persists. */
  def extract(): ExtractResult = {
    val tap = new Tap
    // Run each registered property by name, pinned to V3 via the version-set
    // overrides. testFun_Run inside the framework wraps each in withVersions(3,3).
    val propertyFailures = tap.runAllProperties()

    // Encode each capture; partition emitted entries from unsupported-kind skips.
    val byOp: Map[String, Seq[Capture]] =
      tap.captures.groupBy(_.op).map { case (op, cs) => op -> cs.toSeq }

    val unsupportedKindReasons = mutable.LinkedHashSet.empty[String]
    val costDiagnostics = mutable.ArrayBuffer.empty[String]
    var skippedUnsupportedKind = 0
    val vectors = mutable.Map.empty[String, Json]

    byOp.foreach { case (op, cs) =>
      // Quarantine: if the property's body threw mid-capture, its Capture list
      // may be truncated (recording stopped partway through the case-set).  A
      // truncated op must NEVER reach the output dir, so skip it entirely here —
      // the matching assert in V6ExtractorTest will make the failure loud.
      if (propertyFailures.exists(_.startsWith(op))) {
        System.err.println(s"[V6Extractor] QUARANTINED op '$op' — its property threw; skipping all ${cs.size} captured entries")
      } else {
        var entryIndex = 0
        val entries = cs.flatMap { c =>
          toEntry(c, entryIndex) match {
            case Right((entry, diag)) =>
              diag.foreach(costDiagnostics += _)
              entryIndex += 1
              Some(entry)
            case Left(reason) =>
              skippedUnsupportedKind += 1
              unsupportedKindReasons += reason
              None
          }
        }
        // Only emit an envelope if the property yielded at least one Stage-1 entry.
        if (entries.nonEmpty) vectors += (op -> envelope(op, entries))
      }
    }

    ExtractResult(
      vectors = vectors.toMap,
      captured = vectors.values.map(_.hcursor.downField("entries").values.fold(0)(_.size)).sum,
      skippedUnsupported = tap.skippedUnsupported,
      skippedError = tap.skippedError,
      skippedContext = tap.skippedContext,
      skippedUnsupportedKind = skippedUnsupportedKind,
      unsupportedKindReasons = unsupportedKindReasons.toSeq,
      skippedContextReasons = tap.skippedContextReasons.toSeq,
      costDiagnostics = costDiagnostics.toSeq,
      propertyFailures = propertyFailures)
  }

  /** Persist the extracted vectors to a staging dir (a build artifact — NOT the
    * committed vectors/eval/, which lands in Task 5). One file per op. */
  def writeVectors(result: ExtractResult, outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    result.vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${slug(op)}.json")
      java.nio.file.Files.write(path,
        json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
