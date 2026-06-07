package santa

import java.nio.file.Files

/** Guard + smoke tests for the sub-min box value adjudication vector.
  * Pins that a `value = 1` box (below sigma-rust's pre-#885 MIN_RAW floor of 10800)
  * hydrates and evals on the JVM. Anchors locked from the first bless run — a drift
  * means the JVM cost model or tree shape moved: INVESTIGATE, not blindly accept. */
class AuthoredBoxSubMinTest extends munit.FunSuite {

  private lazy val vectors = AuthoredBoxSubMin.extract()

  test("1 entry, blessed Right (non-null value+cost, null error)") {
    val env     = vectors(AuthoredBoxSubMin.Op)
    val entries = env.hcursor.downField("entries").values.get.toVector
    assertEquals(entries.size, 1)
    val c = entries.head.hcursor.downField("expected")
    assert(!c.downField("value").focus.get.isNull, "unblessed value")
    assert(!c.downField("cost").focus.get.isNull,  "unblessed cost")
    assertEquals(c.downField("error").focus.map(_.noSpaces), Some("null"))
  }

  test("anchor: b.value evals to Long 1 — the box hydrates below the floor") {
    val e = vectors(AuthoredBoxSubMin.Op).hcursor
      .downField("entries").values.get.head.hcursor
    val v = e.downField("expected").downField("value")
    assertEquals(v.get[String]("kind").toOption,  Some("Long"))
    assertEquals(v.get[String]("value").toOption, Some("1"))
  }

  // Tree is box-independent — identical to AuthoredBoxSignedView's value tree;
  // cost likewise (GetVar + OptionGet + ExtractAmount = 33 at v5/activated=2).
  test("anchors: tree hex + cost locked from first bless") {
    val e = vectors(AuthoredBoxSubMin.Op).hcursor
      .downField("entries").values.get.head.hcursor
    assertEquals(e.get[String]("tree_bytes_hex").toOption, Some("1a0600c1e4e30163"))
    assertEquals(e.downField("expected").get[Long]("cost").toOption, Some(33L))
  }

  test("staging: writes Box.sub_min_value.json") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredBoxSubMin.writeVectors(out)
    assert(Files.exists(out.resolve("Box.sub_min_value.json")))
  }
}
