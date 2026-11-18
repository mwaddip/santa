package santa

import io.circe.Json

/** Anchors the AND / OR round's whole-tree SPENDS: every one of the request's whole trees at each of its activated
  * versions, in the request's SELF with its extension; the verdict the jars' (`extract()` fails loud otherwise). */
class AuthoredTxAndOrItemByItemRoundTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredTxAndOrItemByItemRound
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries(a: Int): List[Json] =
    blessed(a).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)

  test("every whole tree at each activated version; the box is the request's SELF, the input its extension") {
    assertEquals(spends.size, 78)
    assertEquals(entries(3).size + entries(2).size, 78)
    Seq(2, 3).foreach { a =>
      spends.filter(_.activated == a).zip(entries(a)).foreach { case (s, e) =>
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assert(inBox.contains(s.treeHex), s.name)
        assert(inBox.endsWith(AuthoredEvalAndOrItemByItemRound.SelfHex.drop(6 + 72)), s"${s.name}: SELF's registers")
        assert(e.hcursor.get[String]("tx_bytes_hex").toOption.get.contains(AuthoredEvalAndOrItemByItemRound.ExtHex), s.name)
        assertEquals(e.hcursor.downField("expected").get[Boolean]("valid").toOption, Some(s.spends), s.name)
      }
    }
    println(s"spends: ${entries(3).size} at activated 3, ${entries(2).size} at 2; valid " +
      s"${entries(3).count(_.hcursor.downField("expected").get[Boolean]("valid").toOption.get)} / " +
      s"${entries(2).count(_.hcursor.downField("expected").get[Boolean]("valid").toOption.get)}")
  }

  test("envelope + write") {
    Seq(2, 3).foreach(a => assertEquals(blessed(a).hcursor.get[String]("op").toOption, Some(B.Op)))
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
