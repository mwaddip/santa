package santa

import io.circe.Json

/** Anchors the four specialisation families. `SpecExtract` already fails loud if an accept does
  * not evaluate or a reject does; what this pins is the STRUCTURE that makes the rows evidence:
  * the specialised/generic pairs must stay OPPOSITE, the ByIndex version witness must stay one
  * header byte apart, and the rows where the JVM hands back a value of the WRONG TYPE must keep
  * doing so. Any of those could rot while every row still graded green. */
class AuthoredEvalCollSpecialisationTest extends munit.FunSuite {
  private val B = AuthoredEvalCollSpecialisation
  private lazy val v = B.extract()

  private def entries(op: String): List[Json] = v(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries($op): $e"), identity)
  private def all: List[Json] = (v.keys.toList.sorted).flatMap(entries)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def valueKind(e: Json): Option[String] =
    e.hcursor.downField("expected").downField("value").get[String]("kind").toOption
  private def boolValue(e: Json): Option[Boolean] =
    e.hcursor.downField("expected").downField("value").get[Boolean]("value").toOption
  private def ergoTree(e: Json): Option[Int] = e.hcursor.downField("version").get[Int]("ergoTree").toOption
  private def row(id: String): Json = all.filter(e => str(e, "script").startsWith(s"[$id]")) match {
    case List(e) => e
    case other   => fail(s"want one row [$id], got ${other.map(str(_, "name"))}")
  }

  test("92 rows over nine families, and every id is unique") {
    assertEquals(entries(B.OpGetOrElse).size, 10)
    assertEquals(entries(B.OpPrefix).size, 13)
    assertEquals(entries(B.OpByIndex).size, 12)
    assertEquals(entries(B.OpBlind).size, 5)
    assertEquals(entries(B.OpPairPrefix).size, 6)
    assertEquals(entries(B.OpItemStore).size, 12)
    assertEquals(entries(B.OpCrossWidth).size, 6)
    assertEquals(entries(B.OpProducer).size, 11)
    assertEquals(entries(B.OpPairShape).size, 17)
    assertEquals(all.size, 92)
    val ids = all.map(e => str(e, "script").takeWhile(_ != ']').drop(1))
    assertEquals(ids.distinct.size, ids.size, s"duplicate row ids: $ids")
    assertEquals(all.map(e => str(e, "tree_bytes_hex")).distinct.size, 92, "duplicate tree bytes")
  }

  test("A: the specialised/generic pairs stay OPPOSITE on all three specialised kinds") {
    // mcZ (a Coll[Boolean] constant) and mcI (indices) must behave like the shipped mcB pair.
    Seq(("Z1", "Z3"), ("Z2", "Z4"), ("I1", "I3"), ("I2", "I4")).foreach { case (sp, gen) =>
      assert(errored(row(sp)), s"$sp (specialised) must ERROR")
      assert(!errored(row(gen)), s"$gen (generic twin) must RETURN")
    }
    // the generic twin hands back the MISTYPED default itself, which is the finding's teeth
    assertEquals(valueKind(row("Z3")), Some("Int"), "Z3 returns an Int out of a Coll[Boolean]")
    assertEquals(valueKind(row("Z4")), Some("Boolean"))
    assertEquals(valueKind(row("I3")), Some("Boolean"), "I3 returns a Boolean out of a Coll[Int]")
    assertEquals(valueKind(row("I4")), Some("Int"))
    // and the controls prove the receivers are usable at all
    assert(!errored(row("Z5")) && !errored(row("I5")))
    assertEquals(cost(row("Z5")), Some(49L))
    assertEquals(cost(row("I5")), Some(75L))
  }

  test("B: startsWith/endsWith separate, and the generic side ANSWERS rather than refusing") {
    Seq("S1", "S3", "S5", "S7", "E1").foreach(id => assert(errored(row(id)), id))
    Seq("S2", "S4", "S6", "S8", "E2").foreach(id => assert(!errored(row(id)), id))
    // S2 is the row to lead with: true, not a refusal, from a Coll[Long] argument
    assertEquals(boolValue(row("S2")), Some(true), "S2 must answer TRUE (boxed numeric ==)")
    assertEquals(boolValue(row("S4")), Some(false))
    assertEquals(boolValue(row("S6")), Some(false))
    // the same argument, opposite ends: the generic answer is the ITEMS' answer
    assertEquals(boolValue(row("E2")), Some(false), "E2 must answer FALSE on S2's own argument")
    // header(1) + size(1) + MethodCall opcode + SCollection type id (dc 0c) + METHOD ID + body:
    // S2 and E2 must be the same bytes but for the id, 1f (startsWith 31) vs 20 (endsWith 32).
    val s2 = str(row("S2"), "tree_bytes_hex")
    val e2 = str(row("E2"), "tree_bytes_hex")
    assertEquals(s2.take(8), e2.take(8), "S2/E2 header and receiver must match")
    assertEquals(s2.drop(10), e2.drop(10), "S2/E2 argument must be the same bytes")
    assertEquals((s2.slice(8, 10), e2.slice(8, 10)), ("1f", "20"))
    // a ZERO-LENGTH ill-typed argument still throws on the specialised receiver
    assert(errored(row("S7")), "S7: the array cast precedes any length check")
    assertEquals(boolValue(row("S8")), Some(true), "S8: vacuously true on the generic twin")
    Seq("S9", "S10", "E3").foreach(id => assert(!errored(row(id)), s"$id is a control"))
  }

  test("C: the ByIndex VERSION WITNESS is one header apart, and v3 flips the specialised row") {
    val n1 = str(row("N1"), "tree_bytes_hex") // v0, unsized: "00" + body
    val n7 = str(row("N7"), "tree_bytes_hex") // v3, sized:   "0b" + size + body
    assertEquals(n1.drop(2), n7.drop(4),
      "N1 and N7 must be the SAME BODY, differing only in the tree header")
    assertEquals(ergoTree(row("N1")), Some(0))
    assertEquals(ergoTree(row("N7")), Some(3))
    assert(errored(row("N1")), "v0: the eager default throws even IN RANGE")
    assert(!errored(row("N7")), "v3: the lazy default is never touched in range")
    assertEquals(cost(row("N7")), Some(40L))
    assertEquals(cost(row("N5")), Some(45L), "the v0 control pays 5 more for the eager default")
    assert(errored(row("N2")) && errored(row("N8")), "out of range the default is reached at both")
    // the generic rows hand a Boolean out of a Coll[Byte], at BOTH versions
    assertEquals(valueKind(row("N4")), Some("Boolean"), "N4: a Boolean out of a Coll[Byte]")
    assertEquals(valueKind(row("N10")), Some("Boolean"), "N10: the same at tree v3")
    assertEquals(valueKind(row("N3")), Some("Byte"))
    assertEquals(cost(row("N3")), Some(75L))
    assertEquals(cost(row("N9")), Some(70L), "v3's lazy default saves 5 in range")
    Seq("N5", "N6", "N11", "N12").foreach(id => assert(!errored(row(id)), s"$id is a control"))
  }

  test("D: the blind operations must stay blind — EQ true, indexOf width-blind") {
    Seq("Q1", "Q2", "Q3", "X1", "X2").foreach(id => assert(!errored(row(id)), id))
    Seq("Q1", "Q2", "Q3").foreach(id => assertEquals(boolValue(row(id)), Some(true),
      s"$id: a specialised and a generic collection with the same items MUST compare equal"))
    assertEquals(cost(row("X1")), Some(96L))
    assertEquals(cost(row("X2")), Some(100L))
  }

  test("E: a specialised SIDE separates on a pair, where getOrElse on a pair does not") {
    assert(errored(row("P1")) && errored(row("P5")), "the delegated ls.startsWith casts")
    assertEquals(boolValue(row("P2")), Some(true), "P2: the generic ls answers TRUE")
    assertEquals(boolValue(row("P6")), Some(false), "P6: the same argument, the other end")
    Seq("P3", "P4").foreach(id => assert(!errored(row(id)), s"$id is a control"))
    // P2 and P6 must differ only in the method id (1f startsWith vs 20 endsWith)
    val p2 = str(row("P2"), "tree_bytes_hex"); val p6 = str(row("P6"), "tree_bytes_hex")
    assertEquals(p2.take(8), p6.take(8)); assertEquals(p2.drop(10), p6.drop(10))
  }

  test("F: a primitive array rejects ANY wrong item; an Object array only a wrong CLASS") {
    (1 to 10).map(i => s"U$i").foreach(id => assert(errored(row(id)),
      s"$id: the generic receiver must throw too — this family is not about specialisation"))
    assert(!errored(row("U11")), "U11: a compatible reference item is WAVED THROUGH")
    assert(errored(row("U12")), "U12: an incompatible class is refused by the array itself")
    assertEquals(cost(row("U11")), Some(100L))
  }

  test("G: a cross-width comparison is REFUSED at a scalar and answers FALSE in a collection") {
    Seq("W1", "W2", "W3", "W4", "W5").foreach(id => assert(errored(row(id)),
      s"$id: Value.checkType refuses before DataValueComparer is reached"))
    assertEquals(boolValue(row("W6")), Some(false),
      "W6: inside a collection the comparer runs and bails on the item type — FALSE, not the " +
      "numeric TRUE that indexOf/startsWith give for the same values")
    assertEquals(cost(row("W6")), Some(66L))
  }

  test("H: the producer pairs — same declared type, same box, opposite verdict") {
    assert(errored(row("H1")) && !errored(row("H2")),
      "SELF.bytes is specialised and SELF.creationInfo._2 is not")
    assertEquals(boolValue(row("H2")), Some(true))
    assert(!errored(row("H3")) && errored(row("H4")),
      "the same method id 6: fixed-width numerics are generic, BigInt is specialised")
    assert(errored(row("H5")), "every toBits is the specialised Boolean class")
    assert(errored(row("H8")) && errored(row("H9")))
    Seq("H6", "H7").foreach(id => assert(!errored(row(id)), s"$id is a control"))
    assertEquals(cost(row("H2")), Some(80L))
    assertEquals(cost(row("H3")), Some(58L))
    // H10 pins the canonical SELF box's length; H11 is its consequence row and H6 must NOT be
    // confounded by it (index 9999, out of range for any box).
    assertEquals(row("H10").hcursor.downField("expected").downField("value").get[Int]("value").toOption,
      Some(46), "SELF.bytes.size is a contract-determined constant")
    assert(str(row("H6"), "tree_bytes_hex") != str(row("H11"), "tree_bytes_hex"))
    assert(str(row("H10"), "script").contains("grade it first"))
  }

  test("I: map turns a PairOfCols into a Tuple2-array collection and flips the verdict") {
    assert(errored(row("M1")) && errored(row("M2")) && errored(row("M5")),
      "a PairOfCols casts the default to Tuple2 first")
    Seq("M3", "M4", "M6", "M7").foreach(id => assert(!errored(row(id)),
      s"$id: the mapped shape has no Tuple2 bridge and RETURNS"))
    assertEquals(boolValue(row("M3")), Some(true))
    assertEquals(cost(row("M3")), Some(99L))
    // the identity map is the ONLY difference between M1 and M3 — if their receivers ever
    // coincide the family has stopped saying anything
    assert(str(row("M1"), "tree_bytes_hex") != str(row("M3"), "tree_bytes_hex"))
    assertEquals(boolValue(row("M10")), Some(true),
      "the two shapes MUST compare equal — otherwise M1/M3 could be satisfied by inequality")
    assertEquals(boolValue(row("M14")), Some(true), "SELF.tokens returns the mistyped default")
    assertEquals(row("M16").hcursor.downField("expected").downField("value").get[Int]("value").toOption,
      Some(0), "the canonical SELF carries no tokens (a §2 premise M14/M15 rest on)")
    Seq("M8", "M9", "M10", "M11", "M12", "M13", "M16", "M17")
      .foreach(id => assert(str(row(id), "script").contains("CONTROL"), s"$id must be marked"))
  }

  test("every errored row claims SEPARATING; every control says so") {
    all.foreach { e =>
      val d = str(e, "script")
      if (errored(e)) assert(d.contains("SEPARATING"), s"${str(e, "name")} does not say why: $d")
    }
    Seq("Z5", "I5", "S9", "S10", "E3", "N5", "N6", "N11", "N12", "Q1", "Q2", "Q3", "X1",
        "P3", "P4", "H6", "H7", "M8", "M9", "M10", "M11", "M12", "M13", "M16", "M17")
      .foreach(id => assert(str(row(id), "script").contains("CONTROL"), s"$id must be marked"))
  }

  test("envelope + write") {
    v.foreach { case (op, j) =>
      assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"), op)
      assertEquals(j.hcursor.get[String]("schema").toOption, Some("santa-eval/v2"), op)
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    Seq(B.OpGetOrElse, B.OpPrefix, B.OpByIndex, B.OpBlind, B.OpPairPrefix, B.OpItemStore,
        B.OpCrossWidth, B.OpProducer, B.OpPairShape)
      .foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), op))
  }
}
