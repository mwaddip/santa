package santa

/** Guard + smoke test for the authored Coll-equality compared-count cost vectors.
  * DataValueComparer charges the per-item eq cost on the COMPARED prefix (short-circuit),
  * not the full operand length — so a same-length Coll-eq that mismatches early costs
  * strictly less than one that mismatches late or compares equal. Asserts that
  * short-circuit ORDERING (the load-bearing evidence; a full-length-charging impl
  * flattens it), value correctness, and schema/provenance; prints the blessed costs;
  * writes staging; and locks the exact blessed costs as a regression baseline. */
class AuthoredCollEqCostTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredCollEqCost.extract()

  private def entries(op: String): List[io.circe.Json] =
    vectors(op).hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)
  private def byName(op: String): Map[String, io.circe.ACursor] =
    entries(op).map(e => e.hcursor.get[String]("name").toOption.get -> e.hcursor).toMap
  private def cost(c: io.circe.ACursor): Long =
    c.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
  private def boolValue(c: io.circe.ACursor): Option[Boolean] =
    c.downField("expected").downField("value").get[Boolean]("value").toOption

  test("extract emits the two Coll-eq cost ops with well-formed envelopes") {
    assertEquals(vectors.keySet, Set(AuthoredCollEqCost.OpCoa, AuthoredCollEqCost.OpGeneric))
    Seq(AuthoredCollEqCost.OpCoa, AuthoredCollEqCost.OpGeneric).foreach { op =>
      val env = vectors(op).hcursor
      assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"), s"$op schema")
      assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"), s"$op blessed_by")
      assertEquals(env.get[String]("source").toOption, Some("santa:authored-coll-eq-cost"), s"$op source")
    }
  }

  test("COA: values correct + chunk-granular compared-count (short-circuit live)") {
    val n = byName(AuthoredCollEqCost.OpCoa)
    assertEquals(entries(AuthoredCollEqCost.OpCoa).flatMap(_.hcursor.get[String]("name").toOption),
      List("equal-len512#0", "mismatch-at-0#1", "mismatch-at-150#2", "mismatch-at-300#3", "mismatch-at-450#4"))
    assertEquals(boolValue(n("equal-len512#0")), Some(true), "x == x is true")
    Seq("mismatch-at-0#1", "mismatch-at-150#2", "mismatch-at-300#3", "mismatch-at-450#4").foreach { m =>
      assertEquals(boolValue(n(m)), Some(false), s"$m is false")
    }
    // The short-circuit evidence: same-length (512) operands, but cost rises as the mismatch
    // moves into later chunks — strict across the full range, weakly monotone between (two
    // mismatches in the same chunk tie). A full-length-charging impl flattens these to one value.
    val staircase = Seq("mismatch-at-0#1", "mismatch-at-150#2", "mismatch-at-300#3", "mismatch-at-450#4").map(name => cost(n(name)))
    assert(staircase.head < staircase.last,
      s"COA early-vs-late: ${staircase.head} !< ${staircase.last} (compared-count not charged)")
    staircase.sliding(2).foreach { w => assert(w(0) <= w(1), s"COA cost not monotone: ${w(0)} !<= ${w(1)}") }
    assert(cost(n("equal-len512#0")) >= staircase.last,
      s"equal ${cost(n("equal-len512#0"))} !>= mismatch@450 ${staircase.last}")
  }

  test("generic: values correct + OUTER per-element compared-count short-circuit live") {
    val n = byName(AuthoredCollEqCost.OpGeneric)
    assertEquals(entries(AuthoredCollEqCost.OpGeneric).flatMap(_.hcursor.get[String]("name").toOption),
      List("equal-len8#0", "mismatch-at-0#1", "mismatch-at-3#2", "mismatch-at-7#3"))
    assertEquals(boolValue(n("equal-len8#0")), Some(true))
    Seq("mismatch-at-0#1", "mismatch-at-3#2", "mismatch-at-7#3").foreach { m =>
      assertEquals(boolValue(n(m)), Some(false), s"$m is false")
    }
    // Generic eq is smooth per-element → strictly increasing with the outer mismatch index.
    assert(cost(n("mismatch-at-0#1")) < cost(n("mismatch-at-3#2")),
      s"outer@0 ${cost(n("mismatch-at-0#1"))} !< outer@3 ${cost(n("mismatch-at-3#2"))}")
    assert(cost(n("mismatch-at-3#2")) < cost(n("mismatch-at-7#3")),
      s"outer@3 ${cost(n("mismatch-at-3#2"))} !< outer@7 ${cost(n("mismatch-at-7#3"))}")
    assert(cost(n("equal-len8#0")) >= cost(n("mismatch-at-7#3")),
      s"equal ${cost(n("equal-len8#0"))} !>= outer@7 ${cost(n("mismatch-at-7#3"))}")
  }

  test("print blessed costs + write staging vectors") {
    val sb = new StringBuilder("\n========== authored Coll-eq compared-count cost ==========\n")
    Seq(AuthoredCollEqCost.OpCoa, AuthoredCollEqCost.OpGeneric).foreach { op =>
      sb.append(s"  [$op]\n")
      entries(op).foreach { e =>
        val nm = e.hcursor.get[String]("name").getOrElse("?")
        val ct = e.hcursor.downField("expected").get[Long]("cost").getOrElse(-1L)
        sb.append(s"    $nm: cost=$ct\n")
      }
    }
    sb.append("==========================================================\n")
    println(sb.toString)
    val outDir = java.nio.file.Paths.get("target", "coll-eq-cost-vectors")
    AuthoredCollEqCost.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("Coll.eq_compared_count_coa.json")),
      "staging Coll.eq_compared_count_coa.json not written")
    assert(java.nio.file.Files.exists(outDir.resolve("Coll.eq_compared_count_generic.json")),
      "staging Coll.eq_compared_count_generic.json not written")
  }

  // ── regression baseline: exact blessed costs, locked after the first observed run.
  //    A change means the JVM cost model or a construction moved — investigate, do not
  //    blindly re-bless (these are what sigma-rust / ergots verify compared-count against).
  //    COA: +2 per ~128-element chunk (20/22/24/26). generic: +20 per outer element (87→227).
  private val baseline: Seq[(String, String, Long)] = Seq(
    (AuthoredCollEqCost.OpCoa,     "equal-len512#0",    26L),
    (AuthoredCollEqCost.OpCoa,     "mismatch-at-0#1",   20L),
    (AuthoredCollEqCost.OpCoa,     "mismatch-at-150#2", 22L),
    (AuthoredCollEqCost.OpCoa,     "mismatch-at-300#3", 24L),
    (AuthoredCollEqCost.OpCoa,     "mismatch-at-450#4", 26L),
    (AuthoredCollEqCost.OpGeneric, "equal-len8#0",      227L),
    (AuthoredCollEqCost.OpGeneric, "mismatch-at-0#1",   87L),
    (AuthoredCollEqCost.OpGeneric, "mismatch-at-3#2",   147L),
    (AuthoredCollEqCost.OpGeneric, "mismatch-at-7#3",   227L))

  test("blessed costs match the recorded baseline") {
    baseline.foreach { case (op, name, expected) =>
      val c = byName(op).getOrElse(name, fail(s"no entry $op/$name"))
      assertEquals(cost(c), expected, s"$op/$name cost drifted")
    }
  }
}
