package santa

class AuthoredGetVarFromInputTest extends munit.FunSuite {
  lazy val env = AuthoredGetVarFromInput.extract()("Context.getVarFromInput")

  test("standalone tree hex + envelope shape are anchored") {
    assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-eval/v3"))
    assertEquals(env.hcursor.get[String]("source").toOption, Some("santa:authored-getvarfrominput"))
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
    assertEquals(entries.size, 4)
    entries.foreach { e =>
      assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption, Some("1b0f020300020bdc650cfe027300730101"))
      assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(17L))
    }
  }

  test("scenario values are blessed correctly") {
    val byName = env.hcursor.downField("entries").as[List[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get.takeWhile(_ != '#') -> e).toMap
    def value(n: String) = byName(n).hcursor.downField("expected").downField("value").focus.map(_.noSpaces)
    assertEquals(value("present-true"),  Some("""{"kind":"Option","value":{"kind":"Boolean","value":true}}"""))
    assertEquals(value("present-false"), Some("""{"kind":"Option","value":{"kind":"Boolean","value":false}}"""))
    assertEquals(value("absent"),        Some("""{"kind":"Option","value":null}"""))
    assertEquals(value("wrong-type"),    Some("""{"kind":"Option","value":null}"""))
  }

  test("write the committed vector") {
    val outDir = java.nio.file.Paths.get("..", "vectors", "eval", "v6", "authored")
    java.nio.file.Files.createDirectories(outDir)
    java.nio.file.Files.write(outDir.resolve("Context.getVarFromInput.json"),
      env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    assert(java.nio.file.Files.exists(outDir.resolve("Context.getVarFromInput.json")))
  }
}
