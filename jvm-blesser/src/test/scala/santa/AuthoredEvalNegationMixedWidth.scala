package santa

// Authored eval vectors for sigma-rust queue item 8, probe 3 §1 (2026-11-11): `Negation` takes its
// implementation from the input's STATIC type. The third node of the mixed-width set, after
// `ArithOp.mixed_width` and `Comparison.mixed_width` — same lever, a unary node this time.
//
// THE RULE, measured (santa.spike.MixedWidthProbe3) and read back in source:
// `Negation.eval` evaluates the input, then `ArithOp.impls(input.tpe.typeCode).i.negate(inputV)`
// (trees.scala:885-890, FixedCost 30). So the negation is chosen by the STATIC type and the value
// is cast to it: a value narrower than its static type throws, and so does one WIDER than it.
//
// The levers are `If(false, t, f)`: `If.tpe` is the TRUE branch's and nothing checks the false one.
// `If(false, 1L, 2)` is Long-typed and Int-valued (pinned as B5/B6 of `ArithOp.mixed_width`);
// `If(false, 1, 2L)` is the MIRROR, Int-typed and Long-valued, and is pinned HERE as L1 because
// nothing else in the corpus does and N2 would silently prove nothing without it.
//
// NO VERSION PAIR EXISTS: a unary node has no second operand to upcast, so the deserialization
// builder inserts nothing at any version. Every script is authored at v3 AND v0 regardless, which
// is what shows the gate absent rather than unexercised.
//
// N3 is the HONEST TWIN of N1 — the same shape with a Long in the false branch. It is what makes
// N1's refusal about the VALUE and not about negating an `If`, and its cost (50 = the If's 20 + 30)
// is the number an implementation answering N1 with a value would most likely charge.

import io.circe.Json
import sigma.VersionContext

object AuthoredEvalNegationMixedWidth {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated 3 throughout; the TREE version varies
  val Op = "Negation.mixed_width"
  val Source = "santa:authored-eval-negation-mixed-width"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def v3(body: String): String = "0b" + "%02x".format(body.length / 2) + body
  private def v0(body: String): String = "00" + body
  def treeFor(body: String, treeV: Int): String = if (treeV == 3) v3(body) else v0(body)

  // Bodies, each DERIVED from an AST in the probe; N0/N1 byte-identical to the sigma-rust session's.
  val N0 = "f00502"            // -(1L)                — the control
  val N1 = "f095010005020404"  // -If(false, 1L, 2)    — Long-typed, Int VALUE
  val N2 = "f095010004020504"  // -If(false, 1, 2L)    — Int-typed, Long VALUE: the mirror
  val N3 = "f095010005020504"  // -If(false, 1L, 2L)   — the honest twin of N1
  val L1 = "95010004020504"    // If(false, 1, 2L)     — the mirror LEVER, alone

  def extract(): Map[String, Json] = {
    def accept(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredEntryV(Op, s"[$id] $script", treeFor(body, treeV), name, input, V3, ergoTree = treeV)
    def reject(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredRejectEntryV(Op, s"[$id] $script", treeFor(body, treeV), name, input, V3, ergoTree = treeV)

    val table = "Negation takes ArithOp.impls(input.tpe.typeCode) (trees.scala:885-890) and casts the " +
      "value to it"
    val noGate = "throws at v0 TOO: a unary node has nothing to upcast, so no version gate exists here"

    val entries = Seq(
      accept("N0", "`-(1L)` at tree v3 — THE CONTROL. Long -1 at cost 35: an honest value, so the " +
        "negation returns and every refusal below is about the VALUE, not about Negation itself.",
        N0, 3, "control-negate-long-v3-accept#0"),
      accept("N0", "`-(1L)` at tree v0 — the control's v0 twin, also Long -1 at 35.", N0, 0,
        "control-negate-long-v0-accept#1"),

      reject("N1", s"`-If(false, 1L, 2)` at tree v3 — a Long-typed input with an Int VALUE. $table, " +
        "so the Integer is cast to Long: ClassCastException. The static type decides, not the value.",
        N1, 3, "negate-narrow-value-v3-errored#2"),
      reject("N1", s"`-If(false, 1L, 2)` at tree v0 — $noGate.", N1, 0,
        "negate-narrow-value-v0-errored#3"),

      reject("N2", s"`-If(false, 1, 2L)` at tree v3 — THE MIRROR: an Int-typed input with a Long " +
        s"VALUE, i.e. a value WIDER than its static type. $table, so the Long is cast DOWN to " +
        "Integer: ClassCastException the other way. The static type decides in BOTH directions, so " +
        "refusing only a narrow value is not the rule.", N2, 3, "negate-wide-value-v3-errored#4"),
      reject("N2", s"`-If(false, 1, 2L)` at tree v0 — $noGate.", N2, 0,
        "negate-wide-value-v0-errored#5"),

      accept("N3", "`-If(false, 1L, 2L)` at tree v3 — THE HONEST TWIN of N1: the same shape with a " +
        "Long in the false branch. Long -2 at cost 50 (the If's 20 + Negation's 30), which makes " +
        "N1's refusal about the VALUE and not about negating an If.", N3, 3,
        "negate-if-honest-long-v3-accept#6"),
      accept("N3", "`-If(false, 1L, 2L)` at tree v0 — the twin's v0 twin, also Long -2 at 50.", N3, 0,
        "negate-if-honest-long-v0-accept#7"),

      accept("L1", "`If(false, 1, 2L)` alone at tree v3 — THE MIRROR LEVER: an Int-typed expression " +
        "evaluating to a Long 2 at cost 20, `If.tpe` being the TRUE branch's. It must keep " +
        "returning a LONG or N2 demonstrates nothing.", L1, 3, "if-false-int-long-v3-accept#8"),
      accept("L1", "`If(false, 1, 2L)` alone at tree v0 — unchanged at v0: the If is indifferent to " +
        "the version.", L1, 0, "if-false-int-long-v0-accept#9"))

    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalNegationMixedWidth", extract(), outDir)
}
