package santa

import io.circe.Json

/** Anchors the static-type families: every selected parse row refused and every control built; the 11 trees
  * rejected; the counts. `SpecExtract` already fails loud on a verdict that moves. */
class AuthoredMethodCallStaticTypeTest extends munit.FunSuite {
  private val B = AuthoredMethodCallStaticType

  test("593 rows: 449 parse refusals, 136 parse controls, 8 evaluation rows; 11 trees") {
    assertEquals(B.Rows.size, 593)
    assertEquals(B.Rows.count(r => r.kind == "parse" && !r.control), 449)
    assertEquals(B.Rows.count(_.control), 136)
    assertEquals(B.Rows.count(_.kind == "eval"), 8)
    assertEquals(B.Trees.size, 11)
    B.Rows.filter(r => r.kind == "parse").foreach(r => assertEquals(r.accept, r.control, r.name))
  }

  test("the eval family blesses every row; the wire family rejects every tree; staging files") {
    val es = B.extract()(B.Op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"$e"), identity)
    assertEquals(es.size, 593)
    B.writeVectors(java.nio.file.Paths.get("target", "eval-authored"),
      java.nio.file.Paths.get("target", "wire-authored", "static-type"))
  }
}
