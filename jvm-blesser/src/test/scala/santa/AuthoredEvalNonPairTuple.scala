package santa

// Authored eval vectors for sigma-rust's evaluated-values regrade follow-up (2026-09-30): Value.checkType on a tuple
// that is not a pair. A constant triple's value is a Coll (Evaluation.toDslTuple, `Evaluation.scala:99-102`), and
// isValueOfType throws "Unsupported tuple type" for an STuple of arity other than 2 (`SType.scala:200-202`) wherever a
// value is checked: EQ's operands (`trees.scala:1206-1208`), a ValDef (`values.scala:1027`), a lambda's argument
// (`:1074`). The pair twins pass. Each tree is a v3 expression that ignores its input (Int 0 at var 1), evaluated under
// the node's v6 context through the same EvalCore oracle as every authored eval vector.

import io.circe.Json
import sigma.VersionContext

object AuthoredEvalNonPairTuple {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Op = "Tuple.non_pair_type_check"
  val Source = "santa:authored-eval-non-pair-tuple"

  private val C3 = "48" + "040404" + "020406" // the (Int, Int, Int) constant (1, 2, 3)
  private val C2 = "58" + "0204"             // the (Int, Int) constant (1, 2)
  /** A size-flagged v3 tree around `body`. */
  private def v3(body: String): String = "0b" + "%02x".format(body.length / 2) + body
  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    val unsupported = "the constant triple's value is a Coll, and isValueOfType throws 'Unsupported tuple type' for " +
      "an STuple of arity 3"
    def reject(script: String, why: String, tree: String, name: String): Json =
      SpecExtract.authoredRejectEntryV(Op, s"$script  // JVM: $why: $unsupported", tree, name, input, V3, ergoTree = 3)
    def accept(script: String, tree: String, name: String): Json =
      SpecExtract.authoredEntryV(Op, s"$script  // JVM: a pair's value is a Tuple2, which passes: true", tree, name,
        input, V3, ergoTree = 3)
    val entries = Seq(
      reject("{ (x: Int) => (1, 2, 3) == (1, 2, 3) }", "EQ checks both operands (trees.scala:1206-1208)",
        v3("93" + C3 + C3), "eq-triple-errored#0"),
      accept("{ (x: Int) => (1, 2) == (1, 2) }", v3("93" + C2 + C2), "eq-pair-accept#1"),
      reject("{ (x: Int) => { val t = (1, 2, 3); t._1 == 1 } }", "BlockValue checks each ValDef's value (values.scala:1027)",
        v3("d801" + "d601" + C3 + "93" + "8c720101" + "0402"), "valdef-triple-errored#2"),
      accept("{ (x: Int) => { val t = (1, 2); t._1 == 1 } }",
        v3("d801" + "d601" + C2 + "93" + "8c720101" + "0402"), "valdef-pair-accept#3"),
      reject("{ (x: Int) => { (t: (Int, Int, Int)) => t._1 }((1, 2, 3)) == 1 }",
        "a lambda checks its argument (values.scala:1074)",
        v3("93" + "da" + "d90101" + "48040404" + "8c720101" + "01" + C3 + "0402"), "lambda-triple-errored#4"),
      accept("{ (x: Int) => { (t: (Int, Int)) => t._1 }((1, 2)) == 1 }",
        v3("93" + "da" + "d90101" + "58" + "8c720101" + "01" + C2 + "0402"), "lambda-pair-accept#5"))
    // SpecExtract's envelope carries the 6.0.3 stamp of the older vectors; these are first blessed on 6.0.6.
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.6"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalNonPairTuple", extract(), outDir)
}
