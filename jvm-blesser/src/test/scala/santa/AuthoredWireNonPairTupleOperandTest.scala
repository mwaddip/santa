package santa

import io.circe.Json

/** Anchors the wire family. Its claims:
  *
  *   1. THE THREE-WAY SPLIT — comparison refuses whatever the operands, equality refuses against a
  *      concrete type and ACCEPTS `SAny`-vs-`SAny`. If the accept row ever joins the rejects, the
  *      family stops distinguishing the two builder checks while still grading green.
  *   2. THE SIZE BIT IS INERT HERE — every v3/v0 twin lands on the same verdict, which is the
  *      opposite of `methodcall_type_id_order` and the reason both families exist.
  *   3. the accept row is a REACHABLE box: it rides in a Box and a Transaction carrier, and the
  *      eval family holds the same tree failing at eval.
  */
class AuthoredWireNonPairTupleOperandTest extends munit.FunSuite {
  private val B = AuthoredWireNonPairTupleOperand
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] = v(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def errored(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def treeVer(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("version"))
  private def row(op: String, slug: String): Json =
    entries(op).filter(e => str(e, "name").contains(slug)) match {
      case List(e) => e
      case other   => fail(s"want one $slug in $op, got ${other.map(str(_, "name"))}")
    }

  test("8 ErgoTree rows, 5 carriers each for Box and Transaction") {
    assertEquals(entries(B.OpErgoTree).size, 8)
    assertEquals(entries(B.OpBox).size, 5)        // the three v0 unsized rows are ErgoTree-only
    assertEquals(entries(B.OpTransaction).size, 5)
    // the two v0 unsized rows are ErgoTree-only, by construction
    assert(entries(B.OpBox).forall(e => treeVer(e) == 3))
    assert(entries(B.OpTransaction).forall(e => treeVer(e) == 3))
  }

  test("THE THREE-WAY SPLIT: comparison always refuses, equality refuses only a type mismatch") {
    assert(errored(row(B.OpErgoTree, "eq-any-concrete-v3")), "SAny vs SInt under EQ: refused")
    assert(errored(row(B.OpErgoTree, "gt-any-concrete-v3")), "SAny vs SInt under GT: refused")
    assert(errored(row(B.OpErgoTree, "gt-any-any-v3")), "SAny vs SAny under GT: still refused")
    assert(!errored(row(B.OpErgoTree, "eq-any-any-v3")), "SAny vs SAny under EQ: ACCEPTED")
    assert(!errored(row(B.OpErgoTree, "eq-concrete-control-v3")), "the control accepts")
  }

  test("THE SIZE BIT IS INERT: every v3/v0 twin agrees") {
    Seq(("eq-any-concrete-v3", "eq-any-concrete-v0"),
        ("gt-any-any-v3", "gt-any-any-v0"),
        ("eq-any-any-v3", "eq-any-any-v0")).foreach { case (sized, unsized) =>
      val a = row(B.OpErgoTree, sized); val b = row(B.OpErgoTree, unsized)
      assertEquals(treeVer(a), 3); assertEquals(treeVer(b), 0)
      assertEquals(errored(a), errored(b), s"$sized and $unsized must agree — no degrade either way")
    }
  }

  test("the accept row rides in a Box and a Transaction, and names its eval twin") {
    Seq(B.OpBox, B.OpTransaction).foreach { op =>
      val e = row(op, "eq-any-any-v3")
      assert(!errored(e), s"$op: the accept must survive the carrier")
      assert(str(e, "bytes_hex").contains("d193b248040404020406040000"),
        s"$op: the carrier must actually hold the tree")
    }
    assert(str(row(B.OpErgoTree, "eq-any-any-v3"), "description").contains("non_pair_value_gates"),
      "the keystone must point at the eval tier's twin")
  }

  test("the rejects carry into the carriers too: no such box can be read") {
    Seq(B.OpBox, B.OpTransaction).foreach { op =>
      assert(errored(row(op, "eq-any-concrete-v3")), s"$op: a box holding the refused tree")
      assert(errored(row(op, "gt-any-any-v3")), s"$op: and the comparison one")
    }
  }

  test("every row states its role, and the envelope is stamped") {
    Seq(B.OpErgoTree, B.OpBox, B.OpTransaction).foreach { op =>
      assertEquals(v(op).hcursor.get[String]("op").toOption, Some(op))
      assertEquals(v(op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
      entries(op).foreach { e =>
        val d = str(e, "description")
        assert(d.contains("SEPARATING") || d.contains("CONTROL") || d.contains("does not depend"),
          s"${str(e, "name")}: $d")
        assertEquals(str(e, "source"), B.Source)
      }
    }
  }

  test("write") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    B.writeVectors(outDir)
    Seq(B.OpErgoTree, B.OpBox, B.OpTransaction).foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), op))
  }
}
