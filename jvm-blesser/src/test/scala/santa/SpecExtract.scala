package santa

// ─────────────────────────────────────────────────────────────────────────────
// Shared pure extraction core for SpecExtract (version-agnostic).
//
// Factored out of V6Extractor so V5Extractor can share it. Parameterized by
// the `activated` version byte and a `source` provenance string; the Tap
// (which must subclass the concrete LanguageSpecification*) lives in the
// version-specific extractor and passes its captured state to `encode`.
// ─────────────────────────────────────────────────────────────────────────────

import scala.collection.mutable

import io.circe.Json

import org.ergoplatform.ErgoBox

import sigma.{Box, BoxRType, Coll, Colls}
import sigma.data.CBox

/** The captured-and-encoded extraction result for the whole spec. */
final case class ExtractResult(
    vectors: Map[String, Json],          // op (property name) -> v2 envelope Json
    captured: Int,                       // entries emitted
    skippedUnsupported: Int,             // feature not supported at the pinned version
    skippedError: Int,                   // expected value is a Failure
    skippedContext: Int,                 // input is a Context (Stage 2b; Box/Header now captured)
    skippedUnsupportedKind: Int,         // input/value of a kind valueToJson can't encode
    rejectsCaptured: Int,                // Failure-expected cases blessed as `errored` reject vectors
    unsupportedKindReasons: Seq[String], // distinct "op: kind not encodable" detail lines
    skippedContextReasons: Seq[String],  // distinct "op: input=<class> | <script>" for Context skips
    costDiagnostics: Seq[String],        // DIAGNOSTIC: cases where eval cost != a spec cost field
    propertyFailures: Seq[String])       // properties whose body threw at the pinned version (logged, not fatal)

object SpecExtract {

  /** Filesystem-safe slug for an op name (property name) → vector filename stem.
    * Logic operators are transliterated to words first — the generic strip below
    * would otherwise collapse `&&` / `||` to nothing, colliding e.g.
    * "&& boolean equivalence" and "|| boolean equivalence" onto one filename. */
  def slug(op: String): String =
    op.trim
      .replace("&&", " and ").replace("||", " or ")
      .replaceAll("""\[""", "_").replaceAll("""\]""", "")
      .replaceAll("""[^A-Za-z0-9._]+""", "_")
      .replaceAll("_+", "_")
      .stripPrefix("_").stripSuffix("_")

  /** One captured case, pre-encoding (raw runtime types from the spec). */
  private[santa] final case class Capture(
      op: String,
      script: String,
      treeBytesHex: String,
      input: Any,
      expectedValue: Any,
      verificationCost: Option[Int], // Expected.verificationCost for the pinned version (verify-path cost)
      costDetailsCost: Option[Int],  // CostDetails.cost = summed JIT trace (the eval cost the spec pins)
      expectsFailure: Boolean = false) // spec expects this (tree, input) to FAIL → bless a coarse reject

  // ── Box-value re-bless (sigma-rust prompt santa-rebless-min-box-value) ────────
  // LanguageSpecification's test boxes carry sub-minimum values (1/20) the test ErgoBox permits but a
  // protocol-enforcing impl (sigma-rust) can't deserialize → Blitzen tags them `unrepresentable`.
  // Floor every box input at the protocol min so the corpus is consensus-valid. The spec hardcodes
  // each case's expected (it does NOT recompute from the box), so a bumped input desyncs from the
  // captured expected; toEntry therefore takes the JVM re-eval as the expected for bumped cases (and
  // drops the spec cross-check — evalApplied IS sigma-state 6.0.3, the oracle). Bumping changes the
  // result of value-reading scripts (e.g. `x.exists(b => b.value > 1)` → true), which is correct and
  // unavoidable: those vectors were un-runnable for a min-enforcing impl at sub-min values.
  private val MinBoxValue: Long = 1000000L

  private def bumpCBox(cb: CBox): CBox = {
    val e = cb.ebox
    if (e.value >= MinBoxValue) cb
    else CBox(new ErgoBox(MinBoxValue, e.ergoTree, e.additionalTokens, e.additionalRegisters,
                          e.transactionId, e.index, e.creationHeight))
  }

  /** Floor any box in a captured input (a Box or a Coll[Box]) at MinBoxValue. Returns the
    * (possibly-rebuilt) input and whether anything actually changed. Non-box inputs pass through. */
  private def bumpBoxes(v: Any): (Any, Boolean) = v match {
    case cb: CBox =>
      val b = bumpCBox(cb); (b, !(b eq cb))
    case coll: Coll[_] if sigma.Evaluation.rtypeToSType(coll.tItem) == sigma.ast.SBox =>
      val arr: Array[Box] = coll.asInstanceOf[Coll[Box]].toArray
      var changed = false
      val bumped: Array[Box] = arr.map {
        case cb: CBox => val nb = bumpCBox(cb); if (!(nb eq cb)) changed = true; (nb: Box)
        case o        => o
      }
      if (changed) (Colls.fromArray(bumped)(BoxRType), true) else (v, false)
    case other => (other, false)
  }

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
    * bare `input.toString` repeats across entries. A consumer keying by `name`
    * must never collide within a single op's entry list.
    *
    * Returns `Left(reason)` ONLY for an unsupported-kind input/value — valueToJson
    * emits {kind:"Opaque"} for a kind it doesn't model; we must never bake a guessed
    * encoding into a vector, so such a case is skipped-and-reported (not emitted).
    * Genuine bad-vector conditions (eval failure on an encodable input, or a
    * value/cost MISMATCH between the spec and the eval) are fail-loud `sys.error`s —
    * the whole point of the suite is that a silent wrong value can never ship. */
  private[santa] def toEntry(c: Capture, entryIndex: Int, activated: Byte): Either[String, (Json, Option[String])] = {
    val (input, rebless) = bumpBoxes(c.input)
    val inputJson = EvalCore.valueToJson(input)
    val valueJson = EvalCore.valueToJson(c.expectedValue)

    // Escalation: refuse to emit an Opaque (unsupported Stage-1 kind), incl. one
    // nested in a Coll/Tuple. Skip + report (never bake a guessed encoding).
    if (hasOpaque(inputJson))
      return Left(s"${c.op}: input kind not encodable — ${inputJson.noSpaces}")
    if (hasOpaque(valueJson))
      return Left(s"${c.op}: expected-value kind not encodable — ${valueJson.noSpaces}")

    // Robustness: an input we can encode but cannot re-DECODE (re-bind) can't be re-blessed —
    // skip-and-report, don't crash. This classifies a decode failure as a skip, leaving
    // evalApplied's Left below to mean a genuine EVAL failure (which stays loud) and a
    // value MISMATCH to stay loud (the cardinal "no silent wrong value can ever ship" rule).
    val decoded =
      try EvalCore.decodeInputConstant(inputJson)
      catch { case t: Throwable => return Left(s"${c.op}: input not decodable — ${EvalCore.errClass(t)}") }

    // Wire-encodability gate: EvalCore binds inputs in-memory (bypassing sigma-state's
    // DataSerializer version gate), so it can over-capture inputs that aren't valid wire
    // encodings at the target ErgoTree version (e.g. SHeader requires ergoTree >= 3, so a
    // Header input is NOT a valid v5 encoding). Such a vector would crash every conformer
    // that deserializes from the wire (ergots, sigma-rust). Drop it here, using sigma-state's
    // OWN gate, so the corpus is exactly what every implementation can deserialize. NOT a
    // value-mismatch skip — the cardinal loud-on-mismatch rule below is untouched.
    if (!EvalCore.isWireEncodable(decoded, activated))
      return Left(s"${c.op}: input type ${decoded.tpe} not wire-encodable at ErgoTree v$activated (version-gated constant)")

    // Re-bless via SANTA's apply-eval (the SAME CErgoTreeEvaluator the spec uses).
    val (_, outcome) = EvalCore.evalApplied(c.treeBytesHex, inputJson, activated = activated)
    val (evalValue, evalCost) = outcome match {
      case Right(vc) => vc
      case Left(err) =>
        sys.error(s"SpecExtract: apply-eval failed for op '${c.op}' (${c.script}) " +
          s"on input ${inputJson.noSpaces}: $err")
    }

    // Expected value: when a sub-min box input was floored (rebless), the spec's captured expected is
    // for the ORIGINAL (un-representable) box — trust the JVM re-eval (evalApplied IS sigma-state
    // 6.0.3, the oracle) and skip the spec cross-check. Otherwise the eval value MUST match the spec's
    // expected (canonical-by-construction; a silent wrong value can never ship).
    val expectedJson =
      if (rebless) evalValue
      else {
        if (evalValue.noSpaces != valueJson.noSpaces)
          sys.error(s"SpecExtract: VALUE MISMATCH for op '${c.op}' (${c.script}): " +
            s"spec=${valueJson.noSpaces} vs eval=${evalValue.noSpaces}")
        valueJson
      }

    // DIAGNOSTIC: emit the eval cost (the v2-defined "raw JIT eval cost"); record any case where a
    // spec cost field disagrees with it. Skipped for rebless cases (the spec cost is for the un-bumped
    // box, so it would always "disagree" — noise, not signal).
    val diag: Option[String] =
      if (rebless) None
      else {
        val dCD = c.costDetailsCost.filter(_.toLong != evalCost).map(v => s"CostDetails.cost=$v")
        val dVC = c.verificationCost.filter(_.toLong != evalCost).map(v => s"verificationCost=$v")
        val mism = (dCD ++ dVC).toSeq
        if (mism.isEmpty) None
        else Some(s"${c.op} [${c.script}] input=${inputJson.noSpaces}: evalCost=$evalCost vs ${mism.mkString(", ")}")
      }

    Right((Json.obj(
      "name"           -> Json.fromString(s"${input.toString}#$entryIndex"),
      "script"         -> Json.fromString(c.script),
      "tree_bytes_hex" -> Json.fromString(c.treeBytesHex),
      "input"          -> inputJson,
      "version"        -> Json.obj("activated" -> Json.fromInt(activated.toInt),
                                   "ergoTree"  -> Json.fromInt(activated.toInt)),
      "expected"       -> Json.obj("value" -> expectedJson,
                                   "cost"  -> Json.fromLong(evalCost),
                                   "error" -> Json.Null)), diag))
  }

  /** Encode a Failure-expected capture into a coarse reject entry. The spec says this
    * (tree, input) fails, so bless { value:null, cost:null, error:"errored" } — but ONLY
    * after the SAME input gates as toEntry (Opaque / decodable / wire-encodable), AND only
    * if SANTA's own eval also fails (rejected for the right reason, coarse). If the eval
    * produces a VALUE, that is the mirror of toEntry's VALUE MISMATCH: a "should-fail" that
    * actually succeeds must never ship — fail loud. Box inputs are floored at the protocol min
    * (santa-rebless-min-box-value) before re-eval; a register/type-mismatch reject still fails. */
  private[santa] def rejectToEntry(c: Capture, entryIndex: Int, activated: Byte): Either[String, (Json, Option[String])] = {
    val (input, _) = bumpBoxes(c.input)
    val inputJson = EvalCore.valueToJson(input)
    if (hasOpaque(inputJson))
      return Left(s"${c.op}: reject input kind not encodable — ${inputJson.noSpaces}")
    val decoded =
      try EvalCore.decodeInputConstant(inputJson)
      catch { case t: Throwable => return Left(s"${c.op}: reject input not decodable — ${EvalCore.errClass(t)}") }
    if (!EvalCore.isWireEncodable(decoded, activated))
      return Left(s"${c.op}: reject input type ${decoded.tpe} not wire-encodable at ErgoTree v$activated")

    val (_, outcome) = EvalCore.evalApplied(c.treeBytesHex, inputJson, activated = activated)
    outcome match {
      case Left(_) =>
        Right((Json.obj(
          "name"           -> Json.fromString(s"${input.toString}#$entryIndex"),
          "script"         -> Json.fromString(c.script),
          "tree_bytes_hex" -> Json.fromString(c.treeBytesHex),
          "input"          -> inputJson,
          "version"        -> Json.obj("activated" -> Json.fromInt(activated.toInt),
                                       "ergoTree"  -> Json.fromInt(activated.toInt)),
          "expected"       -> Json.obj("value" -> Json.Null,
                                       "cost"  -> Json.Null,
                                       "error" -> Json.fromString("errored"))), None))
      case Right((evalValue, _)) =>
        sys.error(s"SpecExtract: REJECT MISMATCH for op '${c.op}' (${c.script}) on input " +
          s"${inputJson.noSpaces}: spec expects Failure but eval produced ${evalValue.noSpaces}")
    }
  }

  /** Wrap a property's entries in the v2 envelope. */
  private def envelope(op: String, entries: Seq[Json], source: String): Json =
    Json.obj(
      "schema"     -> Json.fromString("santa-eval/v2"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "source"     -> Json.fromString(source),
      "entries"    -> Json.arr(entries: _*))

  // ── Authored path (for vectors the spec doesn't declare) ─────────────────────
  // The spec covers Global.serialize only for direct types; the delegated-serializer
  // types (GroupElement/SigmaProp/UnsignedBigInt/AvlTree/Box/Header + composites) are
  // absent, so we AUTHOR them: pick representative inputs, build the spec's own
  // `serialize` tree (via mkSerializeFeature), and bless value+cost from the JVM eval.
  // There is no spec-declared expected to cross-check, so (unlike toEntry) evalApplied's
  // value+cost ARE canonical — the rebless philosophy (evalApplied IS sigma-state 6.0.3,
  // the oracle). Honest provenance ⇒ vectors/eval/v6/authored/, source "santa:authored".

  /** Author one v2 entry from a hand-picked input. The input passes the SAME
    * wire-encodability gate as extracted vectors — an authored input that isn't
    * wire-encodable at the target version would be unusable by every conformer; since
    * we control authored inputs, that is a fail-loud authoring bug, not a silent skip. */
  def authoredEntry(op: String, script: String, treeBytesHex: String, name: String,
                    inputJson: Json, activated: Byte): Json = {
    val decoded =
      try EvalCore.decodeInputConstant(inputJson)
      catch { case t: Throwable =>
        sys.error(s"authoredEntry: input not decodable for '$op': ${EvalCore.errClass(t)} — ${inputJson.noSpaces}") }
    if (!EvalCore.isWireEncodable(decoded, activated))
      sys.error(s"authoredEntry: input ${decoded.tpe} not wire-encodable at ErgoTree v$activated for '$op' — ${inputJson.noSpaces}")

    val (_, outcome) = EvalCore.evalApplied(treeBytesHex, inputJson, activated = activated)
    val (valueJson, cost) = outcome match {
      case Right(vc) => vc
      case Left(err) =>
        sys.error(s"authoredEntry: apply-eval failed for '$op' ($script) on ${inputJson.noSpaces}: $err")
    }
    Json.obj(
      "name"           -> Json.fromString(name),
      "script"         -> Json.fromString(script),
      "tree_bytes_hex" -> Json.fromString(treeBytesHex),
      "input"          -> inputJson,
      "version"        -> Json.obj("activated" -> Json.fromInt(activated.toInt),
                                   "ergoTree"  -> Json.fromInt(activated.toInt)),
      "expected"       -> Json.obj("value" -> valueJson,
                                   "cost"  -> Json.fromLong(cost),
                                   "error" -> Json.Null))
  }

  /** Public v2 envelope for authored vectors (reuses the canonical envelope). */
  def authoredEnvelope(op: String, entries: Seq[Json], source: String): Json =
    envelope(op, entries, source)

  /** Author one santa-eval/v3 entry: extensions given as {varId -> SValue JSON} per input,
    * decoded to Constants for the eval and written verbatim into the vector. JVM blesses
    * value+cost via evalWithInputExtensions (the oracle; no spec-declared expected to cross-check). */
  def authoredV3Entry(op: String, script: String, treeBytesHex: String, name: String,
      inputExtensionsJson: Seq[Map[Byte, Json]], activated: Byte): Json = {
    val inputExtensions =
      inputExtensionsJson.map(_.map { case (id, j) => id -> EvalCore.decodeInputConstant(j) })
    val (_, outcome) = EvalCore.evalWithInputExtensions(treeBytesHex, inputExtensions, activated)
    val (valueJson, cost) = outcome match {
      case Right(vc) => vc
      case Left(err) => sys.error(s"authoredV3Entry: eval failed for '$op' ($script): $err")
    }
    val inputsJson = inputExtensionsJson.map { ext =>
      Json.obj("extension" -> Json.obj(
        ext.toSeq.sortBy(_._1).map { case (id, j) => (id & 0xff).toString -> j }: _*))
    }
    Json.obj(
      "name"           -> Json.fromString(name),
      "script"         -> Json.fromString(script),
      "tree_bytes_hex" -> Json.fromString(treeBytesHex),
      "inputs"         -> Json.arr(inputsJson: _*),
      "version"        -> Json.obj("activated" -> Json.fromInt(activated.toInt),
                                   "ergoTree"  -> Json.fromInt(activated.toInt)),
      "expected"       -> Json.obj("value" -> valueJson, "cost" -> Json.fromLong(cost), "error" -> Json.Null))
  }

  def authoredV3Envelope(op: String, entries: Seq[Json], source: String): Json =
    Json.obj(
      "schema"     -> Json.fromString("santa-eval/v3"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "source"     -> Json.fromString(source),
      "entries"    -> Json.arr(entries: _*))

  /** Encode captured cases into an ExtractResult: group by op; quarantine any op whose
    * property threw mid-capture (its Capture list may be truncated, so it must never
    * ship); encode the rest via `toEntry` (Opaque/unsupported-kind cases are skipped
    * with a recorded reason); wrap each op's surviving entries in the v2 envelope
    * stamped with `source`. `activated` is the script version the cases are re-blessed
    * and version-stamped at. Pure (no file IO) so tests can assert on the result. */
  def encode(
      captures: Seq[Capture],
      skippedUnsupported: Int,
      skippedError: Int,
      skippedContext: Int,
      skippedContextReasons: Seq[String],
      propertyFailures: Seq[String],
      activated: Byte,
      source: String): ExtractResult = {
    val byOp: Map[String, Seq[Capture]] = captures.groupBy(_.op)
    val unsupportedKindReasons = mutable.LinkedHashSet.empty[String]
    val costDiagnostics = mutable.ArrayBuffer.empty[String]
    var skippedUnsupportedKind = 0
    var rejectsCaptured = 0
    val vectors = mutable.Map.empty[String, Json]
    byOp.foreach { case (op, cs) =>
      if (propertyFailures.exists(_.startsWith(op))) {
        System.err.println(s"[SpecExtract] QUARANTINED op '$op' — its property threw; skipping all ${cs.size} captured entries")
      } else {
        var entryIndex = 0
        val entries = cs.flatMap { c =>
          val outcome = if (c.expectsFailure) rejectToEntry(c, entryIndex, activated)
                        else toEntry(c, entryIndex, activated)
          outcome match {
            case Right((entry, diag)) =>
              diag.foreach(costDiagnostics += _)
              if (c.expectsFailure) rejectsCaptured += 1
              entryIndex += 1
              Some(entry)
            case Left(reason) => skippedUnsupportedKind += 1; unsupportedKindReasons += reason; None
          }
        }
        if (entries.nonEmpty) vectors += (op -> envelope(op, entries, source))
      }
    }
    ExtractResult(
      vectors = vectors.toMap,
      captured = vectors.values.map(_.hcursor.downField("entries").values.fold(0)(_.size)).sum,
      skippedUnsupported = skippedUnsupported,
      skippedError = skippedError,
      skippedContext = skippedContext,
      skippedUnsupportedKind = skippedUnsupportedKind,
      rejectsCaptured = rejectsCaptured,
      unsupportedKindReasons = unsupportedKindReasons.toSeq,
      skippedContextReasons = skippedContextReasons,
      costDiagnostics = costDiagnostics.toSeq,
      propertyFailures = propertyFailures)
  }

  /** Persist the extracted vectors to a staging dir (a build artifact — NOT the
    * committed vectors/eval/, which lands in Task 5). One file per op. */
  def writeVectors(result: ExtractResult, outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    // Totality: two ops sharing a slug would write the same file, silently dropping
    // one's entries. Refuse loudly rather than overwrite (a silent drop once lost the
    // `|| boolean`/`|| sigma` ops to their `&&` siblings).
    val collisions = result.vectors.keys.groupBy(slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("SpecExtract.writeVectors: slug collision would silently drop entries — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    result.vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${slug(op)}.json")
      java.nio.file.Files.write(path,
        json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
