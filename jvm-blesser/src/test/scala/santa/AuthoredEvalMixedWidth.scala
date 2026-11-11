package santa

// Authored eval vectors for sigma-rust queue item 8 (2026-11-11): MIXED-WIDTH ARITHMETIC — where
// the parse-time `Upcast` is inserted, what it costs, and what happens when a VALUE is narrower
// than its static type. Not about tuples, despite arriving inside the tuple-values round.
//
// THE RULE, measured (santa.spike.MixedWidthProbe) and then read back in source:
//   * Below tree version 3 the DESERIALIZATION builder inserts an `Upcast` over the narrower
//     operand (`SigmaBuilder.scala:757-763`); from v3 it inserts nothing.
//   * `ArithOp` then takes the implementation of the LEFT operand's STATIC type and casts BOTH
//     values to it. So the same body is `3L` at v0 and a `ClassCastException` at v3.
//   * The exception DIRECTION is the evidence for that reading: `1L + 2` fails Integer -> Long,
//     while `2 + 1L` fails Long -> Integer — the Long cast DOWN to the left operand's Int.
//   * The `Upcast`'s cost scales with the TARGET width: Int -> Long is 10 (35 against the 25
//     control), Long -> BigInt is 30 (60 against the 30 control). A flat-cost model is 20 short
//     on BigInt, which is why the BigInt control is in the family.
//
// AND THE HALF A VERSION GATE CANNOT REACH: a value narrower than its own static type throws at
// EVERY version, because both static types already agree and there is no `Upcast` to insert.
// `If(false, 1L, 2)` is the lever — `If.tpe` is the TRUE branch's and nothing checks the false
// one, so it is a `Long`-typed expression that evaluates to an `Int` (`Int 2` @20 at both
// versions). Feeding it to arithmetic throws at v3 AND v0, which no version pair would show.
//
// Every script appears TWICE, as a v3 size-flagged tree and as a v0 unsized tree, because the
// version is the whole subject. Costs and classes are the oracle's (6.0.7), via the same
// EvalCore door as every authored eval vector.

import io.circe.Json
import sigma.VersionContext

object AuthoredEvalMixedWidth {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated 3 throughout; the TREE version varies
  val Op = "ArithOp.mixed_width"
  val Source = "santa:authored-eval-mixed-width"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** A size-flagged v3 tree around `body`. */
  private def v3(body: String): String = "0b" + "%02x".format(body.length / 2) + body
  /** The v0 twin: no size bit, so the builder's Upcast insertion is in play. */
  private def v0(body: String): String = "00" + body

  // Bodies. Each was DERIVED from an AST in the probe and came out byte-identical to the
  // sigma-rust session's own encoding; the comments give the shape.
  private val LL   = "9a05020504"                     // 1L + 2L        — the control, no widths to mix
  private val LI   = "9a05020404"                      // 1L + 2
  private val LBI  = "9a0502b248040404020406040000"    // 1L + ByIndex((1,2,3), 0)  — an SAny operand
  private val IL   = "9a04040502"                      // 2 + 1L         — the narrower operand on the LEFT
  private val BIL  = "9a0601010504"                    // BigInt(1) + 2L
  private val BIBI = "9a060101060102"                  // BigInt(1) + BigInt(2) — the BigInt-add base
  private val IFLI = "95010005020404"                  // If(false, 1L, 2) — Long-typed, Int-valued
  private val L_IF = "9a050295010005020404"            // 1L + If(false, 1L, 2)
  private val IF_I = "9a950100050204040404"            // If(false, 1L, 2) + 2

  def extract(): Map[String, Json] = {
    def accept(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredEntryV(Op, s"[$id] $script", if (treeV == 3) v3(body) else v0(body), name,
        input, V3, ergoTree = treeV)
    def reject(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredRejectEntryV(Op, s"[$id] $script", if (treeV == 3) v3(body) else v0(body), name,
        input, V3, ergoTree = treeV)

    val upcastCost = "the v0 twin returns, so the inserted Upcast's cost is the difference"
    val leftWins = "ArithOp takes the LEFT operand's static type and casts both values to it"

    val entries = Seq(
      // ── the control: no widths to mix, so the version cannot matter ───────────────────────
      accept("A1", "`1L + 2L` at tree v3 — THE CONTROL. 3L at cost 25; nothing to upcast, so v3 " +
        "and v0 agree and the pair below is what isolates the Upcast.", LL, 3, "control-long-long-v3-accept#0"),
      accept("A2", "`1L + 2L` at tree v0 — the control's v0 twin, also 3L at 25.", LL, 0,
        "control-long-long-v0-accept#1"),

      // ── the version gate: the SAME body, one header byte apart ────────────────────────────
      reject("A3", s"`1L + 2` at tree v3 — from v3 the builder inserts NO Upcast, so $leftWins and " +
        "the Integer is cast to Long: ClassCastException. THE SEPARATING ROW of the pair.", LI, 3,
        "long-plus-int-v3-errored#2"),
      accept("A4", s"`1L + 2` at tree v0 — the builder inserts an Upcast over the Int " +
        s"(SigmaBuilder.scala:757-763): 3L at cost 35. Against A2's 25, $upcastCost — Int -> Long is 10.",
        LI, 0, "long-plus-int-v0-accept#3"),

      // ── an SAny operand is not a width at all: no Upcast can help it ──────────────────────
      reject("A5", "`1L + ByIndex((1,2,3), 0)` at tree v3 — the right operand is SAny-typed, and " +
        "applyUpcast only widens NUMERICS, so there is nothing to insert: throws.", LBI, 3,
        "long-plus-any-v3-errored#4"),
      reject("A6", "`1L + ByIndex((1,2,3), 0)` at tree v0 — throws at v0 TOO, which is what " +
        "separates an SAny operand from a narrow numeric one: the version gate does not reach it.",
        LBI, 0, "long-plus-any-v0-errored#5"),

      // ── the direction: which operand decides ──────────────────────────────────────────────
      reject("B1", s"`2 + 1L` at tree v3 — the narrower operand on the LEFT. $leftWins, so the " +
        "LONG is cast DOWN: ClassCastException Long -> Integer. The exception's DIRECTION is the " +
        "evidence for the rule, A3 failing the other way on the same pair of types.", IL, 3,
        "int-plus-long-v3-errored#6"),
      accept("B2", "`2 + 1L` at tree v0 — 3L at cost 35, the same as A4: the Upcast goes on " +
        "whichever side is narrower, so the cost does not depend on the order.", IL, 0,
        "int-plus-long-v0-accept#7"),

      // ── the cost scales with the TARGET width, which needs its own base ────────────────────
      reject("B3", "`BigInt(1) + 2L` at tree v3 — throws, CCE Long -> sigma.BigInt.", BIL, 3,
        "bigint-plus-long-v3-errored#8"),
      accept("B4", "`BigInt(1) + 2L` at tree v0 — BigInt 3 at cost 60. Against C1's 30 the " +
        "Upcast to BigInt costs 30, THREE TIMES the Int -> Long one: the inserted node's cost " +
        "scales with the target width, so a flat-cost model is 20 short here.", BIL, 0,
        "bigint-plus-long-v0-accept#9"),
      accept("C1", "`BigInt(1) + BigInt(2)` at tree v3 — THE BIGINT CONTROL, 30 at both versions. " +
        "Without it B4's 60 would be read against the LONG base of 25 and the Upcast mis-costed " +
        "at 35; the family exists to make that subtraction legitimate.", BIBI, 3,
        "control-bigint-bigint-v3-accept#10"),
      accept("C2", "`BigInt(1) + BigInt(2)` at tree v0 — the control's v0 twin, also 30.", BIBI, 0,
        "control-bigint-bigint-v0-accept#11"),

      // ── a VALUE narrower than its STATIC type: the half no version pair can show ───────────
      accept("B5", "`If(false, 1L, 2)` alone at tree v3 — Int 2 at cost 20. `If.tpe` is the TRUE " +
        "branch's (Long) and nothing checks the false one, so this is a Long-typed expression " +
        "with an Int VALUE. The lever for B7-B10, and it must keep returning or they prove nothing.",
        IFLI, 3, "if-false-long-int-v3-accept#12"),
      accept("B6", "`If(false, 1L, 2)` alone at tree v0 — Int 2 at 20, unchanged: the If is " +
        "indifferent to the version, so B8/B10's throws are not the builder's doing.", IFLI, 0,
        "if-false-long-int-v0-accept#13"),

      reject("B7", "`1L + If(false, 1L, 2)` at tree v3 — both static types are already Long, so " +
        "there is no Upcast to insert and the narrow VALUE is cast: throws.", L_IF, 3,
        "long-plus-narrow-value-v3-errored#14"),
      reject("B8", "`1L + If(false, 1L, 2)` at tree v0 — throws at v0 TOO. THE ROW THE FAMILY IS " +
        "FOR: the static types agree, so the version gate has nothing to do, and a value narrower " +
        "than its static type fails at EVERY version.", L_IF, 0, "long-plus-narrow-value-v0-errored#15"),
      reject("B9", "`If(false, 1L, 2) + 2` at tree v3 — the narrow value on the LEFT: the Long " +
        "implementation is chosen from the static type and gets an Integer. Throws.", IF_I, 3,
        "narrow-value-plus-int-v3-errored#16"),
      reject("B10", "`If(false, 1L, 2) + 2` at tree v0 — throws at v0 too, the mirror of B8 with " +
        "the narrow value on the other side.", IF_I, 0, "narrow-value-plus-int-v0-errored#17"))

    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalMixedWidth", extract(), outDir)
}
