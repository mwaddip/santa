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

import org.scalatest.{Args, Reporter, Tracker}
import org.scalatest.events.{Event, TestFailed}

import sigma.{Context, VersionContext}
import sigma.ast.ErgoTree
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.eval.CostDetails

import sigma.LanguageSpecificationV6

object V6Extractor {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). */
  val V3: Byte = VersionContext.V6SoftForkVersion

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

    val captures: mutable.ArrayBuffer[SpecExtract.Capture] = mutable.ArrayBuffer.empty
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
          captures += SpecExtract.Capture(
            op = currentOp, script = f.script, treeBytesHex = treeHex, input = input,
            expectedValue = null, verificationCost = None, costDetailsCost = None, expectsFailure = true)
        } else {
          captures += SpecExtract.Capture(
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

  /** Drive the spec, capture every Stage-1 success case, encode to v2 vectors.
    * Pure (no file IO) so tests can assert on the result; `SpecExtract.writeVectors` persists. */
  def extract(): ExtractResult = {
    // Reproducible blessing: pin ScalaCheck's global RNG seed so re-extraction is byte-stable
    // (mirrors V5Extractor — a blessed corpus must be reproducible, not RNG-dependent).
    scala.util.Random.setSeed(0L)
    val tap = new Tap
    val propertyFailures = tap.runAllProperties()
    SpecExtract.encode(
      captures = tap.captures.toSeq,
      skippedUnsupported = tap.skippedUnsupported,
      skippedError = tap.skippedError,
      skippedContext = tap.skippedContext,
      skippedContextReasons = tap.skippedContextReasons.toSeq,
      propertyFailures = propertyFailures,
      activated = V3,
      source = "sigma-state:LanguageSpecificationV6")
  }
}
