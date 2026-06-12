package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredChainStatusUpdates — the statusUpdates section of proposed_update,
// authorable since enr's RuleStatusSerializer port lifted the §F-2 rules-only
// deferral (their ask list, prompts/santa-lifecycle-parity-vector-asks.md;
// spike = StatusUpdatesSpike, all five classes pinned through the voting seam).
//
//   vectors/chain/v6/authored/Voting.status_updates.json (9):
//     accepts (6): single ReplacedRule + the REAL mainnet h=1,628,160 shape
//       (3× ReplacedRule 1007→1017 / 1008→1018 / 1011→1016) round-trip
//       CANONICALLY at activation (activated == proposed byte-exact) · the
//       unknown-statusCode forward-compat skip ACCEPTS at a plain boundary
//       (the lenience pin — see the landmine note below) · trailing bytes
//       after the final entry ACCEPT and the activation re-serialization
//       DROPS them (no full-consumption check + canonicalization in one) ·
//       count-wrap 0xFFFFFFFF on EACH count parses as ZERO entries
//       (getUInt().toInt wraps negative ⇒ empty range) ⇒ activation emits
//       the canonical empty "0000".
//     rejects (3): truncated inside an entry's dataBytes · dataSize claiming
//       more bytes than remain · the bare 1-byte "00" payload (truncated
//       before the status count — getUInt underflow).
//
// THE LANDMINE (spike s2b, REPORTED not authored): an unknown statusCode
// parses to ReplacedRule(0) (RuleStatusSerializer.scala:55-57), and
// re-serializing THAT computes rule-id offset 0 − 1000 = −1000 →
// putUShort REQUIRE FAILS ("Value -1000 is out of unsigned short range").
// At an ACTIVATION boundary the activated_update hex therefore CANNOT EXIST
// — the JVM itself cannot re-serialize what it accepted. The accept pin
// lives at a plain boundary only; the activation arm is unrepresentable and
// flagged to enr/upstream as a liveness quirk (a node activating such an
// update may be unable to serialize its own state context).
//
// Valid hexes are ORACLE-SERIALIZED (ErgoValidationSettingsUpdateSerializer
// on constructed updates); hostile hexes are documented byte-level mangles
// of oracle output. Every expected is engine-emitted.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import org.ergoplatform.settings.{ErgoValidationSettingsUpdate, ErgoValidationSettingsUpdateSerializer}
import scorex.util.encode.Base16
import sigma.validation.ReplacedRule

object AuthoredChainStatusUpdates {

  private val BlessedBy = "jvm:ergo-core-6.0.2.1-chain-model"

  val Path = "chain/v6/authored/Voting.status_updates.json"

  private val L = 128
  private val S = 2560
  private val Activation = S + 8192 // 10752 — 121=3687 alone clears the strict > 3686 line

  private val Settings = Json.obj(
    "voting_length"              -> Json.fromInt(L),
    "soft_fork_epochs"           -> Json.fromInt(32),
    "activation_epochs"          -> Json.fromInt(32),
    "version2_activation_height" -> Json.fromInt(417792))

  /** The real boundary-2560 table — INPUT ONLY (CapturedChainTest pins it). */
  private val BaseV4: Map[String, Int] = Map(
    "1" -> 1250000, "2" -> 360, "3" -> 524288, "4" -> 1000000, "5" -> 100,
    "6" -> 2000, "7" -> 100, "8" -> 100, "9" -> 30, "123" -> 4)

  private val ActivationTable = BaseV4 + ("121" -> 3687) + ("122" -> S)

  private def hexOf(u: ErgoValidationSettingsUpdate): String =
    Base16.encode(ErgoValidationSettingsUpdateSerializer.toBytes(u))

  /** Oracle-serialized valid payloads (never hand-written). */
  private def singleReplaced = hexOf(ErgoValidationSettingsUpdate(Seq(),
    Seq((1011: Short) -> ReplacedRule(1016))))
  private def mainnetShape = hexOf(ErgoValidationSettingsUpdate(Seq(),
    Seq((1007: Short) -> ReplacedRule(1017),
        (1008: Short) -> ReplacedRule(1018),
        (1011: Short) -> ReplacedRule(1016))))
  private def disable111 = hexOf(ErgoValidationSettingsUpdate(Seq(111: Short), Seq()))

  // Hostile/edge hexes: documented mangles. Outer layout: UInt rulesCount |
  // ruleIds | UInt statusCount | per status: UShort(ruleId−1000) | UShort dataSize |
  // Byte statusCode | dataBytes.
  private def unknownStatus = "00" + "01" + "0b" + "02" + "63" + "beef" // code 0x63 unknown, skip 2
  private def trailingBytes = disable111 + "deadbeef"                  // junk after a valid update
  private def rulesCountWrap  = "ffffffff0f" + "00" // VLQ(0xFFFFFFFF) wraps → zero rules
  private def statusCountWrap = "00" + "ffffffff0f" // zero rules, wrapped status count
  private def truncatedData = "00" + "01" + "0b" + "04" + "04" + "beef" // ChangedRule dataSize 4, 2 bytes left
  private def overclaimData = "00" + "01" + "0b" + "0a" + "04"          // ChangedRule dataSize 10, 0 bytes left
  private def bare00 = "00"                                             // truncated before the status count

  private final case class SCase(name: String, source: String, boundary: Int,
                                 table: Map[String, Int], proposedHex: () => String,
                                 expectError: Boolean, note: String)

  private def s(name: String, slug: String, boundary: Int, table: Map[String, Int],
                proposedHex: () => String, expectError: Boolean = false,
                note: String): SCase =
    SCase(name, s"santa:$slug", boundary, table, proposedHex, expectError, note)

  private val Cases: Seq[SCase] = Seq(
    s("status-replaced-roundtrip", "status_updates:replaced-roundtrip",
      Activation, ActivationTable, () => singleReplaced,
      note = "one ReplacedRule (1011 → 1016; sigma-side script rule): parses, activates, " +
        "and the activated_update is the CANONICAL re-serialization — byte-identical to " +
        "the proposed hex for canonical input. Entry format: UShort(ruleId−1000) | " +
        "UShort dataSize | code 3 | UShort(newRuleId−1000); the ReplacedRule parse reads " +
        "its UShort regardless of the declared dataSize (the JVM quirk enr pinned)."),
    s("status-mainnet-shape-roundtrip", "status_updates:mainnet-shape-roundtrip",
      Activation, ActivationTable, () => mainnetShape,
      note = "the REAL mainnet h=1,628,160 payload shape — 3× ReplacedRule 1007→1017 / " +
        "1008→1018 / 1011→1016 — activates verbatim (canonical round-trip). The one " +
        "status-bearing update ever activated on-chain; conformers must keep it flowing " +
        "byte-exact."),
    s("status-unknown-code-skip", "status_updates:unknown-code-skip",
      S, BaseV4, () => unknownStatus,
      note = "unknown statusCode 0x63 with a valid dataSize: the JVM SKIPS dataSize bytes " +
        "and parses the entry as ReplacedRule(0) — the forward-compat soft-fork arm " +
        "(RuleStatusSerializer.scala:55-57). Accepted at this PLAIN boundary (no round ⇒ " +
        "activated 0000, table unchanged). DELIBERATELY NOT AUTHORED at an activation " +
        "boundary: re-serializing ReplacedRule(0) computes offset −1000 and putUShort " +
        "REQUIRE-FAILS — the JVM cannot re-serialize what it accepted (the activated hex " +
        "cannot exist; reported to enr/upstream as a liveness quirk)."),
    s("status-trailing-bytes-canonicalized", "status_updates:trailing-bytes-canonicalized",
      Activation, ActivationTable, () => trailingBytes,
      note = "four junk bytes AFTER a complete valid update (disable rule 111): the parse " +
        "reads exactly the declared counts and stops — NO full-consumption check (JVM " +
        "Reader parity) — and the ACTIVATED hex is the canonical re-serialization WITHOUT " +
        "the junk: proposed ≠ activated, the canonicalization pin. An impl that rejects " +
        "trailing bytes (or echoes them) diverges."),
    s("status-count-wrap-rules", "status_updates:count-wrap-rules",
      Activation, ActivationTable, () => rulesCountWrap,
      note = "rulesToDisable count = VLQ 0xFFFFFFFF: getUInt().toInt WRAPS negative and " +
        "`0 until -1` reads ZERO entries — the payload parses as the EMPTY update (not an " +
        "error, not a 4-billion-entry read; enr's count-wrap pin, implementation-derived " +
        "and test-pinned their side). Activation proceeds with activated_update 0000."),
    s("status-count-wrap-status", "status_updates:count-wrap-status",
      Activation, ActivationTable, () => statusCountWrap,
      note = "the statusUpdates count wrapped the same way (rules count 0): zero status " +
        "entries read, EMPTY update, activation emits 0000 — the wrap holds on BOTH counts."),
    s("status-truncated-databytes", "status_updates:truncated-databytes",
      S, BaseV4, () => truncatedData, expectError = true,
      note = "ChangedRule declaring dataSize 4 with only 2 bytes remaining: getBytes " +
        "underflows (\"Not enough bytes in the buffer\") — the strict parse rejects. " +
        "A lenient/zero-filling impl diverges."),
    s("status-datasize-overclaim", "status_updates:datasize-overclaim",
      S, BaseV4, () => overclaimData, expectError = true,
      note = "dataSize 10 with ZERO data bytes remaining (the over-claim arm): same " +
        "underflow class, distinct trigger — the size field itself lies."),
    s("status-bare-00", "status_updates:bare-00",
      S, BaseV4, () => bare00, expectError = true,
      note = "the bare 1-byte payload \"00\" (zero rules, truncated BEFORE the status " +
        "count): getUInt underflows (BufferUnderflowException). Pairs with enr's own " +
        "test pin; \"0000\" (both counts present and zero) is the canonical EMPTY and " +
        "accepts — one byte decides."))

  // ── entry assembly ──────────────────────────────────────────────────────────

  private def tableJson(t: Map[String, Int]): Json =
    Json.obj(t.toSeq.sortBy(_._1.toInt).map { case (k, v) => k -> Json.fromInt(v) }: _*)

  private def baseEntry(c: SCase): Seq[(String, Json)] = Seq(
    "name"     -> Json.fromString(c.name),
    "source"   -> Json.fromString(c.source),
    "kind"     -> Json.fromString("voting"),
    "settings" -> Settings,
    "payload"  -> Json.obj(
      "boundary_height"    -> Json.fromInt(c.boundary),
      "current_parameters" -> Json.obj("table" -> tableJson(c.table)),
      "vote_stream" -> Json.arr(math.max(1, c.boundary - L).until(c.boundary).map { h =>
        Json.obj("height" -> Json.fromInt(h), "votes" -> Json.fromString("000000"))
      }: _*),
      "boundary_votes"  -> Json.fromString("000000"),
      "proposed_update" -> Json.fromString(c.proposedHex())))

  private def bless(c: SCase): Json = {
    val base = baseEntry(c)
    val (_, actual) = santa.runner.ChainEngine.chainEntry(Json.obj(base: _*))
    val err = actual.hcursor.get[String]("error").toOption
    if (c.expectError) {
      if (!err.contains("errored"))
        sys.error(s"AuthoredChainStatusUpdates[${c.name}]: hostile case must come back errored, " +
          s"got error=${err.getOrElse("null")} — recipe or engine-seam bug")
      val oracleNote = actual.hcursor.get[String]("note").toOption.getOrElse("")
      Json.obj(base ++ Seq(
        "expected"   -> Json.obj("error" -> Json.fromString("errored")),
        "diagnostic" -> Json.obj(
          "note"        -> Json.fromString(c.note),
          "oracle_note" -> Json.fromString(oracleNote))): _*)
    } else {
      if (err.nonEmpty)
        sys.error(s"AuthoredChainStatusUpdates[${c.name}]: engine returned error=${err.get} " +
          s"note=${actual.hcursor.get[String]("note").toOption.getOrElse("<none>")} — " +
          "an accept case must bless cleanly")
      val table = actual.hcursor.downField("parameters").downField("table").focus
        .getOrElse(sys.error(s"AuthoredChainStatusUpdates[${c.name}]: no parameters.table"))
      val activated = actual.hcursor.get[String]("activated_update")
        .fold(e => sys.error(s"AuthoredChainStatusUpdates[${c.name}]: activated_update: $e"), identity)
      Json.obj(base ++ Seq(
        "expected" -> Json.obj(
          "parameters"       -> Json.obj("table" -> table),
          "activated_update" -> Json.fromString(activated)),
        "diagnostic" -> Json.obj("note" -> Json.fromString(c.note))): _*)
    }
  }

  // ── public API ──────────────────────────────────────────────────────────────

  def blessAll(): Seq[(String, Json)] =
    Seq(Path -> Json.obj(
      "schema"     -> Json.fromString("santa-chain/v1"),
      "blessed_by" -> Json.fromString(BlessedBy),
      "entries"    -> Json.arr(Cases.map(bless): _*)))

  def writeVectors(results: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    results.foreach { case (rel, json) =>
      val path = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
