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

  test("Transaction: parses + reserializes a real Fleet signed tx idempotently under v5 (2,2)") {
    // First entry of Fleet's vendored signedTransactions.json (fleet:serializer/_test-vectors).
    val text = new String(java.nio.file.Files.readAllBytes(
      java.nio.file.Paths.get("src", "test", "resources", "fleet-wire", "signedTransactions.json")),
      java.nio.charset.StandardCharsets.UTF_8)
    val arr = io.circe.parser.parse(text).fold(e => fail(s"bad seed: $e"), identity)
      .asArray.getOrElse(fail("signedTransactions.json is not a JSON array"))
    val hex = arr.head.hcursor.get[String]("hex").fold(e => fail(s"first tx hex: $e"), identity)
    val once  = WireCanonicalize.canonicalize("Transaction", hex, 2, 2)
    val twice = WireCanonicalize.canonicalize("Transaction", once, 2, 2)
    assertEquals(once, twice, "canonicalize must be idempotent on its own output")
    assert(once.matches("^([0-9a-f]{2})+$"), s"canonical hex malformed: $once")
    println(s"[wire] Transaction fleet[0] jvm-bytes=${once.length / 2} agree=${once == hex}")
  }

  test("Constant: type-prefixed Fleet constants parse + reserialize idempotently under v5 (2,2)") {
    Seq(
      "0101",     // SBoolean true   (type 01 + data 01)
      "027f",     // SByte 127       (type 02 + data 7f)
      "03feff03"  // SShort 32767    (type 03 + zigzag)
    ).foreach { hex =>
      val once  = WireCanonicalize.canonicalize("Constant", hex, 2, 2)
      val twice = WireCanonicalize.canonicalize("Constant", once, 2, 2)
      assertEquals(once, twice, s"idempotent for $hex")
      assert(once.matches("^([0-9a-f]{2})+$"), s"canonical hex malformed: $once")
      println(s"[wire] Constant $hex jvm=$once agree=${once == hex}")
    }
  }
}
