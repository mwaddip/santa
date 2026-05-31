package santa

/** TDD + smoke test for the V6 extractor.
  *
  * Anchored to the spike's hand-verified case: the first entry of the
  * `Global.serialize[Byte]` property must match the canonical capture
  * (tree bytes, input, expected value, cost) exactly. Also reports the
  * Stage-1 corpus size (captured vs skipped) so we know what was extracted,
  * and writes the staging vectors as a side effect for inspection. */
class V6ExtractorTest extends munit.FunSuite {

  // The extraction is moderately expensive (drives the whole V6 spec once); run it
  // a single time and share across assertions.
  lazy val result: ExtractResult = V6Extractor.extract()

  test("Global.serialize[Byte] vector matches the spike's canonical capture") {
    val env = result.vectors.getOrElse("Global.serialize[Byte]",
      fail(s"no vector captured for Global.serialize[Byte]; ops captured: ${result.vectors.keys.toSeq.sorted}"))

    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)

    // Envelope shape (v2).
    assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.hcursor.get[String]("op").toOption, Some("Global.serialize[Byte]"))
    assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.hcursor.get[String]("source").toOption, Some("sigma-state:LanguageSpecificationV6"))

    // The spike captured -128 as the first of five serialize[Byte] cases.
    val entry = entries.find { e =>
      e.hcursor.downField("input").get[Int]("value").toOption.contains(-128)
    }.getOrElse(fail(s"no entry with input -128; entries: ${entries.map(_.noSpaces)}"))

    val c = entry.hcursor
    assertEquals(c.get[String]("tree_bytes_hex").toOption,
      Some("1b1200dad9010102dc6a03dd01720101e4e30102"))
    assertEquals(c.downField("input").focus.map(_.noSpaces),
      Some("""{"kind":"Byte","value":-128}"""))
    assertEquals(c.downField("expected").downField("value").focus.map(_.noSpaces),
      Some("""{"kind":"Coll","elem":{"tag":"SByte"},"items":[{"kind":"Byte","value":-128}]}"""))
    assertEquals(c.downField("expected").get[Long]("cost").toOption, Some(90L))
    assertEquals(c.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"))
  }

  test("extraction reports a non-trivial Stage-1 corpus and writes staging vectors") {
    // Corpus-size report (informational; the precise numbers are in the test log).
    println(
      s"""
         |==================== V6 Stage-1 extraction summary ====================
         |  properties with emitted entries   : ${result.vectors.size}
         |  entries emitted (Stage-1 success) : ${result.captured}
         |  skipped — unsupported at V3       : ${result.skippedUnsupported}
         |  skipped — error-expected (Failure): ${result.skippedError}
         |  skipped — Context input (Stage 2b) : ${result.skippedContext}
         |${result.skippedContextReasons.map("    - " + _).mkString("\n")}
         |  skipped — unsupported value kind  : ${result.skippedUnsupportedKind}
         |${result.unsupportedKindReasons.map("    - " + _).mkString("\n")}
         |  COST DIAGNOSTICS (eval vs spec)   : ${result.costDiagnostics.size}
         |${result.costDiagnostics.map("    - " + _).mkString("\n")}
         |  property bodies that failed at V3 : ${result.propertyFailures.size}
         |${result.propertyFailures.map("    - " + _).mkString("\n")}
         |  ops: ${result.vectors.keys.toSeq.sorted.mkString(", ")}
         |=======================================================================
         |""".stripMargin)

    // Gate: any mid-body throw means that property's Capture list is truncated —
    // a truncated op must never ship silently.  The quarantine in V6Extractor
    // prevents the truncated entries from reaching the output dir, but this assert
    // makes the condition loud so it cannot be ignored.
    assert(result.propertyFailures.isEmpty,
      s"property bodies threw — corpus would be truncated: ${result.propertyFailures.mkString("; ")}")

    assert(result.captured > 0, "no Stage-1 cases captured at all")
    assert(result.vectors.contains("Global.serialize[Byte]"))

    // Persist staging vectors (build artifact — target/, not committed yet).
    val outDir = java.nio.file.Paths.get("target", "v6-vectors")
    V6Extractor.writeVectors(result, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("Global.serialize_Byte.json")),
      "staging vector for Global.serialize[Byte] was not written")
  }
}
