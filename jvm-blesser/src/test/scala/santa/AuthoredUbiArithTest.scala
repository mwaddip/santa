package santa

class AuthoredUbiArithTest extends munit.FunSuite {
  private lazy val vectors = AuthoredUbiArith.extract()

  test("two ops: accept table + reject arm, expected counts") {
    val accepts = vectors(AuthoredUbiArith.OpTable).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    val rejects = vectors(AuthoredUbiArith.OpReject).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    assertEquals(accepts.size, 8)
    assertEquals(rejects.size, 5)
  }

  test("accept values are UnsignedBigInt with the expected decimal results") {
    val byName = vectors(AuthoredUbiArith.OpTable).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap
    def valueOf(name: String): String = byName(name).hcursor
      .downField("expected").downField("value").get[String]("value").toOption.get
    assertEquals(valueOf("plus-small#0"), "8")
    assertEquals(valueOf("minus-small#2"), "2")
    assertEquals(valueOf("multiply-small#4"), "15")
    assertEquals(valueOf("divide-floor#6"), "3")
    assertEquals(valueOf("mod-small#7"), "2")
  }

  test("staging written") {
    val outDir = java.nio.file.Paths.get("target", "ubi-arith-vectors")
    AuthoredUbiArith.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.list(outDir).count() == 2)
  }
}
