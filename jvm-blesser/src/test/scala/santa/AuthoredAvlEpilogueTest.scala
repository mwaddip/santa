package santa

import java.nio.file.Files

/** Guard for the AvlTree epilogue pins (valueLengthOpt-negative, composite cost).
  * Values locked from the JVM oracle/spike. */
class AuthoredAvlEpilogueTest extends munit.FunSuite {

  private lazy val vectors = AuthoredAvlEpilogue.extract()
  private def only(op: String): io.circe.ACursor =
    vectors(op).hcursor.downField("entries").values.get.toVector.head.hcursor

  test("two single-entry families") {
    assertEquals(vectors.size, 2)
    assertEquals(vectors(AuthoredAvlEpilogue.OpValueLen).hcursor.downField("entries").values.get.size, 1)
    assertEquals(vectors(AuthoredAvlEpilogue.OpComposite).hcursor.downField("entries").values.get.size, 1)
  }

  test("Ask 1: valueLengthOpt wrapped-negative → Some(Int -2147483647), cost 20") {
    val exp = only(AuthoredAvlEpilogue.OpValueLen).downField("expected")
    val v = exp.downField("value")
    assertEquals(v.get[String]("kind").toOption, Some("Option"))
    assertEquals(v.downField("value").get[String]("kind").toOption, Some("Int"))
    assertEquals(v.downField("value").get[Long]("value").toOption, Some(-2147483647L))
    assertEquals(exp.get[Long]("cost").toOption, Some(20L))
    // patched valueLengthOpt VLQ present
    assert(only(AuthoredAvlEpilogue.OpValueLen).get[String]("tree_bytes_hex").toOption.exists(_.contains("8180808008")))
  }

  test("Ask 3: composite updateDigest(3b).contains → false, cost 262") {
    val exp = only(AuthoredAvlEpilogue.OpComposite).downField("expected")
    assertEquals(exp.downField("value").get[String]("kind").toOption, Some("Boolean"))
    assertEquals(exp.downField("value").get[Boolean]("value").toOption, Some(false))
    assertEquals(exp.get[Long]("cost").toOption, Some(262L))
  }

  test("both pinned at {activated 2, ergoTree 2}") {
    Seq(AuthoredAvlEpilogue.OpValueLen, AuthoredAvlEpilogue.OpComposite).foreach { op =>
      val ver = only(op).downField("version")
      assertEquals(ver.get[Int]("activated").toOption, Some(2), s"$op activated")
      assertEquals(ver.get[Int]("ergoTree").toOption, Some(2), s"$op ergoTree")
    }
  }

  test("staging: writes both family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredAvlEpilogue.writeVectors(out)
    assert(Files.exists(out.resolve("AvlTree.valueLengthOpt_wrapped_negative.json")))
    assert(Files.exists(out.resolve("AvlTree.updateDigest_then_contains.json")))
  }
}
