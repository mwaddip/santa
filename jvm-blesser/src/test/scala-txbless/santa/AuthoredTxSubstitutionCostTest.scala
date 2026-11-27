package santa

import io.circe.Json

/** Bless ergots' Deserialize substitution cost requests. blessAll() fails loud unless the JVM gives each entry ergots'
  * probe figure; this pins the files' shape and writes them. */
class AuthoredTxSubstitutionCostTest extends munit.FunSuite {
  import AuthoredTxSubstitutionCost._
  private lazy val blessed = blessAll().toMap

  private def entries(path: String): List[Json] =
    blessed(path).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)

  test("V6 (block version 4) and before V6 (block version 3): the entry counts and activated versions") {
    assertEquals(entries(V6Path).size, 24)
    assertEquals(entries(V5Path).size, 17)
    for ((path, activated) <- Seq(V6Path -> 3, V5Path -> 2); e <- entries(path)) {
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(activated), path)
      assertEquals(e.hcursor.downField("preHeader").get[Int]("version").toOption, Some(activated + 1), path)
    }
  }

  test("print the blessed verdicts") {
    blessed.foreach { case (path, _) =>
      entries(path).foreach { e =>
        val c = e.hcursor
        println(f"  ${c.get[String]("name").getOrElse("?")}%-52s valid=${c.downField("expected").get[Boolean]("valid").getOrElse("?")} " +
          s"cost=${c.downField("expected").get[Long]("cost").getOrElse(-1L)}")
      }
    }
  }

  test("write step: files land under vectors/transaction/") {
    val root = java.nio.file.Paths.get("..", "vectors")
    writeVectors(blessed.toSeq, root)
    blessed.keys.foreach(rel => assert(java.nio.file.Files.exists(root.resolve(rel)), s"not written: $rel"))
  }
}
