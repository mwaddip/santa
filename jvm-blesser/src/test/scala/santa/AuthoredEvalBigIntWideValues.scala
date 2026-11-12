package santa

// Authored eval vectors for sigma-rust's santa-probe-bigint-range-2 (2026-11-12), the review of its
// fork PR 81 (`fork/fix/bigint-runtime-range`). Measured first in santa.spike.BigIntRangeProbe2; the
// reply is prompts/drafts/sigma-rust-bigint-range-2-reply.md. Follows `AuthoredEvalBigIntRangeCheck`
// (G0-G5): X = `BigInt.MIN / -1` is 2^255 below tree version 3, where CBigInt's constructor does not
// check (CBigInt.scala:18), and Y = `6 / -1` is its in-range twin.
//
// THREE FAMILIES, three readings from that review, each measured as read:
//
//  * `SubstConstants.wide_new_value` (tree v0). `toArrayOfConstants` lifts every new value with no
//    range check, and `substituteConstants` serializes a new value only at a position inside the tree's
//    constants that is the FIRST to name it (ErgoTreeSerializer.scala:286-300, :345-361). So the
//    constant writer's `fitsIn256Bits` requirement fires only for an X it writes: S1 (X at position 0)
//    and S5 (X first of two at 0) throw; S2/S3 (positions 5 and -1, outside the tree's one constant)
//    and S4 (Y first, X second at 0) return. The twins carry Y where the rows carry X and cost the same.
//  * `GroupElement.exp_methodcall_wide_exponent`. `ExponentiateMethod` (id 3) is in the method table
//    below v3, the serializer builds a plain MethodCall, and `invokeFixed` applies no range check: at v0
//    the method call returns g^(2^255), the point the `Exponentiate` NODE returns (E1), at 4 more cost.
//    At v3 the exponent X itself throws.
//  * `BigInt.shift_and_inverse_range_edges` (tree v3). `shiftLeft` wraps its result in
//    `toSignedBigIntValueExact` (CBigInt.scala:67), so a shift past the range throws "BigInteger out of
//    256 bit range" (H1-H3); `BigInt(-1).shiftLeft(255)` is MIN and fits (H5). `shiftRight` (R1/R2)
//    and `bitwiseInverse` (I1/I2, `CBigInt(x.not())`) cannot leave the range at its edges. The shift
//    AMOUNT bounds (< 0, >= 256) are already pinned by the spec corpus (BigInt_6.0_features #29/#30/
//    #36/#37), `toSigned` of 2^255 by UnsignedBigInt.mod_toSigned_domain_rejects, and `-MIN` by G3, so
//    none is repeated here.
//
// Each tree is built from its AST; where the sigma-rust session gave an encoding (S1-S5, M1, M2) the
// anchoring test pins the two equal. Every tree ignores its input (Int 0 at var 1).

import java.math.BigInteger

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalBigIntWideValues {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated 3 in every entry; the TREE version varies
  val Source = "santa:authored-eval-bigint-wide-values"
  val Stamp = "jvm:sigma-state-6.0.7"
  val OpSubst = "SubstConstants.wide_new_value"
  val OpExp = "GroupElement.exp_methodcall_wide_exponent"
  val OpShift = "BigInt.shift_and_inverse_range_edges"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  /** The body's bytes, serialized in the version context of the tree it goes into. */
  def body(ast: Value[SType], treeV: Int): String = VersionContext.withVersions(V3, treeV.toByte) {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(ast, w); hx(w.toBytes)
  }
  /** An unsized v0 tree, or a size-flagged v3 one; constants inline. */
  def tree(body: String, treeV: Int): String =
    if (treeV == 0) "00" + body else "0b" + vlq(body.length / 2) + body

  // ── values ──────────────────────────────────────────────────────────────────────────────────────
  val Two255: BigInteger = BigInteger.ONE.shiftLeft(255)
  val Min: BigInteger = Two255.negate()
  val Max: BigInteger = Two255.subtract(BigInteger.ONE)
  private def BI(n: BigInteger): Value[SType] = any(BigIntConstant(n))
  private def I(n: Int): Value[SType] = any(IntConstant(n))
  private def as(v: Value[SType]) = v.asInstanceOf[Value[SBigInt.type]]
  private def div(l: Value[SType], r: Value[SType]) = any(ArithOp(as(l), as(r), ArithOp.Division.opCode))
  val X: Value[SType] = div(BI(Min), BI(BigInteger.ONE.negate()))              // 2^255 below v3
  val Y: Value[SType] = div(BI(BigInteger.valueOf(6)), BI(BigInteger.ONE.negate())) // -6, in range

  /** The tree substConstants rewrites: one segregated BigInt constant, `sigmaProp(g.exp(c0) == g)`. */
  val T = "1001060101d1939f82730082"
  private def subst(pos: Seq[Int], vals: Seq[Value[SType]]) = any(SubstConstants(
    ByteArrayConstant(Base16.decode(T).get), IntArrayConstant(pos.toArray),
    ConcreteCollection(vals, SBigInt).asInstanceOf[Value[SCollection[SType]]]))

  private def mcall(typeId: Int, methodId: Int, recv: Value[SType], args: Value[SType]*): Value[SType] =
    VersionContext.withVersions(V3, V3) {
      any(MethodCall(recv, SMethod.fromIds(typeId.toByte, methodId.toByte), args.toIndexedSeq, Map()))
    }
  private val GG: Value[SType] = any(GroupGenerator)
  private def shl(x: Value[SType], n: Int) = mcall(6, 12, x, I(n))
  private def shr(x: Value[SType], n: Int) = mcall(6, 13, x, I(n))
  private def inv(x: Value[SType])         = mcall(6, 8, x)

  // The sigma-rust session's encodings (santa-probe-bigint-range-2.md), pinned against the ASTs.
  val Xh = "9d0620" + "80" + "00" * 31 + "0601ff"
  val Yh = "9d0601060601ff"

  /** One row. `accept` rows return; the rest throw. `requestHex` is the probe's body, "" where none. */
  case class Row(op: String, id: String, script: String, ast: () => Value[SType], treeV: Int,
                 accept: Boolean, slug: String, requestHex: String = "")

  private val writer = "the constant WRITER's `fitsIn256Bits` requirement"
  val Rows: Seq[Row] = Seq(
    Row(OpSubst, "S1", s"`substConstants(t, Coll(0), Coll(X))` at tree v0 — THROWS `IllegalArgumentException: " +
      s"requirement failed: BigInt value 2^255 doesn't fit into 256 bits`, $writer: X is written at position " +
      "0, the tree's one constant. SEPARATING: a lifter that range-checks every new value throws on S2-S4 too.",
      () => subst(Seq(0), Seq(X)), 0, accept = false, "x-at-position-0-errored",
      "740e0c" + T + "100100" + "830106" + Xh),
    Row(OpSubst, "S2", "`substConstants(t, Coll(5), Coll(X))` at tree v0 — `t` UNCHANGED at cost 265: position 5 " +
      "names no constant of `t`, so X is never written and the writer's range check never runs. " +
      "SEPARATING: an implementation that range-checks the lifted values throws.",
      () => subst(Seq(5), Seq(X)), 0, accept = true, "x-at-position-past-the-constants-accept",
      "740e0c" + T + "10010a" + "830106" + Xh),
    Row(OpSubst, "S3", "`substConstants(t, Coll(-1), Coll(X))` at tree v0 — `t` UNCHANGED at cost 265: a negative " +
      "position names no constant either. SEPARATING, as S2.",
      () => subst(Seq(-1), Seq(X)), 0, accept = true, "x-at-negative-position-accept",
      "740e0c" + T + "100101" + "830106" + Xh),
    Row(OpSubst, "S4", "`substConstants(t, Coll(0, 0), Coll(Y, X))` at tree v0 — `t` with -6, " +
      "`10010601fad1939f82730082`, at cost 300: the FIRST value to name a position is the one written, so Y " +
      "is written and X is not. SEPARATING: an implementation that writes the last value, or range-checks " +
      "every value, throws.",
      () => subst(Seq(0, 0), Seq(Y, X)), 0, accept = true, "y-then-x-at-position-0-accept",
      "740e0c" + T + "10020000" + "830206" + Yh + Xh),
    Row(OpSubst, "S5", s"`substConstants(t, Coll(0, 0), Coll(X, Y))` at tree v0 — THROWS, as S1: X is first, " +
      s"so X is written and $writer fires.",
      () => subst(Seq(0, 0), Seq(X, Y)), 0, accept = false, "x-then-y-at-position-0-errored",
      "740e0c" + T + "10020000" + "830206" + Xh + Yh),
    Row(OpSubst, "S2t", "`substConstants(t, Coll(5), Coll(Y))` at tree v0 — THE TWIN of S2, in range: `t` " +
      "unchanged at 265, the same cost, so X's magnitude costs nothing.",
      () => subst(Seq(5), Seq(Y)), 0, accept = true, "twin-y-at-position-past-the-constants-accept"),
    Row(OpSubst, "S4t", "`substConstants(t, Coll(0, 0), Coll(Y, Y))` at tree v0 — THE TWIN of S4: the same -6 " +
      "tree at 300, the same cost.",
      () => subst(Seq(0, 0), Seq(Y, Y)), 0, accept = true, "twin-y-then-y-at-position-0-accept"),

    Row(OpExp, "M1", "`MethodCall(groupGenerator, GroupElement.exp, X)` at tree v0 — g^(2^255) at cost 949: " +
      "`ExponentiateMethod` (id 3) is in the table below v3 and `invokeFixed` applies no range check, so " +
      "the call returns the point the node returns (E1). SEPARATING: an implementation that refuses a " +
      "BigInt outside the range as an exponent throws.",
      () => mcall(7, 3, GG, X), 0, accept = true, "x-exponent-v0-accept", "dc07038201" + Xh),
    Row(OpExp, "M1", "`MethodCall(groupGenerator, GroupElement.exp, X)` at tree v3 — THROWS at X itself, " +
      "`ArithmeticException: Too big bigint value` (the CBigInt constructor checks from v3).",
      () => mcall(7, 3, GG, X), 3, accept = false, "x-exponent-v3-errored", "dc07038201" + Xh),
    Row(OpExp, "M2", "`MethodCall(groupGenerator, GroupElement.exp, Y)` at tree v0 — the in-range twin: " +
      "g^(-6) at cost 949, M1's cost.",
      () => mcall(7, 3, GG, Y), 0, accept = true, "y-exponent-v0-accept", "dc07038201" + Yh),
    Row(OpExp, "M2", "`MethodCall(groupGenerator, GroupElement.exp, Y)` at tree v3 — the same point at 949.",
      () => mcall(7, 3, GG, Y), 3, accept = true, "y-exponent-v3-accept", "dc07038201" + Yh),
    Row(OpExp, "E1", "`groupGenerator.exp(X)`, the `Exponentiate` NODE, at tree v0 — the same g^(2^255) as M1, " +
      "at cost 945: the method-call form costs 4 more, and the exponent's range moves neither.",
      () => any(Exponentiate(GroupGenerator, as(X))), 0, accept = true, "x-exponent-node-v0-accept"),

    Row(OpShift, "H1", "`BigInt(1).shiftLeft(255)` at tree v3 — THROWS `ArithmeticException: BigInteger out " +
      "of 256 bit range`: `shiftLeft` checks its result with toSignedBigIntValueExact (CBigInt.scala:67), " +
      "before the constructor does. SEPARATING: a 256-bit shift that wraps returns BigInt.MIN.",
      () => shl(BI(BigInteger.ONE), 255), 3, accept = false, "one-shiftleft-255-errored"),
    Row(OpShift, "H2", "`BigInt(2).shiftLeft(255)` at tree v3 — THROWS, as H1. SEPARATING: a wrapping shift " +
      "returns 0.",
      () => shl(BI(BigInteger.valueOf(2)), 255), 3, accept = false, "two-shiftleft-255-errored"),
    Row(OpShift, "H3", "`BigInt.MAX.shiftLeft(1)` at tree v3 — THROWS, as H1. SEPARATING: a wrapping shift " +
      "returns -2.",
      () => shl(BI(Max), 1), 3, accept = false, "max-shiftleft-1-errored"),
    Row(OpShift, "H4", "`BigInt(1).shiftLeft(254)` at tree v3 — 2^254 at cost 19. CONTROL: the largest power " +
      "of two in range.",
      () => shl(BI(BigInteger.ONE), 254), 3, accept = true, "one-shiftleft-254-accept"),
    Row(OpShift, "H5", "`BigInt(-1).shiftLeft(255)` at tree v3 — BigInt.MIN at cost 19. CONTROL: the result " +
      "has bitLength 255 and fits, so the check is on the RANGE, not on the shift amount or the sign bit.",
      () => shl(BI(BigInteger.ONE.negate()), 255), 3, accept = true, "minus-one-shiftleft-255-accept"),
    Row(OpShift, "R1", "`BigInt.MIN.shiftRight(255)` at tree v3 — -1 at cost 19: an in-range value shifted " +
      "right stays in range.",
      () => shr(BI(Min), 255), 3, accept = true, "min-shiftright-255-accept"),
    Row(OpShift, "R2", "`BigInt.MAX.shiftRight(255)` at tree v3 — 0 at cost 19.",
      () => shr(BI(Max), 255), 3, accept = true, "max-shiftright-255-accept"),
    Row(OpShift, "I1", "`BigInt.MIN.bitwiseInverse` at tree v3 — BigInt.MAX at cost 14: `~x = -x - 1` maps the " +
      "range onto itself, so the inverse of either edge is the other.",
      () => inv(BI(Min)), 3, accept = true, "min-bitwiseinverse-accept"),
    Row(OpShift, "I2", "`BigInt.MAX.bitwiseInverse` at tree v3 — BigInt.MIN at cost 14.",
      () => inv(BI(Max)), 3, accept = true, "max-bitwiseinverse-accept"))

  def extract(): Map[String, Json] =
    Seq(OpSubst, OpExp, OpShift).map { op =>
      val entries = Rows.filter(_.op == op).zipWithIndex.map { case (r, i) =>
        val t = tree(body(r.ast(), r.treeV), r.treeV)
        val name = s"${r.slug}#$i"
        if (r.accept) SpecExtract.authoredEntryV(op, s"[${r.id}] ${r.script}", t, name, input, V3, ergoTree = r.treeV)
        else SpecExtract.authoredRejectEntryV(op, s"[${r.id}] ${r.script}", t, name, input, V3, ergoTree = r.treeV)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalBigIntWideValues", extract(), outDir)
}
