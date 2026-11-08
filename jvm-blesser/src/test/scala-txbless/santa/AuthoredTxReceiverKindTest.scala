package santa

import io.circe.Json

/** Anchors the receiver-kind TRANSACTION vectors. `extract()` requires each verdict to be the one
  * the source predicts, so this pins the split and the control's cost — and that exactly one row
  * is a control, since a second valid row would mean an evaluator had stopped reading its
  * receiver on the oracle's own side. */
class AuthoredTxReceiverKindTest extends munit.FunSuite {
  private val B = AuthoredTxReceiverKind
  private lazy val blessed = B.extract()
  private def entries: List[Json] =
    blessed.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption

  test("7 spends: 1 control valid at 12109, 6 separating rows invalid") {
    assertEquals(entries.size, 7)
    assertEquals(entries.count(valid), 1, "exactly one control — a second would mean the oracle changed")
    val control = entries.find(valid).get
    assertEquals(cost(control), Some(12109L))
    entries.filterNot(valid).foreach { e =>
      assertEquals(cost(e), None, s"${str(e, "name")}: an invalid spend has no cost")
      assert(e.hcursor.downField("expected").get[String]("reason").toOption.exists(_.nonEmpty),
        s"${str(e, "name")}: must say why")
      assert(str(e, "description").contains("SEPARATING ROW"), str(e, "name"))
    }
    assert(str(control, "description").contains("THE CONTROL"))
  }

  test("the control is row 1, and every row's tree is the probe's own") {
    val control = entries.find(valid).get
    assert(str(control, "name").contains(
      ReceiverKindTrees.rows.find(_.n == ReceiverKindTrees.SpendControlRow).get.slug))
    ReceiverKindTrees.wrappedTrees.foreach { case (n, tree) =>
      assert(entries.exists(e =>
        e.hcursor.downField("input_boxes_hex").as[List[String]].fold(_ => Nil, identity)
          .exists(_.contains(tree))),
        s"row $n's tree must appear verbatim in an input box")
    }
  }

  test("every entry carries the shared rent context and one input") {
    entries.foreach { e =>
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("input_boxes_hex").as[List[String]].fold(_ => Nil, identity).size, 1)
    }
  }

  test("envelope + write") {
    assertEquals(blessed.hcursor.get[String]("op").toOption, Some("receiver-kind"))
    assertEquals(blessed.hcursor.get[String]("blessed_by").toOption,
      Some("jvm:ergo-core-6.0.7-validateStateful"))
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
