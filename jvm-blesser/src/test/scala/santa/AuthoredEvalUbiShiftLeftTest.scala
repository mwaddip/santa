package santa

import java.math.BigInteger

import io.circe.Json

/** Anchors the UnsignedBigInt.shiftLeft family. `SpecExtract` already fails loud if an accept does not
  * evaluate or a reject does; pinned here is what would rot silently while still grading green:
  *
  *  - every row runs the spec's row-16 tree, verbatim (read back from the committed spec vector);
  *  - the controls' values and their cost, 104;
  *  - each refusal's MECHANISM, from the oracle's own string: U1-U3 the CONSTRUCTOR's unwrapped
  *    "Too big unsigned big int value", L1/L2 the amount bound's IllegalArgumentException.
  */
class AuthoredEvalUbiShiftLeftTest extends munit.FunSuite {
  private val B = AuthoredEvalUbiShiftLeft
  private lazy val v = B.extract()
  private def entries: List[Json] =
    v(B.Op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def row(i: String): Json = entries.find(id(_) == i).getOrElse(fail(s"no row $i"))
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")

  test("8 rows on the spec's row-16 tree: 3 controls, 5 refusals") {
    assertEquals(entries.size, 8)
    assertEquals(entries.count(errored), 5)
    val spec = io.circe.parser.parse(new String(java.nio.file.Files.readAllBytes(
      java.nio.file.Paths.get("..", "vectors", "eval", "v6", "spec", "UnsignedBigInt_methods.json")), "UTF-8"))
      .fold(e => fail(s"spec: $e"), identity)
    val row16 = spec.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"$e"), identity)
      .find(_.hcursor.get[String]("name").toOption.contains("(CUnsignedBigInt(3),3)#16")).getOrElse(fail("row 16"))
    assertEquals(str(row16, "tree_bytes_hex"), B.Row16, "the spec's row 16 tree")
    entries.foreach { e =>
      assertEquals(str(e, "tree_bytes_hex"), B.Row16, str(e, "name"))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
    }
  }

  test("THE CONTROLS: 2^255, 2^255 and 0, each at 104") {
    Seq("U4" -> B.p2(255), "U5" -> B.p2(255), "U6" -> BigInteger.ZERO).foreach { case (i, want) =>
      val x = row(i).hcursor.downField("expected")
      assertEquals(x.downField("value").get[String]("kind").toOption, Some("UnsignedBigInt"), i)
      assertEquals(x.downField("value").get[String]("value").toOption.map(new BigInteger(_)), Some(want), i)
      assertEquals(x.get[Long]("cost").toOption, Some(104L), i)
    }
  }

  test("THE MECHANISM: U1-U3 the constructor's unwrapped ArithmeticException, L1/L2 the amount bound") {
    def refusal(i: String): String = {
      val (_, x, n, _, _, _) = B.Rows.find(_._1 == i).get
      EvalCore.evalApplied(B.Row16, B.pair(x, n), B.V3)._2 match {
        case Left(err) => err
        case Right(r)  => fail(s"$i must throw, got $r")
      }
    }
    Seq("U1", "U2", "U3").foreach { i =>
      val err = refusal(i)
      assert(err.startsWith("ArithmeticException") && err.contains("Too big unsigned big int value"),
        s"$i must fail the constructor's range check, got: ${err.take(160)}")
    }
    Seq("L1", "L2").foreach { i =>
      val err = refusal(i)
      assert(err.startsWith("IllegalArgumentException") && err.contains("bits < 0 || bits >= 256"),
        s"$i must fail the amount bound, got: ${err.take(160)}")
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
