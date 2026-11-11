package santa

import io.circe.Json

/** Anchors the non-pair-tuple SPEND vectors. `extract()` already requires each verdict to be the
  * one the measurement found, so this pins what the family is FOR:
  *
  *   1. the three valid spends and their costs (12104 / 12104 / 12103) — boxes anyone can create
  *      that an implementation holding tuples in a dedicated node cannot follow;
  *   2. the two invalid ones, which are the opposite risk (a creatable box no one can spend);
  *   3. the cross-tier pair: the keystone's tree must be the SAME bytes the wire family accepts,
  *      and the deserialize row must cost more than its inline twin by the substitution.
  */
class AuthoredTxNonPairTupleSpendTest extends munit.FunSuite {
  private val B = AuthoredTxNonPairTupleSpend
  private lazy val blessed = B.extract()
  private def entries: List[Json] =
    blessed.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def boxes(e: Json): String =
    e.hcursor.downField("input_boxes_hex").as[List[String]].fold(_ => Nil, identity).mkString
  private def row(slug: String): Json = entries.filter(e => str(e, "name").contains(slug)) match {
    case List(e) => e
    case other   => fail(s"want one $slug, got ${other.map(str(_, "name"))}")
  }

  test("7 spends: 5 valid (3 shapes + deserialize + control), 2 invalid") {
    assertEquals(entries.size, 7)
    assertEquals(entries.count(valid), 5)
    Seq("size_eq", "exists", "and_bool", "deserialize_from_var", "control_true")
      .foreach(s => assert(valid(row(s)), s))
    Seq("at_least", "eq_any_operands").foreach(s => assert(!valid(row(s)), s))
  }

  test("the three shapes spend, at the costs measured") {
    assertEquals(cost(row("size_eq")), Some(12104L))
    assertEquals(cost(row("exists")), Some(12104L))
    assertEquals(cost(row("and_bool")), Some(12103L))
    assertEquals(cost(row("control_true")), Some(12105L))
  }

  test("the deserialize route costs the substitution over its inline twin") {
    val d = cost(row("deserialize_from_var")).getOrElse(fail("cost"))
    val inline = cost(row("size_eq")).getOrElse(fail("cost"))
    assertEquals(d, 12138L)
    assertEquals(d - inline, 34L, "the +34 is the deserialize substitution; if it moves, say so")
    // the row must actually carry the script in a context variable, not inline
    assert(str(row("deserialize_from_var"), "tx_bytes_hex").contains(B.InnerScript),
      "the inner script must ride in the transaction's context extension")
    assert(!boxes(row("deserialize_from_var")).contains(B.InnerScript),
      "and NOT in the box's own script")
  }

  test("the two invalid rows have no cost and say why") {
    Seq("at_least", "eq_any_operands").foreach { s =>
      assertEquals(cost(row(s)), None, s)
      assert(row(s).hcursor.downField("expected").get[String]("reason").toOption.exists(_.nonEmpty), s)
    }
  }

  test("THE CROSS-TIER PAIR: the keystone's tree is the one the wire family accepts") {
    val tree = "0b18d193b248040404020406040000b248040404020406040200"
    assert(boxes(row("eq_any_operands")).contains(tree),
      "the invalid spend must carry the exact tree the wire tier round-trips")
    assert(str(row("eq_any_operands"), "description").contains("CROSS-TIER KEYSTONE"))
  }

  test("every row states its role, and the envelope is stamped") {
    entries.foreach { e =>
      val d = str(e, "description")
      assert(d.contains("SEPARATING") || d.contains("THE CONTROL"), s"${str(e, "name")}: $d")
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
      assertEquals(e.hcursor.downField("input_boxes_hex").as[List[String]].fold(_ => Nil, identity).size, 1)
    }
    assertEquals(blessed.hcursor.get[String]("op").toOption, Some("non-pair-tuple-spend"))
    assertEquals(blessed.hcursor.get[String]("blessed_by").toOption,
      Some("jvm:ergo-core-6.0.7-validateStateful"))
  }

  test("write") {
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
