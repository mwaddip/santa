package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredChainHeaderVotes — the header_votes kind's authored families
// (T3 parity spike proved checkHeaderVotes ≡ real validateVotes on 12-row grid).
//
//   vectors/chain/any/authored/HeaderVotes.field_rules.json (8):
//     rule-discriminating edges; each case isolates exactly one rule or its
//     interaction with the 120-asymmetry between rule 212 (free for count) and
//     rule 213 (NOT exempt from dup check).
//   vectors/chain/any/authored/HeaderVotes.canonical.json (4):
//     real-header shapes; the shapes a well-behaved miner actually produces.
//
// header_votes is version-independent (any/) — the 3-byte vote logic does not
// change across protocol versions; rule 215 (hdrVotesUnknown) is epoch-start-
// gated and deferred (contract §2); no 215 arm here.
//
// The engine reads ONLY payload.votes; settings are present-but-unread (uniform
// with the voting/fork_vote_gate kinds). TWO outcomes: valid:true / valid:false;
// NO errored arm — the byte logic over a 3-byte array is total.
//
// Every expected is ORACLE-EMITTED via ChainEngine.chainEntry; the test FAIL-
// LOUDs if oracle ≠ intended verdict (any disagreement = engine or recipe bug).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredChainHeaderVotes {

  private val BlessedBy = "jvm:ergo-core-6.0.2.1-chain-model"

  val FieldRulesPath = "chain/any/authored/HeaderVotes.field_rules.json"
  val CanonicalPath  = "chain/any/authored/HeaderVotes.canonical.json"

  // Settings are present-but-unread (contract §2 uniformity with the voting kinds).
  private val Settings = Json.obj(
    "voting_length"              -> Json.fromInt(128),
    "soft_fork_epochs"           -> Json.fromInt(32),
    "activation_epochs"          -> Json.fromInt(32),
    "version2_activation_height" -> Json.fromInt(417792))

  private final case class HCase(file: String, name: String,
                                 votes: String, intendedValid: Boolean, note: String)

  private def h(file: String, name: String, votes: String,
                intendedValid: Boolean, note: String): HCase =
    HCase(file, name, votes, intendedValid, note)

  private val Cases: Seq[HCase] = Seq(
    // ── field_rules: rule-discriminating edges ──────────────────────────────
    h(FieldRulesPath, "count-three-nonfork-reject", votes = "010203",
      intendedValid = false,
      note = "rule 212 hdrVotesNumber: votes [1,2,3] — 3 non-120 entries > 2; " +
        "120 is free for the count (not present here) but the 3 ordinary ids alone " +
        "exceed the limit."),
    h(FieldRulesPath, "count-two-nonfork-pass", votes = "010278",
      intendedValid = true,
      note = "rule 212: votes [1,2,120] — only 2 non-120 entries (≤ 2); the " +
        "soft-fork id 120 (0x78) is free for the count, so 2 non-120 passes."),
    h(FieldRulesPath, "count-one-nonfork-pass", votes = "010000",
      intendedValid = true,
      note = "rule 212: votes [1,0,0] — the two 0s (NoParameter) are filtered " +
        "before all checks, leaving [1]; 1 non-120 ≤ 2 — passes."),
    h(FieldRulesPath, "dup-ordinary-reject", votes = "010100",
      intendedValid = false,
      note = "rule 213 hdrVotesDuplicates: votes [1,1,0] — after 0-filtering, " +
        "[1,1]; id 1 appears twice — duplicate rejected."),
    h(FieldRulesPath, "dup-softfork-reject", votes = "787800",
      intendedValid = false,
      note = "rule 213: votes [120,120,0] — the 120-asymmetry: 120 is FREE for " +
        "the 212 count but NOT exempt from the 213 dup check; [120,120] after " +
        "0-filtering — duplicate rejected."),
    h(FieldRulesPath, "softfork-plus-two-distinct-pass", votes = "780102",
      intendedValid = true,
      note = "corollary of the 120-asymmetry: votes [120,1,2] — 120 free for " +
        "the 212 count (2 non-120 ≤ 2), 1 and 2 are distinct (213), no negation " +
        "pair (214); the realistic maximum passes all three."),
    h(FieldRulesPath, "contradictory-reject", votes = "01ff00",
      intendedValid = false,
      note = "rule 214 hdrVotesContradictory: votes [1,255,0] — after 0-filtering " +
        "[1,0xFF]; 0xFF = −1 as signed i8, and −(1) = −1 = 0xFF: id 1 and its " +
        "negation are both present — contradictory rejected."),
    h(FieldRulesPath, "self-negation-0x80-reject", votes = "800000",
      intendedValid = false,
      note = "rule 214: votes [0x80,0,0] — after 0-filtering [0x80 = −128 as " +
        "signed i8]; (−(−128)).toByte = (128).toByte = −128 = 0x80 — id 128 is " +
        "its own negation (i8 overflow), so it self-contradicts and is rejected."),

    // ── canonical: real-header shapes ──────────────────────────────────────
    h(CanonicalPath, "abstain-empty", votes = "000000",
      intendedValid = true,
      note = "votes [0,0,0] — all NoParameter; after 0-filtering the vote array " +
        "is empty; 212 (0 non-120 ≤ 2), 213 (no dups), 214 (no contradictions) " +
        "all trivially hold. A header that casts no vote."),
    h(CanonicalPath, "lone-softfork-vote", votes = "780000",
      intendedValid = true,
      note = "votes [120,0,0] — after 0-filtering [120]; 0 non-120 ids ≤ 2 " +
        "(212); no dups (213); no contradictions (214). A header voting only " +
        "for the pending soft fork."),
    h(CanonicalPath, "two-ordinary-votes", votes = "040300",
      intendedValid = true,
      note = "votes [4,3,0] — after 0-filtering [4,3]; 2 non-120 ≤ 2 (212); " +
        "no dups (213); 4 and 3 are not negations of each other in i8 (214). " +
        "A header voting for two distinct ordinary parameters."),
    h(CanonicalPath, "softfork-plus-two-ordinary", votes = "780403",
      intendedValid = true,
      note = "votes [120,4,3] — the realistic maximum: soft-fork vote (120) free " +
        "for the 212 count + 2 distinct ordinary ids; 2 non-120 ≤ 2 (212); " +
        "120, 4, 3 all distinct (213); no negation pair (214). The full vote."))

  // ── entry assembly ──────────────────────────────────────────────────────────

  private def baseEntry(c: HCase): Seq[(String, Json)] = Seq(
    "name"     -> Json.fromString(c.name),
    "source"   -> Json.fromString(s"santa:header_votes:${c.name}"),
    "kind"     -> Json.fromString("header_votes"),
    "settings" -> Settings,
    "payload"  -> Json.obj("votes" -> Json.fromString(c.votes)))

  private def bless(c: HCase): Json = {
    val base = baseEntry(c)
    val (_, actual) = santa.runner.ChainEngine.chainEntry(Json.obj(base: _*))
    val err = actual.hcursor.get[String]("error").toOption
    if (err.nonEmpty)
      sys.error(s"AuthoredChainHeaderVotes[${c.name}]: engine returned error=${err.get} — " +
        "header_votes has no errored arm; all cases must bless cleanly")
    val valid = actual.hcursor.get[Boolean]("valid")
      .fold(e => sys.error(s"AuthoredChainHeaderVotes[${c.name}]: valid field missing: $e"), identity)
    if (valid != c.intendedValid)
      sys.error(s"AuthoredChainHeaderVotes[${c.name}]: ORACLE DISAGREES — " +
        s"intended ${c.intendedValid}, oracle $valid — spike or recipe bug")
    Json.obj(base ++ Seq(
      "expected"   -> Json.obj("valid" -> Json.fromBoolean(valid)),
      "diagnostic" -> Json.obj("note"  -> Json.fromString(c.note))): _*)
  }

  // ── public API ──────────────────────────────────────────────────────────────

  def blessAll(): Seq[(String, Json)] = Seq(
    FieldRulesPath -> envelope(Cases.filter(_.file == FieldRulesPath).map(bless)),
    CanonicalPath  -> envelope(Cases.filter(_.file == CanonicalPath).map(bless)))

  private def envelope(entries: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-chain/v1"),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries"    -> Json.arr(entries: _*))

  def writeVectors(results: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit = {
    val collisions = results.map(_._1).groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("AuthoredChainHeaderVotes.writeVectors: path collision — " + collisions.mkString(", "))
    results.foreach { case (rel, json) =>
      val path = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
