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

  // post-bless anchors — a re-bless that changes these is a cost-model/value change to
  // INVESTIGATE, not blindly accept (flat-17 = ArithOp's type-based cost, operand-size-independent)
  test("accept entries: cost == 17 for all 8 (flat JIT, operand-size-independent)") {
    val accepts = vectors(AuthoredUbiArith.OpTable).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    assertEquals(accepts.size, 8, "accept count changed")
    accepts.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse("?")
      val cost = e.hcursor.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
      assertEquals(cost, 17L, s"$name: expected flat cost 17, got $cost")
    }
  }

  test("accept entries: UnsignedBigInt value kind for all 8 accepts") {
    val accepts = vectors(AuthoredUbiArith.OpTable).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    accepts.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse("?")
      val kind = e.hcursor.downField("expected").downField("value").get[String]("kind").toOption.getOrElse("?")
      assertEquals(kind, "UnsignedBigInt", s"$name: expected UnsignedBigInt value kind, got $kind")
    }
  }

  test("boundary decimal strings anchored (plus-to-max#1 and multiply-big#5)") {
    val byName = vectors(AuthoredUbiArith.OpTable).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap
    def valueOf(name: String): String = byName(name).hcursor
      .downField("expected").downField("value").get[String]("value").toOption.get
    assertEquals(valueOf("plus-to-max#1"),
      "115792089237316195423570985008687907853269984665640564039457584007913129639935",
      "plus-to-max#1 boundary value drifted")
    assertEquals(valueOf("multiply-big#5"),
      "57896044618658097711785492504343953926634992332820282019728792003956564819968",
      "multiply-big#5 boundary value drifted")
  }
}
