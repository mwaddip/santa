package santa

import io.circe.Json
import sigma.VersionContext

class EvalConformanceTest extends munit.FunSuite {
  private val activated = VersionContext.MaxSupportedScriptVersion

  /** Bless every context-free entry of `file` and assert it matches the
    * fixture's own expected_* fields. */
  def checkFixture(file: String): Unit =
    FixtureOracle.entries(file).filter(FixtureOracle.isContextFree).foreach { e =>
      val c    = e.hcursor
      val name = c.get[String]("name").toOption.getOrElse("?")
      val hex  = c.get[String]("tree_bytes_hex").toOption.get
      val expValue = c.downField("expected_value_json").focus.getOrElse(Json.Null)
      val expErr   = c.get[String]("expected_error_code").toOption // None when JSON null
      val (_, outcome) = EvalCore.evalEntry(hex, activated)
      outcome match {
        case Right((value, cost)) =>
          assert(expErr.isEmpty, s"$file/$name: expected error $expErr but blessed ok")
          assertEquals(value, expValue, s"$file/$name value")
          assertEquals(cost, c.get[Long]("expected_cost").toOption.get, s"$file/$name cost")
        case Left(detail) =>
          assert(expErr.isDefined, s"$file/$name: unexpected error: $detail")
      }
    }

  // Baseline: GroupElement is already supported (Phase 1). Proves the harness
  // runs and decode-point still blesses correctly. Not a RED test.
  test("decode-point") { checkFixture("decode-point.json") }
}
