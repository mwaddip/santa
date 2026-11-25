package santa

import io.circe.Json

/** Anchors the two families that replace and complement the nine eval entries whose tree holds a constant of a 0- or
  * 1-item tuple type (sigma-rust's re-authoring request, 2026-11-25):
  *
  *   1. SPENDS, for the four with a SigmaProp root, which no box can carry: each script arrives as bytes in the
  *      spender's context var 1 and keeps its eval fact — one valid spend, three invalid ones refused for the reason
  *      their eval entries documented, and a control for the carrier;
  *   2. OUTPUTS, for the five with another root, which a box CAN carry: transactions creating such an output are
  *      valid, at the control's cost, and carry the original tree's bytes unchanged.
  */
class AuthoredTxUnwritableTreeTest extends munit.FunSuite {
  private val B = AuthoredTxUnwritableTree
  private lazy val spends  = B.extractSpends()
  private lazy val outputs = B.extractOutputs()
  private def entries(j: Json): List[Json] =
    j.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def reason(e: Json): String = e.hcursor.downField("expected").get[String]("reason").toOption.getOrElse("")
  private def row(j: Json, slug: String): Json = entries(j).filter(e => str(e, "name").contains(s"-$slug#")) match {
    case List(e) => e
    case other   => fail(s"want one $slug, got ${other.map(str(_, "name"))}")
  }

  test("spends: the serialize row spends, the three constant rows are refused for their documented reasons") {
    assertEquals(entries(spends).size, 5)
    assert(valid(row(spends, "serialize_one_item_tuple")), "Global.serialize of a one-item tuple works")
    Seq("const_one_item_type" -> "Invalid type returned by evaluator",
        "const_zero_item_type" -> "Unsupported tuple type ()",
        "const_one_item_type_eq_self" -> "Unsupported tuple type (SInt$)").foreach { case (slug, why) =>
      val e = row(spends, slug)
      assert(!valid(e), s"$slug must be refused")
      assertEquals(cost(e), None)
      assert(reason(e).contains(why), s"$slug must be refused for '$why', got: ${reason(e)}")
    }
    assert(valid(row(spends, "control")), "the carrier itself works")
  }

  test("spends: every box is guarded by executeFromVar[SigmaProp](1), so the script lives only in the extension") {
    entries(spends).foreach { e =>
      val bx = e.hcursor.downField("input_boxes_hex").as[List[String]].fold(_ => Nil, identity)
      assertEquals(bx.size, 1)
      assert(bx.head.contains(B.CarrierTree), s"${str(e, "name")}: the box must carry ${B.CarrierTree}")
    }
  }

  test("outputs: creating a box with each of the five trees is valid, at the control's cost, its bytes unchanged") {
    assertEquals(entries(outputs).size, 6)
    val control = cost(row(outputs, "control"))
    assert(control.isDefined && valid(row(outputs, "control")))
    B.OutputTrees.foreach { case (slug, treeHex, _) =>
      val e = row(outputs, slug)
      assert(valid(e), s"$slug: the transaction creating the box must be valid")
      assertEquals(cost(e), control, s"$slug: the output's tree costs nothing to create")
      assert(str(e, "tx_bytes_hex").contains(treeHex), s"$slug: the output must carry the original tree bytes")
    }
  }

  test("every row states its role, and both envelopes are stamped") {
    (entries(spends) ++ entries(outputs)).foreach { e =>
      val d = str(e, "description")
      assert(d.contains("SEPARATING") || d.contains("THE CONTROL"), s"${str(e, "name")}: $d")
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
    }
    Seq(spends -> B.SpendOp, outputs -> B.OutputOp).foreach { case (j, op) =>
      assertEquals(j.hcursor.get[String]("op").toOption, Some(op))
      assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some("jvm:ergo-core-6.0.7-validateStateful"))
    }
  }

  test("write") {
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
