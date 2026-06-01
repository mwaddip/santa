package santa

import io.circe.Json

/** Integration guard for the reject-arm harvest: LanguageSpecificationV5's Failure-expected
  * cases must be blessed as coarse `errored` reject vectors, not dropped. Drives one
  * reject-producing op (Long.toByte — it has both in-range accept cases and overflow-Failure
  * cases) and asserts both outcome kinds land in the same op file with the right shape. */
class RejectHarvestTest extends munit.FunSuite {

  test("Failure-expected cases are blessed as errored reject vectors") {
    val op = "Long.toByte method"
    val v = V5Extractor.extract(_ == op).vectors.getOrElse(op, fail(s"no vector emitted for '$op'"))
    val entries = v.hcursor.downField("entries").as[List[Json]].getOrElse(Nil)
    assert(entries.nonEmpty, "op emitted no entries")

    val rejects = entries.filter(e =>
      e.hcursor.downField("expected").downField("error").as[String].toOption.contains("errored"))
    val accepts = entries.filter(e =>
      e.hcursor.downField("expected").downField("error").focus.contains(Json.Null))

    assert(rejects.nonEmpty, "no errored reject entries emitted — Failure-cases still dropped")
    assert(accepts.nonEmpty, "expected accept entries alongside the rejects")

    // A reject entry is coarse: value AND cost are null, error is the string "errored".
    val exp = rejects.head.hcursor.downField("expected")
    assertEquals(exp.downField("value").focus, Some(Json.Null))
    assertEquals(exp.downField("cost").focus, Some(Json.Null))
  }
}
