package santa

import io.circe.Json

/** Anchors the AvlTree item-bind SPENDS. `extract()` requires each verdict to be the eval oracle's (an answer spends, a
  * throw does not); pinned here: the selection (the first row per method, tree, carrier, outcome class and tree
  * version), where each tree travels, and that the verdict is the jars' own measurement too. */
class AuthoredTxAvlTreeItemBindTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredTxAvlTreeItemBind
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries(a: Int): List[Json] =
    blessed(a).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))

  test("the first row per method, tree, carrier, outcome class and tree version, at each of its activated versions") {
    val groups = spends.filter(_.activated == 3)
      .groupBy(s => (s.row.method, s.row.tree, s.row.carrier, B.outcomeClass(s.outcome), s.row.treeVersion))
    groups.foreach { case (k, ss) => assertEquals(ss.size, 1, s"$k") }
    assertEquals(groups.size, spends.count(_.activated == 3))
    spends.filter(_.row.treeVersion == 0).groupBy(_.name).foreach { case (n, ss) =>
      assertEquals(ss.map(_.activated).sorted, Vector(2, 3), n)
    }
    assertEquals(entries(3).size, spends.count(_.activated == 3))
    assertEquals(entries(2).size, spends.count(_.activated == 2))
    println(s"spends: ${entries(3).size} at activated 3, ${entries(2).size} at 2")
  }

  test("each tree in its box; the verdict is the eval oracle's and the jars'; costs only on a spend; nine headers") {
    Seq(2, 3).foreach { a =>
      spends.filter(_.activated == a).zip(entries(a)).foreach { case (s, e) =>
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assert(inBox.contains(s.writtenTreeHex), s"${s.name}: the writer's form of the tree is in the input box")
        assert(s.treeHex.contains(B.rootOf(s.row.body)), s.name)
        // the writer's form differs exactly where the row writes a Coll[Coll[<primitive>]] type as `0c` + its code
        val longForm = s.row.carrier == "c" && s.row.side.forall(_ == "nopair") &&
          s.row.kind.exists(Set("Coll[Int]", "Coll[Boolean]", "Coll[Long]"))
        assertEquals(s.writtenTreeHex != s.treeHex, longForm, s.name)
        assertEquals(str(e, "description").contains("the long form"), longForm, s.name)
        assert(str(e, "description").startsWith(s"[${s.name}]"), s.name)
        assertEquals(valid(e), s.answers, s"${s.name} a$a")
        assertEquals(valid(e), s.row.jarsOutcome == "value", s"${s.name} a$a: the jars' table")
        assertEquals(str(e, "description").contains("SEPARATING ROW"), !s.row.control, s.name)
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(s.row.treeVersion))
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
