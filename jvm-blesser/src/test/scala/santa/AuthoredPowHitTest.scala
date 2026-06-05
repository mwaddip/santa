package santa

/** Guard + smoke test for the authored `Global.powHit` k-parameterized vectors — the k≠32
  * value+cost coverage and the require-boundary reject arm (ergots vector request
  * `prompts/ergots-powhit-vectors.md`, v6 P5c).
  *
  * The only JVM-blessed powHit *value* vector (LanguageSpecificationV6, k=32) coincides with the
  * header-verify path's hardcoded k=32 — so a correct k=32 result does not exercise the general
  * `(0 until k)` index generation. These pin it for k ∈ {2,16,31} (value+cost) plus the
  * `require(k∈[2,32], N≥16)` boundary (k=1 / k=33 / N=15 → eval-fail). Exact value/cost/tree
  * anchors are locked in the baseline below once observed (the regression guard). */
class AuthoredPowHitTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredPowHit.extract()

  private val OpValue  = "Global.powHit varying k"
  private val OpReject = "Global.powHit require boundary"

  private def entries(op: String): List[io.circe.Json] =
    vectors(op).hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"$op entries missing/invalid: $e"), identity)

  test("both ops authored under santa-eval/v2 + santa:authored-powhit") {
    assertEquals(vectors.keySet, Set(OpValue, OpReject))
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-eval/v2"), s"$op schema")
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"), s"$op blessed_by")
      assertEquals(c.get[String]("source").toOption, Some("santa:authored-powhit"), s"$op source")
    }
  }

  test("value: three k≠32 entries, UnsignedBigInt result, positive cost, no error") {
    val es = entries(OpValue)
    assertEquals(es.flatMap(_.hcursor.get[String]("name").toOption),
      List("k=2#0", "k=16#1", "k=31#2"))
    es.foreach { e =>
      val ec   = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assert(ec.get[String]("script").toOption.exists(_.contains("powHit")), s"$name: script mentions powHit")
      assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(3), s"$name v6 activated")
      assertEquals(ec.downField("version").get[Int]("ergoTree").toOption, Some(3), s"$name v6 ergoTree")
      assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption,
        Some("UnsignedBigInt"), s"$name: result kind")
      assert(ec.downField("expected").get[Long]("cost").toOption.exists(_ > 0), s"$name: positive cost")
      assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$name: no error")
    }
  }

  test("value: cost delta is exactly powHit's (k+1)·7 — tree overhead is k-independent") {
    val cost = entries(OpValue).map { e =>
      val ec = e.hcursor
      ec.get[String]("name").toOption.get -> ec.downField("expected").get[Long]("cost").toOption.get
    }.toMap
    // FixedCost(k) = 500 + (k+1)·(⌊(7+8+4)/128⌋+1)·7 = 500 + 7(k+1); trees differ only in IntConstant(k),
    // so the surrounding cost cancels and the delta is the pure powHit k-coefficient.
    assertEquals(cost("k=16#1") - cost("k=2#0"), 98L, "Δcost(16,2) = 7·14")
    assertEquals(cost("k=31#2") - cost("k=16#1"), 105L, "Δcost(31,16) = 7·15")
    assert(cost("k=2#0") > 521L, s"k=2 total ${cost("k=2#0")} must exceed the bare powHit-op cost (521)")
  }

  test("reject: three require-boundary entries, all parse-ok / eval-fail (errored)") {
    val es = entries(OpReject)
    assertEquals(es.flatMap(_.hcursor.get[String]("name").toOption),
      List("reject-k=1#0", "reject-k=33#1", "reject-N=15#2"))
    es.foreach { e =>
      val ec   = e.hcursor
      val name = ec.get[String]("name").toOption.getOrElse("?")
      assert(ec.get[String]("script").toOption.exists(_.contains("powHit")), s"$name: script mentions powHit")
      assertEquals(ec.downField("expected").downField("value").focus.map(_.noSpaces), Some("null"), s"$name: null value")
      assertEquals(ec.downField("expected").downField("cost").focus.map(_.noSpaces), Some("null"), s"$name: null cost")
      assertEquals(ec.downField("expected").get[String]("error").toOption, Some("errored"), s"$name: errored")
    }
  }

  test("summary + write staging") {
    val sb = new StringBuilder("\n========== authored powHit k-parameterized vectors ==========\n")
    Seq(OpValue, OpReject).foreach { op =>
      sb.append(s"-- $op --\n")
      entries(op).foreach { e =>
        val c    = e.hcursor
        val n    = c.get[String]("name").getOrElse("?")
        val cost = c.downField("expected").get[Long]("cost").toOption.map(_.toString).getOrElse("—")
        val v    = c.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("?")
        val hex  = c.get[String]("tree_bytes_hex").getOrElse("?")
        sb.append(s"  $n: cost=$cost value=$v\n      tree=$hex\n")
      }
    }
    sb.append("=============================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "powhit-vectors")
    AuthoredPowHit.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(SpecExtract.slug(OpValue) + ".json")), "value staging written")
    assert(java.nio.file.Files.exists(outDir.resolve(SpecExtract.slug(OpReject) + ".json")), "reject staging written")
  }

  // ── regression baseline: exact blessed value+cost+tree, locked after the first observed run.
  //    A change means the JVM cost model, a powHit hit value, or the tree shape moved —
  //    investigate, do not blindly re-bless. The three value trees differ in exactly one byte
  //    (IntConstant(k): 0404/0420/043e); reject trees swap k (0402/0442) or N (…041e…).
  private val msgTail = "0e070a101b8c6a4f2e0e08000000000000002c0e04000000000480808001dc6a08dd0573007301730273037304"

  private val valueBaseline: Seq[(String, Long, String, String)] = Seq(
    ("k=2#0",  535L,
      """{"kind":"UnsignedBigInt","value":"35274035984502409775838491832016077028975555290652562361403236742579897229335"}""",
      "1b30050404" + msgTail),
    ("k=16#1", 633L,
      """{"kind":"UnsignedBigInt","value":"86883723630804049999276477318408104291833135337669321564636939912790977587966"}""",
      "1b30050420" + msgTail),
    ("k=31#2", 738L,
      """{"kind":"UnsignedBigInt","value":"35399430143882412612100281269753553760506747322394340848280571876306312093601"}""",
      "1b3005043e" + msgTail))

  private val rejectBaseline: Seq[(String, String)] = Seq(
    ("reject-k=1#0",  "1b30050402" + msgTail),
    ("reject-k=33#1", "1b30050442" + msgTail),
    ("reject-N=15#2", "1b2d0504040e070a101b8c6a4f2e0e08000000000000002c0e0400000000041edc6a08dd0573007301730273037304"))

  test("value: blessed value+cost+tree match the recorded baseline") {
    val byName = entries(OpValue).map(e => e.hcursor.get[String]("name").toOption.getOrElse("?") -> e.hcursor).toMap
    valueBaseline.foreach { case (name, cost, value, hex) =>
      val ec = byName.getOrElse(name, fail(s"no entry named '$name'"))
      assertEquals(ec.downField("expected").get[Long]("cost").toOption, Some(cost), s"$name cost drifted")
      assertEquals(ec.downField("expected").downField("value").focus.map(_.noSpaces), Some(value), s"$name value drifted")
      assertEquals(ec.get[String]("tree_bytes_hex").toOption, Some(hex), s"$name tree drifted")
    }
  }

  test("reject: tree bytes match the recorded baseline") {
    val byName = entries(OpReject).map(e => e.hcursor.get[String]("name").toOption.getOrElse("?") -> e.hcursor).toMap
    rejectBaseline.foreach { case (name, hex) =>
      val ec = byName.getOrElse(name, fail(s"no entry named '$name'"))
      assertEquals(ec.get[String]("tree_bytes_hex").toOption, Some(hex), s"$name tree drifted")
    }
  }
}
