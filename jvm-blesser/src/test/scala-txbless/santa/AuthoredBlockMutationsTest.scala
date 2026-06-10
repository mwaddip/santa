package santa

/** Bless + verify the block-tier reject arm (mutation classes over 2666).
  *
  * Run under SANTA_TX_BLESSER=1; cwd = jvm-blesser/ (forked test JVM).
  *
  * blessMutation itself fail-louds on accept / wrong-reason / runner-path error,
  * so a green suite means: every class rejects, for its intended reason, through
  * the exact runner path. The tests here assert the EMITTED vector shape (what
  * the validate guard will hold committed files to). */
class AuthoredBlockMutationsTest extends munit.FunSuite {

  /** Run once per suite; lazy so a blessAll failure surfaces as a test error. */
  lazy val blessed: Seq[(String, io.circe.Json)] = AuthoredBlockMutations.blessAll()

  private val Classes = Seq(
    "params-shrink-maxBlockCost", "stateroot-flip", "adproof-tamper",
    "txs-reorder", "pow-solution-flip", "version-gate")

  // ── helpers ───────────────────────────────────────────────────────────────

  private def envelope(name: String): io.circe.Json =
    blessed.find(_._1 == name).map(_._2)
      .getOrElse(fail(s"class '$name' not found in blessed output"))

  private def entry(name: String): io.circe.ACursor = {
    val entries = envelope(name).hcursor.downField("entries").focus
      .flatMap(_.asArray)
      .getOrElse(fail(s"$name: entries missing or not an array"))
    if (entries.isEmpty) fail(s"$name: entries empty")
    entries.head.hcursor
  }

  // ── class-set integrity ───────────────────────────────────────────────────

  test("blessed class set matches spec §7 exactly") {
    assertEquals(blessed.map(_._1), Classes,
      "mutation classes drifted from the spec §7 ranking")
  }

  // ── per-class vector shape ────────────────────────────────────────────────

  Classes.foreach { name =>

    test(s"$name: envelope is santa-block/v1 with block:mutation: op") {
      assertEquals(envelope(name).hcursor.get[String]("schema").toOption,
        Some("santa-block/v1"), s"$name: schema field")
      assertEquals(envelope(name).hcursor.get[String]("op").toOption,
        Some(s"block:mutation:$name"), s"$name: op field")
    }

    test(s"$name: reject shape (valid=false, post_digest=null, cost=null, reason set)") {
      val ex = entry(name).downField("expected")
      assertEquals(ex.get[Boolean]("valid").toOption, Some(false), s"$name: valid")
      assertEquals(ex.downField("post_digest").focus.map(_.isNull), Some(true),
        s"$name: post_digest must be null on the reject arm")
      assertEquals(ex.downField("cost").focus.map(_.isNull), Some(true),
        s"$name: cost must be null on the reject arm")
      val reason = ex.get[String]("reason").getOrElse(fail(s"$name: reason missing"))
      assert(reason.nonEmpty, s"$name: reason must be a non-empty string")
    }

    test(s"$name: authored provenance source (santa:mutation:…:over:donor)") {
      val source = entry(name).get[String]("source").getOrElse(fail(s"$name: source missing"))
      assertEquals(source, s"santa:mutation:$name:over:bigint-downcast-2666")
    }

    test(s"$name: version carried from donor (activated=3, ergoTree=3)") {
      assertEquals(entry(name).downField("version").get[Int]("activated").toOption,
        Some(3), s"$name: activated must be 3")
      assertEquals(entry(name).downField("version").get[Int]("ergoTree").toOption,
        Some(3), s"$name: ergoTree must be 3")
    }
  }

  // ── summary + vector write + file presence ───────────────────────────────

  test("summary + write staging vectors + file check") {
    val sb = new StringBuilder("\n========== AuthoredBlockMutations blessed classes ==========\n")
    blessed.foreach { case (name, _) =>
      val reason = entry(name).downField("expected").get[String]("reason")
        .toOption.getOrElse("ERROR")
      sb.append(s"  $name\n    reason: ${reason.take(110)}\n")
    }
    sb.append("=============================================================\n")
    println(sb.toString)

    val baseDir = java.nio.file.Paths.get("target")
    AuthoredBlockMutations.writeVectors(blessed, baseDir)

    val outDir = baseDir.resolve("block-mutations")
    Classes.foreach { name =>
      val outFile = outDir.resolve(s"$name.json")
      assert(java.nio.file.Files.exists(outFile), s"$name vector file not written to $outDir")
      val raw = {
        val src = scala.io.Source.fromFile(outFile.toFile)
        try src.mkString finally src.close()
      }
      val parsed = io.circe.parser.parse(raw)
        .fold(e => fail(s"$name staged file is not valid JSON: $e"), identity)
      assertEquals(parsed.hcursor.get[String]("schema").toOption,
        Some("santa-block/v1"), s"$name staged file: schema must be santa-block/v1")
    }
  }
}
