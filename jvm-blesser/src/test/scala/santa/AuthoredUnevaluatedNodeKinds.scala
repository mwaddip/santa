package santa

// Authored eval vectors — the thirteen node kinds the JVM PARSES and never EVALUATES.
//
// `ValueSerializer.serializers` registers a reader for each, so a tree carrying one is a valid
// script; none defines `eval`, so reducing one throws `RuntimeException: Should be overriden in
// class sigma.ast.<Node>` (`values.scala:101-102`). Measured, not read: every one of the thirteen
// errors at eval on 6.0.7, under activated 3 at tree version 3.
//
// Why this is a conformance question and not trivia: an implementation that DOES evaluate one of
// these accepts a spend the JVM refuses. The sigma-rust fork evaluates `CreateAvlTree`,
// `BitOr`/`BitAnd`/`BitXor` and `BitInversion` (its own reading, 2026-11-08), so five of these
// thirteen are live over-accepts there; the other eight are regression guards.
//
// The trees are the v3 size-flagged form. The v0 unsized twins carry the same eval verdict (no
// `eval` override is version-independent) and so add nothing HERE — they are where the wire tier
// separates instead, because a missing READER has no degrade path at v0:
// `AuthoredWireUnevaluatedNodeKinds`.
//
// `SpecExtract.authoredRejectEntry` re-derives each verdict through `EvalCore.evalApplied` and
// fails loud if the JVM evaluates one, so this family cannot rot into a false expectation.

import io.circe.Json

object AuthoredUnevaluatedNodeKinds {
  val Op     = "Unevaluated.node_kinds"
  val Source = "santa:authored-unevaluated-node-kinds"
  private val V3: Byte = UnevaluatedNodeTrees.Activated

  /** The eval tier applies the tree to an input; these trees read none, so it is a placeholder. */
  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    val v3Entries = UnevaluatedNodeTrees.nodes.zipWithIndex.map { case (n, i) =>
      SpecExtract.authoredRejectEntry(Op,
        s"${n.what}  // parsed, never evaluated: no `eval` override -> errored",
        UnevaluatedNodeTrees.v3(n.body), s"${n.slug}-errored#$i", dummyInput, V3)
    }
    // The four-value CreateAvlTree layout at tree version 0, which needs an expression as its
    // fourth argument to exist at all (rule 1009). Its eval verdict is the same `errored`, and on
    // an implementation that evaluates CreateAvlTree it is the v0 half of that over-accept.
    val v0Entries = UnevaluatedNodeTrees.extraEncodings.zipWithIndex.map { case (n, j) =>
      SpecExtract.authoredRejectEntryV(Op,
        s"${n.what}  // parsed, never evaluated: no `eval` override -> errored",
        UnevaluatedNodeTrees.v0(n.body),
        s"${n.slug}-errored#${UnevaluatedNodeTrees.nodes.size + j}", dummyInput, V3, 0)
    }
    val entries = v3Entries ++ v0Entries
    // SpecExtract stamps 6.0.3; this family was blessed on the current oracle, as
    // AuthoredEvalNonPairTuple does for the same reason.
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredUnevaluatedNodeKinds", extract(), outDir)
}
