package santa

class AuthoredGetVarFromInputMultiTest extends munit.FunSuite {
  lazy val env = AuthoredGetVarFromInputMulti.extract()("Context.getVarFromInput")

  // ── Step 1: inspect oracle outputs ──────────────────────────────────────────
  test("dump oracle outputs for baseline inspection") {
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
    entries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.get
      val hex  = e.hcursor.get[String]("tree_bytes_hex").toOption.get
      val value = e.hcursor.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("?")
      val cost  = e.hcursor.downField("expected").downField("cost").focus.map(_.noSpaces).getOrElse("?")
      println(s"[$name] tree=$hex  value=$value  cost=$cost")
    }
    assertEquals(entries.size, 6)
  }

  // ── Envelope shape ──────────────────────────────────────────────────────────
  test("envelope schema and entry count") {
    assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-eval/v3"))
    assertEquals(env.hcursor.get[String]("source").toOption, Some("santa:authored-getvarfrominput-multi"))
    assertEquals(env.hcursor.get[String]("op").toOption, Some("Context.getVarFromInput"))
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
    assertEquals(entries.size, 6)
  }

  // ── Group-1 tree hex is consistent ──────────────────────────────────────────
  test("group-1 entries share the idx=1 tree hex") {
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
    val group1  = entries.take(4)
    val hexes   = group1.map(_.hcursor.get[String]("tree_bytes_hex").toOption.get).toSet
    assertEquals(hexes.size, 1, s"all 4 group-1 entries should share one tree hex, got: $hexes")
    // Lock the idx=1 tree hex baseline
    assertEquals(hexes.head, "1b0f020302020bdc650cfe027300730101")
  }

  // ── Tree hex baselines locked ────────────────────────────────────────────────
  test("tree hex baselines are anchored") {
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
    // Group 1: inputIdx=1, varId=11
    entries.take(4).foreach { e =>
      assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption,
        Some("1b0f020302020bdc650cfe027300730101"))
    }
    // OOB entry: inputIdx=5, varId=11
    assertEquals(entries(4).hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1b0f02030a020bdc650cfe027300730101"))
    // 0xFF entry: inputIdx=0, varId=-1 (0xFF)
    assertEquals(entries(5).hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1b0f02030002ffdc650cfe027300730101"))
  }

  // ── Cost baseline ────────────────────────────────────────────────────────────
  test("all entries cost 17") {
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
    entries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.get
      assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption,
        Some(17L), s"entry $name: cost")
    }
  }

  // ── Group-1 values: absent/wrong-type → None; present → Some ────────────────
  test("group-1 values match expected shapes") {
    val byName = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get.takeWhile(_ != '#') -> e).toMap
    def value(n: String) = byName(n).hcursor.downField("expected").downField("value").focus.map(_.noSpaces)

    assertEquals(value("multi-input-no-var-at-idx1"),
      Some("""{"kind":"Option","value":null}"""),
      "Case A: absent → None")
    assertEquals(value("multi-input-present-true-at-idx1"),
      Some("""{"kind":"Option","value":{"kind":"Boolean","value":true}}"""),
      "Case B: present Boolean(true) → Some(true)")
    assertEquals(value("multi-input-wrong-type-at-idx1"),
      Some("""{"kind":"Option","value":null}"""),
      "Case C: wrong type → None")
    assertEquals(value("multi-input-present-false-at-idx1"),
      Some("""{"kind":"Option","value":{"kind":"Boolean","value":false}}"""),
      "Case D: present Boolean(false) → Some(false)")
  }

  // ── OOB input-index pin: oracle verdict LOCKED ──────────────────────────────
  // inputIdx=5 with 2 spending inputs: out-of-range → None, cost 17.
  // Distinct path: an impl branching at OOB before the extension lookup would be
  // untested if only the var-absent path (multi-input-no-var-at-idx1) were pinned.
  test("oob-input-index oracle verdict is locked (None, cost 17)") {
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
    val pin     = entries(4)
    assertEquals(pin.hcursor.get[String]("name").toOption,
      Some("oob-input-index#4"))
    assertEquals(
      pin.hcursor.downField("expected").downField("value").focus.map(_.noSpaces),
      Some("""{"kind":"Option","value":null}"""),
      "OOB inputIdx: oracle must return None")
    assertEquals(
      pin.hcursor.downField("expected").get[Long]("cost").toOption,
      Some(17L),
      "OOB inputIdx: cost must be 17")
  }

  // ── 0xFF pin: oracle verdict LOCKED ────────────────────────────────────────
  // Oracle says: ByteConstant(-1) in AST matches extension key 255 (0xFF) —
  // JVM byte-identity: -1.toByte == (0xFF).toByte. Result: Some(true), cost 17.
  test("negative-varid-0xff oracle verdict is locked (Some(true), cost 17)") {
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
    val pin     = entries.last
    assertEquals(pin.hcursor.get[String]("name").toOption,
      Some("negative-varid-0xff#5"))
    assertEquals(
      pin.hcursor.downField("expected").downField("value").focus.map(_.noSpaces),
      Some("""{"kind":"Option","value":{"kind":"Boolean","value":true}}"""),
      "0xFF varId: oracle must return Some(true) — byte-identity matching confirmed")
    assertEquals(
      pin.hcursor.downField("expected").get[Long]("cost").toOption,
      Some(17L),
      "0xFF varId: cost must be 17")
  }

  // ── Write the committed vector file ─────────────────────────────────────────
  test("write the committed vector") {
    val outDir = java.nio.file.Paths.get("..", "vectors", "eval", "v6", "authored")
    java.nio.file.Files.createDirectories(outDir)
    AuthoredGetVarFromInputMulti.writeVector(
      AuthoredGetVarFromInputMulti.extract(),
      outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("Context.getVarFromInput_multi_input.json")))
  }
}
