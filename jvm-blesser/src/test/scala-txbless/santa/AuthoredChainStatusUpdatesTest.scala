package santa

import io.circe.Json
import munit.FunSuite

/** Generator test for AuthoredChainStatusUpdates (the statusUpdates classes enr's
  * port unlocked). Properties = the StatusUpdatesSpike pins re-expressed against
  * the blessed JSON; FAIL-LOUD, never adjusted to pass. */
class AuthoredChainStatusUpdatesTest extends FunSuite {

  private val blessed = AuthoredChainStatusUpdates.blessAll()
  private def entries: Vector[Json] =
    blessed.head._2.hcursor.downField("entries").focus.flatMap(_.asArray).get
  private def entry(name: String): Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(sys.error(s"missing entry $name"))

  private def proposed(e: Json): String =
    e.hcursor.downField("payload").get[String]("proposed_update").toOption.get
  private def activated(e: Json): String =
    e.hcursor.downField("expected").get[String]("activated_update").toOption.get
  private def expectedTable(e: Json): Map[String, Int] =
    e.hcursor.downField("expected").downField("parameters").downField("table")
      .focus.flatMap(_.asObject).get.toMap.map { case (k, v) => k -> v.as[Int].toOption.get }
  private def inputTable(e: Json): Map[String, Int] =
    e.hcursor.downField("payload").downField("current_parameters").downField("table")
      .focus.flatMap(_.asObject).get.toMap.map { case (k, v) => k -> v.as[Int].toOption.get }

  test("one committed file, 9 entries (6 accept + 3 reject), canonical envelope") {
    assertEquals(blessed.map(_._1), Seq(AuthoredChainStatusUpdates.Path))
    assertEquals(entries.size, 9)
    val env = blessed.head._2
    assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-chain/v1"))
    assertEquals(env.hcursor.get[String]("blessed_by").toOption,
      Some("jvm:ergo-core-6.0.2.1-chain-model"))
    assertEquals(entries.count(_.hcursor.downField("expected")
      .get[String]("error").toOption.contains("errored")), 3)
  }

  test("PROPERTY canonical round-trips: activated == proposed for the two valid status payloads") {
    for (n <- Seq("status-replaced-roundtrip", "status-mainnet-shape-roundtrip")) {
      val e = entry(n)
      assertEquals(activated(e), proposed(e), n)
      assertEquals(expectedTable(e).get("123"), Some(5), n) // activation bumped
    }
    // the mainnet shape carries THREE status entries (count byte 03 after the rules "00")
    assert(proposed(entry("status-mainnet-shape-roundtrip")).startsWith("0003"))
  }

  test("PROPERTY unknown-code skip: plain boundary accepts, table unchanged, 0000") {
    val e = entry("status-unknown-code-skip")
    assertEquals(expectedTable(e), inputTable(e))
    assertEquals(activated(e), "0000")
  }

  test("PROPERTY trailing bytes: parse accepts, activation DROPS the junk (canonicalization)") {
    val e = entry("status-trailing-bytes-canonicalized")
    assert(proposed(e).endsWith("deadbeef"))
    assertEquals(activated(e), proposed(e).stripSuffix("deadbeef"))
    assert(activated(e) != proposed(e))
  }

  test("PROPERTY count wraps: both counts parse EMPTY — activation emits canonical 0000") {
    for (n <- Seq("status-count-wrap-rules", "status-count-wrap-status")) {
      val e = entry(n)
      assertEquals(activated(e), "0000", n)
      assertEquals(expectedTable(e).get("123"), Some(5), n) // activation still fires
      assertEquals(expectedTable(e).get("1"), inputTable(e).get("1"), n) // nothing disabled
    }
  }

  test("PROPERTY rejects: the three hostile payloads are errored with underflow notes") {
    val expectNotes = Map(
      "status-truncated-databytes" -> "Not enough bytes",
      "status-datasize-overclaim"  -> "Not enough bytes",
      "status-bare-00"             -> "BufferUnderflow")
    expectNotes.foreach { case (n, token) =>
      val e = entry(n)
      assertEquals(e.hcursor.downField("expected").focus.flatMap(_.asObject).get.toMap.keySet,
        Set("error"), n)
      assert(e.hcursor.downField("diagnostic").get[String]("oracle_note").toOption
        .exists(_.contains(token)), s"$n oracle_note should carry $token")
    }
  }

  test("write step: the file lands at the committed path") {
    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredChainStatusUpdates.writeVectors(blessed, vectorsRoot)
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
