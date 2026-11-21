package santa.runner.harness

import io.circe.Json
import io.circe.parser.parse

/** The parser's strictness (santa-check's refusals) and the structural equality, case by case. */
class GradingTest extends munit.FunSuite {
  def j(s: String): Json = parse(s).fold(e => fail(e.toString), identity)

  /** One tier, one type: a reject branch and a catch-all with a dependent, claimed cost. */
  def minimal(typeBody: String = """{"branches": [
      {"when": {"expected.error": "errored"}, "dims": [{"name": "reject", "clean": false,
        "compare": [{"a": {"path": "actual.error"}, "b": {"lit": "errored"}}]}]},
      {"dims": [{"name": "value", "compare": [{"a": {"path": "actual.value"}, "b": {"path": "expected.value"}}]},
                {"name": "cost", "requires": ["value"], "claimed": true,
                 "compare": [{"a": {"path": "actual.cost"}, "b": {"path": "expected.cost"}}]}]}]}""",
              schema: String = "santa-grading/v1", extra: String = ""): Json =
    j(s"""{"schema": "$schema", "tiers": {"eval": "eval"}, "types": {"eval": $typeBody}$extra}""")

  def refused(doc: Json, why: String): Unit = assert(Grading.parse(doc).isLeft, s"accepted: $why")

  test("the minimal file parses") { assert(Grading.parse(minimal()).isRight) }
  test("an unknown schema version is refused") { refused(minimal(schema = "santa-grading/v2"), "v2") }
  test("an unknown top-level key is refused") { refused(minimal(extra = """, "typo": 1"""), "a top-level typo") }
  test("an operand with two forms is refused") {
    refused(minimal("""{"branches": [{"dims": [{"name": "value", "compare": [{"a": {"path": "actual.value", "lit": 1}, "b": {"lit": 1}}]}]}]}"""), "two forms")
  }
  test("field without pluck is refused") {
    refused(minimal("""{"branches": [{"dims": [{"name": "value", "compare": [{"a": {"path": "actual.value", "field": "x"}, "b": {"lit": 1}}]}]}]}"""), "field without pluck")
  }
  test("a requires naming a later dimension is refused") {
    refused(minimal("""{"branches": [{"dims": [{"name": "cost", "requires": ["value"], "compare": [{"a": {"lit": 1}, "b": {"lit": 1}}]},
      {"name": "value", "compare": [{"a": {"lit": 1}, "b": {"lit": 1}}]}]}]}"""), "requires later")
  }
  test("a dimension listed twice is refused") {
    refused(minimal("""{"branches": [{"dims": [{"name": "value", "compare": [{"a": {"lit": 1}, "b": {"lit": 1}}]},
      {"name": "value", "compare": [{"a": {"lit": 1}, "b": {"lit": 1}}]}]}]}"""), "twice")
  }
  test("a path with an unknown root is refused") {
    refused(minimal("""{"branches": [{"dims": [{"name": "value", "compare": [{"a": {"path": "result.value"}, "b": {"lit": 1}}]}]}]}"""), "root")
  }
  test("empty branches and empty compare are refused") {
    refused(minimal("""{"branches": []}"""), "no branches")
    refused(minimal("""{"branches": [{"dims": [{"name": "value", "compare": []}]}]}"""), "no comparisons")
  }
  test("a tier default naming no type is refused") {
    refused(j("""{"schema": "santa-grading/v1", "tiers": {"eval": "nope"}, "types": {}}"""), "tier default")
  }

  test("structural equality: numbers numerically, bool only bool, objects key-order-free, arrays in order") {
    assert(Equal.structural(j("5"), j("5.0")))
    assert(!Equal.structural(j("true"), j("1")))
    assert(!Equal.structural(j("null"), j("0")))
    assert(Equal.structural(j("""{"a": 1, "b": [1, 2]}"""), j("""{"b": [1, 2], "a": 1}""")))
    assert(!Equal.structural(j("[1, 2]"), j("[2, 1]")))
    assert(!Equal.structural(j("""{"a": 1}"""), j("""{"a": 1, "b": null}""")))
  }

  test("the cost dimension is n/a when not claimed, and depends on value") {
    val g = Grading.parse(minimal()).toOption.get
    val entry = j("""{"expected": {"value": 1, "cost": 5, "error": null}}""")
    val v = g.grade("eval", entry, Some(j("""{"value": 1, "cost": 6, "error": null}""")), j("{}"), claimsCost = false)
    assertEquals(v.map(_.toJson), Right(j("""{"outcome": "graded", "dims": {"value": "nice"}}""")))
    val w = g.grade("eval", entry, Some(j("""{"value": 2, "cost": 5, "error": null}""")), j("{}"), claimsCost = true)
    assertEquals(w.map(_.toJson), Right(j("""{"outcome": "graded", "dims": {"value": "red"}}""")))
  }
}
