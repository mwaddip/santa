package santa

// Authored eval vectors for the argument-kind round (fork PR 88; sigma-rust's santa-probe-argument-kind, 2026-11-13,
// queue item 15). Measured by SANTA then (spike santa.spike.ArgumentKindProbe; reply
// prompts/drafts/sigma-rust-argument-kind-reply.md) and vectored now under the backlog's standing rule (2026-11-14).
// The AvlTree digest rows of that round (D/E) are in AvlTree.digest_any_collection; the spends in
// AuthoredTxArgumentWidening.
//
// THE RULE, read in 6.0.7: a fixed-cost method is invoked by Java reflection (`invokeFixed`, values.scala:1361-1380),
// and the `_eval` methods through `evalMethod.invoke` (:1391); a handler (`userDefinedInvoke`) takes its arguments
// as they are. Java reflection WIDENS a primitive argument to the declared parameter type (Byte → Short → Int →
// Long), and never narrows. So a Byte or a Short where an Int is declared reaches the method as the Int it is,
// through either reflective path; a Long where an Int is declared, or any non-primitive, is refused
// (`IllegalArgumentException`); and a handler that casts its argument refuses a Byte where it expects an Int
// (`ClassCastException`: the X rows).
//
// HOW A ROW GETS SUCH AN ARGUMENT. `lie(a, b)` is `if (false) a else b`, typed as `a` and valued as `b`: the call is
// specialised to `a`'s type and receives `b`. A Byte/Short CONSTANT argument (no lie) leaves a GENERIC method
// unspecialised, so the call's static type is the table's variable `IV`: it still evaluates, but a consumer that
// type-checks its operand refuses it (Q1 at parse, Q3 at eval), while `sigmaProp(...)` does not check (Q2).
//
// TWO FAMILIES:
//  * MethodCall.argument_widening_v6 (activated 3, tree v3): the round's W/X rows (W10 with CONTEXT written `fe`;
//    the round's own trees wrote `8e`, a generator slip that fails to parse), the Q rows, the kinds that cannot
//    stand for an Int (each refused), `updateDigest`'s Coll[Byte] parameter (any collection passes, a non-collection
//    is refused), and the receivers (a constant, a pair collection, a tuple constant, a ConcreteCollection).
//  * MethodCall.argument_widening (activated 2, tree v0): the rows that use only v5 methods.

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalArgumentWidening {
  val V3: Byte = 3
  val V2: Byte = 2
  val Source = "santa:authored-argument-widening"
  val Stamp = "jvm:sigma-state-6.0.7"
  val Op6 = "MethodCall.argument_widening_v6"
  val Op5 = "MethodCall.argument_widening"
  val Ops: Seq[String] = Seq(Op6, Op5)

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  // ── AST builders ────────────────────────────────────────────────────────────────────────────────────
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def cc(tpe: SType, items: Value[SType]*): Value[SType] = any(ConcreteCollection(items.toIndexedSeq, tpe))
  private def mcall(typeId: Int, methodId: Int, recv: Value[SType], args: Value[SType]*): Value[SType] =
    VersionContext.withVersions(V3, V3) {
      any(MethodCall(recv, SMethod.fromIds(typeId.toByte, methodId.toByte), args.toIndexedSeq, Map()))
    }
  private def lie(a: Value[SType], b: Value[SType]): Value[SType] = any(If(FalseLeaf, a, b))
  /** The expression body (no tree header), written at tree v3. */
  def body(root: Value[SType]): String = VersionContext.withVersions(V3, V3) {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(root, w); hx(w.toBytes)
  }
  private val I0 = any(IntConstant(0)); private val I1 = any(IntConstant(1)); private val I7 = any(IntConstant(7))
  private val B1 = any(ByteConstant(1)); private val B0 = any(ByteConstant(0))
  private val G = any(GroupGenerator); private val S = any(Self)
  private val PH = mcall(101, 3, any(Context))
  private val Str = any(Constant[SType]("a".asInstanceOf[SType#WrappedType], SString))
  private val c = any(IntArrayConstant(Array(0, 1, 2)))
  private def apply(recv: Value[SType], i: Value[SType]) = mcall(12, 10, recv, i)
  /** The round's AvlTree constant and 33-byte digest (from its D1c tree); the (1,2,3) tuple constant of R4t. */
  private val AvlHex = "64000000000000000000000000000000000000000000000000000000000000000000072000"
  private val Bytes33 = "0e21" + "01" * 33
  private def parseBody(h: String): Value[SType] = VersionContext.withVersions(V3, V3)(
    ValueSerializer.deserialize(Base16.decode(h).get).asInstanceOf[Value[SType]])
  private lazy val Avl = parseBody(AvlHex)
  private lazy val D33 = parseBody(Bytes33)
  private lazy val Tup = parseBody("48040404020406")
  private def keyLengthOf(arg: Value[SType]) = mcall(100, 3, mcall(100, 15, Avl, arg))

  /** One row: an expression body; `v5` whether it also goes into the v5 family (tree v0, activated 2). */
  case class Row(id: String, script: String, body: String, accept: Boolean, v5: Boolean)

  private def strip(tree: String): String = { // a sized v3 tree's body
    require(tree.startsWith("0b"), tree)
    val r = SigmaSerializer.startReader(Base16.decode(tree.drop(2)).get); val n = r.getUInt().toInt
    val b = tree.drop(2).drop((r.position) * 2); require(b.length == n * 2, tree); b
  }

  private val Widen = "WIDENS: Java reflection converts the primitive argument to the declared parameter type."
  private def w(id: String, s: String, tree: String, v5: Boolean, why: String = Widen) =
    Row(id, s"`$s`. $why", strip(tree), accept = true, v5)
  private def ctl(id: String, s: String, tree: String, v5: Boolean) =
    Row(id, s"`$s`. CONTROL: the declared type.", strip(tree), accept = true, v5)

  val Rows: Seq[Row] = Seq(
    ctl("W1c", "c.apply(1)", "0b0bdc0c0a1003000204010402", v5 = true),
    w("W1", "c.apply(lie(1, 1.toByte))", "0b10dc0c0a10030002040195010004020201", v5 = true),
    w("W1d", "c.apply(1.toByte)", "0b0bdc0c0a1003000204010201", v5 = true, Widen + " A Byte CONSTANT argument leaves " +
      "`apply` unspecialised (its static type is `IV`, Q1/Q3), yet the call evaluates."),
    w("W1s", "c.apply(lie(1, 1.toShort))", "0b10dc0c0a10030002040195010004020302", v5 = true),
    Row("W1n", "`c.apply(lie(1, 1L))` — THROWS `IllegalArgumentException`: reflection never NARROWS a Long to an Int. " +
      "SEPARATING: an implementation that converts any integer answers 1.",
      strip("0b10dc0c0a10030002040195010004020502"), accept = false, v5 = true),
    ctl("W2c", "c.getOrElse(1, 7)", "0b0ddc0c021003000204020402040e", v5 = true),
    w("W2", "c.getOrElse(lie(1, 1.toByte), 7)", "0b12dc0c0210030002040295010004020201040e", v5 = true),
    w("W2s", "c.getOrElse(1.toShort, 7)", "0b0ddc0c021003000204020302040e", v5 = true),
    ctl("W3c", "c.get(1)", "0b0bdc0c211003000204010402", v5 = false),
    w("W3", "c.get(lie(1, 1.toByte))", "0b10dc0c2110030002040195010004020201", v5 = false),
    ctl("W4c", "c.indexOf(1, 0)", "0b0ddc0c1a10030002040204020400", v5 = true),
    w("W4", "c.indexOf(1, lie(0, 0.toByte))", "0b12dc0c1a100300020402040295010004000200", v5 = true),
    ctl("W5c", "c.patch(0, Coll(7), 1)", "0b10dc0c13100300020403040010010e0402", v5 = true),
    w("W5a", "c.patch(lie(0, 0.toByte), Coll(7), 1)", "0b15dc0c131003000204039501000400020010010e0402", v5 = true),
    w("W5b", "c.patch(0, Coll(7), lie(1, 1.toShort))", "0b15dc0c13100300020403040010010e95010004020302", v5 = true),
    ctl("W6c", "c.updated(0, 7)", "0b0ddc0c141003000204020400040e", v5 = true),
    w("W6", "c.updated(lie(0, 0.toShort), 7)", "0b12dc0c1410030002040295010004000300040e", v5 = true),
    ctl("W7c", "Global.decodeNbits(1L)", "0b07dc6a07dd010502", v5 = false),
    w("W7", "Global.decodeNbits(lie(1L, 1))", "0b0cdc6a07dd0195010005020402", v5 = false),
    w("W7b", "Global.decodeNbits(1.toByte)", "0b07dc6a07dd010201", v5 = false),
    ctl("W8c", "Global.powHit(32, msg, nonce, h, 1024)",
      "0b1ddc6a08dd0504400e01010e0800000000000000010e0400000001048010", v5 = false),
    w("W8a", "Global.powHit(lie(32, 32.toByte), msg, nonce, h, 1024)",
      "0b22dc6a08dd05950100044002200e01010e0800000000000000010e0400000001048010", v5 = false),
    w("W8b", "Global.powHit(32, msg, nonce, h, lie(1024, 1024.toShort))",
      "0b23dc6a08dd0504400e01010e0800000000000000010e0400000001950100048010038010", v5 = false),
    ctl("W9c", "SELF.getReg[Long](0)", "0b08dc6313a701040005", v5 = false),
    w("W9", "SELF.getReg[Long](lie(0, 0.toByte))", "0b0ddc6313a7019501000400020005", v5 = false),
    w("W9s", "SELF.getReg[Long](0.toShort)", "0b08dc6313a701030005", v5 = false),
    ctl("W10c", "CONTEXT.getVarFromInput[Int](0.toShort, 1.toByte)", "0b0adc650cfe020300020104", v5 = false),
    w("W10", "CONTEXT.getVarFromInput[Int](lie(0.toShort, 0.toByte), 1.toByte)",
      "0b0fdc650cfe0295010003000200020104", v5 = false, Widen + " (None here: the eval context has no input " +
        "extension; the spend family reads Some(5).)"),
    Row("X1", "`1.shiftLeft(lie(1, 1.toByte))` — THROWS `ClassCastException` (Byte → Integer): `shiftLeft` is a " +
      "HANDLER (`userDefinedInvoke`), which casts its argument; nothing widens it. SEPARATING: an implementation that " +
      "widens every argument answers 2.", strip("0b0ddc040c04020195010004020201"), accept = false, v5 = false),
    Row("X2", "`1L.shiftRight(1.toShort)` — THROWS `ClassCastException` (Short → Integer): a handler. SEPARATING, as " +
      "X1.", strip("0b08dc050d0502010302"), accept = false, v5 = false),
    Row("X3", "`BigInt(1).shiftLeft(lie(1, 1.toByte))` — THROWS `ClassCastException`: a handler. SEPARATING, as X1.",
      strip("0b0edc060c0601010195010004020201"), accept = false, v5 = false),
    Row("X4", "`tree.updateOperations(lie(1.toByte, 1.toShort)).enabledOperations` — THROWS `IllegalArgumentException`: " +
      "the parameter is a Byte, and nothing widens TO a Byte.",
      strip("0b33db6402dc6408640000000000000000000000000000000000000000000000000000000000000000000720000195010002010302"),
      accept = false, v5 = false),
    Row("X5", "`c.updateMany(lie(Coll[Int](0), Coll[Byte](0)), Coll(7))` — THROWS `InvocationTargetException` ← " +
      "`ClassCastException` in `CollOverArray.updateMany`: a collection's ITEMS are not widened.",
      strip("0b15dc0c151003000204029501001001000e010010010e"), accept = false, v5 = true),
    Row("Q1", "`c.apply(1.toByte) == 1` — REFUSED AT PARSE (`ConstraintFailed`, `SameType2`): the Byte constant leaves " +
      "`apply` typed `IV`, and `==` checks its operands when built. SEPARATING: a fix that widens the argument AND " +
      "specialises the call to Int over-accepts this tree.", strip("0b0e93dc0c0a10030002040102010402"),
      accept = false, v5 = true),
    Row("Q1c", "`c.apply(1) == 1` → true. CONTROL for Q1.", body(any(EQ(apply(c, I1), I1))), accept = true, v5 = true),
    Row("Q3", "`sigmaProp(c.apply(1.toByte) == c.apply(1.toByte))` — THROWS `RuntimeException: Unknown type IV` at " +
      "evaluation: both operands are `IV`, so the tree parses, and `EQ`'s operand check has no case for a type " +
      "variable.", strip("0b18d193dc0c0a1003000204010201dc0c0a1003000204010201"), accept = false, v5 = true),
    Row("Q2", "`sigmaProp(Coll(true, true).apply(1.toByte))` → sigmaProp(true): `sigmaProp` does not check its " +
      "operand's static type, so the IV-typed widened call reaches a script. SEPARATING: an implementation that " +
      "refuses the Byte argument errors.", strip("0b0ad1dc0c0a0d0203010201"), accept = true, v5 = true),
    Row("Q2c", "`sigmaProp(Coll(true, true).apply(1))` → sigmaProp(true). CONTROL for Q2.",
      strip("0b0ad1dc0c0a0d0203010402"), accept = true, v5 = true)) ++
    // the kinds that cannot stand for an Int
    Seq[(String, String, Value[SType])](
      ("K1", "sigmaProp(true)", any(BoolToSigmaProp(TrueLeaf))), ("K2", "SELF", S), ("K3", "CONTEXT.preHeader", PH),
      ("K4", "g", G), ("K5", "BigInt(1)", any(BigIntConstant(1L))), ("K6", "true", any(TrueLeaf)),
      ("K7", "()", any(UnitConstant())), ("K8", "Coll[Byte](1)", any(ByteArrayConstant(Array[Byte](1))))
    ).map { case (id, x, v) =>
      Row(id, s"`c.apply(lie(1, $x))` — THROWS `IllegalArgumentException`: a non-primitive is never converted to an " +
        "Int. SEPARATING: an implementation that converts it, or reads it as an index, answers.",
        body(apply(c, lie(I1, v))), accept = false, v5 = true)
    } ++ Seq(
    Row("K9", "`c.apply(lie(1, \"a\"))` — THROWS `RuntimeException: Unknown type SString` at the `If`: it checks the " +
      "taken branch's value against THAT branch's type, and has no String case. A String cannot ride a lie.",
      strip("0b11dc0c0a1003000204019501000402660161"), accept = false, v5 = true),
    Row("K10", "`c.apply(\"a\")` — THROWS `IllegalArgumentException: argument type mismatch`.",
      strip("0b0cdc0c0a100300020401660161"), accept = false, v5 = true)) ++
    // updateDigest's Coll[Byte] parameter: any collection, read back by keyLength
    Seq(Row("U0", "`tree.updateDigest(33 bytes).keyLength` → 32. CONTROL.", body(keyLengthOf(D33)),
      accept = true, v5 = true)) ++
    Seq[(String, String, Value[SType], Boolean)](
      ("U1", "sigmaProp(true)", any(BoolToSigmaProp(TrueLeaf)), false), ("U2", "SELF", S, false),
      ("U3", "CONTEXT.preHeader", PH, false), ("U4", "1", I1, false),
      ("U5", "Coll[Box](SELF)", cc(SBox, S), true), ("U6", "Coll[GroupElement](g)", cc(SGroupElement, G), true),
      ("U7", "Coll(1).zip(Coll(2))", mcall(12, 29, cc(SInt, I1), cc(SInt, any(IntConstant(2)))), true),
      ("U8", "Coll[Boolean](true)", cc(SBoolean, any(TrueLeaf)), true),
      ("U9", "Coll[Coll[Byte]](Coll(1))", cc(SCollection(SByte), any(ByteArrayConstant(Array[Byte](1)))), true)
    ).map { case (id, x, v, ok) =>
      val s = s"`tree.updateDigest(lie(33 bytes, $x)).keyLength`"
      if (ok) Row(id, s"$s → 32: the reflective call asks only for a `Coll`, and `updateDigest` copies it unread. " +
        "SEPARATING: an implementation that requires bytes refuses it.", body(keyLengthOf(lie(D33, v))),
        accept = true, v5 = true)
      else Row(id, s"$s — THROWS `IllegalArgumentException`: a non-collection is refused by the reflective call.",
        body(keyLengthOf(lie(D33, v))), accept = false, v5 = true)
    } ++ Seq(
    // receivers
    w("R1", "Coll[Byte] constant (0,1,2).apply(1.toByte)", "0b0bdc0c0a0e03000102010201", v5 = true),
    Row("R1c", "`Coll[Byte] constant (0,1,2).apply(1)`. CONTROL.", body(apply(any(ByteArrayConstant(Array[Byte](0, 1, 2))), I1)),
      accept = true, v5 = true),
    w("R2", "Coll[Long] constant (0,1,2).apply(lie(1, 1.toByte))", "0b10dc0c0a11030002040195010004020201", v5 = true),
    w("R3", "Coll(1,2).zip(Coll(3,4)).apply(1.toByte), a pair collection",
      "0b18dc0c0adc0c1d830204040204040183020404060408010201", v5 = true),
    Row("R3c", "`Coll(1,2).zip(Coll(3,4)).apply(1)`. CONTROL.", body(apply(mcall(12, 29, cc(SInt, I1, any(IntConstant(2))),
      cc(SInt, any(IntConstant(3)), any(IntConstant(4)))), I1)), accept = true, v5 = true),
    w("R4t", "(1,2,3) CONSTANT .apply(1.toByte), a Coll[Any]", "0b0ddc0c0a48040404020406010201", v5 = true),
    Row("R4tl", s"`(1,2,3) CONSTANT .apply(lie(1, 1.toByte))`. $Widen", body(apply(Tup, lie(I1, B1))), accept = true,
      v5 = true),
    Row("R4tc", "`(1,2,3) CONSTANT .apply(1)`. CONTROL.", body(apply(Tup, I1)), accept = true, v5 = true),
    w("R5", "Coll[Box](SELF).apply(0.toByte), a reference receiver", "0b0adc0c0a830163a7010200", v5 = true),
    w("R6", "ConcreteCollection[Int](0,1,2).getOrElse(1.toByte, 7)", "0b11dc0c02830304040004020404020201040e", v5 = true),
    w("R7", "Coll[Byte] constant (0,1,2).getOrElse(lie(1, 1.toShort), 7.toByte)",
      "0b12dc0c020e0300010202950100040203020207", v5 = true))

  def entriesFor(op: String): Seq[(Row, String, Byte, Int)] = op match {
    case Op6 => Rows.map(r => (r, "0b" + vlq(r.body.length / 2) + r.body, V3, 3))
    case Op5 => Rows.filter(_.v5).map(r => (r, "00" + r.body, V2, 0))
  }

  def extract(): Map[String, Json] =
    Ops.map { op =>
      val entries = entriesFor(op).zipWithIndex.map { case ((r, tree, act, tv), i) =>
        val name = s"${r.id.toLowerCase}-${if (r.accept) "accept" else "errored"}#$i"
        val s = s"[${r.id}] ${r.script}"
        if (r.accept) SpecExtract.authoredEntryV(op, s, tree, name, IntInput, act, ergoTree = tv)
        else SpecExtract.authoredRejectEntryV(op, s, tree, name, IntInput, act, ergoTree = tv)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalArgumentWidening", extract(), outDir)
}
