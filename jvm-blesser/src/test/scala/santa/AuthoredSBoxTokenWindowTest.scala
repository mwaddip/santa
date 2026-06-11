package santa

import io.circe.Json
import munit.FunSuite
import java.nio.file.Files

/** Generator test for AuthoredSBoxTokenWindow (ergots Ask 18). Inputs are asserted
  * structurally; expecteds are PROPERTIES of the oracle output (the
  * SBoxTokenWindowSpike pins re-expressed against the blessed JSON). */
class AuthoredSBoxTokenWindowTest extends FunSuite {

  private lazy val vectors = AuthoredSBoxTokenWindow.extract()
  private def entries(op: String): Vector[Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def entry(op: String, name: String): Json =
    entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(sys.error(s"missing entry $name in $op"))

  private def expectedValue(e: Json): Json =
    e.hcursor.downField("expected").downField("value").focus.get
  private def expectedCost(e: Json): Long =
    e.hcursor.downField("expected").get[Long]("cost").toOption.get
  private def expectedError(e: Json): Option[String] =
    e.hcursor.downField("expected").get[String]("error").toOption
  private def intValue(e: Json): Int =
    expectedValue(e).hcursor.get[Int]("value").toOption.get

  import AuthoredSBoxTokenWindow.{OpConst, OpDesTo}

  test("two op files: const (1 entry, v2 envelope) + deserializeTo (5, v4 envelope)") {
    assertEquals(vectors.keySet, Set(OpConst, OpDesTo))
    assertEquals(entries(OpConst).size, 1)
    assertEquals(entries(OpDesTo).size, 5)
    assertEquals(vectors(OpDesTo).hcursor.get[String]("schema").toOption, Some("santa-eval/v4"))
  }

  test("versions: const {activated 2, ergoTree 0}; deserializeTo {activated 3, ergoTree 3}") {
    entries(OpConst).foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(2))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(0))
    }
    entries(OpDesTo).foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
    }
  }

  test("PROPERTY const-122: value 122, the spike-pinned cost 34; tree fits 4096") {
    val e = entry(OpConst, "const-122-accept-control#0")
    assertEquals(intValue(e), 122)
    assertEquals(expectedCost(e), 34L)
    val treeBytes = e.hcursor.get[String]("tree_bytes_hex").toOption.get.length / 2
    assert(treeBytes <= 4096, s"const tree must be tree-embeddable: $treeBytes")
    assert(treeBytes >= 4000, s"const tree should sit near the cap (the edge): $treeBytes")
  }

  test("PROPERTY deserializeTo accepts: 122 / 123 / fat-trailing — the JVM has NO count cap") {
    assertEquals(intValue(entry(OpDesTo, "destobox-122-accept-control#0")), 122)
    assertEquals(intValue(entry(OpDesTo, "destobox-123-accept#1")), 123)
    // candidate 4281 > 4096 yet ACCEPTS (final-field overrun escapes the window):
    assertEquals(intValue(entry(OpDesTo, "destobox-fat-trailing-accept#3")), 2)
  }

  test("PROPERTY deserializeTo rejects: 124 and fat-then-reg are eval-errored") {
    for (n <- Seq("destobox-124-errored#2", "destobox-fat-then-reg-errored#4")) {
      val e = entry(OpDesTo, n)
      assertEquals(expectedError(e), Some("errored"), n)
      assert(e.hcursor.downField("expected").downField("value").focus.exists(_.isNull), n)
    }
  }

  test("PROPERTY costs: deserializeTo is per-byte — strictly increasing with input length") {
    val c122 = expectedCost(entry(OpDesTo, "destobox-122-accept-control#0"))
    val c123 = expectedCost(entry(OpDesTo, "destobox-123-accept#1"))
    val cFat = expectedCost(entry(OpDesTo, "destobox-fat-trailing-accept#3"))
    assert(c122 < c123 && c123 < cFat, s"costs must scale with bytes: $c122 / $c123 / $cFat")
  }

  test("input shape: var-1 is the compact Coll[Byte] form; hex length == the full box bytes") {
    // 122-token minimal box full bytes = 4072, 123 → 4105 (spike s1)
    def hexLen(name: String): Int = {
      val e = entry(OpDesTo, name).hcursor
      assertEquals(e.downField("input").get[String]("kind").toOption, Some("Coll[Byte]"))
      e.downField("input").get[String]("value_hex").toOption.get.length / 2
    }
    assertEquals(hexLen("destobox-122-accept-control#0"), 4072)
    assertEquals(hexLen("destobox-123-accept#1"), 4105)
  }

  test("staging: writes both family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredSBoxTokenWindow.writeVectors(out)
    assert(Files.exists(out.resolve("Box.token_window_const.json")))
    assert(Files.exists(out.resolve("Global.deserializeTo_Box_token_window.json")))
  }
}
