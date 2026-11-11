package santa

import io.circe.Json

/** Anchors the mixed-width family. `SpecExtract` already fails loud if an accept does not evaluate
  * or a reject does, so what is pinned here is what would ROT SILENTLY while still grading green:
  *
  *  - the two UPCAST SUBTRACTIONS, which are the family's quantitative result. Each needs its own
  *    base, and B4's needs the BigInt one specifically — read against the Long base of 25 it would
  *    put the BigInt Upcast at 35 instead of 30.
  *  - the VERSION PAIRS. A3/A4 must straddle the gate; A5/A6 and B7-B10 must NOT, because their
  *    whole point is a failure the gate does not reach.
  *  - B5/B6 must keep RETURNING. They are the lever: if `If.tpe` ever stopped being the true
  *    branch's, B7-B10 would still throw and would silently stop demonstrating anything.
  *  - every row's declared `version.ergoTree` against its own tree header byte. `SpecExtract`
  *    does NOT cross-check those, so a v3 row mislabelled v0 would grade against the wrong
  *    version and look like an implementation divergence.
  */
class AuthoredEvalMixedWidthTest extends munit.FunSuite {
  private val B = AuthoredEvalMixedWidth
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def treeV(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("version.ergoTree"))
  private def value(e: Json): String =
    e.hcursor.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("-")
  private def row(id: String): Json = entries.filter(e => str(e, "script").startsWith(s"[$id] ")) match {
    case List(e) => e
    case other   => fail(s"want exactly one row [$id], got ${other.map(str(_, "name"))}")
  }

  test("18 rows: 10 errored, 8 accepts") {
    assertEquals(entries.size, 18)
    val accepts = Seq("A1", "A2", "A4", "B2", "B4", "B5", "B6", "C1", "C2")
    val rejects = Seq("A3", "A5", "A6", "B1", "B3", "B7", "B8", "B9", "B10")
    assertEquals(accepts.size + rejects.size, 18)
    accepts.foreach(id => assert(!errored(row(id)), s"$id must RETURN"))
    rejects.foreach(id => assert(errored(row(id)), s"$id must be errored"))
    assertEquals(entries.count(errored), rejects.size)
  }

  test("every row's declared ergoTree matches its own tree header byte") {
    entries.foreach { e =>
      val hex = str(e, "tree_bytes_hex")
      val declared = treeV(e)
      val header = hex.take(2)
      val expected = header match {
        case "0b" => 3
        case "00" => 0
        case other => fail(s"${str(e, "name")}: unexpected header $other")
      }
      assertEquals(declared, expected,
        s"${str(e, "name")}: header $header says tree v$expected but the entry declares v$declared")
    }
    // and both versions are actually represented, 9 scripts twice over
    assertEquals(entries.count(treeV(_) == 3), 9)
    assertEquals(entries.count(treeV(_) == 0), 9)
  }

  test("THE UPCAST SUBTRACTIONS: cost scales with the target width") {
    // Int -> Long against the Long base
    assertEquals(cost(row("A2")), Some(25L), "the Long-add base")
    assertEquals(cost(row("A4")), Some(35L), "1L + 2 at v0")
    assertEquals(cost(row("B2")), Some(35L), "2 + 1L at v0 — the same, so order does not matter")
    assertEquals(cost(row("A4")).get - cost(row("A2")).get, 10L, "Int -> Long Upcast costs 10")
    // Long -> BigInt against the BIGINT base, which is why C1 exists
    assertEquals(cost(row("C1")), Some(30L), "the BigInt-add base")
    assertEquals(cost(row("C2")), Some(30L), "unchanged at v0")
    assertEquals(cost(row("B4")), Some(60L), "BigInt(1) + 2L at v0")
    assertEquals(cost(row("B4")).get - cost(row("C1")).get, 30L, "Long -> BigInt Upcast costs 30")
    // the result that a flat-cost model gets wrong
    assert(cost(row("B4")).get - cost(row("C1")).get > cost(row("A4")).get - cost(row("A2")).get,
      "the BigInt Upcast must be DEARER than the Long one — the family's quantitative claim")
    // the trap the BigInt control exists to prevent
    assertNotEquals(cost(row("B4")).get - cost(row("A2")).get, 30L,
      "read against the LONG base the BigInt Upcast comes out 35, which is the mis-costing C1 prevents")
  }

  test("A3/A4 STRADDLE the version gate — the same body, one header byte apart") {
    assertEquals(str(row("A3"), "tree_bytes_hex").drop(4), str(row("A4"), "tree_bytes_hex").drop(2),
      "A3 and A4 must be the SAME body, or the pair proves nothing about the version")
    assert(errored(row("A3")) && !errored(row("A4")), "v3 throws, v0 returns")
  }

  test("A5/A6 and B7-B10 do NOT straddle it — the half no version pair can show") {
    Seq(("A5", "A6"), ("B7", "B8"), ("B9", "B10")).foreach { case (a, b) =>
      assertEquals(str(row(a), "tree_bytes_hex").drop(4), str(row(b), "tree_bytes_hex").drop(2),
        s"$a/$b must be the same body")
      assert(errored(row(a)) && errored(row(b)),
        s"$a/$b must BOTH throw: there is no Upcast to insert, so the gate cannot reach them")
    }
  }

  test("B5/B6 are the LEVER and must keep returning an Int from a Long-typed expression") {
    Seq("B5", "B6").foreach { id =>
      assert(!errored(row(id)), s"$id must RETURN, or B7-B10 demonstrate nothing")
      assertEquals(cost(row(id)), Some(20L), s"$id cost")
      assert(value(row(id)).contains("\"Int\""),
        s"$id must evaluate to an INT while its static type is Long — the whole lever")
    }
  }

  test("envelope: the 6.0.7 stamp") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }

  test("the SAny rows are not a width case: A5/A6 throw where a numeric would be upcast") {
    // A5/A6 vs A3/A4 is the comparison that separates "narrower numeric" from "not a numeric".
    assert(!errored(row("A4")), "a narrow NUMERIC is rescued at v0")
    assert(errored(row("A6")), "an SAny operand is NOT, at the same version")
  }
}
