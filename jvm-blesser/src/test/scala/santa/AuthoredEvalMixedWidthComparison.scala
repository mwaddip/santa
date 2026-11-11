package santa

// Authored eval vectors for sigma-rust queue item 8, probe 2 (2026-11-11): the COMPARISONS reach
// their ordering through the same table as arithmetic. The companion of
// `ArithOp.mixed_width` — same lever, different nodes.
//
// THE RULE, measured (santa.spike.MixedWidthComparisonProbe) and read back in source:
// `lazy val opImpl = ArithOp.impls(left.tpe.typeCode)` (trees.scala:1075-1078), then
// `opImpl.o.lt(lV, rV)` (:1090-1098); Min/Max go through the same `o`. So the ordering is taken
// from the LEFT operand's STATIC type and both values are cast to it, and two values narrower
// than their static type throw in a comparison exactly as they throw in an addition.
//
// The lever is `If(false, 1L, n)`: `If.tpe` is the TRUE branch's and nothing checks the false one,
// so it is a Long-typed expression that evaluates to an Int (`Int 2` @20 — pinned as B5/B6 of
// `ArithOp.mixed_width`). The parser cannot refuse these: `comparisonOp` checks `SameType` on the
// STATIC types, and those agree.
//
// NO VERSION PAIR EXISTS HERE, and that is itself the point: both static types already agree, so
// the deserialization builder has no `Upcast` to insert and the version gate never engages. Every
// row below throws at v3 AND v0; R0 is the only one that returns. Both versions are authored
// anyway, because that is what shows the gate is ABSENT rather than merely unexercised.
//
// R7 IS A CONTROL, AND ITS WHOLE VALUE IS THAT IT FAILS DIFFERENTLY. `I2 == I2` does not reach the
// ordering table at all: `EQ`'s own `Value.checkType` site catches the narrow value first and
// raises `InterpreterException: Invalid type returned by evaluator`, where every other row raises
// `ClassCastException`. The eval tier grades refusals COARSELY, so the vector cannot state that
// difference — an implementation that fixed only `EQ` would go green on R7 and still be wrong on
// the other eight, and nothing in the JSON would say so. The anchoring test therefore asserts the
// two mechanisms apart from the oracle's own error strings, and each row's `script` names which
// mechanism it is exercising.

import io.circe.Json
import sigma.VersionContext

object AuthoredEvalMixedWidthComparison {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated 3 throughout; the TREE version varies
  val Op = "Comparison.mixed_width"
  val Source = "santa:authored-eval-mixed-width-comparison"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def v3(body: String): String = "0b" + "%02x".format(body.length / 2) + body
  private def v0(body: String): String = "00" + body
  def treeFor(body: String, treeV: Int): String = if (treeV == 3) v3(body) else v0(body)

  // Bodies, each DERIVED from an AST in the probe and byte-identical to the sigma-rust session's.
  // I2 = If(false, 1L, 2) = 95010005020404 — static type Long, value Int 2
  // I3 = If(false, 1L, 3) = 95010005020406
  val R0 = "9105020504"                       // 1L > 2L        — the control
  val R1 = "919501000502040695010005020404"   // I3 > I2        — the refutation row
  val R2 = "91950100050204040502"             // I2 > 1L
  val R3 = "91050695010005020404"             // 3L > I2
  val R4 = "8f9501000502040495010005020406"   // I2 < I3
  val R5 = "929501000502040495010005020404"   // I2 >= I2
  val R6 = "909501000502040495010005020404"   // I2 <= I2
  val R7 = "939501000502040495010005020404"   // I2 == I2       — the DIFFERENT mechanism
  val R8 = "a19501000502040495010005020406"   // min(I2, I3)

  def extract(): Map[String, Json] = {
    def accept(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredEntryV(Op, s"[$id] $script", treeFor(body, treeV), name, input, V3, ergoTree = treeV)
    def reject(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredRejectEntryV(Op, s"[$id] $script", treeFor(body, treeV), name, input, V3, ergoTree = treeV)

    val table = "the ordering comes from ArithOp.impls(left.tpe.typeCode) (trees.scala:1075-1078) " +
      "and both values are cast to it, so a value narrower than its static type fails with a " +
      "ClassCastException"
    val noGate = "throws at v0 TOO: both static types already agree, so the builder has no Upcast " +
      "to insert and the version gate never engages"

    val entries = Seq(
      accept("R0", "`1L > 2L` at tree v3 — THE CONTROL. false at cost 30: honest widths, so the " +
        "comparison returns and every refusal below is about the VALUES, not about comparison itself.",
        R0, 3, "control-long-gt-long-v3-accept#0"),
      accept("R0", "`1L > 2L` at tree v0 — the control's v0 twin, also false at 30.", R0, 0,
        "control-long-gt-long-v0-accept#1"),

      reject("R1", s"`If(false,1L,3) > If(false,1L,2)` at tree v3 — BOTH operands Long-typed with " +
        s"Int VALUES. THE ROW THAT WOULD HAVE REFUTED THE READING: $table. It throws, so the " +
        "ordering IS reached through the static type.", R1, 3, "narrow-both-gt-v3-errored#2"),
      reject("R1", s"`If(false,1L,3) > If(false,1L,2)` at tree v0 — $noGate.", R1, 0,
        "narrow-both-gt-v0-errored#3"),

      reject("R2", "`If(false,1L,2) > 1L` at tree v3 — the narrow value on the LEFT, an honest " +
        "Long on the right.", R2, 3, "narrow-left-gt-long-v3-errored#4"),
      reject("R2", "`If(false,1L,2) > 1L` at tree v0 — the same at v0.", R2, 0,
        "narrow-left-gt-long-v0-errored#5"),

      reject("R3", "`3L > If(false,1L,2)` at tree v3 — the mirror of R2: an honest Long on the " +
        "left, the narrow value on the right. Both sides are cast, so the side does not save it.",
        R3, 3, "long-gt-narrow-right-v3-errored#6"),
      reject("R3", "`3L > If(false,1L,2)` at tree v0 — the same at v0.", R3, 0,
        "long-gt-narrow-right-v0-errored#7"),

      reject("R4", "`If(false,1L,2) < If(false,1L,3)` at tree v3 — LT takes the same table as GT.",
        R4, 3, "narrow-both-lt-v3-errored#8"),
      reject("R4", "`If(false,1L,2) < If(false,1L,3)` at tree v0 — the same at v0.", R4, 0,
        "narrow-both-lt-v0-errored#9"),

      reject("R5", "`If(false,1L,2) >= If(false,1L,2)` at tree v3 — GE likewise. Note it would be " +
        "trivially TRUE if it were reached: the refusal is the table's, not the comparison's.",
        R5, 3, "narrow-both-ge-v3-errored#10"),
      reject("R5", "`If(false,1L,2) >= If(false,1L,2)` at tree v0 — the same at v0.", R5, 0,
        "narrow-both-ge-v0-errored#11"),

      reject("R6", "`If(false,1L,2) <= If(false,1L,2)` at tree v3 — LE, completing the four " +
        "orderings. Also trivially true if reached.", R6, 3, "narrow-both-le-v3-errored#12"),
      reject("R6", "`If(false,1L,2) <= If(false,1L,2)` at tree v0 — the same at v0.", R6, 0,
        "narrow-both-le-v0-errored#13"),

      reject("R7", "`If(false,1L,2) == If(false,1L,2)` at tree v3 — **THE CONTROL THAT FAILS " +
        "DIFFERENTLY, and the point of its being here.** EQ never reaches the ordering table: its " +
        "own Value.checkType site catches the narrow value first and raises " +
        "`InterpreterException: Invalid type returned by evaluator`, where every row above raises " +
        "`ClassCastException`. The eval tier grades refusals COARSELY, so this vector CANNOT state " +
        "that difference — an implementation that fixed only EQ would go green here and stay wrong " +
        "on the other eight. Read this row's role, not just its verdict.",
        R7, 3, "narrow-both-eq-checktype-v3-errored#14"),
      reject("R7", "`If(false,1L,2) == If(false,1L,2)` at tree v0 — the same different mechanism " +
        "at v0.", R7, 0, "narrow-both-eq-checktype-v0-errored#15"),

      reject("R8", "`min(If(false,1L,2), If(false,1L,3))` at tree v3 — Min/Max go through the SAME " +
        "`o` as the orderings (impl.o.min), so this is the table again under a different node, and " +
        "an ArithOp rather than a relation.", R8, 3, "narrow-both-min-v3-errored#16"),
      reject("R8", "`min(If(false,1L,2), If(false,1L,3))` at tree v0 — the same at v0.", R8, 0,
        "narrow-both-min-v0-errored#17"))

    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalMixedWidthComparison", extract(), outDir)
}
