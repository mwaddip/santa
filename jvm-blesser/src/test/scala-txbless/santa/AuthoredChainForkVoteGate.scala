package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredChainForkVoteGate — the fork_vote_gate kind's authored families
// (enr's ask D; spike grid = ForkVoteGateSpike, mirror ≡ real on 30 rows).
//
//   vectors/chain/v6/authored/ForkVoteGate.window_edges.json (8):
//     both approval arms over the testnet grid (S=2560 ⇒ finishing=6656,
//     finishing+L=6784, afterActivation=10880). The not-approved arm prohibits
//     exactly ONE epoch [6656, 6784); the approved arm 33 [6656, 10880) — so
//     the 3686/3687 collected-only threshold FLIPS the verdict across
//     [6784, 10880) (the operand pin), and finishing−1 passes both arms (fork-
//     voting DURING the voting window is legal — the gate's key leniency).
//   vectors/chain/v6/authored/ForkVoteGate.preconditions.json (4):
//     no-round pass (122 absent, fork-voting header inside what would be a
//     window) · non-120 votes inside a prohibited window pass (the gate is
//     120-gated) · the 120-precondition PRECEDES the table read (122-without-
//     121 + non-120 votes → pass) · the eager-.get reject (same table + 120
//     votes → errored; the contrast pair to the voting kind's lazy leniency).
//
// Every expected is ORACLE-EMITTED via ChainEngine.chainEntry; the reject case
// must come back errored (anything else = recipe/engine bug, fail loud).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredChainForkVoteGate {

  private val BlessedBy = "jvm:ergo-core-6.0.2.1-chain-model"

  val WindowPath  = "chain/v6/authored/ForkVoteGate.window_edges.json"
  val PrecondPath = "chain/v6/authored/ForkVoteGate.preconditions.json"

  private val L = 128
  private val S = 2560
  private val Finishing = S + L * 32          // 6656
  private val AfterAct  = Finishing + L * 33  // 10880

  private val Settings = Json.obj(
    "voting_length"              -> Json.fromInt(L),
    "soft_fork_epochs"           -> Json.fromInt(32),
    "activation_epochs"          -> Json.fromInt(32),
    "version2_activation_height" -> Json.fromInt(417792))

  /** The real boundary-2560 table — INPUT ONLY (CapturedChainTest pins it). */
  private val BaseV4: Map[String, Int] = Map(
    "1" -> 1250000, "2" -> 360, "3" -> 524288, "4" -> 1000000, "5" -> 100,
    "6" -> 2000, "7" -> 100, "8" -> 100, "9" -> 30, "123" -> 4)

  private def round(collected: Int): Map[String, Int] =
    BaseV4 + ("121" -> collected) + ("122" -> S)

  private final case class GCase(file: String, name: String, source: String,
                                 height: Int, table: Map[String, Int],
                                 headerVotes: String, expectError: Boolean, note: String)

  private def g(file: String, name: String, slug: String, height: Int,
                table: Map[String, Int], headerVotes: String = "780000",
                expectError: Boolean = false, note: String): GCase =
    GCase(file, name, s"santa:$slug", height, table, headerVotes, expectError, note)

  private val Cases: Seq[GCase] = Seq(
    // ── window_edges: both arms × the grid ──────────────────────────────────
    g(WindowPath, "gate-during-voting-pass", "fork_vote_gate:during-voting-pass",
      height = Finishing - 1, table = round(3686),
      note = "finishing−1 (6655): fork-voting DURING the voting window is LEGAL — both " +
        "reject arms require h ≥ finishing. The gate's most important leniency: an " +
        "impl prohibiting from the round's start over-rejects every mid-round 120 vote."),
    g(WindowPath, "gate-finishing-prohibited-notapproved", "fork_vote_gate:finishing-prohibited-notapproved",
      height = Finishing, table = round(3686),
      note = "finishing (6656), collected 3686 (NOT approved — strict > 3686 fails): " +
        "prohibited — the not-approved arm [finishing, finishing+L)."),
    g(WindowPath, "gate-lastepoch-prohibited-notapproved", "fork_vote_gate:lastepoch-prohibited-notapproved",
      height = Finishing + L - 1, table = round(3686),
      note = "finishing+L−1 (6783), not approved: the not-approved arm's last " +
        "prohibited height."),
    g(WindowPath, "gate-window-end-pass-notapproved", "fork_vote_gate:window-end-pass-notapproved",
      height = Finishing + L, table = round(3686),
      note = "finishing+L (6784), not approved: the one-epoch arm has ENDED — pass. " +
        "The SAME height with collected 3687 is prohibited (the twin below): the " +
        "verdict flip across [finishing+L, afterActivation) IS the collected-only " +
        "operand threshold pin."),
    g(WindowPath, "gate-finishing-prohibited-approved", "fork_vote_gate:finishing-prohibited-approved",
      height = Finishing, table = round(3687),
      note = "finishing (6656), collected 3687 (approved): prohibited — the approved " +
        "arm [finishing, finishing + L·(ae+1)) opens at the same height."),
    g(WindowPath, "gate-flip-prohibited-approved", "fork_vote_gate:flip-prohibited-approved",
      height = Finishing + L, table = round(3687),
      note = "finishing+L (6784), approved: PROHIBITED where the 3686 twin passes — " +
        "the operand threshold (table[121] alone, strict > 128·32·9/10 = 3686) read " +
        "through the window-length flip."),
    g(WindowPath, "gate-lastwait-prohibited-approved", "fork_vote_gate:lastwait-prohibited-approved",
      height = AfterAct - 1, table = round(3687),
      note = "afterActivation−1 (10879), approved: the approved arm's last prohibited " +
        "height — 33 epochs of prohibition [finishing, finishing + L·(ae+1))."),
    g(WindowPath, "gate-after-activation-pass-approved", "fork_vote_gate:after-activation-pass-approved",
      height = AfterAct, table = round(3687),
      note = "afterActivation (10880), approved: the window has ENDED — fork-voting is " +
        "legal again (the next round's territory)."),

    // ── preconditions ───────────────────────────────────────────────────────
    g(PrecondPath, "gate-no-round-pass", "fork_vote_gate:no-round-pass",
      height = Finishing, table = BaseV4,
      note = "no round in progress (122 absent): the gate's outer guard short-circuits " +
        "— a fork-voting header passes at a height that WOULD be prohibited mid-round."),
    g(PrecondPath, "gate-non-120-pass", "fork_vote_gate:non-120-pass",
      height = Finishing, table = round(3687), headerVotes = "010000",
      note = "the gate is 120-GATED (the JVM call site's `if (forkVote)`): a header " +
        "voting only ordinary ids passes INSIDE a prohibited window."),
    g(PrecondPath, "gate-precondition-precedes-table", "fork_vote_gate:precondition-precedes-table",
      height = Finishing, table = BaseV4 + ("122" -> S), headerVotes = "010000",
      note = "the 120 precondition PRECEDES the table read: 122-without-121 (the " +
        "hostile table) + NON-120 votes → pass, the eager .get never runs. The reject " +
        "twin below proves the read order from the other side."),
    g(PrecondPath, "gate-hostile-122-without-121", "fork_vote_gate:hostile-122-without-121",
      height = Finishing, table = BaseV4 + ("122" -> S), expectError = true,
      note = "122-without-121 + 120 votes: softForkVotesCollected.get is EAGER — " +
        "NoSuchElementException at ANY height once the round-open guard passes " +
        "(ErgoStateContext.scala:161; rule-407 wrapper in-band). The deliberate " +
        "contrast pair to the voting kind's lazy leniency: the SAME orphan-122 table " +
        "passes through `leniency-122-without-121-nonforce` at a non-force boundary " +
        "and is fatal here. Contract §2: this split pins FINER than the JVM " +
        "block-acceptance observable."))

  // ── entry assembly ──────────────────────────────────────────────────────────

  private def tableJson(t: Map[String, Int]): Json =
    Json.obj(t.toSeq.sortBy(_._1.toInt).map { case (k, v) => k -> Json.fromInt(v) }: _*)

  private def baseEntry(c: GCase): Seq[(String, Json)] = Seq(
    "name"     -> Json.fromString(c.name),
    "source"   -> Json.fromString(c.source),
    "kind"     -> Json.fromString("fork_vote_gate"),
    "settings" -> Settings,
    "payload"  -> Json.obj(
      "height"             -> Json.fromInt(c.height),
      "header_votes"       -> Json.fromString(c.headerVotes),
      "current_parameters" -> Json.obj("table" -> tableJson(c.table))))

  private def bless(c: GCase): Json = {
    val base = baseEntry(c)
    val (_, actual) = santa.runner.ChainEngine.chainEntry(Json.obj(base: _*))
    val err = actual.hcursor.get[String]("error").toOption
    if (c.expectError) {
      if (!err.contains("errored"))
        sys.error(s"AuthoredChainForkVoteGate[${c.name}]: hostile case must come back errored, " +
          s"got error=${err.getOrElse("null")} — recipe or engine-seam bug")
      val oracleNote = actual.hcursor.get[String]("note").toOption.getOrElse("")
      Json.obj(base ++ Seq(
        "expected"   -> Json.obj("error" -> Json.fromString("errored")),
        "diagnostic" -> Json.obj(
          "note"        -> Json.fromString(c.note),
          "oracle_note" -> Json.fromString(oracleNote))): _*)
    } else {
      if (err.nonEmpty)
        sys.error(s"AuthoredChainForkVoteGate[${c.name}]: engine returned error=${err.get} — " +
          "clean-verdict cases must bless cleanly (valid true OR false)")
      val valid = actual.hcursor.get[Boolean]("valid")
        .fold(e => sys.error(s"AuthoredChainForkVoteGate[${c.name}]: valid: $e"), identity)
      Json.obj(base ++ Seq(
        "expected"   -> Json.obj("valid" -> Json.fromBoolean(valid)),
        "diagnostic" -> Json.obj("note" -> Json.fromString(c.note))): _*)
    }
  }

  // ── public API ──────────────────────────────────────────────────────────────

  def blessAll(): Seq[(String, Json)] = Seq(
    WindowPath  -> envelope(Cases.filter(_.file == WindowPath).map(bless)),
    PrecondPath -> envelope(Cases.filter(_.file == PrecondPath).map(bless)))

  private def envelope(entries: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-chain/v1"),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries"    -> Json.arr(entries: _*))

  def writeVectors(results: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit = {
    val collisions = results.map(_._1).groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("AuthoredChainForkVoteGate.writeVectors: path collision — " + collisions.mkString(", "))
    results.foreach { case (rel, json) =>
      val path = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
