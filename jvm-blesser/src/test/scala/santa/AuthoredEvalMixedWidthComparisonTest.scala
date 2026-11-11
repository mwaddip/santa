package santa

import io.circe.Json

/** Anchors the mixed-width COMPARISON family.
  *
  * The one thing here that no other gate can catch: **R7 fails by a different mechanism from the
  * other eight, and the vector cannot say so.** The eval tier grades refusals coarsely, so R1-R6
  * and R8 (`ClassCastException`, from the ordering table) and R7
  * (`InterpreterException: Invalid type returned by evaluator`, from `EQ`'s own `checkType` site)
  * are indistinguishable in the JSON — an implementation that fixed only `EQ` would go green on R7
  * and stay wrong on the rest. This test asserts the split from the ORACLE's own error strings, so
  * the premise is a fact of the suite rather than of a sentence in a `script` field.
  *
  * Also pinned: that NO row straddles a version gate (unlike `ArithOp.mixed_width`'s A3/A4), that
  * R0 is the only returning row, and every row's declared `version.ergoTree` against its own tree
  * header byte — `SpecExtract` does not cross-check that.
  */
class AuthoredEvalMixedWidthComparisonTest extends munit.FunSuite {
  private val B = AuthoredEvalMixedWidthComparison
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def treeV(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("version.ergoTree"))
  private def rows(id: String): List[Json] = entries.filter(e => str(e, "script").startsWith(s"[$id] "))

  /** The oracle's own refusal string for a body at a tree version — the class is its head. */
  private def refusal(body: String, treeV: Int): String =
    EvalCore.evalApplied(B.treeFor(body, treeV),
      Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0)), activated = B.V3) match {
      case (_, Left(err)) => err.toString
      case (_, Right(r))  => fail(s"expected a refusal, got $r")
    }

  test("18 rows: R0 returns at both versions, the other eight refuse at both") {
    assertEquals(entries.size, 18)
    assertEquals(entries.count(errored), 16)
    val r0 = rows("R0")
    assertEquals(r0.size, 2)
    r0.foreach(e => assert(!errored(e), "R0 must RETURN"))
    r0.foreach(e => assertEquals(cost(e), Some(30L), "R0 cost 30"))
    Seq("R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8").foreach { id =>
      val rs = rows(id)
      assertEquals(rs.size, 2, s"$id must appear at both versions")
      rs.foreach(e => assert(errored(e), s"$id must refuse"))
    }
  }

  test("NO row straddles a version gate — the gate is absent, not unexercised") {
    Seq("R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8").foreach { id =>
      val rs = rows(id)
      assertEquals(rs.map(treeV).sorted, List(0, 3), s"$id must be authored at v0 AND v3")
      assertEquals(rs.map(errored).distinct.size, 1,
        s"$id must reach the SAME verdict at both versions — a split here would mean the builder " +
          "had something to insert after all")
      // same body, one header byte apart
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

  test("THE MECHANISM SPLIT: R7 refuses by checkType, the other eight by the ordering table") {
    // From the oracle, not from the vector — the vector grades these identically.
    Seq(0, 3).foreach { tv =>
      Seq(("R1", B.R1), ("R2", B.R2), ("R3", B.R3), ("R4", B.R4),
          ("R5", B.R5), ("R6", B.R6), ("R8", B.R8)).foreach { case (id, body) =>
        val r = refusal(body, tv)
        assert(r.contains("ClassCastException"),
          s"$id at v$tv must fail through the ordering table (ClassCastException), got: ${r.take(90)}")
      }
      val r7 = refusal(B.R7, tv)
      assert(r7.contains("Invalid type returned by evaluator"),
        s"R7 at v$tv must fail at EQ's checkType site, got: ${r7.take(90)}")
      assert(!r7.contains("ClassCastException"),
        s"R7 at v$tv must NOT be a ClassCastException — if it ever becomes one, the control has " +
          s"stopped being a control and this family lost the distinction it exists to carry")
    }
  }

  test("R5/R6 would be trivially TRUE if reached — so the refusal is the table's, not the comparison's") {
    // I2 >= I2 and I2 <= I2 compare a value with itself. A reachable comparison answers true;
    // these refuse, which is what shows the cast happens BEFORE any comparing.
    Seq("R5", "R6").foreach(id => rows(id).foreach(e => assert(errored(e), id)))
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
