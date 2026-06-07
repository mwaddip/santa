package santa

import java.nio.file.Files

/** Guard for the two resolved-divergence regression pins. Values locked from the
  * JVM oracle (the findings doc + spike): plus_kind → Long 3 cost 35 (accept);
  * tuple_triple → errored (reject). Both at version {activated 2, ergoTree 0}. */
class AuthoredArithTupleTest extends munit.FunSuite {

  private lazy val vectors = AuthoredArithTuple.extract()
  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def only(op: String): io.circe.ACursor = entries(op).head.hcursor

  test("two single-entry families") {
    assertEquals(vectors.size, 2)
    assertEquals(entries(AuthoredArithTuple.OpArith).size, 1)
    assertEquals(entries(AuthoredArithTuple.OpTuple).size, 1)
  }

  test("arith: mismatched Int/Long coerces to Long 3, cost 35 (accept)") {
    val exp = only(AuthoredArithTuple.OpArith).downField("expected")
    assertEquals(exp.downField("value").get[String]("kind").toOption, Some("Long"))
    assertEquals(exp.downField("value").get[String]("value").toOption, Some("3"))
    assertEquals(exp.get[Long]("cost").toOption, Some(35L))
    assert(exp.downField("error").focus.exists(_.isNull))
  }

  test("tuple: flat arity-3 rejected (errored)") {
    val exp = only(AuthoredArithTuple.OpTuple).downField("expected")
    assertEquals(exp.get[String]("error").toOption, Some("errored"))
    assert(exp.downField("value").focus.exists(_.isNull))
    assert(exp.downField("cost").focus.exists(_.isNull))
  }

  test("version pinned at {activated 2, ergoTree 0} (the native v0 wire form)") {
    Seq(AuthoredArithTuple.OpArith, AuthoredArithTuple.OpTuple).foreach { op =>
      val v = only(op).downField("version")
      assertEquals(v.get[Int]("activated").toOption, Some(2), s"$op activated")
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0), s"$op ergoTree")
    }
  }

  test("tree bytes are the exact finding wire forms") {
    assertEquals(only(AuthoredArithTuple.OpArith).get[String]("tree_bytes_hex").toOption, Some("009a04020504"))
    assertEquals(only(AuthoredArithTuple.OpTuple).get[String]("tree_bytes_hex").toOption, Some("0086030101020703a413"))
  }

  test("staging: writes both family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredArithTuple.writeVectors(out)
    assert(Files.exists(out.resolve("ArithOp.numeric_kind_mismatch.json")))
    assert(Files.exists(out.resolve("Tuple.non_pair_arity3.json")))
  }
}
