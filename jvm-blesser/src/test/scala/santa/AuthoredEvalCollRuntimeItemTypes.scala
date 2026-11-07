package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored eval vectors for sigma-rust's `santa-probe-coll-runtime-array-ops` (2026-11-07):
// `flatMap`'s element-type gate, and the four ops whose JVM verdict hangs on how
// `CollOverArray` actually stores things. MEASURED, not read — the probe asked for that
// explicitly, and the answers are not uniform.
//
// The building blocks, same If trick as `Value.checkType_sites` (an If is typed by its TRUE
// branch and no builder checks the pair):
//
//   X = if (false) Coll(1, 2) else Coll(1L, 2L)   declared Coll[Int], holds LONGS
//   E = if (false) Coll(1)    else Coll[Long]()   declared Coll[Int], holds an EMPTY Coll[Long]
//
// FOUR OPS, FOUR DIFFERENT ANSWERS:
//
//   append      throws ArrayStoreException on primitive width mismatch — EXCEPT when the LEFT
//               side is empty, where `CollOverArray.append` returns the right side as it is.
//               An empty RIGHT side does NOT save it: System.arraycopy checks element types
//               before length, so copying a zero-length long[] into an int[] still throws.
//               Reference items ACCEPT when the classes are compatible (Coll[] into Coll[]) and
//               throw when they are not (Box[] into GroupElement[]). Pairs unzip through
//               PairOfCols and throw on the underlying primitive arrays.
//   updateMany  throws, both when the receiver lies and when the VALUES argument lies.
//   startsWith  ACCEPTS everything, and answers TRUE across item types — because the comparison
//   endsWith    reaches Scala's `==` on boxed numerics, where 1L == 1. So a Coll[Long] does start
//               with a Coll[Int] of equal values. An implementation refusing these OVER-REJECTS.
//   flatMap     no element-type check at all; only the lambda's shallow argument check, so a
//               Coll[Long] element satisfies a declared Coll[Int]. A plain Long element does not
//               satisfy it (a Long is no Coll) and that one rejects.
//
// The append and startsWith/endsWith families together are the useful pair: the same mismatch is
// fatal to one and invisible to the other, so an implementation cannot gate "mismatched item
// types" uniformly and match the JVM.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.ast._

object AuthoredEvalCollRuntimeItemTypes {

  val V3: Byte = VersionContext.V6SoftForkVersion
  val OpFlatMap  = "Coll.flatMap_element_type"
  val OpAppend   = "Coll.append_runtime_item_type"
  val OpUpdate   = "Coll.updateMany_runtime_item_type"
  val OpPrefix   = "Coll.prefix_suffix_runtime_item_type"
  val Source     = "santa:authored-eval-coll-runtime-item-types"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def m(name: String): SMethod = SCollectionMethods.getMethodByName(name)
  private def mc(obj: Value[_ <: SType], name: String, args: Value[SType]*): Value[SType] =
    any(MethodCall(any(obj), m(name), args.toIndexedSeq, Map.empty))

  private def ints(ns: Int*): Value[SCollection[SInt.type]] =
    ConcreteCollection(ns.map(IntConstant(_)).toIndexedSeq, SInt)
  private def longs(ns: Long*): Value[SCollection[SLong.type]] =
    ConcreteCollection(ns.map(LongConstant(_)).toIndexedSeq, SLong)

  private def X: Value[SCollection[SInt.type]] =
    If(FalseLeaf, ints(1, 2), longs(1L, 2L).asInstanceOf[Value[SCollection[SInt.type]]])
  private def E: Value[SCollection[SInt.type]] =
    If(FalseLeaf, ints(1), longs().asInstanceOf[Value[SCollection[SInt.type]]])

  private val cInt  = SCollection(SInt).asInstanceOf[SType]
  private val cLong = SCollection(SLong).asInstanceOf[SType]
  private val pairI = STuple(SInt, SInt)
  private val pairL = STuple(SLong, SLong)

  private def collOf(elem: SType, items: Value[SType]*): Value[SType] =
    any(ConcreteCollection(items.toIndexedSeq, elem))

  /** Declared Coll[Coll[Int]], holds a Coll[Coll[Long]] — REFERENCE items. */
  private def nestedLie: Value[SType] =
    any(If(FalseLeaf, collOf(cInt, any(ints(1))), collOf(cLong, any(longs(2L)))))
  /** Declared Coll[Coll[Int]] but holds plain Longs — not collections at all. */
  private def notACollLie: Value[SType] =
    any(If(FalseLeaf, collOf(cInt, any(ints(1))), collOf(SInt, any(LongConstant(2L)))))
  /** Declared Coll[Coll[Int]], holds an EMPTY Coll[Coll[Long]]. */
  private def nestedEmpty: Value[SType] =
    any(If(FalseLeaf, collOf(cInt, any(ints(1))), collOf(cLong)))
  /** Declared Coll[Box], holds a Coll[GroupElement] — unrelated runtime classes. */
  private def boxGeLie: Value[SType] =
    any(If(FalseLeaf, collOf(SBox, any(Self)), collOf(SGroupElement, any(GroupGenerator))))

  private def pairColl(t: STuple, a: Long, b: Long): Value[SType] = {
    def n(v: Long): Value[SType] =
      if (t == pairI) any(IntConstant(v.toInt)) else any(LongConstant(v))
    collOf(t.asInstanceOf[SType], any(Tuple(IndexedSeq(n(a), n(b)))))
  }
  private def pairLie: Value[SType] =
    any(If(FalseLeaf, pairColl(pairI, 1, 1), pairColl(pairL, 1, 1)))

  /** `x => x.indices` — flatMap requires a property-call lambda. */
  private def indicesLam: Value[SType] =
    any(FuncValue(IndexedSeq(1 -> cInt), mc(ValUse(1, cInt), "indices")))

  private def app(a: Value[SType], b: Value[SType]): Value[SType] =
    any(Append(a.asInstanceOf[Value[SCollection[SType]]], b.asInstanceOf[Value[SCollection[SType]]]))

  private def treeHex(root: Value[SType]): String = VersionContext.withVersions(V3, V3) {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
    val tree = new ErgoTree(header, IndexedSeq.empty,
      Right(root.asInstanceOf[sigma.ast.syntax.SigmaPropValue]))
    Base16.encode(sigma.serialization.ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(tree))
  }

  /** The v6 SCollection methods are only visible to getMethodByName inside a v6 context. */
  private def inV6[A](f: => A): A = VersionContext.withVersions(V3, V3)(f)

  private def accept(op: String, script: String, why: String, root: => Value[SType], name: String): Json =
    SpecExtract.authoredEntryV(op, s"$script  // JVM: $why", treeHex(inV6(root)), name, input, V3, 3)
  private def reject(op: String, script: String, why: String, root: => Value[SType], name: String): Json =
    SpecExtract.authoredRejectEntryV(op, s"$script  // JVM: $why", treeHex(inV6(root)), name, input, V3, 3)

  private val Shallow = "the lambda's argument check is SHALLOW (isValueOfType asks only " +
    "isInstanceOf[Coll[_]] for a collection, SType.scala:198), and flatMap_eval reads no element " +
    "type of its own"

  def extract(): Map[String, Json] = Map(
    OpFlatMap -> SpecExtract.authoredEnvelope(OpFlatMap, Seq(
      accept(OpFlatMap, "{ If(false, Coll(Coll(1)), Coll(Coll(2L))).flatMap({ (x: Coll[Int]) => x.indices }) }",
        s"Coll(0). A Coll[Long] element satisfies the declared Coll[Int] because $Shallow. " +
        "An implementation gating flatMap on the element type rejects this instead",
        mc(nestedLie, "flatMap", indicesLam), "flatmap-nested-lie-accept#0"),
      accept(OpFlatMap, "{ If(false, Coll(Coll(1)), Coll[Coll[Long]]()).flatMap({ (x: Coll[Int]) => x.indices }) }",
        "an empty Coll — no element, so the lambda never runs and no check of any kind fires",
        mc(nestedEmpty, "flatMap", indicesLam), "flatmap-empty-accept#1"),
      reject(OpFlatMap, "{ If(false, Coll(Coll(1)), Coll(2L)).flatMap({ (x: Coll[Int]) => x.indices }) }",
        "REJECTS. Here the element is a plain Long, and a Long is no Coll — the shallow check does " +
        "stop this one. So flatMap's gate is not absent, it is just shallower than the node's " +
        "declared element type",
        mc(notACollLie, "flatMap", indicesLam), "flatmap-long-element-errored#2")), Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))),

    OpAppend -> SpecExtract.authoredEnvelope(OpAppend, Seq(
      reject(OpAppend, "{ X.append(Coll(3)) }",
        "REJECTS, ArrayStoreException \"can not copy int[] into long[]\": concatArrays allocates by " +
        "the LEFT's ClassTag (long[], from X's runtime items) and System.arraycopy refuses the " +
        "int[] right side (CollectionUtil.scala:34-42)",
        app(any(X), any(ints(3))), "append-left-lies-errored#0"),
      reject(OpAppend, "{ Coll(3).append(X) }",
        "REJECTS, ArrayStoreException \"can not copy long[] into int[]\" — the mirror: allocation " +
        "follows the left (int[]) and the long[] right side will not copy in",
        app(any(ints(3)), any(X)), "append-right-lies-errored#1"),
      accept(OpAppend, "{ E.append(Coll(3)) }",
        "ACCEPTS: Coll[Int](3). An EMPTY left side short-circuits — CollOverArray.append returns " +
        "the right side as it is, with its own item type, and never allocates " +
        "(CollsOverArrays.scala:48-57). The one row of this family that survives a mismatch",
        app(any(E), any(ints(3))), "append-empty-left-accept#2"),
      reject(OpAppend, "{ Coll(3).append(E) }",
        "REJECTS, ArrayStoreException \"can not copy long[] into int[]\" — an empty RIGHT side does " +
        "NOT short-circuit. System.arraycopy checks element types before length, so a ZERO-LENGTH " +
        "long[] still refuses to copy into an int[]. This is the asymmetry worth knowing: empty on " +
        "the left saves the call, empty on the right does not",
        app(any(ints(3)), any(E)), "append-empty-right-errored#3"),
      accept(OpAppend, "{ If(false, Coll(Coll(1)), Coll(Coll(2L))).append(Coll(Coll(3))) }",
        "ACCEPTS, and the result is a Coll[Coll[LONG]] — the left's RUNTIME item type, not the " +
        "node's declared Coll[Coll[Int]]. REFERENCE items allocate as Coll[] and a Coll[] copies " +
        "into a Coll[] whatever its own element type, so nothing throws",
        app(nestedLie, collOf(cInt, any(ints(3)))), "append-reference-items-accept#4"),
      reject(OpAppend, "{ If(false, Coll(SELF), Coll(groupGenerator)).append(Coll(SELF)) }",
        "REJECTS, ArrayStoreException \"can not copy sigma.Box[] into sigma.GroupElement[]\". " +
        "Reference items accept only while the classes are compatible; UNRELATED classes throw, so " +
        "#4's acceptance is about Coll[]-into-Coll[] and not about references in general",
        app(boxGeLie, collOf(SBox, any(Self))), "append-unrelated-classes-errored#5"),
      reject(OpAppend, "{ If(false, Coll((1, 1)), Coll((1L, 1L))).append(Coll((2, 2))) }",
        "REJECTS, ArrayStoreException \"can not copy int[] into long[]\": a pair collection is a " +
        "PairOfCols, so append unzips it (CollsOverArrays.scala:379-382) and the clash happens on " +
        "the underlying PRIMITIVE arrays",
        app(pairLie, pairColl(pairI, 2, 2)), "append-pair-items-errored#6")), Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))),

    OpUpdate -> SpecExtract.authoredEnvelope(OpUpdate, Seq(
      reject(OpUpdate, "{ X.updateMany(Coll(0), Coll(5)) }",
        "REJECTS. updateMany clones the receiver's array and assigns each value into it " +
        "(CollsOverArrays.scala:106-117), so an Int assigned into a long[] clone throws. The " +
        "exception surfaces wrapped as an InvocationTargetException, the method being reflective",
        mc(X, "updateMany", any(ints(0)), any(ints(5))), "updatemany-receiver-lies-errored#0"),
      reject(OpUpdate, "{ Coll(1, 2).updateMany(Coll(0), If(false, Coll(5), Coll(5L))) }",
        "REJECTS too, with the lie moved to the VALUES argument instead of the receiver: an honest " +
        "int[] receiver and a Long value. Both directions throw, unlike append, which has an " +
        "empty-left escape",
        mc(ints(1, 2), "updateMany", any(ints(0)),
          any(If(FalseLeaf, ints(5), longs(5L).asInstanceOf[Value[SCollection[SInt.type]]]))),
        "updatemany-values-lie-errored#1")), Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))),

    OpPrefix -> SpecExtract.authoredEnvelope(OpPrefix, Seq(
      accept(OpPrefix, "{ X.startsWith(Coll(1)) }",
        "TRUE — and this is the answer the probe could not read off the source. The comparison " +
        "reaches Scala's `==` on boxed numerics, where 1L == 1, so a Coll[LONG] does start with a " +
        "Coll[INT] of equal values. No throw, no false: an implementation that refuses a mismatched " +
        "item type here OVER-REJECTS",
        mc(X, "startsWith", any(ints(1))), "startswith-left-lies-true#0"),
      accept(OpPrefix, "{ X.endsWith(Coll(2)) }",
        "TRUE, the endsWith twin of #0 — same numeric-equality comparison",
        mc(X, "endsWith", any(ints(2))), "endswith-left-lies-true#1"),
      accept(OpPrefix, "{ Coll(1, 2).startsWith(If(false, Coll(1), Coll(1L))) }",
        "TRUE with the lie on the ARGUMENT instead of the receiver — the comparison is symmetric",
        mc(ints(1, 2), "startsWith",
          any(If(FalseLeaf, ints(1), longs(1L).asInstanceOf[Value[SCollection[SInt.type]]]))),
        "startswith-arg-lies-true#2"),
      accept(OpPrefix, "{ E.startsWith(Coll(1)) }",
        "FALSE — an empty receiver cannot start with a one-element prefix. False for the LENGTH, " +
        "not for the item type: the type mismatch is still invisible here",
        mc(E, "startsWith", any(ints(1))), "startswith-empty-receiver-false#3"),
      accept(OpPrefix, "{ E.endsWith(Coll(1)) }",
        "FALSE, the endsWith twin of #3",
        mc(E, "endsWith", any(ints(1))), "endswith-empty-receiver-false#4"),
      accept(OpPrefix, "{ X.startsWith(Coll[Int]()) }",
        "TRUE — every collection starts with the empty one, mismatched items or not",
        mc(X, "startsWith", any(ints())), "startswith-empty-arg-true#5"),
      accept(OpPrefix, "{ X.endsWith(Coll[Int]()) }",
        "TRUE, the endsWith twin of #5",
        mc(X, "endsWith", any(ints())), "endswith-empty-arg-true#6"),
      accept(OpPrefix, "{ E.startsWith(Coll[Int]()) }",
        "TRUE — empty starts with empty. The cheapest row of the family, and the one an " +
        "element-type gate would still reject",
        mc(E, "startsWith", any(ints())), "startswith-both-empty-true#7")), Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalCollRuntimeItemTypes", extract(), outDir)
}
