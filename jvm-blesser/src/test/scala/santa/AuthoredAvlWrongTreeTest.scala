package santa

import java.nio.file.Files

/** Guard + smoke tests for the AvlTree wrong-tree-proof families.
  * Pins the JVM's per-method asymmetry (contains→false / get,getMany→raise /
  * insert→raise at v5, None at v6 (#908) / update,remove→None) and the
  * cross-version insert pair. Cost anchors observed on the scope spike and
  * locked here — a drift means the JVM cost model or prover material moved:
  * INVESTIGATE, not blindly accept. */
class AuthoredAvlWrongTreeTest extends munit.FunSuite {

  private lazy val vectors = AuthoredAvlWrongTree.extract()

  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector

  private def byName(op: String, name: String): io.circe.ACursor =
    entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"entry '$name' not found in $op")).hcursor.downField("expected")

  test("two families: v5 row of 6 (3 accepts + 3 rejects), v6 single insert accept") {
    assertEquals(vectors.size, 2)
    assertEquals(entries(AuthoredAvlWrongTree.OpV5).size, 6)
    assertEquals(entries(AuthoredAvlWrongTree.OpV6).size, 1)

    Seq("get-errored#1", "getMany-errored#2", "insert-errored#3").foreach { n =>
      assertEquals(byName(AuthoredAvlWrongTree.OpV5, n).get[String]("error").toOption,
        Some("errored"), s"$n should be a reject")
    }
    Seq("contains-false#0", "update-none#4", "remove-none#5").foreach { n =>
      val c = byName(AuthoredAvlWrongTree.OpV5, n)
      assert(!c.downField("value").focus.get.isNull, s"unblessed value: $n")
      assert(!c.downField("cost").focus.get.isNull,  s"unblessed cost:  $n")
    }
  }

  test("value pins: contains=false, update/remove=None (v5), insert=None (v6)") {
    val contains = byName(AuthoredAvlWrongTree.OpV5, "contains-false#0").downField("value")
    assertEquals(contains.get[String]("kind").toOption, Some("Boolean"))
    assertEquals(contains.get[Boolean]("value").toOption, Some(false))

    Seq(
      (AuthoredAvlWrongTree.OpV5, "update-none#4"),
      (AuthoredAvlWrongTree.OpV5, "remove-none#5"),
      (AuthoredAvlWrongTree.OpV6, "insert-none#0")
    ).foreach { case (op, n) =>
      val v = byName(op, n).downField("value")
      assertEquals(v.get[String]("kind").toOption, Some("Option"), s"$n kind")
      assert(v.downField("value").focus.exists(_.isNull), s"$n should be None")
    }
  }

  test("the #908 pair: SAME insert tree bytes modulo the version header") {
    def treeOf(op: String, name: String): String =
      entries(op).find(_.hcursor.get[String]("name").toOption.contains(name)).get
        .hcursor.get[String]("tree_bytes_hex").toOption.get
    val v5 = treeOf(AuthoredAvlWrongTree.OpV5, "insert-errored#3")
    val v6 = treeOf(AuthoredAvlWrongTree.OpV6, "insert-none#0")
    // header byte differs (version bits), body identical
    assertEquals(v5.drop(2), v6.drop(2), "insert pair bodies must be identical")
    assert(v5.take(2) != v6.take(2), "insert pair headers must differ (v2 vs v3)")
  }

  test("version pins: v5 row at (2,2), v6 insert at (3,3)") {
    entries(AuthoredAvlWrongTree.OpV5).foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(2))
    }
    assertEquals(entries(AuthoredAvlWrongTree.OpV6).head.hcursor
      .downField("version").get[Int]("ergoTree").toOption, Some(3))
  }

  // Costs observed on the scope spike (the first bless observation):
  //   contains 257 · update 428 · remove 387 (v5) · insert 308 (v6)
  test("cost anchors: locked from the spike observation") {
    assertEquals(byName(AuthoredAvlWrongTree.OpV5, "contains-false#0").get[Long]("cost").toOption, Some(257L))
    assertEquals(byName(AuthoredAvlWrongTree.OpV5, "update-none#4").get[Long]("cost").toOption,    Some(428L))
    assertEquals(byName(AuthoredAvlWrongTree.OpV5, "remove-none#5").get[Long]("cost").toOption,    Some(387L))
    assertEquals(byName(AuthoredAvlWrongTree.OpV6, "insert-none#0").get[Long]("cost").toOption,    Some(308L))
  }

  test("staging: writes both family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredAvlWrongTree.writeVectors(out)
    assert(Files.exists(out.resolve("AvlTree.wrong_tree_proof.json")))
    assert(Files.exists(out.resolve("AvlTree.insert_wrong_tree.json")))
  }
}
