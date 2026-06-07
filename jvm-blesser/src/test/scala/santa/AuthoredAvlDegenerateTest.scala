package santa

import java.nio.file.Files

/** Guard + smoke for the AvlTree degenerate-edge families (Ask 1 / Ask 2 / twins).
  * Costs locked from the spike observation; drift ⇒ INVESTIGATE. */
class AuthoredAvlDegenerateTest extends munit.FunSuite {

  private lazy val vectors = AuthoredAvlDegenerate.extract()

  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def byName(op: String, name: String): io.circe.ACursor =
    entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"entry '$name' not found in $op")).hcursor.downField("expected")
  private def isNone(c: io.circe.ACursor): Boolean = {
    val v = c.downField("value")
    v.get[String]("kind").toOption.contains("Option") && v.downField("value").focus.exists(_.isNull)
  }

  test("family entry counts") {
    assertEquals(entries(AuthoredAvlDegenerate.OpBadBytesV5).size, 5)
    assertEquals(entries(AuthoredAvlDegenerate.OpBadBytesV6).size, 1)
    assertEquals(entries(AuthoredAvlDegenerate.OpEmptyOpsV5).size, 3)
    assertEquals(entries(AuthoredAvlDegenerate.OpEmptyOpsV6).size, 1)
    assertEquals(entries(AuthoredAvlDegenerate.OpEdgesV5).size, 4)
    assertEquals(entries(AuthoredAvlDegenerate.OpEdgesV6).size, 1)
  }

  test("Ask 1: contains bad-bytes → false (×3); get/insert@v5 → errored; insert@v6 → None") {
    Seq("contains-0x00-false#0", "contains-truncated-false#1", "contains-empty-false#2").foreach { n =>
      val c = byName(AuthoredAvlDegenerate.OpBadBytesV5, n).downField("value")
      assertEquals(c.get[String]("kind").toOption, Some("Boolean"), s"$n kind")
      assertEquals(c.get[Boolean]("value").toOption, Some(false), s"$n value")
    }
    Seq("get-0x00-errored#3", "insert-0x00-errored#4").foreach { n =>
      assertEquals(byName(AuthoredAvlDegenerate.OpBadBytesV5, n).get[String]("error").toOption, Some("errored"), n)
    }
    assert(isNone(byName(AuthoredAvlDegenerate.OpBadBytesV6, "insert-0x00-none#0")), "insert@v6 must be None")
  }

  test("Ask 2: empty-ops valid-proof → Some(starting tree), unchanged digest across methods") {
    val digests = Seq(
      byName(AuthoredAvlDegenerate.OpEmptyOpsV5, "insert-empty-ops-some#0"),
      byName(AuthoredAvlDegenerate.OpEmptyOpsV5, "update-empty-ops-some#1"),
      byName(AuthoredAvlDegenerate.OpEmptyOpsV5, "remove-empty-ops-some#2"),
      byName(AuthoredAvlDegenerate.OpEmptyOpsV6, "insertOrUpdate-empty-ops-some#0")
    ).map { c =>
      val v = c.downField("value")
      assertEquals(v.get[String]("kind").toOption, Some("Option"), "must be Some")
      assert(!v.downField("value").focus.get.isNull, "must be Some (non-null)")
      v.downField("value").get[String]("bytes_hex").toOption.get
    }
    assertEquals(digests.distinct.size, 1, "all four return the SAME unchanged starting tree")
  }

  test("Twins: T1 mismatched-op→None, T2 empty-keys→empty Coll, T3 empty-entries→None (v5 & v6)") {
    assert(isNone(byName(AuthoredAvlDegenerate.OpEdgesV5, "remove-mismatched-op-none#0")), "T1 → None")
    val t2 = byName(AuthoredAvlDegenerate.OpEdgesV5, "getMany-empty-keys-empty-coll#1").downField("value")
    assertEquals(t2.get[String]("kind").toOption, Some("Coll"), "T2 kind")
    assertEquals(t2.downField("items").values.get.size, 0, "T2 empty Coll")
    assert(isNone(byName(AuthoredAvlDegenerate.OpEdgesV5, "insert-empty-entries-none#2")), "T3 insert@v5 → None")
    assert(isNone(byName(AuthoredAvlDegenerate.OpEdgesV5, "update-empty-entries-none#3")), "T3 update@v5 → None")
    assert(isNone(byName(AuthoredAvlDegenerate.OpEdgesV6, "insert-empty-entries-none#0")), "T3 insert@v6 → None")
  }

  test("cost anchors: locked from the spike observation") {
    assertEquals(byName(AuthoredAvlDegenerate.OpBadBytesV5, "contains-0x00-false#0").get[Long]("cost").toOption, Some(217L))
    assertEquals(byName(AuthoredAvlDegenerate.OpEmptyOpsV5, "insert-empty-ops-some#0").get[Long]("cost").toOption, Some(211L))
    assertEquals(byName(AuthoredAvlDegenerate.OpEmptyOpsV5, "remove-empty-ops-some#2").get[Long]("cost").toOption, Some(226L))
    assertEquals(byName(AuthoredAvlDegenerate.OpEdgesV5, "remove-mismatched-op-none#0").get[Long]("cost").toOption, Some(407L))
    assertEquals(byName(AuthoredAvlDegenerate.OpEdgesV5, "getMany-empty-keys-empty-coll#1").get[Long]("cost").toOption, Some(196L))
    assertEquals(byName(AuthoredAvlDegenerate.OpEdgesV5, "insert-empty-entries-none#2").get[Long]("cost").toOption, Some(211L))
  }

  test("staging: writes all six family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredAvlDegenerate.writeVectors(out)
    Seq("AvlTree.bad_proof_bytes.json", "AvlTree.bad_proof_bytes_v6.json",
        "AvlTree.empty_ops_valid_proof.json", "AvlTree.empty_ops_valid_proof_v6.json",
        "AvlTree.degenerate_edges.json", "AvlTree.degenerate_edges_v6.json").foreach { f =>
      assert(Files.exists(out.resolve(f)), s"missing $f")
    }
  }
}
