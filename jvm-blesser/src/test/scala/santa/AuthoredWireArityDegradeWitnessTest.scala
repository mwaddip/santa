package santa

import io.circe.Json

/** Anchors the degrade-witness entries. extract() requires a NON-identity round-trip for every
  * entry — that is the whole mechanism — and that the JVM's own output round-trips to itself.
  * This test pins the two properties the family rests on: the JVM parses each tree (so the
  * re-encode happens at all), and the lossy name really does widen. */
class AuthoredWireArityDegradeWitnessTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireArityDegradeWitness.extract()

  private def entries: List[Json] =
    vectors(AuthoredWireArityDegradeWitness.Op).hcursor
      .downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, field: String): String =
    e.hcursor.get[String](field).toOption.getOrElse(fail(field))

  test("four entries: the three wrong arities and the legal-arity control") {
    assertEquals(entries.map(str(_, "name")), List(
      "arity-degrade-witness-legal-arity-control#0",
      "arity-degrade-witness-user-defined-invoke-extra-arg#1",
      "arity-degrade-witness-reflective-extra-arg#2",
      "arity-degrade-witness-property-call-of-arg-taking-method#3"))
  }

  test("every entry is a NON-identity round-trip — without that it separates nothing") {
    entries.foreach { e =>
      val in = str(e, "bytes_hex")
      val out = str(e, "expected_bytes_hex")
      assertNotEquals(in, out, s"${str(e, "name")}: identity would be blind to a degrade")
    }
  }

  test("the lossy name widens 1 -> 3 bytes, and only that") {
    entries.foreach { e =>
      val in = str(e, "bytes_hex")
      val out = str(e, "expected_bytes_hex")
      // 67 01 ff  ->  67 03 efbfbd, twice (the ValDef's type param and the FuncValue's)
      assert(in.contains("6701ff"), s"${str(e, "name")}: input carries the lossy name")
      assert(out.contains("6703efbfbd"), s"${str(e, "name")}: output carries U+FFFD")
      assert(!out.contains("6701ff"), s"${str(e, "name")}: no lossy name survives re-encoding")
      // each of the two names grows by 2 bytes, so the tree grows by 4
      assertEquals(out.length - in.length, 8, s"${str(e, "name")}: two names, +2 bytes each (hex)")
    }
  }

  test("the trees keep their constants INLINE, carrying the measured call bytes") {
    // header 0x0b = v3 + size, no segregation; the surplus-argument call is the same
    // fragment ErgoTree.method_call_arity carries, which is the form the fork degrades.
    entries.foreach(e => assert(str(e, "bytes_hex").startsWith("0b"),
      s"${str(e, "name")}: v3 sized, unsegregated"))
    assert(str(entries(1), "bytes_hex").contains("dc04090402020404040 8".replace(" ", "")),
      "the surplus-argument call fragment matches method_call_arity's")
  }

  test("the control carries the same lossy name as the wrong-arity rows") {
    // This is what makes a red attributable to the arity degrade rather than to name handling.
    val control = str(entries.head, "bytes_hex")
    assert(control.contains("6701ff"), "the control must carry the lossy name too")
  }

  test("envelope: santa-wire/v1, ErgoTree kind, the v6 context, 6.0.7 blessing") {
    val c = vectors(AuthoredWireArityDegradeWitness.Op).hcursor
    assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
    assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    entries.foreach { e =>
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("ErgoTree"), str(e, "name"))
      assertEquals(e.hcursor.get[String]("source").toOption,
        Some(AuthoredWireArityDegradeWitness.Source))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireArityDegradeWitness.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${AuthoredWireArityDegradeWitness.Op}.json")))
  }
}
