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
        case Some("retargeting") => retarget(e)
        case Some("voting")      => vote(e)
        case other               => sys.error(s"unknown chain kind: $other")
      }
      name -> out
    } catch {
      case scala.util.control.NonFatal(t) =>
        name -> Json.obj(
          "nbits" -> Json.Null, "parameters" -> Json.Null, "activated_update" -> Json.Null,
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

    // Parameters.parametersTable is Map[Byte, Int] — vector carries string keys.
    // Real ids live in 1..9 and 120..124 — all < 128. Guard the Byte conversion so a
    // hand-authored id > 127 panics loudly instead of wrapping (BlockEngine's guard).
    val table: Map[Byte, Int] = p.downField("current_parameters").downField("table").focus
      .flatMap(_.asObject).getOrElse(sys.error("payload.current_parameters.table: missing"))
      .toMap.map { case (k, v) =>
        val id = k.toInt
        require(id >= 0 && id <= 127, s"param id $id outside Byte range [0,127]")
        id.toByte -> v.as[Int].fold(err => sys.error(s"param $k: $err"), identity)
      }

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
    val proposedUpdate = ErgoValidationSettingsUpdateSerializer
      .parseBytesTry(scorex.util.encode.Base16.decode(proposedHex)
        .getOrElse(sys.error("proposed_update: hex decode failed")))
      .fold(err => sys.error(s"proposed_update parse: $err"), identity)

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
    // The pair IS the verdict (findings Pin 4, Parameters.scala:82-86; the call form is
    // the spike-proven one, epochVotes via tally.epochVotes.toSeq).
    val (next, activated) = currentParams.update(
      boundaryHeight, forkVote, tally.epochVotes.toSeq, proposedUpdate, votingSettings)
    // Canonical serializer hex (findings Pin 4: toBytes, Parameters.scala:225) —
    // lower-case Base16; the EMPTY update is "0000", never "" (contract §2).
    val activatedHex = scorex.util.encode.Base16.encode(
      ErgoValidationSettingsUpdateSerializer.toBytes(activated))

    // Stringified-int keys sorted numerically, mirroring how BlockEngine reads them.
    val tableJson = Json.obj(next.parametersTable.toSeq.sortBy(_._1.toInt)
      .map { case (k, v) => k.toInt.toString -> Json.fromInt(v) }: _*)
    Json.obj(
      "parameters"       -> Json.obj("table" -> tableJson),
      "activated_update" -> Json.fromString(activatedHex),
      "error"            -> Json.Null) // §3: voting shape
  }

  // ── decode helpers ─────────────────────────────────────────────────────────

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
