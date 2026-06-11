package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredChainLifecycleEdges — enr's lifecycle-parity cross-read asks (their
// prompts/santa-lifecycle-parity-vector-asks.md, 2026-06-12), asks A/B/C/E:
//
//   vectors/chain/v6/authored/Voting.lifecycle_leniency.json (4):
//     leniency-122-without-121-nonforce  [A] the INVERSE of hostile-122-without-
//       121: the JVM throw is LAZY (`lazy val votes`, Parameters.scala:108) —
//       at a boundary that forces nothing (S+4352: past accumulate, not a
//       checkpoint) the orphan-122 table PASSES THROUGH while ordinary
//       updateParams still steps an approved id (the pipeline-ran proof). An
//       impl reading 121 eagerly (error OR default-0) grades coal between this
//       and the hostile vector.
//     inert-121-without-122              [B1] every lifecycle branch gates on
//       122 presence (Parameters.scala:113-147): an orphan 121 with no fork
//       vote is fully inert — verbatim pass-through, votes never forced.
//     overwrite-121-without-122-forkvote [B2] orphan 121 + boundary fork-vote:
//       restart disjunct 1 (122 absent && h % L == 0) fires and OVERWRITES the
//       orphan — 122=T, 121=0.
//     wrap-int-votes-collected           [E] `votes = closing + table(121)` is
//       Int `+` — WRAPS. 121 = Int.MaxValue + 1 closing 120-vote at the S+4224
//       checkpoint: wrapped-negative ⇒ NOT approved ⇒ fail-cleanup removes the
//       counters. A saturating/widening impl stays approved ⇒ keeps them.
//
//   vectors/chain/v6/authored/Voting.tally_order.json (3):
//     JVM `VotingData.epochVotes` is an ARRAY seeded in the boundary header's
//     vote-SLOT order (zero-filtered, duplicates KEPT — ErgoStateContext.scala:
//     238,250) and `updateParams` folds it in sequence order, reading every
//     current value from the post-fork SNAPSHOT (the method arg, NOT the fold
//     accumulator — Parameters.scala:162-182). Hence:
//     tally-order-updown / tally-order-downup  [C1] a contradictory ±1 pair
//       (on-chain UNREACHABLE — hdrVotesContradictory rejects such headers
//       upstream; same legality-upstream framing as the negative-id tally
//       entries): both ids approved ⇒ LAST-WRITE-WINS by seed-slot order, each
//       write stepping from the SNAPSHOT — [+1,−1] nets one step DOWN,
//       [−1,+1] one step UP. A running-table impl nets ±0 on both.
//     tally-dup-120-first-entry          [C2] a duplicated 120 seed slot keeps
//       TWO tally entries; `votesInPrevEpoch = epochVotes.find(_._1 ==
//       SoftFork)` reads the FIRST (count k), a summing impl reads k+1.
//       Straddled at the S+4224 checkpoint: JVM 3676+10 = 3686 NOT approved
//       (fail-cleanup fires), summing 3687 approved (no cleanup) — the
//       lifecycle outcome discriminates.
//
// Every expected is ORACLE-EMITTED via ChainEngine.chainEntry (the engine's
// tally mirrors ErgoStateContext.process over the JVM's own VotingData, so the
// order/dup semantics under pin are the oracle's, never hand-derived).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredChainLifecycleEdges {

  /** House oracle identity (CapturedChain's). */
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-chain-model"

  val LeniencyPath = "chain/v6/authored/Voting.lifecycle_leniency.json"
  val TallyPath    = "chain/v6/authored/Voting.tally_order.json"

  private val L = 128
  private val S = 2560 // the round anchor where one is in play (122 = S)

  private val Settings = Json.obj(
    "voting_length"              -> Json.fromInt(L),
    "soft_fork_epochs"           -> Json.fromInt(32),
    "activation_epochs"          -> Json.fromInt(32),
    "version2_activation_height" -> Json.fromInt(417792))

  /** The real boundary-2560 table — INPUT ONLY (CapturedChainTest pins it). */
  private val BaseV4: Map[String, Int] = Map(
    "1" -> 1250000, "2" -> 360, "3" -> 524288, "4" -> 1000000, "5" -> 100,
    "6" -> 2000, "7" -> 100, "8" -> 100, "9" -> 30, "123" -> 4)

  /** One case. `votersCount` voters from the window head; the SEED (T−L, slot
    * order is the pin!) casts `seedVotesHex`, the rest cast `votesHex`. */
  private final case class LCase(file: String, name: String, source: String,
                                 boundary: Int, table: Map[String, Int],
                                 votersCount: Int, votesHex: String, seedVotesHex: String,
                                 boundaryVotes: String, note: String)

  private def lc(file: String, name: String, slug: String, boundary: Int,
                 table: Map[String, Int], votersCount: Int = 0,
                 votesHex: String = "000000", seedVotesHex: String = null,
                 boundaryVotes: String = "000000", note: String): LCase =
    LCase(file, name, s"santa:$slug", boundary, table, votersCount, votesHex,
      Option(seedVotesHex).getOrElse(votesHex), boundaryVotes, note)

  private val Cases: Seq[LCase] = Seq(
    // ── lifecycle_leniency ──────────────────────────────────────────────────
    lc(LeniencyPath, "leniency-122-without-121-nonforce", "lifecycle_leniency:122-no-121-nonforce",
      boundary = S + 4352, table = BaseV4 + ("122" -> S),
      votersCount = 66, votesHex = "010000",
      note = "the INVERSE of hostile-122-without-121: `votes` is LAZY (Parameters.scala:108) " +
        "and S+4352 forces nothing (past the accumulate window, not a checkpoint) — the " +
        "orphan-122 table PASSES THROUGH (122 retained, 121 still absent) while the 66 " +
        "approved id-1 votes still step StorageFeeFactor (the pipeline-ran proof). An impl " +
        "reading 121 eagerly — error or default-0 — diverges between this entry and the " +
        "hostile one (which sits at an accumulate boundary, a force site)."),
    lc(LeniencyPath, "inert-121-without-122", "lifecycle_leniency:121-no-122-inert",
      boundary = S, table = BaseV4 + ("121" -> 777),
      note = "every lifecycle branch gates on 122 presence (Parameters.scala:113-147): an " +
        "orphan 121 with no boundary fork-vote is fully INERT — verbatim pass-through " +
        "(votes never forced, no throw despite the weird table)."),
    lc(LeniencyPath, "overwrite-121-without-122-forkvote", "lifecycle_leniency:121-no-122-overwrite",
      boundary = S, table = BaseV4 + ("121" -> 777),
      boundaryVotes = "780000",
      note = "orphan 121 + a fork-voting boundary: restart disjunct 1 (122 absent && " +
        "h % L == 0) fires and OVERWRITES the orphan — 122=T, 121=0. The 777 is " +
        "discarded, not summed, not retained."),
    lc(LeniencyPath, "wrap-int-votes-collected", "lifecycle_leniency:wrap-int-votes",
      boundary = S + 4224, table = BaseV4 + ("121" -> 2147483647) + ("122" -> S),
      votersCount = 1, votesHex = "780000",
      note = "`votes = votesInPrevEpoch + table(121)` is Int `+` — it WRAPS: " +
        "2147483647 + 1 closing 120-vote = Int.MinValue ⇒ NOT approved at the S+4224 " +
        "checkpoint ⇒ unsuccessful-voting cleanup removes 121/122. A saturating or " +
        "widening implementation stays approved and KEEPS the counters."),

    // ── tally_order ─────────────────────────────────────────────────────────
    lc(TallyPath, "tally-order-updown", "tally_order:updown",
      boundary = S, table = BaseV4,
      votersCount = 65, votesHex = "01ff00",
      note = "contradictory ±1 votes, seed slots [+1, −1] (on-chain UNREACHABLE — " +
        "hdrVotesContradictory rejects the header upstream; legality-upstream framing): " +
        "65 headers each vote both ids ⇒ both approved (65 > 64). updateParams folds in " +
        "SEED-SLOT order, each write stepping from the post-fork SNAPSHOT (the method " +
        "arg, not the fold accumulator): +1 writes snapshot+25000, then −1 writes " +
        "snapshot−25000 — LAST WRITE WINS: id 1 ends 1225000. A running-table impl nets " +
        "1250000; a map-ordered impl flips with the twin entry."),
    lc(TallyPath, "tally-order-downup", "tally_order:downup",
      boundary = S, table = BaseV4,
      votersCount = 65, votesHex = "01ff00", seedVotesHex = "ff0100",
      note = "the order twin: seed slots [−1, +1] (mid-epoch voters unchanged — " +
        "VotingData.update increments existing entries without reordering): the fold " +
        "runs −1 then +1, so id 1 ends 1275000. Together with tally-order-updown this " +
        "pins BOTH the array order and the snapshot read."),
    lc(TallyPath, "tally-dup-120-first-entry", "tally_order:dup-120-first-entry",
      boundary = S + 4224, table = BaseV4 + ("121" -> 3676) + ("122" -> S),
      votersCount = 10, votesHex = "780000", seedVotesHex = "787800",
      note = "a DUPLICATED 120 seed slot keeps two tally entries (zero-filtered, " +
        "duplicates kept — ErgoStateContext.scala:238,250): seed [120,120] + 9 mid " +
        "120-votes ⇒ entries (120,10),(120,1). `votesInPrevEpoch` is " +
        "epochVotes.find(_._1 == SoftFork) = the FIRST = 10; votes = 3676+10 = 3686 ⇒ " +
        "NOT approved (strict >) ⇒ the S+4224 fail-cleanup removes 121/122. A SUMMING " +
        "impl reads 11 ⇒ 3687 approved ⇒ keeps the counters — the lifecycle outcome " +
        "discriminates."))

  // ── entry assembly ──────────────────────────────────────────────────────────

  private def window(boundary: Int): Seq[Int] =
    math.max(1, boundary - L) until boundary

  private def voterSet(c: LCase): Set[Int] =
    if (c.votersCount == 0) Set.empty
    else ((c.boundary - L) to (c.boundary - L + c.votersCount - 1)).toSet

  private def tableJson(t: Map[String, Int]): Json =
    Json.obj(t.toSeq.sortBy(_._1.toInt).map { case (k, v) => k -> Json.fromInt(v) }: _*)

  private def baseEntry(c: LCase): Seq[(String, Json)] = {
    val voters = voterSet(c)
    val seedHeight = c.boundary - L
    val stream = window(c.boundary).map { h =>
      val hex = if (!voters(h)) "000000"
                else if (h == seedHeight) c.seedVotesHex
                else c.votesHex
      Json.obj("height" -> Json.fromInt(h), "votes" -> Json.fromString(hex))
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
        "proposed_update"    -> Json.fromString("0000")))
  }

  /** Bless one case — all seven are ACCEPT vectors (the leniency family's whole
    * point: the JVM does NOT throw here); any engine error is a recipe bug. */
  private def bless(c: LCase): Json = {
    val base = baseEntry(c)
    val (_, actual) = santa.runner.ChainEngine.chainEntry(Json.obj(base: _*))
    val err = actual.hcursor.get[String]("error").toOption
    if (err.nonEmpty)
      sys.error(s"AuthoredChainLifecycleEdges[${c.name}]: engine returned error=${err.get} " +
        s"note=${actual.hcursor.get[String]("note").toOption.getOrElse("<none>")} — " +
        "these are all accept cases (the leniency IS the pin); fail loud")
    val table = actual.hcursor.downField("parameters").downField("table").focus
      .getOrElse(sys.error(s"AuthoredChainLifecycleEdges[${c.name}]: no parameters.table"))
    val activated = actual.hcursor.get[String]("activated_update")
      .fold(e => sys.error(s"AuthoredChainLifecycleEdges[${c.name}]: activated_update: $e"), identity)
    Json.obj(base ++ Seq(
      "expected" -> Json.obj(
        "parameters"       -> Json.obj("table" -> table),
        "activated_update" -> Json.fromString(activated)),
      "diagnostic" -> Json.obj("note" -> Json.fromString(c.note))): _*)
  }

  // ── public API ──────────────────────────────────────────────────────────────

  def blessAll(): Seq[(String, Json)] = Seq(
    LeniencyPath -> envelope(Cases.filter(_.file == LeniencyPath).map(bless)),
    TallyPath    -> envelope(Cases.filter(_.file == TallyPath).map(bless)))

  private def envelope(entries: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-chain/v1"),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries"    -> Json.arr(entries: _*))

  /** Persist at the COMMITTED paths (collision-guarded, like the siblings). */
  def writeVectors(results: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit = {
    val collisions = results.map(_._1).groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("AuthoredChainLifecycleEdges.writeVectors: path collision — " + collisions.mkString(", "))
    results.foreach { case (rel, json) =>
      val path = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
