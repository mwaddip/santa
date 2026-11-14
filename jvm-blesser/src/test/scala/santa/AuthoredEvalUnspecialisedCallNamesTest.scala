package santa

import io.circe.Json

/** Anchors the unspecialised-call family: sigma-rust's 457 rows (280 values, 177 refusals) and 82 derived controls,
  * every control returning; the derivation reproducible from the rows (the untaken If replaced by its else branch). */
class AuthoredEvalUnspecialisedCallNamesTest extends munit.FunSuite {
  private val B = AuthoredEvalUnspecialisedCallNames

  test("457 selected rows (280 accept, 177 refused), 82 controls (all accept)") {
    assertEquals(B.Rows.count(!_.control), 457)
    assertEquals(B.Rows.count(r => !r.control && r.accept), 280)
    assertEquals(B.Rows.count(_.control), 82)
    assert(B.Rows.filter(_.control).forall(_.accept))
  }

  test("each control is the derivation of a selected row") {
    val derived = B.Rows.filterNot(_.control).flatMap(r => B.control(r.expr)).toSet
    B.Rows.filter(_.control).foreach(c => assert(derived(c.expr), s"control ${c.name} is no row's derivation"))
  }

  test("the family blesses every row; staging") {
    val es = B.extract()(B.Op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"$e"), identity)
    assertEquals(es.size, 539 + 177)
    assertEquals(B.Twins.count(_.accept), 157, "the JVM parses 157 of the 177 it errors on")
    B.writeVectors(java.nio.file.Paths.get("target", "eval-authored"))
  }
}
