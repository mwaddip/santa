package santa

/** Guard + smoke for the authored FunDef type-var-body vectors (the ergots P6
  * follow-up request): a FunDef whose lambda arg is typed by the type
  * var `T` itself. CONSTRUCT-only accepts (Int 5); APPLY errors ("Unknown type T") regardless
  * of whether the body reads the arg. Asserts well-formedness (1 accept, 3 reject errored),
  * prints blessed values/costs/trees, writes staging. Exact anchors locked below (baseline). */
class AuthoredFunDefTypeVarTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredFunDefTypeVar.extract()

  private val Op = "HOF FunDef type-var body"
  private val accept = Set("bound-never-applied-accept#0")

  test("FunDef type-var body authored; four entries well-formed") {
    assertEquals(vectors.keySet, Set(Op))
    val env = vectors(Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.get[String]("source").toOption, Some("santa:authored-fundef-typevar"))

    val entries = env.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)
    assertEquals(entries.flatMap(_.hcursor.get[String]("name").toOption),
      List("bound-never-applied-accept#0", "identity-applied-reject#1",
           "applied-body-ignores-arg-reject#2", "type-dependent-applied-reject#3"))

    entries.foreach { e =>
      val ec = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assert(ec.get[String]("script").toOption.exists(_.contains("[T]")),
        s"$name: script must declare the type var [T]")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption, Some(3), s"$name v6 ergoTree")
      if (accept(name)) {
        assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption,
          Some("Int"), s"$name: construct-only ⇒ Int")
        assertEquals(ec.downField("expected").downField("value").get[Int]("value").toOption,
          Some(5), s"$name: result 5")
        assert(ec.downField("expected").get[Long]("cost").toOption.exists(_ > 0), s"$name: cost > 0")
        assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$name no error")
      } else {
        assertEquals(ec.downField("expected").downField("value").focus.map(_.noSpaces), Some("null"),
          s"$name: reject ⇒ null value")
        assertEquals(ec.downField("expected").get[String]("error").toOption, Some("errored"),
          s"$name: applied type-var lambda ⇒ errored")
      }
    }
  }

  test("summary + write staging") {
    val entries = vectors(Op).hcursor.downField("entries").as[List[io.circe.Json]].getOrElse(Nil)
    val sb = new StringBuilder("\n========== authored FunDef type-var body vectors ==========\n")
    entries.foreach { e =>
      val c   = e.hcursor
      val n   = c.get[String]("name").getOrElse("?")
      val exp = c.downField("expected").focus.map(_.noSpaces).getOrElse("?")
      val hex = c.get[String]("tree_bytes_hex").getOrElse("?")
      sb.append(s"  $n: $exp\n      tree=$hex\n")
    }
    sb.append("===========================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "fundef-typevar-vectors")
    AuthoredFunDefTypeVar.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(SpecExtract.slug(Op) + ".json")),
      "staging vector was not written")
  }

  // ── regression baseline: exact blessed value/cost/tree, locked after the first observed run.
  //    A change means the tree shape or the JVM's type-var handling moved — investigate, don't
  //    blindly re-bless. (construct-only ⇒ Int 5 cost 13; any APPLY of a type-var lambda ⇒ errored.)
  private val baseline: Seq[(String, String, String)] = Seq(
    // name, expected(noSpaces), tree_bytes_hex
    ("bound-never-applied-accept#0",      """{"value":{"kind":"Int","value":5},"cost":13,"error":null}""", "1b1501040ad801d70101670154d9010267015472027300"),
    ("identity-applied-reject#1",         """{"value":null,"cost":null,"error":"errored"}""",              "1b1901040ed801d70101670154d901026701547202da7201017300"),
    ("applied-body-ignores-arg-reject#2", """{"value":null,"cost":null,"error":"errored"}""",              "1b1b02040a040ed801d70101670154d901026701547300da7201017301"),
    ("type-dependent-applied-reject#3",   """{"value":null,"cost":null,"error":"errored"}""",              "1b1c01040ed801d70101670154d901026701549a72027202da7201017300"))

  test("blessed value/cost/tree match the recorded baseline") {
    val entries = vectors(Op).hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries: $e"), identity)
    baseline.foreach { case (name, expected, hex) =>
      val ec = entries.find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"no entry named '$name'")).hcursor
      assertEquals(ec.downField("expected").focus.map(_.noSpaces), Some(expected), s"$name expected drifted")
      assertEquals(ec.get[String]("tree_bytes_hex").toOption, Some(hex), s"$name tree drifted")
    }
  }
}
