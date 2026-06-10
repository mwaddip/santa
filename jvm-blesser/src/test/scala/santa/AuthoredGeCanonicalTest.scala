package santa

import java.nio.file.Files

/** Guard for the GE canonical-bytes witnesses (ergots Ask 16). The three rejects are
  * JVM-confirmed (two at tree DESERIALIZE — the dead-branch arm proving parse-time —
  * one at eval inside deserializeTo); extract() re-blesses and fails loud on drift.
  * All accept arms assert exact value+cost. Splice markers are asserted present
  * exactly once where the arm depends on them (the garbage-identity / invalid-point
  * encodings); full byte pins are impractical here (box/header trees ~0.4-1.2KB) —
  * the live oracle re-bless IS the stability gate. */
class AuthoredGeCanonicalTest extends munit.FunSuite {

  private lazy val vectors = AuthoredGeCanonical.extract()
  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def byName(op: String, name: String): io.circe.Json =
    entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"missing entry $name in $op"))
  private def expectedOf(e: io.circe.Json): io.circe.ACursor = e.hcursor.downField("expected")
  private def hexOf(e: io.circe.Json): String =
    e.hcursor.get[String]("tree_bytes_hex").toOption.getOrElse(fail("missing hex"))

  private val garbageMarker = "0700" + "aa" * 32 // GE-constant-typed garbage identity
  private val invalidMarker = "0702" + "ff" * 32 // GE-constant-typed invalid point
  private def countOf(hay: String, needle: String): Int =
    Iterator.iterate(hay.indexOf(needle))(i => hay.indexOf(needle, i + 1)).takeWhile(_ >= 0).size

  test("three families: GE 6 entries, Box 3, Header 5") {
    assertEquals(vectors.size, 3)
    assertEquals(entries(AuthoredGeCanonical.OpGe).size, 6)
    assertEquals(entries(AuthoredGeCanonical.OpBox).size, 3)
    assertEquals(entries(AuthoredGeCanonical.OpHeader).size, 5)
  }

  test("reject arms (2 parse-time GE + 1 eval-time Header)") {
    Seq(
      AuthoredGeCanonical.OpGe     -> "invalid-point-dead-branch-errored#0",
      AuthoredGeCanonical.OpGe     -> "invalid-point-eq-errored#1",
      AuthoredGeCanonical.OpHeader -> "invalid-pk-errored#3").foreach { case (op, name) =>
      val exp = expectedOf(byName(op, name))
      assertEquals(exp.get[String]("error").toOption, Some("errored"), s"$name error")
      assert(exp.downField("value").focus.exists(_.isNull), s"$name value")
      assert(exp.downField("cost").focus.exists(_.isNull), s"$name cost")
    }
  }

  test("GE accept arms: garbage identity decodes to the identity point") {
    def boolArm(name: String, expected: Boolean, cost: Long): Unit = {
      val exp = expectedOf(byName(AuthoredGeCanonical.OpGe, name))
      assertEquals(exp.downField("value").get[Boolean]("value").toOption, Some(expected), name)
      assertEquals(exp.get[Long]("cost").toOption, Some(cost), s"$name cost")
    }
    boolArm("garbage-identity-eq-true#2", expected = true, cost = 174L)
    val dead = expectedOf(byName(AuthoredGeCanonical.OpGe, "garbage-identity-dead-branch-accept#3"))
    assertEquals(dead.downField("value").get[Int]("value").toOption, Some(5))
    assertEquals(dead.get[Long]("cost").toOption, Some(12L))
    // getEncoded arms: 33 bytes; garbage → all zeros; control → the canonical generator
    def encArm(name: String, firstByte: Int, allZero: Boolean): Unit = {
      val exp = expectedOf(byName(AuthoredGeCanonical.OpGe, name))
      val items = exp.downField("value").downField("items").values.get.toVector
      assertEquals(items.size, 33, s"$name length")
      val bytes = items.map(_.hcursor.get[Int]("value").toOption.get)
      assertEquals(bytes.head, firstByte, s"$name lead byte")
      if (allZero) assert(bytes.forall(_ == 0), s"$name should be canonical 33 zeros")
      assertEquals(exp.get[Long]("cost").toOption, Some(255L), s"$name cost")
    }
    encArm("garbage-identity-getEncoded#4", firstByte = 0, allZero = true)
    encArm("generator-getEncoded-control#5", firstByte = 2, allZero = false)
  }

  test("Box family: byte-basis EQ false @ 8, value-basis register EQ true @ 304, control true @ 8") {
    def arm(name: String, expected: Boolean, cost: Long): Unit = {
      val exp = expectedOf(byName(AuthoredGeCanonical.OpBox, name))
      assertEquals(exp.downField("value").get[Boolean]("value").toOption, Some(expected), name)
      assertEquals(exp.get[Long]("cost").toOption, Some(cost), s"$name cost")
    }
    arm("box-eq-byte-basis-false#0", expected = false, cost = 8L)
    arm("register-eq-value-basis-true#1", expected = true, cost = 304L)
    arm("box-eq-control-true#2", expected = true, cost = 8L)
  }

  test("Header family: id-basis EQ false @ 802, minerPk EQ true @ 996, getEncoded zeros @ 666, control true @ 802") {
    def boolArm(name: String, expected: Boolean, cost: Long): Unit = {
      val exp = expectedOf(byName(AuthoredGeCanonical.OpHeader, name))
      assertEquals(exp.downField("value").get[Boolean]("value").toOption, Some(expected), name)
      assertEquals(exp.get[Long]("cost").toOption, Some(cost), s"$name cost")
    }
    boolArm("header-eq-id-basis-false#0", expected = false, cost = 802L)
    boolArm("minerpk-eq-value-basis-true#1", expected = true, cost = 996L)
    boolArm("header-eq-control-true#4", expected = true, cost = 802L)
    val enc = expectedOf(byName(AuthoredGeCanonical.OpHeader, "garbage-pk-getEncoded#2"))
    val items = enc.downField("value").downField("items").values.get.toVector
    assertEquals(items.size, 33)
    assert(items.forall(_.hcursor.get[Int]("value").toOption.contains(0)), "canonical 33 zeros")
    assertEquals(enc.get[Long]("cost").toOption, Some(666L))
  }

  test("splice markers present exactly once where the arm depends on them") {
    // GE family: the spliced constant is a GE constant (07-typed marker)
    Seq("invalid-point-dead-branch-errored#0" -> invalidMarker,
        "invalid-point-eq-errored#1"          -> invalidMarker,
        "garbage-identity-eq-true#2"          -> garbageMarker,
        "garbage-identity-dead-branch-accept#3" -> garbageMarker,
        "garbage-identity-getEncoded#4"       -> garbageMarker).foreach { case (name, marker) =>
      assertEquals(countOf(hexOf(byName(AuthoredGeCanonical.OpGe, name)), marker), 1, name)
    }
    // Box family: the garbage register travels inside box2's serialized bytes
    Seq("box-eq-byte-basis-false#0", "register-eq-value-basis-true#1").foreach { name =>
      assertEquals(countOf(hexOf(byName(AuthoredGeCanonical.OpBox, name)), garbageMarker), 1, name)
    }
    // Header family: the garbage/invalid pk travels as plain Coll[Byte] data (no 07 type prefix)
    Seq("header-eq-id-basis-false#0", "minerpk-eq-value-basis-true#1", "garbage-pk-getEncoded#2")
      .foreach { name =>
        assertEquals(countOf(hexOf(byName(AuthoredGeCanonical.OpHeader, name)), "00" + "aa" * 32), 1, name)
      }
    assertEquals(countOf(hexOf(byName(AuthoredGeCanonical.OpHeader, "invalid-pk-errored#3")), "02" + "ff" * 32), 1)
  }

  test("versions: GE+Box at {activated 2, ergoTree 0}; Header family at {activated 3, ergoTree 3}") {
    (entries(AuthoredGeCanonical.OpGe) ++ entries(AuthoredGeCanonical.OpBox)).foreach { e =>
      val v = e.hcursor.downField("version")
      assertEquals(v.get[Int]("activated").toOption, Some(2))
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0))
    }
    entries(AuthoredGeCanonical.OpHeader).foreach { e =>
      val v = e.hcursor.downField("version")
      assertEquals(v.get[Int]("activated").toOption, Some(3))
      assertEquals(v.get[Int]("ergoTree").toOption, Some(3))
    }
  }

  test("staging: writes all three family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredGeCanonical.writeVectors(out)
    assert(Files.exists(out.resolve("GroupElement.canonical_bytes.json")))
    assert(Files.exists(out.resolve("Box.eq_id_basis.json")))
    assert(Files.exists(out.resolve("Global.deserializeTo_Header_id_basis.json")))
  }
}
