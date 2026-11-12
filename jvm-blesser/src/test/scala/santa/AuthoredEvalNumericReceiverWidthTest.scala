package santa

import io.circe.Json

/** Anchors the numeric receiver-width family. `SpecExtract` already fails loud if a control does not
  * evaluate or a refusal does not refuse; pinned here is what would rot silently while still grading
  * green:
  *
  *  - each tree is the sigma-rust session's own encoding, re-built from its AST (so a vector and the
  *    probe it answers cannot drift apart);
  *  - the two controls' exact values and costs;
  *  - each refusal's MECHANISM, from the oracle's own string: a `ClassCastException` to the OWNER's
  *    class — the receiver's for N1-N10, the argument's for A1, whose receiver is the right width;
  *  - the role declarations.
  */
class AuthoredEvalNumericReceiverWidthTest extends munit.FunSuite {
  private val B = AuthoredEvalNumericReceiverWidth
  private lazy val v = B.extract()
  private def entries: List[Json] =
    v(B.Op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def row(i: String): Json = entries.find(id(_) == i).getOrElse(fail(s"no row $i"))
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")

  test("12 rows, each tree the probe's own encoding, re-built from its AST") {
    assertEquals(entries.size, 12)
    B.Rows.foreach { case (i, _, hex, ast, _) =>
      assertEquals(B.tree(ast()), hex, s"$i: AST vs the probe's hex")
      assertEquals(str(row(i), "tree_bytes_hex"), hex, s"$i: the vector's tree")
    }
  }

  test("THE CONTROLS: toBytes on a Long gives 8 bytes @14, shiftLeft(1) on a Long gives Long 2 @19") {
    val n0 = row("N0").hcursor.downField("expected")
    assertEquals(n0.downField("value").focus.map(_.noSpaces), Some(Json.obj(
      "kind" -> Json.fromString("Coll"), "elem" -> Json.obj("tag" -> Json.fromString("SByte")),
      "items" -> Json.arr((Seq.fill(7)(0) :+ 1).map(b => Json.obj("kind" -> Json.fromString("Byte"),
        "value" -> Json.fromInt(b))): _*)).noSpaces))
    assertEquals(n0.get[Long]("cost").toOption, Some(14L))
    val n5 = row("N5").hcursor.downField("expected")
    assertEquals(n5.downField("value").focus.map(_.noSpaces), Some("""{"kind":"Long","value":"2"}"""))
    assertEquals(n5.get[Long]("cost").toOption, Some(19L))
    assertEquals(entries.count(errored), 10)
  }

  test("THE MECHANISM: every refusal is a ClassCastException to the OWNER's class, from the oracle") {
    val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
    B.Rows.collect { case (i, _, hex, _, Some(cls)) => (i, hex, cls) }.foreach { case (i, hex, cls) =>
      EvalCore.evalApplied(hex, input, B.V3)._2 match {
        case Left(err) =>
          assert(err.startsWith("ClassCastException") && err.contains(s"cannot be cast to class $cls"),
            s"$i must fail the owner's cast to $cls, got: ${err.take(140)}")
        case Right(r) => fail(s"$i must throw, got $r")
      }
    }
    // A1 is the ARGUMENT's cast: its receiver is a Long, the owner's width, so the Integer is the argument
    assert(str(row("A1"), "tree_bytes_hex").startsWith("0b08dc05090502"), "A1's receiver must be Long 1")
  }

  test("every row states its role: N0/N5 are CONTROLS, the rest SEPARATING") {
    entries.foreach { e =>
      val d = str(e, "script")
      if (Set("N0", "N5")(id(e))) assert(d.contains("CONTROL"), s"${id(e)}: $d")
      else assert(d.contains("SEPARATING"), s"${id(e)} must say what it separates: $d")
    }
  }

  test("envelope + write staging files") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some(B.Stamp))
    assertEquals(v(B.Op).hcursor.get[String]("source").toOption, Some(B.Source))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
