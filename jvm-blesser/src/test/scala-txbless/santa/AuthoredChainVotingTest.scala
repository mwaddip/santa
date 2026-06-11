package santa

/** Property-assert + persist the authored voting edge families (contract §6 authored):
  * threshold edges (exactly-half / half-plus-one / softfork-below-threshold) and the
  * chain-start window clamp.
  *
  * Run under SANTA_TX_BLESSER=1; cwd = jvm-blesser/ (forked test JVM).
  *
  * THE RULE: every blessed `expected` is ORACLE-EMITTED via ChainEngine — these tests
  * assert PROPERTIES (relations between oracle outputs and the constructed inputs),
  * never absolute oracle tables; the committed vectors pin the bytes. The INPUT side
  * (vote streams, the in-force table) is asserted literally — inputs are authored.
  *
  * If blessAll() itself fails (engine error) it throws before any test body runs —
  * the whole suite errors, no misleading green. */
class AuthoredChainVotingTest extends munit.FunSuite {

  /** Run once per suite; lazy so a blessAll failure surfaces as a test error. */
  lazy val blessed: Seq[(String, io.circe.Json)] = AuthoredChainVoting.blessAll()

  private val ThresholdEdgesPath = "chain/v6/authored/Voting.threshold_edges.json"
  private val WindowClampPath    = "chain/v6/authored/Voting.window_clamp.json"

  /** The real boundary-2560 table — the INPUT every case starts from (CapturedChainTest
    * pins this exact map against Parameters.parseExtension(2560, <real extension>)). */
  private val InputTable: Map[String, Int] = Map(
    "1" -> 1250000, "2" -> 360, "3" -> 524288, "4" -> 1000000, "5" -> 100,
    "6" -> 2000, "7" -> 100, "8" -> 100, "9" -> 30, "123" -> 4)

  // ── helpers ───────────────────────────────────────────────────────────────

  private def envelope(relPath: String): io.circe.Json =
    blessed.find(_._1 == relPath).map(_._2)
      .getOrElse(fail(s"path '$relPath' not found in blessed output"))

  private def entries(relPath: String): Vector[io.circe.Json] =
    envelope(relPath).hcursor.downField("entries").focus.flatMap(_.asArray)
      .getOrElse(fail(s"$relPath: entries missing or not an array"))

  private def entry(relPath: String, name: String): io.circe.ACursor =
    entries(relPath).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"$relPath: no entry named '$name'")).hcursor

  private def tableOf(c: io.circe.ACursor, what: String): Map[String, Int] =
    c.downField("table").focus.flatMap(_.asObject)
      .getOrElse(fail(s"$what: table missing"))
      .toMap.map { case (k, v) => k -> v.as[Int].fold(e => fail(s"$what table[$k]: $e"), identity) }

  private def inputTableOf(e: io.circe.ACursor, name: String): Map[String, Int] =
    tableOf(e.downField("payload").downField("current_parameters"), s"$name input")

  private def expectedTableOf(e: io.circe.ACursor, name: String): Map[String, Int] =
    tableOf(e.downField("expected").downField("parameters"), s"$name expected")

  private def activatedOf(e: io.circe.ACursor, name: String): String =
    e.downField("expected").get[String]("activated_update")
      .fold(err => fail(s"$name: expected.activated_update: $err"), identity)

  private def streamOf(e: io.circe.ACursor, name: String): Vector[(Int, String)] =
    e.downField("payload").downField("vote_stream").focus.flatMap(_.asArray)
      .getOrElse(fail(s"$name: vote_stream missing"))
      .map { v =>
        val c = v.hcursor
        (c.get[Int]("height").fold(e => fail(s"$name stream height: $e"), identity),
         c.get[String]("votes").fold(e => fail(s"$name stream votes: $e"), identity))
      }

  // ── corpus shape ──────────────────────────────────────────────────────────

  test("blessAll returns exactly the two committed authored voting files (4 + 1 entries)") {
    assertEquals(blessed.map(_._1), Seq(ThresholdEdgesPath, WindowClampPath))
    assertEquals(entries(ThresholdEdgesPath).map(_.hcursor.get[String]("name").toOption.get),
      Vector("voting-threshold-half", "voting-threshold-half-plus-one",
             "voting-softfork-below-threshold", "voting-id9-step"))
    assertEquals(entries(WindowClampPath).map(_.hcursor.get[String]("name").toOption.get),
      Vector("voting-window-clamp-chain-start"))
  }

  test("envelopes: santa-chain/v1 schema + the house blessed_by") {
    Seq(ThresholdEdgesPath, WindowClampPath).foreach { rel =>
      assertEquals(envelope(rel).hcursor.get[String]("schema").toOption,
        Some("santa-chain/v1"), s"$rel: schema field")
      assertEquals(envelope(rel).hcursor.get[String]("blessed_by").toOption,
        Some("jvm:ergo-core-6.0.2.1-chain-model"), s"$rel: blessed_by convention")
    }
  }

  test("all entries: kind voting, testnet-shaped settings, real-2560 input table, " +
       "boundary_votes 000000, proposed_update 0000") {
    val all = Seq(
      (ThresholdEdgesPath, "voting-threshold-half"),
      (ThresholdEdgesPath, "voting-threshold-half-plus-one"),
      (ThresholdEdgesPath, "voting-softfork-below-threshold"),
      (ThresholdEdgesPath, "voting-id9-step"),
      (WindowClampPath,    "voting-window-clamp-chain-start"))
    all.foreach { case (rel, name) =>
      val e = entry(rel, name)
      assertEquals(e.get[String]("kind").toOption, Some("voting"), s"$name: kind")
      val s = e.downField("settings")
      assertEquals(s.get[Int]("voting_length").toOption, Some(128), s"$name: voting_length")
      assertEquals(s.get[Int]("soft_fork_epochs").toOption, Some(32), s"$name: soft_fork_epochs")
      assertEquals(s.get[Int]("activation_epochs").toOption, Some(32), s"$name: activation_epochs")
      assertEquals(s.get[Int]("version2_activation_height").toOption, Some(417792),
        s"$name: version2_activation_height")
      assertEquals(inputTableOf(e, name), InputTable, s"$name: input table must be the real 2560 table")
      // boundary votes affect forkVote ONLY — none of these cases fork-vote the boundary,
      // and proposed_update stays the canonical EMPTY: the cases pin voting MATH.
      assertEquals(e.downField("payload").get[String]("boundary_votes").toOption,
        Some("000000"), s"$name: boundary_votes")
      assertEquals(e.downField("payload").get[String]("proposed_update").toOption,
        Some("0000"), s"$name: proposed_update")
    }
  }

  test("provenance: santa:<family>:<case> sources") {
    assertEquals(entry(ThresholdEdgesPath, "voting-threshold-half").get[String]("source").toOption,
      Some("santa:threshold_edges:half"))
    assertEquals(entry(ThresholdEdgesPath, "voting-threshold-half-plus-one").get[String]("source").toOption,
      Some("santa:threshold_edges:half-plus-one"))
    assertEquals(entry(ThresholdEdgesPath, "voting-softfork-below-threshold").get[String]("source").toOption,
      Some("santa:threshold_edges:softfork-below-threshold"))
    assertEquals(entry(ThresholdEdgesPath, "voting-id9-step").get[String]("source").toOption,
      Some("santa:threshold_edges:id9-step"))
    assertEquals(entry(WindowClampPath, "voting-window-clamp-chain-start").get[String]("source").toOption,
      Some("santa:window_clamp:chain-start"))
  }

  // ── input construction: the seed accounting (the family's whole point) ────
  // The tally seed is stream[0]'s votes iff stream[0] IS the previous boundary
  // (height == T − L); VotingData.update never inserts unseeded ids. So the
  // threshold streams MUST have the seed header vote id 1 (count 1) with the
  // remaining votes mid-epoch — 63 ⇒ 64 total (== half) and 64 ⇒ 65 (> half).

  test("input: half = seed(2432 votes id 1) + 63 mid-epoch = 64 total over window [2432,2559]") {
    val st = streamOf(entry(ThresholdEdgesPath, "voting-threshold-half"), "half")
    assertEquals(st.map(_._1), (2432 until 2560).toVector, "window must be [2432, 2559] ascending")
    assertEquals(st.head, (2432, "010000"),
      "stream[0] IS the previous boundary and MUST vote id 1 — it seeds the tally slot")
    assertEquals(st.count(_._2 == "010000"), 64, "exactly half of 128")
    assertEquals(st.collect { case (h, v) if v != "010000" => v }.distinct, Vector("000000"))
  }

  test("input: half-plus-one = seed + 64 mid-epoch = 65 total") {
    val st = streamOf(entry(ThresholdEdgesPath, "voting-threshold-half-plus-one"), "half+1")
    assertEquals(st.map(_._1), (2432 until 2560).toVector)
    assertEquals(st.head, (2432, "010000"), "the seed vote — without it all 64 mid-epoch votes would drop")
    assertEquals(st.count(_._2 == "010000"), 65, "half plus one")
  }

  test("input: softfork-below-threshold = seed + 39 mid-epoch = 40 SoftFork(0x78) votes") {
    val st = streamOf(entry(ThresholdEdgesPath, "voting-softfork-below-threshold"), "softfork")
    assertEquals(st.map(_._1), (2432 until 2560).toVector)
    assertEquals(st.head, (2432, "780000"), "the seed votes SoftFork so id 120 exists in the tally")
    assertEquals(st.count(_._2 == "780000"), 40,
      "40 votes — far below the 90% soft-fork line (> 128*32*9/10 = 3686 over the 32-epoch window)")
  }

  test("input: id9-step = seed + 65 mid-epoch = 66 id-9 votes; first at height 2432") {
    val st = streamOf(entry(ThresholdEdgesPath, "voting-id9-step"), "id9-step")
    assertEquals(st.map(_._1), (2432 until 2560).toVector, "window must be [2432, 2559] ascending")
    assertEquals(st.head, (2432, "090000"),
      "stream[0] IS the previous boundary and MUST vote id 9 — it seeds the tally slot")
    assertEquals(st.count(_._2 == "090000"), 66, "66 votes — strict > 64 so changeApproved")
    assertEquals(st.collect { case (h, v) if v != "090000" => v }.distinct, Vector("000000"))
  }

  test("input: chain-start window clamps to [1,127]; NO seed possible; 110 id-1 votes") {
    val st = streamOf(entry(WindowClampPath, "voting-window-clamp-chain-start"), "clamp")
    assertEquals(st.map(_._1), (1 until 128).toVector, "clamped window [max(1, T−L), T−1] = [1, 127]")
    // stream[0].height = 1 != T − L = 0 ⇒ the seed is EMPTY (no previous boundary exists);
    // every mid-epoch vote lands on an unseeded id and is dropped by the node.
    assertEquals(st.head._1, 1)
    assert(st.head._1 != 0, "T − L = 0 — no previous boundary at chain start")
    assertEquals(st.count(_._2 == "010000"), 110,
      "110 id-1 votes — far over the 64-vote line, ALL dropped (unseeded)")
  }

  // ── ORACLE-OUTPUT PROPERTIES ──────────────────────────────────────────────

  test("PROPERTY half: 64 votes == exactly half — strict >, table unchanged, no activation") {
    val e = entry(ThresholdEdgesPath, "voting-threshold-half")
    assertEquals(expectedTableOf(e, "half"), inputTableOf(e, "half"),
      "64 is NOT > 64 (changeApproved is strict): the oracle table must deep-equal the input")
    assertEquals(activatedOf(e, "half"), "0000")
  }

  test("PROPERTY half-plus-one: 65 votes steps id 1 UP and changes nothing else") {
    val e = entry(ThresholdEdgesPath, "voting-threshold-half-plus-one")
    val cur = inputTableOf(e, "half+1")
    val got = expectedTableOf(e, "half+1")
    assertEquals(got.keySet, cur.keySet, "no ids may appear or vanish (forkVote=false, id 9 present)")
    val moved = got.keySet.filter(k => got(k) != cur(k))
    assertEquals(moved, Set("1"), "the table must differ EXACTLY at id 1")
    // Sign pin: id 1 = StorageFeeFactorIncrease, a positive id — updateParams' `b > 0`
    // arm ADDS the step (Parameters.scala:172-177; magnitude is the oracle's, byte-pinned
    // in the vector, not asserted here).
    assert(got("1") > cur("1"), s"id 1 is an increase vote: ${got("1")} must be > ${cur("1")}")
    assertEquals(activatedOf(e, "half+1"), "0000")
  }

  test("PROPERTY softfork-below-threshold: blockVersion unchanged, no rule-update activated") {
    val e = entry(ThresholdEdgesPath, "voting-softfork-below-threshold")
    val cur = inputTableOf(e, "softfork")
    val got = expectedTableOf(e, "softfork")
    assertEquals(got.get("123"), cur.get("123"),
      "blockVersion (id 123) must not move on a below-threshold epoch")
    assertEquals(activatedOf(e, "softfork"), "0000",
      "no activation — the rest of the table (121/122 counters or not) is whatever the " +
      "oracle emits, byte-pinned in the vector")
  }

  test("PROPERTY id9-step: 66 votes steps id 9 UP, nothing else moves, no activation") {
    val e = entry(ThresholdEdgesPath, "voting-id9-step")
    val cur = inputTableOf(e, "id9-step")
    val got = expectedTableOf(e, "id9-step")
    assertEquals(got.keySet, cur.keySet, "no ids may appear or vanish")
    val moved = got.keySet.filter(k => got(k) != cur(k))
    assertEquals(moved, Set("9"), "the table must differ EXACTLY at id 9")
    assertEquals(got("9"), cur("9") + 1,
      s"id 9 steps +1 (SubblocksPerBlockStep = 1): ${cur("9")} → ${got("9")}")
    assertEquals(activatedOf(e, "id9-step"), "0000")
  }

  test("PROPERTY chain-start clamp: table unchanged though 110 headers voted id 1") {
    val e = entry(WindowClampPath, "voting-window-clamp-chain-start")
    assertEquals(expectedTableOf(e, "clamp"), inputTableOf(e, "clamp"),
      "EMPTY seed ⇒ unseeded id-1 votes all drop ⇒ identity table (the consensus-critical drop)")
    assertEquals(activatedOf(e, "clamp"), "0000")
  }

  // ── summary + write step ──────────────────────────────────────────────────
  // writeVectors and the file assertions live in ONE test so the files exist before
  // we check them; the targets ARE the committed paths — re-blessing regenerates.

  test("summary + write step: files land at the committed vectors/chain/v6/authored/ paths") {
    val sb = new StringBuilder("\n========== AuthoredChainVoting blessed ==========\n")
    blessed.foreach { case (rel, env) =>
      env.hcursor.downField("entries").focus.flatMap(_.asArray).getOrElse(Vector.empty).foreach { e =>
        val n = e.hcursor.get[String]("name").toOption.getOrElse("?")
        val t = e.hcursor.downField("expected").downField("parameters").downField("table").focus
          .map(_.noSpaces).getOrElse("?")
        val a = e.hcursor.downField("expected").get[String]("activated_update").toOption.getOrElse("?")
        sb.append(s"  $n\n    table: $t\n    activated_update: $a\n")
      }
    }
    sb.append("==================================================\n")
    println(sb.toString)

    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredChainVoting.writeVectors(blessed, vectorsRoot)
    Seq(ThresholdEdgesPath, WindowClampPath).foreach { rel =>
      val f = vectorsRoot.resolve(rel)
      assert(java.nio.file.Files.exists(f), s"vector file not written: $f")
      val src = scala.io.Source.fromFile(f.toFile)
      val raw = try src.mkString finally src.close()
      val parsed = io.circe.parser.parse(raw).fold(e => fail(s"$rel: written file not JSON: $e"), identity)
      assertEquals(parsed.hcursor.get[String]("schema").toOption,
        Some("santa-chain/v1"), s"$rel: written file schema")
    }
  }
}
