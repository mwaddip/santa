package santa

// Authored eval vectors — a v3 MethodCall whose receiver is of another kind. The four controls
// carry value AND cost; the six wrong-receiver rows are `errored`.
//
// The mechanism, the corrected exception class and the 6.0.7 line check are all in
// `ReceiverKindTrees`. The short version: the serializer checks no receiver, so these parse, and
// the reflective invoke refuses them one level earlier than the `asInstanceOf` the probe read —
// `IllegalArgumentException`, not `ClassCastException`, and with two different messages. The eval
// tier's coarse `errored` is the right granularity for precisely that reason.
//
// THE WIDER QUESTION, measured rather than read (`ReceiverKindProbe` part D): of the 199
// parser-visible methods `SMethod.fromIds` resolves, **158 were called with a deliberately wrong
// receiver and NOT ONE returned a value** — 45 `ClassCastException`, 68 `IllegalArgumentException`,
// 33 `RuntimeException`, 12 `NoSuchMethodException`. So the rule "a receiver of another kind
// throws" holds everywhere it could be measured, including the `userDefinedInvoke` handlers the
// probe had not read. The 41 unmeasured are methods whose argument types could not be synthesised
// (Box, Header, AvlTree, SigmaProp, type-var and function domains) or whose call would not
// build/serialize; the sweep says nothing about those, and that count is the honest gap.

import io.circe.Json

object AuthoredEvalReceiverKind {
  val Op     = "MethodCall.receiver_kind"
  val Source = "santa:authored-receiver-kind"
  private val V3: Byte = ReceiverKindTrees.Activated

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    val entries = ReceiverKindTrees.rows.map { r =>
      val tree = ReceiverKindTrees.evalTree(r.expr)
      val name = s"${r.slug}-${if (r.control) "accept" else "errored"}#${r.n - 1}"
      val script = s"${r.what}  // row ${r.n} of the probe"
      if (r.control) SpecExtract.authoredEntry(Op, script, tree, name, dummyInput, V3)
      else SpecExtract.authoredRejectEntry(Op, script, tree, name, dummyInput, V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalReceiverKind", extract(), outDir)
}
