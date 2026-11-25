package santa

import io.circe.Json

/** Anchors the small-tuples EVAL family. `extract()` already fails loud if a reject evaluates or
  * an accept does not, so this test pins what a re-derivation cannot see: which refusals are
  * supposed to be distinct mechanisms, the costs the controls set, and the one row that WORKS. */
class AuthoredEvalSmallTuplesTest extends munit.FunSuite {
  private val B = AuthoredEvalSmallTuples
  private lazy val vectors = B.extract()

  private def entries(op: String): List[Json] = vectors(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def one(op: String, frag: String): Json = entries(op).filter(e => str(e, "name").contains(frag)) match {
    case List(e) => e
    case other   => fail(s"want one entry matching '$frag', got ${other.map(str(_, "name"))}")
  }

  test("the triple is deliberately absent — Tuple.non_pair_type_check already covers it") {
    assertEquals(B.evalCases.size, 9)
    assert(!B.evalCases.exists(_.slug == "const_triple_type"),
      "valdef-triple-errored#2 of Tuple.non_pair_type_check is the SelectField-on-a-triple site")
    // 9 cases + the (1)==(1) twin, less the three retired below; the serialize pair, less its one-item row
    assertEquals(entries(B.Op).size, 7)
    assertEquals(entries(B.OpSerialize).size, 1)
  }

  test("the four rows no box can carry are retired, and the rows that stay keep their names") {
    val names = (entries(B.Op) ++ entries(B.OpSerialize)).map(str(_, "name")).toSet
    assertEquals(B.Retired, Set("const_one_item_type-errored#0", "const_zero_item_type-errored#1",
      "const_one_item_type_eq_self-errored#9", "serialize-one-item-tuple-accept#0"))
    assert(B.Retired.forall(n => !names(n)), s"retired rows must be gone: ${B.Retired.intersect(names)}")
    // names are taken BEFORE the retired rows are dropped: what stays is named as committed
    Seq("tuple_expr_one_item-errored#2", "const_pair_type_control-accept#",
        "serialize-pair-control-accept#1").foreach(n => assert(names.exists(_.startsWith(n)), s"$n must stay"))
  }

  test("three refusals, and the arms that produce them") {
    import SmallTupleTrees._
    // the CountBound cases error, and so do the three expression cases (the TypeWrite rows are spends now:
    // AuthoredTxUnwritableTree)
    Seq("tuple_expr_one_item-", "tuple_expr_zero_item-", "tuple_expr_127_items-",
        "tuple_expr_128_items-").foreach { frag =>
      val e = one(B.Op, frag)
      assert(errored(e), s"${str(e, "name")}: must error at eval")
      assertEquals(cost(e), None, s"${str(e, "name")}: an errored eval carries no cost")
    }
    // and the controls evaluate, with the costs the probe measured
    assertEquals(cost(one(B.Op, "const_pair_type_control-")), Some(38L))
    assertEquals(cost(one(B.Op, "const_pair_eq_control-")), Some(35L))
    assertEquals(cost(one(B.Op, "tuple_expr_two_items_control-")), Some(58L))
    Seq("const_pair_type_control-", "const_pair_eq_control-", "tuple_expr_two_items_control-")
      .foreach(f => assert(!errored(one(B.Op, f)), f))
  }

  test("Global.serialize on the pair twin keeps its cost (the one-item row is a spend now)") {
    val pair = one(B.OpSerialize, "serialize-pair-control-accept")
    assert(!errored(pair))
    assertEquals(cost(pair), Some(51L))
  }

  test("every tree is the v3 size-flagged form, at activated 3") {
    (entries(B.Op) ++ entries(B.OpSerialize)).foreach { e =>
      assert(str(e, "tree_bytes_hex").startsWith("0b"), s"${str(e, "name")}: size-flagged v3 header")
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
    }
  }

  test("envelopes: the 6.0.7 stamp, not SpecExtract's hardcoded 6.0.3") {
    vectors.foreach { case (op, env) =>
      assertEquals(env.hcursor.get[String]("blessed_by").toOption,
        Some("jvm:sigma-state-6.0.7"), op)
      assertEquals(env.hcursor.get[String]("op").toOption, Some(op))
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json")
    }
  }
}
