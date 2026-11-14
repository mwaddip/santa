package santa

// Authored eval vectors for sigma-rust's santa-probe-method-call-charge-order (fork PR 90, from its vector backlog,
// 2026-11-14). Measured first in santa.spike.ChargeOrderProbe (local): the jars' 17 answers as read, value and cost.
//
// THE MECHANISM, read in 6.0.7. `AvlTree.insert`/`update`/`remove`/`insertOrUpdate` are DynamicCost methods, so
// `MethodCall.eval` reaches their evaluator through Java reflection (`evalMethod.invoke`, values.scala:1391) on
// `insert_eval(mc, tree, entries: KeyValueColl, proof)` and its siblings (methods.scala:1598-1700). Two consequences:
//
//  RULE 1 — an argument of another CLASS is refused by the reflective call, before the body runs. Each body checks
//  its flag first (`if (!tree.isInsertAllowed) None`, CErgoTreeEvaluator.scala:134; update :170, insertOrUpdate
//  :201, remove :234), so with a flag OFF a well-typed call answers None; a `Boolean` where a collection is declared
//  throws `IllegalArgumentException: argument type mismatch` instead. The tree parses: nothing checks an argument
//  against the declared domain at parse (`specializeFor` finds no substitution and leaves the method as it is).
//
//  RULE 2 — nothing after a failed entry is read. `insert` (from tree v3), `update` and `insertOrUpdate` loop with
//  `forall` (:140, :178, :209), which stops at the first entry whose operation fails and answers None. The rows'
//  entries argument is a TRIPLE constant (a good pair, then a pair with a `Coll[Int]` key or a bare `Int`, then 0):
//  the JVM holds a non-pair tuple as a `Coll[Any]`, so the reflective call accepts it as the declared collection, and
//  the wrong-shaped second item is never looked at. Each row's CONTROL is the same call with a one-entry
//  `Coll[(Coll[Byte], Coll[Byte])]` holding only the first entry: the same None at the SAME cost, so the later entry
//  is not charged either.
//
// The fixture (sigma-rust's, from the tests' BatchAVLProver): tree T holds keys 1 and 2 (key length 1, flags 07);
// T0 is T with every flag off; T_B is another tree. The proofs insert key 3 → 30, update key 1 → 11. "other-tree"
// rows apply a valid proof to T_B; "built" rows apply it on T to an entry it was not made for (insert key 1, already
// present; update key 3, absent). Either way the FIRST entry fails.
//
// TWO FAMILIES:
//  * v6 (activated 3, tree v3): the 17 rows, bodies verbatim, plus a derived control for each (10).
//  * v5 (activated 2, tree v0), measured here, not asked for: rule 1 holds for insert/update/remove, and rule 2 for
//    `update` (None at the same cost). Left out: v0 `insert` rule-2 rows (a failed insert THROWS below tree v3, the
//    control included, so they cannot tell whether later entries are read; that arm is AvlTree.per_op_failure's) and
//    `insertOrUpdate` (method 16 is v6-only).

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.data.RType
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalAvlChargeOrder {
  val V3: Byte = 3
  val V2: Byte = 2
  val Source = "santa:authored-avl-charge-order"
  val Stamp = "jvm:sigma-state-6.0.7"
  val Op6 = "AvlTree.method_call_charge_order_v6"
  val Op5 = "AvlTree.method_call_charge_order"
  val Ops: Seq[String] = Seq(Op6, Op5)

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  def v3Tree(body: String): String = "0b" + vlq(body.length / 2) + body

  case class Row(op: String, id: String, script: String, body: String, accept: Boolean, slug: String) {
    def v6: Boolean = op == Op6
    def treeHex: String = if (v6) v3Tree(body) else "00" + body
    def activated: Byte = if (v6) V3 else V2
    def ergoTree: Int = if (v6) 3 else 0
  }

  // ── derived controls ────────────────────────────────────────────────────────────────────────────────
  private def mcOf(body: String): MethodCall = {
    val t = sigma.santa.LenientErgoTree.deserialize(Base16.decode(v3Tree(body)).get)
    VersionContext.withVersions(V3, V3)(t.toProposition(false)).asInstanceOf[MethodCall]
  }
  private def bodyOf(root: Value[SType]): String = VersionContext.withVersions(V3, V3) {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(root, w); hx(w.toBytes)
  }
  private val PairT = STuple(SCollection(SByte), SCollection(SByte))
  private def emptyOf(t: SType): Value[SType] = {
    val rt = sigma.Evaluation.stypeToRType(t.asInstanceOf[SCollection[SType]].elemType).asInstanceOf[RType[Any]]
    Constant[SType](sigma.Colls.emptyColl[Any](rt).asInstanceOf[SType#WrappedType], t)
  }
  /** Rule 1's control: the Boolean argument replaced by an empty constant of the declared type. */
  def flagOffControl(body: String): String = {
    val mc = mcOf(body)
    val dom = mc.method.stype.tDom.tail
    bodyOf(mc.copy(args = mc.args.zipWithIndex.map { case (a, i) => if (a == TrueLeaf) emptyOf(dom(i)) else a }))
  }
  /** Rule 2's control: the triple replaced by a one-entry `Coll[(Coll[Byte], Coll[Byte])]` of its first item. */
  def firstEntryControl(body: String): String = {
    val mc = mcOf(body)
    val first = mc.args(0).asInstanceOf[ConstantNode[SType]].value.asInstanceOf[sigma.Coll[Any]](0)
    val rt = sigma.Evaluation.stypeToRType(PairT).asInstanceOf[RType[Any]]
    val c = Constant[SType](sigma.Colls.fromItems[Any](first)(rt).asInstanceOf[SType#WrappedType], SCollection(PairT))
    bodyOf(mc.copy(args = c +: mc.args.tail))
  }

  // ── sigma-rust's bodies, verbatim ───────────────────────────────────────────────────────────────────
  private val T = "6415cc1ba1054bf74431a2ccfc57e6051b9d0e7561f70586e5512c0ae3f3bac4fd02"
  private val PI = "0e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a039d4d9434aa05d4dc888d939d6771fe05418df5bd91101dbe63384514c1869e140202ff00000008000000000000001400010400"
  private val PU = "0e550385ab460a6564d1e5ded17716cd5866650d7fbb9cada7fecabd5fe21e2f80e43a02010200000008000000000000000a03fc29fc93353c639f7cf275ea69851dfa41d720ed53bcc9c10bda17166966d5ed00010402"
  private val TB = "647ca99024833a7c538e35710c94acf164ca93693117acee3a5bfdd3035810ee110107010002"
  val FlagOffInsert  = s"dc640c${T}0001000201010e0102"
  val FlagOffUpdate  = s"dc640d${T}000100020c3c0e0e000101"
  val FlagOffRemove  = s"dc640e${T}0001000201010e0102"
  val FlagOffIou     = s"dc6410${T}000100020c3c0e0e000101"
  val FlagOffZero    = "dc640c640000000000000000000000000000000000000000000000000000000000000000000020000201010e0102"
  private val E3Int = "483c0e0e3c100e04010308000000000000001e01040105" + "00"  // ((3 → 30), (Coll[Int](2) → 5), 0)
  private val E3No  = "483c0e0e0404010308000000000000001e0a" + "00"            // ((3 → 30), 5, 0)
  private val E1Int = "483c0e0e3c100e04010108000000000000000b01040105" + "00"  // ((1 → 11), …)
  private val E1No  = "483c0e0e0404010108000000000000000b0a" + "00"
  private val E1aInt = "483c0e0e3c100e04010108000000000000000a01040105" + "00" // ((1 → 10), …)
  private val E1aNo  = "483c0e0e0404010108000000000000000a0a" + "00"
  val OtherInsInt = s"dc640c$TB$E3Int$PI";  val OtherInsNo = s"dc640c$TB$E3No$PI"
  val OtherUpdInt = s"dc640d$TB$E1Int$PU";  val OtherUpdNo = s"dc640d$TB$E1No$PU"
  val OtherIouInt = s"dc6410$TB$E3Int$PI";  val OtherIouNo = s"dc6410$TB$E3No$PI"
  private val TT = T + "07010002"
  val BuiltInsInt = s"dc640c$TT$E1aInt$PI"; val BuiltInsNo = s"dc640c$TT$E1aNo$PI"
  val BuiltUpdInt = s"dc640d$TT$E3Int$PU";  val BuiltUpdNo = s"dc640d$TT$E3No$PU"
  val BuiltIouInt = s"dc6410$TT$E1aInt$PI"
  /** sigma-rust's own control for the built insert rows; the test pins that `firstEntryControl` derives it. */
  val BuiltInsControlTheirs = s"dc640c${TT}0c3c0e0e01010108000000000000000a$PI"

  // ── rows ────────────────────────────────────────────────────────────────────────────────────────────
  private val Reflect = "reached by reflection (`evalMethod.invoke`, values.scala:1391)"
  private def rule1(op: String, id: String, call: String, m: String, flagLine: String, body: String): Seq[Row] = Seq(
    Row(op, id, s"`$call` on a tree whose flags are all OFF — THROWS `IllegalArgumentException: argument type " +
      s"mismatch`: `$m` is $Reflect, and a `Boolean` where a collection is declared is refused by the reflective call " +
      s"BEFORE the body's flag check ($flagLine) runs. SEPARATING: an evaluator that checks the flag first answers " +
      s"None (${id}c).", body, accept = false, s"flag-off-$m-boolean-arg-errored"),
    Row(op, s"${id}c", s"The same call with the `Boolean` replaced by a well-typed empty collection → None: the " +
      s"flag check answers first. CONTROL for $id.", flagOffControl(body), accept = true,
      s"flag-off-$m-well-typed-control-accept"))

  /** `no` is None where sigma-rust selected no no-pair twin (B5). */
  private def rule2(op: String, ids: (String, String, String), m: String, set: String, why: String,
                    loop: String, int: String, no: Option[String]): Seq[Row] = {
    val (a, b, c) = ids
    val rows = if (no.isDefined) s"$a/$b" else a
    val costs = if (no.isDefined) s"$a and $b" else a
    val common = s"the entries argument is a TRIPLE constant, held as a `Coll[Any]`, so the reflective call accepts " +
      s"it; $why, so the first entry's operation fails and `forall` ($loop) stops there → None. "
    Seq(
      Row(op, a, s"`tree.$m((first, (Coll[Int](2) → Coll(5.toByte)), 0), proof)` ($set) — $common" +
        "The second entry, a pair whose KEY is a `Coll[Int]`, is never read. SEPARATING: an evaluator that reads or " +
        s"type-checks every entry first errors. CONTROL: $c.", int, accept = true,
        s"$set-$m-second-key-coll-int-accept"),
      Row(op, c, s"`tree.$m(Coll(first), proof)` ($set) — the same call with only the first entry, in a well-typed " +
        s"`Coll[(Coll[Byte], Coll[Byte])]` → None at the SAME cost as $costs: the later entry is not charged either. " +
        s"CONTROL for $rows.", firstEntryControl(int), accept = true, s"$set-$m-first-entry-only-control-accept")) ++
      no.toSeq.map(n => Row(op, b, s"`tree.$m((first, 5, 0), proof)` ($set) — $common" +
        "The second item, a bare `Int` where a pair is declared, is never read. SEPARATING, as " + a + s". CONTROL: $c.",
        n, accept = true, s"$set-$m-second-no-pair-accept"))
  }
  private val OtherWhy = "the proof is valid for the fixture tree T, applied here to ANOTHER tree"
  private val BuiltInsWhy = "on the fixture tree, the first entry inserts key 1, which is already present, against " +
    "the proof for key 3"
  private val BuiltUpdWhy = "on the fixture tree, the first entry updates key 3, which is absent, against the proof " +
    "for key 1"
  private val BuiltIouWhy = "on the fixture tree, the first entry is key 1 against the proof that inserts key 3"

  val Rows6: Seq[Row] =
    rule1(Op6, "F1", "tree.insert(true, Coll(2.toByte))", "insert", "CErgoTreeEvaluator.scala:134", FlagOffInsert) ++
    rule1(Op6, "F2", "tree.update(Coll(), true)", "update", "CErgoTreeEvaluator.scala:170", FlagOffUpdate) ++
    rule1(Op6, "F3", "tree.remove(true, Coll(2.toByte))", "remove", "CErgoTreeEvaluator.scala:234", FlagOffRemove) ++
    rule1(Op6, "F4", "tree.insertOrUpdate(Coll(), true)", "insertOrUpdate", "CErgoTreeEvaluator.scala:201", FlagOffIou) ++
    rule1(Op6, "F5", "zeroTree.insert(true, Coll(2.toByte))", "insert", "CErgoTreeEvaluator.scala:134", FlagOffZero)
      .map(r => r.copy(script = r.script.replace("on a tree whose flags", "on an all-zero digest, key length 32, flags"),
        slug = r.slug.replace("flag-off-insert", "flag-off-zero-digest-insert"))) ++
    rule2(Op6, ("O1", "O2", "O1c"), "insert", "other-tree", OtherWhy, "CErgoTreeEvaluator.scala:140",
      OtherInsInt, Some(OtherInsNo)) ++
    rule2(Op6, ("O3", "O4", "O3c"), "update", "other-tree", OtherWhy, "CErgoTreeEvaluator.scala:178",
      OtherUpdInt, Some(OtherUpdNo)) ++
    rule2(Op6, ("O5", "O6", "O5c"), "insertOrUpdate", "other-tree", OtherWhy, "CErgoTreeEvaluator.scala:209",
      OtherIouInt, Some(OtherIouNo)) ++
    rule2(Op6, ("B1", "B2", "B1c"), "insert", "built", BuiltInsWhy, "CErgoTreeEvaluator.scala:140",
      BuiltInsInt, Some(BuiltInsNo)) ++
    rule2(Op6, ("B3", "B4", "B3c"), "update", "built", BuiltUpdWhy, "CErgoTreeEvaluator.scala:178",
      BuiltUpdInt, Some(BuiltUpdNo)) ++
    rule2(Op6, ("B5", "B6", "B5c"), "insertOrUpdate", "built", BuiltIouWhy, "CErgoTreeEvaluator.scala:209",
      BuiltIouInt, None)

  private val V5Note = " Tree v0 under activated 2: the same outcome and cost as at tree v3."
  val Rows5: Seq[Row] = (
    rule1(Op5, "F1", "tree.insert(true, Coll(2.toByte))", "insert", "CErgoTreeEvaluator.scala:134", FlagOffInsert) ++
    rule1(Op5, "F2", "tree.update(Coll(), true)", "update", "CErgoTreeEvaluator.scala:170", FlagOffUpdate) ++
    rule1(Op5, "F3", "tree.remove(true, Coll(2.toByte))", "remove", "CErgoTreeEvaluator.scala:234", FlagOffRemove) ++
    rule1(Op5, "F5", "zeroTree.insert(true, Coll(2.toByte))", "insert", "CErgoTreeEvaluator.scala:134", FlagOffZero)
      .map(r => r.copy(script = r.script.replace("on a tree whose flags", "on an all-zero digest, key length 32, flags"),
        slug = r.slug.replace("flag-off-insert", "flag-off-zero-digest-insert"))) ++
    rule2(Op5, ("O3", "O4", "O3c"), "update", "other-tree", OtherWhy, "CErgoTreeEvaluator.scala:178",
      OtherUpdInt, Some(OtherUpdNo)) ++
    rule2(Op5, ("B3", "B4", "B3c"), "update", "built", BuiltUpdWhy, "CErgoTreeEvaluator.scala:178",
      BuiltUpdInt, Some(BuiltUpdNo))
  ).map(r => r.copy(script = r.script + V5Note))

  val Rows: Seq[Row] = Rows6 ++ Rows5

  def extract(): Map[String, Json] =
    Ops.map { op =>
      val entries = Rows.filter(_.op == op).zipWithIndex.map { case (r, i) =>
        val name = s"${r.slug}#$i"
        if (r.accept) SpecExtract.authoredEntryV(op, s"[${r.id}] ${r.script}", r.treeHex, name, IntInput,
          r.activated, ergoTree = r.ergoTree)
        else SpecExtract.authoredRejectEntryV(op, s"[${r.id}] ${r.script}", r.treeHex, name, IntInput,
          r.activated, ergoTree = r.ergoTree)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalAvlChargeOrder", extract(), outDir)
}
