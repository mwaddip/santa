package santa

/** Regression guard: the v5 blesser must be reproducible. LanguageSpecificationV5 samples
  * some inputs (e.g. Coll.append) via ScalaCheck, whose Seed.random() draws from the global
  * scala.util.Random — so without a pinned seed, re-extraction produced different random inputs
  * each run (a blessed corpus that changes per run is not a stable source of truth). Driving the
  * append op twice and comparing the emitted vectors is the exact guard: drop the seed in
  * V5Extractor.extract and this trips. Scoped to the one randomly-sampled op so it stays fast. */
class V5DeterminismTest extends munit.FunSuite {

  test("extract is byte-stable for the randomly-sampled Coll.append op") {
    val onlyAppend = (n: String) => n == "Coll.append equivalence"
    val a = V5Extractor.extract(onlyAppend).vectors
    val b = V5Extractor.extract(onlyAppend).vectors
    assert(a.contains("Coll.append equivalence"),
      s"probe captured no append op — the filter or op name is wrong; ran: ${a.keys.mkString(", ")}")
    assertEquals(b, a, "two extractions of the same op diverged — generation is not seeded")
  }
}
