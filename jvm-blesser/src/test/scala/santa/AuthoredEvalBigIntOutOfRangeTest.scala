package santa

import io.circe.Json

/** Anchors the out-of-range BigInt family against the measured reply (prompts/drafts/sigma-rust-bigint-range-reply.md):
  * the B rows' costs, each container row costing exactly its in-range twin, and the §1 bodies equal to the request's
  * own hex (the reply derived them byte-identical). */
class AuthoredEvalBigIntOutOfRangeTest extends munit.FunSuite {
  private val B = AuthoredEvalBigIntOutOfRange
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"$e"), identity)
  private def id(e: Json): String = e.hcursor.get[String]("script").toOption.get.takeWhile(_ != ']').drop(1)
  private def cost(i: String): Long =
    entries.find(id(_) == i).flatMap(_.hcursor.downField("expected").get[Long]("cost").toOption).getOrElse(fail(i))

  test("the B rows' costs (the reply's, at activated 3; the same at activated 2)") {
    val want = Map("B1" -> 65L, "B2" -> 65L, "B4" -> 65L, "B5" -> 65L, "B7" -> 60L, "B8" -> 65L, "B9" -> 50L,
      "B10" -> 50L, "B11" -> 60L, "B12" -> 60L, "B13" -> 75L, "B14" -> 45L, "B15" -> 65L, "B16" -> 65L, "B17" -> 95L)
    want.foreach { case (i, c) => assertEquals(cost(i), c, i) }
  }

  test("each container row costs exactly its in-range twin; the request's §1 hex, byte-identical") {
    Seq("S1", "S2", "S3", "S4", "S5").foreach(i => assertEquals(cost(i), cost(s"${i}t"), i))
    val Xh = "9d0620" + "80" + "00" * 31 + "0601ff"
    assertEquals(B.Rows.find(_.id == "B13").get.body, "93" + Xh + Xh)
    assertEquals(B.Rows.find(_.id == "B16").get.body, "9e060107" + Xh)
  }

  test("envelope + write staging files") {
    assertEquals(v(B.Op).hcursor.get[String]("source").toOption, Some(B.Source))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
