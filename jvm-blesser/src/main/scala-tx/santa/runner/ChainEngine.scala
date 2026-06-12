package santa.runner

import io.circe.{ACursor, Json}

import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.mining.difficulty.{DifficultyAdjustment, DifficultySerializer}
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.nodeView.state.VotingData
import org.ergoplatform.settings.{ChainSettingsReader, ErgoValidationSettingsUpdate,
  ErgoValidationSettingsUpdateSerializer, Parameters, VotingSettings}

/** The gated chain-tier engine: ergo-core's pure seams over header-chain inputs —
  * `DifficultyAdjustment.calculate`/`eip37Calculate` for retargeting and
  * `Parameters.update` (with the `ErgoStateContext.process`-mirrored tally) for voting.
  * Same gate + reflection seam as Tx/BlockEngine: an ergo-core-less build degrades the
  * runner's chain arm to not-implemented.
  *
  * Entries are SELF-CONTAINED: every value the computation reads comes from
  * entry.settings/payload — never from a bundled conf (runner-contract-chain §5; the
  * bundled conf carries mainnet drift in `epochLength`/`blockInterval`, so it is a
  * template ONLY, for fields the calculation never reads).
  */
object ChainEngine extends ApiCodecs {
  // FILE path (not classpath), shared with Tx/BlockEngine — santa-run forks sbt from
  // jvm-blesser/, so the cwd-relative read works for both scopes.
  private val ChainConf = "src/test/resources/chain-testnet.conf"

  /** One `santa-chain` vector entry → actuals (per-kind result shape, contract §3).
    * Decode failures = harness self-contradiction → panicked (never aborts the file). */
  def chainEntry(e: Json): (String, Json) = {
    val c = e.hcursor
    val name = c.get[String]("name").toOption.getOrElse("?")
    try {
      val out = c.get[String]("kind").toOption match {
        case Some("retargeting")   => retarget(e)
        case Some("voting")        => vote(e)
        case Some("fork_vote_gate") => forkVoteGate(e)
        case Some("header_votes")  => headerVotes(e)
        case other                 => sys.error(s"unknown chain kind: $other")
      }
      name -> out
    } catch {
      case scala.util.control.NonFatal(t) =>
        name -> Json.obj(
          "nbits" -> Json.Null, "parameters" -> Json.Null, "activated_update" -> Json.Null,
          "valid" -> Json.Null,
          "error" -> Json.fromString("panicked"),
          "note"  -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"))
    }
  }

  /** `kind: "retargeting"` — the required difficulty (compact nBits) for
    * `payload.target_height`, computed from the epoch-spaced `payload.anchor_headers`
    * (node-API header JSON, circe `as[Header]` via ApiCodecs — the santa-block embed). */
  private def retarget(e: Json): Json = {
    val s = e.hcursor.downField("settings")
    val entryEpochLength = reqInt(s, "epoch_length")
    val useLastEpochs    = reqInt(s, "use_last_epochs")
    val blockIntervalMs  = reqLong(s, "block_interval_ms")
    val initialNBits     = reqLong(s, "initial_nbits")
    val eip37Activation  = optInt(s, "eip37_activation_height")
    val eip37EpochLength = optInt(s, "eip37_epoch_length")
    require(eip37Activation.isDefined == eip37EpochLength.isDefined,
      "eip37_activation_height and eip37_epoch_length must be present together")

    val p = e.hcursor.downField("payload")
    val targetHeight = reqInt(p, "target_height")
    val anchors: Seq[Header] = p.downField("anchor_headers").focus.flatMap(_.asArray)
      .getOrElse(sys.error("payload.anchor_headers: missing or not an array"))
      .map(_.as[Header].fold(err => sys.error(s"anchor header decode: $err"), identity))

    // Classic-vs-EIP-37 dispatch mirroring HeadersProcessor.requiredDifficultyAfter,
    // driven by ENTRY settings (findings Pin 2 / contract §2): the eip37 arm is taken
    // iff the settings pair is present AND T >= eip37_activation_height — and when it
    // governs, eip37_epoch_length replaces epoch_length THROUGHOUT (the recalculation
    // predicate, the anchor spacing, the eip37Calculate argument, and the ctor-checked
    // ChainSettings.epochLength field below).
    val eip37Governs = (eip37Activation, eip37EpochLength) match {
      case (Some(activation), Some(_)) => targetHeight >= activation
      case _                           => false
    }
    val epochLength = if (eip37Governs) eip37EpochLength.get else entryEpochLength

    // requiredDifficultyAfter recalculates exactly when parentHeight % L == 0, i.e.
    // (T - 1) % L == 0 (findings Pin 2). v1 vectors MUST target recalculation points
    // (contract §2) — the mid-epoch parent-echo arm is outside the graded surface, so
    // a non-recalc target is a malformed fixture and panics loudly.
    require((targetHeight - 1) % epochLength == 0,
      s"target_height $targetHeight is not a recalculation point for epoch length $epochLength")
    require(anchors.nonEmpty, "anchor_headers must be non-empty")
    // The node gathers headers at previousHeightsRequiredForRecalculation(T, L), whose
    // last element is always the parent T-1 (`.ensuring(_.last == parentHeight)`,
    // findings Pin 2); anchors arrive ASCENDING, so the last anchor must be the parent.
    require(anchors.last.height == targetHeight - 1,
      s"last anchor height ${anchors.last.height} != target parent ${targetHeight - 1}")

    // ChainSettings template-.copy (findings "Settings ground truth", verbatim form):
    // every field the calculation reads — blockInterval, useLastEpochs,
    // initialDifficulty (via initialDifficultyHex), epochLength — comes from ENTRY
    // settings (contract §5); the conf is a template for the fields it never reads.
    // The initialDifficultyHex ← initial_nbits translation round-trips:
    // decodeCompactBits(16842752) = 1 → "01".
    val cs = ChainSettingsReader.read(ChainConf).getOrElse(sys.error(s"chain settings: $ChainConf"))
      .copy(blockInterval = scala.concurrent.duration.FiniteDuration(blockIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS),
            epochLength   = epochLength,
            useLastEpochs = useLastEpochs,
            initialDifficultyHex = scorex.util.encode.Base16.encode(
              DifficultySerializer.decodeCompactBits(initialNBits).toByteArray))

    // Spike-proven form (findings "Resolved ADJUST markers"): anchors ascending, step
    // == epochLength (DifficultyAdjustment's own require enforces the step per pair;
    // a single anchor takes the early-chain echo arm, 2-8 the sliding-pairs arm).
    val da = new DifficultyAdjustment(cs)
    val diff: BigInt =
      if (eip37Governs) da.eip37Calculate(anchors, epochLength) // findings Pin 1, :80
      else da.calculate(anchors, epochLength)                   // findings Pin 1, :106
    val nbits: Long = DifficultySerializer.encodeCompactBits(diff) // findings Pin 1, :49

    Json.obj("nbits" -> Json.fromLong(nbits), "error" -> Json.Null) // §3: retargeting shape
  }

  /** `kind: "voting"` — the epoch-boundary parameter verdict: tally the window's raw
    * vote stream exactly as `ErgoStateContext.process` does, then `Parameters.update`
    * → (full next table, activated update). */
  private def vote(e: Json): Json = {
    val s = e.hcursor.downField("settings")
    val votingLength     = reqInt(s, "voting_length")
    val softForkEpochs   = reqInt(s, "soft_fork_epochs")
    val activationEpochs = reqInt(s, "activation_epochs")
    // Optional; absent ⇒ 0 (contract §2): safe for v6-era tables — updateFork's forced
    // v1→v2 bump reads it but requires table(123) == 1 to fire (findings Pin 4, :151).
    val v2ActivationHeight = optInt(s, "version2_activation_height").getOrElse(0)

    val p = e.hcursor.downField("payload")
    val boundaryHeight = reqInt(p, "boundary_height")
    // Header.votingStarts (findings Pin 3, Header.scala:116): the boundary predicate is
    // T % votingLength == 0 && T > 0 — a non-boundary height is a malformed fixture.
    require(boundaryHeight > 0 && boundaryHeight % votingLength == 0,
      s"boundary_height $boundaryHeight is not an epoch boundary for voting_length $votingLength")

    val table: Map[Byte, Int] = decodeTable(p)

    val stream: Vector[(Int, Array[Byte])] = p.downField("vote_stream").focus
      .flatMap(_.asArray).getOrElse(sys.error("payload.vote_stream: missing or not an array"))
      .map { vj =>
        val vc = vj.hcursor
        val h = vc.get[Int]("height").fold(err => sys.error(s"vote_stream height: $err"), identity)
        h -> votesBytes(vc.get[String]("votes")
          .fold(err => sys.error(s"vote_stream[$h] votes: $err"), identity), s"vote_stream[$h].votes")
      }

    val boundaryVotes = votesBytes(p.get[String]("boundary_votes")
      .fold(err => sys.error(s"payload.boundary_votes: $err"), identity), "boundary_votes")

    // proposedUpdate = the boundary block's own extension field [0x00, 124], parsed via
    // the canonical serializer (findings Pin 4: parseBytesTry; Parameters.scala:256-257).
    val proposedHex = p.get[String]("proposed_update")
      .fold(err => sys.error(s"payload.proposed_update: $err"), identity)
    val proposedBytes = scorex.util.encode.Base16.decode(proposedHex)
      .getOrElse(sys.error("proposed_update: hex decode failed"))

    // Tally mirroring ErgoStateContext.process (findings Pin 3 + the spike-proven form):
    // the seed is stream[0]'s votes iff stream[0] IS the previous boundary
    // (height == T - votingLength; :250-251 `VotingData(votes.map(_ -> 1))`), else —
    // chain-start clamp — the seed is empty (VotingData.empty). NoParameter (0x00) is
    // filtered BEFORE seeding/folding (:236). The rest fold via VotingData.update, which
    // increments ONLY ids already present (VotingData.scala:9-13) — mid-epoch votes for
    // unseeded ids are silently dropped, exactly as the node drops them. The boundary
    // header T itself is NOT in the stream: its votes arrive as payload.boundary_votes.
    val seedIsPrevBoundary = stream.headOption.exists(_._1 == boundaryHeight - votingLength)
    val seedVotes: Array[Byte] =
      if (seedIsPrevBoundary) stream.head._2.filter(_ != Parameters.NoParameter)
      else Array.empty[Byte]
    val rest = if (seedIsPrevBoundary) stream.tail else stream
    val seeded = VotingData(seedVotes.map(_ -> 1))
    val tally = rest.foldLeft(seeded) { case (vd, (_, votes)) =>
      votes.filter(_ != Parameters.NoParameter).foldLeft(vd) { case (v, id) => v.update(id) } }

    // forkVote from the BOUNDARY header's own votes (findings Pin 3, :236-240):
    // filtered of NoParameter, contains SoftFork (= 120, Parameters.scala:252).
    val forkVote = boundaryVotes.filter(_ != Parameters.NoParameter).contains(Parameters.SoftFork)

    // update reads NOTHING from the receiver except parametersTable — the height stamp
    // and carried update are never consulted, so this receiver is semantically safe
    // (findings Pin 4: "the Task-7 sketch's receiver is therefore semantically safe").
    val currentParams = new Parameters(boundaryHeight - 1, table, ErgoValidationSettingsUpdate.empty)
    // VotingSettings: the FIVE-field ctor (findings Pin 4, VotingSettings.scala:3-7).
    // version2ActivationDifficultyHex is never read by update — any template value is
    // safe; the node's application.conf value is used.
    val votingSettings = VotingSettings(votingLength, softForkEpochs, activationEpochs,
      v2ActivationHeight, "6f98d5000000")

    // ── The consensus seam (contract §2 reject form / §3): everything the JVM itself
    // rejects about these INPUTS throws here — the proposed-update parse (extension
    // validation: mandatory rules may not be disabled) and Parameters.update (hostile
    // tables: 122-without-121 forcing `votes`, approved votes for table-absent ids).
    // A throw is the JVM's verdict on the inputs → errored envelope (note = the throw),
    // never panicked. Decode/setup failures above stay panicked; the encode below is
    // OUTSIDE the seam (not a verdict on the inputs — an encode throw is a harness bug
    // and must stay panicked, like the sibling engines).
    val verdict: Either[Throwable, (Parameters, ErgoValidationSettingsUpdate)] =
      try {
        val proposedUpdate = ErgoValidationSettingsUpdateSerializer
          .parseBytesTry(proposedBytes)
          .fold(err => throw err, identity)
        Right(currentParams.update(
          boundaryHeight, forkVote, tally.epochVotes.toSeq, proposedUpdate, votingSettings))
      } catch {
        case scala.util.control.NonFatal(t) => Left(t)
      }

    verdict match {
      case Left(t) =>
        Json.obj(
          "parameters" -> Json.Null, "activated_update" -> Json.Null,
          "error" -> Json.fromString("errored"),
          "note"  -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"))
      case Right((next, activated)) =>
        val activatedHex = scorex.util.encode.Base16.encode(
          ErgoValidationSettingsUpdateSerializer.toBytes(activated))
        val tableJson = Json.obj(next.parametersTable.toSeq.sortBy(_._1.toInt)
          .map { case (k, v) => k.toInt.toString -> Json.fromInt(v) }: _*)
        Json.obj(
          "parameters"       -> Json.obj("table" -> tableJson),
          "activated_update" -> Json.fromString(activatedHex),
          "error"            -> Json.Null) // §3: voting shape
    }
  }

  /** `kind: "fork_vote_gate"` — ErgoStateContext.checkForkVote (rule 407) MIRRORED
    * over public API (the method is protected; ForkVoteGateSpike proved the mirror ≡
    * the real method via reflection across a 30-row window grid). Three outcomes:
    * valid:true (pass — incl. no-120-in-votes, no-round, outside both windows),
    * valid:false (the CLEAN rule-407 prohibition), errored (the eager
    * softForkVotesCollected.get on a 122-without-121 table — contract §2: the split
    * pins finer than the JVM block-acceptance observable). The 120 precondition is
    * the JVM call site's `if (forkVote)`, folded in — and it PRECEDES the gate's
    * table reads, so non-120 votes pass over any decodable table.
    * Parity-grid skeleton (re-derivable after the spike's deletion; testnet
    * settings 128/32/32, S = table[122] = 2560 ⇒ finishing 6656 · finishing+L
    * 6784 · afterActivation 10880): not-approved (3686) prohibits exactly
    * [6656, 6784); approved (3687) prohibits [6656, 10880); no-round passes at
    * every height; 122-without-121 errors at every height once votes carry 120. */
  private def forkVoteGate(e: Json): Json = {
    val s = e.hcursor.downField("settings")
    val votingLength     = reqInt(s, "voting_length")
    val softForkEpochs   = reqInt(s, "soft_fork_epochs")
    val activationEpochs = reqInt(s, "activation_epochs")
    // present-but-unread (contract §2: settings uniformity with the voting kind)
    reqInt(s, "version2_activation_height")

    val p = e.hcursor.downField("payload")
    val height = reqInt(p, "height")
    require(height >= 1, s"height $height must be >= 1")
    val headerVotes = votesBytes(p.get[String]("header_votes")
      .fold(err => sys.error(s"payload.header_votes: $err"), identity), "header_votes")
    val table = decodeTable(p)

    val forkVote = headerVotes.filter(_ != Parameters.NoParameter).contains(Parameters.SoftFork)
    def verdict(valid: Boolean): Json = Json.obj(
      "valid" -> Json.fromBoolean(valid), "error" -> Json.Null)

    if (!forkVote) verdict(true)
    else {
      val params = new Parameters(height, table, ErgoValidationSettingsUpdate.empty)
      params.softForkStartingHeight match {
        case None => verdict(true)
        case Some(start) =>
          // ── the consensus seam: the eager collected read is the gate's one throw
          // class (NoSuchElementException on 122-without-121) → errored, never
          // panicked. The window arithmetic below it is total. (Int-wraps on hostile
          // settings — out of scope per the floor-only schema; settings stay sane).
          try {
            val collected = params.softForkVotesCollected.get
            val finishing = start + votingLength * softForkEpochs
            val afterAct  = finishing + votingLength * (activationEpochs + 1)
            val approved  = VotingSettings(votingLength, softForkEpochs, activationEpochs,
              0, "6f98d5000000").softForkApproved(collected)
            val prohibited =
              (height >= finishing && height < finishing + votingLength && !approved) ||
              (height >= finishing && height < afterAct && approved)
            verdict(!prohibited)
          } catch {
            case scala.util.control.NonFatal(t) =>
              Json.obj(
                "valid" -> Json.Null,
                "error" -> Json.fromString("errored"),
                "note"  -> Json.fromString(s"${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}"))
          }
      }
    }
  }

  /** `kind: "header_votes"` — ErgoStateContext.validateVotes (rules 212–214,
    * ErgoStateContext.scala:329-346) MIRRORED over the raw 3-byte vote field
    * (HeaderVotesSpike proved the mirror ≡ the real `private def validateVotes`
    * via reflection across a 12-row grid). TWO outcomes only — valid:true (all
    * three pass) / valid:false (any fails); NO errored arm: pure byte logic over a
    * 3-byte array cannot throw (no eager table read, no `.get`, unlike fork_vote_gate).
    *
    * The seam reads ONLY payload.votes; settings are present-but-unread (contract §2
    * uniformity with the voting/fork_vote_gate kinds; shared conformer decoders). The
    * three rules run over `votes = field.filter(_ != 0)` (the 0-filtered slice):
    *   212 hdrVotesNumber:      votes.count(_ != 120) <= 2   (120 free for the COUNT)
    *   213 hdrVotesDuplicates:  each v in votes appears exactly once (120 NOT exempt —
    *                            the asymmetry with 212; only 0 is removed pre-check)
    *   214 hdrVotesContradictory: reverseVotes = votes.map(v => (-v).toByte); no v has
    *                            its negation present. SIGNED i8 arith — 0x80 (−128) is
    *                            its own negation, so [0x80,0,0] self-contradicts.
    * Rule 215 (hdrVotesUnknown) is OUT OF SCOPE — it fires only at epoch starts
    * (height-dependent) and is deferred (contract §2 header_votes); no 215 arm here. The shared
    * helper `checkHeaderVotes` is the exact byte logic HeaderVotesSpike proves against
    * the real method at a NON-epoch-start height (215 dormant ⇒ verdict == 212∧213∧214).
    *
    * Parity grid (re-derivable after the spike's deletion): [1,2,3]→false (212),
    * [1,2,120]→true, [1,0,0]→true, [1,1,0]→false (213), [120,120,0]→false (213
    * 120-dup asymmetry), [120,1,2]→true (120-count corollary), [1,0xFF,0]→false (214,
    * 0xFF=−1 contradicts 1), [0x80,0,0]→false (214 self-negation), [4,3,0]→true,
    * [0,0,0]→true, [120,0,0]→true, [120,4,3]→true. */
  private def headerVotes(e: Json): Json = {
    val s = e.hcursor.downField("settings")
    // present-but-unread (contract §2: settings uniformity with the voting kinds)
    reqInt(s, "voting_length"); reqInt(s, "soft_fork_epochs")
    reqInt(s, "activation_epochs"); reqInt(s, "version2_activation_height")

    val p = e.hcursor.downField("payload")
    val votes = votesBytes(p.get[String]("votes")
      .fold(err => sys.error(s"payload.votes: $err"), identity), "votes")

    // §3: header_votes shape — two-outcome, NO errored arm (the byte logic is total).
    Json.obj("valid" -> Json.fromBoolean(checkHeaderVotes(votes)), "error" -> Json.Null)
  }

  /** The exact ErgoStateContext.validateVotes byte logic for rules 212–214, factored
    * so HeaderVotesSpike exercises THIS code against the real method (the tally-mirror
    * precedent). Signed-byte semantics throughout: `(-v).toByte` negation so 0x80
    * self-negates; 0 (NoParameter) filtered before all three checks; 120 (SoftFork)
    * free for the 212 count but NOT exempt from the 213 dup check. */
  def checkHeaderVotes(field: Array[Byte]): Boolean = {
    val votes: Array[Byte] = field.filter(_ != Parameters.NoParameter)
    val reverseVotes: Array[Byte] = votes.map(v => (-v).toByte)
    val rule212 = votes.count(_ != Parameters.SoftFork) <= Parameters.ParamVotesCount
    val rule213 = votes.forall(v => votes.count(_ == v) == 1)
    val rule214 = votes.forall(v => !reverseVotes.contains(v))
    rule212 && rule213 && rule214
  }

  // ── decode helpers ─────────────────────────────────────────────────────────

  /** parameters_table decode shared by voting + fork_vote_gate: string keys →
    * Map[Byte, Int]. Real ids live in 1..9 and 120..124 — all < 128. Guard the Byte
    * conversion so a hand-authored id > 127 panics loudly instead of wrapping
    * (BlockEngine's guard). */
  private def decodeTable(p: ACursor): Map[Byte, Int] =
    p.downField("current_parameters").downField("table").focus
      .flatMap(_.asObject).getOrElse(sys.error("payload.current_parameters.table: missing"))
      .toMap.map { case (k, v) =>
        val id = k.toInt
        require(id >= 0 && id <= 127, s"param id $id outside Byte range [0,127]")
        id.toByte -> v.as[Int].fold(err => sys.error(s"param $k: $err"), identity)
      }

  private def reqInt(c: ACursor, k: String): Int =
    c.get[Int](k).fold(err => sys.error(s"$k: $err"), identity)

  private def reqLong(c: ACursor, k: String): Long =
    c.get[Long](k).fold(err => sys.error(s"$k: $err"), identity)

  /** Absent ⇒ None; present-but-malformed ⇒ panic (a wrong-typed optional must not
    * silently flip a dispatch arm). */
  private def optInt(c: ACursor, k: String): Option[Int] =
    c.downField(k).focus.map(_.as[Int].fold(err => sys.error(s"$k: $err"), identity))

  /** A header `votes` field: 6 hex chars = exactly 3 bytes (the node's fixed width). */
  private def votesBytes(hex: String, field: String): Array[Byte] = {
    val bs = scorex.util.encode.Base16.decode(hex)
      .getOrElse(sys.error(s"$field: hex decode failed"))
    require(bs.length == 3, s"$field must be 3 bytes (6 hex chars), got ${bs.length}")
    bs
  }
}
