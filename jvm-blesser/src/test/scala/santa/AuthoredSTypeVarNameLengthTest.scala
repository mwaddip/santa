package santa

/** Guard for the STypeVar name-length domain witness (ergots ask 23). Lengths 0 and 255 both
  * accept (Int 5 @ 13) on the JVM (unbounded getUByte) where a [1,254]-bounded impl over-rejects.
  * Locks the blessed values; writes staging. (256 is serialize-side only — documented, not vectored.) */
class AuthoredSTypeVarNameLengthTest extends munit.FunSuite {
  lazy val vectors: Map[String, io.circe.Json] = AuthoredSTypeVarNameLength.extract()

  test("name length 0 + 255 both accept Int 5 @ 13; envelope well-formed") {
    val env = vectors(AuthoredSTypeVarNameLength.Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("source").toOption, Some(AuthoredSTypeVarNameLength.Source))
    val es = env.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)
    assertEquals(es.flatMap(_.hcursor.get[String]("name").toOption),
      List("name-length-0-accept#0", "name-length-255-accept#1"))
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
    AuthoredSTypeVarNameLength.writeVectors(out)
    assert(java.nio.file.Files.exists(out.resolve("STypeVar.name_length_bound.json")),
      "staging STypeVar.name_length_bound.json not written")
  }
}
