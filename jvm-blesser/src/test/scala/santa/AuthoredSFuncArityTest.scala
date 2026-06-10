package santa

import java.nio.file.Files

/** Guard for the SFunc-arity witnesses (ergots Ask 11). The five rejects are JVM
  * eval-ERROR (spike-confirmed); `AuthoredSFuncArity.extract` re-blesses them through
  * EvalCore and fails loud if any drifts to a success — extract() returning IS the live
  * proof each still errors on sigma-state 6.0.3. The two accept arms (lazy-If skip,
  * unary control) bless live; their value+cost are asserted exactly. Tree bytes are
  * built from the IR by the same serializer every run — the byte assertions pin
  * serializer stability against the spike wire forms. */
class AuthoredSFuncArityTest extends munit.FunSuite {

  private lazy val vectors = AuthoredSFuncArity.extract()
  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def byName(op: String, name: String): io.circe.Json =
    entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"missing entry $name in $op"))
  private def expectedOf(entry: io.circe.Json): io.circe.ACursor = entry.hcursor.downField("expected")

  test("two families: FuncValue.non_unary_arity (4 entries), Apply.non_unary_arity (3)") {
    assertEquals(vectors.size, 2)
    assertEquals(entries(AuthoredSFuncArity.OpFuncValue).size, 4)
    assertEquals(entries(AuthoredSFuncArity.OpApply).size, 3)
  }

  test("the five reject arms (error=errored, value+cost null)") {
    val rejects = Seq(
      AuthoredSFuncArity.OpFuncValue -> "funcvalue-2arg-applied-errored#0",
      AuthoredSFuncArity.OpFuncValue -> "funcvalue-2arg-bound-errored#0",
      AuthoredSFuncArity.OpFuncValue -> "funcvalue-0arg-errored#0",
      AuthoredSFuncArity.OpApply     -> "apply-2-args-on-unary-errored#0",
      AuthoredSFuncArity.OpApply     -> "apply-0-args-on-unary-errored#0")
    rejects.foreach { case (op, name) =>
      val exp = expectedOf(byName(op, name))
      assertEquals(exp.get[String]("error").toOption, Some("errored"), s"$name error")
      assert(exp.downField("value").focus.exists(_.isNull), s"$name value should be null")
      assert(exp.downField("cost").focus.exists(_.isNull), s"$name cost should be null")
    }
  }

  test("the two accept arms bless exactly (lazy-If skip → 5 @ 12; unary control → 42 @ 74)") {
    val lazyIf = expectedOf(byName(AuthoredSFuncArity.OpFuncValue, "funcvalue-2arg-lazy-if-accept#0"))
    assertEquals(lazyIf.downField("value").get[Int]("value").toOption, Some(5))
    assertEquals(lazyIf.get[Long]("cost").toOption, Some(12L))
    assert(lazyIf.downField("error").focus.exists(_.isNull))
    val control = expectedOf(byName(AuthoredSFuncArity.OpApply, "apply-unary-control-accept#0"))
    assertEquals(control.downField("value").get[Int]("value").toOption, Some(42))
    assertEquals(control.get[Long]("cost").toOption, Some(74L))
    assert(control.downField("error").focus.exists(_.isNull))
  }

  test("tree bytes are the exact spike wire forms (serializer stability)") {
    val expected = Map(
      "funcvalue-2arg-applied-errored#0" ->
        "100204060408d801d601d902020403049a72027203da72010273007301",
      "funcvalue-2arg-bound-errored#0" ->
        "1001040ad801d601d902020403049a720272037300",
      "funcvalue-0arg-errored#0" ->
        "1001040ad801d601d9007300da720100",
      "funcvalue-2arg-lazy-if-accept#0" ->
        "10040101040a040604089573007301d801d601d902020403049a72027203da72010273027303",
      "apply-2-args-on-unary-errored#0" ->
        "1003040204060408d801d601d90102049a72027300da72010273017302",
      "apply-0-args-on-unary-errored#0" ->
        "10010402d801d601d90102049a72027300da720100",
      "apply-unary-control-accept#0" ->
        "100204020452d801d601d90102049a72027300da7201017301")
    val all = entries(AuthoredSFuncArity.OpFuncValue) ++ entries(AuthoredSFuncArity.OpApply)
    assertEquals(all.size, expected.size)
    all.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse(fail("unnamed entry"))
      assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption, expected.get(name), name)
    }
  }

  test("version pinned at {activated 2, ergoTree 0} (segregated-v0 wire forms)") {
    val all = entries(AuthoredSFuncArity.OpFuncValue) ++ entries(AuthoredSFuncArity.OpApply)
    all.foreach { e =>
      val v    = e.hcursor.downField("version")
      val name = e.hcursor.get[String]("name").toOption.getOrElse("<unnamed>")
      assertEquals(v.get[Int]("activated").toOption, Some(2), s"$name activated")
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0), s"$name ergoTree")
    }
  }

  test("staging: writes both family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredSFuncArity.writeVectors(out)
    assert(Files.exists(out.resolve("FuncValue.non_unary_arity.json")))
    assert(Files.exists(out.resolve("Apply.non_unary_arity.json")))
  }
}
