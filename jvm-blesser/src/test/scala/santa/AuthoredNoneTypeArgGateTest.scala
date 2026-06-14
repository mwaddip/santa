package santa

import java.nio.file.Files

/** Guard for the pre-V3 dead-branch v6-construct gate (ergots v6 audit V6-PROPERTY-TYPEARG-GATE-01).
  * The reject arm is a JVM DESERIALIZE error (rule 1011, whole-tree validation of the v2-declared
  * tree); `extract()` re-blesses it through EvalCore and fails loud if it ever stops erroring. The
  * v3 accept control blesses live; its value+cost are asserted exactly. The two tree hexes are
  * pinned against the spike wire forms — they differ ONLY in byte0's version nibble (3 → 2). */
class AuthoredNoneTypeArgGateTest extends munit.FunSuite {

  private lazy val vectors = AuthoredNoneTypeArgGate.extract()
  private def entries: Vector[io.circe.Json] =
    vectors(AuthoredNoneTypeArgGate.Op).hcursor.downField("entries").values.get.toVector
  private def byName(name: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"missing entry $name"))
  private def expectedOf(entry: io.circe.Json): io.circe.ACursor = entry.hcursor.downField("expected")

  test("one family, two entries (v3 accept control + v2 reject)") {
    assertEquals(vectors.size, 1)
    assertEquals(entries.size, 2)
    assertEquals(vectors(AuthoredNoneTypeArgGate.Op).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"))
  }

  test("v2 reject arm (error=errored, value+cost null)") {
    val exp = expectedOf(byName("none-ubi-dead-branch-v2-errored#0"))
    assertEquals(exp.get[String]("error").toOption, Some("errored"))
    assert(exp.downField("value").focus.exists(_.isNull), "value should be null")
    assert(exp.downField("cost").focus.exists(_.isNull), "cost should be null")
  }

  test("v3 accept control blesses exactly (dead branch → Boolean true @ 12)") {
    val exp = expectedOf(byName("none-ubi-dead-branch-v3-accept#0"))
    assertEquals(exp.downField("value").get[String]("kind").toOption, Some("Boolean"))
    assertEquals(exp.downField("value").get[Boolean]("value").toOption, Some(true))
    assertEquals(exp.get[Long]("cost").toOption, Some(12L))
    assert(exp.downField("error").focus.exists(_.isNull))
  }

  test("tree bytes are the exact spike wire forms (header version nibble is the only delta)") {
    assertEquals(byName("none-ubi-dead-branch-v3-accept#0").hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1b0f02010101019573007301db6a0add09"))
    assertEquals(byName("none-ubi-dead-branch-v2-errored#0").hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1a0f02010101019573007301db6a0add09"))
  }

  test("version: accept {activated 3, ergoTree 3}; reject {activated 3, ergoTree 2}") {
    val acc = byName("none-ubi-dead-branch-v3-accept#0").hcursor.downField("version")
    assertEquals(acc.get[Int]("activated").toOption, Some(3))
    assertEquals(acc.get[Int]("ergoTree").toOption, Some(3))
    val rej = byName("none-ubi-dead-branch-v2-errored#0").hcursor.downField("version")
    assertEquals(rej.get[Int]("activated").toOption, Some(3))
    assertEquals(rej.get[Int]("ergoTree").toOption, Some(2))
  }

  test("staging: writes the family file") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredNoneTypeArgGate.writeVectors(out)
    assert(Files.exists(out.resolve("Global.none_pre_v3_dead_branch.json")))
  }
}
