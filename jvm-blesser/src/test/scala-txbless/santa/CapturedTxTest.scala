package santa

/** Bless + baseline-lock the 4 captured tx-tier seeds (Task 2.4 keystone).
  *
  * Run under SANTA_TX_BLESSER=1; cwd = jvm-blesser/ (forked test JVM).
  *
  * Pass 1 (first run): blessAll() succeeds; bigint==14846 anchor fires; the other 3 costs are
  * REPORTED (not asserted) because the baseline map is incomplete. Fill in the observed values
  * and re-run (Pass 2) to fully lock all 4.
  *
  * FAIL-LOUD guarantee: if blessAll() itself fails (valid:false, capture gap, file-not-found)
  * it throws before any test body runs — the whole suite errors, no misleading green. */
class CapturedTxTest extends munit.FunSuite {

  /** Run once per suite; lazy so a blessAll failure surfaces as a test error, not class-init. */
  lazy val blessed: Seq[(String, io.circe.Json)] = CapturedTx.blessAll()

  /** Expected slugs in declaration order. */
  private val Slugs = Seq(
    "bigint-downcast-2666",
    "deserialize-context-111927",
    "atleast-degenerate-bound-184137",
    "powhit-return-type-28474")

  // ── helpers ───────────────────────────────────────────────────────────────────

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

  // ── structural assertions ─────────────────────────────────────────────────────

  test("all 4 slugs present") {
    val got = blessed.map(_._1)
    assertEquals(got, Slugs, "slug list order or content mismatch")
  }

  test("every envelope is santa-transaction/v1") {
    blessed.foreach { case (slug, env) =>
      assertEquals(env.hcursor.get[String]("schema").toOption,
        Some("santa-transaction/v1"), s"$slug: schema")
    }
  }

  test("every entry: valid=true, cost>0, reason=null, activated=3") {
    Slugs.foreach { slug =>
      val ec = entry(slug)
      assertEquals(ec.downField("expected").get[Boolean]("valid").toOption,
        Some(true), s"$slug: valid")
      assert(ec.downField("expected").get[Long]("cost").toOption.exists(_ > 0),
        s"$slug: cost must be > 0")
      assertEquals(ec.downField("expected").downField("reason").focus.map(_.noSpaces),
        Some("null"), s"$slug: reason must be null")
      assertEquals(ec.downField("version").get[Int]("activated").toOption,
        Some(3), s"$slug: activated==3 (v6)")
    }
  }

  // ── keystone anchor: the Phase-0 spike proved 14846 for bigint-downcast-2666 ──

  test("bigint-downcast-2666 cost == 14846 (keystone reproduction)") {
    val cost = entry("bigint-downcast-2666").downField("expected").get[Long]("cost")
      .fold(e => fail(s"cost field missing: $e"), identity)
    assertEquals(cost, 14846L,
      s"keystone cost mismatch — spike proved 14846; got $cost; stop and investigate")
  }

  // ── baseline map: bigint pre-filled; remaining 3 filled after first observed run ─
  // A changed cost means the JVM cost model or box assembly moved — investigate, never
  // blindly re-bless.

  private val costBaseline: Map[String, Long] = Map(
    "bigint-downcast-2666"          -> 14846L,
    "deserialize-context-111927"    -> 15374L,
    "atleast-degenerate-bound-184137"-> 15487L,
    "powhit-return-type-28474"      -> 16656L
  )

  test("baseline: costs match locked values (only asserts slugs present in the map)") {
    costBaseline.foreach { case (slug, expected) =>
      val got = entry(slug).downField("expected").get[Long]("cost")
        .fold(e => fail(s"$slug cost field missing: $e"), identity)
      assertEquals(got, expected, s"$slug cost drifted from baseline")
    }
  }

  // ── summary + vector write ────────────────────────────────────────────────────

  test("summary + write staging vectors") {
    val sb = new StringBuilder("\n========== CapturedTx blessed seeds ==========\n")
    Slugs.foreach { slug =>
      val ec   = entry(slug)
      val cost = ec.downField("expected").get[Long]("cost").toOption.map(_.toString).getOrElse("ERROR")
      val valid= ec.downField("expected").get[Boolean]("valid").toOption.map(_.toString).getOrElse("ERROR")
      sb.append(s"  $slug: valid=$valid cost=$cost\n")
    }
    sb.append("===============================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "tx-vectors")
    CapturedTx.writeVectors(blessed, outDir)
    Slugs.foreach { slug =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$slug.json")),
        s"$slug vector file not written to $outDir")
    }
  }
}
