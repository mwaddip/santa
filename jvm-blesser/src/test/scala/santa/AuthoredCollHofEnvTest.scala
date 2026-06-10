package santa

import java.nio.file.Files

/** Guard for the Coll-HOF per-element env ladder (ergots Ask 14). All ten arms bless
  * live; the test pins value+cost exactly — two n-points per arm pin the per-element
  * SLOPE (map 27 · filter 32 · exists 32 · forall 32 · fold 51), each including the
  * per-invocation ADD_TO_ENV(5) charged inside FuncValue's closure. */
class AuthoredCollHofEnvTest extends munit.FunSuite {

  private lazy val vectors = AuthoredCollHofEnv.extract()
  private lazy val entries: Vector[io.circe.Json] =
    vectors(AuthoredCollHofEnv.Op).hcursor.downField("entries").values.get.toVector
  private def byName(name: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"missing entry $name"))
  private def costOf(name: String): Long =
    byName(name).hcursor.downField("expected").get[Long]("cost").toOption
      .getOrElse(fail(s"$name cost"))

  test("one family, ten entries (5 arms × n ∈ {2,4})") {
    assertEquals(vectors.size, 1)
    assertEquals(entries.size, 10)
  }

  test("costs exact, and the two points per arm pin the spike slopes") {
    val expected = Map(
      "map-n2#0" -> 100L, "map-n4#0" -> 154L,       // slope 27
      "filter-n2#0" -> 110L, "filter-n4#0" -> 174L, // slope 32
      "exists-n2#0" -> 93L, "exists-n4#0" -> 157L,  // slope 32
      "forall-n2#0" -> 93L, "forall-n4#0" -> 157L,  // slope 32
      "fold-n2#0" -> 132L, "fold-n4#0" -> 234L)     // slope 51
    expected.foreach { case (name, cost) => assertEquals(costOf(name), cost, name) }
    val slopes = Map("map" -> 27L, "filter" -> 32L, "exists" -> 32L, "forall" -> 32L, "fold" -> 51L)
    slopes.foreach { case (arm, slope) =>
      assertEquals((costOf(s"$arm-n4#0") - costOf(s"$arm-n2#0")) / 2, slope, s"$arm slope")
    }
  }

  test("values: map/filter return colls; exists false / forall true; fold sums") {
    def value(name: String) = byName(name).hcursor.downField("expected").downField("value")
    assertEquals(value("exists-n4#0").get[Boolean]("value").toOption, Some(false))
    assertEquals(value("forall-n4#0").get[Boolean]("value").toOption, Some(true))
    assertEquals(value("fold-n2#0").get[Int]("value").toOption, Some(3))
    assertEquals(value("fold-n4#0").get[Int]("value").toOption, Some(10))
    assertEquals(value("map-n4#0").downField("items").values.get.size, 4)
    assertEquals(value("filter-n4#0").downField("items").values.get.size, 4) // x > 0 keeps all
  }

  test("version pinned at {activated 2, ergoTree 0}") {
    entries.foreach { e =>
      val v = e.hcursor.downField("version")
      assertEquals(v.get[Int]("activated").toOption, Some(2))
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0))
    }
  }

  test("staging: writes the family file") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredCollHofEnv.writeVectors(out)
    assert(Files.exists(out.resolve("Coll.hof_per_element_env.json")))
  }
}
