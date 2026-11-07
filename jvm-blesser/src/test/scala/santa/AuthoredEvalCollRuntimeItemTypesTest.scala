package santa

import io.circe.Json

/** Anchors the four runtime-item-type families. `extract()` re-derives every outcome through
  * EvalCore, so this pins what sigma-state 6.0.7 said about how `CollOverArray` stores things —
  * the part of the probe that could not be read off the source. */
class AuthoredEvalCollRuntimeItemTypesTest extends munit.FunSuite {
  import AuthoredEvalCollRuntimeItemTypes._
  private lazy val vectors = extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse("?")
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def boolValue(e: Json): Option[Boolean] =
    e.hcursor.downField("expected").downField("value").get[Boolean]("value").toOption
  private def byName(op: String): Map[String, Json] = entries(op).map(e => name(e) -> e).toMap

  /** (op, name, errored, cost) as the 6.0.7 oracle blessed them. */
  private val Pinned = List(
    (OpFlatMap, "flatmap-nested-lie-accept#0", false, Some(175L)),
    (OpFlatMap, "flatmap-empty-accept#1", false, Some(114L)),
    (OpFlatMap, "flatmap-long-element-errored#2", true, None),
    (OpAppend, "append-left-lies-errored#0", true, None),
    (OpAppend, "append-right-lies-errored#1", true, None),
    (OpAppend, "append-empty-left-accept#2", false, Some(82L)),
    (OpAppend, "append-empty-right-errored#3", true, None),
    (OpAppend, "append-reference-items-accept#4", false, Some(127L)),
    (OpAppend, "append-unrelated-classes-errored#5", true, None),
    (OpAppend, "append-pair-items-errored#6", true, None),
    (OpUpdate, "updatemany-receiver-lies-errored#0", true, None),
    (OpUpdate, "updatemany-values-lie-errored#1", true, None),
    (OpPrefix, "startswith-left-lies-true#0", false, Some(85L)),
    (OpPrefix, "endswith-left-lies-true#1", false, Some(85L)),
    (OpPrefix, "startswith-arg-lies-true#2", false, Some(85L)),
    (OpPrefix, "startswith-empty-receiver-false#3", false, Some(75L)),
    (OpPrefix, "endswith-empty-receiver-false#4", false, Some(75L)),
    (OpPrefix, "startswith-empty-arg-true#5", false, Some(80L)),
    (OpPrefix, "endswith-empty-arg-true#6", false, Some(80L)),
    (OpPrefix, "startswith-both-empty-true#7", false, Some(70L)))

  test("every row's verdict and cost are the blessed ones") {
    val got = Seq(OpFlatMap, OpAppend, OpUpdate, OpPrefix).flatMap(op =>
      entries(op).map(e => (op, name(e), errored(e), cost(e))))
    assertEquals(got, Pinned)
  }

  /** The asymmetry is the finding: an empty LEFT side survives the mismatch, an empty RIGHT side
    * does not, because System.arraycopy checks element types before length. */
  test("append: empty left accepts, empty right rejects") {
    val by = byName(OpAppend)
    assert(!errored(by("append-empty-left-accept#2")), "an empty left side must short-circuit")
    assert(errored(by("append-empty-right-errored#3")), "an empty right side must NOT save the copy")
  }

  /** Reference items accept only while the classes are compatible. Both rows must stay on their
    * own side or the family stops saying anything about ClassTag allocation. */
  test("append: Coll-into-Coll accepts, Box-into-GroupElement rejects") {
    val by = byName(OpAppend)
    assert(!errored(by("append-reference-items-accept#4")), "Coll[] into Coll[] must accept")
    assert(errored(by("append-unrelated-classes-errored#5")), "unrelated classes must throw")
  }

  /** startsWith/endsWith accept EVERY row — the same mismatch that is fatal to append is invisible
    * here. That contrast is why the two families are worth having side by side. */
  test("prefix/suffix: all eight accept, and the mismatched-item rows answer TRUE") {
    val es = entries(OpPrefix)
    assertEquals(es.count(errored), 0, "no row of this family may reject")
    val by = byName(OpPrefix)
    Seq("startswith-left-lies-true#0", "endswith-left-lies-true#1", "startswith-arg-lies-true#2")
      .foreach(n => assertEquals(boolValue(by(n)), Some(true), s"$n: numeric equality across widths"))
    Seq("startswith-empty-receiver-false#3", "endswith-empty-receiver-false#4")
      .foreach(n => assertEquals(boolValue(by(n)), Some(false), s"$n: false for LENGTH, not item type"))
  }

  test("flatMap: the gate is shallow, not absent — a Long element still rejects") {
    val by = byName(OpFlatMap)
    assert(!errored(by("flatmap-nested-lie-accept#0")), "a Coll[Long] element satisfies Coll[Int]")
    assert(errored(by("flatmap-long-element-errored#2")), "a plain Long element does not")
  }

  test("envelopes: the 6.0.7 blessing, activated 3 / ErgoTree 3") {
    Seq(OpFlatMap, OpAppend, OpUpdate, OpPrefix).foreach { op =>
      val env = vectors(op).hcursor
      assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"), op)
      assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"), op)
      assertEquals(env.get[String]("source").toOption, Some(Source), op)
      entries(op).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3), name(e))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3), name(e))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    writeVectors(outDir)
    Seq(OpFlatMap, OpAppend, OpUpdate, OpPrefix).foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"${SpecExtract.slug(op)}.json")), s"staging $op"))
  }
}
