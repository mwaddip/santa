package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredChainVoting — the authored voting edge families (contract §6 authored):
//
//   vectors/chain/v6/authored/Voting.threshold_edges.json
//     voting-threshold-half             64 votes for id 1 == exactly half of 128
//                                       → no change (changeApproved is STRICT >)
//     voting-threshold-half-plus-one    65 votes → id 1 steps
//     voting-softfork-below-threshold   40 SoftFork(120) votes, boundary does NOT
//                                       fork-vote → whatever the oracle does with
//                                       the 120/121/122 family is the vector
//   vectors/chain/v6/authored/Voting.window_clamp.json
//     voting-window-clamp-chain-start   first boundary (T = L = 128): window
//                                       clamps to [1, 127], EMPTY tally seed,
//                                       110 id-1 votes all drop
//
// THE SEED ACCOUNTING (the family's whole point — contract §2 voting): the tally
// seed is stream[0]'s votes iff stream[0] IS the previous boundary (height ==
// T − L; ErgoStateContext.scala:250-251), and VotingData.update NEVER inserts
// unseeded ids (VotingData.scala:9-13) — mid-epoch votes for any id the seed
// header did not vote are silently dropped. Hence:
//   - the threshold streams make the SEED header (2432) vote id 1 — that vote
//     CREATES the tally slot and counts 1, so 63 / 64 mid-epoch voters yield the
//     64 / 65 totals (a naive every-vote counter or a non-seeding tally diverges
//     on exactly these);
//   - the chain-start stream CANNOT seed (T − L = 0, height 0 does not exist:
//     window [max(1, T−L), T−1] = [1, 127]) — so its 110 id-1 votes all drop and
//     the table must come back unchanged (the consensus-critical drop).
//
// Every `expected` is ORACLE-EMITTED via ChainEngine.chainEntry — the exact
// runner path the committed vectors will hit; hand-computed expectations are
// forbidden. The generator test asserts PROPERTIES of the oracle output
// (input↔output relations); the committed vectors pin the bytes.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredChainVoting {

  /** House oracle identity (CapturedChain's). */
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-chain-model"

  /** Committed output paths, vectors/-relative (voting is era-coupled ⇒ v6). */
  val ThresholdEdgesPath = "chain/v6/authored/Voting.threshold_edges.json"
  val WindowClampPath    = "chain/v6/authored/Voting.window_clamp.json"

  private val VotingLength = 128

  /** Testnet-shaped settings — CapturedChain's spike-verified recorded INPUTS
    * (contract §5 self-containment). */
  private val Settings = Json.obj(
    "voting_length"              -> Json.fromInt(VotingLength),
    "soft_fork_epochs"           -> Json.fromInt(32),
    "activation_epochs"          -> Json.fromInt(32),
    "version2_activation_height" -> Json.fromInt(417792))

  /** The real boundary-2560 table — INPUT ONLY (CapturedChainTest pins this exact
    * map against Parameters.parseExtension(2560, <real extension>)). Expecteds are
    * never derived from it by hand; the oracle emits them. */
  private val Current2560Table = Json.obj(
    "1"   -> Json.fromInt(1250000),
    "2"   -> Json.fromInt(360),
    "3"   -> Json.fromInt(524288),
    "4"   -> Json.fromInt(1000000),
    "5"   -> Json.fromInt(100),
    "6"   -> Json.fromInt(2000),
    "7"   -> Json.fromInt(100),
    "8"   -> Json.fromInt(100),
    "9"   -> Json.fromInt(30),
    "123" -> Json.fromInt(4))

  /** One authored case: which committed file it lands in, the heights that vote
    * (everyone else votes "000000"), and the 3-byte votes hex they cast. */
  private final case class VCase(file: String, name: String, source: String,
                                 boundary: Int, voters: Set[Int], votesHex: String,
                                 note: String)

  // Vote hex: the param id byte in slot 1 of 3 — id 1 → "010000", id 120 (0x78) →
  // "780000"; the filler/abstain vote is "000000" (NoParameter ×3).
  private val Cases: Seq[VCase] = Seq(
    VCase(ThresholdEdgesPath, "voting-threshold-half", "santa:threshold_edges:half",
      boundary = 2560,
      // stream[0] = 2432 (the previous boundary) MUST vote — its vote seeds the
      // tally slot and counts 1 — so 63 mid-epoch voters complete the 64.
      voters = (2432 to 2495).toSet, votesHex = "010000",
      note = "seed 2432 (1) + 63 mid-epoch = 64 votes for id 1 — exactly half of " +
        "voting_length 128; changeApproved is strict >, so the table is unchanged"),
    VCase(ThresholdEdgesPath, "voting-threshold-half-plus-one", "santa:threshold_edges:half-plus-one",
      boundary = 2560,
      voters = (2432 to 2496).toSet, votesHex = "010000",
      note = "seed 2432 (1) + 64 mid-epoch = 65 votes for id 1 — over the strict " +
        "half line; id 1 (StorageFeeFactor, an increase id) steps, nothing else moves"),
    VCase(ThresholdEdgesPath, "voting-softfork-below-threshold", "santa:threshold_edges:softfork-below-threshold",
      boundary = 2560,
      voters = (2432 to 2471).toSet, votesHex = "780000",
      note = "seed 2432 (1) + 39 mid-epoch = 40 SoftFork(120) votes — far below the " +
        "90% line (> 128*32*9/10 = 3686 over the 32-epoch window); the boundary header itself " +
        "does NOT fork-vote (boundary_votes 000000 ⇒ forkVote=false), so no soft-fork " +
        "voting starts: blockVersion holds and nothing activates"),
    VCase(ThresholdEdgesPath, "voting-id9-step", "santa:threshold_edges:id9-step",
      boundary = 2560,
      voters = (2432 to 2497).toSet, votesHex = "090000",
      note = "seed 2432 (1) + 65 mid-epoch = 66 votes for id 9 (SubblocksPerBlockIncrease, " +
        "the v6-born param): changeApproved (strict > 64) steps it 30 → 31 " +
        "(SubblocksPerBlockStep = 1); everything else holds. id 9 IS steppable via votes " +
        "— enr guard-rail: their old 1..=8 ordinary-step guard made it unsteppable"),
    VCase(WindowClampPath, "voting-window-clamp-chain-start", "santa:window_clamp:chain-start",
      boundary = 128,
      // No seed is POSSIBLE: T − L = 0 and heights start at 1, so the clamped
      // window's first header (height 1) is not the previous boundary — the tally
      // seed is EMPTY and every one of these 110 id-1 votes is dropped unseeded.
      voters = (1 to 110).toSet, votesHex = "010000",
      note = "first boundary (T = voting_length = 128): window clamps to [1, 127], " +
        "no previous boundary ⇒ EMPTY tally seed ⇒ all 110 id-1 votes drop " +
        "(VotingData.update never inserts unseeded ids) — table unchanged"))

  // ── entry assembly ──────────────────────────────────────────────────────────

  /** The closing epoch's window [max(1, T−L), T−1], ascending — the chain-start
    * clamp mirrors ErgoStateContext.process (contract §2). */
  private def window(boundary: Int): Seq[Int] =
    math.max(1, boundary - VotingLength) until boundary

  private def baseEntry(c: VCase): Seq[(String, Json)] = {
    val stream = window(c.boundary).map { h =>
      Json.obj(
        "height" -> Json.fromInt(h),
        "votes"  -> Json.fromString(if (c.voters(h)) c.votesHex else "000000"))
    }
    Seq(
      "name"     -> Json.fromString(c.name),
      "source"   -> Json.fromString(c.source),
      "kind"     -> Json.fromString("voting"),
      "settings" -> Settings,
      "payload"  -> Json.obj(
        "boundary_height"    -> Json.fromInt(c.boundary),
        "current_parameters" -> Json.obj("table" -> Current2560Table),
        "vote_stream"        -> Json.arr(stream: _*),
        // boundary votes affect forkVote ONLY — no case here fork-votes the boundary;
        // proposed_update stays the canonical EMPTY ("0000"): these cases pin voting
        // MATH, not rule updates.
        "boundary_votes"     -> Json.fromString("000000"),
        "proposed_update"    -> Json.fromString("0000")))
  }

  /** Drive ChainEngine.chainEntry — the exact runner path; an authored entry must
    * bless cleanly: any non-null `error` (incl. the caught-panic envelope) is a
    * recipe bug, fail loud. */
  private def engineActual(entry: Json, what: String): Json = {
    val (_, actual) = santa.runner.ChainEngine.chainEntry(entry)
    val err = actual.hcursor.downField("error").focus.getOrElse(Json.Null)
    if (!err.isNull)
      sys.error(s"AuthoredChainVoting[$what]: engine returned error=${err.noSpaces} " +
        s"note=${actual.hcursor.get[String]("note").toOption.getOrElse("<none>")} — " +
        "an authored entry must bless cleanly")
    actual
  }

  /** Bless one case: assemble inputs, run the oracle, emit `expected` VERBATIM from
    * the oracle output (the table and the activated update — both graded). */
  private def bless(c: VCase): Json = {
    val base = baseEntry(c)
    val actual = engineActual(Json.obj(base: _*), c.name)
    val table = actual.hcursor.downField("parameters").downField("table").focus
      .getOrElse(sys.error(s"AuthoredChainVoting[${c.name}]: engine returned no parameters.table"))
    val activated = actual.hcursor.get[String]("activated_update")
      .fold(e => sys.error(s"AuthoredChainVoting[${c.name}]: engine activated_update: $e"), identity)
    Json.obj(base ++ Seq(
      "expected" -> Json.obj(
        "parameters"       -> Json.obj("table" -> table),
        "activated_update" -> Json.fromString(activated)),
      "diagnostic" -> Json.obj("note" -> Json.fromString(c.note))): _*)
  }

  // ── public API ──────────────────────────────────────────────────────────────

  /** Bless all cases → (vectors/-relative path, envelope) pairs: the threshold
    * file (3 entries) and the window-clamp file (1 entry). */
  def blessAll(): Seq[(String, Json)] = Seq(
    ThresholdEdgesPath -> envelope(Cases.filter(_.file == ThresholdEdgesPath).map(bless)),
    WindowClampPath    -> envelope(Cases.filter(_.file == WindowClampPath).map(bless)))

  private def envelope(entries: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-chain/v1"),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries"    -> Json.arr(entries: _*))

  /** Persist blessed vectors at their COMMITTED paths under vectorsRoot
    * (`../vectors` from the blesser's cwd) — re-blessing regenerates in place.
    * Fails loud on a path collision (would silently drop a file). */
  def writeVectors(results: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit = {
    val collisions = results.map(_._1).groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("AuthoredChainVoting.writeVectors: path collision would silently drop a file — " +
        collisions.mkString(", "))
    results.foreach { case (rel, json) =>
      val path = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
