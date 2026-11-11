package santa

import io.circe.Json

/** Anchors the `Negation` mixed-width family. `SpecExtract` already fails loud if an accept does
  * not evaluate or a reject does, so what is pinned here is what would ROT SILENTLY while still
  * grading green:
  *
  *  - the DIRECTION of each refusal, from the oracle's own error strings. The eval tier grades
  *    refusals coarsely, so N1 (Integer -> Long) and N2 (Long -> Integer) look identical in the
  *    JSON; the opposite directions are what show the STATIC type is cast to, both ways.
  *  - the levers. L1 must keep returning a LONG from an Int-typed expression, or N2 throws for
  *    some other reason and demonstrates nothing.
  *  - N3's cost against N0's: the honest twin is the If's 20 plus Negation's 30.
  *  - that NO row straddles a version gate, and every row's declared `version.ergoTree` against
  *    its own tree header byte — `SpecExtract` does not cross-check that.
  */
class AuthoredEvalNegationMixedWidthTest extends munit.FunSuite {
  private val B = AuthoredEvalNegationMixedWidth
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
  private def rows(id: String): List[Json] = entries.filter(e => str(e, "script").startsWith(s"[$id] "))

  private def refusal(body: String, treeV: Int): String =
    EvalCore.evalApplied(B.treeFor(body, treeV),
      Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0)), activated = B.V3) match {
      case (_, Left(err)) => err.toString
      case (_, Right(r))  => fail(s"expected a refusal, got $r")
    }

  test("10 rows: N0/N3/L1 return at both versions, N1/N2 refuse at both") {
    assertEquals(entries.size, 10)
    Seq("N0", "N3", "L1").foreach(id => rows(id).foreach(e => assert(!errored(e), s"$id must RETURN")))
    Seq("N1", "N2").foreach(id => rows(id).foreach(e => assert(errored(e), s"$id must refuse")))
    assertEquals(entries.count(errored), 4)
  }

  test("NO row straddles a version gate — a unary node has nothing to upcast") {
    Seq("N0", "N1", "N2", "N3", "L1").foreach { id =>
      val rs = rows(id)
      assertEquals(rs.map(treeV).sorted, List(0, 3), s"$id must be authored at v0 AND v3")
      assertEquals(rs.map(errored).distinct.size, 1, s"$id must reach the SAME verdict at both versions")
      assertEquals(rs.map(cost).distinct.size, 1, s"$id must cost the same at both versions")
      val v3body = rs.find(treeV(_) == 3).map(e => str(e, "tree_bytes_hex").drop(4)).get
      val v0body = rs.find(treeV(_) == 0).map(e => str(e, "tree_bytes_hex").drop(2)).get
      assertEquals(v3body, v0body, s"$id: the v3 and v0 rows must carry the same body")
    }
  }

  test("every row's declared ergoTree matches its own tree header byte") {
    entries.foreach { e =>
      val expected = str(e, "tree_bytes_hex").take(2) match {
        case "0b" => 3
        case "00" => 0
        case other => fail(s"${str(e, "name")}: unexpected header $other")
      }
      assertEquals(treeV(e), expected, s"${str(e, "name")}: header disagrees with version.ergoTree")
    }
  }

  test("THE DIRECTIONS: N1 casts Integer -> Long, N2 casts Long -> Integer — both toward the STATIC type") {
    // From the oracle, not from the vector — the vector grades these identically.
    Seq(0, 3).foreach { tv =>
      val n1 = refusal(B.N1, tv)
      assert(n1.contains("ClassCastException") && n1.contains("java.lang.Integer cannot be cast to class java.lang.Long"),
        s"N1 at v$tv must cast the Int VALUE to the Long STATIC type, got: ${n1.take(110)}")
      val n2 = refusal(B.N2, tv)
      assert(n2.contains("ClassCastException") && n2.contains("java.lang.Long cannot be cast to class java.lang.Integer"),
        s"N2 at v$tv must cast the Long VALUE DOWN to the Int STATIC type, got: ${n2.take(110)}")
    }
  }

  test("the levers: L1 is Int-typed and returns a LONG; N3 returns the twin's Long") {
    rows("L1").foreach { e =>
      assertEquals(cost(e), Some(20L), "L1 cost")
      assert(value(e).contains("\"Long\""), s"L1 must evaluate to a LONG from an Int-typed If, got ${value(e)}")
    }
    rows("N3").foreach(e => assert(value(e).contains("\"Long\"") && value(e).contains("\"-2\""),
      s"N3 must be Long -2, got ${value(e)}"))
    rows("N0").foreach(e => assert(value(e).contains("\"Long\"") && value(e).contains("\"-1\""),
      s"N0 must be Long -1, got ${value(e)}"))
  }

  test("the costs: N0 35, N3 50 = the If's 20 + Negation's 30") {
    rows("N0").foreach(e => assertEquals(cost(e), Some(35L), "N0"))
    rows("N3").foreach(e => assertEquals(cost(e), Some(50L), "N3"))
    assertEquals(cost(rows("N3").head).get - cost(rows("L1").head).get, 30L,
      "the honest twin minus the lever alone must be Negation's FixedCost 30")
  }

  test("envelope: the 6.0.7 stamp") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
