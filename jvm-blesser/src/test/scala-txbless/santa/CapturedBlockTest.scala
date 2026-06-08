package santa

/** Bless + baseline-lock the captured block-tier seeds (Task 7 keystone).
  *
  * Run under SANTA_TX_BLESSER=1; cwd = jvm-blesser/ (forked test JVM).
  *
  * Block 2666 is today in PROOFLESS quarantine (adProofs: null in the capture).
  * The blesser emits to target/block-vectors-PROOFLESS/ and prints a loud banner.
  * Once the proof file lands, re-bless with the completed capture; the file then
  * moves to target/block-vectors/ and this test is updated to assert the live path.
  *
  * FAIL-LOUD guarantee: if blessAll() itself fails (valid:false, capture gap,
  * file-not-found, parse-error) it throws before any test body runs — the whole
  * suite errors, no misleading green. */
class CapturedBlockTest extends munit.FunSuite {

  /** Run once per suite; lazy so a blessAll failure surfaces as a test error. */
  lazy val blessed: Seq[(String, Boolean, io.circe.Json)] = CapturedBlock.blessAll()

  private val Slug2666 = "bigint-downcast-2666"

  // ── helpers ───────────────────────────────────────────────────────────────

  private def result(slug: String): (Boolean, io.circe.Json) =
    blessed.find(_._1 == slug)
      .map { case (_, q, j) => q -> j }
      .getOrElse(fail(s"slug '$slug' not found in blessed output"))

  private def envelope(slug: String): io.circe.Json = result(slug)._2
  private def quarantined(slug: String): Boolean    = result(slug)._1

  private def entry(slug: String): io.circe.ACursor = {
    val entries = envelope(slug).hcursor.downField("entries").focus
      .flatMap(_.asArray)
      .getOrElse(fail(s"$slug: entries missing or not an array"))
    if (entries.isEmpty) fail(s"$slug: entries empty")
    entries.head.hcursor
  }

  // ── structural assertions ─────────────────────────────────────────────────

  test("bigint-downcast-2666: envelope is santa-block/v1") {
    assertEquals(
      envelope(Slug2666).hcursor.get[String]("schema").toOption,
      Some("santa-block/v1"),
      s"$Slug2666: schema field")
  }

  test("bigint-downcast-2666: op prefix is block:") {
    val op = envelope(Slug2666).hcursor.get[String]("op").toOption.getOrElse("")
    assert(op.startsWith("block:"), s"op must start with 'block:' got: $op")
  }

  // ── keystone cost anchor: triple-anchored 39379 ───────────────────────────

  test("bigint-downcast-2666 cost == 39379 (triple-anchored keystone)") {
    val cost = entry(Slug2666).downField("expected").get[Long]("cost")
      .fold(e => fail(s"cost field missing: $e"), identity)
    assertEquals(cost, 39379L,
      s"keystone cost mismatch — triple-anchored at 39379; got $cost; stop and investigate")
  }

  // ── post_digest anchor ────────────────────────────────────────────────────

  test("bigint-downcast-2666 post_digest matches block stateRoot") {
    val got = entry(Slug2666).downField("expected").get[String]("post_digest")
      .fold(e => fail(s"post_digest field missing: $e"), identity)
    assertEquals(got,
      "40e3b4b002b7abe56c8da96442bcd042c60c031ca8a5abd4cb288f9b97524dfa0d",
      s"post_digest mismatch")
  }

  // ── valid + reason ────────────────────────────────────────────────────────

  test("bigint-downcast-2666: valid=true, reason=null") {
    assertEquals(
      entry(Slug2666).downField("expected").get[Boolean]("valid").toOption,
      Some(true),
      s"$Slug2666: valid must be true")
    assertEquals(
      entry(Slug2666).downField("expected").downField("reason").focus.map(_.isNull),
      Some(true),
      s"$Slug2666: reason must be null")
  }

  // ── version ───────────────────────────────────────────────────────────────

  test("bigint-downcast-2666: activated=3, ergoTree=3") {
    assertEquals(
      entry(Slug2666).downField("version").get[Int]("activated").toOption,
      Some(3),
      s"$Slug2666: activated must be 3")
    assertEquals(
      entry(Slug2666).downField("version").get[Int]("ergoTree").toOption,
      Some(3),
      s"$Slug2666: ergoTree must be 3")
  }

  // ── parameters cross-check ────────────────────────────────────────────────

  test("bigint-downcast-2666: parameters table cross-check (maxBlockCost + blockVersion)") {
    val tableC = entry(Slug2666).downField("parameters").downField("table")
    // table("4") == 1000000 (maxBlockCost)
    assertEquals(
      tableC.get[Int]("4").toOption,
      Some(1000000),
      s"$Slug2666: table[4] (maxBlockCost) must be 1000000")
    // table("123") == 4 (blockVersion)
    assertEquals(
      tableC.get[Int]("123").toOption,
      Some(4),
      s"$Slug2666: table[123] (blockVersion) must be 4")
  }

  // ── PROOFLESS quarantine assertion ────────────────────────────────────────

  test("bigint-downcast-2666: staged to PROOFLESS quarantine dir (adProofs null)") {
    assert(quarantined(Slug2666),
      s"$Slug2666 should be quarantined (adProofs is null in the capture)")
  }

  // ── summary + vector write + file presence ───────────────────────────────
  // writeVectors and the staging-file assertions live in ONE test so the file
  // is guaranteed to exist before we check for it (munit runs tests in order,
  // but only within the same test body can we ensure write-then-read sequencing).

  test("summary + write staging vectors + quarantine file check") {
    val sb = new StringBuilder("\n========== CapturedBlock blessed seeds ==========\n")
    blessed.foreach { case (slug, q, json) =>
      val ec    = entry(slug)
      val cost  = ec.downField("expected").get[Long]("cost").toOption.map(_.toString).getOrElse("ERROR")
      val valid = ec.downField("expected").get[Boolean]("valid").toOption.map(_.toString).getOrElse("ERROR")
      val qFlag = if (q) " [PROOFLESS-QUARANTINE]" else ""
      sb.append(s"  $slug: valid=$valid cost=$cost$qFlag\n")
    }
    sb.append("=================================================\n")
    println(sb.toString)

    val baseDir = java.nio.file.Paths.get("target")
    CapturedBlock.writeVectors(blessed, baseDir)

    // 2666 is quarantined — assert the quarantine dir, not the live dir.
    val proofDir = baseDir.resolve("block-vectors-PROOFLESS")
    val outFile  = proofDir.resolve(s"$Slug2666.json")
    assert(java.nio.file.Files.exists(outFile),
      s"$Slug2666 quarantine vector file not written to $proofDir")

    // Parse the written file and check its schema.
    val raw = {
      val src = scala.io.Source.fromFile(outFile.toFile)
      try src.mkString finally src.close()
    }
    val parsed = io.circe.parser.parse(raw)
      .fold(e => fail(s"$Slug2666 quarantine file is not valid JSON: $e"), identity)
    assertEquals(
      parsed.hcursor.get[String]("schema").toOption,
      Some("santa-block/v1"),
      s"$Slug2666 quarantine file: schema must be santa-block/v1")
  }
}
