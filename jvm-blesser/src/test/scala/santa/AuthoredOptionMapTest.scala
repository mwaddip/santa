package santa

/** Guard + smoke test for the authored `Option.map` (36:7) vectors (v5/authored).
  *
  * map#some              : input Int 5  (var 1 bound)  → Option(Int 6)
  * map#identity-shape    : input Int 41 (var 1 bound)  → Option(Int 42)
  * map#none-via-absent-var: input Int 5 (var 1 bound), tree reads var 99 (absent) → None (cost 39)
  *
  * Exact value/cost/tree anchors are locked below after the first observed bless.
  * A change means the JVM cost model or method implementation moved — INVESTIGATE,
  * do not blindly re-bless.
  */
class AuthoredOptionMapTest extends munit.FunSuite {

  private lazy val vectors: Map[String, io.circe.Json] = AuthoredOptionMap.extract()

  private lazy val byName: Map[String, io.circe.Json] =
    vectors(AuthoredOptionMap.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap

  private val expectedNames =
    List("map#some", "map#identity-shape", "map#none-via-absent-var")

  test("well-formedness: single op, correct schema/source/blessed_by") {
    assertEquals(vectors.keySet, Set(AuthoredOptionMap.Op))
    val env = vectors(AuthoredOptionMap.Op).hcursor
    assertEquals(env.get[String]("schema").toOption,     Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.get[String]("source").toOption,     Some(AuthoredOptionMap.Source))
  }

  test("well-formedness: 3 entries, expected names in order") {
    val es = vectors(AuthoredOptionMap.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    assertEquals(
      es.map(_.hcursor.get[String]("name").toOption.get).toList,
      expectedNames)
  }

  test("well-formedness: map#some is an accept") {
    val e  = byName("map#some").hcursor
    val v  = e.downField("expected").downField("value").focus.get
    val c  = e.downField("expected").get[Long]("cost").toOption
    val er = e.downField("expected").downField("error").focus.get
    assert(!v.isNull,  "map#some: value must not be null")
    assert(c.isDefined, "map#some: cost must be present")
    assert(er.isNull,  "map#some: error must be null")
  }

  test("well-formedness: map#identity-shape is an accept") {
    val e  = byName("map#identity-shape").hcursor
    val v  = e.downField("expected").downField("value").focus.get
    val c  = e.downField("expected").get[Long]("cost").toOption
    val er = e.downField("expected").downField("error").focus.get
    assert(!v.isNull,  "map#identity-shape: value must not be null")
    assert(c.isDefined, "map#identity-shape: cost must be present")
    assert(er.isNull,  "map#identity-shape: error must be null")
  }

  test("well-formedness: map#none-via-absent-var is an accept with None result") {
    // Oracle returned None (Option{null}) for the absent-context-var arm — not an error.
    val e    = byName("map#none-via-absent-var").hcursor
    val kind = e.downField("expected").downField("value").get[String]("kind").toOption
    val inner = e.downField("expected").downField("value").downField("value").focus.get
    val er   = e.downField("expected").downField("error").focus.get
    assertEquals(kind, Some("Option"), "map#none-via-absent-var: result kind must be Option")
    assert(inner.isNull, s"map#none-via-absent-var: inner value must be null (None), got: ${inner.noSpaces}")
    assert(er.isNull,    "map#none-via-absent-var: error must be null (accepted)")
  }

  test("well-formedness: v5 version pair (activated=2, ergoTree=2)") {
    // All 3 entries should carry the v5 version pair.
    byName.foreach { case (name, e) =>
      val ec = e.hcursor
      assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(2), s"$name activated")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption,  Some(2), s"$name ergoTree")
    }
  }

  test("well-formedness: Option result kind for map#some / map#identity-shape") {
    for (name <- Seq("map#some", "map#identity-shape")) {
      val kind = byName(name).hcursor
        .downField("expected").downField("value").get[String]("kind").toOption
      assertEquals(kind, Some("Option"), s"$name: result kind must be Option")
    }
  }

  test("well-formedness: 2 distinct tree hexes (var-1 tree == some/identity-shape; var-99 tree != var-1)") {
    val trees = byName.values.map(_.hcursor.get[String]("tree_bytes_hex").toOption.get).toSeq.distinct
    assertEquals(trees.size, 2, s"expected 2 distinct trees (var-1 and var-99), got ${trees.size}: ${trees.mkString(", ")}")
    // Additionally, map#some and map#identity-shape must share the same tree.
    assertEquals(
      byName("map#some").hcursor.get[String]("tree_bytes_hex").toOption,
      byName("map#identity-shape").hcursor.get[String]("tree_bytes_hex").toOption,
      "map#some and map#identity-shape must share the same tree hex (both read GetVar(1,SInt))")
  }

  // ── summary + staging ────────────────────────────────────────────────────────

  test("summary + write staging") {
    val es = vectors(AuthoredOptionMap.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    val sb = new StringBuilder("\n========== authored Option.map vectors ==========\n")
    es.foreach { e =>
      val c    = e.hcursor
      val n    = c.get[String]("name").getOrElse("?")
      val cost = c.downField("expected").get[Long]("cost").toOption.map(_.toString).getOrElse("—")
      val err  = c.downField("expected").downField("error").focus.map(_.noSpaces).getOrElse("?")
      val v    = c.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("?")
      val hex  = c.get[String]("tree_bytes_hex").getOrElse("?")
      sb.append(s"  $n: cost=$cost value=$v error=$err\n      tree=$hex\n")
    }
    sb.append("==================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "authored-staging")
    AuthoredOptionMap.writeVectors(outDir)
    assert(
      java.nio.file.Files.exists(outDir.resolve("Option.map.json")),
      s"expected Option.map.json in $outDir")
  }

  // ── ANCHOR: exact blessed value/cost/tree locked after first observed bless ──
  // Costs are pinned from the first oracle bless; a change means the cost model moved.
  // INVESTIGATE — do not blindly re-bless.

  test("ANCHOR: map#some value = Option wrapping Int 6") {
    val entry = byName("map#some").hcursor
    val kind  = entry.downField("expected").downField("value").get[String]("kind").toOption.get
    val inner = entry.downField("expected").downField("value").downField("value")
    assertEquals(kind, "Option")
    assertEquals(inner.get[String]("kind").toOption, Some("Int"), "map#some inner kind")
    assertEquals(inner.get[Int]("value").toOption, Some(6), "map#some inner value")
  }

  test("ANCHOR: map#identity-shape value = Option wrapping Int 42") {
    val entry = byName("map#identity-shape").hcursor
    val kind  = entry.downField("expected").downField("value").get[String]("kind").toOption.get
    val inner = entry.downField("expected").downField("value").downField("value")
    assertEquals(kind, "Option")
    assertEquals(inner.get[String]("kind").toOption, Some("Int"), "map#identity-shape inner kind")
    assertEquals(inner.get[Int]("value").toOption, Some(42), "map#identity-shape inner value")
  }

  test("ANCHOR: map#none-via-absent-var value = Option(null) i.e. None") {
    val entry = byName("map#none-via-absent-var").hcursor
    val kind  = entry.downField("expected").downField("value").get[String]("kind").toOption.get
    val inner = entry.downField("expected").downField("value").downField("value").focus.get
    assertEquals(kind, "Option")
    assert(inner.isNull, s"map#none-via-absent-var inner must be null (None), got: ${inner.noSpaces}")
  }

  // ── ANCHOR: cost + tree hex pins (oracle-blessed) ────────────────────────────
  // Costs from first bless (sigma-state 6.0.3):
  //   map#some / map#identity-shape: 65
  //     GetVar(10) + MethodCall dispatch(4) + FuncValue creation(5) + MapMethod FixedCost(20)
  //     + AddToEnvironment(5, the lambda-arg binding) + ValUse(5) + ConstantPlaceholder(1)
  //     + Plus on SInt (TypeBasedCost 15) = 65
  //   map#none-via-absent-var: 39 (absent-var short-circuits before the lambda runs)
  //     GetVar(10) + MethodCall dispatch(4) + FuncValue creation(5) +
  //     MapMethod FixedCost(20) = 39 (lambda body NOT evaluated for None receiver)
  //   Δ26 = AddToEnvironment(5) + ValUse(5) + ConstantPlaceholder(1) + Plus(15)
  // Both var-1 trees are identical hex (same tree, different input);
  // var-99 tree differs only in the var-id byte (0x63 vs 0x01 in the GetVar encoding).
  private val costAnchors: Map[String, Long] = Map(
    "map#some"                -> 65L,
    "map#identity-shape"      -> 65L,
    "map#none-via-absent-var" -> 39L
  )

  test("ANCHOR: cost pins match oracle-blessed values") {
    costAnchors.foreach { case (name, expected) =>
      val actual = byName(name).hcursor.downField("expected").get[Long]("cost").toOption
      assertEquals(actual, Some(expected), s"$name cost drifted")
    }
  }

  // Tree hex anchors (oracle-blessed, sigma-state 6.0.3):
  //   var-1 tree (map#some, map#identity-shape): GetVar(1, SInt) receiver
  //   var-99 tree (map#none-via-absent-var):     GetVar(99, SInt) receiver
  private val treeAnchors: Map[String, String] = Map(
    "map#some"                -> "1a13010402dc2407e3010401d90102049a72027300",
    "map#identity-shape"      -> "1a13010402dc2407e3010401d90102049a72027300",
    "map#none-via-absent-var" -> "1a13010402dc2407e3630401d90102049a72027300"
  )

  test("ANCHOR: tree hex pins match oracle-blessed values") {
    treeAnchors.foreach { case (name, expected) =>
      val actual = byName(name).hcursor.get[String]("tree_bytes_hex").toOption
      assertEquals(actual, Some(expected), s"$name tree hex drifted")
    }
  }
}
