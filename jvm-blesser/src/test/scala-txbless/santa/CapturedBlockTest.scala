package santa

/** Bless + baseline-lock the captured block-tier seeds (proofs arm).
  *
  * Run under SANTA_TX_BLESSER=1; cwd = jvm-blesser/ (forked test JVM).
  *
  * All seeds read proof-complete `block-<h>-full.json` captures; the engine verifies
  * the ADProofs at bless time (replay parent_digest → header.stateRoot), so every
  * post_digest below is computed-and-checked, not echoed. powhit-return-type-28474
  * is absent until a canonical (JVM-sourced) proof exists — see
  * docs/findings/testnet-powhit-return-type/ADPROOF-FINDING.md.
  *
  * FAIL-LOUD guarantee: if blessAll() itself fails (valid:false, capture gap,
  * file-not-found, parse-error) it throws before any test body runs — the whole
  * suite errors, no misleading green. */
class CapturedBlockTest extends munit.FunSuite {

  /** Run once per suite; lazy so a blessAll failure surfaces as a test error. */
  lazy val blessed: Seq[(String, io.circe.Json)] = CapturedBlock.blessAll()

  /** Per-seed pins: blessed cost (bless-then-pin) + post_digest (the block's own
    * header stateRoot — the proofs arm verified the replayed digest equals it).
    * 2666's 39379 is the triple-anchored keystone (spike + equivalence anchor +
    * tx-tier input-0 bracket); a drift here = stop and investigate. */
  private val Pins: Map[String, (Long, String)] = Map(
    "bigint-downcast-2666" ->
      ((39379L, "40e3b4b002b7abe56c8da96442bcd042c60c031ca8a5abd4cb288f9b97524dfa0d")),
    "deserialize-context-111927" ->
      ((170876L, "fa677bf827e52292ed376f4c0c3e958627e58f29f2349f9a5193b6f3391cd91515")),
    "atleast-degenerate-bound-184137" ->
      ((40020L, "05a3795297ca43c7476330b9b06cd24dc159568b95d6bf55a4234721d39c426216")))

  // ── helpers ───────────────────────────────────────────────────────────────

  private def envelope(slug: String): io.circe.Json =
    blessed.find(_._1 == slug).map(_._2)
      .getOrElse(fail(s"slug '$slug' not found in blessed output"))

  private def entry(slug: String): io.circe.ACursor = {
    val entries = envelope(slug).hcursor.downField("entries").focus
      .flatMap(_.asArray)
      .getOrElse(fail(s"$slug: entries missing or not an array"))
    if (entries.isEmpty) fail(s"$slug: entries empty")
    entries.head.hcursor
  }

  // ── seed-set integrity ────────────────────────────────────────────────────

  test("blessed seed set matches the pin table exactly") {
    assertEquals(blessed.map(_._1).toSet, Pins.keySet,
      "seed list and pin table diverged — add/remove pins together with seeds")
  }

  // ── per-seed assertions ───────────────────────────────────────────────────

  Pins.foreach { case (slug, (pinnedCost, pinnedDigest)) =>

    test(s"$slug: envelope is santa-block/v1 with block: op") {
      assertEquals(envelope(slug).hcursor.get[String]("schema").toOption,
        Some("santa-block/v1"), s"$slug: schema field")
      val op = envelope(slug).hcursor.get[String]("op").toOption.getOrElse("")
      assert(op.startsWith("block:"), s"$slug: op must start with 'block:' got: $op")
    }

    test(s"$slug: valid=true, reason=null") {
      assertEquals(entry(slug).downField("expected").get[Boolean]("valid").toOption,
        Some(true), s"$slug: valid must be true")
      assertEquals(entry(slug).downField("expected").downField("reason").focus.map(_.isNull),
        Some(true), s"$slug: reason must be null")
    }

    test(s"$slug: cost pin") {
      val cost = entry(slug).downField("expected").get[Long]("cost")
        .fold(e => fail(s"$slug: cost field missing: $e"), identity)
      assertEquals(cost, pinnedCost,
        s"$slug: blessed cost drifted from the pin; stop and investigate")
    }

    test(s"$slug: post_digest pin (computed-and-checked vs header stateRoot)") {
      val got = entry(slug).downField("expected").get[String]("post_digest")
        .fold(e => fail(s"$slug: post_digest field missing: $e"), identity)
      assertEquals(got, pinnedDigest, s"$slug: post_digest mismatch")
    }

    test(s"$slug: activated=3, ergoTree=3") {
      assertEquals(entry(slug).downField("version").get[Int]("activated").toOption,
        Some(3), s"$slug: activated must be 3")
      assertEquals(entry(slug).downField("version").get[Int]("ergoTree").toOption,
        Some(3), s"$slug: ergoTree must be 3")
    }

    test(s"$slug: parameters table cross-check (maxBlockCost + blockVersion)") {
      val tableC = entry(slug).downField("parameters").downField("table")
      assertEquals(tableC.get[Int]("4").toOption, Some(1000000),
        s"$slug: table[4] (maxBlockCost) must be 1000000")
      assertEquals(tableC.get[Int]("123").toOption, Some(4),
        s"$slug: table[123] (blockVersion) must be 4")
    }
  }

  // ── summary + vector write + file presence ───────────────────────────────
  // writeVectors and the staging-file assertions live in ONE test so the files
  // are guaranteed to exist before we check for them.

  test("summary + write staging vectors + live-path file check") {
    val sb = new StringBuilder("\n========== CapturedBlock blessed seeds ==========\n")
    blessed.foreach { case (slug, _) =>
      val ec    = entry(slug)
      val cost  = ec.downField("expected").get[Long]("cost").toOption.map(_.toString).getOrElse("ERROR")
      val valid = ec.downField("expected").get[Boolean]("valid").toOption.map(_.toString).getOrElse("ERROR")
      sb.append(s"  $slug: valid=$valid cost=$cost\n")
    }
    sb.append("=================================================\n")
    println(sb.toString)

    val baseDir = java.nio.file.Paths.get("target")
    CapturedBlock.writeVectors(blessed, baseDir)

    val liveDir = baseDir.resolve("block-vectors")
    Pins.keys.foreach { slug =>
      val outFile = liveDir.resolve(s"$slug.json")
      assert(java.nio.file.Files.exists(outFile),
        s"$slug vector file not written to $liveDir")
      val raw = {
        val src = scala.io.Source.fromFile(outFile.toFile)
        try src.mkString finally src.close()
      }
      val parsed = io.circe.parser.parse(raw)
        .fold(e => fail(s"$slug staged file is not valid JSON: $e"), identity)
      assertEquals(parsed.hcursor.get[String]("schema").toOption,
        Some("santa-block/v1"), s"$slug staged file: schema must be santa-block/v1")
    }
  }
}
