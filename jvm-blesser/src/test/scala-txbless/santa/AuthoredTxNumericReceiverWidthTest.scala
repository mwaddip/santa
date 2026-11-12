package santa

import io.circe.Json

/** Anchors the numeric receiver-width TRANSACTION vectors. `extract()` requires each verdict to be
  * the one the source predicts; this pins the split, the controls' costs, the refusal's class, and
  * that every spend tree is `sigmaProp(row == row)` over sigma-rust's own encoding of the row. */
class AuthoredTxNumericReceiverWidthTest extends munit.FunSuite {
  private val B = AuthoredTxNumericReceiverWidth
  private lazy val blessed = B.extract()
  private def entries: List[Json] =
    blessed.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def reason(e: Json): String =
    e.hcursor.downField("expected").get[String]("reason").toOption.getOrElse("")
  private def inputBox(e: Json): String =
    e.hcursor.downField("input_boxes_hex").as[List[String]].fold(_ => Nil, identity) match {
      case List(one) => one
      case other     => fail(s"${str(e, "name")}: want one input box, got ${other.size}")
    }

  test("12 spends: the two controls valid, the ten wrong-width rows invalid on a ClassCastException") {
    assertEquals(entries.size, 12)
    val (ok, refused) = entries.partition(valid)
    assertEquals(ok.map(str(_, "name")),
      List("numeric-receiver-width-spend-n0#0", "numeric-receiver-width-spend-n5#5"))
    ok.foreach(e => assert(str(e, "description").contains("THE CONTROL"), str(e, "name")))
    refused.foreach { e =>
      assertEquals(cost(e), None, s"${str(e, "name")}: an invalid spend has no cost")
      assert(reason(e).contains("ClassCastException"), s"${str(e, "name")}: ${reason(e)}")
      assert(str(e, "description").contains("SEPARATING ROW"), str(e, "name"))
    }
  }

  test("the controls' costs") {
    assertEquals(entries.filter(valid).map(cost), List(Some(12106L), Some(12105L)))
  }

  test("every spend tree is sigmaProp(row == row) over sigma-rust's own row body, verbatim in the box") {
    AuthoredEvalNumericReceiverWidth.Rows.zip(entries).foreach { case ((id, _, hex, ast, _), e) =>
      // sigma-rust's hex is the size-flagged v3 eval tree: 0b, a one-byte size, the call.
      assertEquals(hex.take(2), "0b", id)
      val body = hex.drop(4)
      assertEquals(Integer.parseInt(hex.slice(2, 4), 16), body.length / 2, s"$id: the size byte")
      val spend = "d193" + body + body
      val want = "0b" + "%02x".format(spend.length / 2) + spend
      assertEquals(B.wrapped(ast), want, id)
      assert(inputBox(e).contains(want), s"$id: the tree must appear verbatim in the input box")
    }
  }

  test("every entry carries the shared rent context and the v3 pair") {
    entries.foreach { e =>
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
    }
  }

  test("envelope + write") {
    assertEquals(blessed.hcursor.get[String]("op").toOption, Some(B.Op))
    assertEquals(blessed.hcursor.get[String]("blessed_by").toOption,
      Some("jvm:ergo-core-6.0.7-validateStateful"))
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
