package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored eval vectors for sigma-rust's `santa-request-checktype-vectors-addendum-equality`
// (2026-11-07): two collections whose ITEM TYPES differ are unequal before any item is compared.
//
// WHAT THE JVM DOES. `DataValueComparer.equalDataValues`'s Coll case charges `MatchType` and then
// bails on `len != coll2.length || coll1.tItem != coll2.tItem` (`DataValueComparer.scala:318-329`,
// the guard at `:323` — it was `:318-326` in 6.0.6, so the block shifted by three lines in 6.0.7).
// The comparison never reaches `equalColls_Dispatch`.
//
// WHY THE LIE SURVIVES TO GET THERE. `Value.checkType` is SHALLOW: `isValueOfType` asks only
// `x.isInstanceOf[Coll[_]]` for any `SCollectionType` (`SType.scala:198`), so an `If` declared
// `Coll[Int]` that evaluates to a `Coll[Long]` passes every check site on the way up. That is what
// separates this family from `Value.checkType_sites` — there the shallowness is what lets the value
// through to the next check, here it is what lets the value through to the COMPARER, which is the
// one place that does look at the item type. See `AuthoredEvalCheckTypeGates` for the sites.
//
// Rust compares item types only when the left collection's items are a primitive, `Box`, `AvlTree`,
// `Header` or `PreHeader`; for tuples, nested collections, options and `SigmaProp` it compares the
// items one by one and never the item types — so two EMPTY collections of different composite item
// types come out equal. Entries #0 and #1 are the two that separates; #2 is the primitive control
// that is false on both.
//
// COSTS ARE ONLY COMPARABLE WITHIN A SHAPE. #3's tree has no `If` (it is a bare pair of empty
// constants), so its 51 is not the "match" counterpart of #0's 56. #7 is the same-shape honest twin
// of #0 and is the row to read the mismatch's cost against.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.ast._

object AuthoredEvalCollEqItemType {

  val V3: Byte = VersionContext.V6SoftForkVersion
  val Op     = "Coll.eq_item_type"
  val Source = "santa:authored-eval-coll-eq-item-type"

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]

  /** An EMPTY collection whose declared element type is `elem`. */
  private def emptyC(elem: SType): Value[SType] =
    ConcreteCollection(IndexedSeq.empty[Value[SType]], elem).asInstanceOf[Value[SType]]

  /** Declared `Coll[a]`, evaluating to an empty `Coll[b]` — the If lie, at collection level. */
  private def lie(a: SType, b: SType): Value[SType] =
    any(If(FalseLeaf, any(emptyC(a)), any(emptyC(b))))

  /** `if (false) Coll[Int](1) else Coll[Long](2L)` — declared Coll[Int], holds a Long. */
  private def CX: Value[SCollection[SInt.type]] =
    If(FalseLeaf,
      ConcreteCollection(IndexedSeq(IntConstant(1)), SInt),
      ConcreteCollection(IndexedSeq(LongConstant(2L)), SLong)
        .asInstanceOf[Value[SCollection[SInt.type]]])

  private val pairI = STuple(SInt, SInt)
  private val pairL = STuple(SLong, SLong)

  private def treeHex(root: Value[SType]): String = VersionContext.withVersions(V3, V3) {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
    val tree = new ErgoTree(header, IndexedSeq.empty,
      Right(root.asInstanceOf[sigma.ast.syntax.SigmaPropValue]))
    Base16.encode(sigma.serialization.ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(tree))
  }

  private def entry(script: String, why: String, root: Value[SType], name: String): Json =
    SpecExtract.authoredEntryV(Op, s"$script  // JVM: $why", treeHex(root), name, input, V3, ergoTree = 3)

  private val Guard = "equalDataValues charges MatchType and then returns false on an item-type " +
    "difference, before comparing any item (DataValueComparer.scala:323)"
  private val Shallow = "Value.checkType cannot stop the tree getting here: isValueOfType asks only " +
    "isInstanceOf[Coll[_]] for a collection (SType.scala:198), so the declared Coll[Int] is satisfied " +
    "by a Coll[Long]"

  def extract(): Map[String, Json] = {
    val entries = Seq(
      entry("{ If(false, Coll[(Int, Int)](), Coll[(Long, Long)]()) == Coll[(Int, Int)]() }",
        s"false. $Guard. Both sides are EMPTY, so an implementation that compares items one by one " +
        s"finds nothing to disagree about and answers true — PAIR items are one of the composite " +
        s"kinds sigma-rust does not item-type-check. $Shallow",
        any(EQ(lie(pairI, pairL), any(emptyC(pairI)))), "pair-items-mismatch-false#0"),
      entry("{ If(false, Coll[Coll[Int]](), Coll[Coll[Long]]()) == Coll[Coll[Int]]() }",
        s"false. $Guard. The NESTED-collection twin of #0, the other composite kind that goes " +
        s"item-by-item in sigma-rust. $Shallow",
        any(EQ(lie(SCollection(SInt), SCollection(SLong)), any(emptyC(SCollection(SInt))))),
        "nested-coll-mismatch-false#1"),
      entry("{ If(false, Coll[Int](), Coll[Long]()) == Coll[Int]() }",
        s"false. The PRIMITIVE-item control: $Guard, and sigma-rust does check item types for " +
        "primitives, so this row is false on both and is what rules the If shape out as the cause " +
        "of #0 and #1",
        any(EQ(lie(SInt, SLong), any(emptyC(SInt)))), "primitive-items-mismatch-false#2"),
      entry("{ Coll[(Int, Int)]() == Coll[(Int, Int)]() }",
        "true. The same-item-type control — two empty pair collections ARE equal, so #0's false is " +
        "the item-type difference and not emptiness. NOTE this tree has no If, so its cost is not " +
        "the counterpart of #0's; #7 is",
        any(EQ(any(emptyC(pairI)), any(emptyC(pairI)))), "same-item-types-true#3"),
      entry("{ If(false, Coll[Int](1), Coll[Long](2L)).filter({ (x: Long) => false }) == Coll[Int]() }",
        s"false. Coll.filter keeps the INPUT's item type, so filtering a Coll[Long] down to nothing " +
        s"yields an empty Coll[LONG], which is unequal to an empty Coll[Int]. $Guard. This is the " +
        "row that needs the element-type gate gone first — see Coll.hof_element_type_mismatch — so " +
        "an implementation still gating Filter rejects here instead of answering false",
        any(EQ(any(Filter(CX, FuncValue(IndexedSeq(1 -> SLong), FalseLeaf))), any(emptyC(SInt)))),
        "filter-keeps-input-item-type-false#4"),
      entry("{ If(false, Coll[(Int, Int)](), Coll[(Long, Long)]()) != Coll[(Int, Int)]() }",
        s"true — #0 under NEQ. $Guard, and NEQ negates it",
        any(NEQ(lie(pairI, pairL), any(emptyC(pairI)))), "pair-items-mismatch-neq-true#5"),
      entry("{ If(false, Coll[Coll[Int]](), Coll[Coll[Long]]()) != Coll[Coll[Int]]() }",
        s"true — #1 under NEQ. $Guard, and NEQ negates it",
        any(NEQ(lie(SCollection(SInt), SCollection(SLong)), any(emptyC(SCollection(SInt))))),
        "nested-coll-mismatch-neq-true#6"),
      entry("{ If(false, Coll[(Int, Int)](), Coll[(Int, Int)]()) == Coll[(Int, Int)]() }",
        "true. The SAME-SHAPE honest twin of #0 — identical tree but for the false branch's item " +
        "types, so the cost difference against #0 is the item-type mismatch alone and nothing else. " +
        "This is the row to read #0's cost against, not #3",
        any(EQ(lie(pairI, pairI), any(emptyC(pairI)))), "same-shape-honest-true#7"))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7"))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalCollEqItemType", extract(), outDir)
}
