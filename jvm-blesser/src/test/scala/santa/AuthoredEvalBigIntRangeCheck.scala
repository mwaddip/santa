package santa

// Authored eval vectors for sigma-rust queue item 8, probe 3 §3 (2026-11-11): the 256-bit BigInt
// range check runs only from TREE version 3, so below it `BigInt.MIN / -1` produces 2^255.
//
// THE RULE, measured (santa.spike.MixedWidthProbe3) and read back in source:
//   * `CBigInt`'s constructor throws on `bitLength > 255` only
//     `if (VersionContext.current.isV3OrLaterErgoTreeVersion …)` (CBigInt.scala:18) — the TREE
//     version, not the activated one (the anchoring test pins that v0 answers identically at
//     activated 2 and 3).
//   * `divide` adds no check of its own (:45), while `add`/`subtract`/`multiply`/`negate` call
//     `toSignedBigIntValueExact` unconditionally (:39-43, :55).
//   So at v0 `MIN / -1` (MIN = -2^255, bitLength 255, in range) is 2^255 — OUTSIDE the 256-bit
//   signed range — and `- 1` brings it back: `2^255 - 1` @60. At v3 the quotient's own
//   construction throws.
//
// G1 RETURNS AN OUT-OF-RANGE BIGINT AS THE SCRIPT'S VALUE at v0. That is the JVM's real answer, and
// an implementation whose BigInt type is 256-bit cannot represent it: that red is the divergence,
// not a vector defect. G0 is the in-range form of the same fact.
//
// G3 shows `divide` and `negate` DIFFER below v3: `-MIN` is the same 2^255 and throws at EVERY
// version. G4/G5 are there so G4 is not misread as a MIN effect: BigInt `%` is `BigInteger.mod` by
// design (BigIntegerOps.scala:84-97), so ANY negative divisor throws, at every version.

import io.circe.Json
import sigma.VersionContext

object AuthoredEvalBigIntRangeCheck {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated 3 in every entry; the TREE version varies
  val Op = "BigInt.range_check_tree_version"
  val Source = "santa:authored-eval-bigint-range-check"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def v3(body: String): String = "0b" + "%02x".format(body.length / 2) + body
  private def v0(body: String): String = "00" + body
  def treeFor(body: String, treeV: Int): String = if (treeV == 3) v3(body) else v0(body)

  // BigInt constants: 0x06, a VLQ byte length, the two's-complement bytes.
  val Min = "0620" + "80" + "00" * 31          // BigInt -2^255, bitLength 255
  // Bodies, each DERIVED from an AST in the probe; G0 byte-identical to the sigma-rust session's.
  val G0 = "99" + "9d" + Min + "0601ff" + "060101" // (MIN / -1) - 1
  val G1 = "9d" + Min + "0601ff"                   // MIN / -1, alone
  val G2 = "999d0601060601ff060101"                // (6 / -1) - 1  — the honest twin
  val G3 = "f0" + Min                              // -(MIN)
  val G4 = "9e" + Min + "0601ff"                   // MIN % -1
  val G5 = "9e0601070601fe"                        // 7 % -2

  def extract(): Map[String, Json] = {
    def accept(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredEntryV(Op, s"[$id] $script", treeFor(body, treeV), name, input, V3, ergoTree = treeV)
    def reject(id: String, script: String, body: String, treeV: Int, name: String): Json =
      SpecExtract.authoredRejectEntryV(Op, s"[$id] $script", treeFor(body, treeV), name, input, V3, ergoTree = treeV)

    val gate = "CBigInt's constructor checks bitLength > 255 only from TREE version 3 (CBigInt.scala:18)"

    val entries = Seq(
      reject("G0", s"`(BigInt.MIN / -1) - 1` at tree v3 — THROWS `ArithmeticException: Too big bigint " +
        s"value 2^255` from the quotient's own construction: $gate.", G0, 3,
        "min-div-minus-one-minus-one-v3-errored#0"),
      accept("G0", s"`(BigInt.MIN / -1) - 1` at tree v0 — 2^255 - 1 at cost 60. Below v3 the constructor " +
        "does not check and `divide` adds nothing (CBigInt.scala:45), so the quotient is 2^255, " +
        "out of range; `subtract`'s unconditional toSignedBigIntValueExact (:41) then passes 2^255 - 1. " +
        "THE SAME BODY as the v3 row, one header byte apart.", G0, 0,
        "min-div-minus-one-minus-one-v0-accept#1"),

      reject("G1", "`BigInt.MIN / -1` alone at tree v3 — throws, the same construction check.", G1, 3,
        "min-div-minus-one-v3-errored#2"),
      accept("G1", "`BigInt.MIN / -1` alone at tree v0 — RETURNS 2^255 at cost 35, a BigInt OUTSIDE the " +
        "256-bit signed range, as the script's value. The JVM's real answer below v3: an " +
        "implementation whose BigInt type is 256 bits cannot represent it, and that red is the " +
        "divergence. G0 is the in-range form of the same fact.", G1, 0,
        "min-div-minus-one-v0-accept#3"),

      accept("G2", "`(BigInt(6) / -1) - 1` at tree v3 — THE HONEST TWIN of G0: in-range values in the " +
        "same shape, -7 at cost 60. It makes G0's v3 throw the RANGE check's and not the shape's.",
        G2, 3, "twin-div-minus-one-minus-one-v3-accept#4"),
      accept("G2", "`(BigInt(6) / -1) - 1` at tree v0 — -7 at 60, unchanged: G0's v0 cost is the " +
        "twin's, so magnitude costs nothing.", G2, 0, "twin-div-minus-one-minus-one-v0-accept#5"),

      reject("G3", "`-(BigInt.MIN)` at tree v3 — throws `BigInteger out of 256 bit range`: `negate` " +
        "checks UNCONDITIONALLY (CBigInt.scala:55).", G3, 3, "negate-min-v3-errored#6"),
      reject("G3", "`-(BigInt.MIN)` at tree v0 — throws at v0 TOO, where G1 returns the same 2^255: " +
        "below v3 `divide` and `negate` DIFFER.", G3, 0, "negate-min-v0-errored#7"),

      reject("G4", "`BigInt.MIN % -1` at tree v3 — throws `BigInteger: modulus not positive`. Not a MIN " +
        "effect: BigInt `%` is BigInteger.mod by design (BigIntegerOps.scala:84-97), see G5.", G4, 3,
        "min-mod-minus-one-v3-errored#8"),
      reject("G4", "`BigInt.MIN % -1` at tree v0 — the same at v0.", G4, 0,
        "min-mod-minus-one-v0-errored#9"),

      reject("G5", "`BigInt(7) % BigInt(-2)` at tree v3 — throws `modulus not positive`: ANY negative " +
        "divisor, which is what G4's throw is.", G5, 3, "small-mod-negative-v3-errored#10"),
      reject("G5", "`BigInt(7) % BigInt(-2)` at tree v0 — the same at v0.", G5, 0,
        "small-mod-negative-v0-errored#11"))

    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalBigIntRangeCheck", extract(), outDir)
}
