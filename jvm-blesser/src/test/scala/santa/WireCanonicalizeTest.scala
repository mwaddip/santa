package santa

/** Smoke + idempotence for the wire parse->reserialize core. Canonical input is a
  * fixpoint (canonicalize(canonicalize(x)) == canonicalize(x)); the println records
  * whether the JVM agrees with sigma-rust's seed bytes (a finding if not). */
class WireCanonicalizeTest extends munit.FunSuite {
  // sbox_minimal from ergots' sbox-roundtrip fixture (value=1_000_000, empty box).
  private val SboxMinimal =
    "c0843d09020101000000000000000000000000000000000000000000000000000000000000000000000000"

  test("Box: parses, reserializes, idempotent under v5 (2,2)") {
    val once  = WireCanonicalize.canonicalize("Box", SboxMinimal, 2, 2)
    val twice = WireCanonicalize.canonicalize("Box", once, 2, 2)
    assertEquals(once, twice, "canonicalize must be idempotent on its own output")
    assert(once.matches("^([0-9a-f]{2})+$"), s"canonical hex malformed: $once")
    println(s"[wire] Box sbox_minimal jvm=$once agree=${once == SboxMinimal}")
  }

  test("SigmaBoolean: trivial + ProveDlog parse and round-trip idempotently") {
    Seq(
      "d3", // TrivialTrue
      "d2", // TrivialFalse
      "cd000000000000000000000000000000000000000000000000000000000000000000" // ProveDlog
    ).foreach { hex =>
      val once  = WireCanonicalize.canonicalize("SigmaBoolean", hex, 2, 2)
      val twice = WireCanonicalize.canonicalize("SigmaBoolean", once, 2, 2)
      assertEquals(once, twice, s"idempotent for $hex")
      println(s"[wire] SigmaBoolean $hex jvm=$once agree=${once == hex}")
    }
  }
}
