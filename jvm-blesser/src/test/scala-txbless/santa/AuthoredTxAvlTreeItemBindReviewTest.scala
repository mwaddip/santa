package santa

import io.circe.Json

/** Anchors the AvlTree item-bind addendum's SPENDS. `extract()` requires each verdict to be the jars' (a whole tree) or
  * the eval oracle's (a row); pinned here: the selection, the boxes' trees, and that a context row's box and input carry
  * sigma-rust's SELF and extension. */
class AuthoredTxAvlTreeItemBindReviewTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredTxAvlTreeItemBindReview
  private val E = AuthoredEvalAvlTreeItemBindReview
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries(a: Int): List[Json] =
    blessed(a).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))

  test("the whole trees: every readable one at or below the activated version; the rows: the first per class") {
    val trees = spends.collect { case t: B.TreeSpend => t }
    assertEquals(trees.count(_.activated == 3), 54)
    assertEquals(trees.count(_.activated == 2), 40)
    trees.foreach(t => assert(t.treeVersion <= t.activated, t.name))
    val rows = spends.collect { case r: B.RowSpend => r }
    rows.filter(_.activated == 3).groupBy(s => (s.row.set, s.row.method, s.row.tree, B.carrier(s.row),
      B.outcomeClass(s.outcome), s.row.treeVersion)).foreach { case (k, ss) => assertEquals(ss.size, 1, s"$k") }
    assertEquals(entries(3).size, spends.count(_.activated == 3))
    assertEquals(entries(2).size, spends.count(_.activated == 2))
    println(s"spends: ${entries(3).size} at activated 3 (trees 54), ${entries(2).size} at 2 (trees 40); valid " +
      s"${entries(3).count(valid)} / ${entries(2).count(valid)}")
  }

  test("each box holds its tree; a context row's box is sigma-rust's SELF, its input their extension") {
    Seq(2, 3).foreach { a =>
      spends.filter(_.activated == a).zip(entries(a)).foreach { case (s, e) =>
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assert(inBox.contains(B.boxTree(s)), s"${s.name}: the writer's form of the tree is in the input box")
        assertEquals(B.boxTree(s) != s.treeHex, s.name.startsWith("census|substConstants|"), s"${s.name}: the long form")
        assertEquals(valid(e), s.spends, s"${s.name} a$a")
        assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption.isDefined, valid(e), s.name)
        s match {
          case r: B.RowSpend if r.row.inContext =>
            assert(inBox.endsWith(E.SelfHex.drop(6 + 72)), s"${s.name}: SELF's registers and reference")
            assert(e.hcursor.get[String]("tx_bytes_hex").toOption.get.contains(E.ExtHex), s"${s.name}: the extension")
          case _ =>
        }
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
