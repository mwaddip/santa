package santa

// Authored eval vectors for sigma-rust queue item 8, probe 3 §2 (2026-11-11): the JVM DIVIDES
// `MIN / -1` and `MIN % -1` at every fixed width. Only a ZERO divisor throws.
//
// THE RULE, measured (santa.spike.MixedWidthProbe3) and read back in source:
// `ExactIntegral.quot` and `divisionRemainder` are `n.quot` / `n.rem` of Scala's `Integral`
// (ExactIntegral.scala:22, :30), and none of the four fixed-width instances (from :36) overrides
// either — only `plus`/`minus`/`times` are made exact. So the division is plain JVM integer
// division: `MIN / -1` wraps to `MIN` and `MIN % -1` is 0, without an exception. Byte and Short
// go through `(x / y).toByte` / `.toShort`, which lands on the same `MIN`.
//
// WHY SINGLE-OP ROWS: the spec corpus already has `(MIN, -1)` inputs, but only inside the
// equivalence bundle `(a + b, a - b, a * b, a / b, a % b)`, which ERRORS as a whole because the
// exact `+`/`*` overflow first — so it cannot say what the division does. These rows can.
// (`-MIN` wrapping at every width is already pinned by `v5/spec/Numeric_Negation_equivalence`.)
//
// Z1/Z2 are the controls that make the returning rows believable: an `ArithmeticException` on this
// path DOES surface (`/ by zero`), so a value on Q1 is not a swallowed exception — and they are the
// one case that throws, which is what a checked division conflates with overflow.
//
// No version gate exists (both operands share a width, so the builder inserts nothing); every
// script is authored at v3 AND v0 regardless, and every returning row costs the control's 25.

import io.circe.Json
import sigma.VersionContext

object AuthoredEvalDivisionOverflow {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated 3 throughout; the TREE version varies
  val Op = "ArithOp.division_overflow"
  val Source = "santa:authored-eval-division-overflow"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def v3(body: String): String = "0b" + "%02x".format(body.length / 2) + body
  private def v0(body: String): String = "00" + body
  def treeFor(body: String, treeV: Int): String = if (treeV == 3) v3(body) else v0(body)

  // Bodies, each DERIVED from an AST in the probe; Q0-Q4 byte-identical to the sigma-rust
  // session's. Int and Long constants are in the JVM writer's form: a negative zigzag Int
  // sign-extends to ten VLQ bytes.
  val Q0 = "9d040e0404"                     // 7 / 2                       — the control
  val Q1 = "9d04ffffffffffffffffff010401"   // Int.MinValue / -1
  val Q2 = "9e04ffffffffffffffffff010401"   // Int.MinValue % -1
  val Q3 = "9d05ffffffffffffffffff010501"   // Long.MinValue / -1L
  val Q5 = "9e05ffffffffffffffffff010501"   // Long.MinValue % -1L
  val Q4 = "9d028002ff"                     // (-128: Byte) / (-1: Byte)
  val Q6 = "9e028002ff"                     // (-128: Byte) % (-1: Byte)
  val Q7 = "9d03ffff030301"                 // Short.MinValue / (-1: Short)
  val Q8 = "9e03ffff030301"                 // Short.MinValue % (-1: Short)
  val Z1 = "9d040e0400"                     // 7 / 0                       — the throw control
  val Z2 = "9e040e0400"                     // 7 % 0                       — the throw control

  def extract(): Map[String, Json] = {
    def accept(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredEntryV(Op, s"[$id] $script", treeFor(body, treeV), name, input, V3, ergoTree = treeV)
    def reject(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredRejectEntryV(Op, s"[$id] $script", treeFor(body, treeV), name, input, V3, ergoTree = treeV)

    val quot = "ExactIntegral.quot is Scala's n.quot, overridden for no width (ExactIntegral.scala:22)"
    val rem = "ExactIntegral.divisionRemainder is Scala's n.rem, overridden for no width (ExactIntegral.scala:30)"
    def both(id: String, script: String, body: String, accepts: Boolean, slug: String, n: Int): Seq[Json] = {
      val f = if (accepts) accept _ else reject _
      val arm = if (accepts) "accept" else "errored"
      Seq(f(id, s"$script at tree v3.", body, 3, s"$slug-v3-$arm#$n"),
          f(id, s"$script at tree v0 — the same: no version gate exists here.", body, 0, s"$slug-v0-$arm#${n + 1}"))
    }

    val entries =
      both("Q0", "`7 / 2` — THE CONTROL. Int 3 at cost 25: every returning row below costs exactly " +
        "this, so overflow changes nothing about the price", Q0, true, "control-int-div", 0) ++
      both("Q1", s"`Int.MinValue / -1` — Int.MinValue at cost 25, NO exception. $quot, so this is " +
        "plain JVM integer division, which wraps", Q1, true, "int-min-div-minus-one", 2) ++
      both("Q2", s"`Int.MinValue % -1` — Int 0 at cost 25, no exception. $rem", Q2, true,
        "int-min-rem-minus-one", 4) ++
      both("Q3", s"`Long.MinValue / -1L` — Long.MinValue at cost 25, no exception. $quot", Q3, true,
        "long-min-div-minus-one", 6) ++
      both("Q5", s"`Long.MinValue % -1L` — Long 0 at cost 25, no exception. $rem", Q5, true,
        "long-min-rem-minus-one", 8) ++
      both("Q4", "`(-128: Byte) / (-1: Byte)` — Byte -128 at cost 25: Scala's ByteIsIntegral.quot " +
        "is `(x / y).toByte`, so 128 truncates back to -128", Q4, true, "byte-min-div-minus-one", 10) ++
      both("Q6", "`(-128: Byte) % (-1: Byte)` — Byte 0 at cost 25", Q6, true, "byte-min-rem-minus-one", 12) ++
      both("Q7", "`Short.MinValue / (-1: Short)` — Short -32768 at cost 25, truncated like the Byte " +
        "row", Q7, true, "short-min-div-minus-one", 14) ++
      both("Q8", "`Short.MinValue % (-1: Short)` — Short 0 at cost 25", Q8, true,
        "short-min-rem-minus-one", 16) ++
      both("Z1", "`7 / 0` — THE THROW CONTROL: `ArithmeticException: / by zero`. An exception on this " +
        "path does surface, so a value on Q1 is not a swallowed one, and a ZERO divisor is the only " +
        "case that throws", Z1, false, "int-div-by-zero", 18) ++
      both("Z2", "`7 % 0` — the remainder's throw control, the same `ArithmeticException: / by zero`",
        Z2, false, "int-rem-by-zero", 20)

    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalDivisionOverflow", extract(), outDir)
}
