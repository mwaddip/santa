package santa

/** Guard + smoke test for the authored `Global.powHit` → Coll-HOF vectors — the powHit
  * return-type / HOF type-propagation case (sigma-rust PR #877), never spec-extracted
  * (LanguageSpecificationV6 has no powHit-HOF feature). Asserts the three HOFs are authored
  * and well-formed (exists/forall ⇒ Boolean, filter.size ⇒ Int; positive cost, no error),
  * prints the blessed value/cost/tree, and writes the staging vectors. Exact value/cost/tree
  * anchors are locked below once observed (the regression baseline). */
class AuthoredPowHitHofTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredPowHitHof.extract()

  private val Op = "Global.powHit feeding Coll-HOF"

  test("powHit Coll-HOF authored; three HOF entries well-formed") {
    assertEquals(vectors.keySet, Set(Op))
    val env = vectors(Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.get[String]("source").toOption, Some("santa:authored-powhit-hof"))

    val entries = env.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)
    assertEquals(entries.flatMap(_.hcursor.get[String]("name").toOption),
      List("exists#0", "forall#1", "filter.size#2"))

    val expectedKind = Map("exists#0" -> "Boolean", "forall#1" -> "Boolean", "filter.size#2" -> "Int")
    entries.foreach { e =>
      val ec = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assert(ec.get[String]("script").toOption.exists(_.contains("powHit")),
        s"$name: script must mention powHit")
      assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(3), s"$name v6 activated")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption, Some(3), s"$name v6 ergoTree")
      assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption,
        Some(expectedKind(name)), s"$name: result kind")
      val cost = ec.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
      assert(cost > 0, s"$name: cost must be positive, got $cost")
      assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$name error")
    }
  }

  test("summary + write staging") {
    val entries = vectors(Op).hcursor.downField("entries").as[List[io.circe.Json]].getOrElse(Nil)
    val sb = new StringBuilder("\n========== authored powHit Coll-HOF vectors ==========\n")
    entries.foreach { e =>
      val c    = e.hcursor
      val n    = c.get[String]("name").getOrElse("?")
      val cost = c.downField("expected").get[Long]("cost").getOrElse(-1L)
      val v    = c.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("?")
      val hex  = c.get[String]("tree_bytes_hex").getOrElse("?")
      sb.append(s"  $n: cost=$cost value=$v\n      tree=$hex\n")
    }
    sb.append("======================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "powhit-hof-vectors")
    AuthoredPowHitHof.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(SpecExtract.slug(Op) + ".json")),
      "staging vector was not written")
  }

  // ── regression baseline: exact blessed value+cost+tree, locked after the first observed
  //    run. A change means the JVM cost model, a powHit value, or the tree shape moved —
  //    investigate, do not blindly re-bless. (This is what a powHit return-type fix is checked
  //    against: pre-fix the impl errors at parse; post-fix it must reproduce these exactly.)
  private val baseline: Seq[(String, Long, String, String)] = Seq(
    ("exists#0", 1230L, """{"kind":"Boolean","value":true}""",
      "1b6a060e0800000000000000000e08000102030405060704040e20000102030405060708090a0b0c0d0e0f10" +
      "1112131415161718191a1b1c1d1e1f0e04000000010420aead83020e73007301d901010edc6a08dd05730273" +
      "03720173047305d90102099172027ee4e3010409"),
    ("forall#1", 1315L, """{"kind":"Boolean","value":true}""",
      "1b6a060e0800000000000000000e08000102030405060704040e20000102030405060708090a0b0c0d0e0f10" +
      "1112131415161718191a1b1c1d1e1f0e04000000010420afad83020e73007301d901010edc6a08dd05730273" +
      "03720173047305d90102099172027ee4e3010409"),
    ("filter.size#2", 1346L, """{"kind":"Int","value":2}""",
      "1b6b060e0800000000000000000e08000102030405060704040e20000102030405060708090a0b0c0d0e0f10" +
      "1112131415161718191a1b1c1d1e1f0e04000000010420b1b5ad83020e73007301d901010edc6a08dd057302" +
      "7303720173047305d90102099172027ee4e3010409")
  )

  test("blessed value+cost+tree match the recorded baseline") {
    val entries = vectors(Op).hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries: $e"), identity)
    baseline.foreach { case (name, cost, value, hex) =>
      val ec = entries.find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"no entry named '$name'")).hcursor
      assertEquals(ec.downField("expected").get[Long]("cost").toOption, Some(cost), s"$name cost drifted")
      assertEquals(ec.downField("expected").downField("value").focus.map(_.noSpaces), Some(value), s"$name value drifted")
      assertEquals(ec.get[String]("tree_bytes_hex").toOption, Some(hex), s"$name tree drifted")
    }
  }
}
