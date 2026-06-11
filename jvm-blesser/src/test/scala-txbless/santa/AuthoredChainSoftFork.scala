package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredChainSoftFork — the soft-fork-round authored families (contract §6):
// handed-121/122 state, spike-proven ≡ chain-accumulated (SoftForkRoundSpike,
// 25/25: `Parameters.update` is pure in the handed table, and id 122's offset
// from the boundary selects the lifecycle branch).
//
//   vectors/chain/v6/authored/Voting.softfork_round.json      (7) — the in-round
//     lifecycle: round start / accumulate / mid-round fork-vote no-op / the
//     <=S+4096 last-accumulate edge / wait identity / failed cleanup / failed
//     restart. Every restart pins the SNAPSHOT semantics (updateFork's guards
//     read the ORIGINAL table — Parameters.scala:107-108 lazy vals + the method
//     arg — so a fresh round's 121 is ALWAYS 0; closing-epoch votes never leak).
//   vectors/chain/v6/authored/Voting.softfork_activation.json (8) — the >90%
//     edge (strict > 128·32·9/10 = 3686) fused with the approval BASIS
//     (closing-epoch-120s PLUS collected — enr guard-rail), activation payloads
//     (the v3→v4 id-9 insertion, its rule-409 suppression incl. the REAL standing
//     testnet proposal 02d701990300, the sigma-rule pass-through), cleanup and
//     same-boundary restart.
//   vectors/chain/v6/authored/Voting.softfork_zombie.json     (4) — approval
//     flips between checkpoints (votes = frozen-121 + the CURRENT closing epoch):
//     survive S+4224 / fail activation / late-cleanup WITHOUT a version bump /
//     the stuck terminal state (121/122 persist forever, no round can restart).
//   vectors/chain/v6/authored/Voting.hostile_tables.json      (3, REJECT arm) —
//     inputs the JVM itself throws on (contract §2 reject form): 122-without-121,
//     approved votes for a table-absent id, mandatory-rule proposed update.
//
// Every accept `expected` is ORACLE-EMITTED via ChainEngine.chainEntry; every
// reject case must come back `errored` from the engine's consensus seam (a
// `panicked` or a clean value on a hostile case is a recipe/engine bug — fail
// loud). Hand-computed expectations are forbidden; the generator test asserts
// PROPERTIES of the oracle output.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredChainSoftFork {

  /** House oracle identity (CapturedChain's). */
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-chain-model"

  val RoundPath      = "chain/v6/authored/Voting.softfork_round.json"
  val ActivationPath = "chain/v6/authored/Voting.softfork_activation.json"
  val ZombiePath     = "chain/v6/authored/Voting.softfork_zombie.json"
  val HostilePath    = "chain/v6/authored/Voting.hostile_tables.json"

  private val L = 128
  /** Round anchor: every family's 122 points at the real testnet boundary 2560.
    * Offsets (settings 128/32/32): accumulate ≤ S+4096=6656 · failed checkpoint
    * S+4224=6784 · activation S+8192=10752 · cleanup S+8320=10880. */
  private val S = 2560

  /** Testnet-shaped settings (contract §5 self-containment). */
  private val Settings = Json.obj(
    "voting_length"              -> Json.fromInt(L),
    "soft_fork_epochs"           -> Json.fromInt(32),
    "activation_epochs"          -> Json.fromInt(32),
    "version2_activation_height" -> Json.fromInt(417792))

  /** The real boundary-2560 table — INPUT ONLY (CapturedChainTest pins this exact
    * map against Parameters.parseExtension(2560, <real extension>)); v4 era, id 9
    * present. Expecteds are never derived from it by hand. */
  private val BaseV4: Map[String, Int] = Map(
    "1" -> 1250000, "2" -> 360, "3" -> 524288, "4" -> 1000000, "5" -> 100,
    "6" -> 2000, "7" -> 100, "8" -> 100, "9" -> 30, "123" -> 4)

  /** A v5-era (blockVersion 3) shape: ids 1-8, NO id 9 (the param is v6-born),
    * 123 → 3 — the activation-inserts-subblocks donor. */
  private val BaseV3: Map[String, Int] = BaseV4 - "9" + ("123" -> 3)

  // Spike-pinned canonical serializer hexes (SoftForkRoundSpike hexOf — emitted by
  // ErgoValidationSettingsUpdateSerializer, never hand-written):
  //   [409] = 01990300 (disableable; the id-9-insertion suppressor, Parameters update:90)
  //   [1011] = 01f30700 (sigma-side: absent from ergo's rulesSpec ⇒ passes the
  //            mayBeDisabled require — the real v5.0-style script soft-fork form)
  //   [102] = 016600 (txManyInputs, MANDATORY ⇒ parse-rejects — the hostile case)
  //   [215, 409] = 02d701990300 (the REAL standing testnet 6.0 proposal — the captured
  //                2560 vector parses it every boundary)
  private val Disable409      = "01990300"
  private val Disable1011     = "01f30700"
  private val Disable102      = "016600"
  private val TestnetProposal = "02d701990300"

  /** One authored case. `votersCount` voters cast `votesHex`, starting AT the window
    * head (T−L, the previous boundary = the tally SEED, which counts 1) and running
    * contiguously; everyone else votes "000000". */
  private final case class SFCase(file: String, name: String, source: String,
                                  boundary: Int, table: Map[String, Int],
                                  votersCount: Int, votesHex: String,
                                  boundaryVotes: String, proposedUpdate: String,
                                  expectError: Boolean, note: String)

  private def sf(file: String, name: String, slug: String, boundary: Int,
                 table: Map[String, Int], votersCount: Int = 0,
                 votesHex: String = "000000", boundaryVotes: String = "000000",
                 proposedUpdate: String = "0000", expectError: Boolean = false,
                 note: String): SFCase =
    SFCase(file, name, s"santa:$slug", boundary, table, votersCount, votesHex,
      boundaryVotes, proposedUpdate, expectError, note)

  private val Cases: Seq[SFCase] = Seq(
    // ── softfork_round: the in-round lifecycle ───────────────────────────────
    sf(RoundPath, "softfork-round-start", "softfork_round:start",
      boundary = S, table = BaseV4, votersCount = 50, votesHex = "780000",
      boundaryVotes = "780000",
      note = "fork-voting boundary with NO round in progress opens one: 122=T, 121=0. " +
        "The 50 window 120-votes do NOT preload 121 — updateFork's accumulate guard " +
        "reads the ORIGINAL table (no 122 yet): snapshot semantics. A mutated-table " +
        "impl preloads 50 (or throws on the missing 121)."),
    sf(RoundPath, "softfork-round-accumulate", "softfork_round:accumulate",
      boundary = S + L, table = BaseV4 + ("121" -> 0) + ("122" -> S),
      votersCount = 50, votesHex = "780000",
      note = "handed {121:0, 122:2560} (≡ the round-start output, spike-proven " +
        "equivalence) accumulates the closing epoch: seed 2560 counts 1 + 49 " +
        "mid-epoch = 121:50. 122 unchanged."),
    sf(RoundPath, "softfork-round-midround-forkvote-noop", "softfork_round:midround-forkvote-noop",
      boundary = S + 2 * L, table = BaseV4 + ("121" -> 50) + ("122" -> S),
      votersCount = 10, votesHex = "780000", boundaryVotes = "780000",
      note = "a fork-voting BOUNDARY mid-round is a no-op for the round state (none of " +
        "branch 3's height alternatives match): votes still accumulate (121: 50→60), " +
        "122 stays. An impl that resets the round on any boundary fork-vote diverges."),
    sf(RoundPath, "softfork-round-last-accumulate", "softfork_round:last-accumulate",
      boundary = S + 4096, table = BaseV4 + ("121" -> 3600) + ("122" -> S),
      votersCount = 87, votesHex = "780000",
      note = "T = S+4096 is the LAST accumulating boundary (the guard is <=, " +
        "Parameters.scala:136): 3600 + 87 = 121:3687. An impl with a strict < stops " +
        "one epoch early."),
    sf(RoundPath, "softfork-round-wait-identity", "softfork_round:wait-identity",
      boundary = S + 4352, table = BaseV4 + ("121" -> 3687) + ("122" -> S),
      votersCount = 50, votesHex = "780000",
      note = "a wait-phase boundary (S+4096 < T, T not a checkpoint) is FULL identity: " +
        "the 50 closing-epoch 120-votes count for NOTHING (no branch fires; 121 frozen " +
        "at 3687). An impl that keeps accumulating in the wait diverges."),
    sf(RoundPath, "softfork-round-failed-cleanup", "softfork_round:failed-cleanup",
      boundary = S + 4224, table = BaseV4 + ("121" -> 100) + ("122" -> S),
      votersCount = 10, votesHex = "780000",
      note = "T = S+4224 with votes = closing 10 + collected 100 = 110 <= 3686 (not " +
        "approved): unsuccessful-voting cleanup removes 121/122; the table is otherwise " +
        "byte-identical."),
    sf(RoundPath, "softfork-round-failed-restart", "softfork_round:failed-restart",
      boundary = S + 4224, table = BaseV4 + ("121" -> 100) + ("122" -> S),
      votersCount = 10, votesHex = "780000", boundaryVotes = "780000",
      note = "failed cleanup + a fork-voting boundary = SAME-boundary restart: 122=S+4224, " +
        "121=0. Snapshot pin: the closing 10 (and the old collected 100) do NOT leak into " +
        "the fresh round — a mutated-table impl re-accumulates into the new 121."),

    // ── softfork_activation: the >90% edge, payloads, cleanup ────────────────
    sf(ActivationPath, "softfork-activation-basis-yes", "softfork_activation:basis-yes",
      boundary = S + 8192, table = BaseV4 + ("121" -> 3650) + ("122" -> S),
      votersCount = 37, votesHex = "780000",
      note = "the approval BASIS is closing-epoch-120s PLUS collected " +
        "(Parameters.scala:107-108): 3650 + 37 = 3687 > 3686 ⇒ blockVersion 4→5, " +
        "activated_update = the (empty) proposed, 121/122 PERSIST until S+8320. A " +
        "collected-only impl reds exactly this entry (enr guard-rail)."),
    sf(ActivationPath, "softfork-activation-edge-no", "softfork_activation:edge-no",
      boundary = S + 8192, table = BaseV4 + ("121" -> 3650) + ("122" -> S),
      votersCount = 36, votesHex = "780000",
      note = "3650 + 36 = 3686 == 128·32·9/10 exactly: softForkApproved is STRICT > ⇒ " +
        "nothing fires at all — full identity (no bump, no cleanup; the zombie family " +
        "picks up from here)."),
    sf(ActivationPath, "softfork-activation-v6-subblocks", "softfork_activation:v6-subblocks",
      boundary = S + 8192, table = BaseV3 + ("121" -> 3687) + ("122" -> S),
      note = "v3→v4 activation with the EMPTY proposed update: blockVersion 3→4 AND id 9 " +
        "(SubblocksPerBlockIncrease) is INSERTED at its default 30 (Parameters.update:89-94 " +
        "— the v6-activation special case; uncovered by the captured corpus, whose tables " +
        "already carry id 9). activated_update stays 0000."),
    sf(ActivationPath, "softfork-activation-v6-disable-409", "softfork_activation:v6-disable-409",
      boundary = S + 8192, table = BaseV3 + ("121" -> 3687) + ("122" -> S),
      proposedUpdate = Disable409,
      note = "v3→v4 activation whose proposed update disables rule 409: the id-9 insertion " +
        "is SUPPRESSED (the rulesToDisable.contains(409) arm) and activated_update is the " +
        "first non-trivial hex the tier pins (01990300)."),
    sf(ActivationPath, "softfork-activation-v6-testnet-proposal", "softfork_activation:v6-testnet-proposal",
      boundary = S + 8192, table = BaseV3 + ("121" -> 3687) + ("122" -> S),
      proposedUpdate = TestnetProposal,
      note = "v3→v4 activation with the REAL standing testnet 6.0 proposal 02d701990300 " +
        "(disable [215, 409] — re-emitted at every live boundary; the captured 2560 entry " +
        "parses it): a TWO-rule update activates verbatim and 409-in-the-set still " +
        "suppresses the id-9 insertion."),
    sf(ActivationPath, "softfork-activation-sigma-rule", "softfork_activation:sigma-rule",
      boundary = S + 8192, table = BaseV4 + ("121" -> 3687) + ("122" -> S),
      proposedUpdate = Disable1011,
      note = "activation carrying a SIGMA-side rule id (1011): absent from ergo-core's " +
        "rulesSpec, the mayBeDisabled require passes it through (ErgoValidationSettingsUpdate" +
        ".scala:48 forall-on-None) — the realistic v5.0-style script soft-fork. v4→v5, " +
        "activated_update = 01f30700."),
    sf(ActivationPath, "softfork-activation-cleanup", "softfork_activation:cleanup",
      boundary = S + 8320, table = BaseV4 + ("121" -> 3700) + ("122" -> S),
      note = "T = S+8320 (one epoch after activation), still approved (3700 > 3686): " +
        "successful-voting cleanup removes 121/122; blockVersion is NOT re-bumped (the " +
        "activation branch needs T == S+8192 exactly)."),
    sf(ActivationPath, "softfork-activation-cleanup-restart", "softfork_activation:cleanup-restart",
      boundary = S + 8320, table = BaseV4 + ("121" -> 3700) + ("122" -> S),
      votersCount = 40, votesHex = "780000", boundaryVotes = "780000",
      note = "cleanup + a fork-voting boundary = back-to-back rounds: 122=S+8320, 121=0. " +
        "Snapshot pin again: neither the closing 40 nor the old 3700 leaks into the fresh " +
        "round (mutated-table impls accumulate 40, 3740, or throw)."),

    // ── softfork_zombie: approval flips between checkpoints ─────────────────
    sf(ZombiePath, "softfork-zombie-survive", "softfork_zombie:survive",
      boundary = S + 4224, table = BaseV4 + ("121" -> 3680) + ("122" -> S),
      votersCount = 10, votesHex = "780000",
      note = "frozen 3680 (would fail) + closing 10 = 3690 > 3686 at the S+4224 " +
        "checkpoint ⇒ the failed-voting cleanup does NOT fire: full identity (no " +
        "accumulation either — T > S+4096). The round limps on."),
    sf(ZombiePath, "softfork-zombie-no-activation", "softfork_zombie:no-activation",
      boundary = S + 8192, table = BaseV4 + ("121" -> 3680) + ("122" -> S),
      note = "the SAME round at activation height with a quiet closing epoch: 3680 + 0 " +
        "<= 3686 ⇒ no activation, no cleanup (that branch needs S+4224), no bump — " +
        "full identity. 121/122 persist past activation height: the zombie."),
    sf(ZombiePath, "softfork-zombie-late-cleanup", "softfork_zombie:late-cleanup",
      boundary = S + 8320, table = BaseV4 + ("121" -> 3680) + ("122" -> S),
      votersCount = 10, votesHex = "780000",
      note = "at S+8320 the closing 10 flips approval BACK on (3690 > 3686) ⇒ the " +
        "successful-voting cleanup fires — 121/122 removed — but blockVersion was NEVER " +
        "bumped (activation needed approval at S+8192 exactly): the round 'cleans up as " +
        "successful' without ever activating."),
    sf(ZombiePath, "softfork-zombie-stuck", "softfork_zombie:stuck",
      boundary = S + 8448, table = BaseV4 + ("121" -> 3680) + ("122" -> S),
      votersCount = 100, votesHex = "780000", boundaryVotes = "780000",
      note = "a zombie that ALSO missed the S+8320 cleanup is stuck FOREVER: every " +
        "lifecycle branch needs an exact checkpoint offset that has passed, so even a " +
        "fork-voting boundary with 100 fresh 120-votes is full identity — no new round " +
        "can ever start on this chain (JVM liveness quirk, pinned as-is: an impl that " +
        "'fixes' this silently forks)."),

    // ── hostile_tables: the REJECT arm (contract §2 reject form) ─────────────
    sf(HostilePath, "hostile-122-without-121", "hostile_tables:no-121",
      boundary = S + L, table = BaseV4 + ("122" -> S),
      votersCount = 5, votesHex = "780000", expectError = true,
      note = "122 present without 121: any branch forcing `votes` reads " +
        "parametersTable(121) by direct apply (Parameters.scala:108) ⇒ " +
        "NoSuchElementException. The JVM throws ⇒ so do we (throw parity)."),
    sf(HostilePath, "hostile-unknown-id-approved", "hostile_tables:unknown-id-approved",
      boundary = S, table = BaseV4,
      votersCount = 66, votesHex = "0a0000", expectError = true,
      note = "66 votes (seed + 65) for id 10 — absent from the table: changeApproved " +
        "(66 > 64) makes updateParams read parametersTable(10) by direct apply " +
        "(Parameters.scala:167) ⇒ NoSuchElementException. enr's unknown-id guard-rail: " +
        "their old impl silently ignored it."),
    sf(HostilePath, "hostile-mandatory-rule-update", "hostile_tables:mandatory-rule-update",
      boundary = S, table = BaseV4,
      proposedUpdate = Disable102, expectError = true,
      note = "the boundary's proposed update disables rule 102 (txManyInputs, " +
        "mayBeDisabled=false): ErgoValidationSettingsUpdate's require rejects it at " +
        "extension parse — before any lifecycle logic. A node accepting this block " +
        "forks off the JVM."))

  // ── entry assembly (AuthoredChainVoting's shape) ────────────────────────────

  /** The closing epoch's window [max(1, T−L), T−1], ascending. */
  private def window(boundary: Int): Seq[Int] =
    math.max(1, boundary - L) until boundary

  private def voterSet(c: SFCase): Set[Int] =
    if (c.votersCount == 0) Set.empty
    else ((c.boundary - L) to (c.boundary - L + c.votersCount - 1)).toSet

  private def tableJson(t: Map[String, Int]): Json =
    Json.obj(t.toSeq.sortBy(_._1.toInt).map { case (k, v) => k -> Json.fromInt(v) }: _*)

  private def baseEntry(c: SFCase): Seq[(String, Json)] = {
    val voters = voterSet(c)
    val stream = window(c.boundary).map { h =>
      Json.obj(
        "height" -> Json.fromInt(h),
        "votes"  -> Json.fromString(if (voters(h)) c.votesHex else "000000"))
    }
    Seq(
      "name"     -> Json.fromString(c.name),
      "source"   -> Json.fromString(c.source),
      "kind"     -> Json.fromString("voting"),
      "settings" -> Settings,
      "payload"  -> Json.obj(
        "boundary_height"    -> Json.fromInt(c.boundary),
        "current_parameters" -> Json.obj("table" -> tableJson(c.table)),
        "vote_stream"        -> Json.arr(stream: _*),
        "boundary_votes"     -> Json.fromString(c.boundaryVotes),
        "proposed_update"    -> Json.fromString(c.proposedUpdate)))
  }

  /** Bless one case. Accept cases: engine must return error=null; expected = the
    * oracle's (table, activated_update) VERBATIM. Reject cases: the engine's
    * consensus seam must return error="errored" (a clean value OR a panic on a
    * hostile case is a recipe/engine bug — fail loud); expected = {error:"errored"},
    * the oracle's throw text goes to diagnostic.oracle_note (never graded). */
  private def bless(c: SFCase): Json = {
    val base = baseEntry(c)
    val (_, actual) = santa.runner.ChainEngine.chainEntry(Json.obj(base: _*))
    val err = actual.hcursor.get[String]("error").toOption
    if (c.expectError) {
      if (!err.contains("errored"))
        sys.error(s"AuthoredChainSoftFork[${c.name}]: hostile case must come back errored, " +
          s"got error=${err.getOrElse("null")} — recipe or engine-seam bug")
      val oracleNote = actual.hcursor.get[String]("note").toOption.getOrElse("")
      Json.obj(base ++ Seq(
        "expected"   -> Json.obj("error" -> Json.fromString("errored")),
        "diagnostic" -> Json.obj(
          "note"        -> Json.fromString(c.note),
          "oracle_note" -> Json.fromString(oracleNote))): _*)
    } else {
      if (err.nonEmpty)
        sys.error(s"AuthoredChainSoftFork[${c.name}]: engine returned error=${err.get} " +
          s"note=${actual.hcursor.get[String]("note").toOption.getOrElse("<none>")} — " +
          "an accept case must bless cleanly")
      val table = actual.hcursor.downField("parameters").downField("table").focus
        .getOrElse(sys.error(s"AuthoredChainSoftFork[${c.name}]: no parameters.table"))
      val activated = actual.hcursor.get[String]("activated_update")
        .fold(e => sys.error(s"AuthoredChainSoftFork[${c.name}]: activated_update: $e"), identity)
      Json.obj(base ++ Seq(
        "expected" -> Json.obj(
          "parameters"       -> Json.obj("table" -> table),
          "activated_update" -> Json.fromString(activated)),
        "diagnostic" -> Json.obj("note" -> Json.fromString(c.note))): _*)
    }
  }

  // ── public API ──────────────────────────────────────────────────────────────

  /** Bless all cases → (vectors/-relative path, envelope) pairs: round 7,
    * activation 8, zombie 4, hostile 3. */
  def blessAll(): Seq[(String, Json)] = Seq(
    RoundPath      -> envelope(Cases.filter(_.file == RoundPath).map(bless)),
    ActivationPath -> envelope(Cases.filter(_.file == ActivationPath).map(bless)),
    ZombiePath     -> envelope(Cases.filter(_.file == ZombiePath).map(bless)),
    HostilePath    -> envelope(Cases.filter(_.file == HostilePath).map(bless)))

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
      sys.error("AuthoredChainSoftFork.writeVectors: path collision would silently drop a file — " +
        collisions.mkString(", "))
    results.foreach { case (rel, json) =>
      val path = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
