package santa

// Authored eval vectors for sigma-rust's santa-probe-reference-store (2026-11-12, ahead of a spec for its
// queue item 10: U12 of Coll.item_store_type_check, T23/U3 of Tuple.non_pair_value_ops). Measured first in
// santa.spike.ReferenceStoreProbe; the reply is prompts/drafts/sigma-rust-reference-store-reply.md.
//
// THE RULE, measured at 6.0.7 and read in CollsOverArrays.scala: a store into a collection goes into an array
// of the collection's RUNTIME class, ITEM BY ITEM — `updated` (:100), `patch` (:94, through Scala's builder),
// `updateMany` (:106, into `toArray.clone()`), `flatMap` (:70, by the result's class tag). An item whose class
// does not fit throws (`ArrayStoreException` naming the item's class; a primitive array unboxes and throws
// `ClassCastException`), one whose class fits is stored whatever its item type, and what is never stored is
// never checked. A `PairOfCols` does it per side, and `patch` unzips its argument first, so a non-pair patch
// throws on the item type's descriptor (`PrimitiveType` cast to `PairType`), empty or not.
//
// THREE FAMILIES:
//  * `Coll.reference_store_ops`: the probe's rows (the sigma-rust session's own trees, verbatim; A1 is
//    left out, being U12 of Coll.item_store_type_check in a SizeOf), plus item classes the rows never used
//    (BigInt, Option, a function) and receivers they never used (a specialised Coll[Byte], INPUTS). B5/B8
//    RETURN — `patch` copies the patch's ITEMS, never its array — and F1-F3 pin `patch`'s cost, which counts
//    both collections (methods.scala:1026: 10 + 1 items is two chunks). Appended from the second probe
//    (santa-probe-reference-store-2, spike ReferenceStoreProbe2): a pair's COMPONENT is stored into its
//    side's typed array (`fromBoxedPairs`, G1-G4), and flatMap's parts are copied item by item, so an empty
//    part of another class passes even into a primitive result (H1/H2) while a Long item into byte[] throws.
//  * `Coll.store_runtime_array_class`: the store follows the RUNTIME array, not the static type, so a
//    WELL-TYPED item is refused when the two disagree. `append` with an empty receiver returns `other`
//    unchanged (:49), so `Coll[Box]() ++ Coll[GroupElement](g)` is a Coll[Box] over GroupElement[] with no
//    lie in the tree, and storing a Box into it throws; an `If` lie does the same with an Int into long[].
//  * `Coll.append_node_array_class`: the `Append` NODE is the one place the OTHER's ARRAY class decides:
//    `safeConcatArrays_v5` → `CollectionUtil.concatArrays` `System.arraycopy`s each operand into an array of
//    the receiver's class, which refuses mismatched primitive arrays before looking at the length (an EMPTY
//    long[] into int[] throws) and checks reference arrays element by element (an empty one passes).
//    `append` as a METHOD CALL does not evaluate at 6.0.7 (pinned by Coll.methodcall_v5_ids #9).
//
// Every root is a SizeOf, so every value is an Int; every tree ignores its input (Int 0 at var 1).

import java.math.BigInteger

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalReferenceStore {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Source = "santa:authored-eval-reference-store"
  val Stamp = "jvm:sigma-state-6.0.7"
  val OpOps = "Coll.reference_store_ops"
  val OpRuntime = "Coll.store_runtime_array_class"
  val OpAppend = "Coll.append_node_array_class"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }

  // ── AST helpers for the rows built here ───────────────────────────────────────────────────────────
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def coll(v: Value[SType]) = v.asInstanceOf[Value[SCollection[SType]]]
  private def cc(tpe: SType, items: Value[SType]*): Value[SType] = any(ConcreteCollection(items.toIndexedSeq, tpe))
  private def mcall(methodId: Int, recv: Value[SType], args: Value[SType]*): Value[SType] =
    VersionContext.withVersions(V3, V3) {
      any(MethodCall(recv, SMethod.fromIds(12.toByte, methodId.toByte), args.toIndexedSeq, Map()))
    }
  private def size(v: Value[SType]): Value[SType] = any(SizeOf(coll(v)))
  def tree(root: Value[SType]): String = VersionContext.withVersions(V3, V3) {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(root, w)
    val body = hx(w.toBytes); "0b" + vlq(body.length / 2) + body
  }
  private val I0 = any(IntConstant(0))
  private val G = any(GroupGenerator)
  private val S = any(Self)
  private def updated(recv: Value[SType], item: Value[SType]) = mcall(20, recv, I0, item)
  private def lie: Value[SType] =   // typed Coll[Int] (the true branch), holding Coll(1L)
    any(If(FalseLeaf, coll(cc(SInt, any(IntConstant(1)))), coll(cc(SLong, any(LongConstant(1L))))))
  private def emptyBoxesPlusG = any(Append(coll(cc(SBox)), coll(cc(SGroupElement, G))))

  /** One row. `tree` is either the probe's hex (Left) or an AST built here (Right). `accept` rows return. */
  case class Row(op: String, id: String, script: String, src: Either[String, () => Value[SType]],
                 accept: Boolean, slug: String) {
    def treeHex: String = src.fold(identity, ast => tree(ast()))
  }

  private val sep = "SEPARATING: an implementation that checks the item against the collection's static item " +
    "type, or does not check at all, returns."
  private def throwsItem(cls: String) = s"THROWS `ArrayStoreException: $cls` (in an InvocationTargetException): " +
    "the store into the receiver's array is by the ITEM's class."

  val Rows: Seq[Row] = Seq(
    // ── updated ──
    Row(OpOps, "A2", "`Coll(1).zip(Coll[Box](SELF)).updated(0, (1, groupGenerator))` — " +
      throwsItem("sigma.data.CGroupElement") + " A `PairOfCols` stores each side into its own array. SEPARATING.",
      Left("0b17b1dc0c14dc0c1d10010201830163a70204008602040282"), accept = false, "pair-box-side-groupelement-errored"),
    Row(OpOps, "A3", "`Coll(1).zip(Coll[Box](SELF)).updated(0, (1, SELF))` — 1 at cost 124. CONTROL for A2.",
      Left("0b17b1dc0c14dc0c1d10010201830163a702040086020402a7"), accept = true, "pair-box-side-box-accept"),
    Row(OpOps, "A4", "`(1,2,3).updated(0, groupGenerator)` — 3 at cost 59: a non-pair tuple's array is " +
      "`Object[]`, so any item fits.",
      Left("0b0fb1dc0c144804040402040602040082"), accept = true, "tuple-receiver-groupelement-accept"),
    Row(OpOps, "A5", "`Coll[Box](SELF).updated(0, 1)` — " + throwsItem("java.lang.Integer") + " " + sep,
      Left("0b0db1dc0c14830163a70204000402"), accept = false, "box-coll-int-item-errored"),
    // ── patch ──
    Row(OpOps, "B1", "`Coll[Box](SELF).patch(0, Coll[GroupElement](g), 0)` — " +
      throwsItem("sigma.data.CGroupElement") + " " + sep,
      Left("0b11b1dc0c13830163a7030400830107820400"), accept = false, "box-coll-groupelement-patch-errored"),
    Row(OpOps, "B2", "`Coll[Box](SELF).patch(0, Coll[GroupElement](), 0)` — 1 at cost 110: an EMPTY patch " +
      "stores nothing, so nothing is checked.",
      Left("0b10b1dc0c13830163a70304008300070400"), accept = true, "box-coll-empty-groupelement-patch-accept"),
    Row(OpOps, "B3", "`Coll[Coll[Int]](Coll(1)).patch(0, Coll[Coll[Long]](Coll(1L)), 0)` — 2 at cost 150: the " +
      "array is `Coll[]`, and a Coll[Long] is a Coll, so it is stored whatever its item type.",
      Left("0b19b1dc0c13830110830104040203040083011183010505020400"), accept = true, "coll-of-coll-long-patch-accept"),
    Row(OpOps, "B4", "`Coll[Box](SELF).patch(0, Coll[Int] constant (1), 0)` — " +
      throwsItem("java.lang.Integer") + " " + sep,
      Left("0b10b1dc0c13830163a70304001001020400"), accept = false, "box-coll-int-patch-errored"),
    Row(OpOps, "B5", "`Coll[Int] constant (1,2,3).patch(0, (7,8,9), 0)` — 6 at cost 70: `patch` copies the " +
      "patch's ITEMS through Scala's builder (CollsOverArrays.scala:94-98), never its array, and the tuple's " +
      "boxed Integers unbox into int[]. Not the array-class rule of the Append node (Coll.append_node_array_class).",
      Left("0b15b1dc0c131003020406030400480404040e10120400"), accept = true, "int-coll-tuple-patch-accept"),
    Row(OpOps, "B6", "`ConcreteCollection[Int](1,2,3).patch(0, (7,8,9), 0)` — 6 at cost 100, as B5 from a " +
      "generic receiver.",
      Left("0b19b1dc0c13830304040204040406030400480404040e10120400"), accept = true, "generic-int-coll-tuple-patch-accept"),
    Row(OpOps, "B7", "`Coll[Int] constant (1,2,3).patch(0, ConcreteCollection[Int](7), 0)` — 4 at cost 90. CONTROL.",
      Left("0b13b1dc0c131003020406030400830104040e0400"), accept = true, "int-coll-int-patch-accept"),
    Row(OpOps, "B8", "`Coll[Int] constant (1,2,3).patch(0, ConcreteCollection[Long](), 0)` — 3 at cost 85: an " +
      "empty long[] patch stores nothing. The Append NODE refuses the same shape (an arraycopy of long[] into " +
      "int[]); `patch` does not.",
      Left("0b11b1dc0c1310030204060304008300050400"), accept = true, "int-coll-empty-long-patch-accept"),
    Row(OpOps, "B9", "`Coll(1).zip(Coll(1)).patch(0, Coll[Int] constant (), 0)` — THROWS `ClassCastException: " +
      "PrimitiveType cannot be cast to PairType`: a PairOfCols unzips its patch, casting the patch's item type, " +
      "so a non-pair patch throws EMPTY or not. SEPARATING: an implementation that skips an empty patch returns 1.",
      Left("0b15b1dc0c13dc0c1d1001020110010203040010000400"), accept = false, "pair-coll-empty-nonpair-patch-errored"),
    Row(OpOps, "B10", "`Coll(1).zip(Coll(1)).patch(0, Coll(1).zip(Coll(1)), 0)` — 2 at cost 110. CONTROL for B9.",
      Left("0b1db1dc0c13dc0c1d10010201100102030400dc0c1d100102011001020400"), accept = true, "pair-coll-pair-patch-accept"),
    // ── updateMany ──
    Row(OpOps, "C1", "`Coll[Box](SELF).updateMany(Coll(0), Coll[GroupElement](g))` — " +
      throwsItem("sigma.data.CGroupElement") + " " + sep,
      Left("0b10b1dc0c15830163a70210010083010782"), accept = false, "box-coll-groupelement-updatemany-errored"),
    Row(OpOps, "C2", "`Coll[Coll[Int]](Coll(1)).updateMany(Coll(0), Coll[Coll[Long]](Coll(1L)))` — 1 at cost " +
      "135: stored into `Coll[]`, no item-type comparison. SEPARATING: an implementation that compares the two " +
      "collections' types refuses.",
      Left("0b18b1dc0c158301108301040402021001008301118301050502"), accept = true, "coll-of-coll-long-updatemany-accept"),
    Row(OpOps, "C3", "`Coll[Box](SELF).updateMany(Coll[Int](), Coll[GroupElement]())` — 1 at cost 95: nothing " +
      "is stored. SEPARATING: an implementation that compares the two collections' types refuses.",
      Left("0b0eb1dc0c15830163a7021000830007"), accept = true, "box-coll-nothing-stored-updatemany-accept"),
    Row(OpOps, "C4", "`Coll(1).zip(Coll[Box](SELF)).updateMany(Coll(0), Coll(1).zip(Coll[GroupElement](g)))` — " +
      throwsItem("sigma.data.CGroupElement") + " Per side, as `updated`.",
      Left("0b1eb1dc0c15dc0c1d10010201830163a702100100dc0c1d1001020183010782"), accept = false,
      "pair-coll-groupelement-side-updatemany-errored"),
    Row(OpOps, "C5", "`Coll(1).zip(Coll[Coll[Int]](Coll(1))).updateMany(Coll(0), Coll(1).zip(Coll[Coll[Long]]" +
      "(Coll(1L))))` — 1 at cost 175: per side, class-compatible.",
      Left("0b26b1dc0c15dc0c1d10010201830110830104040202100100dc0c1d100102018301118301050502"), accept = true,
      "pair-coll-coll-long-side-updatemany-accept"),
    // ── map / flatMap ──
    Row(OpOps, "D1", "`Coll(1).map({x => If(false, SELF, groupGenerator)})` — THROWS `InterpreterException: " +
      "Invalid type returned by evaluator`: the closure checks the body's value against the body's type " +
      "(values.scala:1080) before map stores anything.",
      Left("0b0eb1ad100102d9010104950100a782"), accept = false, "map-lambda-box-typed-groupelement-errored"),
    Row(OpOps, "D2", "`Coll(1).map({x => If(false, Coll(1), Coll(1L))})` — 1 at cost 90: the body's check is by " +
      "class, and a Coll[Long] is a Coll.",
      Left("0b16b1ad100102d901010495010083010404028301050502"), accept = true, "map-lambda-class-compatible-lie-accept"),
    Row(OpOps, "D3", "`Coll(1).flatMap({x => If(false, Coll[Box](SELF), Coll[GroupElement](g))})` — " +
      throwsItem("sigma.data.CGroupElement") + " flatMap copies each part into an array of the result's item " +
      "class (CollsOverArrays.scala:70). " + sep,
      Left("0b17b1dc0c0f10010201d9010104950100830163a783010782"), accept = false, "flatmap-box-typed-groupelement-errored"),
    Row(OpOps, "D4", "`Coll(1).flatMap({x => If(false, Coll[Coll[Int]](Coll(1)), Coll[Coll[Long]](Coll(1L)))})` — " +
      "1 at cost 163: class-compatible.",
      Left("0b1fb1dc0c0f10010201d901010495010083011083010404028301118301050502"), accept = true,
      "flatmap-coll-of-coll-long-accept"),
    Row(OpOps, "D7", "`Coll(1).zip(Coll(1)).map({p => If(false, SELF, groupGenerator)})` — THROWS, as D1, on a " +
      "PairOfCols receiver.",
      Left("0b15b1addc0c1d10010201100102d9010158950100a782"), accept = false, "pair-map-lambda-box-typed-groupelement-errored"),
    Row(OpOps, "D8", "`Coll(1).zip(Coll(1)).map({p => SELF})` — 1 at cost 80. CONTROL for D7.",
      Left("0b11b1addc0c1d10010201100102d9010158a7"), accept = true, "pair-map-lambda-self-accept"),
    // ── patch's cost ──
    Row(OpOps, "F1", "`(1..10).patch(0, Coll(1), 0)` — 11 at cost 72: `patch` charges PerItemCost(30, 2, 10) on " +
      "BOTH collections (methods.scala:1026, nItems = 10 + 1 = two chunks). SEPARATING on cost: an implementation " +
      "that counts the receiver alone charges 70.",
      Left("0b18b1dc0c13100a020406080a0c0e1012140304001001020400"), accept = true, "patch-cost-10-plus-1-accept"),
    Row(OpOps, "F2", "`(1..9).patch(0, Coll(1), 0)` — 10 at cost 70: 9 + 1 items, one chunk. CONTROL for F1.",
      Left("0b17b1dc0c131009020406080a0c0e10120304001001020400"), accept = true, "patch-cost-9-plus-1-accept"),
    Row(OpOps, "F3", "`(1..11).patch(0, Coll(1), 0)` — 12 at cost 72: 11 + 1 items, two chunks either way. " +
      "CONTROL for F1.",
      Left("0b19b1dc0c13100b020406080a0c0e101214160304001001020400"), accept = true, "patch-cost-11-plus-1-accept"),
    // ── item classes and receivers the probe did not use ──
    Row(OpOps, "X1", "`Coll[BigInt](BigInt(1)).updated(0, groupGenerator)` — " +
      throwsItem("sigma.data.CGroupElement") + " The item class BigInt. " + sep,
      Right(() => size(updated(cc(SBigInt, any(BigIntConstant(BigInteger.ONE))), G))), accept = false,
      "bigint-coll-groupelement-errored"),
    Row(OpOps, "X2", "`Coll[Option[Int]](getVar[Int](7)).updated(0, groupGenerator)` — " +
      throwsItem("sigma.data.CGroupElement") + " The item class Option. " + sep,
      Right(() => size(updated(cc(SOption(SInt), any(GetVar(7.toByte, SInt))), G))), accept = false,
      "option-coll-groupelement-errored"),
    Row(OpOps, "X3", "`Coll[Int => Int](x => x).updated(0, groupGenerator)` — " +
      throwsItem("sigma.data.CGroupElement") + " The item class: a function. " + sep,
      Right(() => size(updated(cc(SFunc(SInt, SInt), any(FuncValue(IndexedSeq((1, SInt)), ValUse(1, SInt)))), G))),
      accept = false, "function-coll-groupelement-errored"),
    Row(OpOps, "X4", "`Coll[Byte] constant (1).updated(0, groupGenerator)` — THROWS `ClassCastException: " +
      "CGroupElement cannot be cast to java.lang.Byte` (in an InvocationTargetException): a specialised byte[] " +
      "receiver unboxes. " + sep,
      Right(() => size(updated(any(ByteArrayConstant(Array[Byte](1))), G))), accept = false,
      "byte-coll-groupelement-errored"),
    Row(OpOps, "X5", "`INPUTS.updated(0, groupGenerator)` — " + throwsItem("sigma.data.CGroupElement") +
      " A receiver from the context, not built by the script. " + sep,
      Right(() => size(updated(any(Inputs), G))), accept = false, "inputs-groupelement-errored"),
    // ── probe 2 (santa-probe-reference-store-2): a pair's component, flatMap's parts. Appended. ──
    Row(OpOps, "G1", "`Coll(1).map({x => If(false, (1, SELF), (1, g))})` — THROWS `ArrayStoreException: " +
      "sigma.data.CGroupElement`, UNWRAPPED: the closure's result check passes (a Tuple2 is a Tuple2), and the " +
      "MapCollection node's `fromArray` at a pair type goes through `fromBoxedPairs`, which stores each " +
      "COMPONENT into its side's typed array. SEPARATING: an implementation that checks only the pair, or " +
      "nothing, returns 1.",
      Left("0b16b1ad100102d901010495010086020402a78602040282"), accept = false, "map-pair-component-lie-errored"),
    Row(OpOps, "G2", "`Coll(1).zip(Coll(1)).map({p => If(false, (1, SELF), (1, g))})` — 1 at cost 115: a " +
      "PairOfCols' `map` builds a generic collection over a `Tuple2[]` directly, so the lie is never stored into " +
      "a typed side. The setup of G3/G4.",
      Left("0b1db1addc0c1d10010201100102d901015895010086020402a78602040282"), accept = true,
      "pair-map-pair-component-lie-accept"),
    Row(OpOps, "G3", "`G2.reverse` — THROWS `ArrayStoreException: sigma.data.CGroupElement` (in an " +
      "InvocationTargetException): `reverse` rebuilds through `fromArray`, and the misfit component meets the " +
      "typed side there. SEPARATING: an implementation that never rebuilds returns 1.",
      Left("0b20b1db0c1eaddc0c1d10010201100102d901015895010086020402a78602040282"), accept = false,
      "pair-map-lie-reverse-errored"),
    Row(OpOps, "G4", "`G2.updated(0, (1, SELF))` — 1 at cost 175: the only misfit is replaced before the rebuild, " +
      "so nothing that does not fit is stored. CONTROL for G3.",
      Left("0b28b1dc0c14addc0c1d10010201100102d901015895010086020402a7860204028202040086020402a7"), accept = true,
      "pair-map-lie-updated-accept"),
    Row(OpOps, "H1", "`Coll(1).flatMap({x => If(false, Coll[Box](SELF), Coll[GroupElement]())})` — 0 at cost 138: " +
      "flatMap copies item by item, and an EMPTY part of another class stores nothing.",
      Left("0b16b1dc0c0f10010201d9010104950100830163a7830007"), accept = true, "flatmap-empty-groupelement-part-accept"),
    Row(OpOps, "H2", "`Coll(1).flatMap({x => If(false, Coll[Byte] constant (1), ConcreteCollection[Long]())})` — 0 " +
      "at cost 138: an empty long[] part into a byte[] result is never read. The Append node refuses the same " +
      "pair of arrays (Coll.append_node_array_class P1). SEPARATING: an implementation that reads each part as " +
      "the result's item type before looking at its items refuses.",
      Left("0b15b1dc0c0f10010201d90101049501000e0101830005"), accept = true, "flatmap-empty-long-part-into-bytes-accept"),
    Row(OpOps, "H4", "`Coll(1).flatMap({x => If(false, Coll[Byte] constant (1), ConcreteCollection[Long](1L))})` — " +
      "THROWS `ClassCastException: Long cannot be cast to Byte` (in an InvocationTargetException): a Long item " +
      "into the byte[] result unboxes. CONTROL for H2: a part with an item is checked.",
      Left("0b17b1dc0c0f10010201d90101049501000e01018301050502"), accept = false, "flatmap-long-item-into-bytes-errored"),

    // ── the runtime array class ──
    Row(OpRuntime, "R1", "`Coll[Box]() ++ Coll[GroupElement](g)` — 1 at cost 86: `append` with an EMPTY " +
      "receiver returns `other` unchanged (CollsOverArrays.scala:49), so the value is a Coll[Box] over a " +
      "GroupElement[], with no lie anywhere in the tree. The setup of R2.",
      Right(() => size(emptyBoxesPlusG)), accept = true, "empty-box-coll-append-groupelement-accept"),
    Row(OpRuntime, "R2", "`(Coll[Box]() ++ Coll[GroupElement](g)).updated(0, SELF)` — THROWS `ArrayStoreException: " +
      "sigma.data.CBox` (in an InvocationTargetException): a WELL-TYPED item, a Box into a Coll[Box], is refused " +
      "because the store goes into the RUNTIME array, a GroupElement[]. SEPARATING: an implementation that checks " +
      "the item against the static item type, or does not check, returns 1.",
      Right(() => size(updated(emptyBoxesPlusG, S))), accept = false, "groupelement-backed-box-coll-box-item-errored"),
    Row(OpRuntime, "R3", "`If(false, Coll(1), Coll(1L)).updated(0, 5)` — THROWS `ClassCastException: Integer cannot " +
      "be cast to Long` (in an InvocationTargetException): an Int into a Coll[Int] whose runtime array is long[]. " +
      "SEPARATING, as R2.",
      Right(() => size(updated(lie, any(IntConstant(5))))), accept = false, "long-backed-int-coll-int-item-errored"),
    Row(OpRuntime, "R4", "`If(false, Coll(1), Coll(1L)).reverse` — 1 at cost 80: `reverse` stores no new item. " +
      "CONTROL for R3.",
      Right(() => size(mcall(30, lie))), accept = true, "long-backed-int-coll-reverse-accept"),

    // ── the Append node ──
    Row(OpAppend, "P1", "`Coll[Int] constant (1,2,3) ++ ConcreteCollection[Long]()` — THROWS `ArrayStoreException: " +
      "arraycopy: type mismatch: can not copy long[] into int[]`, though the operand is EMPTY: the node " +
      "`System.arraycopy`s each operand into an array of the receiver's class (CollectionUtil.scala:34-42), which " +
      "refuses mismatched primitive arrays before it looks at the length. `patch` RETURNS on the same shape " +
      "(Coll.reference_store_ops B8). SEPARATING: an implementation that checks items, or skips an empty operand, " +
      "returns 3.",
      Right(() => size(any(Append(coll(any(IntArrayConstant(Array(1, 2, 3)))), coll(cc(SLong)))))), accept = false,
      "int-coll-append-empty-long-errored"),
    Row(OpAppend, "P2", "`Coll[Int] constant (1,2,3) ++ ConcreteCollection[Int](7)` — 4 at cost 66. CONTROL for P1.",
      Right(() => size(any(Append(coll(any(IntArrayConstant(Array(1, 2, 3)))), coll(cc(SInt, any(IntConstant(7)))))))),
      accept = true, "int-coll-append-int-accept"),
    Row(OpAppend, "P3", "`Coll[Box](SELF) ++ Coll[GroupElement](g)` — THROWS `ArrayStoreException: arraycopy: type " +
      "mismatch` (GroupElement[] into Box[]). SEPARATING.",
      Right(() => size(any(Append(coll(cc(SBox, S)), coll(cc(SGroupElement, G)))))), accept = false,
      "box-coll-append-groupelement-errored"),
    Row(OpAppend, "P4", "`Coll[Box](SELF) ++ Coll[GroupElement]()` — 1 at cost 86: between two REFERENCE arrays " +
      "`arraycopy` checks element by element, so an empty operand passes, unlike P1's primitive arrays.",
      Right(() => size(any(Append(coll(cc(SBox, S)), coll(cc(SGroupElement)))))), accept = true,
      "box-coll-append-empty-groupelement-accept"))

  def extract(): Map[String, Json] =
    Seq(OpOps, OpRuntime, OpAppend).map { op =>
      val entries = Rows.filter(_.op == op).zipWithIndex.map { case (r, i) =>
        val name = s"${r.slug}#$i"
        if (r.accept) SpecExtract.authoredEntryV(op, s"[${r.id}] ${r.script}", r.treeHex, name, input, V3, ergoTree = 3)
        else SpecExtract.authoredRejectEntryV(op, s"[${r.id}] ${r.script}", r.treeHex, name, input, V3, ergoTree = 3)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalReferenceStore", extract(), outDir)
}
