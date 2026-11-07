package santa

import io.circe.Json

/** Anchors the collection item-type equality family. `extract()` re-derives every outcome through
  * EvalCore, so this pins what the 6.0.7 oracle said. A failure means sigma-state changed how
  * `DataValueComparer` compares collections, or how a collection's item type survives an `If`. */
class AuthoredEvalCollEqItemTypeTest extends munit.FunSuite {
  import AuthoredEvalCollEqItemType._
  private lazy val vectors = extract()

  private def entries: List[Json] =
    vectors(Op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse("?")
  private def tree(e: Json): String = e.hcursor.get[String]("tree_bytes_hex").toOption.getOrElse(fail("tree"))
  private def value(e: Json): Option[Boolean] =
    e.hcursor.downField("expected").downField("value").get[Boolean]("value").toOption
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption

  /** (name, value, cost, tree) as the 6.0.7 oracle blessed them. */
  private val Pinned = List(
    ("pair-items-mismatch-false#0", false, 56L, "0b0d93950100830058830059830058"),
    ("nested-coll-mismatch-false#1", false, 56L, "0b0d93950100830010830011830010"),
    ("primitive-items-mismatch-false#2", false, 56L, "0b0d93950100830004830005830004"),
    ("same-item-types-true#3", true, 51L, "0b0793830058830058"),
    ("filter-keeps-input-item-type-false#4", false, 97L, "0b1893b595010083010404028301050504d90101050100830004"),
    ("pair-items-mismatch-neq-true#5", true, 56L, "0b0d94950100830058830059830058"),
    ("nested-coll-mismatch-neq-true#6", true, 56L, "0b0d94950100830010830011830010"),
    ("same-shape-honest-true#7", true, 66L, "0b0d93950100830058830058830058"))

  test("the mismatch bails EARLIER than a real comparison, measured at the same tree shape") {
    val by = entries.map(e => name(e) -> e).toMap
    val mismatch = cost(by("pair-items-mismatch-false#0")).getOrElse(0L)
    val honest = cost(by("same-shape-honest-true#7")).getOrElse(0L)
    assert(mismatch < honest,
      s"the item-type bail should cost less than equalColls_Dispatch: $mismatch vs $honest")
  }

  test("every row's value, cost and serialized tree are the blessed ones") {
    val got = entries.map(e => (name(e), value(e).getOrElse(fail(s"value ${name(e)}")),
      cost(e).getOrElse(fail(s"cost ${name(e)}")), tree(e)))
    assertEquals(got, Pinned)
  }

  /** The claim of #0 is that the item-type mismatch is what makes it false, and #7 is the row that
    * holds it: the same tree but for the false branch's item types. Only those bytes may differ. */
  test("#7 is #0's same-shape honest twin and disagrees with it") {
    val by = entries.map(e => name(e) -> e).toMap
    val mismatch = by("pair-items-mismatch-false#0")
    val honest = by("same-shape-honest-true#7")
    assertEquals(tree(mismatch).length, tree(honest).length, "same shape means same length")
    assertEquals(value(mismatch), Some(false))
    assertEquals(value(honest), Some(true))
  }

  test("the three mismatch rows all cost the same — MatchType and out") {
    val by = entries.map(e => name(e) -> e).toMap
    val costs = Seq("pair-items-mismatch-false#0", "nested-coll-mismatch-false#1",
      "primitive-items-mismatch-false#2").map(n => cost(by(n)))
    assertEquals(costs.distinct.length, 1, s"expected one cost across the three mismatches, got $costs")
  }

  test("envelope: the 6.0.7 blessing, activated 3 throughout") {
    val env = vectors(Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    assertEquals(env.get[String]("source").toOption, Some(Source))
    entries.foreach(e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3), name(e)))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${SpecExtract.slug(Op)}.json")), "staging not written")
  }
}
