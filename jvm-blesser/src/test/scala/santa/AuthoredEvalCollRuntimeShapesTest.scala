package santa

import io.circe.Json

/** Anchors the Coll runtime-shapes family. `SpecExtract` already fails loud if an accept does not
  * evaluate or a reject does; this pins the costs and — above all — the two PROVENANCE PAIRS,
  * which are the family's entire point. If B1/B3 or B2/B4 ever land on the same verdict, the
  * family has stopped demonstrating that the JVM's answer depends on how the collection was
  * built, while still grading green. */
class AuthoredEvalCollRuntimeShapesTest extends munit.FunSuite {
  private val B = AuthoredEvalCollRuntimeShapes
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def row(id: String): Json = entries.filter(e => str(e, "script").startsWith(s"[$id]")) match {
    case List(e) => e
    case other   => fail(s"want one row [$id], got ${other.map(str(_, "name"))}")
  }

  test("11 rows: 4 errored (A1 A2 B1 B2), 7 accepts") {
    assertEquals(entries.size, 11)
    assertEquals(entries.count(errored), 4)
    Seq("A1", "A2", "B1", "B2").foreach(id => assert(errored(row(id)), id))
    Seq("A3", "A4", "B3", "B4", "C1", "C2", "C3").foreach(id => assert(!errored(row(id)), id))
  }

  test("THE PROVENANCE PAIRS: same declared type, same call, opposite outcome") {
    // B1/B3 differ only in how the Coll[Byte] receiver was built; likewise B2/B4. This is the
    // finding, and it is the thing that would rot silently.
    assert(errored(row("B1")) && !errored(row("B3")),
      "a CONSTANT Coll[Byte] throws and a ConcreteCollection of the same bytes returns")
    assert(errored(row("B2")) && !errored(row("B4")), "the same at an in-range index")
    assertEquals(cost(row("B3")), Some(74L))
    assertEquals(cost(row("B4")), Some(74L))
    assertEquals(cost(row("B1")), None)
    assertEquals(cost(row("B2")), None)
  }

  test("A's controls show the fault is the DEFAULT's type, not the pair receiver") {
    assert(errored(row("A1")) && errored(row("A2")), "a non-pair default throws at any index")
    assert(!errored(row("A3")) && !errored(row("A4")), "a pair default works at any index")
    assertEquals(cost(row("A3")), Some(109L))
    assertEquals(cost(row("A4")), Some(109L))
  }

  test("the index is irrelevant to A and B, and relevant to the controls") {
    // A1/A2 and B1/B2 are out-of-range / in-range pairs that behave identically: the cast precedes
    // the index. A3/A4 and B3/B4 differ in WHAT they return, proving the index is honoured once
    // the types line up.
    assertEquals(errored(row("A1")), errored(row("A2")))
    assertEquals(errored(row("B1")), errored(row("B2")))
    assertNotEquals(str(row("A3"), "tree_bytes_hex"), str(row("A4"), "tree_bytes_hex"))
    assertNotEquals(str(row("B3"), "tree_bytes_hex"), str(row("B4"), "tree_bytes_hex"))
  }

  test("C: a 3-item tuple is a Coll[Any] and all three methods work") {
    assertEquals(cost(row("C1")), Some(23L))
    assertEquals(cost(row("C2")), Some(44L))
    assertEquals(cost(row("C3")), Some(49L))
    assert(str(row("C3"), "script").contains("not a `PairOfCols`"),
      "C3 must say why it does not hit A's bridge problem")
  }

  test("every errored row says it SEPARATES, every control says it is one") {
    entries.foreach { e =>
      val d = str(e, "script")
      if (errored(e)) assert(d.contains("SEPARATING"), s"${str(e, "name")}: $d")
    }
    Seq("A3", "A4").foreach(id => assert(str(row(id), "script").contains("CONTROL") ||
      str(row(id), "script").contains("Pairs with A3"), id))
  }

  test("envelope + write") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
