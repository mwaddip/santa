package santa

import io.circe.Json

/** Anchors the argument-widening families against the measured reply (prompts/drafts/sigma-rust-argument-kind-reply.md):
  * every W row's value and cost, each lie costing its control + 15 (the If, its condition, the taken branch), the
  * refusals, and the v5 family's membership. */
class AuthoredEvalArgumentWideningTest extends munit.FunSuite {
  private val B = AuthoredEvalArgumentWidening
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def id(e: Json): String = e.hcursor.get[String]("script").toOption.get.takeWhile(_ != ']').drop(1)
  private def x(op: String, i: String) = entries(op).find(id(_) == i).getOrElse(fail(s"$op $i")).hcursor.downField("expected")
  private def cost(op: String, i: String): Long = x(op, i).get[Long]("cost").toOption.getOrElse(fail(s"$op $i: no cost"))
  private def errored(op: String, i: String): Boolean = x(op, i).get[String]("error").toOption.contains("errored")

  test("the W rows' costs, as measured in the reply") {
    val want = Map("W1c" -> 44L, "W1" -> 59L, "W1d" -> 44L, "W1s" -> 59L, "W2c" -> 49L, "W2" -> 64L, "W2s" -> 49L,
      "W3c" -> 44L, "W3" -> 59L, "W4c" -> 55L, "W4" -> 70L, "W5c" -> 56L, "W5a" -> 71L, "W5b" -> 71L, "W6c" -> 40L,
      "W6" -> 55L, "W7c" -> 64L, "W7" -> 79L, "W7b" -> 64L, "W8c" -> 765L, "W8a" -> 780L, "W8b" -> 780L,
      "W9c" -> 69L, "W9" -> 84L, "W9s" -> 69L, "W10c" -> 25L, "W10" -> 40L)
    want.foreach { case (i, c) => assertEquals(cost(B.Op6, i), c, i) }
  }

  test("the refusals: the narrowing, the handlers, the Byte parameter, the items, the IV consumers, the kinds") {
    Seq("W1n", "X1", "X2", "X3", "X4", "X5", "Q1", "Q3", "K1", "K2", "K3", "K4", "K5", "K6", "K7", "K8", "K9", "K10",
      "U1", "U2", "U3", "U4").foreach(i => assert(errored(B.Op6, i), i))
    Seq("Q1c", "Q2", "Q2c", "U0", "U5", "U6", "U7", "U8", "U9", "R1", "R2", "R3", "R4t", "R4tl", "R4tc", "R5", "R6", "R7")
      .foreach(i => assert(!errored(B.Op6, i), i))
  }

  test("v6 holds every row; v5 the rows of v5 methods, at tree v0 activated 2") {
    assertEquals(entries(B.Op6).size, B.Rows.size)
    assertEquals(entries(B.Op5).size, B.Rows.count(_.v5))
    entries(B.Op5).foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(2))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(0))
    }
  }

  test("envelope + write staging files") {
    B.Ops.foreach(op => assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    B.Ops.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
