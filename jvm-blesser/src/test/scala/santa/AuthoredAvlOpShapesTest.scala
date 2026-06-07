package santa

import java.nio.file.Files

/** Guard for the op-shape sweep (Ask 5's 18 pins) + S5 negative-keyLength tree.
  * Pins the failure-model routing at the OP level (valid construction, op fails):
  * contains→false · get/getMany→errored · insert raise@v5/None@v6 · update/remove→None
  * · insertOrUpdate→None. Costs locked from the bless; drift ⇒ INVESTIGATE. */
class AuthoredAvlOpShapesTest extends munit.FunSuite {

  private lazy val vectors = AuthoredAvlOpShapes.extract()
  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def byName(op: String, name: String): io.circe.ACursor =
    entries(op).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"entry '$name' not found in $op")).hcursor.downField("expected")
  private def isFalse(c: io.circe.ACursor): Boolean = {
    val v = c.downField("value")
    v.get[String]("kind").toOption.contains("Boolean") && v.get[Boolean]("value").toOption.contains(false)
  }
  private def isNone(c: io.circe.ACursor): Boolean = {
    val v = c.downField("value")
    v.get[String]("kind").toOption.contains("Option") && v.downField("value").focus.exists(_.isNull)
  }
  private def isErrored(c: io.circe.ACursor): Boolean = c.get[String]("error").toOption.contains("errored")

  test("counts: per-op v5=20, v6=8; negKl v5=5, v6=1 (full 18-pin sweep + S5)") {
    assertEquals(entries(AuthoredAvlOpShapes.OpPerOpV5).size, 20)
    assertEquals(entries(AuthoredAvlOpShapes.OpPerOpV6).size, 8)
    assertEquals(entries(AuthoredAvlOpShapes.OpNegKlV5).size, 5)
    assertEquals(entries(AuthoredAvlOpShapes.OpNegKlV6).size, 1)
  }

  test("routing — contains→false, get/getMany→errored (across all three key shapes)") {
    Seq("wrong-len", "-inf", "+inf").foreach { tag =>
      assert(isFalse(byName(AuthoredAvlOpShapes.OpPerOpV5, s"contains-$tag-false")), s"contains-$tag")
      assert(isErrored(byName(AuthoredAvlOpShapes.OpPerOpV5, s"get-$tag-errored")), s"get-$tag")
      assert(isErrored(byName(AuthoredAvlOpShapes.OpPerOpV5, s"getMany-$tag-errored")), s"getMany-$tag")
    }
  }

  test("routing — insert raise@v5 / None@v6 (#908), all four shapes") {
    Seq("wrong-len-key", "-inf-key", "+inf-key", "wrong-val-len").foreach { tag =>
      assert(isErrored(byName(AuthoredAvlOpShapes.OpPerOpV5, s"insert-$tag-errored")), s"insert-$tag@v5")
      assert(isNone(byName(AuthoredAvlOpShapes.OpPerOpV6, s"insert-$tag-none")), s"insert-$tag@v6")
    }
  }

  test("routing — update/remove/insertOrUpdate → None") {
    Seq("wrong-len-key", "-inf-key", "+inf-key", "wrong-val-len").foreach { tag =>
      assert(isNone(byName(AuthoredAvlOpShapes.OpPerOpV5, s"update-$tag-none")), s"update-$tag")
      assert(isNone(byName(AuthoredAvlOpShapes.OpPerOpV6, s"insertOrUpdate-$tag-none")), s"iou-$tag")
    }
    Seq("wrong-len", "-inf", "+inf").foreach { tag =>
      assert(isNone(byName(AuthoredAvlOpShapes.OpPerOpV5, s"remove-$tag-none")), s"remove-$tag")
    }
  }

  test("S5 — negative-keyLength tree: contains→false, get/insert@v5→errored, remove→None, keyLength→neg, insert@v6→None") {
    assert(isFalse(byName(AuthoredAvlOpShapes.OpNegKlV5, "contains-false#0")))
    assert(isErrored(byName(AuthoredAvlOpShapes.OpNegKlV5, "get-errored#1")))
    assert(isErrored(byName(AuthoredAvlOpShapes.OpNegKlV5, "insert-errored#2")))
    assert(isNone(byName(AuthoredAvlOpShapes.OpNegKlV5, "remove-none#3")))
    val kl = byName(AuthoredAvlOpShapes.OpNegKlV5, "keyLength-negative#4").downField("value")
    assertEquals(kl.get[String]("kind").toOption, Some("Int"))
    assertEquals(kl.get[Long]("value").toOption, Some(-2147483648L))
    assert(isNone(byName(AuthoredAvlOpShapes.OpNegKlV6, "insert-none#0")))
  }

  test("cost anchors (keyLength=32): uniform per method, locked from the bless") {
    // per-op failure (valid construction): contains 257 · update 428 · remove 407 (v5)
    //                                       insert 308 · insertOrUpdate 443 (v6)
    assertEquals(byName(AuthoredAvlOpShapes.OpPerOpV5, "contains-wrong-len-false").get[Long]("cost").toOption, Some(257L))
    assertEquals(byName(AuthoredAvlOpShapes.OpPerOpV5, "update-wrong-len-key-none").get[Long]("cost").toOption, Some(428L))
    assertEquals(byName(AuthoredAvlOpShapes.OpPerOpV5, "remove-wrong-len-none").get[Long]("cost").toOption, Some(407L))
    assertEquals(byName(AuthoredAvlOpShapes.OpPerOpV6, "insert-wrong-len-key-none").get[Long]("cost").toOption, Some(308L))
    assertEquals(byName(AuthoredAvlOpShapes.OpPerOpV6, "insertOrUpdate-wrong-len-key-none").get[Long]("cost").toOption, Some(443L))
    // S5 negative-keyLength tree (cheaper — broken-tree charge): contains 217 · remove 362 · keyLength 20 · insert@v6 278
    assertEquals(byName(AuthoredAvlOpShapes.OpNegKlV5, "contains-false#0").get[Long]("cost").toOption, Some(217L))
    assertEquals(byName(AuthoredAvlOpShapes.OpNegKlV5, "remove-none#3").get[Long]("cost").toOption, Some(362L))
    assertEquals(byName(AuthoredAvlOpShapes.OpNegKlV5, "keyLength-negative#4").get[Long]("cost").toOption, Some(20L))
    assertEquals(byName(AuthoredAvlOpShapes.OpNegKlV6, "insert-none#0").get[Long]("cost").toOption, Some(278L))
  }

  test("staging: writes all four family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredAvlOpShapes.writeVectors(out)
    Seq("AvlTree.per_op_failure.json", "AvlTree.per_op_failure_v6.json",
        "AvlTree.negative_keylength_tree.json", "AvlTree.negative_keylength_tree_v6.json").foreach { f =>
      assert(Files.exists(out.resolve(f)), s"missing $f")
    }
  }
}
