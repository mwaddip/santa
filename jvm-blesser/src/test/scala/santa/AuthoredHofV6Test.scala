package santa

/** Guard + smoke for the authored v6 HOF vectors (ergots P6, prompts/ergots-v6-hof-vectors.md).
  * Accept arms: ask 1 (FunDef 0xd7 at v0/v2/v3 → Int 7), ask 4 (currying add(3)(1) → Int 4),
  * ask 2 (function in Coll[SFunc], fs(0)(5) → Int 6). authoredEntry throws if any rejects.
  * Exact cost/tree anchors locked once observed. */
class AuthoredHofV6Test extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredHofV6.extract()

  private val OpFunDef   = "HOF FunDef polymorphic identity"
  private val OpCurrying = "HOF currying Apply of Apply"
  private val OpCollFunc = "HOF function in Coll of SFunc"

  private def entries(op: String): List[io.circe.Json] =
    vectors(op).hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"$op entries missing/invalid: $e"), identity)

  private def assertIntResult(op: String, name: String, want: Int): Unit = {
    val ec = entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"$op: no entry '$name'")).hcursor
    assertEquals(ec.downField("expected").downField("value").get[String]("kind").toOption, Some("Int"), s"$op/$name kind")
    assertEquals(ec.downField("expected").downField("value").get[Int]("value").toOption, Some(want), s"$op/$name value")
    assert(ec.downField("expected").get[Long]("cost").toOption.exists(_ > 0), s"$op/$name positive cost")
    assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"), s"$op/$name no error")
  }

  test("all accept ops authored under santa:authored-hof-v6") {
    assertEquals(vectors.keySet, Set(OpFunDef, OpCurrying, OpCollFunc))
    vectors.foreach { case (op, env) =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-eval/v2"), s"$op schema")
      assertEquals(env.hcursor.get[String]("source").toOption, Some("santa:authored-hof-v6"), s"$op source")
    }
  }

  test("ask 1 — FunDef (0xd7) deserializes + evaluates to Int 7 at v6") {
    assertEquals(entries(OpFunDef).flatMap(_.hcursor.get[String]("name").toOption), List("v3#0"))
    assertIntResult(OpFunDef, "v3#0", 7)
  }

  test("ask 4 — currying add(3)(1) evaluates to Int 4") {
    assertIntResult(OpCurrying, "add(3)(1)#0", 4)
  }

  test("ask 2 — function in Coll[SFunc] fs(0)(5) evaluates to Int 6") {
    assertIntResult(OpCollFunc, "fs(0)(5)#0", 6)
  }

  // ── regression baseline: exact blessed costs, locked after the first observed run. A change means
  //    the JVM cost model or the tree shape moved — investigate, do not blindly re-bless.
  test("blessed costs match the recorded baseline") {
    def cost(op: String, name: String): Long =
      entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"$op: no entry '$name'")).hcursor.downField("expected").get[Long]("cost").toOption.get
    assertEquals(cost(OpFunDef, "v3#0"), 58L, "FunDef v3")
    assertEquals(cost(OpCurrying, "add(3)(1)#0"), 119L, "currying")
    assertEquals(cost(OpCollFunc, "fs(0)(5)#0"), 130L, "Coll[SFunc]")
  }

  test("summary + write staging") {
    val sb = new StringBuilder("\n========== authored v6 HOF ==========\n")
    Seq(OpFunDef, OpCurrying, OpCollFunc).foreach { op =>
      sb.append(s"-- $op --\n")
      entries(op).foreach { e =>
        val c = e.hcursor
        sb.append(s"  ${c.get[String]("name").getOrElse("?")}: " +
          s"cost=${c.downField("expected").get[Long]("cost").toOption.map(_.toString).getOrElse("—")} " +
          s"value=${c.downField("expected").downField("value").focus.map(_.noSpaces).getOrElse("?")}\n" +
          s"      tree=${c.get[String]("tree_bytes_hex").getOrElse("?")}\n")
      }
    }
    println(sb.append("=====================================\n").toString)
    val outDir = java.nio.file.Paths.get("target", "hof-v6-vectors")
    AuthoredHofV6.writeVectors(vectors, outDir)
    Seq(OpFunDef, OpCurrying, OpCollFunc).foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(SpecExtract.slug(op) + ".json")), s"$op staging written")
    }
  }
}
