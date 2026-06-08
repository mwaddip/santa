package santa

import java.nio.file.Files

/** Guard for the four checkType / non-pair-tuple reject witnesses. All four are
  * JVM eval-ERROR (spike-confirmed); `AuthoredCheckType.extract` re-blesses them
  * through EvalCore and fails loud if any drifts to a success — so the mere fact that
  * `extract()` returns is the live proof each one still errors on sigma-state 6.0.3.
  * Here we assert the emitted reject SHAPE (value/cost null, error "errored"), the exact
  * spike wire bytes, and the version envelope {activated 2, ergoTree 0}. */
class AuthoredCheckTypeTest extends munit.FunSuite {

  private lazy val vectors = AuthoredCheckType.extract()
  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def expectedOf(entry: io.circe.Json): io.circe.ACursor = entry.hcursor.downField("expected")

  test("three families: tuple-checkType (2 entries), SelectField non-pair (1), EQ non-pair (1)") {
    assertEquals(vectors.size, 3)
    assertEquals(entries(AuthoredCheckType.OpTupleCheckType).size, 2)
    assertEquals(entries(AuthoredCheckType.OpSelectField).size, 1)
    assertEquals(entries(AuthoredCheckType.OpEqNonPair).size, 1)
  }

  test("every entry is a reject (error=errored, value+cost null)") {
    val all = entries(AuthoredCheckType.OpTupleCheckType) ++
              entries(AuthoredCheckType.OpSelectField) ++
              entries(AuthoredCheckType.OpEqNonPair)
    assertEquals(all.size, 4)
    all.foreach { e =>
      val exp  = expectedOf(e)
      val name = e.hcursor.get[String]("name").toOption.getOrElse("<unnamed>")
      assertEquals(exp.get[String]("error").toOption, Some("errored"), s"$name error")
      assert(exp.downField("value").focus.exists(_.isNull), s"$name value should be null")
      assert(exp.downField("cost").focus.exists(_.isNull), s"$name cost should be null")
    }
  }

  test("tree bytes are the exact spike wire forms") {
    val tc = entries(AuthoredCheckType.OpTupleCheckType)
    assertEquals(tc(0).hcursor.get[String]("tree_bytes_hex").toOption, Some("008602480101010101010402"))
    assertEquals(tc(1).hcursor.get[String]("tree_bytes_hex").toOption, Some("1002480101010101010402860273007301"))
    assertEquals(entries(AuthoredCheckType.OpSelectField).head.hcursor.get[String]("tree_bytes_hex").toOption,
      Some("008c6001040a01"))
    assertEquals(entries(AuthoredCheckType.OpEqNonPair).head.hcursor.get[String]("tree_bytes_hex").toOption,
      Some("10060402040404060402040404069386037300730173028603730373047305"))
  }

  test("the two tuple-checkType entries are distinct seams (distinct names + bytes)") {
    val tc = entries(AuthoredCheckType.OpTupleCheckType)
    val names = tc.flatMap(_.hcursor.get[String]("name").toOption).toSet
    assertEquals(names, Set("checktype-inline-errored#0", "checktype-placeholder-errored#0"))
    val byteSet = tc.flatMap(_.hcursor.get[String]("tree_bytes_hex").toOption).toSet
    assertEquals(byteSet.size, 2, "the inline and placeholder forms must be distinct trees")
  }

  test("EQ non-pair witness is NOT a duplicate of Tuple.non_pair_arity3") {
    val eqBytes = entries(AuthoredCheckType.OpEqNonPair).head.hcursor.get[String]("tree_bytes_hex").toOption
    val arityBytes = AuthoredArithTuple.extract()(AuthoredArithTuple.OpTuple)
      .hcursor.downField("entries").values.get.head.hcursor.get[String]("tree_bytes_hex").toOption
    assert(eqBytes.isDefined && arityBytes.isDefined)
    assertNotEquals(eqBytes, arityBytes, "EQ-wrapped witness must differ from the bare non-pair-arity3 tree")
    assert(AuthoredCheckType.OpEqNonPair != AuthoredArithTuple.OpTuple, "op names must differ")
  }

  test("version pinned at {activated 2, ergoTree 0} (the native v0 wire forms)") {
    val all = entries(AuthoredCheckType.OpTupleCheckType) ++
              entries(AuthoredCheckType.OpSelectField) ++
              entries(AuthoredCheckType.OpEqNonPair)
    all.foreach { e =>
      val v    = e.hcursor.downField("version")
      val name = e.hcursor.get[String]("name").toOption.getOrElse("<unnamed>")
      assertEquals(v.get[Int]("activated").toOption, Some(2), s"$name activated")
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0), s"$name ergoTree")
    }
  }

  test("staging: writes all three family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredCheckType.writeVectors(out)
    assert(Files.exists(out.resolve("Tuple.checkType_unsupported.json")))
    assert(Files.exists(out.resolve("SelectField.non_pair.json")))
    assert(Files.exists(out.resolve("EQ.non_pair_tuple_operand.json")))
  }
}
