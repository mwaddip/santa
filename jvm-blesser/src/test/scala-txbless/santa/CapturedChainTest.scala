package santa

import org.ergoplatform.mining.difficulty.DifficultySerializer
import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.settings.Parameters

/** Bless + truth-lock the captured chain-tier corpus (the first committed chain
  * vectors: 2 retargeting recalculation points + 1 voting epoch).
  *
  * Run under SANTA_TX_BLESSER=1; cwd = jvm-blesser/ (forked test JVM).
  *
  * FAIL-LOUD verification, independent of the blesser's own gate: every blessed
  * `expected` is re-checked HERE against the raw captures —
  * docs/findings/chain-captures/testnet-retarget/target-p*.json's real nBits for
  * retargeting; `Parameters.parseExtension(2560, <real boundary extension>)` plus
  * the pinned empty activation "0000" for voting. The literals double-anchor the
  * capture FILES (a swapped/corrupted capture trips the literal; a blesser bug
  * trips the file-read); a mismatch is a blesser/fixture bug, never the chain.
  *
  * If blessAll() itself fails (engine error, capture gap, FAIL-LOUD trip) it
  * throws before any test body runs — the whole suite errors, no misleading green. */
class CapturedChainTest extends munit.FunSuite {

  private val CapturesDir = "../docs/findings/chain-captures"

  /** Run once per suite; lazy so a blessAll failure surfaces as a test error. */
  lazy val blessed: Seq[(String, io.circe.Json)] = CapturedChain.blessAll()

  /** The two committed paths, vectors/-relative (retargeting ⇒ `any`, voting ⇒ `v6`
    * — contract §2 kind↔version). */
  private val RetargetingPath = "chain/any/captured/Retargeting.testnet_points.json"
  private val VotingPath      = "chain/v6/captured/Voting.testnet_epoch_2560.json"

  /** Findings-pinned retargeting truths (contract §8/§9):
    * (target height T, capture file, the real header's nBits). */
  private val RetargetPins: Seq[(Int, String, Long)] = Seq(
    (393601, "target-p1.json", 84150434L),
    (393473, "target-p2.json", 84128203L))

  /** The §9-pinned boundary-2560 table (identity epoch — input == output). */
  private val PinnedTable: Map[String, Int] = Map(
    "1" -> 1250000, "2" -> 360, "3" -> 524288, "4" -> 1000000, "5" -> 100,
    "6" -> 2000, "7" -> 100, "8" -> 100, "9" -> 30, "123" -> 4)

  // ── helpers ───────────────────────────────────────────────────────────────

  private def parseFile(p: String): io.circe.Json = {
    val src = scala.io.Source.fromFile(p)
    val raw = try src.mkString finally src.close()
    io.circe.parser.parse(raw).fold(e => fail(s"parse $p: $e"), identity)
  }

  private def envelope(relPath: String): io.circe.Json =
    blessed.find(_._1 == relPath).map(_._2)
      .getOrElse(fail(s"path '$relPath' not found in blessed output"))

  private def entries(relPath: String): Vector[io.circe.Json] =
    envelope(relPath).hcursor.downField("entries").focus.flatMap(_.asArray)
      .getOrElse(fail(s"$relPath: entries missing or not an array"))

  private def entry(relPath: String, name: String): io.circe.ACursor =
    entries(relPath).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"$relPath: no entry named '$name'")).hcursor

  /** Independent truth: the boundary block's table via the real ergo-core reader. */
  private def parseExtensionTable(boundaryFile: String, height: Int): Map[String, Int] = {
    val boundary = parseFile(s"$CapturesDir/testnet-voting-2560/$boundaryFile")
    val extJson = boundary.hcursor.downField("extension").focus
      .getOrElse(fail(s"$boundaryFile: no extension field"))
    val extension = extJson.as[Extension](Extension.jsonDecoder)
      .fold(e => fail(s"$boundaryFile: Extension decode: $e"), identity)
    Parameters.parseExtension(height, extension)
      .fold(e => fail(s"$boundaryFile: parseExtension($height): $e"), identity)
      .parametersTable.map { case (k, v) => k.toInt.toString -> v }
  }

  // ── corpus shape ──────────────────────────────────────────────────────────

  test("blessAll returns exactly the two committed vector files (2 + 1 entries)") {
    assertEquals(blessed.map(_._1), Seq(RetargetingPath, VotingPath),
      "blessed output must be exactly the two committed chain vector paths")
    assertEquals(entries(RetargetingPath).size, 2, "retargeting vector: p1 + p2")
    assertEquals(entries(VotingPath).size, 1, "voting vector: the single epoch-2560 entry")
  }

  test("envelopes: santa-chain/v1 schema + the house blessed_by") {
    Seq(RetargetingPath, VotingPath).foreach { rel =>
      assertEquals(envelope(rel).hcursor.get[String]("schema").toOption,
        Some("santa-chain/v1"), s"$rel: schema field")
      assertEquals(envelope(rel).hcursor.get[String]("blessed_by").toOption,
        Some("jvm:ergo-core-6.0.2.1-chain-model"), s"$rel: blessed_by convention")
    }
  }

  // ── retargeting ───────────────────────────────────────────────────────────

  test("retargeting entries: §9 names, sources, kind, spike-verified settings") {
    RetargetPins.foreach { case (t, _, _) =>
      val e = entry(RetargetingPath, s"retargeting-testnet-$t")
      assertEquals(e.get[String]("source").toOption, Some(s"testnet:testnet-retarget@$t"))
      assertEquals(e.get[String]("kind").toOption, Some("retargeting"))
      val s = e.downField("settings")
      assertEquals(s.get[Int]("epoch_length").toOption, Some(128))
      assertEquals(s.get[Int]("use_last_epochs").toOption, Some(8))
      assertEquals(s.get[Long]("block_interval_ms").toOption, Some(45000L))
      assertEquals(s.get[Long]("initial_nbits").toOption, Some(16842752L))
      assertEquals(e.downField("payload").get[Int]("target_height").toOption, Some(t))
      val anchors = e.downField("payload").downField("anchor_headers").focus.flatMap(_.asArray)
        .getOrElse(fail(s"retargeting-testnet-$t: anchor_headers missing"))
      assertEquals(anchors.size, 9, s"retargeting-testnet-$t: 9 epoch-spaced anchors")
    }
  }

  RetargetPins.foreach { case (t, targetFile, pinnedNBits) =>
    test(s"retargeting-testnet-$t: blessed nbits == the captured target header's real nBits") {
      // Truth read from the CAPTURE FILE (not echoed engine output); the literal
      // pins the capture itself against drift.
      val target = parseFile(s"$CapturesDir/testnet-retarget/$targetFile")
      assertEquals(target.hcursor.get[Int]("height").toOption, Some(t),
        s"$targetFile is not the block we think it is")
      val realNBits = target.hcursor.get[Long]("nBits")
        .fold(e => fail(s"$targetFile: nBits: $e"), identity)
      assertEquals(realNBits, pinnedNBits, s"$targetFile drifted from the findings-pinned nBits")

      val got = entry(RetargetingPath, s"retargeting-testnet-$t")
        .downField("expected").get[Long]("nbits")
        .fold(e => fail(s"retargeting-testnet-$t: expected.nbits: $e"), identity)
      assertEquals(got, realNBits,
        s"retargeting-testnet-$t: blessed nbits contradicts chain history — blesser bug")
    }
  }

  test("retargeting diagnostics: the decoded difficulty behind the blessed nbits") {
    // p1's decimal is the §9-pinned worked-example value; both must equal what the
    // canonical serializer decodes from the blessed nbits (the engine's own basis).
    assertEquals(entry(RetargetingPath, "retargeting-testnet-393601")
      .downField("diagnostic").get[String]("difficulty").toOption, Some("17324703744"))
    RetargetPins.foreach { case (t, _, pinnedNBits) =>
      val want = DifficultySerializer.decodeCompactBits(pinnedNBits).toString
      assertEquals(entry(RetargetingPath, s"retargeting-testnet-$t")
        .downField("diagnostic").get[String]("difficulty").toOption, Some(want),
        s"retargeting-testnet-$t: diagnostic.difficulty must decode from the blessed nbits")
    }
  }

  // ── voting ────────────────────────────────────────────────────────────────

  test("voting entry: name, source, settings, capture-verbatim payload") {
    val e = entry(VotingPath, "voting-testnet-epoch-2560")
    assertEquals(e.get[String]("source").toOption, Some("testnet:testnet-voting-2560@2560"))
    assertEquals(e.get[String]("kind").toOption, Some("voting"))
    val s = e.downField("settings")
    assertEquals(s.get[Int]("voting_length").toOption, Some(128))
    assertEquals(s.get[Int]("soft_fork_epochs").toOption, Some(32))
    assertEquals(s.get[Int]("activation_epochs").toOption, Some(32))
    assertEquals(s.get[Int]("version2_activation_height").toOption, Some(417792))
    val p = e.downField("payload")
    assertEquals(p.get[Int]("boundary_height").toOption, Some(2560))
    assertEquals(p.get[String]("boundary_votes").toOption, Some("000000"))
    assertEquals(p.get[String]("proposed_update").toOption, Some("02d701990300"),
      "the boundary extension's 007c field (disable rules [215, 409])")
    // The window [T-L, T-1] = [2432, 2559], ascending contiguous, stream[0] = the
    // previous boundary (its votes seed the tally).
    val heights = p.downField("vote_stream").focus.flatMap(_.asArray)
      .getOrElse(fail("vote_stream missing"))
      .map(_.hcursor.get[Int]("height").fold(e => fail(s"vote_stream height: $e"), identity))
    assertEquals(heights, (2432 until 2560).toVector,
      "vote_stream must cover exactly [2432, 2559] ascending")
  }

  test("voting expected == FAIL-LOUD truth: parseExtension(2560) table + activated 0000") {
    // Independent truth: the real boundary-2560 extension through the real reader.
    val truth = parseExtensionTable("boundary-2560.json", 2560)
    assertEquals(truth, PinnedTable, "boundary-2560 capture drifted from the §9-pinned table")

    val exp = entry(VotingPath, "voting-testnet-epoch-2560").downField("expected")
    val gotTable = exp.downField("parameters").downField("table").focus.flatMap(_.asObject)
      .getOrElse(fail("expected.parameters.table missing"))
      .toMap.map { case (k, v) => k -> v.as[Int].fold(e => fail(s"table[$k]: $e"), identity) }
    assertEquals(gotTable, truth,
      "blessed table contradicts the boundary block's real parameters — blesser bug")
    assertEquals(exp.get[String]("activated_update").toOption, Some("0000"),
      "the empty update's canonical serializer hex — never \"\"")
  }

  // ── Task-7 fixture agreement (same captures, one assembly) ───────────────

  test("entry assembly agrees with the Task-7 chain fixtures") {
    val p1Fixture = parseFile("src/test/resources/chain-fixtures/retargeting-p1.entry.json")
    val p1 = entry(RetargetingPath, "retargeting-testnet-393601")
    assertEquals(p1.downField("settings").focus, p1Fixture.hcursor.downField("settings").focus)
    assertEquals(p1.downField("payload").focus, p1Fixture.hcursor.downField("payload").focus)

    val vFixture = parseFile("src/test/resources/chain-fixtures/voting-2560.entry.json")
    val v = entry(VotingPath, "voting-testnet-epoch-2560")
    assertEquals(v.downField("settings").focus, vFixture.hcursor.downField("settings").focus)
    assertEquals(v.downField("payload").focus, vFixture.hcursor.downField("payload").focus)
  }

  // ── write step ────────────────────────────────────────────────────────────
  // writeVectors and the file assertions live in ONE test so the files are
  // guaranteed to exist before we check for them (the CapturedBlockTest pattern);
  // the targets ARE the committed paths — re-blessing regenerates the corpus.

  test("write step: files land at the exact committed vectors/chain/ paths") {
    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    CapturedChain.writeVectors(blessed, vectorsRoot)
    Seq(RetargetingPath, VotingPath).foreach { rel =>
      val f = vectorsRoot.resolve(rel)
      assert(java.nio.file.Files.exists(f), s"vector file not written: $f")
      val parsed = parseFile(f.toString)
      assertEquals(parsed.hcursor.get[String]("schema").toOption,
        Some("santa-chain/v1"), s"$rel: written file schema")
    }
  }
}
