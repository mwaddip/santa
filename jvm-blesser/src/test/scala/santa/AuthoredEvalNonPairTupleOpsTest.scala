package santa

import io.circe.Json

/** Anchors the non-pair-tuple-value family. `SpecExtract` already fails loud if an accept does not
  * evaluate or a reject does, so what this adds is the family's CLAIMS:
  *
  *   1. every tuple row has a control, and the two differ ONLY where the family says they differ;
  *   2. the cost is representation-blind — equal on every T/C pair that returns, which is the
  *      round's headline and the thing that would rot silently if a cost kind changed;
  *   3. the one bare `Coll[Any]` root really carries `{"tag":"SAny"}` (the corpus's first), and its
  *      control the same shape with `SInt` — if those ever agree, the encoding pin is gone while
  *      the vector still grades green;
  *   4. the array-needing rows are errored and their controls are not, in both operand directions.
  */
class AuthoredEvalNonPairTupleOpsTest extends munit.FunSuite {
  private val B = AuthoredEvalNonPairTupleOps
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def value(e: Json): Json =
    e.hcursor.downField("expected").downField("value").focus.getOrElse(Json.Null)
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def row(id: String): Json = entries.filter(e => str(e, "script").startsWith(s"[$id]")) match {
    case List(e) => e
    case other   => fail(s"want exactly one row [$id], got ${other.map(str(_, "name"))}")
  }

  private val ArrayNeeded = Seq("T12", "T13", "T17", "T18", "T21", "T22")
  private val InterfaceOnly =
    Seq("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "T10", "T11",
        "T14", "T15", "T16", "T19", "T20")

  test("47 rows: 22 tuple rows each with a control, plus P1, Z1, Z2") {
    assertEquals(entries.size, 47)
    (1 to 22).foreach { n =>
      val t = row(s"T$n"); val c = row(s"C$n")
      assertNotEquals(str(t, "tree_bytes_hex"), str(c, "tree_bytes_hex"), s"T$n/C$n must differ")
      assert(str(c, "script").contains("CONTROL"), s"C$n must say it is a control")
    }
    assertEquals(entries.count(errored), 7) // the 6 array-needing rows + P1, and nothing else
  }

  test("THE HEADLINE: cost is representation-blind — equal on every pair that returns") {
    InterfaceOnly.foreach { id =>
      val n = id.drop(1)
      val t = row(id); val c = row(s"C$n")
      assert(!errored(t) && !errored(c), s"$id and C$n must both return")
      assertEquals(cost(t), cost(c), s"$id cost must equal C$n's — the family's central claim")
    }
  }

  test("the values agree too, EXCEPT the one row where the item type is observable") {
    InterfaceOnly.filterNot(_ == "T5").foreach { id =>
      val n = id.drop(1)
      assertEquals(value(row(id)), value(row(s"C$n")),
        s"$id must give the same value as C$n — a scalar root hides the item type by design")
    }
    // T5/C5: same items, same cost, different elem — the encoding pin
    assertNotEquals(value(row("T5")), value(row("C5")))
    assertEquals(cost(row("T5")), cost(row("C5")))
    val t5 = value(row("T5")).noSpaces
    assert(t5.contains("\"elem\":{\"tag\":\"SAny\"}"), s"T5 must carry the SAny item type: $t5")
    assert(value(row("C5")).noSpaces.contains("\"elem\":{\"tag\":\"SInt\"}"), "C5 must be SInt")
    assert(t5.contains("\"value\":1") && t5.contains("\"value\":2") && !t5.contains("\"value\":3"),
      s"T5 must hold exactly the first two items: $t5")
  }

  test("the array-needing rows throw and their controls return") {
    ArrayNeeded.foreach { id =>
      val n = id.drop(1)
      assert(errored(row(id)), s"$id must be errored")
      assert(!errored(row(s"C$n")), s"C$n must RETURN — that is what makes $id the tuple's doing")
      assertEquals(cost(row(id)), None)
    }
    // both Append directions, so a reordered copy cannot fix one of them
    assert(str(row("T12"), "script").contains("int[] into object array[]"))
    assert(str(row("T13"), "script").contains("object array[] into int[]"))
  }

  test("the pinned costs, as measured") {
    Seq("T1" -> 19L, "T2" -> 40L, "T3" -> 45L, "T4" -> 41L, "T5" -> 27L, "T6" -> 46L, "T7" -> 150L,
        "T8" -> 121L, "T9" -> 32L, "T10" -> 119L, "T11" -> 169L, "T14" -> 20L, "T15" -> 15L,
        "T16" -> 30L, "T19" -> 22L, "T20" -> 22L, "Z1" -> 19L, "Z2" -> 19L)
      .foreach { case (id, c) => assertEquals(cost(row(id)), Some(c), id) }
    Seq("C17" -> 46L, "C18" -> 107L, "C21" -> 63L, "C22" -> 35L)
      .foreach { case (id, c) => assertEquals(cost(row(id)), Some(c), id) }
  }

  test("Z1/Z2 are the readable-but-unwritable pair, and P1 is the lower boundary") {
    assertEquals(value(row("Z1")).noSpaces, """{"kind":"Int","value":0}""")
    assertEquals(value(row("Z2")).noSpaces, """{"kind":"Int","value":1}""")
    Seq("Z1", "Z2").foreach { id =>
      assert(str(row(id), "script").contains("TypeSerializer.scala:95"),
        s"$id must record WHY it can never be a box: the writer refuses the type")
      assert(str(row(id), "script").contains("no spend arm"), s"$id must say the tier limit")
    }
    assert(errored(row("P1")), "a pair is a Tuple2, not a Coll")
    assert(str(row("P1"), "script").contains("LOWER BOUNDARY"))
  }

  test("every row states its role: tuple rows separate, controls say they are controls") {
    entries.foreach { e =>
      val d = str(e, "script")
      val id = d.takeWhile(_ != ']').drop(1)
      if (id.startsWith("C")) assert(d.contains("CONTROL"), s"$id: $d")
      else assert(d.contains("SEPARATING"), s"$id must say what it separates: $d")
    }
  }

  test("envelope + write") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    assertEquals(v(B.Op).hcursor.get[String]("source").toOption, Some(B.Source))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
