package santa

import io.circe.Json

/** Anchors the collection-length-limit whole-tree SPENDS: every whole tree at its activated version, once per file, in
  * the plain box; the box holds the writer's form; the verdict the jars' (`extract()` fails loud otherwise). */
class AuthoredTxCollectionLengthLimitTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredTxCollectionLengthLimit
  private val E = AuthoredEvalCollectionLengthLimit
  private lazy val blessed = B.extract()
  private lazy val plan = B.plan()
  private def entries(a: Int): List[Json] =
    blessed(a).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)

  test("every whole tree at its activated version, once per file; the plain box, the writer's form, the jars' verdict") {
    val whole = E.allRows().filter(_.whole)
    assertEquals(whole.size, 64)
    val rewritten = whole.filter(r => B.boxTree(r) != r.rowTreeHex)
    println(s"held in the writer's form: ${rewritten.map(r => s"${r.name} a${r.activated}").mkString(", ")}")
    assertEquals(plan.values.map(p => p._1.size + p._2.size).sum, whole.size)
    plan.foreach { case (a, (rs, repeats)) =>
      repeats.foreach(r => assert(rs.exists(x => B.boxTree(x) == B.boxTree(r) && B.spends(x) == B.spends(r)), s"${r.name}: a repeat"))
      assertEquals(entries(a).size, rs.size, a.toString)
      rs.zip(entries(a)).foreach { case (r, e) =>
        assertEquals(e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head, B.boxHex(r), r.name)
        assertEquals(e.hcursor.downField("expected").get[Boolean]("valid").toOption, Some(B.spends(r)), r.name)
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a), r.name)
      }
      println(s"${B.Op} a$a: ${rs.size} (${repeats.size} repeats left out), valid " +
        s"${entries(a).count(_.hcursor.downField("expected").get[Boolean]("valid").toOption.get)}")
    }
  }

  test("envelope + write") {
    blessed.foreach { case (_, j) => assertEquals(j.hcursor.get[String]("op").toOption, Some(B.Op)) }
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
