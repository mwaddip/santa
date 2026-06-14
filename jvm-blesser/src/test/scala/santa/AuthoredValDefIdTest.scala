package santa

import java.nio.file.Files

/** Guard for the ValDef.id wire-bound witness (ergots v6 audit REL-WIRE-ID-01).
  * The reject arm is a JVM DESERIALIZE error (getUIntExact); `extract()` re-blesses it
  * through EvalCore and fails loud if it ever stops erroring. The accept-boundary arm
  * (id = Int.MaxValue) blesses live; its value+cost are asserted exactly. The two tree
  * hexes are pinned against the spike wire forms — they differ only in the two id VLQ
  * runs (`ffffffff07` ↔ `8080808008`). */
class AuthoredValDefIdTest extends munit.FunSuite {

  private lazy val vectors = AuthoredValDefId.extract()
  private def entries: Vector[io.circe.Json] =
    vectors(AuthoredValDefId.Op).hcursor.downField("entries").values.get.toVector
  private def byName(name: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"missing entry $name"))
  private def expectedOf(entry: io.circe.Json): io.circe.ACursor = entry.hcursor.downField("expected")

  test("one family, two entries (accept boundary + overflow reject)") {
    assertEquals(vectors.size, 1)
    assertEquals(entries.size, 2)
    assertEquals(vectors(AuthoredValDefId.Op).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"))
  }

  test("overflow reject arm (error=errored, value+cost null)") {
    val exp = expectedOf(byName("valdef-id-overflow-errored#0"))
    assertEquals(exp.get[String]("error").toOption, Some("errored"))
    assert(exp.downField("value").focus.exists(_.isNull), "value should be null")
    assert(exp.downField("cost").focus.exists(_.isNull), "cost should be null")
  }

  test("accept-boundary arm blesses exactly (id Int.MaxValue → Int 7 @ 13)") {
    val exp = expectedOf(byName("valdef-id-int-max-accept#0"))
    assertEquals(exp.downField("value").get[String]("kind").toOption, Some("Int"))
    assertEquals(exp.downField("value").get[Int]("value").toOption, Some(7))
    assertEquals(exp.get[Long]("cost").toOption, Some(13L))
    assert(exp.downField("error").focus.exists(_.isNull))
  }

  test("tree bytes are the exact spike wire forms (id VLQ patch is the only delta)") {
    assertEquals(byName("valdef-id-int-max-accept#0").hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1001040ed801d6ffffffff07730072ffffffff07"))
    assertEquals(byName("valdef-id-overflow-errored#0").hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1001040ed801d680808080087300728080808008"))
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
    AuthoredValDefId.writeVectors(out)
    assert(Files.exists(out.resolve("ValDef.id_int_max_bound.json")))
  }
}
