package santa

/** Guard + smoke test for the authored DeserializeContext leniency vectors (sigma-rust #879):
  * an absent / wrong-typed context var under a DeserializeContext on a DEAD branch → ACCEPT,
  * on the LIVE path → REJECT. Asserts well-formedness (2 accept Boolean-true, 2 reject errored),
  * prints blessed values/costs/trees, writes staging. Exact value/cost/tree anchors are locked
  * below once observed (the regression baseline). */
class AuthoredDeserializeContextTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredDeserializeContext.extract()

  private val Op = "DeserializeContext over absent/wrong-typed var"
  private val accept = Set("dead-branch-absent#0", "dead-branch-wrong-type#1")

  test("DeserializeContext leniency authored; four entries well-formed") {
    assertEquals(vectors.keySet, Set(Op))
    val env = vectors(Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.get[String]("source").toOption, Some("santa:authored-deserialize-context"))

    val entries = env.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)
    assertEquals(entries.flatMap(_.hcursor.get[String]("name").toOption),
      List("dead-branch-absent#0", "dead-branch-wrong-type#1", "live-absent#2", "live-wrong-type#3"))

    entries.foreach { e =>
      val ec = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assert(ec.get[String]("script").toOption.exists(_.contains("deserializeContext")),
        s"$name: script must mention deserializeContext")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption, Some(3), s"$name v6 ergoTree")
      if (accept(name)) {
        assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption,
          Some("Boolean"), s"$name: dead branch ⇒ Boolean")
        assertEquals(ec.downField("expected").downField("value").get[Boolean]("value").toOption,
          Some(true), s"$name: if(true) ⇒ true")
        assert(ec.downField("expected").get[Long]("cost").toOption.exists(_ > 0), s"$name: cost > 0")
        assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$name no error")
      } else {
        assertEquals(ec.downField("expected").downField("value").focus.map(_.noSpaces), Some("null"),
          s"$name: reject ⇒ null value")
        assertEquals(ec.downField("expected").get[String]("error").toOption, Some("errored"),
          s"$name: live DeserializeContext ⇒ errored")
      }
    }
  }

  test("summary + write staging") {
    val entries = vectors(Op).hcursor.downField("entries").as[List[io.circe.Json]].getOrElse(Nil)
    val sb = new StringBuilder("\n========== authored DeserializeContext vectors ==========\n")
    entries.foreach { e =>
      val c   = e.hcursor
      val n   = c.get[String]("name").getOrElse("?")
      val exp = c.downField("expected").focus.map(_.noSpaces).getOrElse("?")
      val hex = c.get[String]("tree_bytes_hex").getOrElse("?")
      sb.append(s"  $n: $exp\n      tree=$hex\n")
    }
    sb.append("=========================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "deserialize-context-vectors")
    AuthoredDeserializeContext.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(SpecExtract.slug(Op) + ".json")),
      "staging vector was not written")
  }

  // ── regression baseline: exact blessed value/cost/tree, locked after the first observed run.
  //    A change means the tree shape or the JVM's leniency/eval moved — investigate, don't
  //    blindly re-bless. (dead-branch ⇒ true/cost-20; live-path ⇒ errored.)
  //    Cost 20 = PRODUCTION (substituted-constant) semantics: deserialize-bearing segregated
  //    trees are blessed at the cost fullReduction charges on-chain (Interpreter.scala:218
  //    evals lazily ONLY when !hasDeserialize; the deserialize branch evals the substituted
  //    prop — Const(5) vs lazy CP(1) per visit, +8 = 2×4 here). Decision 2026-06-06 (ergots
  //    F1 consult); EvalCore mirrors the conditionality, so the bless path produces 20.
  private val baseline: Seq[(String, String, String)] = Seq(
    // name, expected(noSpaces), tree_bytes_hex
    ("dead-branch-absent#0",     """{"value":{"kind":"Boolean","value":true},"cost":20,"error":null}""", "1b0d02010101019573007301d40100"),
    ("dead-branch-wrong-type#1", """{"value":{"kind":"Boolean","value":true},"cost":20,"error":null}""", "1b0d02010101019573007301d40101"),
    ("live-absent#2",            """{"value":null,"cost":null,"error":"errored"}""",                     "1b0400d40100"),
    ("live-wrong-type#3",        """{"value":null,"cost":null,"error":"errored"}""",                     "1b0400d40101"))

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
