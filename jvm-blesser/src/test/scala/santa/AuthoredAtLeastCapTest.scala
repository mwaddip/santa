package santa

import java.nio.file.Files

/** Guard for the atLeast children-cap witnesses (ergots Ask 15). The two rejects are
  * JVM eval-ERROR (spike-confirmed; the cap precedes reduce's degenerates) and re-bless
  * through EvalCore — extract() returning IS the live proof. The three accepts bless
  * live; value (TrueProp d3 / FalseProp d2) and cost are asserted exactly. Trees are
  * rebuilt from the IR each run; prefix/length assertions pin serializer stability
  * (full hex is ~2.3KB per entry — prefix + length suffice to catch drift). */
class AuthoredAtLeastCapTest extends munit.FunSuite {

  private lazy val vectors = AuthoredAtLeastCap.extract()
  private lazy val entries: Vector[io.circe.Json] =
    vectors(AuthoredAtLeastCap.Op).hcursor.downField("entries").values.get.toVector
  private def byName(name: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"missing entry $name"))
  private def expectedOf(e: io.circe.Json): io.circe.ACursor = e.hcursor.downField("expected")

  test("one family, five entries") {
    assertEquals(vectors.size, 1)
    assertEquals(entries.size, 5)
  }

  test("the ordering pin + plain cap reject (error=errored, value+cost null)") {
    Seq("cap-overrides-degenerate-bound-errored#0", "cap-256-errored#1").foreach { name =>
      val exp = expectedOf(byName(name))
      assertEquals(exp.get[String]("error").toOption, Some("errored"), s"$name error")
      assert(exp.downField("value").focus.exists(_.isNull), s"$name value should be null")
      assert(exp.downField("cost").focus.exists(_.isNull), s"$name cost should be null")
    }
  }

  test("the three accept arms bless exactly (TrueProp d3 / FalseProp d2, all @ 449)") {
    val expected = Map(
      "cap-exclusive-bound0-TrueProp#2" -> "d3",
      "bound-2-of-255-TrueProp#3"       -> "d3",
      "bound-256-of-255-FalseProp#4"    -> "d2")
    expected.foreach { case (name, rawHex) =>
      val exp = expectedOf(byName(name))
      assertEquals(exp.downField("value").get[String]("kind").toOption, Some("SigmaProp"), s"$name kind")
      assertEquals(exp.downField("value").get[String]("raw_hex").toOption, Some(rawHex), s"$name raw_hex")
      assertEquals(exp.get[Long]("cost").toOption, Some(449L), s"$name cost")
      assert(exp.downField("error").focus.exists(_.isNull), s"$name error should be null")
    }
  }

  test("wire forms: segregated v0, 256-child trees are the spike sizes (serializer stability)") {
    // (prefix, hex length) per entry — header 0x10, VLQ constants count (257 = 0x8102 /
    // 256 = 0x8002), then the segregated bound + 08d3 TrueProp constants.
    val pins = Map(
      "cap-overrides-degenerate-bound-errored#0" -> ("108102040008d3", 2330),
      "cap-256-errored#1"                        -> ("108102040408d3", 2330),
      "cap-exclusive-bound0-TrueProp#2"          -> ("108002040008d3", 2320),
      "bound-2-of-255-TrueProp#3"                -> ("108002040408d3", 2320),
      "bound-256-of-255-FalseProp#4"             -> ("10800204800408d3", 2322))
    pins.foreach { case (name, (prefix, len)) =>
      val hex = byName(name).hcursor.get[String]("tree_bytes_hex").toOption.getOrElse(fail(s"$name hex"))
      assert(hex.startsWith(prefix), s"$name prefix: ${hex.take(20)}")
      assertEquals(hex.length, len, s"$name hex length")
    }
  }

  test("version pinned at {activated 2, ergoTree 0}") {
    entries.foreach { e =>
      val v    = e.hcursor.downField("version")
      val name = e.hcursor.get[String]("name").toOption.getOrElse("<unnamed>")
      assertEquals(v.get[Int]("activated").toOption, Some(2), s"$name activated")
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0), s"$name ergoTree")
    }
  }

  test("staging: writes the family file") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredAtLeastCap.writeVectors(out)
    assert(Files.exists(out.resolve("atLeast.children_cap.json")))
  }
}
