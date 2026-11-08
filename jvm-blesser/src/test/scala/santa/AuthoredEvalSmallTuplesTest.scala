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
    // 9 cases + the (1)==(1) twin
    assertEquals(entries(B.Op).size, 10)
    assertEquals(entries(B.OpSerialize).size, 2)
  }

  test("three refusals, and the arms that produce them") {
    import SmallTupleTrees._
    // the TypeWrite and CountBound cases error, and so do the three expression cases
    Seq("const_one_item_type-", "const_zero_item_type-", "tuple_expr_one_item-",
        "tuple_expr_zero_item-", "tuple_expr_127_items-", "tuple_expr_128_items-",
        "const_one_item_type_eq_self-").foreach { frag =>
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

  test("the one-item EQ twin proves the refusal is about arity, not emptiness") {
    // `()` == `()` and `(1)` == `(1)` both error, and they are DIFFERENT trees — if a later
    // change collapsed them, the family would claim a distinction it no longer draws.
    val zero = one(B.Op, "const_zero_item_type-")
    val oneI = one(B.Op, "const_one_item_type_eq_self-")
    assert(errored(zero) && errored(oneI))
    assertNotEquals(str(zero, "tree_bytes_hex"), str(oneI, "tree_bytes_hex"))
    assert(str(oneI, "tree_bytes_hex").contains(SmallTupleTrees.OneItemConst))
    assert(str(zero, "tree_bytes_hex").contains(SmallTupleTrees.ZeroItemConst))
  }

  test("Global.serialize on a one-item tuple WORKS, and costs more than the pair twin") {
    val oneI = one(B.OpSerialize, "serialize-one-item-tuple-accept")
    val pair = one(B.OpSerialize, "serialize-pair-control-accept")
    assert(!errored(oneI), "a one-item tuple serializes: DataSerializer writes items, not the type")
    assert(!errored(pair))
    assertEquals(cost(oneI), Some(65L))
    assertEquals(cost(pair), Some(51L))
    // the two trees differ in exactly the constant
    assertEquals(str(oneI, "tree_bytes_hex").length - str(pair, "tree_bytes_hex").length,
      SmallTupleTrees.OneItemConst.length - SmallTupleTrees.PairConst.length)
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
