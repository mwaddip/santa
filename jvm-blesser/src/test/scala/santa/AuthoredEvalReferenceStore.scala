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
//    Appended from the third probe (santa-probe-reference-store-3, spike ReferenceStoreProbe3; ids carry a `3.`,
//    since A2/X4/X5 are probe 1's): a Coll[Unit]. Scala's `ArrayBuilder.ofUnit` `++=` only COUNTS what it is
//    given, so `patch` and `flatMap` (builder `++=`) store anything into a Coll[Unit] as units, while every
//    single store is checked (`updated`, `updateMany`, `map`'s per-item `+=`, PairOfCols' `new Array[Unit]`, the
//    Append node). A call whose receiver and argument disagree on the item type stays unspecialised (`Coll[IV]`),
//    so `== Coll[Unit]` over it is refused at PARSE (3.R7; 3.R7m asks the same through an identity map); a
//    type-variable result throws when it is resolved, before anything is stored; a `Coll[Unit]` CONSTANT is
//    refused at parse by rule 1020 (hand-built: our writer refuses it). Every row that errors has a PARSE TWIN
//    (`if (true) true else { val v = <expr>; false }`, as MethodCall.unspecialised_call_names_v6): true where the
//    JVM parses the expression, errored where it refuses it at parse, which the bare row grades alike.
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
// Every root is a SizeOf, so every value is an Int, except probe 3's three EQs (Boolean) and the parse twins (an
// If); every tree ignores its input (Int 0 at var 1).

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

  private val Base: Seq[Row] = Seq(
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

  // ── probe 3 (santa-probe-reference-store-3): a Coll[Unit], a type-variable result, map with two lambdas ──
  /** The probe-3 rows the JVM refuses at PARSE (their parse twins are refused too). */
  val ParseRefused3: Set[String] = Set("3.R7", "3.X1h", "3.X1z", "3.X2h")
  private val unitBuilder = "a `Unit` array builder's `++=` only COUNTS what it is given (scala-library 2.12.20 " +
    "`ArrayBuilder.ofUnit`: `size += xs.size`) and fills the result with units, so the item is never looked at"
  private val sepUnit = "SEPARATING: an implementation that checks the item against the collection's item type refuses."
  private val sepStore = "SEPARATING: an implementation that does not check the store returns."
  private val toUnit = "`ClassCastException: java.lang.Integer cannot be cast to scala.runtime.BoxedUnit`"
  private val rule1020 = "REFUSED AT PARSE by rule 1020 `CheckZeroWidthCollection` (`ValidationException` <- " +
    "`SerializerException: Collection with zero-width element type SUnit cannot be deserialized`; " +
    "ValidationRules.scala:199-210, from CoreDataSerializer.deserializeColl, v6.0.7)"

  private val Probe3: Seq[Row] = Seq(
    Row(OpOps, "3.R1", "`ConcreteCollection[Unit](()).patch(0, Coll[Int] constant (1), 0)` — 2 at cost 90: `patch` " +
      "goes through Scala's builder (CollsOverArrays.scala:94-98), and " + unitBuilder + ": the Int becomes a unit " +
      "(3.R7m). The receiver and the patch disagree on the item type, so the call stays unspecialised (`Coll[IV]`); " +
      "a size consumes it. " + sepUnit,
      Left("0b10b1dc0c13830162620304001001020400"), accept = true, "unit-coll-int-patch-accept"),
    Row(OpOps, "3.R3", "`ConcreteCollection[Unit](()).patch(0, Coll[Box](SELF), 0)` — 2 at cost 115: as 3.R1, with " +
      "a Box. " + sepUnit,
      Left("0b11b1dc0c1383016262030400830163a70400"), accept = true, "unit-coll-box-patch-accept"),
    Row(OpOps, "3.R2", "`Coll(1).flatMap({x => If(false, ConcreteCollection[Unit](()), Coll[Int] constant (1))})` — " +
      "1 at cost 123: flatMap adds each part with `++=` to a builder of the result's item class " +
      "(CollsOverArrays.scala:70-73), and " + unitBuilder + " (3.R9). " + sepUnit,
      Left("0b16b1dc0c0f10010201d901010495010083016262100102"), accept = true, "flatmap-int-part-into-unit-accept"),
    Row(OpOps, "3.Q1", "`Coll(1).zip(ConcreteCollection[Unit](())).patch(0, Coll(1).zip(Coll(7)), 0)` — 2 at cost " +
      "130: a PairOfCols unzips its patch and patches each side (CollsOverArrays.scala:423-428), and the Unit " +
      "side's builder counts the 7. " + sepUnit,
      Left("0b1eb1dc0c13dc0c1d1001020183016262030400dc0c1d1001020110010e0400"), accept = true,
      "pair-unit-side-int-patch-accept"),
    Row(OpOps, "3.R4", "`ConcreteCollection[Unit](()).updated(0, 1)` — THROWS " + toUnit + " (in an " +
      "InvocationTargetException): a single store is checked, unlike 3.R1's `++=`. " + sepStore,
      Left("0b0db1dc0c14830162620204000402"), accept = false, "unit-coll-int-updated-errored"),
    Row(OpOps, "3.R5", "`ConcreteCollection[Unit](()).updateMany(Coll(0), Coll[Int] constant (1))` — " +
      throwsItem("java.lang.Integer") + " `updateMany` stores into `toArray.clone()`, a `BoxedUnit[]`. " + sepStore,
      Left("0b0fb1dc0c158301626202100100100102"), accept = false, "unit-coll-int-updatemany-errored"),
    Row(OpOps, "3.R6", "`ConcreteCollection[Unit](()) ++ Coll[Box](SELF)` — THROWS `ArrayStoreException: arraycopy: " +
      "type mismatch: can not copy sigma.Box[] into scala.runtime.BoxedUnit[]`, UNWRAPPED: the Append node copies " +
      "the other's array into one of the receiver's class (Coll.append_node_array_class). " + sepStore,
      Left("0b0ab1b383016262830163a7"), accept = false, "unit-coll-append-box-errored"),
    Row(OpOps, "3.R7", "`3.R1 == ConcreteCollection[Unit]((), ())` — REFUSED AT PARSE: `ConstraintFailed` " +
      "(`Constraints$SameType2`, on the EQ). 3.R1's `patch` stays unspecialised, typed `Coll[IV]`, and the EQ " +
      "refuses `Coll[IV]` against `Coll[Unit]`; 3.R7m asks the same question through an identity map. SEPARATING: " +
      "an implementation that specialises the call builds the tree (and returns true, or throws only at " +
      "evaluation, which the parse twin catches).",
      Left("0b1593dc0c138301626203040010010204008302626262"), accept = false, "unit-patch-eq-refused-at-parse"),
    Row(OpOps, "3.R9", "`3.R2 == ConcreteCollection[Unit](())` — true at cost 147: what flatMap stored is a unit. " +
      "SEPARATING: an implementation that stores the Int returns false.",
      Left("0b1a93dc0c0f10010201d90101049501008301626210010283016262"), accept = true, "flatmap-unit-result-eq-accept"),
    Row(OpOps, "3.R8", "`Coll(1).flatMap({x => If(false, Coll[T](), Coll[Int] constant (1))})` — THROWS " +
      "`RuntimeException: Don't know how to convert SType OV to RType` (in an InvocationTargetException): the " +
      "lambda returns `Coll[T]`, a type VARIABLE, so the call stays unspecialised (`=> Coll[OV]`) and flatMap's own " +
      "`OV` reaches `stypeToRType` when the result type is resolved, before anything is stored. SEPARATING: an " +
      "implementation that does not resolve the result type returns 1.",
      Left("0b17b1dc0c0f10010201d90101049501008300670154100102"), accept = false, "flatmap-type-variable-result-errored"),
    Row(OpOps, "3.R8e", "`Coll(1).flatMap({x => If(false, Coll[T](), Coll[Int] constant ())})` — THROWS the same: " +
      "with nothing to store, the result type is still resolved. SEPARATING: an implementation that resolves it " +
      "only when it stores returns 0.",
      Left("0b16b1dc0c0f10010201d901010495010083006701541000"), accept = false,
      "flatmap-type-variable-result-empty-errored"),
    Row(OpOps, "3.A2", "`3.R8 ++ Coll[Box](SELF)` — THROWS the same, from the inner flatMap: the Append is never " +
      "reached. SEPARATING: an implementation that evaluates the inner call returns 2.",
      Left("0b1cb1b3dc0c0f10010201d90101049501008300670154100102830163a7"), accept = false,
      "flatmap-type-variable-result-append-errored"),
    Row(OpOps, "3.P1", "`Coll(1).zip(Coll(1)).map(If(false, {p => SELF}, {p => groupGenerator}))` — THROWS " +
      "`ArrayStoreException: sigma.data.CGroupElement`, UNWRAPPED: the taken lambda's body is a GroupElement and " +
      "passes the closure's own check (it is typed GroupElement); the PairOfCols `map` stores it into " +
      "`new Array[V]` of the map's result type, a `Box[]` (CollsOverArrays.scala:317-323). " + sepStore,
      Left("0b19b1addc0c1d10010201100102950100d9010158a7d901015882"), accept = false,
      "pair-map-two-lambdas-groupelement-errored"),
    Row(OpOps, "3.P2", "`Coll(1).map(If(false, {x => SELF}, {x => groupGenerator}))` — THROWS the same, UNWRAPPED, " +
      "on a plain receiver: `map` stores each item through a `Box` builder (CollsOverArrays.scala:28-31). " + sepStore,
      Left("0b12b1ad100102950100d9010104a7d901010482"), accept = false, "map-two-lambdas-groupelement-errored"),
    Row(OpOps, "3.P3", "`Coll(1).zip(Coll(1)).map(If(false, {p => (1, SELF)}, {p => (1, groupGenerator)}))` — 1 at " +
      "cost 115: the result type is a pair, its array a `Tuple2[]`, and any pair fits. CONTROL for 3.P4.",
      Left("0b21b1addc0c1d10010201100102950100d901015886020402a7d90101588602040282"), accept = true,
      "pair-map-two-lambdas-pair-accept"),
    Row(OpOps, "3.P4", "`Coll(1).zip(Coll(1)).map(If(false, {p => (1, SELF)}, {p => groupGenerator}))` — THROWS " +
      "`ArrayStoreException: sigma.data.CGroupElement`, UNWRAPPED: a non-pair into the `Tuple2[]`. " + sepStore,
      Left("0b1db1addc0c1d10010201100102950100d901015886020402a7d901015882"), accept = false,
      "pair-map-two-lambdas-nonpair-errored"),
    Row(OpOps, "3.R7m", "`3.R1's patch.map({(x: Unit) => x}) == ConcreteCollection[Unit]((), ())` — true at cost " +
      "167: 3.R7's question in a formable tree (the identity map's type comes from its lambda): the patch stored " +
      "units. SEPARATING: an implementation that stores the Int returns false.",
      Left("0b1c93addc0c13830162620304001001020400d901016272018302626262"), accept = true,
      "unit-patch-identity-map-eq-accept"),
    Row(OpOps, "3.X1h", "`SizeOf(Coll[Unit] constant, length 2)`, hand-built (type `0c62`, length `02`, no item " +
      "bytes; the JVM's writer refuses to emit it) — " + rule1020 + ". The tree-constant form of what the wire tier " +
      "pins in registers and extensions ({Box,Transaction}.zero_width_collection). SEPARATING: an implementation " +
      "without the rule returns 2.",
      Left("0b04b10c6202"), accept = false, "unit-coll-constant-refused-at-parse"),
    Row(OpOps, "3.X1z", "`SizeOf(Coll[Unit] constant, length 0)`, hand-built — " + rule1020 + ": the rule does not " +
      "look at the length. SEPARATING: an implementation without the rule returns 0.",
      Left("0b04b10c6200"), accept = false, "empty-unit-coll-constant-refused-at-parse"),
    Row(OpOps, "3.X2h", "`Coll[Unit] constant (length 2).patch(0, Coll[Int] constant (1), 0)`, hand-built — " +
      rule1020 + ": 3.R1 from a constant receiver. SEPARATING: an implementation without the rule evaluates it.",
      Left("0b0fb1dc0c130c62020304001001020400"), accept = false, "unit-coll-constant-patch-refused-at-parse"),
    Row(OpOps, "3.X4", "`ConcreteCollection[Unit](If(false, (), 1))` — THROWS `InterpreterException: Invalid type " +
      "returned by evaluator … expected type: SUnit, resulting value: 1`, before any store: ConcreteCollection " +
      "checks each item's value against its declared type, so a lie cannot enter a Coll[Unit] this way. " + sepStore,
      Left("0b0ab1830162950100620402"), accept = false, "unit-concrete-collection-int-item-errored"),
    Row(OpOps, "3.X11", "`Coll(1).map({x => ()})` — 1 at cost 75. CONTROL for 3.X5.",
      Left("0b0cb1ad8301040402d901010462"), accept = true, "map-to-unit-accept"),
    Row(OpOps, "3.X5", "`Coll(1).map(If(false, {x => ()}, {x => 1}))` — THROWS " + toUnit + ", UNWRAPPED: `map` " +
      "over a plain receiver adds one item at a time, and `ArrayBuilder.ofUnit`'s `+=` takes a `BoxedUnit`, so the " +
      "item is cast; `patch`'s and `flatMap`'s `++=` never look (3.R1, 3.R2). " + sepStore,
      Left("0b15b1ad8301040402950100d901010462d90101040402"), accept = false, "map-two-lambdas-int-into-unit-errored"),
    Row(OpOps, "3.X6", "`Coll(1).zip(Coll(1)).map(If(false, {p => ()}, {p => 1}))` — THROWS `ArrayStoreException: " +
      "java.lang.Integer`, UNWRAPPED: a PairOfCols' `map` stores into `new Array[Unit]`, a `BoxedUnit[]` " +
      "(CollsOverArrays.scala:317-323). " + sepStore,
      Left("0b1eb1addc0c1d8301040402018301040402950100d901015862d90101580402"), accept = false,
      "pair-map-two-lambdas-int-into-unit-errored"),
    Row(OpOps, "3.X7", "`Coll(1).zip(ConcreteCollection[Unit](())).updateMany(Coll(0), Coll(1).zip(Coll(7)))` — " +
      throwsItem("java.lang.Integer") + " A Coll[Unit] as a pair's side is checked by `updateMany` (the side's " +
      "`toArray.clone()`). " + sepStore,
      Left("0b25b1dc0c15dc0c1d83010404020183016262028301040400dc0c1d830104040201830104040e"), accept = false,
      "pair-unit-side-int-updatemany-errored"),
    Row(OpOps, "3.X8", "`Coll(1).zip(ConcreteCollection[Unit](())).updated(0, (1, 7))` — THROWS " + toUnit + " (in an " +
      "InvocationTargetException): per side, as 3.R4. " + sepStore,
      Left("0b1ab1dc0c14dc0c1d8301040402018301626202040086020402040e"), accept = false,
      "pair-unit-side-int-updated-errored"),
    Row(OpOps, "3.X9", "`Coll(1).zip(Coll(1)).flatMap({p => If(false, ConcreteCollection[Unit](()), Coll[Int] " +
      "constant (1))})` — 1 at cost 183: a PairOfCols' `flatMap` goes through the same builder `++=` " +
      "(CollsOverArrays.scala:410-411), lenient as 3.R2. " + sepUnit,
      Left("0b21b1dc0c0fdc0c1d830104040201830104040201d901015895010083016262100102"), accept = true,
      "pair-flatmap-int-part-into-unit-accept"),
    Row(OpOps, "3.X10", "`ConcreteCollection[Unit](()).append(Coll[Int] constant (1))`, the METHOD (id 9) — THROWS " +
      "`RuntimeException: Cannot find eval method def append_eval` <- `NoSuchMethodException`: `append` as a method " +
      "call has no eval method at 6.0.7, for any receiver (Coll.methodcall_v5_ids #9); 3.R6, the node, is the only " +
      "append. SEPARATING: an implementation that evaluates the method returns 2.",
      Left("0b0cb1dc0c098301626201100102"), accept = false, "unit-coll-append-method-errored"),
    Row(OpOps, "3.X12", "`Coll(1).map({x => Coll[T]()})` — THROWS `RuntimeException: Don't know how to convert SType " +
      "T to RType`, UNWRAPPED: the map node resolves its result's item type. SEPARATING: an implementation that " +
      "never resolves it returns 1.",
      Left("0b10b1ad8301040402d90101048300670154"), accept = false, "map-type-variable-result-errored"),
    Row(OpOps, "3.X13", "`Coll[T]()` — THROWS the same, UNWRAPPED: a bare ConcreteCollection resolves its item type. " +
      "SEPARATING: an implementation that never resolves it returns 0.",
      Left("0b06b18300670154"), accept = false, "type-variable-concrete-collection-errored"))

  /** A tree's root expression: the bytes after the header and the VLQ size (every tree here is `0b`-headed). */
  private def body(treeHex: String): String = {
    val bs = Base16.decode(treeHex).get
    require(bs(0) == 0x0b, s"not a sized v3 tree: $treeHex")
    var i = 1
    while ((bs(i) & 0x80) != 0) i += 1
    hx(bs.drop(i + 1))
  }
  /** `if (true) true else { val v100 = expr; false }`: the expression's PARSE outcome alone. */
  def wrap(expr: String): String = { val b = "950101" + "0101" + "d801d664" + expr + "0100"; "0b" + vlq(b.length / 2) + b }

  private val Probe3Twins: Seq[Row] = Probe3.filterNot(_.accept).map { r =>
    val refused = ParseRefused3(r.id)
    val why =
      if (refused) "The JVM refuses it at PARSE, as the bare row. SEPARATING: an implementation that builds it and " +
        "throws only at evaluation is green on the bare row (the eval tier grades the two alike) but returns true here."
      else "The JVM PARSES it and throws only when it is evaluated (the bare row). SEPARATING: an implementation that " +
        "refuses it at parse is green on the bare row (the eval tier grades the two alike) but errors here."
    Row(OpOps, s"${r.id}|parse", s"`${r.id}` inside `if (true) true else { val v = …; false }`, its parse outcome " +
      s"alone. $why", Left(wrap(body(r.treeHex))), accept = !refused, s"parse-${r.slug.stripSuffix("-errored")}")
  }

  val Rows: Seq[Row] = Base ++ Probe3 ++ Probe3Twins

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
