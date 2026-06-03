package santa

/** Guard + smoke test for the authored SigmaProp-equality cost vectors — the SigmaProp branch
  * of the equality comparer (DataValueComparer.equalSigmaBoolean / equalECPoint), absent from
  * LanguageSpecificationV5's NEQ features so never spec-extracted. Asserts the op is authored
  * and well-formed (`==` ⇒ Boolean `true`, positive cost, no error), prints the blessed costs,
  * runs the String-equality reachability probe, and writes the staging vectors. Exact-cost
  * anchors + the probe verdict are locked below once observed (the regression baseline). */
class AuthoredSigmaPropEqTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredSigmaPropEq.extract()

  test("EQ of SigmaProp authored; each entry well-formed (== ⇒ Boolean true, cost > 0)") {
    assertEquals(vectors.keySet, Set("EQ of SigmaProp"))
    val env = vectors("EQ of SigmaProp").hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.get[String]("source").toOption, Some("santa:authored-sigmaprop-eq"))

    val entries = env.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)
    assertEquals(entries.flatMap(_.hcursor.get[String]("name").toOption),
      List("proveDlog#0", "proveDHTuple#1", "CAND#2"))

    entries.foreach { e =>
      val ec = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assert(ec.get[String]("script").toOption.exists(_.contains("getVar[SigmaProp]")),
        s"$name: script must read getVar[SigmaProp](1)")
      assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(2), s"$name v5 activated")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption, Some(2), s"$name v5 ergoTree")
      assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption,
        Some("Boolean"), s"$name: == yields Boolean")
      assertEquals(ec.downField("expected").downField("value").get[Boolean]("value").toOption,
        Some(true), s"$name: x == x is true")
      val cost = ec.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
      assert(cost > 0, s"$name: cost must be positive, got $cost")
      assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$name error")
    }
  }

  test("summary + String-eq reachability probe + write staging") {
    val env = vectors("EQ of SigmaProp").hcursor
    val entries = env.downField("entries").as[List[io.circe.Json]].getOrElse(Nil)
    val sb = new StringBuilder("\n========== authored SigmaProp-eq vectors ==========\n")
    entries.foreach { e =>
      val n    = e.hcursor.get[String]("name").getOrElse("?")
      val cost = e.hcursor.downField("expected").get[Long]("cost").getOrElse(-1L)
      sb.append(s"  $n: cost=$cost\n")
    }
    // String-equality reachability probe (the prompt's secondary ask) — empirical, via eval.
    val (script, outcome) = AuthoredSigmaPropEq.stringEqProbe()
    sb.append(s"  --- String-eq probe: $script\n")
    outcome match {
      case Right((v, cost)) => sb.append(s"      REACHABLE → cost=$cost value=${v.noSpaces}\n")
      case Left(err)        => sb.append(s"      eval rejected SString → $err\n")
    }
    sb.append("===================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "sigmaprop-eq-vectors")
    AuthoredSigmaPropEq.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("EQ_of_SigmaProp.json")),
      "staging vector EQ_of_SigmaProp.json was not written")
  }

  // ── regression baseline: exact blessed costs + tree + the String-eq verdict, locked after
  //    the first observed run. A change means the JVM cost model or an input moved —
  //    investigate, do not blindly re-bless (these are what sigma-rust verifies its equality
  //    accounting against; vs sigma-rust's flat EQ_PRIM_COST=3 the deltas are 171/687/345).
  private val expectedCosts: Seq[(String, Long)] =
    Seq("proveDlog#0" -> 224L, "proveDHTuple#1" -> 740L, "CAND#2" -> 398L)

  test("blessed costs match the recorded baseline") {
    val entries = vectors("EQ of SigmaProp").hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries: $e"), identity)
    expectedCosts.foreach { case (name, cost) =>
      val e = entries.find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"no entry named '$name'"))
      assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(cost),
        s"$name cost drifted")
    }
  }

  test("one EQ-of-getVar tree shared by all three shapes (tree anchored)") {
    val entries = vectors("EQ of SigmaProp").hcursor.downField("entries").as[List[io.circe.Json]]
      .toOption.get
    val hexes = entries.flatMap(_.hcursor.get[String]("tree_bytes_hex").toOption).distinct
    assertEquals(hexes, List("1a0a0093e4e30108e4e30108"), "tree shape drifted")
  }

  test("String-eq stays unreachable at eval (defensive comparer code, not a consensus divergence)") {
    val (_, outcome) = AuthoredSigmaPropEq.stringEqProbe()
    outcome match {
      case Left(err) => assert(err.contains("SString"), s"expected an SString-type rejection, got: $err")
      case Right((v, cost)) =>
        fail(s"String EQ became reachable at eval (value=${v.noSpaces} cost=$cost) — sigma-state now " +
          "evaluates SString; revisit whether a String-eq cost vector is warranted")
    }
  }
}
