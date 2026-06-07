package santa

import java.nio.file.Files

/** Guard + smoke tests for the Box signed-view u64 adjudication vectors.
  * Pins the JVM's signed-Long surface for box value / R0 / token amounts in [2^63, 2^64).
  * Exact cost anchors and tree hex are locked once observed from the first bless run —
  * a drift here means the JVM cost model or a tree shape moved: INVESTIGATE, not blindly accept. */
class AuthoredBoxSignedViewTest extends munit.FunSuite {

  private lazy val vectors = AuthoredBoxSignedView.extract()

  // ── count + well-formedness ────────────────────────────────────────────────

  test("9 entries (3 surfaces × 3 value ranges), all bless Right (non-null value+cost, null error)") {
    val env     = vectors(AuthoredBoxSignedView.Op)
    val entries = env.hcursor.downField("entries").values.get.toVector
    assertEquals(entries.size, 9)
    entries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse("?")
      val c = e.hcursor.downField("expected")
      assert(!c.downField("value").focus.get.isNull,  s"unblessed value: $name")
      assert(!c.downField("cost").focus.get.isNull,   s"unblessed cost:  $name")
      assertEquals(c.downField("error").focus.map(_.noSpaces), Some("null"), s"unexpected error: $name")
    }
  }

  // ── signed-view ANCHOR test ────────────────────────────────────────────────
  // A re-bless that changes any of these is a JVM surface or cost-model change to
  // INVESTIGATE, not blindly accept.

  test("signed-view anchors: u64 carriers in [2^63,2^64) surface as negative signed Longs") {
    val env     = vectors(AuthoredBoxSignedView.Op)
    val entries = env.hcursor.downField("entries").values.get.toVector
    def byName(n: String) = entries.find(_.hcursor.get[String]("name").toOption.contains(n))
      .getOrElse(fail(s"entry '$n' not found"))
    def longVal(n: String): String = byName(n).hcursor
      .downField("expected").downField("value").get[String]("value").toOption
      .getOrElse(fail(s"'$n' has no Long value string"))
    def kind(n: String): String = byName(n).hcursor
      .downField("expected").downField("value").get[String]("kind").toOption
      .getOrElse(fail(s"'$n' has no kind"))

    // All nine entries must carry Long values.
    Seq(
      "b.value#nominal", "b.value#2^63",  "b.value#u64-max",
      "b.R0#nominal",    "b.R0#2^63",     "b.R0#u64-max",
      "b.tokens(0)._2#nominal", "b.tokens(0)._2#2^63", "b.tokens(0)._2#u64-max"
    ).foreach(n => assertEquals(kind(n), "Long", s"$n: expected Long kind"))

    // Nominal control values — must be the expected positive numbers.
    assertEquals(longVal("b.value#nominal"),        "1000000", "value#nominal")
    assertEquals(longVal("b.tokens(0)._2#nominal"), "42",      "tokens#nominal")

    // The signed-view assertion: u64 carriers in [2^63,2^64) become negative signed Longs.
    assertEquals(longVal("b.value#2^63"),           "-9223372036854775808", "value#2^63")
    assertEquals(longVal("b.value#u64-max"),        "-1",                  "value#u64-max")
    assertEquals(longVal("b.R0#2^63"),              "-9223372036854775808", "R0#2^63")
    assertEquals(longVal("b.R0#u64-max"),           "-1",                  "R0#u64-max")
    assertEquals(longVal("b.tokens(0)._2#2^63"),    "-9223372036854775808", "tokens#2^63")
    assertEquals(longVal("b.tokens(0)._2#u64-max"), "-1",                  "tokens#u64-max")
  }

  test("3 distinct trees across the family (value / R0 / tokens surfaces)") {
    val env     = vectors(AuthoredBoxSignedView.Op)
    val entries = env.hcursor.downField("entries").values.get.toVector
    val trees   = entries.flatMap(_.hcursor.get[String]("tree_bytes_hex").toOption).distinct
    assertEquals(trees.size, 3,
      s"expected 3 distinct trees (value/R0/tokens), got ${trees.size}: $trees")
  }

  // ── cost anchors ──────────────────────────────────────────────────────────
  // Costs recorded from the first bless run (v5/activated=2):
  //   value-tree  = 33  (GetVar + OptionGet + ExtractAmount)
  //   r0-tree     = 90  (GetVar + OptionGet + ExtractRegisterAs + OptionGet)
  //   tokens-tree = 85  (GetVar + OptionGet + tokens MethodCall + ByIndex + SelectField)
  // A drift here means the JVM cost model shifted: INVESTIGATE, not blindly accept.
  private val expectedCosts: Seq[(String, Long)] = Seq(
    "b.value#nominal"          -> 33L,
    "b.value#2^63"             -> 33L,
    "b.value#u64-max"          -> 33L,
    "b.R0#nominal"             -> 90L,
    "b.R0#2^63"                -> 90L,
    "b.R0#u64-max"             -> 90L,
    "b.tokens(0)._2#nominal"   -> 85L,
    "b.tokens(0)._2#2^63"      -> 85L,
    "b.tokens(0)._2#u64-max"   -> 85L
  )

  test("cost anchors: each entry's cost matches the recorded baseline") {
    val env     = vectors(AuthoredBoxSignedView.Op)
    val entries = env.hcursor.downField("entries").values.get.toVector
    expectedCosts.foreach { case (name, expected) =>
      val e = entries.find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"no entry named '$name'"))
      assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(expected),
        s"$name cost drifted")
    }
  }

  // ── staging ───────────────────────────────────────────────────────────────

  test("staging: writes Box.signed_view_u64.json") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredBoxSignedView.writeVectors(out)
    assert(Files.exists(out.resolve("Box.signed_view_u64.json")))
  }
}
