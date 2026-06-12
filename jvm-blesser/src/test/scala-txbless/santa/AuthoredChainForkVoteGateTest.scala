package santa

import io.circe.Json
import munit.FunSuite

/** Generator test for AuthoredChainForkVoteGate. Properties = the spike grid
  * re-expressed against the blessed JSON; FAIL-LOUD, never adjusted to pass. */
class AuthoredChainForkVoteGateTest extends FunSuite {

  private val blessed = AuthoredChainForkVoteGate.blessAll()
  private def fileEntries(path: String): Vector[Json] =
    blessed.toMap.apply(path).hcursor.downField("entries").focus.flatMap(_.asArray).get
  private def entry(path: String, name: String): Json =
    fileEntries(path).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(sys.error(s"missing entry $name in $path"))
  private def expectedValid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.get

  import AuthoredChainForkVoteGate.{WindowPath, PrecondPath}

  test("two committed files: window_edges (8) + preconditions (4); envelopes canonical") {
    assertEquals(blessed.map(_._1), Seq(WindowPath, PrecondPath))
    assertEquals(fileEntries(WindowPath).size, 8)
    assertEquals(fileEntries(PrecondPath).size, 4)
    blessed.foreach { case (_, env) =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-chain/v1"))
      assertEquals(env.hcursor.get[String]("blessed_by").toOption,
        Some("jvm:ergo-core-6.0.2.1-chain-model"))
    }
    blessed.flatMap(p => fileEntries(p._1)).foreach { e =>
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("fork_vote_gate"))
      assertEquals(e.hcursor.downField("settings")
        .get[Int]("version2_activation_height").toOption, Some(417792))
    }
  }

  test("PROPERTY window grid, not-approved arm (3686): pass / prohibited / prohibited / pass") {
    assertEquals(expectedValid(entry(WindowPath, "gate-during-voting-pass")), true)
    assertEquals(expectedValid(entry(WindowPath, "gate-finishing-prohibited-notapproved")), false)
    assertEquals(expectedValid(entry(WindowPath, "gate-lastepoch-prohibited-notapproved")), false)
    assertEquals(expectedValid(entry(WindowPath, "gate-window-end-pass-notapproved")), true)
  }

  test("PROPERTY window grid, approved arm (3687): prohibited × 3, pass at afterActivation") {
    assertEquals(expectedValid(entry(WindowPath, "gate-finishing-prohibited-approved")), false)
    assertEquals(expectedValid(entry(WindowPath, "gate-flip-prohibited-approved")), false)
    assertEquals(expectedValid(entry(WindowPath, "gate-lastwait-prohibited-approved")), false)
    assertEquals(expectedValid(entry(WindowPath, "gate-after-activation-pass-approved")), true)
  }

  test("PROPERTY the operand flip: same height 6784, collected 3686 pass vs 3687 prohibited") {
    val pass = entry(WindowPath, "gate-window-end-pass-notapproved")
    val proh = entry(WindowPath, "gate-flip-prohibited-approved")
    assertEquals(pass.hcursor.downField("payload").get[Int]("height").toOption,
      proh.hcursor.downField("payload").get[Int]("height").toOption)
    assertEquals(expectedValid(pass), true)
    assertEquals(expectedValid(proh), false)
  }

  test("PROPERTY preconditions: no-round + non-120 + read-order all pass") {
    assertEquals(expectedValid(entry(PrecondPath, "gate-no-round-pass")), true)
    assertEquals(expectedValid(entry(PrecondPath, "gate-non-120-pass")), true)
    assertEquals(expectedValid(entry(PrecondPath, "gate-precondition-precedes-table")), true)
  }

  test("PROPERTY the eager-.get reject: errored, oracle_note carries None.get") {
    val e = entry(PrecondPath, "gate-hostile-122-without-121")
    assertEquals(e.hcursor.downField("expected").focus.flatMap(_.asObject).get.toMap.keySet,
      Set("error"))
    assertEquals(e.hcursor.downField("expected").get[String]("error").toOption, Some("errored"))
    assert(e.hcursor.downField("diagnostic").get[String]("oracle_note").toOption
      .exists(_.contains("None.get")))
  }

  test("write step: files land at the committed paths") {
    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredChainForkVoteGate.writeVectors(blessed, vectorsRoot)
    blessed.foreach { case (rel, json) =>
      val f = vectorsRoot.resolve(rel)
      assert(java.nio.file.Files.exists(f), s"missing $f")
      val onDisk = io.circe.parser.parse(
        new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8))
        .fold(err => sys.error(s"$f: $err"), identity)
      assertEquals(onDisk, json)
    }
  }
}
