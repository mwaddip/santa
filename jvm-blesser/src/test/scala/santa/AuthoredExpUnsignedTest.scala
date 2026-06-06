package santa

/** Guard + smoke test for the authored `GroupElement.expUnsigned` vectors (ergots vector
  * request P7a-1, v6/authored).
  *
  * Three cases over the secp256k1 generator:
  *   g^1   → g (the generator itself)
  *   g^0   → identity
  *   g^order → identity  (group order collapses to identity, same as g^0)
  *
  * All are V3-gated closed trees (activated=3, ergoTree=3). Value+cost are JVM-blessed
  * via EvalCore. The baseline regression block below is locked in after the first observed
  * run — a drift means the JVM cost model or the GroupElement encoding moved; investigate
  * before re-blessing. */
class AuthoredExpUnsignedTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredExpUnsigned.extract()

  private val Op = "GroupElement.expUnsigned"

  private def entries: List[io.circe.Json] =
    vectors(Op).hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"$Op entries missing/invalid: $e"), identity)

  test("authored under santa-eval/v2 + santa:authored-expunsigned") {
    val c = vectors(Op).hcursor
    assertEquals(c.get[String]("schema").toOption,     Some("santa-eval/v2"),           "schema")
    assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"),   "blessed_by")
    assertEquals(c.get[String]("source").toOption,     Some("santa:authored-expunsigned"), "source")
  }

  test("three entries, expected names, v6 version stamps") {
    val es = entries
    assertEquals(es.size, 3, "entry count")
    assertEquals(es.flatMap(_.hcursor.get[String]("name").toOption),
      List("exp-1#0", "exp-0#1", "exp-order#2"))
    es.foreach { e =>
      val ec   = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(3), s"$name v6 activated")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption,  Some(3), s"$name v6 ergoTree")
    }
  }

  test("all entries produce GroupElement values, positive costs, no error") {
    entries.foreach { e =>
      val ec   = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption,
        Some("GroupElement"), s"$name: result kind must be GroupElement")
      assert(ec.downField("expected").get[Long]("cost").toOption.exists(_ > 0), s"$name: positive cost")
      assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$name: no error")
    }
  }

  test("exp-0 and exp-order both yield the identity point (same bytes_hex)") {
    val byName = entries.map(e =>
      e.hcursor.get[String]("name").toOption.getOrElse("?") ->
      e.hcursor.downField("expected").downField("value").get[String]("bytes_hex").toOption).toMap
    val identity0     = byName.getOrElse("exp-0#1",     fail("no exp-0#1"))
    val identityOrder = byName.getOrElse("exp-order#2", fail("no exp-order#2"))
    assert(identity0.isDefined,     "exp-0 bytes_hex present")
    assert(identityOrder.isDefined, "exp-order bytes_hex present")
    assertEquals(identity0, identityOrder, "g^0 and g^order must encode the same identity point")
  }

  test("summary + write staging") {
    val sb = new StringBuilder(s"\n========== authored GroupElement.expUnsigned vectors ==========\n")
    entries.foreach { e =>
      val c   = e.hcursor
      val n   = c.get[String]("name").getOrElse("?")
      val cost = c.downField("expected").get[Long]("cost").toOption.map(_.toString).getOrElse("—")
      val v   = c.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("?")
      val hex = c.get[String]("tree_bytes_hex").getOrElse("?")
      sb.append(s"  $n: cost=$cost value=$v\n      tree=$hex\n")
    }
    sb.append("================================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "expunsigned-vectors")
    AuthoredExpUnsigned.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(SpecExtract.slug(Op) + ".json")), "staging written")
  }

  // ── regression baseline: exact blessed value+cost+tree, locked in after the first observed run.
  //    A cost drift means the JVM cost model moved — investigate, do not blindly re-bless.
  //    A bytes_hex drift means the GroupElement encoding changed — fatal.
  //    All three cases share cost=906 (Exponentiate.costKind = FixedCost(JitCost(900)) + tree overhead).
  private val genHex      = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  private val identityHex = "000000000000000000000000000000000000000000000000000000000000000000"

  private val baseline: Seq[(String, Long, String, String)] = Seq(
    // (name, cost, bytes_hex of the GroupElement result, tree_bytes_hex)
    ("exp-1#0",
     906L,
     genHex,
     "1b2e02070279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798090101dc07067300017301"),
    ("exp-0#1",
     906L,
     identityHex,
     "1b2d02070279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980900dc07067300017301"),
    ("exp-order#2",
     906L,
     identityHex,
     "1b4d02070279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817980920fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141dc07067300017301"),
  )

  test("blessed value+cost+tree match the recorded baseline") {
    val byName = entries.map(e =>
      e.hcursor.get[String]("name").toOption.getOrElse("?") -> e.hcursor).toMap
    baseline.foreach { case (name, cost, bytesHex, treeHex) =>
      val ec = byName.getOrElse(name, fail(s"no entry named '$name'"))
      assertEquals(ec.downField("expected").get[Long]("cost").toOption, Some(cost), s"$name cost drifted")
      assertEquals(ec.downField("expected").downField("value").get[String]("bytes_hex").toOption,
        Some(bytesHex), s"$name value bytes_hex drifted")
      assertEquals(ec.get[String]("tree_bytes_hex").toOption, Some(treeHex), s"$name tree drifted")
    }
  }
}
