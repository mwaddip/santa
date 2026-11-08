package santa

import io.circe.Json

/** Anchors the missing-v5-methods EVAL family. `SpecExtract` already fails loud if an accept does
  * not evaluate or a reject does, so this pins the costs (the graded part), the 5/5 split, and the
  * two control pairs that carry the family's point — that the same operation works as a dedicated
  * node and cannot be invoked as a MethodCall. */
class AuthoredEvalV5MissingMethodsTest extends munit.FunSuite {
  private val B = AuthoredEvalV5MissingMethods
  private lazy val v = B.extract()

  private def entries(op: String): List[Json] = v(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def one(op: String, frag: String): Json =
    entries(op).filter(x => str(x, "name").startsWith(frag)) match {
      case List(e) => e
      case other   => fail(s"want one in $op starting '$frag', got ${other.map(str(_, "name"))}")
    }

  test("10 rows split 5 accept / 5 errored, and 6 dedicated-node controls") {
    assertEquals(entries(B.Op).size, 10)
    assertEquals(entries(B.Op).count(errored), 5)
    assertEquals(entries(B.OpControl).size, 6)
    entries(B.OpControl).foreach(e => assert(!errored(e), s"${str(e, "name")}: a control must evaluate"))
  }

  test("the costs that are graded") {
    assertEquals(cost(one(B.Op, "group_element_exp")), Some(928L))
    assertEquals(cost(one(B.Op, "group_element_multiply")), Some(82L))
    assertEquals(cost(one(B.Op, "option_is_defined_some")), Some(33L))
    assertEquals(cost(one(B.Op, "option_is_defined_none")), Some(28L))
    assertEquals(cost(one(B.Op, "option_get_some")), Some(38L))
    assertEquals(cost(one(B.OpControl, "exponentiate")), Some(915L))
    assertEquals(cost(one(B.OpControl, "multiply_group")), Some(60L))
    assertEquals(cost(one(B.OpControl, "option_get_or_else")), Some(30L))
    assertEquals(cost(one(B.OpControl, "extract_register_as")), Some(60L))
  }

  test("the MethodCall encoding costs MORE than the dedicated node, where both work") {
    // Only the two rows whose receiver matches the control's are comparable; isDefined's is not.
    assertEquals(cost(one(B.Op, "group_element_exp")).get - cost(one(B.OpControl, "exponentiate")).get, 13L)
    assertEquals(cost(one(B.Op, "group_element_multiply")).get - cost(one(B.OpControl, "multiply_group")).get, 22L)
  }

  test("the two control PAIRS are the family's point: dedicated works, MethodCall cannot") {
    // If either of these ever flips, the family stops showing that the fault is the encoding.
    assert(errored(one(B.Op, "option_get_or_else_some")) && errored(one(B.Op, "option_get_or_else_none")))
    assert(!errored(one(B.OpControl, "option_get_or_else")))
    assert(errored(one(B.Op, "box_r0_is_defined")) && errored(one(B.Op, "box_r4_is_defined")))
    assert(!errored(one(B.OpControl, "extract_register_as")))
  }

  test("R0 (mandatory) and R4 (not) fail identically — the lookup precedes the register") {
    val r0 = one(B.Op, "box_r0_is_defined"); val r4 = one(B.Op, "box_r4_is_defined")
    assertEquals(errored(r0), errored(r4))
    assertEquals(cost(r0), cost(r4))
    assert(str(r4, "script").contains("makes NO difference"))
  }

  test("get-on-none reached the method; the others did not — keep both shapes") {
    assert(errored(one(B.Op, "option_get_none")))
    assert(str(one(B.Op, "option_get_none"), "script").contains("INVOKES and then throws"))
  }

  test("envelopes carry the 6.0.7 stamp") {
    v.foreach { case (op, e) =>
      assertEquals(e.hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"), op)
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    v.keys.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
