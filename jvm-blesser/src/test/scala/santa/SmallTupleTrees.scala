package santa

// Tuples of fewer than two items, and the 128-item boundary of a script tuple. Shared by the wire
// blesser (`AuthoredWireSmallTuples`) and the eval one, so both tiers grade the same bytes.
//
// Measured on the JVM at 6.0.7, activated 3, by `santa.spike.SmallTuplesProbe`; the request is
// `prompts/santa-probe-small-tuples.md` (sigma-rust session, 2026-11-08).
//
// Three independent asymmetries, and they do NOT line up:
//
//   a tuple TYPE      reads 0..255 items (`TypeSerializer.scala:188-194`, `getUByte`, no lower
//                     bound) and the WRITER refuses fewer than two (`:94-95`, `sys.error`). So a
//                     0/1-item tuple type PARSES and can never be written back — and
//                     `ErgoBoxCandidate.serializer` re-writes a box's tree from STRUCTURE
//                     (`:142` -> `serializeErgoTree`, which echoes raw bytes only for a
//                     Left/unparsed tree) and each register through `w.putValue` (`:175`). The
//                     carrier therefore dies on the WRITE, after a parse that already succeeded.
//   a tuple EXPRESSION reads its count as a SIGNED byte (`TupleSerializer.scala:28`) and writes no
//                     type at all. So 0..127 items parse and round-trip VERBATIM, and 0x80 is -128
//                     -> `safeNewArray(-128)`, whose `checkLength` bounds only the upper end
//                     (`sigma/util/package.scala:9-13`) -> NegativeArraySizeException.
//   `Tuple.eval`      refuses any count but two (`values.scala:825-827`), and
//                     `Evaluation.toDslTuple` hands back the raw `Coll[Any]` for anything but a
//                     pair (`Evaluation.scala:99-102`) — which is the eval tier's half.
//
// WHICH ROW SEPARATES, per arm — this is the point of the family and each row says so:
//
//   TypeWrite   REJECTED at both versions, for the same JVM reason (the write). An implementation
//               that refuses the tuple at PARSE has a degrade path at v3 and none at v0, so the
//               **v3 row is the only one that can separate** (it degrades to opaque bytes and
//               ACCEPTS where the JVM rejects) and the **v0 row grades green on both sides**. The
//               v0 row is a control, not evidence. Per the `reject-arm-must-surface-over-accept`
//               memory: do not call this arm done until conform shows the expected diverger RED on
//               the v3 rows.
//   CountBound  REJECTED at both versions. An implementation reading the count UNSIGNED parses 128
//               items successfully, so it accepts where the JVM rejects — **both versions
//               separate**, no degrade involved.
//   ExprAccept  ACCEPTED at both versions, and the exact MIRROR of TypeWrite: only the **v0 row
//               separates** — an implementation whose tuples hold 2..255 refuses the carrier and
//               has nothing to degrade behind, an over-reject of a box the JVM accepts. At **v3**
//               such an implementation degrades and echoes the very bytes the JVM echoes, so the
//               row is green either way and is a control. (Measured at fork `6f49c97c`: v0 red on
//               all three kinds, v3 green. I first wrote that both versions separate; they do
//               not, and the wire tier being degrade-blind is exactly why.)
//   Control     ACCEPTED, green everywhere by construction: the writable twin of a TypeWrite or
//               CountBound row, same shape, a tuple the JVM can write.
//
// Every expression body is written by the JVM's own `ValueSerializer`. The two TypeWrite bodies
// cannot be — `TypeSerializer` refuses to write them — so each is composed by writing its
// CONTROL twin through the AST and substituting the twin's constant, which leaves every byte but
// the tuple itself the JVM's own. `SmallTupleTreesTest` pins all of them against the measured hex.

import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.ast.syntax.SigmaPropValue
import sigma.serialization.{SigmaSerializer, ValueSerializer}

import RentFixtures._

object SmallTupleTrees {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3
  val V0: Byte = 0

  /** Which asymmetry a row exercises, and therefore which version of it can separate. */
  sealed trait Arm
  /** A 0/1-item tuple TYPE: rejected at both versions; only the v3 row separates. */
  case object TypeWrite extends Arm
  /** A script tuple of 128 items: rejected at both versions; both separate. */
  case object CountBound extends Arm
  /** A 0/1-item tuple EXPRESSION: accepted at both versions; both separate. */
  case object ExprAccept extends Arm
  /** A writable twin: accepted, green everywhere by construction. */
  case object Control extends Arm

  /** One row of the family. `body` is the version-free serialized proposition. */
  case class Case(slug: String, what: String, body: String, arm: Arm) {
    def rejects: Boolean = arm == TypeWrite || arm == CountBound
  }

  private def hx(bs: Array[Byte]): String = Base16.encode(bs)
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated, Activated)(f)

  private def bodyOf(prop: SigmaPropValue): String = ctx {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(prop, w); hx(w.toBytes)
  }
  private def constOf(c: Value[_ <: SType]): String = ctx {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(c, w); hx(w.toBytes)
  }

  /** The tree at version 0, UNSIZED — no degrade path behind it. */
  def v0(body: String): String = "00" + body
  /** The same body at version 3, SIZE-FLAGGED — where a failed parse DEGRADES instead. */
  def v3(body: String): String = {
    val header = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, Activated))
    hx(Array(header.toByte)) + hex(vlqU32((body.length / 2).toLong)) + body
  }

  // ── the constants the JVM will not write ─────────────────────────────────────────────────────
  /** `(Int)` holding 1: type code 0x60 (`STuple.TupleTypeCode` = (11+1)*8), length 01, item SInt,
    * then the data byte. Hand-written: `TypeSerializer` refuses fewer than two items. */
  val OneItemConst: String = "60" + "01" + "04" + "02"
  /** `()`: type code 0x60, length 00, and no data at all. */
  val ZeroItemConst: String = "60" + "00"

  private def pairValue: Constant[SType] =
    Constant[SType]((1, 2).asInstanceOf[SType#WrappedType], STuple(SInt, SInt))
  private def tripleValue: Constant[SType] =
    Constant[SType](sigma.Colls.fromItems[Any](1, 2, 3)(sigma.AnyType).asInstanceOf[SType#WrappedType],
      STuple(SInt, SInt, SInt))
  /** The `(Int,Int)` pair constant as the JVM writes it — `580204`: `PairSymmetricTypeCode` (84)
    * embedded with SInt's type code 4 = 0x58, then the two zigzag data bytes. */
  lazy val PairConst: String = constOf(pairValue)

  private def sub(in: String, from: String, to: String): String = {
    val i = in.indexOf(from)
    require(i >= 0 && in.indexOf(from, i + 1) < 0, s"'$from' must occur exactly once in $in")
    in.take(i) + to + in.drop(i + from.length)
  }

  // ── the propositions ─────────────────────────────────────────────────────────────────────────
  /** `sigmaProp(c._1 == 1)` over a tuple CONSTANT. */
  private def selectFirst(c: Constant[SType]): SigmaPropValue =
    BoolToSigmaProp(EQ(SelectField.typed[Value[SInt.type]](c.asInstanceOf[Value[STuple]], 1), IntConstant(1)))
  /** `sigmaProp(c == c)` over a tuple CONSTANT, the same constant written twice. */
  private def eqSelf(c: Constant[SType]): SigmaPropValue = BoolToSigmaProp(EQ(c, c))
  /** `sigmaProp(Tuple(1, .., 1)._1 == 1)` over a tuple EXPRESSION of `n` items. */
  private def selectFirstOfExpr(n: Int): SigmaPropValue =
    BoolToSigmaProp(EQ(SelectField.typed[Value[SInt.type]](
      Tuple(IndexedSeq.fill(n)(IntConstant(1): Value[SType])), 1), IntConstant(1)))
  /** `sigmaProp(Tuple() == Tuple())` — the 0-item expression, which has no field to select. */
  private def eqEmptyExpr: SigmaPropValue = {
    val t = Tuple(IndexedSeq.empty[Value[SType]])
    BoolToSigmaProp(EQ[SType](t, t))
  }

  // The TypeWrite bodies: the control twin written by the JVM, then the pair constant replaced.
  private lazy val selectFirstPairBody: String = bodyOf(selectFirst(pairValue))
  private lazy val eqSelfPairBody: String      = bodyOf(eqSelf(pairValue))

  val cases: Seq[Case] = Seq(
    Case("const_one_item_type",
      "sigmaProp(c._1 == 1) where c is a CONSTANT of type `(Int)` — a one-item tuple TYPE, which " +
      "the JVM parses and can never write back",
      sub(selectFirstPairBody, PairConst, OneItemConst), TypeWrite),
    Case("const_zero_item_type",
      "sigmaProp(c == c) where c is a CONSTANT of type `()` — a zero-item tuple TYPE, whose data " +
      "occupies no bytes at all",
      eqSelfPairBody.replace(PairConst, ZeroItemConst), TypeWrite),
    Case("tuple_expr_one_item",
      "sigmaProp(Tuple(1)._1 == 1) — a one-item tuple EXPRESSION. `TupleSerializer` writes a " +
      "count and no type, so this round-trips verbatim; `Tuple.eval` then refuses it",
      bodyOf(selectFirstOfExpr(1)), ExprAccept),
    Case("tuple_expr_zero_item",
      "sigmaProp(Tuple() == Tuple()) — a zero-item tuple EXPRESSION on both sides of an EQ",
      bodyOf(eqEmptyExpr), ExprAccept),
    Case("tuple_expr_127_items",
      "sigmaProp(Tuple(1 x 127)._1 == 1) — the LARGEST script tuple a signed count can express. " +
      "The byte is 0x7f; one more wraps negative",
      bodyOf(selectFirstOfExpr(127)), ExprAccept),
    Case("tuple_expr_128_items",
      "sigmaProp(Tuple(1 x 128)._1 == 1) — one past the boundary. The count byte 0x80 reads as " +
      "-128 and `safeNewArray` bounds only the upper end, so the parse throws " +
      "NegativeArraySizeException and takes the whole carrier with it",
      bodyOf(selectFirstOfExpr(128)), CountBound),
    Case("const_pair_type_control",
      "sigmaProp(c._1 == 1) where c is a `(Int,Int)` CONSTANT — the writable twin of " +
      "`const_one_item_type`, identical in shape",
      selectFirstPairBody, Control),
    Case("const_pair_eq_control",
      "sigmaProp(c == c) where c is a `(Int,Int)` CONSTANT — the writable twin of " +
      "`const_zero_item_type`",
      eqSelfPairBody, Control),
    Case("tuple_expr_two_items_control",
      "sigmaProp(Tuple(1, 1)._1 == 1) — the writable twin of the expression rows, and the one " +
      "count `Tuple.eval` accepts, so this is the only row of the family that also SPENDS",
      bodyOf(selectFirstOfExpr(2)), Control),
    Case("const_triple_type",
      "sigmaProp(c._1 == 1) where c is a `(Int,Int,Int)` CONSTANT — a THREE-item tuple type, " +
      "which `TypeSerializer` writes happily (`TripleTypeCode`), so the carrier round-trips. It " +
      "is here because its EVAL fails exactly as the one-item row's does: `toDslTuple` returns " +
      "the raw `Coll[Any]` for ANY non-pair, so the `SelectField` divergence is not a property of " +
      "small tuples at all — it is a property of non-pairs",
      bodyOf(selectFirst(tripleValue)), Control))

  def caseBySlug(slug: String): Case =
    cases.find(_.slug == slug).getOrElse(sys.error(s"no such case: $slug"))

  // ── the register arms ────────────────────────────────────────────────────────────────────────
  /** A four-byte `Coll[Byte]` register value (`0e020102`) used as a splice placeholder, so a
    * register the JVM cannot write can still be put into a box the JVM did write. */
  val RegisterPlaceholder: EvaluatedValue[_ <: SType] = ByteArrayConstant(Array[Byte](1, 2))
  lazy val RegisterPlaceholderHex: String = constOf(RegisterPlaceholder)

  /** R4 = `Tuple(Tuple(1), 1)`: a pair whose FIRST item is a one-item tuple. The JVM writes this
    * one — `TupleSerializer` emits `putUByte(length)` and no type — so the box and the
    * transaction are valid, and `SELF.R4[((Int),Int)].isDefined` answers true. */
  val NestedTupleRegister: EvaluatedValue[_ <: SType] =
    Tuple(IndexedSeq[Value[SType]](Tuple(IndexedSeq[Value[SType]](IntConstant(1))), IntConstant(1)))
      .asInstanceOf[EvaluatedValue[_ <: SType]]
  lazy val NestedTupleRegisterHex: String = constOf(NestedTupleRegister)

  /** `sigmaProp(true)` — every register row's carrier tree, so the only thing under test is the
    * register. Tree version 0. */
  val PlainTreeHex: String = "0008d3"
}
