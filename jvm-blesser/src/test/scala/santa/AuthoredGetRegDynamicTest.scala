package santa

/** Guard + smoke test for the authored Box.getReg dynamic-index MethodCall vectors
  * (typeId=99, methodId=19, V3-gated, P7a-2).
  *
  * Tests:
  *   1. Shape: schema=v4, op+source correct, 4 entries with expected names.
  *   2. Arm 1 (accept): value = Option Some(Long 7), positive cost, error null.
  *   3. Arm 2 (reject): value null, cost null, error "errored".
  *   4. Arm 3a (none-absent-r5): value = Option None, positive cost, error null.
  *   5. Arm 3b (none-out-of-range-10): value = Option None, positive cost, error null.
  *   6. Regression baseline: exact blessed values and costs locked after first run.
  *   7. Write the committed vector to vectors/eval/v6/authored/.
  */
class AuthoredGetRegDynamicTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredGetRegDynamic.extract()
  lazy val env: io.circe.Json                  = vectors(AuthoredGetRegDynamic.Op)

  private def entries: List[io.circe.Json] =
    env.hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)

  private def entryByName(n: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.exists(_.startsWith(n)))
      .getOrElse(fail(s"no entry named '$n'"))

  test("envelope shape: schema=v4, op, source, 4 entries") {
    assertEquals(env.hcursor.get[String]("schema").toOption,    Some("santa-eval/v4"))
    assertEquals(env.hcursor.get[String]("op").toOption,        Some(AuthoredGetRegDynamic.Op))
    assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.hcursor.get[String]("source").toOption,    Some(AuthoredGetRegDynamic.Source))
    assertEquals(entries.size, 4)
    val names = entries.flatMap(_.hcursor.get[String]("name").toOption)
    assertEquals(names, List("accept-r4-long#0", "reject-wrong-type#1", "none-absent-r5#2", "none-out-of-range-10#3"))
  }

  test("arm 1 accept: value = Option Some(Long 7), cost > 0, error null") {
    val e = entryByName("accept-r4-long#0")
    val ec = e.hcursor
    val valField = ec.downField("expected").downField("value")
    assertEquals(valField.get[String]("kind").toOption, Some("Option"), "value kind must be Option")
    val inner = valField.downField("value")
    assertEquals(inner.get[String]("kind").toOption, Some("Long"), "inner kind must be Long")
    assertEquals(inner.get[String]("value").toOption, Some("7"), "inner Long must be 7")
    val cost = ec.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
    assert(cost > 0, s"cost must be positive, got $cost")
    assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"))
  }

  test("arm 2 reject wrong type: value null, cost null, error errored") {
    val e  = entryByName("reject-wrong-type#1")
    val ec = e.hcursor
    assertEquals(ec.downField("expected").downField("value").focus.map(_.noSpaces), Some("null"))
    assertEquals(ec.downField("expected").downField("cost").focus.map(_.noSpaces),  Some("null"))
    assertEquals(ec.downField("expected").get[String]("error").toOption, Some("errored"))
  }

  test("arm 3a none-absent-r5: value = Option None, cost > 0, error null") {
    val e  = entryByName("none-absent-r5#2")
    val ec = e.hcursor
    val valField = ec.downField("expected").downField("value")
    assertEquals(valField.get[String]("kind").toOption, Some("Option"), "value kind must be Option")
    assertEquals(valField.downField("value").focus.map(_.noSpaces), Some("null"),
      "absent register → None (null inner value)")
    val cost = ec.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
    assert(cost > 0, s"cost must be positive, got $cost")
    assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"))
  }

  test("arm 3b none-out-of-range-10: value = Option None, cost > 0, error null") {
    val e  = entryByName("none-out-of-range-10#3")
    val ec = e.hcursor
    val valField = ec.downField("expected").downField("value")
    assertEquals(valField.get[String]("kind").toOption, Some("Option"), "value kind must be Option")
    assertEquals(valField.downField("value").focus.map(_.noSpaces), Some("null"),
      "out-of-range index → None (null inner value)")
    val cost = ec.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
    assert(cost > 0, s"cost must be positive, got $cost")
    assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"))
  }

  // ── Regression baseline: exact blessed values/costs locked after first run ──
  // Values and costs are blessed by the JVM oracle (sigma-state 6.0.3).
  // A drift means the cost model or tree moved — investigate, do not blindly re-bless.

  test("blessed costs match the recorded baseline") {
    val byName = entries.map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap
    val expectedCosts: Seq[(String, Long)] = Seq(
      "accept-r4-long#0"       -> 89L,
      "none-absent-r5#2"       -> 89L,
      "none-out-of-range-10#3" -> 89L)
    expectedCosts.foreach { case (name, cost) =>
      val e = byName.getOrElse(name, fail(s"no entry '$name'"))
      assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(cost), s"$name cost drifted")
    }
  }

  test("tree hex anchored (Long-tree and Int-tree)") {
    val longTreeHex = entryByName("accept-r4-long#0").hcursor.get[String]("tree_bytes_hex").toOption
    val intTreeHex  = entryByName("reject-wrong-type#1").hcursor.get[String]("tree_bytes_hex").toOption
    assertEquals(longTreeHex, Some("1b0b00dc6313a701e4e3010405"), "Long-tree hex drifted")
    assertEquals(intTreeHex,  Some("1b0b00dc6313a701e4e3010404"), "Int-tree hex drifted")
    // Both None arms share the Long tree
    assertEquals(entryByName("none-absent-r5#2").hcursor.get[String]("tree_bytes_hex").toOption, longTreeHex, "none-3a tree")
    assertEquals(entryByName("none-out-of-range-10#3").hcursor.get[String]("tree_bytes_hex").toOption, longTreeHex, "none-3b tree")
  }

  test("print oracle outputs") {
    val sb = new StringBuilder("\n========== Box.getReg dynamic-index oracle outputs ==========\n")
    entries.foreach { e =>
      val n     = e.hcursor.get[String]("name").getOrElse("?")
      val value = e.hcursor.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("?")
      val cost  = e.hcursor.downField("expected").downField("cost").focus.map(_.noSpaces).getOrElse("?")
      val err   = e.hcursor.downField("expected").downField("error").focus.map(_.noSpaces).getOrElse("?")
      sb.append(s"  $n: value=$value cost=$cost error=$err\n")
    }
    val longTree = entries.find(_.hcursor.get[String]("name").toOption.exists(_.startsWith("accept"))).get
    sb.append(s"  Long-tree hex: ${longTree.hcursor.get[String]("tree_bytes_hex").getOrElse("?")}\n")
    val intTree  = entries.find(_.hcursor.get[String]("name").toOption.exists(_.startsWith("reject"))).get
    sb.append(s"  Int-tree hex:  ${intTree.hcursor.get[String]("tree_bytes_hex").getOrElse("?")}\n")
    sb.append("==============================================================\n")
    println(sb.toString)
  }

  test("write the committed vector") {
    val outDir = java.nio.file.Paths.get("..", "vectors", "eval", "v6", "authored")
    java.nio.file.Files.createDirectories(outDir)
    java.nio.file.Files.write(outDir.resolve("Box.getReg_dynamic_index.json"),
      env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    assert(java.nio.file.Files.exists(outDir.resolve("Box.getReg_dynamic_index.json")))
  }
}
