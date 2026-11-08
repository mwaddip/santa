package santa

import io.circe.Json

/** Anchors the receiver-kind EVAL family. `SpecExtract.authored{,Reject}Entry` already fails loud
  * if a control does not evaluate or a wrong receiver does, so this pins the costs (which are the
  * graded part) and the shape: four controls, six separating rows. */
class AuthoredEvalReceiverKindTest extends munit.FunSuite {
  private val B = AuthoredEvalReceiverKind
  private lazy val vectors = B.extract()

  private def entries: List[Json] = vectors(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def one(frag: String): Json = entries.filter(e => str(e, "name").startsWith(frag)) match {
    case List(e) => e
    case other   => fail(s"want one entry starting '$frag', got ${other.map(str(_, "name"))}")
  }

  test("10 rows: 4 controls with value+cost, 6 separating rows errored") {
    assertEquals(entries.size, 10)
    assertEquals(entries.count(errored), 6)
    assertEquals(entries.count(e => !errored(e)), 4)
    entries.filter(errored).foreach(e => assertEquals(cost(e), None, str(e, "name")))
  }

  test("the controls' costs — the graded half of the family") {
    assertEquals(cost(one("encode_nbits_global_control")), Some(39L))
    assertEquals(cost(one("decode_nbits_global_control")), Some(64L))
    assertEquals(cost(one("pow_hit_global_control")), Some(765L))
    assertEquals(cost(one("get_var_from_input_context_control")), Some(25L))
  }

  test("every row carries the probe's own expression verbatim") {
    ReceiverKindTrees.rows.foreach { r =>
      val e = entries.find(x => str(x, "name").startsWith(r.slug)).getOrElse(fail(r.slug))
      assertEquals(str(e, "tree_bytes_hex"), ReceiverKindTrees.evalTree(r.expr), r.slug)
      assert(str(e, "tree_bytes_hex").contains(r.expr), s"${r.slug}: the call must appear verbatim")
    }
  }

  test("both wrong-receiver SHAPES are present: a constant and a first-class singleton") {
    // Rows 3 and 9 give a wrong receiver that is not a constant (CONTEXT, Global). If those were
    // dropped the family would only show that a CONSTANT receiver is refused, which is weaker.
    assert(errored(one("encode_nbits_context_receiver")), "CONTEXT as a Global method's receiver")
    assert(errored(one("get_var_from_input_global_receiver")), "Global as a Context method's receiver")
    assert(errored(one("encode_nbits_int_receiver")) && errored(one("get_var_from_input_int_receiver")))
  }

  test("envelope: the 6.0.7 stamp") {
    assertEquals(vectors(B.Op).hcursor.get[String]("blessed_by").toOption,
      Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
