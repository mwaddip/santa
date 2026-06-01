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

/** The captured-and-encoded extraction result for the whole spec. */
final case class ExtractResult(
    vectors: Map[String, Json],          // op (property name) -> v2 envelope Json
    captured: Int,                       // entries emitted
    skippedUnsupported: Int,             // feature not supported at the pinned version
    skippedError: Int,                   // expected value is a Failure
    skippedContext: Int,                 // input is a Context (Stage 2b; Box/Header now captured)
    skippedUnsupportedKind: Int,         // input/value of a kind valueToJson can't encode
    unsupportedKindReasons: Seq[String], // distinct "op: kind not encodable" detail lines
    skippedContextReasons: Seq[String],  // distinct "op: input=<class> | <script>" for Context skips
    costDiagnostics: Seq[String],        // DIAGNOSTIC: cases where eval cost != a spec cost field
    propertyFailures: Seq[String])       // properties whose body threw at the pinned version (logged, not fatal)

object SpecExtract {

  /** Filesystem-safe slug for an op name (property name) → vector filename stem. */
  def slug(op: String): String =
    op.trim
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
      costDetailsCost: Option[Int])  // CostDetails.cost = summed JIT trace (the eval cost the spec pins)

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
  private[santa] def toEntry(c: Capture, entryIndex: Int, activated: Byte): Either[String, (Json, Option[String])] = {
    val inputJson = EvalCore.valueToJson(c.input)
    val valueJson = EvalCore.valueToJson(c.expectedValue)

    // Escalation: refuse to emit an Opaque (unsupported Stage-1 kind), incl. one
    // nested in a Coll/Tuple. Skip + report (never bake a guessed encoding).
    if (hasOpaque(inputJson))
      return Left(s"${c.op}: input kind not encodable — ${inputJson.noSpaces}")
    if (hasOpaque(valueJson))
      return Left(s"${c.op}: expected-value kind not encodable — ${valueJson.noSpaces}")

    // Re-bless via SANTA's apply-eval (the SAME CErgoTreeEvaluator the spec uses).
    val (_, outcome) = EvalCore.evalApplied(c.treeBytesHex, inputJson, activated = activated)
    val (evalValue, evalCost) = outcome match {
      case Right(vc) => vc
      case Left(err) =>
        sys.error(s"SpecExtract: apply-eval failed for op '${c.op}' (${c.script}) " +
          s"on input ${inputJson.noSpaces}: $err")
    }

    // The eval value MUST match the spec's expected value (canonical-by-construction).
    if (evalValue.noSpaces != valueJson.noSpaces)
      sys.error(s"SpecExtract: VALUE MISMATCH for op '${c.op}' (${c.script}): " +
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
      "version"        -> Json.obj("activated" -> Json.fromInt(activated.toInt),
                                   "ergoTree"  -> Json.fromInt(activated.toInt)),
      "expected"       -> Json.obj("value" -> valueJson,
                                   "cost"  -> Json.fromLong(evalCost),
                                   "error" -> Json.Null)), diag))
  }

  /** Wrap a property's entries in the v2 envelope. */
  private def envelope(op: String, entries: Seq[Json], source: String): Json =
    Json.obj(
      "schema"     -> Json.fromString("santa-eval/v2"),
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
    val vectors = mutable.Map.empty[String, Json]
    byOp.foreach { case (op, cs) =>
      if (propertyFailures.exists(_.startsWith(op))) {
        System.err.println(s"[SpecExtract] QUARANTINED op '$op' — its property threw; skipping all ${cs.size} captured entries")
      } else {
        var entryIndex = 0
        val entries = cs.flatMap { c =>
          toEntry(c, entryIndex, activated) match {
            case Right((entry, diag)) => diag.foreach(costDiagnostics += _); entryIndex += 1; Some(entry)
            case Left(reason)         => skippedUnsupportedKind += 1; unsupportedKindReasons += reason; None
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
      unsupportedKindReasons = unsupportedKindReasons.toSeq,
      skippedContextReasons = skippedContextReasons,
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
