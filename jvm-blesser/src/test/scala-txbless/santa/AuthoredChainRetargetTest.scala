package santa

import org.ergoplatform.mining.difficulty.DifficultySerializer

/** Property-assert + persist the authored retargeting damping-clamp family
  * (contract §6 authored: "retargeting damping clamps (0.5× / 1.5× both hit)").
  *
  * Run under SANTA_TX_BLESSER=1; cwd = jvm-blesser/ (forked test JVM).
  *
  * THE RULE: every blessed `expected.nbits` is ORACLE-EMITTED via ChainEngine —
  * the clamps are proven by EQUALITY between oracle outputs (10× vs 100× time
  * compression emit the SAME nbits ⇒ the damping bound, never a hand-computed
  * 1.5×/0.5× value), plus classic-arm probes proving the clamps live ONLY in the
  * EIP-37 arm (the load-bearing reason the entries carry the eip37 settings pair).
  *
  * If blessAll() itself fails (engine error) it throws before any test body runs. */
class AuthoredChainRetargetTest extends munit.FunSuite {

  /** Run once per suite; lazy so a blessAll failure surfaces as a test error. */
  lazy val blessed: Seq[(String, io.circe.Json)] = AuthoredChainRetarget.blessAll()

  /** Generator-test-only oracle probes (never committed) — see AuthoredChainRetarget. */
  lazy val probes: Map[String, Long] = AuthoredChainRetarget.probes()

  private val DampingClampsPath = "chain/any/authored/Retargeting.damping_clamps.json"
  private val DonorFile = "../docs/findings/chain-captures/testnet-retarget/header-392576.json"

  private val Names = Vector(
    "retargeting-flat-control",
    "retargeting-fast-chain-clamps-up",
    "retargeting-slow-chain-clamps-down")

  // ── helpers ───────────────────────────────────────────────────────────────

  private def parseFile(p: String): io.circe.Json = {
    val src = scala.io.Source.fromFile(p)
    val raw = try src.mkString finally src.close()
    io.circe.parser.parse(raw).fold(e => fail(s"parse $p: $e"), identity)
  }

  private def envelope: io.circe.Json =
    blessed.find(_._1 == DampingClampsPath).map(_._2)
      .getOrElse(fail(s"path '$DampingClampsPath' not found in blessed output"))

  private def entries: Vector[io.circe.Json] =
    envelope.hcursor.downField("entries").focus.flatMap(_.asArray)
      .getOrElse(fail("entries missing or not an array"))

  private def entry(name: String): io.circe.ACursor =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"no entry named '$name'")).hcursor

  private def nbitsOf(name: String): Long =
    entry(name).downField("expected").get[Long]("nbits")
      .fold(e => fail(s"$name: expected.nbits: $e"), identity)

  private def anchorsOf(name: String): Vector[io.circe.Json] =
    entry(name).downField("payload").downField("anchor_headers").focus.flatMap(_.asArray)
      .getOrElse(fail(s"$name: anchor_headers missing"))

  /** Decoded difficulty behind an nbits — direction comparisons happen on the
    * difficulty basis (compact-bits integers are not order-comparable in general). */
  private def diffOf(nbits: Long): BigInt = DifficultySerializer.decodeCompactBits(nbits)

  // ── corpus shape ──────────────────────────────────────────────────────────

  test("blessAll returns exactly the one committed file with the three cases in order") {
    assertEquals(blessed.map(_._1), Seq(DampingClampsPath))
    assertEquals(entries.map(_.hcursor.get[String]("name").toOption.get), Names)
  }

  test("envelope: santa-chain/v1 schema + the house blessed_by") {
    assertEquals(envelope.hcursor.get[String]("schema").toOption, Some("santa-chain/v1"))
    assertEquals(envelope.hcursor.get[String]("blessed_by").toOption,
      Some("jvm:ergo-core-6.0.2.1-chain-model"))
  }

  test("all entries: kind retargeting, testnet base settings + the LOAD-BEARING eip37 pair") {
    Names.foreach { name =>
      val e = entry(name)
      assertEquals(e.get[String]("kind").toOption, Some("retargeting"), s"$name: kind")
      assertEquals(e.get[String]("source").toOption,
        Some(s"santa:damping_clamps:${name.stripPrefix("retargeting-")}"), s"$name: source")
      val s = e.downField("settings")
      assertEquals(s.get[Int]("epoch_length").toOption, Some(128), s"$name: epoch_length")
      assertEquals(s.get[Int]("use_last_epochs").toOption, Some(8), s"$name: use_last_epochs")
      assertEquals(s.get[Long]("block_interval_ms").toOption, Some(45000L), s"$name: block_interval_ms")
      assertEquals(s.get[Long]("initial_nbits").toOption, Some(16842752L), s"$name: initial_nbits")
      // The damping clamps live ONLY in eip37Calculate (DifficultyAdjustment.scala:85-96);
      // the classic arm has none (proven by the classic probes below) — so the family's
      // entries MUST carry the eip37 pair, with activation 1 ⇒ the arm governs at T.
      assertEquals(s.get[Int]("eip37_activation_height").toOption, Some(1), s"$name: eip37 activation")
      assertEquals(s.get[Int]("eip37_epoch_length").toOption, Some(128), s"$name: eip37 epoch length")
      assertEquals(e.downField("payload").get[Int]("target_height").toOption, Some(393601),
        s"$name: target height (the captured p1 grid, (T-1)%128==0)")
    }
  }

  // ── input construction: synthetic anchors over the captured donor ─────────

  test("input: 9 anchors ascending step 128 ending at T-1; constant nBits; ascending timestamps") {
    Names.foreach { name =>
      val hs = anchorsOf(name).map(_.hcursor.get[Int]("height").fold(e => fail(s"$name h: $e"), identity))
      assertEquals(hs, (0 to 8).map(k => 392576 + k * 128).toVector, s"$name: anchor height grid")
      assertEquals(hs.last, 393600, s"$name: last anchor must be the target's parent T-1")
      val nb = anchorsOf(name).map(_.hcursor.get[Long]("nBits").fold(e => fail(s"$name nb: $e"), identity))
      assertEquals(nb.distinct.size, 1, s"$name: anchors must share ONE constant nBits")
      val ts = anchorsOf(name).map(_.hcursor.get[Long]("timestamp").fold(e => fail(s"$name ts: $e"), identity))
      assert(ts == ts.sorted && ts.distinct.size == ts.size, s"$name: timestamps strictly ascending")
    }
  }

  test("input: synthetic anchors are byte-identical to the donor except height/timestamp/nBits") {
    val donorStripped = parseFile(DonorFile).asObject
      .map(_.remove("height").remove("timestamp").remove("nBits"))
      .map(io.circe.Json.fromJsonObject)
      .getOrElse(fail("donor is not a JSON object"))
    Names.foreach { name =>
      anchorsOf(name).zipWithIndex.foreach { case (a, i) =>
        val stripped = a.asObject
          .map(_.remove("height").remove("timestamp").remove("nBits"))
          .map(io.circe.Json.fromJsonObject)
          .getOrElse(fail(s"$name anchor[$i] not an object"))
        assertEquals(stripped, donorStripped,
          s"$name anchor[$i]: only height/timestamp/nBits may be rewritten")
      }
    }
  }

  test("input: per-case epoch spans (flat on-interval; fast 10x compressed; slow 10x stretched)") {
    def spanOf(name: String): Long = {
      val ts = anchorsOf(name).map(_.hcursor.get[Long]("timestamp").fold(e => fail(s"$name: $e"), identity))
      val spans = ts.sliding(2).map(p => p(1) - p(0)).toVector.distinct
      assertEquals(spans.size, 1, s"$name: all epoch spans must be equal")
      spans.head
    }
    val flat = 128L * 45000L // perfectly on-interval: epoch_length × block_interval_ms
    assertEquals(spanOf("retargeting-flat-control"), flat)
    assertEquals(spanOf("retargeting-fast-chain-clamps-up"), flat / 10)
    assertEquals(spanOf("retargeting-slow-chain-clamps-down"), flat * 10)
  }

  // ── ORACLE-OUTPUT PROPERTIES ──────────────────────────────────────────────

  test("PROPERTY flat: a flat chain retargets to itself (expected.nbits == the anchors' nBits)") {
    val anchorNBits = anchorsOf("retargeting-flat-control").head.hcursor
      .get[Long]("nBits").fold(e => fail(s"anchor nBits: $e"), identity)
    assertEquals(nbitsOf("retargeting-flat-control"), anchorNBits,
      "on-interval epochs at constant difficulty must emit that same difficulty")
  }

  test("PROPERTY fast: the 1.5x upper damping clamp binds — oracle(10x) == oracle(100x)") {
    assertEquals(nbitsOf("retargeting-fast-chain-clamps-up"), probes("fast-100x-eip37"),
      "10x and 100x time compression must emit the SAME nbits: the clamp, not the ratio, " +
      "decides (committed vector = the 10x form)")
  }

  test("PROPERTY slow: the 0.5x lower damping clamp binds — oracle(10x) == oracle(100x)") {
    assertEquals(nbitsOf("retargeting-slow-chain-clamps-down"), probes("slow-100x-eip37"),
      "10x and 100x time stretch must emit the SAME nbits (committed vector = the 10x form)")
  }

  test("PROPERTY direction: fast > flat > slow on the decoded-difficulty basis") {
    val flat = diffOf(nbitsOf("retargeting-flat-control"))
    val fast = diffOf(nbitsOf("retargeting-fast-chain-clamps-up"))
    val slow = diffOf(nbitsOf("retargeting-slow-chain-clamps-down"))
    assert(fast > flat, s"fast($fast) must exceed flat($flat)")
    assert(slow < flat, s"slow($slow) must undercut flat($flat)")
  }

  test("EVIDENCE: the classic arm has NO damping clamp — the eip37 pair is load-bearing") {
    // Same anchors, settings WITHOUT the eip37 pair: 10x vs 100x compression/stretch
    // must DIFFER (classic calculate is linear interpolation, DifficultyAdjustment
    // .scala:106-128) — this pins WHY the committed family rides the EIP-37 arm.
    assert(probes("fast-10x-classic") != probes("fast-100x-classic"),
      "classic arm: deeper compression must yield a different (higher) difficulty — no clamp")
    assert(probes("slow-10x-classic") != probes("slow-100x-classic"),
      "classic arm: deeper stretch must yield a different (lower) difficulty — no clamp")
    assert(diffOf(probes("fast-100x-classic")) > diffOf(probes("fast-10x-classic")),
      "classic fast: difficulty grows with compression")
    assert(diffOf(probes("slow-100x-classic")) < diffOf(probes("slow-10x-classic")),
      "classic slow: difficulty shrinks with stretch")
  }

  // ── summary + write step ──────────────────────────────────────────────────

  test("summary + write step: file lands at the committed vectors/chain/any/authored/ path") {
    val sb = new StringBuilder("\n========== AuthoredChainRetarget blessed ==========\n")
    Names.foreach { name =>
      val nb = nbitsOf(name)
      sb.append(s"  $name\n    nbits: $nb  (difficulty ${diffOf(nb)})\n")
    }
    sb.append(s"  probes: ${probes.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(", ")}\n")
    sb.append("====================================================\n")
    println(sb.toString)

    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredChainRetarget.writeVectors(blessed, vectorsRoot)
    val f = vectorsRoot.resolve(DampingClampsPath)
    assert(java.nio.file.Files.exists(f), s"vector file not written: $f")
    val parsed = parseFile(f.toString)
    assertEquals(parsed.hcursor.get[String]("schema").toOption, Some("santa-chain/v1"))
  }
}
