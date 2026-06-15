package santa

/** Guard for the STypeVar name UTF-8 leniency witness (sigma-rust vector ask). All 5 invalid-UTF-8
  * name sequences ACCEPT (Int 5 @ 13) on the JVM (lossy `new String(_, UTF_8)`) where a strict-UTF-8
  * impl rejects. Locks the blessed values; writes staging. (Byte-exactness of the U+FFFD canonical
  * form is the separate wire round-trip vector — this only pins parse-acceptance.) */
class AuthoredSTypeVarNameUtf8Test extends munit.FunSuite {
  lazy val vectors: Map[String, io.circe.Json] = AuthoredSTypeVarNameUtf8.extract()

  test("all 5 invalid-UTF-8 names accept Int 5 @ 13; envelope well-formed") {
    val env = vectors(AuthoredSTypeVarNameUtf8.Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("source").toOption, Some(AuthoredSTypeVarNameUtf8.Source))
    val es = env.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)
    assertEquals(es.flatMap(_.hcursor.get[String]("name").toOption),
      List("name-utf8-ff-accept#0", "name-utf8-e282-accept#1", "name-utf8-c080-accept#2",
        "name-utf8-eda080-accept#3", "name-utf8-61ff62-accept#4"))
    es.foreach { e =>
      val exp = e.hcursor.downField("expected")
      val nm = e.hcursor.get[String]("name").toOption.getOrElse("?")
      assertEquals(exp.downField("value").get[String]("kind").toOption, Some("Int"), s"$nm kind")
      assertEquals(exp.downField("value").get[Int]("value").toOption, Some(5), s"$nm value")
      assertEquals(exp.get[Long]("cost").toOption, Some(13L), s"$nm cost")
      assert(exp.downField("error").focus.exists(_.isNull), s"$nm error")
    }
  }

  test("write staging") {
    val out = java.nio.file.Paths.get("target", "version-signedness-vectors")
    AuthoredSTypeVarNameUtf8.writeVectors(out)
    assert(java.nio.file.Files.exists(out.resolve("STypeVar.name_utf8_leniency.json")),
      "staging STypeVar.name_utf8_leniency.json not written")
  }
}
