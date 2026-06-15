package santa

/** Guard for the FunDef nTpeArgs count-bound witness (ergots ask 22). 127 accepts (Int 5 @ 13),
  * 128 (patched count 0x80) rejects at deserialize (NegativeArraySizeException). Locks the blessed
  * value/cost + the reject shape; writes staging. */
class AuthoredFunDefNTpeArgsTest extends munit.FunSuite {
  lazy val vectors: Map[String, io.circe.Json] = AuthoredFunDefNTpeArgs.extract()

  test("nTpeArgs 127 accept / 128 reject; envelope well-formed") {
    val env = vectors(AuthoredFunDefNTpeArgs.Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("source").toOption, Some(AuthoredFunDefNTpeArgs.Source))
    val es = env.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)
    assertEquals(es.flatMap(_.hcursor.get[String]("name").toOption),
      List("nTpeArgs-127-accept#0", "nTpeArgs-128-reject#1"))

    val accept = es.head.hcursor.downField("expected")
    assertEquals(accept.downField("value").get[String]("kind").toOption, Some("Int"))
    assertEquals(accept.downField("value").get[Int]("value").toOption, Some(5))
    assertEquals(accept.get[Long]("cost").toOption, Some(13L))
    assert(accept.downField("error").focus.exists(_.isNull))

    val reject = es(1).hcursor.downField("expected")
    assertEquals(reject.get[String]("error").toOption, Some("errored"))
    assert(reject.downField("value").focus.exists(_.isNull), "reject value must be null")
    assert(reject.downField("cost").focus.exists(_.isNull), "reject cost must be null")
  }

  test("write staging") {
    val out = java.nio.file.Paths.get("target", "version-signedness-vectors")
    AuthoredFunDefNTpeArgs.writeVectors(out)
    assert(java.nio.file.Files.exists(out.resolve("FunDef.nTpeArgs_count_bound.json")),
      "staging FunDef.nTpeArgs_count_bound.json not written")
  }
}
