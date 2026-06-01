package santa

import java.nio.file.{Files, Paths}

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import santa.runner.Runner

/** Verifies that Rudolph (Runner) correctly dispatches v1 vs v2 vectors.
  *
  * For v2 (santa-eval/v2): actuals must NOT be all-errored and must match the
  * vector's `expected` (value + cost + error) for every entry.
  *
  * For v1 (santa-eval/v1): existing decode-point behaviour is preserved
  * (no regression). */
class RunnerTest extends munit.FunSuite {

  private val vectorsDir = Paths.get("../vectors/eval")

  private def readVector(name: String): Json = {
    val raw = new String(Files.readAllBytes(vectorsDir.resolve(name)))
    parseJson(raw).fold(e => sys.error(s"bad json in $name: $e"), identity)
  }

  /** Run Runner over the given vector (JSON), returning the actuals map. */
  private def runActuals(doc: Json): Map[String, Json] = {
    val schema  = doc.hcursor.get[String]("schema").toOption.getOrElse("santa-eval/v1")
    val entries = doc.hcursor.downField("entries").values.getOrElse(Vector.empty)
    entries.toVector.map(Runner.evalEntry(schema, _)).toMap
  }

  /** Assert every entry in the vector matches the runner's actuals. */
  private def assertNice(doc: Json, actuals: Map[String, Json]): Unit = {
    val op      = doc.hcursor.get[String]("op").toOption.getOrElse("?")
    val entries = doc.hcursor.downField("entries").values.getOrElse(Vector.empty)
    var lumps   = 0
    entries.toVector.foreach { e =>
      val c        = e.hcursor
      val name     = c.get[String]("name").toOption.getOrElse("?")
      val expected = c.downField("expected").focus.getOrElse(Json.Null)
      val actual   = actuals.getOrElse(name, Json.Null)
      if (expected != actual) {
        System.err.println(s"LUMP  op=$op  name=$name")
        System.err.println(s"  expected: ${expected.noSpaces}")
        System.err.println(s"  actual:   ${actual.noSpaces}")
        lumps += 1
      }
    }
    assertEquals(lumps, 0, s"$lumps lump(s) of coal in op=$op — see stderr above")
  }

  // ── v2 dispatch ───────────────────────────────────────────────────────────

  test("Runner: higher_order_lambdas (v2) — actuals match expected; no errored entries") {
    val doc     = readVector("v6/higher_order_lambdas.json")
    val actuals = runActuals(doc)

    // No entry may be all-errored (that was the bug)
    val allErrored = actuals.values.forall(j =>
      j.hcursor.downField("error").as[String].toOption.contains("errored"))
    assert(!allErrored, "all v2 entries errored — v2 dispatch is broken")

    assertNice(doc, actuals)
  }

  test("Runner: Coll.reverse (v2) — actuals match expected; no errored entries") {
    val doc     = readVector("v6/Coll.reverse.json")
    val actuals = runActuals(doc)

    val allErrored = actuals.values.forall(j =>
      j.hcursor.downField("error").as[String].toOption.contains("errored"))
    assert(!allErrored, "all v2 entries errored — v2 dispatch is broken")

    assertNice(doc, actuals)
  }

  // ── v1 regression ─────────────────────────────────────────────────────────

  test("Runner: decode-point (v1) — actuals match expected (regression guard)") {
    val doc     = readVector("v6/decode-point.json")
    val actuals = runActuals(doc)
    assertNice(doc, actuals)
  }

  // ── directory mode (orchestrator entrypoint) ───────────────────────────────

  test("Runner.runDir writes one actuals file per vector in the dir") {
    val tmpIn  = Files.createTempDirectory("santa-rin")
    val tmpOut = Files.createTempDirectory("santa-rout")
    Files.copy(vectorsDir.resolve("v6/decode-point.json"), tmpIn.resolve("dp.json"))
    Runner.runDir(tmpIn.toString, tmpOut.toString)
    val out = tmpOut.resolve("dp.json")
    assert(Files.exists(out), "runDir must write one actuals file per vector")
    val parsed = parseJson(new String(Files.readAllBytes(out), "UTF-8"))
      .fold(e => fail(s"actuals not valid JSON: $e"), identity)
    assertEquals(parsed.asObject.map(_.size), Some(6), "actuals keyed by all 6 entry names")
  }
}
