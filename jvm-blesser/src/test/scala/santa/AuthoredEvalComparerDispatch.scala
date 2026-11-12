package santa

// Authored eval vectors for sigma-rust's santa-probe-comparer-dispatch (2026-11-12, its queue item 11). Measured
// first in santa.spike.ComparerDispatchProbe; the reply is prompts/drafts/sigma-rust-comparer-dispatch-reply.md.
//
// THE RULE, read in 6.0.7 and measured: `DataValueComparer.equalDataValues(l, r)` (DataValueComparer.scala:310-416)
// matches on the class of the LEFT value alone. Each case charges its own cost (raw JIT: a primitive `EQ_Prim` 3,
// a Coll / SigmaProp / String `MatchType` 1, a pair `EQ_Tuple` 4, GroupElement 172, BigInt / UnsignedBigInt 5,
// AvlTree / Header / Box 6, Option / PreHeader 4), and a right value of another class is then unequal. The
// primitive case is Scala's boxed `l == r`, so two integers compare by VALUE across widths (Long 1 equals Int 1),
// while a Boolean never equals a number and a BigInt never equals a Long. A left with no case (Context, Global)
// reaches `sys.error` and throws. The comparer has three callers: `EQ` (trees.scala:1209), `NEQ` (:1229) and
// `Coll.indexOf` (methods.scala:1091), where the left value is the collection's ITEM.
//
// How a value of another class reaches it: `indexOf`'s argument is never type-checked, and `EQ`/`NEQ` check each
// operand with `Value.checkType`, which looks at the ROOT class only, so an item of a pair or an option passes. A
// "lie" is `If(false, a, b)`: typed as `a`, valued as `b`.
//
// TWO FAMILIES, one per caller:
//  * `Coll.indexOf_comparer_dispatch`: the probe's A and D rows (A1/A2 left out: they are X1/X2 of
//    Coll.specialisation_blind_ops), plus O3/O3c, an Option ITEM (a `Coll[Option[Int]]` of `GetVar` nodes).
//  * `EQ.comparer_dispatch`: the probe's B, C, S and K rows (B1/B2 left out: they are W3/W5 of
//    EQ.cross_width_numeric), plus a Header, PreHeader, AvlTree and Option on the left of a pair item against a
//    Long right, each with its control and its mirror (Long left). Each `X1` and `X2` evaluate the same nodes and
//    differ only in the comparer's case, so X1 − X2 = k − 3; C1/C2, C3/C4 and S4/S5 are the same shape.
// The probe's rows are the sigma-rust session's own trees, verbatim; the extras are built here at the hex the
// spike measured. Every tree is v3, activated 3, and reads nothing from its input except where noted: var 1 is
// Int 0, or the mainnet v2 header for the H rows.

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalComparerDispatch {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source = "santa:authored-eval-comparer-dispatch"
  val Stamp = "jvm:sigma-state-6.0.7"
  val OpIndexOf = "Coll.indexOf_comparer_dispatch"
  val OpEq = "EQ.comparer_dispatch"

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  /** The mainnet v2 header SANTA's Header blessers bind (AuthoredHeaderProps, EvalCoreTest). */
  val MainnetHeaderHex =
    "02ac2101807f0000ca01ff0119db227f202201007f62000177a080005d440896d05d3f80dcff7f5e7f59007294c180808d0158d1ff6ba10000f901c7f0ef87dcfff17fffacb6ff7f7f1180d2ff7f1e24ffffe1ff937f807f0797b9ff6ebdae007e5c8c00b8403d3701557181c8df800001b6d5009e2201c6ff807d71808c00019780f087adb3fcdbc0b3441480887f80007f4b01cf7f013ff1ffff564a0000b9a54f00770e807f41ff88c00240000080c0250000000003bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c0000000000003105"
  val HeaderInput: Json =
    Json.obj("kind" -> Json.fromString("Header"), "bytes_hex" -> Json.fromString(MainnetHeaderHex))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  // ── AST helpers for the rows built here ───────────────────────────────────────────────────────────
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def cc(tpe: SType, items: Value[SType]*): Value[SType] = any(ConcreteCollection(items.toIndexedSeq, tpe))
  private def indexOf(recv: Value[SType], x: Value[SType]): Value[SType] = VersionContext.withVersions(V3, V3) {
    any(MethodCall(recv, SMethod.fromIds(12.toByte, 26.toByte), IndexedSeq(x, any(IntConstant(0))), Map()))
  }
  private def eq(l: Value[SType], r: Value[SType]): Value[SType] = any(EQ(l, r))
  private def lie(a: Value[SType], b: Value[SType]): Value[SType] = any(If(FalseLeaf, a, b))
  private def pair(a: Value[SType], b: Value[SType]): Value[SType] = any(Tuple(a, b))
  def tree(root: Value[SType]): String = VersionContext.withVersions(V3, V3) {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(root, w)
    val body = hx(w.toBytes); "0b" + vlq(body.length / 2) + body
  }
  private val I2 = any(IntConstant(2))
  private val L1 = any(LongConstant(1L))
  private val H  = any(OptionGet(GetVar(1.toByte, SHeader)))
  private val PH = VersionContext.withVersions(V3, V3) {
    any(MethodCall(Context, SContextMethods.getMethodByName("preHeader"), IndexedSeq(), Map()))
  }
  private val AV = any(LastBlockUtxoRootHash)
  private val OI = any(GetVar(1.toByte, SInt))   // Option[Int]: Some(0) under the Int binding

  /** One row. `src` is the probe's hex (Left) or an AST built here (Right). `accept` rows return. */
  case class Row(op: String, id: String, script: String, src: Either[String, () => Value[SType]],
                 accept: Boolean, slug: String, input: Json = IntInput) {
    def treeHex: String = src.fold(identity, ast => tree(ast()))
  }

  private val longDispatch = "SEPARATING on cost: a comparison dispatched on the Long instead costs"

  /** The extras' three rows for one left kind: the lie (v left, Long right), its control, the mirror (Long left). */
  private def kindRows(tag: String, label: String, v: Value[SType], kase: String, k: Int,
                       costs: (Int, Int, Int), kind: String, in: Json, note: String = ""): Seq[Row] = {
    val (c1, c1c, c2) = costs
    Seq(
      Row(OpEq, s"${tag}1", s"`($label, 2) == lie(($label,2), (1L,2))`$note — false at cost $c1: a $kind left charges " +
        s"`$kase` $k, and a Long is not a $kind, so the pair is unequal. $longDispatch $c2 (${tag}2).",
        Right(() => eq(pair(v, I2), lie(pair(v, I2), pair(L1, I2)))), accept = true,
        s"${kind.toLowerCase}-left-long-right-accept", in),
      Row(OpEq, s"${tag}1c", s"`($label, 2) == ($label, 2)` — true at cost $c1c. CONTROL for ${tag}1.",
        Right(() => eq(pair(v, I2), pair(v, I2))), accept = true, s"${kind.toLowerCase}-pair-control-accept", in),
      Row(OpEq, s"${tag}2", s"`lie(($label,2), (1L,2)) == ($label, 2)` — false at cost $c2: the MIRROR of ${tag}1, " +
        s"a Long left (`EQ_Prim` 3) against a $kind right. The two evaluate the same nodes, so ${tag}1 − ${tag}2 = " +
        s"$k − 3.",
        Right(() => eq(lie(pair(v, I2), pair(L1, I2)), pair(v, I2))), accept = true,
        s"long-left-${kind.toLowerCase}-right-accept", in))
  }

  val Rows: Seq[Row] = Seq(
    // ── Coll.indexOf: the left value is the ITEM ──
    Row(OpIndexOf, "A3", "`Coll(1L).indexOf(lie(1L, 1), 0)` — 0 at cost 87: `indexOf` compares each ITEM, the " +
      "comparer's LEFT value, with the argument (methods.scala:1091) and never type-checks the argument. A Long item " +
      "takes the primitive case (`EQ_Prim` 3, Scala's boxed `l == r`), where two integers compare by VALUE across " +
      "widths, so Long 1 equals Int 1. SEPARATING: a width-aware comparison answers -1.",
      Left("0b12dc0c1a830105050202950100050204020400"), accept = true, "long-item-int-arg-accept"),
    Row(OpIndexOf, "A3c", "`Coll(1L).indexOf(1L, 0)` — 0 at cost 72. CONTROL for A3.",
      Left("0b0ddc0c1a83010505020205020400"), accept = true, "long-item-long-arg-control-accept"),
    Row(OpIndexOf, "A4", "`Coll[Byte] constant (1).indexOf(lie(1.toByte, 1L), 0)` — 0 at cost 67: a Byte item " +
      "against a Long argument, equal by value as in A3, on a specialised Coll[Byte] receiver. SEPARATING, as A3.",
      Left("0b10dc0c1a0e010102950100020105020400"), accept = true, "byte-item-long-arg-accept"),
    Row(OpIndexOf, "D1", "`Coll[Box](SELF).indexOf(lie(SELF, 1L), 0)` — -1 at cost 95: a Box item takes the Box " +
      s"case (`EQ_Box` 6), and a Long right is unequal. $longDispatch 92.",
      Left("0b10dc0c1a830163a702950100a705020400"), accept = true, "box-item-long-arg-accept"),
    Row(OpIndexOf, "D1c", "`Coll[Box](SELF).indexOf(SELF, 0)` — 0 at cost 85. CONTROL for D1.",
      Left("0b0bdc0c1a830163a702a70400"), accept = true, "box-item-box-arg-control-accept"),
    Row(OpIndexOf, "D2", "`Coll[BigInt](1).indexOf(lie(BigInt(1), 1L), 0)` — -1 at cost 89: a BigInt item takes the " +
      "BigInt case (`EQ_BigInt` 5), and `CBigInt(1) == 1L` is false: a BigInt is not a `java.lang.Number`, so the " +
      s"primitive case's cross-width equality never reaches it. $longDispatch 87; a comparer that equates BigInt 1 " +
      "with Long 1 answers 0.",
      Left("0b14dc0c1a8301060601010295010006010105020400"), accept = true, "bigint-item-long-arg-accept"),
    Row(OpIndexOf, "D2c", "`Coll[BigInt](1).indexOf(BigInt(1), 0)` — 0 at cost 74. CONTROL for D2.",
      Left("0b0fdc0c1a830106060101020601010400"), accept = true, "bigint-item-bigint-arg-control-accept"),
    Row(OpIndexOf, "D3", "`Coll[Coll[Int]](Coll(1)).indexOf(lie(Coll(1), 1L), 0)` — -1 at cost 105: a Coll item " +
      s"charges `MatchType` 1, then finds the right value is not a Coll. $longDispatch 107.",
      Left("0b18dc0c1a830110830104040202950100830104040205020400"), accept = true, "coll-item-long-arg-accept"),
    Row(OpIndexOf, "D3c", "`Coll[Coll[Int]](Coll(1)).indexOf(Coll(1), 0)` — 0 at cost 127. CONTROL for D3: equal " +
      "collections go on to the length, the item type and the per-class comparison.",
      Left("0b13dc0c1a83011083010404020283010404020400"), accept = true, "coll-item-coll-arg-control-accept"),
    Row(OpIndexOf, "D4", "`Coll((1,2)).indexOf(lie((1,2), 1L), 0)` — -1 at cost 108: a pair item charges `EQ_Tuple` " +
      s"4, and a Long is not a pair. $longDispatch 107.",
      Left("0b1adc0c1a8301588602040204040295010086020402040405020400"), accept = true, "pair-item-long-arg-accept"),
    Row(OpIndexOf, "D4c", "`Coll((1,2)).indexOf((1,2), 0)` — 0 at cost 119. CONTROL for D4.",
      Left("0b15dc0c1a830158860204020404028602040204040400"), accept = true, "pair-item-pair-arg-control-accept"),
    Row(OpIndexOf, "D5", "`Coll[GroupElement](g).indexOf(lie(g, 1L), 0)` — -1 at cost 261: a GroupElement item " +
      s"charges `EQ_GroupElement` 172 whatever the right value is. $longDispatch 92.",
      Left("0b10dc0c1a83010782029501008205020400"), accept = true, "groupelement-item-long-arg-accept"),
    Row(OpIndexOf, "D5c", "`Coll[GroupElement](g).indexOf(g, 0)` — 0 at cost 251. CONTROL for D5.",
      Left("0b0bdc0c1a8301078202820400"), accept = true, "groupelement-item-groupelement-arg-control-accept"),
    Row(OpIndexOf, "D6", "`Coll(1).indexOf(lie(1, g), 0)` — -1 at cost 92: the MIRROR of D5, an Int item against a " +
      "GroupElement argument: the primitive case charges 3, and `1 == g` is false. SEPARATING on cost: a comparison " +
      "dispatched on the GroupElement argument charges 172 and costs 261.",
      Left("0b11dc0c1a8301040402029501000402820400"), accept = true, "int-item-groupelement-arg-accept"),
    Row(OpIndexOf, "D7", "`Coll(true).indexOf(lie(true, 1), 0)` — -1 at cost 87: a Boolean item takes the " +
      "primitive case, and Scala's boxed `==` never equates a Boolean with a number. SEPARATING: a comparer that " +
      "reads `true` as 1 answers 0.",
      Left("0b12dc0c1a830101010102950100010104020400"), accept = true, "boolean-item-int-arg-accept"),
    Row(OpIndexOf, "D8", "`Coll(1).indexOf(lie(1, true), 0)` — -1 at cost 87: the mirror of D7, an Int item against " +
      "a Boolean argument. SEPARATING, as D7.",
      Left("0b12dc0c1a830104040202950100040201010400"), accept = true, "int-item-boolean-arg-accept"),
    Row(OpIndexOf, "O3", "`Coll(getVar[Int](1)).indexOf(lie(getVar[Int](1), 1L), 0)`, var 1 = Int 0 — -1 at cost 93: " +
      "the item is `Some(0)`, an Option left (`EQ_Option` 4), and a Long is not an Option. The receiver is a " +
      s"`ConcreteCollection` of `GetVar` nodes, item type `Option[Int]`. $longDispatch 92.",
      Right(() => indexOf(cc(SOption(SInt), OI), lie(OI, L1))), accept = true, "option-item-long-arg-accept"),
    Row(OpIndexOf, "O3c", "`Coll(getVar[Int](1)).indexOf(getVar[Int](1), 0)` — 0 at cost 86. CONTROL for O3.",
      Right(() => indexOf(cc(SOption(SInt), OI), OI)), accept = true, "option-item-option-arg-control-accept"),

    // ── EQ / NEQ: a value of another class inside a pair or an option ──
    Row(OpEq, "B3", "`lie((1,2), (1L,2)) == (1,2)` — true at cost 75: `EQ` checks each operand's ROOT class only " +
      "(`Value.checkType`; a pair passes), so the lie reaches the comparer, which descends the pair (`EQ_Tuple` 4) and " +
      "compares Long 1 with Int 1 by VALUE (`EQ_Prim` 3). SEPARATING: a width-aware comparison answers false at 72.",
      Left("0b1693950100860204020404860205020404860204020404"), accept = true, "pair-long-item-left-accept"),
    Row(OpEq, "B3c", "`(1,2) == (1,2)` — true at cost 60. CONTROL for B3.",
      Left("0b0d93860204020404860204020404"), accept = true, "pair-control-accept"),
    Row(OpEq, "B4", "`(1,2) == lie((1,2), (1L,2))` — true at cost 75: the lie on the RIGHT; an Int 1 left equals " +
      "Long 1 the same way. SEPARATING, as B3.",
      Left("0b1693860204020404950100860204020404860205020404"), accept = true, "pair-long-item-right-accept"),
    Row(OpEq, "B5", "`lie((1,2), (1L,2)) != (1,2)` — false at cost 75: `NEQ` calls the same comparer " +
      "(trees.scala:1229). SEPARATING, as B3.",
      Left("0b1694950100860204020404860205020404860204020404"), accept = true, "neq-pair-long-item-left-accept"),
    Row(OpEq, "B6", "`lie((1,2), (2L,2)) == (1,2)` — false at cost 72: the lie's value is not numerically equal, so " +
      "every rule answers false, and the first item's false short-circuits the second (75 − 3). CONTROL for B3: the " +
      "cost of an unequal first item.",
      Left("0b1693950100860204020404860205040404860204020404"), accept = true, "pair-unequal-long-item-accept"),
    Row(OpEq, "B7", "`lie((1.toByte,2), (1L,2)) == (1.toByte,2)` — true at cost 75: Long 1 equals Byte 1 by value. " +
      "SEPARATING, as B3.",
      Left("0b1693950100860202010404860205020404860202010404"), accept = true, "pair-long-vs-byte-item-accept"),
    Row(OpEq, "B8", "`lie((true,2), (1,2)) == (true,2)` — false at cost 72: an Int left against a Boolean right, which " +
      "the boxed `==` never equates. SEPARATING: a comparer that reads `true` as 1 answers true.",
      Left("0b1693950100860201010404860204020404860201010404"), accept = true, "pair-int-vs-boolean-item-accept"),
    Row(OpEq, "B9", "`lie(Coll(1).get(0), Coll(1L).get(0)) == Coll(1).get(0)` — true at cost 150: an Option passes the " +
      "operand check by its root class; an Option left (`EQ_Option` 4) descends to its contents, `Some(1L)` against " +
      "`Some(1)`, and compares them by value. SEPARATING, as B3.",
      Left("0b2593950100dc0c218301040402010400dc0c218301050502010400dc0c218301040402010400"), accept = true,
      "option-long-content-left-accept"),
    Row(OpEq, "B9c", "`Coll(1).get(0) == Coll(1).get(0)` — true at cost 135. CONTROL for B9.",
      Left("0b1793dc0c218301040402010400dc0c218301040402010400"), accept = true, "option-control-accept"),
    Row(OpEq, "C1", "`(BigInt(1), 2) == lie((BigInt(1),2), (1L,2))` — false at cost 74: a BigInt left charges " +
      "`EQ_BigInt` 5, and `CBigInt(1) == 1L` is false: a BigInt is not a `java.lang.Number`, so the primitive case's " +
      s"cross-width equality never reaches it. $longDispatch 72; a comparer that equates BigInt 1 with Long 1 " +
      "answers true.",
      Left("0b18938602060101040495010086020601010404860205020404"), accept = true, "bigint-left-long-right-accept"),
    Row(OpEq, "C1c", "`(BigInt(1), 2) == (BigInt(1), 2)` — true at cost 62. CONTROL for C1.",
      Left("0b0f938602060101040486020601010404"), accept = true, "bigint-pair-control-accept"),
    Row(OpEq, "C2", "`lie((BigInt(1),2), (1L,2)) == (BigInt(1), 2)` — false at cost 72: the MIRROR of C1, a Long left " +
      "(`EQ_Prim` 3) against a BigInt right, unequal from this side too. C1 − C2 = 5 − 3. SEPARATING on value, as C1.",
      Left("0b18939501008602060101040486020502040486020601010404"), accept = true, "long-left-bigint-right-accept"),
    Row(OpEq, "C3", "`(g, 2) == lie((g,2), (1L,2))` — false at cost 246: a GroupElement left charges " +
      s"`EQ_GroupElement` 172 whatever the right value is. $longDispatch 77 (C4).",
      Left("0b149386028204049501008602820404860205020404"), accept = true, "groupelement-left-long-right-accept"),
    Row(OpEq, "C4", "`lie((g,2), (1L,2)) == (g, 2)` — false at cost 77: the MIRROR of C3, a Long left. C3 − C4 = " +
      "172 − 3.",
      Left("0b149395010086028204048602050204048602820404"), accept = true, "long-left-groupelement-right-accept")) ++
    kindRows("H", "h", H, "EQ_Header", 6, (95, 103, 92), "Header", HeaderInput,
      ", h = `getVar[Header](1).get`, var 1 a mainnet v2 header") ++
    kindRows("P", "CONTEXT.preHeader", PH, "EQ_PreHeader", 4, (88, 91, 87), "PreHeader", IntInput) ++
    kindRows("V", "LastBlockUtxoRootHash", AV, "EQ_AvlTree", 6, (85, 83, 82), "AvlTree", IntInput) ++
    kindRows("O", "getVar[Int](1)", OI, "EQ_Option", 4, (78, 74, 77), "Option", IntInput,
      ", var 1 = Int 0 so the left is `Some(0)`") ++ Seq(
    Row(OpEq, "S0", "`\"a\" == \"a\"`, two String constants — THROWS `RuntimeException: Unknown type SString` at " +
      "`EQ`'s operand check, before the comparer: `SType.isValueOfType` has no String case. A different exception " +
      "from the `InterpreterException` of a lie at the root (EQ.cross_width_numeric W3), though the eval tier grades " +
      "both as errored; a String reaches the comparer only inside a pair (S1-S5). SEPARATING: an operand check with " +
      "a String case answers true.",
      Left("0b0793660161660161"), accept = false, "bare-string-operands-errored"),
    Row(OpEq, "S1", "`(\"a\",1) == (\"a\",1)`, two `(String, Int)` pair constants — true at cost 35: a String left " +
      "charges `MatchType` 1 and, the lengths being equal, `EQ_COA_Short` over the length (15 + 2 per 96-char chunk: " +
      "17), then the Int item 3. Pins the String case's cost.",
      Left("0b0b934c660161024c66016102"), accept = true, "string-pair-equal-accept"),
    Row(OpEq, "S2", "`(\"a\",1) == (\"b\",1)` — false at cost 32: the same charges, and the String's false " +
      "short-circuits the Int (35 − 3).",
      Left("0b0b934c660161024c66016202"), accept = true, "string-pair-unequal-accept"),
    Row(OpEq, "S3", "`(\"a\",1) == (\"ab\",1)` — false at cost 15: the lengths differ, so the String case returns " +
      "before charging `EQ_COA_Short` (32 − 17); only `MatchType` is paid.",
      Left("0b0c934c660161024c6602616202"), accept = true, "string-pair-length-bail-accept"),
    Row(OpEq, "S4", "`(\"a\",1) == lie((\"a\",1), (1L,1))` — false at cost 50: a String left charges only `MatchType` " +
      s"1 against a Long right. $longDispatch 52 (S5).",
      Left("0b14934c660161029501004c66016102860205020402"), accept = true, "string-left-long-right-accept"),
    Row(OpEq, "S5", "`lie((\"a\",1), (1L,1)) == (\"a\",1)` — false at cost 52: the MIRROR of S4, a Long left. " +
      "S4 − S5 = 1 − 3.",
      Left("0b14939501004c660161028602050204024c66016102"), accept = true, "long-left-string-right-accept"),
    Row(OpEq, "K1", "`CONTEXT == CONTEXT` — THROWS `RuntimeException: Cannot compare … unknown type`: `EQ`'s operand " +
      "check passes a Context, and the comparer has no case for it, so its default `sys.error` throws " +
      "(DataValueComparer.scala:413). SEPARATING: an implementation that compares a Context answers true.",
      Left("0b0393fefe"), accept = false, "context-operands-errored"),
    Row(OpEq, "K2", "`Global == Global` — THROWS the same, the comparer's default case on a `SigmaDslBuilder`. " +
      "SEPARATING, as K1.",
      Left("0b0393dddd"), accept = false, "global-operands-errored"),
    Row(OpEq, "K3", "`(CONTEXT, 1) == (CONTEXT, 1)` — THROWS the same, reached through the pair case's first item. " +
      "SEPARATING, as K1.",
      Left("0b0b938602fe04028602fe0402"), accept = false, "context-pair-item-errored"),
    Row(OpEq, "K4", "`(Global, 1) == (Global, 1)` — THROWS the same, through the pair case. SEPARATING, as K1.",
      Left("0b0b938602dd04028602dd0402"), accept = false, "global-pair-item-errored"),
    Row(OpEq, "K5", "`CONTEXT != CONTEXT` — THROWS the same: `NEQ` calls the same comparer (trees.scala:1229). " +
      "SEPARATING, as K1.",
      Left("0b0394fefe"), accept = false, "neq-context-operands-errored"))

  def extract(): Map[String, Json] =
    Seq(OpIndexOf, OpEq).map { op =>
      val entries = Rows.filter(_.op == op).zipWithIndex.map { case (r, i) =>
        val name = s"${r.slug}#$i"
        if (r.accept) SpecExtract.authoredEntryV(op, s"[${r.id}] ${r.script}", r.treeHex, name, r.input, V3, ergoTree = 3)
        else SpecExtract.authoredRejectEntryV(op, s"[${r.id}] ${r.script}", r.treeHex, name, r.input, V3, ergoTree = 3)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalComparerDispatch", extract(), outDir)
}
