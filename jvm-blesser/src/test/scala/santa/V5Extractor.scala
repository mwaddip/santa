package santa

import scala.collection.mutable

import scorex.util.encode.Base16

import org.scalatest.{Args, Reporter, Tracker}
import org.scalatest.events.{Event, TestFailed}

import sigma.{Context, VersionContext}
import sigma.ast.ErgoTree
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

import special.sigma.LanguageSpecificationV5

/** V5 corpus extractor: drives LanguageSpecificationV5 at (2,2) and delegates to the
  * shared SpecExtract.encode. Sibling of V6Extractor. */
object V5Extractor {
  /** v5 pin: activated=2, ergoTree=2 (JIT activation). */
  val v5Pin: Byte = VersionContext.JitActivationVersion

  private final class RecordingReporter extends Reporter {
    val failures: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    override def apply(event: Event): Unit = event match {
      case tf: TestFailed => failures += s"${tf.testName}: ${tf.throwable.map(EvalCore.errClass).getOrElse(tf.message)}"
      case _ => ()
    }
  }

  private final class Tap extends LanguageSpecificationV5 {
    override protected val activatedVersions: Seq[Byte] = Array(v5Pin)
    override val ergoTreeVersions: Seq[Byte] = Array(v5Pin)

    val captures: mutable.ArrayBuffer[SpecExtract.Capture] = mutable.ArrayBuffer.empty
    var skippedUnsupported = 0
    var skippedError = 0
    var skippedContext = 0
    val skippedContextReasons: mutable.LinkedHashSet[String] = mutable.LinkedHashSet.empty[String]
    var currentOp: String = "?"

    override def verifyCases[A: Ordering : org.scalacheck.Arbitrary : scala.reflect.ClassTag, B](
        cases: Seq[(A, Expected[B])], f: Feature[A, B],
        printTestCases: Boolean, failOnTestVectors: Boolean,
        preGeneratedSamples: Option[Seq[A]]): Unit = {
      val vc = VersionContext.current
      if (!f.isSupportedIn(vc)) { skippedUnsupported += cases.size; return }
      val compiledTree = f.newF.compiledTree
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, ergoTreeVersionInTests))
      val treeHex = Base16.encode(sigma.santa.LenientErgoTree.serialize(header, compiledTree))
      cases.foreach { case (input, expected) =>
        val (expRes, expDetails) = expected.newResults(ergoTreeVersionInTests)
        if (input.isInstanceOf[Context]) skippedContext += 1
        else if (expRes.value.isFailure)
          captures += SpecExtract.Capture(currentOp, f.script, treeHex, input, null, None, None, expectsFailure = true)
        else captures += SpecExtract.Capture(currentOp, f.script, treeHex, input,
          expRes.value.get, expRes.verificationCost, expDetails.map(_.cost.value))
      }
    }

    override def verifyCasesMany[A: Ordering : org.scalacheck.Arbitrary : scala.reflect.ClassTag, B](
        cases: Seq[(A, Expected[B])], features: Seq[Feature[A, B]],
        printTestCases: Boolean, failOnTestVectors: Boolean,
        preGeneratedSamples: Option[Seq[A]]): Unit =
      features.foreach(f => verifyCases(cases, f, printTestCases, failOnTestVectors, preGeneratedSamples))

    def runAllProperties(only: String => Boolean): Seq[String] = {
      val reporter = new RecordingReporter
      val tracker  = new Tracker
      testNames.toSeq.sorted.filterNot(_.endsWith("_MCLowering")).filter(only).foreach { name =>
        currentOp = name
        try runTest(name, Args(reporter, tracker = tracker))
        catch { case t: Throwable => reporter.failures += s"$name (threw): ${EvalCore.errClass(t)}" }
      }
      reporter.failures.toSeq
    }
  }

  def extract(only: String => Boolean = _ => true): ExtractResult = {
    // Reproducible blessing: LanguageSpecificationV5 samples some inputs (e.g. Coll.append)
    // via ScalaCheck, whose Seed.random() draws from the global scala.util.Random. Pin it so
    // re-extraction is byte-stable — a blessed corpus must be reproducible, not RNG-dependent.
    scala.util.Random.setSeed(0L)
    val tap = new Tap
    val propertyFailures = tap.runAllProperties(only)
    SpecExtract.encode(
      captures = tap.captures.toSeq,
      skippedUnsupported = tap.skippedUnsupported,
      skippedError = tap.skippedError,
      skippedContext = tap.skippedContext,
      skippedContextReasons = tap.skippedContextReasons.toSeq,
      propertyFailures = propertyFailures,
      activated = v5Pin,
      source = "sigma-state:LanguageSpecificationV5")
  }
}
