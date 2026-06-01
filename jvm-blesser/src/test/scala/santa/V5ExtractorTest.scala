package santa

/** TDD + smoke test for the V5 extractor.
  *
  * Drives LanguageSpecificationV5 at (2,2), asserts corpus shape and the
  * nBits exclusion, writes staging vectors, and anchors one deterministic
  * entry byte-for-byte to guard against encoder drift. */
class V5ExtractorTest extends munit.FunSuite {

  // Extraction is moderately expensive; run it once and share across all tests.
  lazy val result: ExtractResult = V5Extractor.extract()

  test("no property threw during V5 extraction (no truncated corpus)") {
    assert(result.propertyFailures.isEmpty,
      s"property bodies threw — corpus would be truncated: ${result.propertyFailures.mkString("; ")}")
  }

  test("V5 extraction emits a non-trivial Stage-1 corpus (> 1500 entries)") {
    assert(result.captured > 1500,
      s"only ${result.captured} entries captured — expected ~1611")
  }

  test("Coll patch method equivalence vector is present (cumulative v5 surface)") {
    assert(result.vectors.contains("Coll patch method equivalence"),
      s"missing 'Coll patch method equivalence'; ops captured: ${result.vectors.keys.toSeq.sorted}")
  }

  test("Coll patch method equivalence envelope has correct v2 schema fields") {
    val env = result.vectors.getOrElse("Coll patch method equivalence",
      fail("no vector for 'Coll patch method equivalence'"))
    assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.hcursor.get[String]("source").toOption, Some("sigma-state:LanguageSpecificationV5"))
    assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
  }

  test("Header.nBits script is excluded — no entry with nBits appears anywhere in vectors") {
    // The ExcludedScripts guard prevents capture; without it, toEntry's VALUE MISMATCH
    // sys.error (spec -1 vs eval 4294967295) would throw and abort this test.
    val allScripts = result.vectors.values.toSeq.flatMap { env =>
      env.hcursor.downField("entries").as[List[io.circe.Json]].getOrElse(Nil)
        .flatMap(_.hcursor.get[String]("script").toOption)
    }
    val nBitsScripts = allScripts.filter(_.contains("nBits"))
    assert(nBitsScripts.isEmpty,
      s"found ${nBitsScripts.size} nBits entry/entries that should have been excluded: ${nBitsScripts.take(3)}")
  }

  test("byte-anchor: first BinXor(logical XOR) equivalence entry matches canonical capture") {
    // BinXor(logical XOR) equivalence: 4 deterministic boolean cases, compact tree bytes.
    // The first entry is (true, true) -> false. Anchors encoder + tree-serializer stability.
    val op = "BinXor(logical XOR) equivalence"
    val env = result.vectors.getOrElse(op,
      fail(s"no vector for '$op'; ops: ${result.vectors.keys.toSeq.sorted}"))
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)

    val entry = entries.find { e =>
      val items = e.hcursor.downField("input").downField("items").as[List[io.circe.Json]].getOrElse(Nil)
      items.flatMap(_.hcursor.get[Boolean]("value").toOption) == List(true, true)
    }.getOrElse(fail(s"no entry with input (true,true) in BinXor(logical XOR) equivalence"))

    val c = entry.hcursor
    assertEquals(c.get[String]("tree_bytes_hex").toOption,
      Some("1a1400dad9010155f48c7201018c72010201e4e30155"))
    assertEquals(c.downField("input").get[String]("kind").toOption, Some("Tuple"))
    assertEquals(c.downField("expected").downField("value").focus.map(_.noSpaces),
      Some("""{"kind":"Boolean","value":false}"""))
    assertEquals(c.downField("expected").get[Long]("cost").toOption, Some(115L))
    assertEquals(c.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"))
  }

  test("staging vectors are written to target/v5-vectors/ (populated)") {
    val outDir = java.nio.file.Paths.get("target", "v5-vectors")
    SpecExtract.writeVectors(result, outDir)
    assert(java.nio.file.Files.exists(outDir) && java.nio.file.Files.list(outDir).findFirst().isPresent,
      s"staging dir $outDir is empty or was not created")
  }

  test("V5 extraction summary (informational)") {
    println(
      s"""
         |==================== V5 Stage-1 extraction summary ====================
         |  properties with emitted entries    : ${result.vectors.size}
         |  entries emitted (Stage-1 success)  : ${result.captured}
         |  skipped — unsupported at V5        : ${result.skippedUnsupported}
         |  skipped — error-expected (Failure) : ${result.skippedError}
         |  skipped — Context input (Stage 2b) : ${result.skippedContext}
         |${result.skippedContextReasons.map("    - " + _).mkString("\n")}
         |  skipped — unsupported value kind   : ${result.skippedUnsupportedKind}
         |${result.unsupportedKindReasons.map("    - " + _).mkString("\n")}
         |  COST DIAGNOSTICS (eval vs spec)    : ${result.costDiagnostics.size}
         |${result.costDiagnostics.map("    - " + _).mkString("\n")}
         |  property bodies that failed at V5  : ${result.propertyFailures.size}
         |${result.propertyFailures.map("    - " + _).mkString("\n")}
         |  ops: ${result.vectors.keys.toSeq.sorted.mkString(", ")}
         |=======================================================================
         |""".stripMargin)
  }
}
