package santa

import io.circe.Json

/** Anchors the Context property-call TRANSACTION vectors. `extract()` requires each verdict to be the one the
  * family predicts; pinned here: the split, every valid spend's cost (the same at both tree versions), that each
  * separating row is refused on input #1's script reducing to FALSE (not on an exception), the probe tree verbatim
  * in input 1's box, the property call's own bytes in every non-control tree, and the distinct context. */
class AuthoredTxContextPropertyCallsTest extends munit.FunSuite {
  private val B = AuthoredTxContextPropertyCalls
  private lazy val blessed = B.extract()
  private def entries: List[Json] =
    blessed.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def id(e: Json): String = str(e, "description").takeWhile(_ != ']').drop(1)
  private def tv(e: Json): Int = e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("ergoTree"))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def reason(e: Json): String = e.hcursor.downField("expected").get[String]("reason").toOption.getOrElse("")
  private def boxes(e: Json): List[String] =
    e.hcursor.downField("input_boxes_hex").as[List[String]].fold(err => fail(s"$err"), identity)

  test("32 spends, 16 per tree version (v3 then v0): the four separating rows invalid at each, the rest valid") {
    assertEquals(entries.size, 32)
    assertEquals(entries.map(tv), List.fill(16)(3) ++ List.fill(16)(0))
    B.TreeVersions.foreach { t =>
      val es = entries.filter(tv(_) == t)
      assertEquals(es.map(id), B.Rows.map(_.id).toList, s"t$t")
      assertEquals(es.filterNot(valid).map(id), List("I3!", "O2!", "Hk!", "Sv!"), s"t$t")
    }
  }

  test("every valid spend's cost, identical at tree v3 and v0") {
    val want = Map("I=" -> 14311L, "I2" -> 14310L, "Ic" -> 14309L, "O=" -> 14312L, "O3" -> 14310L, "Oc" -> 14309L,
      "H=" -> 14312L, "Hk" -> 14310L, "Hc" -> 14309L, "S=" -> 14309L, "Sv" -> 14309L, "Sc" -> 14309L)
    entries.filter(valid).foreach(e => assertEquals(cost(e), Some(want(id(e))), s"${id(e)} t${tv(e)}"))
    assertEquals(entries.count(valid), 2 * want.size)
  }

  test("each separating row: input #1's script reduces to FALSE, no exception, no cost") {
    entries.filterNot(valid).foreach { e =>
      val r = reason(e)
      assertEquals(cost(e), None, id(e))
      assert(r.contains("Scripts of all transaction inputs should pass verification") && r.contains("#1 => Success((false"),
        s"${id(e)} t${tv(e)}: $r")
      assert(str(e, "description").contains("SEPARATING ROW"), id(e))
    }
  }

  test("input 1 is the probe box carrying the tree verbatim; every non-control tree holds the property call") {
    val call = Map('I' -> "db6504fe", 'O' -> "db6505fe", 'H' -> "db6506fe", 'S' -> "db6507fe")
    val node = Map('I' -> "a4", 'O' -> "a5", 'H' -> "a3", 'S' -> "a7")
    B.TreeVersions.foreach { t =>
      B.Rows.zip(entries.filter(tv(_) == t)).foreach { case (r, e) =>
        val tree = B.treeOf(r, t)
        assertEquals(tree.take(2), if (t == 3) "0b" else "00", s"${r.id} t$t: header")
        val body = if (t == 3) tree.drop(4) else tree.drop(2)
        assert(body.startsWith("d1"), s"${r.id} t$t: sigmaProp root")
        val bs = boxes(e)
        assertEquals(bs.size, 2, r.id)
        assert(bs(1).contains(tree), s"${r.id} t$t: the tree verbatim in input 1")
        assert(!bs(0).contains(tree), s"${r.id} t$t: input 0 is the other box")
        if (r.id.endsWith("c")) {
          assert(!body.contains("db65"), s"${r.id}: a control has no property call")
          assert(body.contains(node(r.id.head)), s"${r.id}: the control's node")
        } else assert(body.contains(call(r.id.head)), s"${r.id} t$t: the property call")
      }
    }
  }

  test("every entry carries the shared rent context, activated 3, and the distinct heights") {
    entries.foreach { e =>
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(B.H))
      assertEquals(e.hcursor.downField("preHeader").get[Int]("height").toOption, Some(B.H))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("headers_hex").as[List[String]].toOption.map(_.size), Some(9))
    }
    assertEquals(B.H, 1051200)
  }

  test("envelope + write") {
    assertEquals(blessed.hcursor.get[String]("op").toOption, Some(B.Op))
    assertEquals(blessed.hcursor.get[String]("blessed_by").toOption, Some("jvm:ergo-core-6.0.7-validateStateful"))
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
