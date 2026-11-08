package santa

// The thirteen node kinds `ValueSerializer.serializers` registers a reader for but which define
// no `eval`, so the PARSE accepts them and evaluating one throws
// (`values.scala:101-102`, "Should be overriden in class ..."). Shared by the eval blesser
// (`AuthoredUnevaluatedNodeKinds`) and the wire one (`AuthoredWireUnevaluatedNodeKinds`), so both
// tiers grade the same bytes.
//
// Measured on the JVM at 6.0.7, activated 3, by `santa.spike.UnevaluatedNodesProbe`; the request
// is `prompts/santa-probe-unevaluated-nodes.md` (sigma-rust session, 2026-11-08).
//
// Trees are composed HEADER + BODY, the body written by `ValueSerializer` under a (3,3) context:
//   - `ErgoTree.fromProposition` SEGREGATES constants and these trees carry them inline;
//   - `ErgoTree`'s serializer re-enters the version context at the TREE's version
//     (`ErgoTreeSerializer.scala:111`), and at tree version 0 rule 1009
//     (`CheckSerializableTypeCode`) refuses the `None: Option[Int]` data value that
//     `CreateAvlTree`'s fourth field is — type code 36. So the JVM cannot WRITE that one tree at
//     v0, and composing the bytes is the only way to ask the parser about it. It then REJECTS it:
//     the rule-1009 ValidationException with no size bit to degrade behind
//     (`v0Representable = false`, and the reject is vectored as such).

import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.ast.syntax.SigmaPropValue
import sigma.data.AvlTreeFlags
import sigma.serialization.{SigmaSerializer, ValueSerializer}
import sigma.serialization.ValueCodes.OpCode

import RentFixtures._

object UnevaluatedNodeTrees {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3

  /** One node kind: `slug` names the entry, `what` opens its description, `body` is the
    * version-free serialized proposition. `v0Representable` is false for the one tree the JVM
    * refuses to write (and to read) at tree version 0. */
  case class Node(slug: String, what: String, body: String, v0Representable: Boolean = true)

  private def hx(bs: Array[Byte]): String = Base16.encode(bs)
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated, Activated)(f)

  private def bodyOf(prop: SigmaPropValue): String = ctx {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(prop, w); hx(w.toBytes)
  }

  /** The tree at version 0, UNSIZED — the form that separates: a missing reader has no degrade
    * path to hide behind. */
  def v0(body: String): String = "00" + body
  /** The same body at version 3, SIZE-FLAGGED — where a missing reader DEGRADES instead. */
  def v3(body: String): String = {
    val header = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, Activated))
    hx(Array(header.toByte)) + hex(vlqU32((body.length / 2).toLong)) + body
  }

  // ── the propositions ─────────────────────────────────────────────────────────────────────────
  private def shift(op: OpCode): SigmaPropValue =
    BoolToSigmaProp(EQ(BitOp[SInt.type](IntConstant(1), IntConstant(2), op), IntConstant(4)))
  private def bitop(op: OpCode): SigmaPropValue =
    BoolToSigmaProp(EQ(BitOp[SInt.type](IntConstant(5), IntConstant(3), op), IntConstant(1)))
  private def modQArith(op: OpCode): SigmaPropValue =
    BoolToSigmaProp(EQ(ModQArithOp(BigIntConstant(1), BigIntConstant(1), op), BigIntConstant(1)))

  private val digest33: Array[Byte] = Array.fill[Byte](33)(0)
  /** The fourth field is a `None: Option[Int]` CONSTANT, which is what makes the four-value
    * layout unwritable at tree version 0 (rule 1009). */
  private def createAvlTree(valueLen: Value[SOption[SInt.type]]): Value[SAvlTree.type] =
    CreateAvlTree(ByteConstant(AvlTreeFlags.AllOperationsAllowed.serializeToByte),
      ByteArrayConstant(digest33), IntConstant(32), valueLen)
  private def noneConstant: Value[SOption[SInt.type]] =
    ConstantNode[SOption[SInt.type]](None, SOption(SInt))
  /** The fourth argument as an EXPRESSION rather than a constant: `getVar[Int](1)`, `e3 01 04`.
    * Rule 1009 guards DATA values, so an expression of the same type rides at any tree version —
    * which is what makes the four-value layout representable at v0 at all (the sigma-rust
    * session's own correction, 2026-11-08). The eval tier binds its `input` at ContextExtension
    * var 1, so the variable even resolves; `CreateAvlTree` still has no `eval`. */
  private def getVarInt: Value[SOption[SInt.type]] = GetVar(1.toByte, SOption(SInt))

  val nodes: Seq[Node] = Seq(
    Node("bit_shift_left", "sigmaProp(BitShiftLeft(1, 2) == 4) — `BitOp` with the `<<` opcode",
      bodyOf(shift(BitOp.BitShiftLeft.opCode))),
    Node("bit_shift_right", "sigmaProp(BitShiftRight(1, 2) == 4) — `BitOp` with the `>>` opcode",
      bodyOf(shift(BitOp.BitShiftRight.opCode))),
    Node("bit_shift_right_zeroed", "sigmaProp(BitShiftRightZeroed(1, 2) == 4) — `BitOp` with `>>>`",
      bodyOf(shift(BitOp.BitShiftRightZeroed.opCode))),
    Node("mod_q", "sigmaProp(ModQ(BigInt 1) == BigInt 1)", bodyOf(BoolToSigmaProp(
      EQ(ModQ(BigIntConstant(1)), BigIntConstant(1))))),
    Node("plus_mod_q", "sigmaProp(PlusModQ(BigInt 1, BigInt 1) == BigInt 1)",
      bodyOf(modQArith(ModQArithOp.PlusModQ.opCode))),
    Node("minus_mod_q", "sigmaProp(MinusModQ(BigInt 1, BigInt 1) == BigInt 1)",
      bodyOf(modQArith(ModQArithOp.MinusModQ.opCode))),
    Node("sigma_prop_is_proven", "sigmaProp(SigmaPropIsProven(sigmaProp(true))) — the one node of " +
      "the thirteen with a build check that reads a child's TYPE (`val opType`, " +
      "`transformers.scala:321-325`)",
      bodyOf(BoolToSigmaProp(SigmaPropIsProven(BoolToSigmaProp(TrueLeaf))))),
    // `TaggedVariable.apply` is @deprecated since 7.0.0 — "Use ValUse / GetVar; TaggedVariable is
    // scheduled for retirement in v7.0" — which is the one of the thirteen with an announced end.
    // It still parses and still has no `eval`, so it stays in the family; the deprecation warning
    // this line raises is expected and is itself the fact.
    Node("tagged_variable", "sigmaProp(TaggedVariable(1, Boolean)) — `TaggedVariable.apply` is " +
      "deprecated upstream since 7.0.0 (\"Use ValUse / GetVar; scheduled for retirement in " +
      "v7.0\"), so this kind has an announced end; it parses and has no `eval` today",
      bodyOf(BoolToSigmaProp(TaggedVariable(1.toByte, SBoolean)))),
    Node("create_avl_tree_four_value", "sigmaProp(CreateAvlTree(flags, digest33, 32, None) == the " +
      "same) — the FOUR-VALUE layout `CreateAvlTreeSerializer` writes, twice under an EQ so the " +
      "layout appears on both sides",
      bodyOf(BoolToSigmaProp(EQ(createAvlTree(noneConstant), createAvlTree(noneConstant)))),
      v0Representable = false),
    Node("bit_or", "sigmaProp(BitOr(5, 3) == 1)", bodyOf(bitop(BitOp.BitOr.opCode))),
    Node("bit_and", "sigmaProp(BitAnd(5, 3) == 1)", bodyOf(bitop(BitOp.BitAnd.opCode))),
    Node("bit_xor", "sigmaProp(BitXor(5, 3) == 1)", bodyOf(bitop(BitOp.BitXor.opCode))),
    Node("bit_inversion", "sigmaProp(BitInversion(5) == -6) — the only one of the thirteen whose " +
      "`costKind` is `notSupportedError` as well",
      bodyOf(BoolToSigmaProp(EQ(BitInversion[SInt.type](IntConstant(5)), IntConstant(-6))))))

  /** Second ENCODINGS of a kind already in `nodes`, not new kinds — so `nodes` stays the thirteen
    * the question is about. This one exists because the four-value `CreateAvlTree` layout is
    * otherwise unreachable at tree version 0. */
  val extraEncodings: Seq[Node] = Seq(
    Node("create_avl_tree_four_value_getvar", "sigmaProp(CreateAvlTree(flags, digest33, 32, " +
      "getVar[Int](1)) == the same) — the FOUR-VALUE layout at tree version 0, with the fourth " +
      "argument as an expression (`e30104`) instead of a `None` constant. Rule 1009 guards data " +
      "values, not expressions, so this is the form that reaches a v0 parser at all — the only " +
      "way to ask a reader whether it takes four values or three plus an option tag",
      bodyOf(BoolToSigmaProp(EQ(createAvlTree(getVarInt), createAvlTree(getVarInt))))))

  def nodeBySlug(slug: String): Node =
    (nodes ++ extraEncodings).find(_.slug == slug)
      .getOrElse(sys.error(s"no such node kind: $slug"))

  // ── the reject arms ──────────────────────────────────────────────────────────────────────────
  /** The fourth value as the JVM writes it: `28 00` — `SOption(SInt)`, None. */
  val NoneOptionHex: String = "2800"

  /** The fork's OLD `CreateAvlTree` layout: three values and then a bare `00`, read as a type
    * prefix 0 (`TypeSerializer.scala:138`). */
  val oldLayoutBody: String =
    nodeBySlug("create_avl_tree_four_value").body.replace(NoneOptionHex, "00")

  /** `BitShiftLeft(true, 2)`: `BitOp`'s `require(left.tpe.isNumTypeOrNoType && …)`
    * (`trees.scala:916`). Authored by splicing a `BooleanConstant(true)` (`0101`) over the left
    * operand's `IntConstant(1)` (`0402`), since the node cannot be BUILT at all. */
  val boolOperandBody: String = {
    val body = nodeBySlug("bit_shift_left").body
    val i = body.indexOf("0402")
    require(i >= 0, s"the left operand 0402 must appear in $body")
    body.take(i) + "0101" + body.drop(i + 4)
  }

  /** `sigmaProp(SigmaPropIsProven(Filter(1, (x: Int) => true)))` — the probe's own encoding. The
    * build check reads the input's type and `Filter.tpe` casts SInt to SCollection. */
  val spipFilterTree: String = "00d1cfb50402d90101040101"
}
