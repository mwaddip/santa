package santa

import io.circe.Json

/** Anchors the AND / OR item-by-item SPENDS. `extract()` requires each verdict to be the eval oracle's; pinned here:
  * the selection, that each tree travels as written (it is in the writer's form), and the verdicts' premises. */
class AuthoredTxAndOrItemByItemTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredTxAndOrItemByItem
  private val E = AuthoredEvalAndOrItemByItem
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries(a: Int): List[Json] =
    blessed(a).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))

  test("the selection: every ladder and expression row, one per tuple shape and collection kind, at each slot") {
    val rows = spends.map(_.row).distinct
    rows.groupBy(r => (r.op, r.group, E.cls(r), B.shapeKey(r))).foreach { case (k, rs) => assertEquals(rs.size, 1, s"$k") }
    assertEquals(rows.count(_.group == "ladder"), E.baseRows().count(_.group == "ladder"))
    assertEquals(rows.count(_.group == "expr"), E.baseRows().count(_.group == "expr"))
    spends.groupBy(_.row).foreach { case (r, ss) =>
      assertEquals(ss.map(s => (s.slot.treeVersion, s.activated)).sorted, Vector((0, 2), (0, 3), (3, 3)), r.name)
    }
    assertEquals(entries(3).size, spends.count(_.activated == 3))
    assertEquals(entries(2).size, spends.count(_.activated == 2))
    println(s"spends: ${entries(3).size} at activated 3, ${entries(2).size} at 2; " +
      s"valid ${entries(3).count(valid)} / ${entries(2).count(valid)}")
  }

  test("each tree in its box as written; the verdict is the eval oracle's; costs only on a spend; nine headers") {
    Seq(2, 3).foreach { a =>
      spends.filter(_.activated == a).zip(entries(a)).foreach { case (s, e) =>
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assert(inBox.contains(s.treeHex), s"${s.name}: the tree must appear verbatim in the input box")
        assert(str(e, "description").startsWith(s"[${s.name}]"), s.name)
        assertEquals(valid(e), s.spends, s"${s.name} a$a")
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(s.slot.treeVersion))
        assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption.isDefined, valid(e), s.name)
        assertEquals(e.hcursor.downField("headers_hex").as[List[String]].toOption.map(_.size), Some(9))
      }
    }
  }

  test("envelope + write") {
    Seq(2, 3).foreach { a =>
      assertEquals(blessed(a).hcursor.get[String]("op").toOption, Some(B.Op))
      assertEquals(blessed(a).hcursor.get[String]("blessed_by").toOption, Some("jvm:ergo-core-6.0.7-validateStateful"))
    }
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
