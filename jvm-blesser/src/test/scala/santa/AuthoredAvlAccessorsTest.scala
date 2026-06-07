package santa

import java.nio.file.Files

/** Guard + smoke tests for the AvlTree Tier-1 accessor edge families.
  * Pins ergots' two source-contradicted forks: updateDigest accepts any digest
  * length (A) and negative keyLength survives deserialization (C). Costs locked
  * from the spike observation — drift means the cost model or material moved:
  * INVESTIGATE, not blindly accept. */
class AuthoredAvlAccessorsTest extends munit.FunSuite {

  private lazy val vectors = AuthoredAvlAccessors.extract()

  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def byName(op: String, name: String): io.circe.ACursor =
    entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"entry '$name' not found in $op")).hcursor.downField("expected")

  test("two families: updateDigest (4 entries), keyLength-negative (1 entry)") {
    assertEquals(vectors.size, 2)
    assertEquals(entries(AuthoredAvlAccessors.OpUpdateDigest).size, 4)
    assertEquals(entries(AuthoredAvlAccessors.OpKeyLengthNeg).size, 1)
  }

  test("A: updateDigest(3-byte) → AvlTree value; .digest reads [1,2,3] back") {
    val tree = byName(AuthoredAvlAccessors.OpUpdateDigest, "updateDigest-3byte#0").downField("value")
    assertEquals(tree.get[String]("kind").toOption, Some("AvlTree"))
    // bytes_hex starts with the 3-byte digest 010203
    assert(tree.get[String]("bytes_hex").toOption.exists(_.startsWith("010203")),
      "3-byte digest must lead the serialized AvlTree")

    val readback = byName(AuthoredAvlAccessors.OpUpdateDigest, "updateDigest-3byte-readback#1").downField("value")
    assertEquals(readback.get[String]("kind").toOption, Some("Coll"))
    val items = readback.downField("items").values.get.toVector
      .map(_.hcursor.get[Int]("value").toOption.get)
    assertEquals(items, Vector(1, 2, 3))
  }

  test("A: empty and 40-byte digests both accepted (any length, no require)") {
    Seq("updateDigest-empty#2", "updateDigest-40byte#3").foreach { n =>
      val v = byName(AuthoredAvlAccessors.OpUpdateDigest, n).downField("value")
      assertEquals(v.get[String]("kind").toOption, Some("AvlTree"), s"$n must bless an AvlTree")
    }
  }

  test("C: keyLength wrapped-negative → Int -2147483647") {
    val v = byName(AuthoredAvlAccessors.OpKeyLengthNeg, "keyLength-wrapped-negative#0").downField("value")
    assertEquals(v.get[String]("kind").toOption, Some("Int"))
    assertEquals(v.get[Long]("value").toOption, Some(-2147483647L))
  }

  test("C: the patch is a single trailing-VLQ-byte swap (deterministic)") {
    // the committed tree bytes carry 0x80000001's VLQ (…808008), not the sentinel (…808007)
    val hex = entries(AuthoredAvlAccessors.OpKeyLengthNeg).head.hcursor.get[String]("tree_bytes_hex").toOption.get
    assert(hex.contains("8180808008"), "patched keyLength VLQ (0x80000001) must be present")
    assert(!hex.contains("8180808007"), "sentinel keyLength VLQ (0x70000001) must be gone")
  }

  test("cost anchors: locked from the spike observation") {
    assertEquals(byName(AuthoredAvlAccessors.OpUpdateDigest, "updateDigest-3byte#0").get[Long]("cost").toOption, Some(46L))
    assertEquals(byName(AuthoredAvlAccessors.OpUpdateDigest, "updateDigest-3byte-readback#1").get[Long]("cost").toOption, Some(65L))
    assertEquals(byName(AuthoredAvlAccessors.OpKeyLengthNeg, "keyLength-wrapped-negative#0").get[Long]("cost").toOption, Some(20L))
  }

  test("staging: writes both family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredAvlAccessors.writeVectors(out)
    assert(Files.exists(out.resolve("AvlTree.updateDigest_any_length.json")))
    assert(Files.exists(out.resolve("AvlTree.keyLength_wrapped_negative.json")))
  }
}
