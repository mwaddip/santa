package santa

import io.circe.Json
import munit.FunSuite

/** Generator test for AuthoredChainLifecycleEdges (enr's lifecycle-parity asks
  * A/B/C/E). Properties are the source-read predictions, FAIL-LOUD against the
  * oracle output; a mismatch is the build teaching us, never adjusted to pass. */
class AuthoredChainLifecycleEdgesTest extends FunSuite {

  private val blessed = AuthoredChainLifecycleEdges.blessAll()
  private def fileEntries(path: String): Vector[Json] =
    blessed.toMap.apply(path).hcursor.downField("entries").focus.flatMap(_.asArray).get
  private def entry(path: String, name: String): Json =
    fileEntries(path).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(sys.error(s"missing entry $name in $path"))

  private def inputTable(e: Json): Map[String, Int] =
    e.hcursor.downField("payload").downField("current_parameters").downField("table")
      .focus.flatMap(_.asObject).get.toMap.map { case (k, v) => k -> v.as[Int].toOption.get }
  private def expectedTable(e: Json): Map[String, Int] =
    e.hcursor.downField("expected").downField("parameters").downField("table")
      .focus.flatMap(_.asObject).get.toMap.map { case (k, v) => k -> v.as[Int].toOption.get }
  private def activated(e: Json): String =
    e.hcursor.downField("expected").get[String]("activated_update").toOption.get

  import AuthoredChainLifecycleEdges.{LeniencyPath, TallyPath}

  test("two committed files: lifecycle_leniency (4) + tally_order (3)") {
    assertEquals(blessed.map(_._1), Seq(LeniencyPath, TallyPath))
    assertEquals(fileEntries(LeniencyPath).size, 4)
    assertEquals(fileEntries(TallyPath).size, 3)
    blessed.foreach { case (_, env) =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-chain/v1"))
      assertEquals(env.hcursor.get[String]("blessed_by").toOption,
        Some("jvm:ergo-core-6.0.2.1-chain-model"))
    }
  }

  test("PROPERTY [A] non-force leniency: orphan 122 passes through, id-1 still steps, no throw") {
    val e = entry(LeniencyPath, "leniency-122-without-121-nonforce")
    assertEquals(e.hcursor.downField("payload").get[Int]("boundary_height").toOption, Some(6912))
    assert(!inputTable(e).contains("121"))
    assertEquals(expectedTable(e), inputTable(e) + ("1" -> 1275000)) // step; 122 retained, 121 absent
    assert(!expectedTable(e).contains("121"))
    assertEquals(expectedTable(e).get("122"), Some(2560))
    assertEquals(activated(e), "0000")
  }

  test("PROPERTY [B1] orphan 121 without fork-vote is verbatim-inert") {
    val e = entry(LeniencyPath, "inert-121-without-122")
    assertEquals(expectedTable(e), inputTable(e))
    assertEquals(expectedTable(e).get("121"), Some(777))
  }

  test("PROPERTY [B2] orphan 121 + fork-vote: restart OVERWRITES — 122=T, 121=0") {
    val e = entry(LeniencyPath, "overwrite-121-without-122-forkvote")
    assertEquals(expectedTable(e), inputTable(e) + ("121" -> 0) + ("122" -> 2560))
  }

  test("PROPERTY [E] Int wrap: MaxValue collected + 1 closing vote ⇒ NOT approved ⇒ counters removed") {
    val e = entry(LeniencyPath, "wrap-int-votes-collected")
    assertEquals(inputTable(e).get("121"), Some(2147483647))
    assertEquals(expectedTable(e), inputTable(e) - "121" - "122")
  }

  test("PROPERTY [C1] seed-slot order [+1,−1]: last write wins from the SNAPSHOT — id 1 steps DOWN") {
    val e = entry(TallyPath, "tally-order-updown")
    assertEquals(expectedTable(e), inputTable(e) + ("1" -> 1225000))
  }

  test("PROPERTY [C1] seed-slot order [−1,+1]: the twin steps UP") {
    val e = entry(TallyPath, "tally-order-downup")
    assertEquals(expectedTable(e), inputTable(e) + ("1" -> 1275000))
    // the seed header's votes differ from the mid-epoch voters' (the slot order IS the pin)
    val stream = e.hcursor.downField("payload").downField("vote_stream").focus.flatMap(_.asArray).get
    assertEquals(stream.head.hcursor.get[String]("votes").toOption, Some("ff0100"))
    assertEquals(stream(1).hcursor.get[String]("votes").toOption, Some("01ff00"))
  }

  test("PROPERTY [C2] duplicated 120 seed: FIRST entry (10) not the sum (11) — 3686 fails, cleanup fires") {
    val e = entry(TallyPath, "tally-dup-120-first-entry")
    assertEquals(inputTable(e).get("121"), Some(3676))
    val stream = e.hcursor.downField("payload").downField("vote_stream").focus.flatMap(_.asArray).get
    assertEquals(stream.head.hcursor.get[String]("votes").toOption, Some("787800"))
    assertEquals(expectedTable(e), inputTable(e) - "121" - "122")
  }

  test("write step: files land at the committed vectors/chain/v6/authored/ paths") {
    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredChainLifecycleEdges.writeVectors(blessed, vectorsRoot)
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
