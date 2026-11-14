package santa

import io.circe.Json

/** Anchors the zero-argument method-call family: each call costs its property-call twin below v3, is refused at v3,
  * and the wire re-writes it as the property call below v3 and rejects it at v3. */
class AuthoredMethodCallNoArgsTest extends munit.FunSuite {
  private val B = AuthoredMethodCallNoArgs
  private def entries(j: Json): List[Json] = j.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"$e"), identity)

  test("each method call costs its property-call twin at v0; refused at v3; the wire re-writes at v0, rejects at v3") {
    val e5 = entries(B.evalExtract()(B.Op5))
    def cost(i: String) = e5.find(_.hcursor.get[String]("script").toOption.get.startsWith(s"[$i]")).get
      .hcursor.downField("expected").get[Long]("cost").toOption.get
    Seq("I", "O", "H", "S").foreach(i => assertEquals(cost(i), cost(s"${i}p"), i))
    val e6 = entries(B.evalExtract()(B.Op6))
    assertEquals(e6.count(_.hcursor.downField("expected").get[String]("error").toOption.contains("errored")), 4)
    assertEquals(entries(B.wireExtract(v6 = false)).count(_.hcursor.get[String]("expected_bytes_hex").isRight), 4)
    assertEquals(entries(B.wireExtract(v6 = true)).count(_.hcursor.get[String]("error").isRight), 4)
  }

  test("staging files") {
    B.writeVectors(java.nio.file.Paths.get("target", "eval-authored"),
      java.nio.file.Paths.get("target", "wire-authored", "method-call-no-args"))
  }
}
