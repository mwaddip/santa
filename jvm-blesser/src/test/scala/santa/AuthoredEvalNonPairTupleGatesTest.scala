package santa

import io.circe.Json

/** Anchors the gates family. Its two claims are the ones that would rot into a green vector:
  *
  *   1. THE OPERAND-ORDER ASYMMETRY — an `Any`-typed operand on the LEFT throws and on the RIGHT
  *      returns, at EXACTLY the control's cost. If a cost kind ever moves, or if both sides land on
  *      the same verdict, the family has stopped saying anything while still grading green.
  *   2. the two kinds of refusal are distinct: `SAny` as an operand TYPE (G1-G5) against the tuple
  *      VALUE at a `checkType` site (G9/G10). Each has a control that returns.
  */
class AuthoredEvalNonPairTupleGatesTest extends munit.FunSuite {
  private val B = AuthoredEvalNonPairTupleGates
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

  test("16 + 23 rows: 14 errored gates, the rest accepts") {
    assertEquals(entries.size, 39)
    Seq("G1", "G2", "G3", "G4", "G5", "G9", "G10", "G11", "G12", "G13", "G16", "G18", "G20", "G22")
      .foreach(id => assert(errored(row(id)), id))
    (Seq("G6", "G7", "G8", "G14", "G15", "G17", "G19", "G21") ++ (1 to 17).map(n => s"C$n"))
      .foreach(id => assert(!errored(row(id)), id))
    assertEquals(entries.count(errored), 14)
    // appended, never renumbered: each name's `#i` is its index in the file
    entries.zipWithIndex.foreach { case (e, i) => assert(str(e, "name").endsWith(s"#$i"), str(e, "name")) }
  }

  test("THE ASYMMETRY holds with UnsignedBigInt items: key 97 on the left, the control's answer on the right") {
    Seq("G11", "G12", "G13").foreach(id => assert(str(row(id), "script").contains("key 97"), s"$id names the lookup"))
    assertEquals(value(row("C7")).noSpaces, """{"kind":"SigmaProp","raw_hex":"d3"}""")
    assertEquals(cost(row("C7")), Some(165L))
    assertEquals(value(row("C8")).noSpaces, """{"kind":"UnsignedBigInt","value":"3"}""")
    assertEquals(cost(row("C8")), Some(95L))
    assertEquals(value(row("G14")).noSpaces, """{"kind":"UnsignedBigInt","value":"2"}""")
    assertEquals((value(row("G14")), cost(row("G14"))), (value(row("C9")), cost(row("C9"))), "G14 must be C9 exactly")
    assertEquals(cost(row("G14")), Some(60L))
  }

  test("A LAMBDA'S ARGUMENT is a checkType site: one data tuple throws, an empty collection answers as its control") {
    val elemTuple = """{"tag":"STuple","items":[{"tag":"SInt"},{"tag":"SInt"},{"tag":"SInt"}]}"""
    val emptyOf = (elem: String) => s"""{"kind":"Coll","elem":$elem,"items":[]}"""
    // (hof, empty row, its control, one-item row, its control, the empty row's value, the one-item control's cost)
    Seq(("ForAll", "G15", "C10", "G16", "C11", """{"kind":"SigmaProp","raw_hex":"d3"}""", 39L),
        ("Exists", "G17", "C12", "G18", "C13", """{"kind":"SigmaProp","raw_hex":"d2"}""", 39L),
        ("Map",    "G19", "C14", "G20", "C15", emptyOf(elemTuple), 41L),
        ("Filter", "G21", "C16", "G22", "C17", emptyOf("""{"tag":"SColl","elem":{"tag":"SAny"}}"""), 41L)).foreach {
      case (hof, empty, emptyCtl, one, oneCtl, emptyValue, oneCtlCost) =>
        assertEquals(value(row(empty)).noSpaces, emptyValue, s"$hof $empty")
        assertEquals(cost(row(empty)), cost(row(emptyCtl)), s"$hof $empty costs what its control does")
        assertEquals(cost(row(empty)), Some(if (hof == "ForAll" || hof == "Exists") 29L else 31L), s"$hof $empty")
        assert(str(row(one), "script").contains("Unsupported tuple type"), s"$hof $one names the checkType site")
        assertEquals(cost(row(oneCtl)), Some(oneCtlCost), s"$hof $oneCtl")
    }
    // the empty Map / Filter results differ only in their item type, and their controls agree with each other
    assertEquals(value(row("C14")), value(row("C16")))
  }

  test("THE ASYMMETRY: Any on the left throws, Any on the right returns at the control's cost") {
    // the node form, the apply-method form, and min: all three the same way round
    Seq(("G3", "G6", "C3", 60L), ("G4", "G7", "C4", 64L), ("G5", "G8", "C5", 54L)).foreach {
      case (left, right, control, c) =>
        assert(errored(row(left)), s"$left: an Any-typed LEFT operand must throw")
        assert(!errored(row(right)), s"$right: an Any-typed RIGHT operand must return")
        assertEquals(cost(row(right)), Some(c), s"$right cost")
        assertEquals(cost(row(control)), Some(c), s"$control must cost the same as $right — that is the claim")
        assertEquals(value(row(right)), value(row(control)), s"$right must give $control's value")
    }
    // and the node form is cheaper than the method form by the MethodCall's own cost
    assertEquals(cost(row("G6")).get + 4, cost(row("G7")).get)
  }

  test("the SAny-operand gate and the checkType-site gate are different refusals") {
    Seq("G1", "G2").foreach(id =>
      assert(str(row(id), "script").contains("Unknown type SAny"), s"$id names its throw"))
    Seq("G3", "G4", "G5").foreach(id =>
      assert(str(row(id), "script").contains("key 97"), s"$id names the table lookup"))
    Seq("G9", "G10").foreach(id =>
      assert(str(row(id), "script").contains("Unsupported tuple type"), s"$id names the checkType site"))
  }

  test("every gate has a control that RETURNS") {
    assertEquals(value(row("C1")).noSpaces, """{"kind":"Boolean","value":false}""")
    assertEquals(cost(row("C1")), Some(83L))
    assertEquals(cost(row("C2")), Some(98L))
    assertEquals(cost(row("C6")), Some(84L))
    assertEquals(value(row("C6")).noSpaces, """{"kind":"Int","value":2}""")
  }

  test("G2 is the reachable carrier and says so; G10 records the half that is not authorable") {
    assert(str(row("G2"), "script").contains("REACHABLE"))
    assert(str(row("G10"), "script").contains("not authorable"),
      "G10 must record that the register half waits on the input decoder")
    assert(str(row("G9"), "script").contains("T11/C11"), "G9 must point at its accepting twin")
  }

  test("every row states its role") {
    entries.foreach { e =>
      val d = str(e, "script")
      val id = d.takeWhile(_ != ']').drop(1)
      if (id.startsWith("C")) assert(d.contains("CONTROL"), s"$id: $d")
      else assert(d.contains("SEPARATING"), s"$id must say what it separates: $d")
    }
  }

  test("envelope + write") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
