package santa

/** Guard + smoke test for the authored `atLeast` degenerate-bound vectors (sigma-rust
  * fix/atleast-degenerate-bound): the JVM reduces degenerate bounds to trivial props, never
  * errors. Asserts the seven entries are authored and well-formed (each yields a SigmaProp,
  * positive cost, no error), prints blessed value/cost/tree, writes staging. Exact value/cost/tree
  * anchors are locked below once observed (the regression baseline). */
class AuthoredAtLeastTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredAtLeast.extract()

  private val Op = "atLeast with a degenerate bound"
  private val names = List(
    "bound-0-TrueProp#0", "bound-neg1-TrueProp#1", "bound-1-COR#2", "bound-2-CAND#3",
    "bound-3-gt-size-False#4", "bound-256-gt-255-False#5", "empty-input-False#6")

  test("atLeast degenerate-bound authored; seven entries well-formed (SigmaProp, cost>0, no error)") {
    assertEquals(vectors.keySet, Set(Op))
    val env = vectors(Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.get[String]("source").toOption, Some("santa:authored-atleast"))

    val entries = env.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)
    assertEquals(entries.flatMap(_.hcursor.get[String]("name").toOption), names)

    entries.foreach { e =>
      val ec = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assert(ec.get[String]("script").toOption.exists(_.contains("atLeast")), s"$name: script mentions atLeast")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption, Some(2), s"$name v5 ergoTree")
      assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption,
        Some("SigmaProp"), s"$name: result is a SigmaProp")
      assert(ec.downField("expected").get[Long]("cost").toOption.exists(_ > 0), s"$name: cost > 0")
      assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$name no error")
    }
  }

  test("summary + write staging") {
    val entries = vectors(Op).hcursor.downField("entries").as[List[io.circe.Json]].getOrElse(Nil)
    val sb = new StringBuilder("\n========== authored atLeast degenerate-bound vectors ==========\n")
    entries.foreach { e =>
      val c    = e.hcursor
      val n    = c.get[String]("name").getOrElse("?")
      val cost = c.downField("expected").get[Long]("cost").getOrElse(-1L)
      val raw  = c.downField("expected").downField("value").get[String]("raw_hex").getOrElse("?")
      val hex  = c.get[String]("tree_bytes_hex").getOrElse("?")
      sb.append(s"  $n: cost=$cost  result_raw=$raw\n      tree=$hex\n")
    }
    sb.append("===============================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "atleast-vectors")
    AuthoredAtLeast.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(SpecExtract.slug(Op) + ".json")),
      "staging vector was not written")
  }

  // ── regression baseline: blessed cost + result SigmaProp (opcode prefix; full children are the
  //    two gen-based props, deterministic). A change means the JVM reduction or cost model moved —
  //    investigate, don't blindly re-bless. TrueProp=d3, FalseProp=d2, COR=97.., CAND=96..
  private val baseline: Seq[(String, Long, String)] = Seq(
    ("bound-0-TrueProp#0",       46L, "d3"),    // bound ≤ 0 → TrueProp
    ("bound-neg1-TrueProp#1",    46L, "d3"),    // bound < 0 → TrueProp   (✗ pre-fix: i32→u8 error)
    ("bound-1-COR#2",            46L, "9702"),  // → COR(p1, p2)
    ("bound-2-CAND#3",           46L, "9602"),  // bound == size → CAND   (satisfiable, NOT false)
    ("bound-3-gt-size-False#4",  46L, "d2"),    // bound > size → FalseProp (✗ the wedge boundary)
    ("bound-256-gt-255-False#5", 46L, "d2"),    // bound > 255 → FalseProp (✗ not an error)
    ("empty-input-False#6",      44L, "d2"))    // empty → FalseProp        (✗ the block-184137 shape)

  test("blessed cost + reduction match the recorded baseline (degenerate bounds → trivial props)") {
    val entries = vectors(Op).hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries: $e"), identity)
    baseline.foreach { case (name, cost, rawPrefix) =>
      val ec = entries.find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"no entry '$name'")).hcursor
      assertEquals(ec.downField("expected").get[Long]("cost").toOption, Some(cost), s"$name cost drifted")
      val raw = ec.downField("expected").downField("value").get[String]("raw_hex").toOption.getOrElse("")
      assert(raw.startsWith(rawPrefix), s"$name reduction drifted: got '$raw', want prefix '$rawPrefix'")
    }
  }
}
