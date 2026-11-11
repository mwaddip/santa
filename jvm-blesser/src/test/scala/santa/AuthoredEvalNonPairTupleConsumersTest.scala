package santa

import io.circe.Json

/** Anchors the producers and type-directed families. What it pins beyond the blesser's own
  * requires: the `elem` of the data-collection root (the probe's §1 question, and the second place
  * the corpus writes `SAny`), the 13-low cost set the three equality rows carry, and the fact that
  * every reject row has a control that RETURNS — including the SubstConstants one, whose obvious
  * control would have been circular.
  */
class AuthoredEvalNonPairTupleConsumersTest extends munit.FunSuite {
  private val B = AuthoredEvalNonPairTupleConsumers
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] = v(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def value(e: Json): Json =
    e.hcursor.downField("expected").downField("value").focus.getOrElse(Json.Null)
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def row(op: String, id: String): Json =
    entries(op).filter(e => str(e, "script").startsWith(s"[$id]")) match {
      case List(e) => e
      case other   => fail(s"want one [$id] in $op, got ${other.map(str(_, "name"))}")
    }

  test("11 producer rows, 8 type-directed rows") {
    assertEquals(entries(B.OpProducers).size, 11)
    assertEquals(entries(B.OpTypeDirected).size, 8)
    assertEquals(entries(B.OpProducers).count(errored), 0, "every route ANSWERS")
    assertEquals(entries(B.OpTypeDirected).count(errored), 4, "R2, R3, S1 and S2")
  }

  test("the five routes answer 3, at the costs measured") {
    Seq("P1" -> 29L, "P2" -> 54L, "P3" -> 34L, "P4" -> 160L).foreach { case (id, c) =>
      assertEquals(value(row(B.OpProducers, id)).noSpaces, """{"kind":"Int","value":3}""", id)
      assertEquals(cost(row(B.OpProducers, id)), Some(c), id)
    }
  }

  test("P5: a DATA collection of non-pair tuples carries Coll[Any] as its item type") {
    val p5 = row(B.OpProducers, "P5")
    assertEquals(cost(p5), Some(5L))
    val j = value(p5).noSpaces
    assert(j.startsWith("""{"kind":"Coll","elem":{"tag":"SColl","elem":{"tag":"SAny"}}"""),
      s"the item type must be Coll[SAny] and NOT the tuple type: $j")
    assert(j.contains("""{"kind":"Coll","elem":{"tag":"SAny"},"items":[{"kind":"Int","value":1}"""),
      s"and each item is itself a Coll[SAny]: $j")
    assert(str(p5, "script").contains("IS observable"),
      "P5 must record that the item type IS observable through the expression producer — the " +
      "family's first answer said otherwise and probe 3 refuted it")
  }

  // The 13-low undercharge was sigma-rust's when this family was written; its tuple-values round
  // fixed it and ergots now carries it (E1/E2/E3 and V1 all exactly 13 low, 2026-11-11). Naming a
  // runner in a test name is how that went stale, so this one names the ORACLE's numbers, which are
  // the only part a blesser test can own.
  test("THE COST SET: the oracle's four equality costs, and the control's") {
    assertEquals(cost(row(B.OpProducers, "E1")), Some(49L))
    assertEquals(cost(row(B.OpProducers, "E2")), Some(40L))
    assertEquals(cost(row(B.OpProducers, "E3")), Some(43L))
    Seq("E1", "E2", "E3").foreach { id =>
      assertEquals(value(row(B.OpProducers, id)).noSpaces, """{"kind":"Boolean","value":true}""", id)
    }
    // the control compares the same nesting with a declared item type
    assertEquals(cost(row(B.OpProducers, "C2")), Some(41L))
    // V1 is the fourth and deepest container of the same comparison. Pinning it here is what
    // makes ergots' uniform -13 legible: 49/40/43/91 against its 36/27/30/78.
    assertEquals(cost(row(B.OpProducers, "V1")), Some(91L))
    assertEquals(value(row(B.OpProducers, "V1")).noSpaces, """{"kind":"SigmaProp","raw_hex":"d3"}""")
  }

  test("serialize writes the ITEMS, and the two shapes that reach it fail") {
    assertEquals(cost(row(B.OpTypeDirected, "W1")), Some(33L))
    assertEquals(cost(row(B.OpTypeDirected, "W2")), Some(36L))
    assertEquals(cost(row(B.OpTypeDirected, "C1")), Some(36L))
    // W2 and the Coll[Int] control agree on cost and differ only in the leading length byte
    val w2 = value(row(B.OpTypeDirected, "W2")).noSpaces
    val c1 = value(row(B.OpTypeDirected, "C1")).noSpaces
    assertNotEquals(w2, c1)
    assert(w2.contains("""{"kind":"Byte","value":2},{"kind":"Byte","value":4},{"kind":"Byte","value":6}"""),
      s"W2 must end in the tuple's own three bytes: $w2")
    Seq("R2", "R3").foreach { id =>
      assert(errored(row(B.OpTypeDirected, id)), id)
      assertEquals(cost(row(B.OpTypeDirected, id)), None, id)
    }
    assert(str(row(B.OpTypeDirected, "R3"), "script").contains("doesn't correspond to value"),
      "R3 must name the length assert")
  }

  test("SubstConstants cannot substitute one BY CONSTRUCTION, and its control is not circular") {
    val s1 = row(B.OpTypeDirected, "S1")
    assert(errored(s1))
    assert(str(s1, "script").contains("Coll[SAny]"), "S1 must name what the lift produces")
    assert(str(s1, "script").contains("BY CONSTRUCTION"))
    val c2 = row(B.OpTypeDirected, "C2")
    assert(!errored(c2), "the control must RETURN")
    assertEquals(cost(c2), Some(315L))
    assert(str(c2, "script").contains("circular"),
      "the control must record why the obvious comparison is not one")
  }

  test("every row states its role, and both envelopes are stamped") {
    Seq(B.OpProducers, B.OpTypeDirected).foreach { op =>
      assertEquals(v(op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
      assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source))
      entries(op).foreach { e =>
        val d = str(e, "script")
        val id = d.takeWhile(_ != ']').drop(1)
        if (id.startsWith("C")) assert(d.contains("CONTROL"), s"$id: $d")
        // W1/W2 are rows every implementation already matches — calling those "separating" would
        // be a claim about two implementations that the grading contradicts, so they say GUARD.
        else assert(d.contains("SEPARATING") || d.contains("REGRESSION GUARD"), s"$id: $d")
      }
    }
  }

  test("envelope + write") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    Seq(B.OpProducers, B.OpTypeDirected).foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), op))
  }
}
