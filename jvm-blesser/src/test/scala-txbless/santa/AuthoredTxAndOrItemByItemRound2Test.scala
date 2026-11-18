package santa

import io.circe.Json

/** Anchors the AND / OR addendum's whole-tree SPENDS: every whole tree at its activated version, once per file; the
  * plain box for the trees that read no context, the addendum's SELF with its extension for the others; the verdict
  * the jars' (`extract()` fails loud otherwise). */
class AuthoredTxAndOrItemByItemRound2Test extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredTxAndOrItemByItemRound2
  private val E = AuthoredEvalAndOrItemByItemRound2
  private lazy val blessed = B.extract()
  private lazy val plan = B.plan()
  private def entries(k: (String, Int)): List[Json] =
    blessed(k).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)

  test("every whole tree at its activated version, once per file; the box and the extension of its part") {
    val whole = E.allRows().filter(_.whole)
    assertEquals(whole.size, 219 + 425)
    val unholdable = whole.filterNot(B.boxParses)
    unholdable.foreach(r => println(s"no box holds ${r.name} a${r.activated}: ${B.boxParse(r).failed.get.getMessage.take(140)}"))
    unholdable.foreach(r => assert(r.name.contains("|ph|dnnK|") && r.jarsOutcome != "value", r.name))
    val rewritten = whole.filter(r => B.boxParses(r) && B.boxTree(r) != r.treeHex)
    println(s"held in the writer's form: ${rewritten.map(r => s"${r.name} a${r.activated}").mkString(", ")}")
    assertEquals(plan.values.map(p => p._1.size + p._2.size).sum, whole.size - unholdable.size)
    plan.foreach { case (k @ (op, a), (rs, repeats)) =>
      repeats.foreach(r => assert(rs.exists(x => B.boxTree(x) == B.boxTree(r) && B.spends(x) == B.spends(r)), s"${r.name}: a repeat"))
      assertEquals(entries(k).size, rs.size, k.toString)
      rs.zip(entries(k)).foreach { case (r, e) =>
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assertEquals(inBox, B.boxHex(r), r.name)
        val txHex = e.hcursor.get[String]("tx_bytes_hex").toOption.get
        if (op == "segregated-tuple") assert(!txHex.contains(E.Ext2Hex), r.name) else assert(txHex.contains(E.Ext2Hex), r.name)
        assertEquals(e.hcursor.downField("expected").get[Boolean]("valid").toOption, Some(B.spends(r)), r.name)
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a), r.name)
      }
      println(s"$op a$a: ${rs.size} (${repeats.size} repeats left out), valid " +
        s"${entries(k).count(_.hcursor.downField("expected").get[Boolean]("valid").toOption.get)}")
    }
  }

  test("envelope + write") {
    blessed.foreach { case ((op, _), j) => assertEquals(j.hcursor.get[String]("op").toOption, Some(op)) }
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
