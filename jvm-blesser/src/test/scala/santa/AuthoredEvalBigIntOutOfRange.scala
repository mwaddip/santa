package santa

// Authored eval vectors for the BigInt range round (fork PR 81; sigma-rust's santa-probe-bigint-range, queue item
// 13b). Measured by SANTA then (spike santa.spike.BigIntRangeProbe; reply prompts/drafts/sigma-rust-bigint-range-
// reply.md) and vectored now under the backlog's standing rule (2026-11-14). The core of the rule is already
// BigInt.range_check_tree_version (MIN / -1 at v0 and v3, negation, `%`); these are the rows it did not hold.
//
// THE RULE, read in 6.0.7: `CBigInt`'s constructor refuses a value wider than 255 bits only from tree v3
// (CBigInt.scala:18). Below v3, `add`/`subtract`/`multiply`/`negate` check their RESULT
// (`toSignedBigIntValueExact`, CBigInt.scala:39/:43: `BigInteger out of 256 bit range`), and `divide`/`mod`/
// `min`/`max` do not. So X = `BigInt.MIN / -1` = 2^255 exists below v3, and what it does next:
//  * arithmetic, ordering and equality on X (B1-B17): an unchecked operation keeps or shrinks it, a checked one
//    refuses its result;
//  * nothing that only MOVES a value looks at its magnitude: a collection, a tuple, a ValDef, a lambda argument,
//    a map (S1-S5, each at exactly its in-range twin's cost, Y = 6 / -1);
//  * `Exponentiate` takes it: g^(2^255) = g^MAX · g (E1-E3);
//  * `SubstConstants` REFUSES it, at the data writer's own range requirement (C1);
//  * below v3 a cast never takes a BigInt input at all (K1/K2 refuse an in-range BigInt the same way, K3 control).
//
// One family, BigInt.out_of_range_below_v3, at activated 2, tree v0: the v5 (mainnet) consensus case. Left out:
// the `powDistance` rows of the reply's §4 (their inputs are v1 headers whose `d` ≥ 2^255 exists on a chain only at
// difficulty exactly 1, a reachability question the reply left open), the method calls (rule 1016 below v3, not a
// range matter) and the bit operations (unimplemented at every version).

import java.math.BigInteger

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}
import sigma.serialization.ErgoTreeSerializer.DefaultSerializer
import sigma.serialization.ValueCodes.OpCode

object AuthoredEvalBigIntOutOfRange {
  val Act: Byte = 2
  val Source = "santa:authored-bigint-out-of-range"
  val Stamp = "jvm:sigma-state-6.0.7"
  val Op = "BigInt.out_of_range_below_v3"

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase

  // ── AST builders (the spike's) ──────────────────────────────────────────────────────────────────────
  private def BI(n: BigInteger): Value[SType] = BigIntConstant(n).asInstanceOf[Value[SType]]
  private def I(n: Int): Value[SType] = IntConstant(n).asInstanceOf[Value[SType]]
  private val Min = BigInteger.ONE.shiftLeft(255).negate()
  private val Max = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)
  private val M1 = BigInteger.ONE.negate()
  private def as(v: Value[SType]) = v.asInstanceOf[Value[SBigInt.type]]
  private def ar(l: Value[SType], r: Value[SType], op: OpCode): Value[SType] = ArithOp(as(l), as(r), op).asInstanceOf[Value[SType]]
  private def plus(l: Value[SType], r: Value[SType]) = ar(l, r, ArithOp.Plus.opCode)
  private def minus(l: Value[SType], r: Value[SType]) = ar(l, r, ArithOp.Minus.opCode)
  private def mul(l: Value[SType], r: Value[SType]) = ar(l, r, ArithOp.Multiply.opCode)
  private def div(l: Value[SType], r: Value[SType]) = ar(l, r, ArithOp.Division.opCode)
  private def rem(l: Value[SType], r: Value[SType]) = ar(l, r, ArithOp.Modulo.opCode)
  private def mn(l: Value[SType], r: Value[SType]) = ar(l, r, ArithOp.Min.opCode)
  private def mx(l: Value[SType], r: Value[SType]) = ar(l, r, ArithOp.Max.opCode)
  private def neg(v: Value[SType]) = Negation(as(v)).asInstanceOf[Value[SType]]
  private def gt(l: Value[SType], r: Value[SType]) = GT(as(l), as(r)).asInstanceOf[Value[SType]]
  private def eq(l: Value[SType], r: Value[SType]) = EQ(l, r).asInstanceOf[Value[SType]]
  private val X: Value[SType] = div(BI(Min), BI(M1))
  private val Y: Value[SType] = div(BI(BigInteger.valueOf(6)), BI(M1))
  private def collOf(v: Value[SType]) = ConcreteCollection(Array(v), SBigInt).asInstanceOf[Value[SCollection[SType]]]
  private def idx0(c: Value[SCollection[SType]]) = ByIndex(c, IntConstant(0)).asInstanceOf[Value[SType]]
  private def lamMinus1 = FuncValue(IndexedSeq((1, SBigInt: SType)), minus(ValUse(1, SBigInt), BI(BigInteger.ONE)))
  private def viaColl(v: Value[SType]) = minus(idx0(collOf(v)), BI(BigInteger.ONE))
  private def viaTuple(v: Value[SType]) =
    minus(SelectField(Tuple(v, I(1)).asInstanceOf[Value[STuple]], 1.toByte).asInstanceOf[Value[SType]], BI(BigInteger.ONE))
  private def viaValDef(v: Value[SType]) =
    BlockValue(IndexedSeq(ValDef(1, v)), minus(ValUse(1, SBigInt), BI(BigInteger.ONE))).asInstanceOf[Value[SType]]
  private def viaApply(v: Value[SType]) = Apply(lamMinus1, IndexedSeq(v)).asInstanceOf[Value[SType]]
  private def viaMap(v: Value[SType]) =
    idx0(MapCollection(collOf(v), lamMinus1.asInstanceOf[Value[SFunc]]).asInstanceOf[Value[SCollection[SType]]])
  private val GG: Value[SGroupElement.type] = GroupGenerator
  private def exp(v: Value[SType]) = Exponentiate(GG, as(v)).asInstanceOf[Value[SType]]
  private lazy val innerTreeBytes: Array[Byte] = VersionContext.withVersions(Act, 0) {
    val root = BoolToSigmaProp(EQ(Exponentiate(GG, BigIntConstant(BigInteger.ONE)), GG))
    DefaultSerializer.serializeErgoTree(ErgoTree.withSegregation(ErgoTree.ZeroHeader, root))
  }
  private def subst(v: Value[SType]) = SubstConstants(ByteArrayConstant(innerTreeBytes),
    IntArrayConstant(Array(0)), collOf(v).asInstanceOf[Value[SCollection[SType]]]).asInstanceOf[Value[SType]]
  private def body(v: Value[SType]): String = VersionContext.withVersions(Act, 0) {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(v, w); hx(w.toBytes)
  }
  private val Xh = "9d0620" + "80" + "00" * 31 + "0601ff"
  private def castOp(c: OpCode) = f"${c.toByte & 0xff}%02x"

  case class Row(id: String, script: String, body: String, accept: Boolean)

  private val Unchecked = "an unchecked operation (no range check below v3) keeps or shrinks the value"
  private val Checked = "THROWS `BigInteger out of 256 bit range`: a checked operation refuses its RESULT " +
    "(toSignedBigIntValueExact)"
  private def b(id: String, s: String, v: Value[SType], accept: Boolean, why: String): Row =
    Row(id, s"`$s`, X = `BigInt.MIN / -1` = 2^255 — $why. SEPARATING: an implementation " +
      "that range-checks every BigInt below v3 (or holds no 2^255) refuses X itself.", body(v), accept)

  lazy val Rows: Seq[Row] = Seq(
    b("B1", "X / 1", div(X, BI(BigInteger.ONE)), accept = true, s"2^255: $Unchecked"),
    b("B2", "X / -1", div(X, BI(M1)), accept = true, s"BigInt.MIN: $Unchecked"),
    b("B3", "X * 1", mul(X, BI(BigInteger.ONE)), accept = false, Checked),
    b("B4", "X * -1", mul(X, BI(M1)), accept = true, "BigInt.MIN: the checked multiply's result is in range"),
    b("B5", "X * 0", mul(X, BI(BigInteger.ZERO)), accept = true, "0: the result is in range"),
    b("B6", "X + 0", plus(X, BI(BigInteger.ZERO)), accept = false, Checked),
    b("B7", "X + (-1)", plus(X, BI(M1)), accept = true, "2^255 − 1: the result is in range"),
    b("B8", "-X", neg(X), accept = true, "BigInt.MIN: negation checks its result, which is in range"),
    b("B9", "min(X, 0)", mn(X, BI(BigInteger.ZERO)), accept = true, s"0: $Unchecked"),
    b("B10", "max(X, 0)", mx(X, BI(BigInteger.ZERO)), accept = true, s"2^255: $Unchecked"),
    b("B11", "X > 0", gt(X, BI(BigInteger.ZERO)), accept = true, "true: ordering reads the value as it is"),
    b("B12", "X > BigInt.MAX", gt(X, BI(Max)), accept = true, "TRUE: X is above MAX"),
    b("B13", "X == X", eq(X, X), accept = true, "true"),
    b("B14", "X == BigInt.MAX", eq(X, BI(Max)), accept = true, "false: X is not MAX"),
    b("B15", "X % 7", rem(X, BI(BigInteger.valueOf(7))), accept = true, s"1: $Unchecked"),
    b("B16", "7 % X", rem(BI(BigInteger.valueOf(7)), X), accept = true, s"7: $Unchecked"),
    b("B17", "X % X", rem(X, X), accept = true, s"0: $Unchecked")) ++
    Seq[(String, String, Value[SType] => Value[SType])](
      ("S1", "Coll(_)(0) - 1", viaColl), ("S2", "(_, 1)._1 - 1", viaTuple), ("S3", "{ val x = _; x - 1 }", viaValDef),
      ("S4", "{ (a: BigInt) => a - 1 }(_)", viaApply), ("S5", "Coll(_).map(a => a - 1)(0)", viaMap)
    ).flatMap { case (id, s, f) => Seq(
      Row(id, s"`${s.replace("_", "X")}` → 2^255 − 1: nothing that only moves a value looks at its magnitude; the " +
        s"cost is the twin's (${id}t). SEPARATING: an implementation that checks a BigInt when it is stored or " +
        "bound refuses it.", body(f(X)), accept = true),
      Row(s"${id}t", s"`${s.replace("_", "Y")}`, Y = `6 / -1` → -7. THE IN-RANGE TWIN of $id: the same nodes.",
        body(f(Y)), accept = true))
    } ++ Seq(
    Row("E1", "`groupGenerator.exp(X)` → g^(2^255): `Exponentiate` takes the out-of-range exponent. SEPARATING: an " +
      "implementation that refuses or reduces it answers otherwise.", body(exp(X)), accept = true),
    Row("E2", "`g.exp(X) == g.exp(BigInt.MAX).multiply(g)` → true: the exponent is used at its full value.",
      body(eq(exp(X), MultiplyGroup(exp(BI(Max)).asInstanceOf[Value[SGroupElement.type]], GG).asInstanceOf[Value[SType]])),
      accept = true),
    Row("E3", "`g.exp(BigInt.MAX)`. CONTROL for E1: the in-range maximum.", body(exp(BI(Max))), accept = true),
    Row("C1", "`substConstants(t, Coll(0), Coll(X))` — THROWS `IllegalArgumentException: BigInt value … doesn't fit " +
      "into 256 bits`: the data writer's own range requirement, the one consumer below v3 that range-checks a value " +
      "it is handed. SEPARATING: a writer without the check writes the tree.", body(subst(X)), accept = false),
    Row("C1t", "`substConstants(t, Coll(0), Coll(Y))` → the tree with the constant -6. CONTROL for C1.",
      body(subst(Y)), accept = true),
    Row("K1", "`Downcast(X, Long)` — THROWS `Cannot downcast value CBigInt(2^255)`: below v3 a cast takes no BigInt " +
      "input at all (K1t refuses an in-range one the same way).", castOp(sigma.serialization.OpCodes.DowncastCode) + Xh + "05",
      accept = false),
    Row("K1t", "`Downcast(Y, Long)` — THROWS the same: not a range matter.",
      castOp(sigma.serialization.OpCodes.DowncastCode) + body(Y) + "05", accept = false),
    Row("K2", "`Upcast(X, BigInt)` — THROWS `Cannot upcast value CBigInt(2^255)`.",
      castOp(sigma.serialization.OpCodes.UpcastCode) + Xh + "06", accept = false),
    Row("K2t", "`Upcast(Y, BigInt)` — THROWS the same.", castOp(sigma.serialization.OpCodes.UpcastCode) + body(Y) + "06",
      accept = false),
    Row("K3", "`Upcast(7L, BigInt)` → 7. CONTROL: a cast from a Long.", castOp(sigma.serialization.OpCodes.UpcastCode) +
      "050e06", accept = true))

  def extract(): Map[String, Json] = {
    val entries = Rows.zipWithIndex.map { case (r, i) =>
      val tree = "00" + r.body
      val name = s"${r.id.toLowerCase}-${if (r.accept) "accept" else "errored"}#$i"
      val s = s"[${r.id}] ${r.script} Tree v0 under activated 2."
      if (r.accept) SpecExtract.authoredEntryV(Op, s, tree, name, IntInput, Act, ergoTree = 0)
      else SpecExtract.authoredRejectEntryV(Op, s, tree, name, IntInput, Act, ergoTree = 0)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalBigIntOutOfRange", extract(), outDir)
}
