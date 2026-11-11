package santa

import io.circe.Json

/** Anchors the division-overflow family. `SpecExtract` already fails loud if an accept does not
  * evaluate or a reject does; pinned here is what would rot silently while still grading green:
  *
  *  - every quotient of MIN by -1 IS that width's MIN and every remainder IS 0 — a blesser that
  *    started producing some other value would still "return" and still be green on a rudolph run.
  *  - every returning row costs the control's 25: overflow is free on the JVM.
  *  - Z1/Z2 throw `ArithmeticException: / by zero`, from the oracle's own string — the controls that
  *    make the returning rows believable, and the only throwing case.
  *  - no version gate, the same body at both versions, and every row's declared `version.ergoTree`
  *    against its own tree header byte.
  */
class AuthoredEvalDivisionOverflowTest extends munit.FunSuite {
  private val B = AuthoredEvalDivisionOverflow
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def treeV(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("version.ergoTree"))
  private def value(e: Json): Json =
    e.hcursor.downField("expected").downField("value").focus.getOrElse(fail("expected.value"))
  private def rows(id: String): List[Json] = entries.filter(e => str(e, "script").startsWith(s"[$id] "))

  private val ids = Seq("Q0", "Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Z1", "Z2")
  private val returning = ids.filterNot(_.startsWith("Z"))

  private def sval(kind: String, v: Json): Json = Json.obj("kind" -> Json.fromString(kind), "value" -> v)

  test("22 rows: Z1/Z2 refuse at both versions, the other nine return at both") {
    assertEquals(entries.size, 22)
    returning.foreach(id => rows(id).foreach(e => assert(!errored(e), s"$id must RETURN")))
    Seq("Z1", "Z2").foreach(id => rows(id).foreach(e => assert(errored(e), s"$id must refuse")))
    assertEquals(entries.count(errored), 4)
  }

  test("MIN / -1 is MIN and MIN % -1 is 0, at every width") {
    val expected = Map(
      "Q0" -> sval("Int", Json.fromInt(3)),
      "Q1" -> sval("Int", Json.fromInt(Int.MinValue)),
      "Q2" -> sval("Int", Json.fromInt(0)),
      "Q3" -> sval("Long", Json.fromString(Long.MinValue.toString)),
      "Q5" -> sval("Long", Json.fromString("0")),
      "Q4" -> sval("Byte", Json.fromInt(Byte.MinValue.toInt)),
      "Q6" -> sval("Byte", Json.fromInt(0)),
      "Q7" -> sval("Short", Json.fromInt(Short.MinValue.toInt)),
      "Q8" -> sval("Short", Json.fromInt(0)))
    expected.foreach { case (id, want) =>
      rows(id).foreach(e => assertEquals(value(e), want, s"$id value"))
    }
  }

  test("overflow is FREE: every returning row costs the control's 25") {
    returning.foreach(id => rows(id).foreach(e => assertEquals(cost(e), Some(25L), s"$id cost")))
  }

  test("Z1/Z2: the only throw is a ZERO divisor, and it is an ArithmeticException") {
    Seq(0, 3).foreach { tv =>
      Seq(("Z1", B.Z1), ("Z2", B.Z2)).foreach { case (id, body) =>
        val r = EvalCore.evalApplied(B.treeFor(body, tv),
          Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0)), activated = B.V3) match {
          case (_, Left(err)) => err.toString
          case (_, Right(x))  => fail(s"$id at v$tv must refuse, got $x")
        }
        assert(r.contains("ArithmeticException") && r.contains("/ by zero"),
          s"$id at v$tv must throw ArithmeticException: / by zero, got: ${r.take(90)}")
      }
    }
  }

  test("NO row straddles a version gate, and v3/v0 carry the same body") {
    ids.foreach { id =>
      val rs = rows(id)
      assertEquals(rs.map(treeV).sorted, List(0, 3), s"$id must be authored at v0 AND v3")
      assertEquals(rs.map(errored).distinct.size, 1, s"$id must reach the SAME verdict at both versions")
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

  test("envelope: the 6.0.7 stamp") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
