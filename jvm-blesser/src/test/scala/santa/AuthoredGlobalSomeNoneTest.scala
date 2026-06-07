package santa

/** Guard + smoke test for the authored `Global.some` (106:9) + `Global.none` (106:10)
  * vectors (v6/authored) — 4 closed entries, single op "Global.some_none".
  *
  * some#int        : Global.some[Int](5)           → Option(5)
  * none#int        : Global.none[Int]              → None
  * some#isDefined  : Global.some[Int](5).isDefined → true
  * none#isDefined  : Global.none[Int].isDefined    → false
  *
  * Exact value/cost/tree anchors are locked below after the first observed bless.
  * A change means the JVM cost model or method implementation moved — INVESTIGATE,
  * do not blindly re-bless.
  */
class AuthoredGlobalSomeNoneTest extends munit.FunSuite {

  private lazy val vectors: Map[String, io.circe.Json] = AuthoredGlobalSomeNone.extract()

  private lazy val byName: Map[String, io.circe.Json] =
    vectors(AuthoredGlobalSomeNone.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap

  private val expectedNames = List("some#int", "none#int", "some#isDefined", "none#isDefined")

  test("well-formedness: single op, correct schema/source/blessed_by") {
    assertEquals(vectors.keySet, Set(AuthoredGlobalSomeNone.Op))
    val env = vectors(AuthoredGlobalSomeNone.Op).hcursor
    assertEquals(env.get[String]("schema").toOption,     Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.get[String]("source").toOption,     Some(AuthoredGlobalSomeNone.Source))
  }

  test("well-formedness: 4 entries, expected names in order") {
    val es = vectors(AuthoredGlobalSomeNone.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    assertEquals(es.map(_.hcursor.get[String]("name").toOption.get).toList, expectedNames)
  }

  test("well-formedness: all 4 entries are accepts (non-null value+cost, null error)") {
    byName.foreach { case (name, e) =>
      val ec    = e.hcursor
      val value = ec.downField("expected").downField("value").focus.get
      val cost  = ec.downField("expected").get[Long]("cost").toOption
      val error = ec.downField("expected").downField("error").focus.get
      assert(!value.isNull,  s"$name: value must not be null")
      assert(cost.isDefined, s"$name: cost must be present")
      assert(error.isNull,   s"$name: error must be null")
    }
  }

  test("well-formedness: v6 version pair (activated=3, ergoTree=3)") {
    byName.foreach { case (name, e) =>
      val ec = e.hcursor
      assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(3), s"$name activated")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption,  Some(3), s"$name ergoTree")
    }
  }

  test("well-formedness: Option result kind for some#int/none#int; Boolean for isDefined entries") {
    // some/none root is MethodCall → SOption[SInt] → serialised as {"kind":"Option",...}
    assertEquals(
      byName("some#int").hcursor.downField("expected").downField("value").get[String]("kind").toOption,
      Some("Option"), "some#int: result kind must be Option")
    assertEquals(
      byName("none#int").hcursor.downField("expected").downField("value").get[String]("kind").toOption,
      Some("Option"), "none#int: result kind must be Option")
    // OptionIsDefined root is SBoolean → {"kind":"Boolean",...}
    assertEquals(
      byName("some#isDefined").hcursor.downField("expected").downField("value").get[String]("kind").toOption,
      Some("Boolean"), "some#isDefined: result kind must be Boolean")
    assertEquals(
      byName("none#isDefined").hcursor.downField("expected").downField("value").get[String]("kind").toOption,
      Some("Boolean"), "none#isDefined: result kind must be Boolean")
  }

  test("well-formedness: 4 distinct tree hexes (copy-paste/wrong-tree guard)") {
    val trees = byName.values.map(_.hcursor.get[String]("tree_bytes_hex").toOption.get).toSeq.distinct
    assertEquals(trees.size, 4, s"expected 4 distinct trees, got ${trees.size}: ${trees.mkString(", ")}")
  }

  // ── summary + staging ────────────────────────────────────────────────────────

  test("summary + write staging") {
    val es = vectors(AuthoredGlobalSomeNone.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    val sb = new StringBuilder("\n========== authored Global.some/none vectors ==========\n")
    es.foreach { e =>
      val c    = e.hcursor
      val n    = c.get[String]("name").getOrElse("?")
      val cost = c.downField("expected").get[Long]("cost").toOption.map(_.toString).getOrElse("—")
      val v    = c.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("?")
      val hex  = c.get[String]("tree_bytes_hex").getOrElse("?")
      sb.append(s"  $n: cost=$cost value=$v\n      tree=$hex\n")
    }
    sb.append("=======================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "authored-staging")
    AuthoredGlobalSomeNone.writeVectors(outDir)
    assert(
      java.nio.file.Files.exists(outDir.resolve("Global.some_none.json")),
      s"expected Global.some_none.json in $outDir")
  }

  // ── ANCHOR: exact blessed value/cost/tree locked after first observed bless ──
  // A change here means cost model, method implementation, or tree encoding moved.
  // INVESTIGATE — do not blindly re-bless.

  test("ANCHOR: some#int value = Option wrapping Int 5") {
    val entry = byName("some#int").hcursor
    val kind  = entry.downField("expected").downField("value").get[String]("kind").toOption.get
    val inner = entry.downField("expected").downField("value").downField("value")
    assertEquals(kind, "Option")
    assertEquals(inner.get[String]("kind").toOption, Some("Int"), "some#int inner kind")
    assertEquals(inner.get[Int]("value").toOption, Some(5), "some#int inner value")
  }

  test("ANCHOR: none#int value = Option with null inner (None)") {
    val entry = byName("none#int").hcursor
    val kind  = entry.downField("expected").downField("value").get[String]("kind").toOption.get
    val inner = entry.downField("expected").downField("value").downField("value").focus.get
    assertEquals(kind, "Option")
    assert(inner.isNull, s"none#int inner value must be null (None), got: ${inner.noSpaces}")
  }

  test("ANCHOR: some#isDefined value = Boolean true") {
    val entry = byName("some#isDefined").hcursor
    val kind  = entry.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.downField("expected").downField("value").get[Boolean]("value").toOption.get
    assertEquals(kind, "Boolean")
    assertEquals(value, true, "some#isDefined must be true")
  }

  test("ANCHOR: none#isDefined value = Boolean false") {
    val entry = byName("none#isDefined").hcursor
    val kind  = entry.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.downField("expected").downField("value").get[Boolean]("value").toOption.get
    assertEquals(kind, "Boolean")
    assertEquals(value, false, "none#isDefined must be false")
  }

  // ── cost + tree pin anchors ────────────────────────────────────────────────
  // Cost breakdown (verified vs sigma-state 6.0.3 sources):
  //   some#int  = Global access JitCost(5) + MethodCall dispatch JitCost(4) + someMethod FixedCost JitCost(5) + ConstantPlaceholder JitCost(1) = 15
  //   none#int  = 5 + 4 + 5 = 14 (no segregated constant — the Δ1 IS the ConstantPlaceholder)
  //   isDefined arms add exactly OptionIsDefined FixedCost JitCost(10): 25 / 24
  // The isDefined pairs differ from their some/none base by a single byte (0xe6 opCode prefix);
  // some↔none pairs differ by ~5 bytes (the serialized constant section for IntConstant(5)).
  private val costAnchors: Map[String, Long] = Map(
    "some#int"       -> 15L,
    "none#int"       -> 14L,
    "some#isDefined" -> 25L,
    "none#isDefined" -> 24L
  )
  private val treeAnchors: Map[String, String] = Map(
    "some#int"       -> "1b0b01040adc6a09dd01730004",
    "none#int"       -> "1b0600db6a0add04",
    "some#isDefined" -> "1b0c01040ae6dc6a09dd01730004",
    "none#isDefined" -> "1b0700e6db6a0add04"
  )

  test("ANCHOR: cost pins match oracle-blessed values") {
    costAnchors.foreach { case (name, expected) =>
      val actual = byName(name).hcursor.downField("expected").get[Long]("cost").toOption
      assertEquals(actual, Some(expected), s"$name cost drifted")
    }
  }

  test("ANCHOR: tree hex pins match oracle-blessed values") {
    treeAnchors.foreach { case (name, expected) =>
      val actual = byName(name).hcursor.get[String]("tree_bytes_hex").toOption
      assertEquals(actual, Some(expected), s"$name tree hex drifted")
    }
  }
}
