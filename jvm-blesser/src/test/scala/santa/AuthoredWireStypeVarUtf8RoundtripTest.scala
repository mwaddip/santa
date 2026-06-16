package santa

/** Anchors the 5 blessed expected_bytes_hex (locks the captured JVM structural bytes) and writes the
  * staging file to cp into vectors/wire/v6/authored/. A change here means the JVM re-encode moved. */
class AuthoredWireStypeVarUtf8RoundtripTest extends munit.FunSuite {
  private lazy val env = AuthoredWireStypeVarUtf8Roundtrip.extract()(AuthoredWireStypeVarUtf8Roundtrip.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  private val expectedByInput: Map[String, String] = Map(
    "1b1501040ad801d701016701ffd901026701ff72027300"       -> "1b1901040ad801d701016703efbfbdd901026703efbfbd72027300",
    "1b1701040ad801d701016702e282d901026702e28272027300"   -> "1b1901040ad801d701016703efbfbdd901026703efbfbd72027300",
    "1b1701040ad801d701016702c080d901026702c08072027300"   -> "1b1f01040ad801d701016706efbfbdefbfbdd901026706efbfbdefbfbd72027300",
    "1b1901040ad801d701016703eda080d901026703eda08072027300" -> "1b1901040ad801d701016703efbfbdd901026703efbfbd72027300",
    "1b1901040ad801d70101670361ff62d90102670361ff6272027300" -> "1b1d01040ad801d70101670561efbfbd62d90102670561efbfbd6272027300")

  test("all 5 entries: ErgoTree kind, non-identity, blessed expected matches the captured JVM bytes") {
    assertEquals(entries.size, 5)
    entries.foreach { e =>
      val c = e.hcursor
      assertEquals(c.get[String]("kind").toOption, Some("ErgoTree"))
      val in  = c.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
      val exp = c.get[String]("expected_bytes_hex").toOption.getOrElse(fail("expected_bytes_hex"))
      assertNotEquals(exp, in, s"must be non-identity for input $in")
      assertEquals(expectedByInput.get(in), Some(exp), s"blessed expected drifted for input $in")
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireStypeVarUtf8Roundtrip.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("STypeVar.name_utf8_roundtrip.json")),
      "staging STypeVar.name_utf8_roundtrip.json not written")
  }
}
