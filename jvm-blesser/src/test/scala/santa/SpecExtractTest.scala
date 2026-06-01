package santa

import io.circe.Json

/** Regression tests for the corpus-write path: a slug collision used to silently
  * overwrite one op's vector file with another's, dropping entries (the committed
  * v5 corpus was missing `|| boolean equivalence` / `|| sigma equivalence` — their
  * `&&` siblings clobbered them). Two guarantees: distinct ops get distinct slugs,
  * and a collision can never silently drop entries (Totality). */
class SpecExtractTest extends munit.FunSuite {

  test("slug disambiguates && from || so their vectors don't collide") {
    assert(
      SpecExtract.slug("&& boolean equivalence") != SpecExtract.slug("|| boolean equivalence"),
      s"&& and || ops must not share a filename: both slugged to '${SpecExtract.slug("&& boolean equivalence")}'")
    assert(
      SpecExtract.slug("&& sigma equivalence") != SpecExtract.slug("|| sigma equivalence"),
      "&& and || sigma ops must not share a filename")
  }

  test("writeVectors fails loud on a slug collision instead of silently overwriting") {
    val dummy = Json.obj("entries" -> Json.arr())
    // "foo-bar" and "foo_bar" both slug to "foo_bar" — a collision the && / || fix
    // does not resolve, so this exercises the write-time guard directly.
    val result = ExtractResult(
      vectors = Map("foo-bar" -> dummy, "foo_bar" -> dummy),
      captured = 0, skippedUnsupported = 0, skippedError = 0, skippedContext = 0,
      skippedUnsupportedKind = 0, rejectsCaptured = 0, unsupportedKindReasons = Nil, skippedContextReasons = Nil,
      costDiagnostics = Nil, propertyFailures = Nil)
    val tmp = java.nio.file.Files.createTempDirectory("santa-slug-collision-test")
    val ex = intercept[RuntimeException] { SpecExtract.writeVectors(result, tmp) }
    assert(ex.getMessage.toLowerCase.contains("collision"), s"unexpected error: ${ex.getMessage}")
  }
}
