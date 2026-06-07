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
    assertEquals(vectors.keySet,
      Set(AuthoredSigmaPropEq.Op, AuthoredSigmaPropEq.OpUnequal, AuthoredSigmaPropEq.OpConjecture),
      "extract() must emit exactly the three EQ ops")
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
    assert(java.nio.file.Files.exists(outDir.resolve("EQ_of_SigmaProp_unequal.json")),
      "staging vector EQ_of_SigmaProp_unequal.json was not written")
    assert(java.nio.file.Files.exists(outDir.resolve("EQ_of_SigmaProp_conjecture_mismatch.json")),
      "staging vector EQ_of_SigmaProp_conjecture_mismatch.json was not written")
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

  test("unequal op: 5 closed-tree entries, all value=false, short-circuit cost ordering") {
    val env = vectors(AuthoredSigmaPropEq.OpUnequal)
    val entries = env.hcursor.downField("entries").as[Seq[io.circe.Json]].toOption.get
    assertEquals(entries.size, 5)
    val byName = entries.map(e => e.hcursor.get[String]("name").toOption.get -> e.hcursor).toMap
    byName.values.foreach { c =>
      assertEquals(c.downField("expected").downField("value").get[Boolean]("value").toOption.get, false)
    }
    def cost(name: String): Long = byName(name).downField("expected").get[Long]("cost").toOption.get
    // the short-circuit evidence: DHT mismatch at the FIRST point is strictly
    // cheaper than at the FOURTH (1 vs 4 EQ_GroupElement charges)
    assert(cost("dht-mismatch-at-g#2") < cost("dht-mismatch-at-v#3"),
      s"${cost("dht-mismatch-at-g#2")} !< ${cost("dht-mismatch-at-v#3")}")
  }

  // post-bless anchors — a re-bless that changes these is a cost-model change to
  // INVESTIGATE, not blindly accept (short-circuit topology is load-bearing evidence)
  private val unequalBlessedCosts: Seq[(String, Long)] = Seq(
    "dlog-vs-dlog2#0"     -> 176L,  // MatchType + 1×EQ_GroupElement
    "dlog-vs-dht#1"       ->   4L,  // MatchType only — node-type mismatch, no EcPoint compared
    "dht-mismatch-at-g#2" -> 176L,  // MatchType + 1×EQ_GroupElement (first point differs)
    "dht-mismatch-at-v#3" -> 692L,  // MatchType + 4×EQ_GroupElement (all four points compared)
    "cand-second-child#4" -> 350L)  // MatchType + first-child match + second-child mismatch

  test("unequal op: exact blessed costs match the recorded baseline") {
    val entries = vectors(AuthoredSigmaPropEq.OpUnequal).hcursor
      .downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)
    unequalBlessedCosts.foreach { case (name, expected) =>
      val e = entries.find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"no entry named '$name'"))
      assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(expected),
        s"$name cost drifted")
    }
  }

  // ── conjecture-mismatch op: the comparer's argument-order asymmetry. Conjecture-LEFT
  //    vs different-variant-right falls through every guard to sys.error → eval THROWS
  //    (reject entries); leaf-LEFT vs conjecture-right returns false at the same cost (4)
  //    as the unequal family's node-type-mismatch class. The asymmetry is the regression
  //    target — a "simplifying" refactor that symmetrizes it breaks consensus.
  test("conjecture op: 2 throws (errored) + 2 leaf-left falses at node-type-mismatch cost") {
    val entries = vectors(AuthoredSigmaPropEq.OpConjecture).hcursor
      .downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)
    assertEquals(entries.flatMap(_.hcursor.get[String]("name").toOption),
      List("cand-vs-dlog#0", "dlog-vs-cand#1", "trivial-vs-dlog#2", "cthreshold-vs-cand#3"))
    val byName = entries.map(e => e.hcursor.get[String]("name").toOption.get -> e.hcursor).toMap

    Seq("cand-vs-dlog#0", "cthreshold-vs-cand#3").foreach { name =>
      val c = byName(name).downField("expected")
      assertEquals(c.get[String]("error").toOption, Some("errored"), s"$name must throw")
      assert(c.downField("value").focus.exists(_.isNull), s"$name value must be null")
      assert(c.downField("cost").focus.exists(_.isNull), s"$name cost must be null")
    }
    Seq("dlog-vs-cand#1", "trivial-vs-dlog#2").foreach { name =>
      val c = byName(name).downField("expected")
      assertEquals(c.downField("value").get[Boolean]("value").toOption, Some(false), s"$name is false")
      assertEquals(c.get[Long]("cost").toOption, Some(4L), s"$name cost drifted")
      assert(c.downField("error").focus.exists(_.isNull), s"$name must not error")
    }
  }
}
