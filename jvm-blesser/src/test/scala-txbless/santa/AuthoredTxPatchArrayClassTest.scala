package santa

import io.circe.Json

/** Anchors the patch-array-class SPENDS. `extract()` requires each verdict to be the jars' (a value spends, a throw does
  * not, for a ClassCastException); pinned here: the selection (one move and one control per receiver and tree version),
  * where each tree travels, and that the box and the extension carry sigma-rust's context values. */
class AuthoredTxPatchArrayClassTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredTxPatchArrayClass
  private val E = AuthoredEvalPatchArrayClass
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries(a: Int): List[Json] =
    blessed(a).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))

  test("one move and one control per receiver and tree version: 248 spends at activated 3, 120 at 2") {
    assertEquals(entries(3).size, 248)
    assertEquals(entries(2).size, 120)
    val byRow = spends.groupBy(s => (s.row.receiver, s.row.treeVersion))
    assertEquals(byRow.size, 124)
    assertEquals(spends.map(_.row.receiver).distinct.size, 64)
    byRow.foreach { case (k, ss) =>
      assertEquals(ss.map(_.name).distinct.size, 2, s"$k")
      assertEquals(ss.filter(_.move).map(_.name).distinct.size, 1, s"$k")
    }
  }

  test("each tree in its box; the verdict is the jars'; costs only on a spend; nine headers") {
    Seq(2, 3).foreach { a =>
      spends.filter(_.activated == a).zip(entries(a)).foreach { case (s, e) =>
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assert(inBox.contains(s.treeHex), s"${s.name}: the tree must appear verbatim in the input box")
        assert(s.treeHex.contains(B.rootOf(s.row.body)), s.name)
        assert(str(e, "description").startsWith(s"[${s.name}]"), s.name)
        assertEquals(valid(e), s.spendsOnANode, s"${s.name} a$a")
        assertEquals(valid(e), s.row.jarsOutcome == "value" || s.row.nodeBuiltGeneric, s"${s.name} a$a")
        assertEquals(str(e, "description").contains("SEPARATING ROW"), s.move, s.name)
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(s.row.treeVersion))
        assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption.isDefined, valid(e), s.name)
        assertEquals(e.hcursor.downField("headers_hex").as[List[String]].toOption.map(_.size), Some(9))
      }
    }
  }

  test("a node's verdict is the eval tier's outcome for the same row, at the same activated version") {
    spends.foreach { s =>
      val evalAnswers = E.outcome(s.row).isInstanceOf[E.Answer]
      assertEquals(evalAnswers, s.spendsOnANode, s"${s.name} a${s.activated}")
    }
    assertEquals(spends.count(s => s.move && s.spendsOnANode), 6) // the pre-header's two, at three slots
  }

  test("the box holds R4..R6 and the token, the extension variables 1 and 2: sigma-rust's values, as bytes") {
    Seq(2, 3).foreach { a =>
      spends.filter(_.activated == a).zip(entries(a)).foreach { case (s, e) =>
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assert(inBox.contains("22" * 32 + "01" + "03" + E.Var1Bytes + E.Var2Bytes + B.R6Bytes), s"${s.name}: registers")
        assert(str(e, "tx_bytes_hex").contains("02" + "01" + E.Var1Bytes + "02" + E.Var2Bytes), s"${s.name}: extension")
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
