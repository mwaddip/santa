package santa

import io.circe.Json
import munit.FunSuite

/** Generator test for AuthoredChainHeaderVotes. Properties re-express the T3
  * parity grid (checkHeaderVotes ≡ real validateVotes) against the blessed JSON;
  * FAIL-LOUD, never adjusted to pass. The bless() call itself also fails loud
  * if oracle ≠ intendedValid — double protection. */
class AuthoredChainHeaderVotesTest extends FunSuite {

  private val blessed = AuthoredChainHeaderVotes.blessAll()
  private def fileEntries(path: String): Vector[Json] =
    blessed.toMap.apply(path).hcursor.downField("entries").focus.flatMap(_.asArray).get
  private def entry(path: String, name: String): Json =
    fileEntries(path).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(sys.error(s"missing entry $name in $path"))
  private def expectedValid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.get

  import AuthoredChainHeaderVotes.{FieldRulesPath, CanonicalPath}

  test("two committed files: field_rules (8) + canonical (4); envelopes canonical") {
    assertEquals(blessed.map(_._1), Seq(FieldRulesPath, CanonicalPath))
    assertEquals(fileEntries(FieldRulesPath).size, 8)
    assertEquals(fileEntries(CanonicalPath).size, 4)
    blessed.foreach { case (_, env) =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-chain/v1"))
      assertEquals(env.hcursor.get[String]("blessed_by").toOption,
        Some("jvm:ergo-core-6.0.2.1-chain-model"))
    }
    blessed.flatMap(p => fileEntries(p._1)).foreach { e =>
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("header_votes"))
      assertEquals(e.hcursor.downField("settings")
        .get[Int]("version2_activation_height").toOption, Some(417792))
      // payload carries votes; no other payload fields
      assert(e.hcursor.downField("payload").get[String]("votes").isRight,
        "payload.votes must be present")
      // expected is two-outcome only: {valid: bool} — no error arm
      assertEquals(e.hcursor.downField("expected").downField("valid").focus.map(_.isBoolean),
        Some(true))
      assertEquals(e.hcursor.downField("expected").keys.map(_.toSet), Some(Set("valid")))
      // every entry's source is namespaced to this kind
      assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:header_votes:")),
        "each entry source must be santa:header_votes:<name>")
    }
  }

  test("PROPERTY field_rules: count rules — 3-nonfork rejects, 2-nonfork passes, 1-nonfork passes") {
    assertEquals(expectedValid(entry(FieldRulesPath, "count-three-nonfork-reject")), false)
    assertEquals(expectedValid(entry(FieldRulesPath, "count-two-nonfork-pass")), true)
    assertEquals(expectedValid(entry(FieldRulesPath, "count-one-nonfork-pass")), true)
  }

  test("PROPERTY field_rules: dup rules — ordinary dup rejects, softfork dup rejects (120-asymmetry)") {
    assertEquals(expectedValid(entry(FieldRulesPath, "dup-ordinary-reject")), false)
    assertEquals(expectedValid(entry(FieldRulesPath, "dup-softfork-reject")), false)
  }

  test("PROPERTY field_rules: 120-corollary — single softfork + 2 distinct non-fork passes all three") {
    assertEquals(expectedValid(entry(FieldRulesPath, "softfork-plus-two-distinct-pass")), true)
  }

  test("PROPERTY field_rules: contradiction rules — negation pair rejects, 0x80 self-negation rejects") {
    assertEquals(expectedValid(entry(FieldRulesPath, "contradictory-reject")), false)
    assertEquals(expectedValid(entry(FieldRulesPath, "self-negation-0x80-reject")), false)
  }

  test("PROPERTY 120-asymmetry: free for 212 count but NOT exempt from 213 dup check") {
    // [1,2,120] passes 212 (2 non-120 ≤ 2) — confirming 120 is free
    assertEquals(expectedValid(entry(FieldRulesPath, "count-two-nonfork-pass")), true)
    // [120,120,0] fails 213 (dup 120) — confirming 120 is NOT exempt from dup check
    assertEquals(expectedValid(entry(FieldRulesPath, "dup-softfork-reject")), false)
    // the votes fields confirm the hex round-trip
    assertEquals(entry(FieldRulesPath, "count-two-nonfork-pass")
      .hcursor.downField("payload").get[String]("votes").toOption, Some("010278"))
    assertEquals(entry(FieldRulesPath, "dup-softfork-reject")
      .hcursor.downField("payload").get[String]("votes").toOption, Some("787800"))
  }

  test("PROPERTY canonical: all four real-header shapes pass") {
    assertEquals(expectedValid(entry(CanonicalPath, "abstain-empty")), true)
    assertEquals(expectedValid(entry(CanonicalPath, "lone-softfork-vote")), true)
    assertEquals(expectedValid(entry(CanonicalPath, "two-ordinary-votes")), true)
    assertEquals(expectedValid(entry(CanonicalPath, "softfork-plus-two-ordinary")), true)
  }

  test("PROPERTY canonical: votes hex round-trip — entries encode the expected byte sequences") {
    assertEquals(entry(CanonicalPath, "abstain-empty")
      .hcursor.downField("payload").get[String]("votes").toOption, Some("000000"))
    assertEquals(entry(CanonicalPath, "lone-softfork-vote")
      .hcursor.downField("payload").get[String]("votes").toOption, Some("780000"))
    assertEquals(entry(CanonicalPath, "two-ordinary-votes")
      .hcursor.downField("payload").get[String]("votes").toOption, Some("040300"))
    assertEquals(entry(CanonicalPath, "softfork-plus-two-ordinary")
      .hcursor.downField("payload").get[String]("votes").toOption, Some("780403"))
  }

  test("write step: files land at the committed paths under vectors/chain/any/authored/") {
    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredChainHeaderVotes.writeVectors(blessed, vectorsRoot)
    blessed.foreach { case (rel, json) =>
      assert(rel.startsWith("chain/any/authored/"), s"path $rel not under any/authored/")
      val f = vectorsRoot.resolve(rel)
      assert(java.nio.file.Files.exists(f), s"missing $f")
      val onDisk = io.circe.parser.parse(
        new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8))
        .fold(err => sys.error(s"$f: $err"), identity)
      assertEquals(onDisk, json)
    }
  }
}
