package santa

// ─────────────────────────────────────────────────────────────────────────────
// CapturedChain — assemble + bless the captured chain-tier corpus
// (docs/findings/chain-captures/) into `santa-chain/v1` vector envelopes,
// driving the gated ChainEngine (ergo-core's pure seams:
// DifficultyAdjustment.calculate for retargeting; Parameters.update with the
// ErgoStateContext.process-mirrored tally for voting).
//
// Two committed files — the first chain corpus (written IN PLACE; re-blessing
// regenerates them, contract §6):
//   vectors/chain/any/captured/Retargeting.testnet_points.json  (2 entries)
//   vectors/chain/v6/captured/Voting.testnet_epoch_2560.json    (1 entry)
//
// Settings are hard-coded to the spike-verified testnet values — recorded
// INPUTS sourced from the captures (contract §5 self-containment), never read
// from a conf at runner time. Payloads are embedded VERBATIM from the captures:
// the anchor arrays, the vote stream, the boundary header's own votes, and the
// boundary extension's [0x00, 124] ("007c") proposed-update field.
//
// ── Parameter table extraction ────────────────────────────────────────────────
// Boundary-block tables come from the extension's 00KK → 4-byte big-endian Int
// fields via Parameters.parseExtension (the real ergo-core reader, decoded
// through Extension.jsonDecoder) — CapturedBlock's idiom, anchors verified
// (table(4) == 1000000 maxBlockCost, table(123) == 4 blockVersion).
//
// ── FAIL-LOUD against chain history (contract §6) ────────────────────────────
// Captured ⇒ the blessed output IS chain history. Before emitting, the engine's
// output is cross-checked against the captured truth:
//   - retargeting: blessed nbits MUST equal the real target header's nBits
//     (target-p1/p2.json — what the chain actually required at T);
//   - voting: the blessed table MUST equal Parameters.parseExtension(2560,
//     <real boundary-2560 extension>).parametersTable, AND the activated update
//     MUST be "0000" (the findings-pinned empty update — the standing testnet
//     proposal had collected no activation by 2560).
// A mismatch is a blesser/fixture bug, never the chain: sys.error with both
// values; never emit.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import io.circe.parser.parse
import org.ergoplatform.mining.difficulty.DifficultySerializer
import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.settings.Parameters

object CapturedChain {

  /** Provenance stamp for the envelope `blessed_by` (house convention: the oracle
    * identity — block's execTransactions-model / tx's validateStateful sibling).
    * Per-entry `source` is `testnet:<seed-dir>@<height>` (contract §6). */
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-chain-model"

  /** docs/findings/ lives at repo root; the blesser runs from jvm-blesser/ cwd. */
  private val CapturesDir = "../docs/findings/chain-captures"
  private val RetargetDir = s"$CapturesDir/testnet-retarget"
  private val VotingDir   = s"$CapturesDir/testnet-voting-2560"

  /** Committed output paths, vectors/-relative. Kind↔version per contract §2:
    * retargeting is height-gated ⇒ `any`; voting is era-coupled ⇒ `v6`. */
  val RetargetingPath = "chain/any/captured/Retargeting.testnet_points.json"
  val VotingPath      = "chain/v6/captured/Voting.testnet_epoch_2560.json"

  // ── file / JSON helpers ────────────────────────────────────────────────────

  private def readFile(p: String): String = {
    val src = scala.io.Source.fromFile(p)
    try src.mkString finally src.close()
  }

  private def parseFile(p: String): Json =
    parse(readFile(p)).fold(e => sys.error(s"CapturedChain: parse $p: $e"), identity)

  private def reqStr(c: io.circe.ACursor, k: String, what: String): String =
    c.get[String](k).fold(e => sys.error(s"CapturedChain[$what]: $k: $e"), identity)

  private def reqInt(c: io.circe.ACursor, k: String, what: String): Int =
    c.get[Int](k).fold(e => sys.error(s"CapturedChain[$what]: $k: $e"), identity)

  /** Drive ChainEngine.chainEntry; a captured entry must bless cleanly — any
    * non-null `error` (incl. the caught-panic envelope) is a blesser/capture bug. */
  private def engineActual(entry: Json, what: String): Json = {
    val (_, actual) = santa.runner.ChainEngine.chainEntry(entry)
    val err = actual.hcursor.downField("error").focus.getOrElse(Json.Null)
    if (!err.isNull)
      sys.error(s"CapturedChain[$what]: engine returned error=${err.noSpaces} " +
        s"note=${actual.hcursor.get[String]("note").toOption.getOrElse("<none>")} — " +
        "a captured entry must bless cleanly")
    actual
  }

  // ── parameter-table extraction (CapturedBlock's parseExtension idiom) ──────

  /** Decode a captured boundary block's extension and read its system-parameter
    * table via Parameters.parseExtension — the real ergo-core reader. Returns the
    * table as numerically-sorted string-keyed JSON (the `parameters.table` house
    * shape). The two known anchors are verified before use. */
  private def extensionTable(boundaryJson: Json, height: Int): Json = {
    val extJson = boundaryJson.hcursor.downField("extension").focus
      .getOrElse(sys.error(s"CapturedChain: boundary $height has no extension field"))
    val extension = extJson.as[Extension](Extension.jsonDecoder)
      .fold(e => sys.error(s"CapturedChain: Extension decode ($height): $e"), identity)
    val params = Parameters.parseExtension(height, extension)
      .fold(e => sys.error(s"CapturedChain: parseExtension($height) failed: $e"), identity)
    val table: Map[Int, Int] = params.parametersTable.map { case (k, v) => k.toInt -> v }
    if (table.getOrElse(4, -1) != 1000000)
      sys.error(s"CapturedChain: boundary $height maxBlockCost anchor: expected 1000000 got ${table.get(4)}")
    if (table.getOrElse(123, -1) != 4)
      sys.error(s"CapturedChain: boundary $height blockVersion anchor: expected 4 got ${table.get(123)}")
    Json.obj(table.toSeq.sortBy(_._1).map { case (k, v) =>
      k.toString -> Json.fromInt(v)
    }: _*)
  }

  /** The boundary capture's header cursor, identity-checked (the file is the
    * block we think it is). */
  private def boundaryHeader(boundaryJson: Json, height: Int): io.circe.ACursor = {
    val c = boundaryJson.hcursor.downField("header")
    val got = reqInt(c, "height", s"boundary-$height header")
    if (got != height)
      sys.error(s"CapturedChain: boundary capture is block $got, expected $height")
    c
  }

  /** A raw extension field by hex key (e.g. "007c" = [0x00, 124], the proposed
    * validation-settings update). Absent ⇒ capture corruption for THIS corpus. */
  private def extensionField(boundaryJson: Json, key: String, height: Int): String = {
    val fields = boundaryJson.hcursor.downField("extension").downField("fields").focus
      .flatMap(_.asArray)
      .getOrElse(sys.error(s"CapturedChain: boundary $height extension has no fields array"))
    fields.collectFirst {
      case kv if kv.asArray.exists(_.headOption.flatMap(_.asString).contains(key)) =>
        kv.asArray.get(1).asString
          .getOrElse(sys.error(s"CapturedChain: boundary $height field $key value is not a string"))
    }.getOrElse(sys.error(s"CapturedChain: boundary $height extension has no $key field"))
  }

  // ── retargeting ────────────────────────────────────────────────────────────

  /** One captured recalculation point: the target height T whose required
    * difficulty is asked + the capture files behind it. */
  private final case class RetargetPoint(targetHeight: Int, anchorsFile: String, targetFile: String)

  /** p1/p2: the two spike-verified testnet recalculation points (contract §8).
    * Anchors are previousHeightsRequiredForRecalculation(T, 128): 9 headers
    * ascending, step 128, last == T−1 — the engine re-checks all of that. */
  private val RetargetPoints = Seq(
    RetargetPoint(393601, "anchors-p1.json", "target-p1.json"),
    RetargetPoint(393473, "anchors-p2.json", "target-p2.json"))

  /** Spike-verified testnet retargeting settings — recorded INPUTS (contract §5):
    * initial_nbits 16842752 decodes to difficulty 1 (testnet initialDifficultyHex
    * "01"); block_interval_ms 45000 = the testnet 45 s blockInterval. */
  private val RetargetSettings = Json.obj(
    "epoch_length"      -> Json.fromInt(128),
    "use_last_epochs"   -> Json.fromInt(8),
    "block_interval_ms" -> Json.fromInt(45000),
    "initial_nbits"     -> Json.fromInt(16842752))

  /** Bless one recalculation point. FAIL LOUD: the engine's nbits must equal the
    * real target header's nBits — the chain is the truth, never the blesser. */
  private def blessRetarget(p: RetargetPoint): Json = {
    val name = s"retargeting-testnet-${p.targetHeight}"
    val anchors = parseFile(s"$RetargetDir/${p.anchorsFile}").asArray
      .getOrElse(sys.error(s"CapturedChain[$name]: ${p.anchorsFile} is not a JSON array"))

    // The captured truth: the real header at T (identity-checked).
    val target = parseFile(s"$RetargetDir/${p.targetFile}")
    val realHeight = reqInt(target.hcursor, "height", name)
    if (realHeight != p.targetHeight)
      sys.error(s"CapturedChain[$name]: ${p.targetFile} is block $realHeight, expected ${p.targetHeight}")
    val realNBits = target.hcursor.get[Long]("nBits")
      .fold(e => sys.error(s"CapturedChain[$name]: ${p.targetFile} nBits: $e"), identity)

    val base = Seq(
      "name"     -> Json.fromString(name),
      "source"   -> Json.fromString(s"testnet:testnet-retarget@${p.targetHeight}"),
      "kind"     -> Json.fromString("retargeting"),
      "settings" -> RetargetSettings,
      "payload"  -> Json.obj(
        "target_height"  -> Json.fromInt(p.targetHeight),
        "anchor_headers" -> Json.arr(anchors: _*)))

    // ── drive the oracle ─────────────────────────────────────────────────────
    val actual = engineActual(Json.obj(base: _*), name)
    val nbits = actual.hcursor.get[Long]("nbits")
      .fold(e => sys.error(s"CapturedChain[$name]: engine nbits: $e"), identity)

    // THE FAIL-LOUD GATE: engine output must equal what the chain actually did.
    if (nbits != realNBits)
      sys.error(s"CapturedChain[$name]: FAIL-LOUD — engine nbits $nbits != the real " +
        s"header ${p.targetHeight}'s nBits $realNBits (${p.targetFile}); " +
        "the chain is right — fix the blesser/capture, never the vector")

    // diagnostic: the decimal difficulty behind the blessed nbits, derived from the
    // engine's own output via the canonical serializer (the JVM normalizes computed
    // difficulty through the same compact-bits cycle — contract §2). Never graded.
    val difficulty = DifficultySerializer.decodeCompactBits(nbits).toString

    Json.obj(base ++ Seq(
      "expected"   -> Json.obj("nbits" -> Json.fromLong(nbits)),
      "diagnostic" -> Json.obj("difficulty" -> Json.fromString(difficulty))): _*)
  }

  // ── voting ─────────────────────────────────────────────────────────────────

  /** Spike-verified testnet voting settings — recorded INPUTS (contract §5/§9).
    * version2_activation_height is optional here (table(123) == 4 ⇒ the forced
    * v1→v2 bump can't fire) but recorded for self-containment. */
  private val VotingSettings = Json.obj(
    "voting_length"              -> Json.fromInt(128),
    "soft_fork_epochs"           -> Json.fromInt(32),
    "activation_epochs"          -> Json.fromInt(32),
    "version2_activation_height" -> Json.fromInt(417792))

  /** Bless the epoch closing at boundary 2560. FAIL LOUD: the engine's table must
    * equal Parameters.parseExtension(2560, <real boundary extension>)'s table, and
    * the activated update must be the findings-pinned empty "0000". */
  private def blessVoting(): Json = {
    val name = "voting-testnet-epoch-2560"
    val boundaryHeight = 2560
    val prev     = parseFile(s"$VotingDir/boundary-2432.json")
    val boundary = parseFile(s"$VotingDir/boundary-2560.json")

    // Capture integrity: the boundary files are the blocks we think; the stream
    // covers exactly the window [T−L, T−1] = [2432, 2559], ascending contiguous
    // (stream[0] IS the previous boundary — its votes seed the tally).
    boundaryHeader(prev, 2432)
    val boundaryVotes = reqStr(boundaryHeader(boundary, boundaryHeight), "votes", name)
    val stream = parseFile(s"$VotingDir/vote-stream.json").asArray
      .getOrElse(sys.error(s"CapturedChain[$name]: vote-stream.json is not a JSON array"))
    val heights = stream.map(v => reqInt(v.hcursor, "height", s"$name vote_stream"))
    if (heights != (2432 until 2560).toVector)
      sys.error(s"CapturedChain[$name]: vote stream window is not [2432, 2559] ascending " +
        s"(got ${heights.headOption.getOrElse(-1)}..${heights.lastOption.getOrElse(-1)}, ${heights.size} entries)")

    // In-force parameters across the closing epoch = the PREVIOUS boundary's table.
    val currentTable = extensionTable(prev, 2432)
    // The standing proposal the boundary block itself carries under [0x00, 124].
    val proposedUpdate = extensionField(boundary, "007c", boundaryHeight)

    val base = Seq(
      "name"     -> Json.fromString(name),
      "source"   -> Json.fromString(s"testnet:testnet-voting-2560@$boundaryHeight"),
      "kind"     -> Json.fromString("voting"),
      "settings" -> VotingSettings,
      "payload"  -> Json.obj(
        "boundary_height"    -> Json.fromInt(boundaryHeight),
        "current_parameters" -> Json.obj("table" -> currentTable),
        "vote_stream"        -> Json.arr(stream: _*),
        "boundary_votes"     -> Json.fromString(boundaryVotes),
        "proposed_update"    -> Json.fromString(proposedUpdate)))

    // ── drive the oracle ─────────────────────────────────────────────────────
    val actual = engineActual(Json.obj(base: _*), name)
    val gotTable = actual.hcursor.downField("parameters").downField("table").focus
      .getOrElse(sys.error(s"CapturedChain[$name]: engine returned no parameters.table"))
    val gotActivated = actual.hcursor.get[String]("activated_update")
      .fold(e => sys.error(s"CapturedChain[$name]: engine activated_update: $e"), identity)

    // THE FAIL-LOUD GATE (twofold): the post-epoch table must equal what the chain
    // recorded in the real boundary-2560 extension, and the activated update must
    // be the pinned empty encoding.
    val realTable = extensionTable(boundary, boundaryHeight)
    if (gotTable != realTable)
      sys.error(s"CapturedChain[$name]: FAIL-LOUD — engine table ${gotTable.noSpaces} != " +
        s"parseExtension($boundaryHeight).table ${realTable.noSpaces}; " +
        "the chain is right — fix the blesser/capture, never the vector")
    if (gotActivated != "0000")
      sys.error(s"CapturedChain[$name]: FAIL-LOUD — engine activated_update " +
        s""""$gotActivated" != the pinned empty update "0000" for this epoch""")

    Json.obj(base ++ Seq(
      "expected" -> Json.obj(
        "parameters"       -> Json.obj("table" -> gotTable),
        "activated_update" -> Json.fromString(gotActivated)),
      "diagnostic" -> Json.obj(
        "epoch_note" -> Json.fromString(
          "identity epoch — no parameter moved; the table equality is the pin"))): _*)
  }

  // ── public API ─────────────────────────────────────────────────────────────

  /** Bless the captured corpus. Returns (vectors/-relative path, envelope) pairs —
    * one retargeting file (p1 + p2) and one voting file. */
  def blessAll(): Seq[(String, Json)] = Seq(
    RetargetingPath -> envelope(RetargetPoints.map(blessRetarget)),
    VotingPath      -> envelope(Seq(blessVoting())))

  private def envelope(entries: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-chain/v1"),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries"    -> Json.arr(entries: _*))

  /** Persist blessed vectors at their COMMITTED paths under vectorsRoot
    * (`../vectors` from the blesser's cwd) — re-blessing regenerates the corpus
    * in place. Fails loud on a path collision (would silently drop a file). */
  def writeVectors(results: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit = {
    val collisions = results.map(_._1).groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("CapturedChain.writeVectors: path collision would silently drop a file — " +
        collisions.mkString(", "))
    results.foreach { case (rel, json) =>
      val path = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
