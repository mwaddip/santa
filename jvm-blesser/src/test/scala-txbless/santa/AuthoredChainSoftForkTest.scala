package santa

import io.circe.Json
import munit.FunSuite

/** Generator test for AuthoredChainSoftFork (gated: SANTA_TX_BLESSER=1; cwd =
  * jvm-blesser/). Inputs are asserted structurally; expecteds are asserted as
  * PROPERTIES of the oracle output (the SoftForkRoundSpike pins, re-expressed
  * against the blessed JSON). The write step lands the four committed files. */
class AuthoredChainSoftForkTest extends FunSuite {

  private val blessed = AuthoredChainSoftFork.blessAll()
  private def fileEntries(path: String): Vector[Json] =
    blessed.toMap.apply(path).hcursor.downField("entries").focus.flatMap(_.asArray).get

  private def entry(path: String, name: String): Json =
    fileEntries(path).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(sys.error(s"missing entry $name in $path"))

  private def inputTable(e: Json): Map[String, Int] =
    e.hcursor.downField("payload").downField("current_parameters").downField("table")
      .focus.flatMap(_.asObject).get.toMap.map { case (k, v) => k -> v.as[Int].toOption.get }

  private def expectedTable(e: Json): Map[String, Int] =
    e.hcursor.downField("expected").downField("parameters").downField("table")
      .focus.flatMap(_.asObject).get.toMap.map { case (k, v) => k -> v.as[Int].toOption.get }

  private def activated(e: Json): String =
    e.hcursor.downField("expected").get[String]("activated_update").toOption.get

  private def votes120(e: Json): Int =
    e.hcursor.downField("payload").downField("vote_stream").focus.flatMap(_.asArray).get
      .count(_.hcursor.get[String]("votes").toOption.contains("780000"))

  import AuthoredChainSoftFork.{RoundPath, ActivationPath, ZombiePath, HostilePath}

  // ── shape ───────────────────────────────────────────────────────────────────

  test("blessAll returns the four committed files (7 + 8 + 4 + 3 entries)") {
    assertEquals(blessed.map(_._1),
      Seq(RoundPath, ActivationPath, ZombiePath, HostilePath))
    assertEquals(fileEntries(RoundPath).size, 7)
    assertEquals(fileEntries(ActivationPath).size, 8)
    assertEquals(fileEntries(ZombiePath).size, 4)
    assertEquals(fileEntries(HostilePath).size, 3)
  }

  test("envelopes: santa-chain/v1 + house blessed_by; every entry kind=voting with testnet settings") {
    blessed.foreach { case (_, env) =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-chain/v1"))
      assertEquals(env.hcursor.get[String]("blessed_by").toOption,
        Some("jvm:ergo-core-6.0.2.1-chain-model"))
    }
    blessed.flatMap(p => fileEntries(p._1)).foreach { e =>
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("voting"))
      assertEquals(e.hcursor.downField("settings").get[Int]("voting_length").toOption, Some(128))
      assertEquals(e.hcursor.downField("settings").get[Int]("soft_fork_epochs").toOption, Some(32))
      assertEquals(e.hcursor.downField("settings").get[Int]("activation_epochs").toOption, Some(32))
    }
  }

  test("provenance: santa:<family>:<case> sources everywhere") {
    blessed.flatMap(p => fileEntries(p._1)).foreach { e =>
      val src = e.hcursor.get[String]("source").toOption.get
      assert(src.startsWith("santa:softfork_") || src.startsWith("santa:hostile_tables:"), src)
    }
  }

  // ── softfork_round properties (spike pins A/B/B3/C/D/E/F) ───────────────────

  test("PROPERTY round-start: opens {121:0, 122:T}; the 50 window 120s do NOT preload (snapshot)") {
    val e = entry(RoundPath, "softfork-round-start")
    assertEquals(votes120(e), 50)
    assertEquals(expectedTable(e), inputTable(e) + ("121" -> 0) + ("122" -> 2560))
    assertEquals(activated(e), "0000")
  }

  test("PROPERTY round-accumulate: handed {121:0,122:S} + 50 closing votes = 121:50") {
    val e = entry(RoundPath, "softfork-round-accumulate")
    assertEquals(expectedTable(e), inputTable(e) + ("121" -> 50))
  }

  test("PROPERTY midround-forkvote-noop: 122 unchanged, votes still accumulate (50→60)") {
    val e = entry(RoundPath, "softfork-round-midround-forkvote-noop")
    assertEquals(e.hcursor.downField("payload").get[String]("boundary_votes").toOption, Some("780000"))
    assertEquals(expectedTable(e), inputTable(e) + ("121" -> 60))
  }

  test("PROPERTY last-accumulate: T == S+4096 still accumulates (<= guard): 3600+87=3687") {
    val e = entry(RoundPath, "softfork-round-last-accumulate")
    assertEquals(e.hcursor.downField("payload").get[Int]("boundary_height").toOption, Some(6656))
    assertEquals(expectedTable(e), inputTable(e) + ("121" -> 3687))
  }

  test("PROPERTY wait-identity: 50 closing 120s count for NOTHING") {
    val e = entry(RoundPath, "softfork-round-wait-identity")
    assertEquals(votes120(e), 50)
    assertEquals(expectedTable(e), inputTable(e))
    assertEquals(activated(e), "0000")
  }

  test("PROPERTY failed-cleanup: 121/122 removed, rest byte-identical") {
    val e = entry(RoundPath, "softfork-round-failed-cleanup")
    assertEquals(expectedTable(e), inputTable(e) - "121" - "122")
  }

  test("PROPERTY failed-restart: fresh round {121:0, 122:S+4224}; closing 10 do not leak") {
    val e = entry(RoundPath, "softfork-round-failed-restart")
    assertEquals(expectedTable(e),
      inputTable(e) - "121" - "122" + ("121" -> 0) + ("122" -> 6784))
  }

  // ── softfork_activation properties (spike pins G/G2-G4/H1-H4/I1/I2) ─────────

  test("PROPERTY basis-yes: closing 37 + collected 3650 = 3687 ACTIVATES — 123 4→5, 121/122 persist") {
    val e = entry(ActivationPath, "softfork-activation-basis-yes")
    assertEquals(votes120(e), 37)
    assertEquals(expectedTable(e), inputTable(e) + ("123" -> 5))
    assertEquals(activated(e), "0000")
  }

  test("PROPERTY edge-no: 3686 exactly does NOT activate (strict >) — full identity") {
    val e = entry(ActivationPath, "softfork-activation-edge-no")
    assertEquals(votes120(e), 36)
    assertEquals(expectedTable(e), inputTable(e))
  }

  test("PROPERTY v6-subblocks: v3→v4 inserts id 9 = 30; empty update") {
    val e = entry(ActivationPath, "softfork-activation-v6-subblocks")
    assert(!inputTable(e).contains("9"))
    assertEquals(expectedTable(e), inputTable(e) + ("123" -> 4) + ("9" -> 30))
    assertEquals(activated(e), "0000")
  }

  test("PROPERTY v6-disable-409: insertion SUPPRESSED; activated_update = 01990300") {
    val e = entry(ActivationPath, "softfork-activation-v6-disable-409")
    assertEquals(expectedTable(e), inputTable(e) + ("123" -> 4)) // NO id 9
    assertEquals(activated(e), "01990300")
  }

  test("PROPERTY v6-testnet-proposal: the real [215,409] proposal activates verbatim; 409 still suppresses") {
    val e = entry(ActivationPath, "softfork-activation-v6-testnet-proposal")
    assertEquals(e.hcursor.downField("payload").get[String]("proposed_update").toOption,
      Some("02d701990300"))
    assertEquals(expectedTable(e), inputTable(e) + ("123" -> 4)) // NO id 9
    assertEquals(activated(e), "02d701990300")
  }

  test("PROPERTY sigma-rule: 1011 passes ergo's rulesSpec check; v4→v5; hex verbatim") {
    val e = entry(ActivationPath, "softfork-activation-sigma-rule")
    assertEquals(expectedTable(e), inputTable(e) + ("123" -> 5))
    assertEquals(activated(e), "01f30700")
  }

  test("PROPERTY cleanup: 121/122 removed at S+8320, version NOT re-bumped") {
    val e = entry(ActivationPath, "softfork-activation-cleanup")
    assertEquals(expectedTable(e), inputTable(e) - "121" - "122")
    assertEquals(expectedTable(e).get("123"), Some(4))
  }

  test("PROPERTY cleanup-restart: back-to-back rounds — {121:0, 122:S+8320}, no leak of 40 or 3700") {
    val e = entry(ActivationPath, "softfork-activation-cleanup-restart")
    assertEquals(expectedTable(e),
      inputTable(e) - "121" - "122" + ("121" -> 0) + ("122" -> 10880))
  }

  // ── softfork_zombie properties (spike pins Z1-Z4) ───────────────────────────

  test("PROPERTY zombie-survive: 3680+10 = 3690 approved at S+4224 ⇒ NO cleanup, full identity") {
    val e = entry(ZombiePath, "softfork-zombie-survive")
    assertEquals(expectedTable(e), inputTable(e))
  }

  test("PROPERTY zombie-no-activation: 3680+0 fails at S+8192 ⇒ identity, no bump") {
    val e = entry(ZombiePath, "softfork-zombie-no-activation")
    assertEquals(expectedTable(e), inputTable(e))
    assertEquals(activated(e), "0000")
  }

  test("PROPERTY zombie-late-cleanup: cleans at S+8320 with blockVersion NEVER bumped") {
    val e = entry(ZombiePath, "softfork-zombie-late-cleanup")
    assertEquals(expectedTable(e), inputTable(e) - "121" - "122")
    assertEquals(expectedTable(e).get("123"), Some(4)) // never became 5
  }

  test("PROPERTY zombie-stuck: past all checkpoints NOTHING can fire — identity despite 100 votes + boundary fork-vote") {
    val e = entry(ZombiePath, "softfork-zombie-stuck")
    assertEquals(votes120(e), 100)
    assertEquals(e.hcursor.downField("payload").get[String]("boundary_votes").toOption, Some("780000"))
    assertEquals(expectedTable(e), inputTable(e))
  }

  // ── hostile_tables properties (reject arm; spike pins J1/J2/H5) ─────────────

  test("PROPERTY hostile: expected is EXACTLY {error: errored}; oracle_note carries the JVM throw") {
    fileEntries(HostilePath).foreach { e =>
      assertEquals(e.hcursor.downField("expected").focus.flatMap(_.asObject).get.toMap.keySet,
        Set("error"))
      assertEquals(e.hcursor.downField("expected").get[String]("error").toOption, Some("errored"))
      assert(e.hcursor.downField("diagnostic").get[String]("oracle_note").toOption.exists(_.nonEmpty))
    }
    assert(entry(HostilePath, "hostile-122-without-121").hcursor.downField("diagnostic")
      .get[String]("oracle_note").toOption.exists(_.contains("121")))
    assert(entry(HostilePath, "hostile-unknown-id-approved").hcursor.downField("diagnostic")
      .get[String]("oracle_note").toOption.exists(_.contains("10")))
    assert(entry(HostilePath, "hostile-mandatory-rule-update").hcursor.downField("diagnostic")
      .get[String]("oracle_note").toOption.exists(_.contains("may not be disabled")))
  }

  // ── write step ──────────────────────────────────────────────────────────────

  test("summary + write step: files land at the committed vectors/chain/v6/authored/ paths") {
    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredChainSoftFork.writeVectors(blessed, vectorsRoot)
    blessed.foreach { case (rel, json) =>
      val f = vectorsRoot.resolve(rel)
      assert(java.nio.file.Files.exists(f), s"missing $f")
      val onDisk = io.circe.parser.parse(
        new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8))
        .fold(err => sys.error(s"$f: $err"), identity)
      assertEquals(onDisk, json)
    }
  }
}
