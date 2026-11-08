package santa

import io.circe.Json

/** Anchors the small-tuples TRANSACTION vectors. `extract()` records whatever ergo-core says, so
  * unlike the wire blesser it does not fail loud on a surprise — which is exactly why this test
  * has to assert the verdicts the source predicts. It also pins the two tier limits that shaped
  * the family (the three absent cases, and the absent register spend), because those are the
  * facts a later session would otherwise have to rediscover by making rudolph panic. */
class AuthoredTxSmallTuplesTest extends munit.FunSuite {
  private val B = AuthoredTxSmallTuples
  private lazy val blessed = B.extract()

  private def entries: List[Json] =
    blessed.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def named(frag: String): List[Json] = entries.filter(e => str(e, "name").contains(frag))
  private def one(frag: String): Json = named(frag) match {
    case List(e) => e
    case other   => fail(s"want exactly one entry matching '$frag', got ${other.map(str(_, "name"))}")
  }

  test("the tier can express 7 of the 10 wire cases, twice each, plus the register output") {
    import SmallTupleTrees._
    assertEquals(B.expressible.size, 7)
    assertEquals(B.expressible.count(_.arm == ExprAccept), 3)
    assertEquals(B.expressible.count(_.arm == Control), 4)
    assert(!B.expressible.exists(_.arm == TypeWrite),
      "a 0/1-item tuple TYPE cannot be a transaction vector: the parse throws and txEntry reports " +
      "panicked, so rudolph would panic on its own vector")
    assert(!B.expressible.exists(_.arm == CountBound),
      "a 128-item tuple cannot be one either: the carrier's parse throws")
    assertEquals(entries.size, 7 * 2 * 2 + 1)
    assertEquals(named("small-tuple-output-").size, 15, "14 tree outputs + the register")
    assertEquals(named("small-tuple-spend-").size, 14)
  }

  test("EVERY output is valid — that is what makes the output side the separating one") {
    named("small-tuple-output-").foreach { e =>
      assert(valid(e), s"${str(e, "name")}: the JVM accepts every expressible case as an output")
      assertEquals(cost(e), Some(12105L), s"${str(e, "name")}: one input, one output, no script work")
    }
  }

  test("the spends: the three expression cases FAIL, the four controls pass") {
    SmallTupleTrees.cases.filter(_.arm == SmallTupleTrees.ExprAccept).foreach { c =>
      named(s"small-tuple-spend-${c.slug}-").foreach { e =>
        assert(!valid(e), s"${str(e, "name")}: Tuple.eval refuses any count but two")
        assertEquals(cost(e), None, s"${str(e, "name")}: an invalid spend has no cost")
        assert(e.hcursor.downField("expected").get[String]("reason").toOption.exists(_.nonEmpty),
          s"${str(e, "name")}: must say why")
      }
    }
    // the two constant-pair controls spend at 12103, the two-item expression at 12105
    Seq("spend-const_pair_type_control", "spend-const_pair_eq_control").foreach { frag =>
      named(frag).foreach { e =>
        assert(valid(e), str(e, "name")); assertEquals(cost(e), Some(12103L), str(e, "name"))
      }
    }
    named("spend-tuple_expr_two_items_control").foreach { e =>
      assert(valid(e), str(e, "name")); assertEquals(cost(e), Some(12105L), str(e, "name"))
    }
  }

  test("the triple is the reachable half of the non-pair SelectField divergence") {
    // A THREE-item tuple type IS writable, so unlike the one-item row this spend is a box that
    // can really exist — and it fails for the same reason: toDslTuple hands back a raw Coll[Any]
    // for ANY non-pair, so SelectField meets a Coll where its type says a tuple.
    named("spend-const_triple_type").foreach { e =>
      assert(!valid(e), s"${str(e, "name")}: SelectField on a non-pair constant fails")
      assertEquals(cost(e), None, str(e, "name"))
    }
    named("output-const_triple_type").foreach(e => assert(valid(e), str(e, "name")))
  }

  test("the register output is valid, and has no spend twin") {
    val e = one("output-register_nested_tuple_expr")
    assert(valid(e)); assertEquals(cost(e), Some(12105L))
    assertEquals(named("spend-register").size, 0,
      "no script the JVM can write reads that register — ((Int),Int) is the only type getReg " +
      "accepts and TypeSerializer cannot write it")
  }

  test("both tree versions appear on both sides, so neither pair can collapse unnoticed") {
    Seq("small-tuple-output-", "small-tuple-spend-").foreach { side =>
      val rows = named(side).filter(e => !str(e, "name").contains("register"))
      assertEquals(rows.count(e => str(e, "name").contains("-v0#")), 7, s"$side v0")
      assertEquals(rows.count(e => str(e, "name").contains("-v3#")), 7, s"$side v3")
    }
  }

  test("every entry carries the shared rent context and the authored source") {
    entries.foreach { e =>
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source), str(e, "name"))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("context").get[Int]("height").toOption,
        Some(AuthoredTxStorageRent.H))
      assert(e.hcursor.downField("input_boxes_hex").as[List[String]].fold(_ => Nil, identity).size == 1,
        str(e, "name"))
    }
  }

  test("envelope: santa-transaction/v1, blessed by ergo-core 6.0.7") {
    val c = blessed.hcursor
    assertEquals(c.get[String]("schema").toOption, Some("santa-transaction/v1"))
    assertEquals(c.get[String]("op").toOption, Some("small-tuples"))
    assertEquals(c.get[String]("blessed_by").toOption,
      Some("jvm:ergo-core-6.0.7-validateStateful"))
  }

  test("write vectors") {
    B.writeVectors(java.nio.file.Paths.get("..", "vectors").normalize())
    assert(java.nio.file.Files.exists(
      java.nio.file.Paths.get("..", "vectors").normalize().resolve(B.Path)))
  }
}
