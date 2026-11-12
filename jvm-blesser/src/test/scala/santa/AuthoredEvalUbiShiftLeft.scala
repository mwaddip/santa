package santa

// Authored eval vectors for sigma-rust's santa-probe-ubi-shiftleft (2026-11-12): UnsignedBigInt.shiftLeft
// past 256 bits, the unsigned sibling of BigInt.shift_and_inverse_range_edges' H1-H3. Measured first in
// santa.spike.UbiShiftLeftProbe; the reply is prompts/drafts/sigma-rust-ubi-shiftleft-reply.md.
//
// THE RULE, read in 6.0.7 and measured: `CUnsignedBigInt.shiftLeft` is `CUnsignedBigInt(wrappedValue
// .shiftLeft(n))` (CUnsignedBigInt.scala:86) with no `…ValueExact` call, so the CONSTRUCTOR is what refuses a
// result above 256 bits (:20-22): `ArithmeticException: Too big unsigned big int value <n>`, unwrapped (the
// method has a userDefinedInvoke). The shift AMOUNT is bounded first, `bits < 0 || bits >= 256` →
// IllegalArgumentException (UnsignedBigIntegerOps.scala:131-137). The spec corpus has the amount bounds for
// `shiftRight` (UnsignedBigInt_methods #20/#21) but none for `shiftLeft`, and no shift past the range.
//
// Every row runs the spec's own tree, UnsignedBigInt_methods row 16 — `{ (x: (UnsignedBigInt, Int)) =>
// x._1.shiftLeft(x._2) }` — with the input bound as the tuple, so the rows differ only in their input.

import java.math.BigInteger

import io.circe.Json
import sigma.VersionContext

object AuthoredEvalUbiShiftLeft {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Op = "UnsignedBigInt.shiftLeft_past_256_bits"
  val Source = "santa:authored-eval-ubi-shiftleft"
  val Stamp = "jvm:sigma-state-6.0.7"

  /** UnsignedBigInt_methods row 16's tree (v3, size-flagged, constant-segregated). */
  val Row16 = "1b1900dad901014504dc090c8c720101018c72010201e4e3014504"

  def p2(n: Int): BigInteger = BigInteger.ONE.shiftLeft(n)
  val Max: BigInteger = p2(256).subtract(BigInteger.ONE)

  def pair(x: BigInteger, n: Int): Json = Json.obj("kind" -> Json.fromString("Tuple"), "items" -> Json.arr(
    Json.obj("kind" -> Json.fromString("UnsignedBigInt"), "value" -> Json.fromString(x.toString)),
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(n))))

  /** (id, x, n, accept, slug, script) */
  val Rows: Seq[(String, BigInteger, Int, Boolean, String, String)] = Seq(
    ("U1", p2(255), 1, false, "2pow255-shiftleft-1-errored",
      "`UnsignedBigInt(2^255).shiftLeft(1)` — THROWS `ArithmeticException: Too big unsigned big int value " +
      "2^256`: the result has 257 bits and the CONSTRUCTOR refuses it (CUnsignedBigInt.scala:20-22); the " +
      "unsigned shiftLeft makes no …ValueExact call of its own (:86). SEPARATING: a 256-bit shift that drops " +
      "the high bits returns 0."),
    ("U2", Max, 1, false, "max-shiftleft-1-errored",
      "`UnsignedBigInt(2^256 - 1).shiftLeft(1)` — THROWS, as U1 (2^257 - 2). SEPARATING: a dropping shift " +
      "returns 2^256 - 2."),
    ("U3", BigInteger.valueOf(3), 255, false, "3-shiftleft-255-errored",
      "`UnsignedBigInt(3).shiftLeft(255)` — THROWS, as U1 (3·2^255). SEPARATING: a dropping shift returns 2^255."),
    ("U4", BigInteger.ONE, 255, true, "1-shiftleft-255-accept",
      "`UnsignedBigInt(1).shiftLeft(255)` — 2^255 at cost 104. CONTROL: 256 bits fit, the top bit set."),
    ("U5", p2(254), 1, true, "2pow254-shiftleft-1-accept",
      "`UnsignedBigInt(2^254).shiftLeft(1)` — 2^255 at cost 104. CONTROL: the same result from the other side."),
    ("U6", BigInteger.ZERO, 255, true, "0-shiftleft-255-accept",
      "`UnsignedBigInt(0).shiftLeft(255)` — 0 at cost 104. CONTROL: the largest legal amount on a zero."),
    ("L1", BigInteger.ONE, 256, false, "1-shiftleft-256-errored",
      "`UnsignedBigInt(1).shiftLeft(256)` — THROWS `IllegalArgumentException: Wrong argument in " +
      "UnsignedBigInt.shiftLeft: bits < 0 || bits >= 256 (256)`: the AMOUNT bound, checked before any shift " +
      "(UnsignedBigIntegerOps.scala:131-137). The spec pins it for shiftRight only."),
    ("L2", BigInteger.ONE, -1, false, "1-shiftleft-minus-1-errored",
      "`UnsignedBigInt(1).shiftLeft(-1)` — THROWS the same amount-bound IllegalArgumentException."))

  def extract(): Map[String, Json] = {
    val entries = Rows.zipWithIndex.map { case ((id, x, n, accept, slug, script), i) =>
      val name = s"$slug#$i"
      if (accept) SpecExtract.authoredEntryV(Op, s"[$id] $script", Row16, name, pair(x, n), V3, ergoTree = 3)
      else SpecExtract.authoredRejectEntryV(Op, s"[$id] $script", Row16, name, pair(x, n), V3, ergoTree = 3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString(Stamp))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalUbiShiftLeft", extract(), outDir)
}
