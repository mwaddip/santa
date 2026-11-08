package santa

import io.circe.Json

/** Anchors the unevaluated-node WIRE family. `extract()` already re-derives every verdict through
  * WireCanonicalize and asserts each reject's REASON out of the cause chain; this pins the shape
  * of the family — which rows are the live arm, which are degrade guards — and the two composed
  * trees whose bytes no JVM call will reproduce. */
class AuthoredWireUnevaluatedNodeKindsTest extends munit.FunSuite {
  private val B = AuthoredWireUnevaluatedNodeKinds
  private lazy val vectors = B.extract()

  private def entries(op: String): List[Json] = vectors(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def treeVer(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("ergoTree"))

  test("both kinds: 13 v0 accepts, 13 v3 accepts, 6 rejects — 32 rows") {
    assertEquals(vectors.keySet, Set(B.OpBox, B.OpTransaction))
    Seq(B.OpBox, B.OpTransaction).foreach { op =>
      val es = entries(op)
      assertEquals(es.size, 32, op)
      val (accepts, rejects) = es.partition(_.hcursor.get[String]("error").toOption.isEmpty)
      assertEquals(accepts.size, 26, s"$op accepts")
      assertEquals(rejects.size, 6, s"$op rejects")
      assertEquals(accepts.count(treeVer(_) == 0), 13, s"$op v0 accepts")
      assertEquals(accepts.count(treeVer(_) == 3), 13, s"$op v3 accepts")
      // one node kind is unrepresentable at v0, and it is the one the v0 accepts leave out
      val v0Names = accepts.filter(treeVer(_) == 0).map(e => str(e, "name"))
      assert(!v0Names.exists(_.contains("create_avl_tree_four_value-v0")),
        s"$op: the None-constant four-value layout cannot be an accept at v0")
      assert(v0Names.exists(_.contains("create_avl_tree_four_value_getvar-v0")),
        s"$op: the getVar form IS the four-value layout at v0")
      rejects.foreach { e =>
        assertEquals(e.hcursor.get[String]("error").toOption, Some("errored"), str(e, "name"))
      }
      es.foreach { e =>
        assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty,
          s"${str(e, "name")}: identity round-trip or reject, never a non-identity expected")
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      }
    }
  }

  test("every accept carries its tree verbatim, at the version its row claims") {
    Seq(B.OpBox, B.OpTransaction).foreach { op =>
      entries(op).filter(_.hcursor.get[String]("error").toOption.isEmpty).foreach { e =>
        val carrier = str(e, "bytes_hex")
        // `bit_shift_right` is a PREFIX of `bit_shift_right_zeroed`, so take the LONGEST match
        val node = (UnevaluatedNodeTrees.nodes ++ UnevaluatedNodeTrees.extraEncodings)
          .filter(n => str(e, "name").contains(s"-${n.slug}-"))
          .sortBy(-_.slug.length).headOption.getOrElse(fail(s"no node kind in ${str(e, "name")}"))
        val tree = if (treeVer(e) == 0) UnevaluatedNodeTrees.v0(node.body)
                   else UnevaluatedNodeTrees.v3(node.body)
        assert(carrier.contains(tree), s"${str(e, "name")}: must carry $tree")
      }
    }
  }

  test("the two composed reject trees are the shapes they claim") {
    // the old layout differs from the four-value one in exactly the fourth field
    val four = UnevaluatedNodeTrees.nodeBySlug("create_avl_tree_four_value").body
    assert(four.contains(UnevaluatedNodeTrees.NoneOptionHex), "the four-value body carries 2800")
    assertEquals(UnevaluatedNodeTrees.oldLayoutBody,
      four.replace(UnevaluatedNodeTrees.NoneOptionHex, "00"))
    // the Boolean operand replaces an IntConstant(1), so the body is the same length
    assertEquals(UnevaluatedNodeTrees.boolOperandBody.length,
      UnevaluatedNodeTrees.nodeBySlug("bit_shift_left").body.length)
    assert(UnevaluatedNodeTrees.boolOperandBody.contains("f70101"),
      s"the shift's left operand must be BooleanConstant(true): ${UnevaluatedNodeTrees.boolOperandBody}")
  }

  test("envelopes: santa-wire/v1, the 6.0.7 stamp, the authored source") {
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"), op)
      assertEquals(c.get[String]("op").toOption, Some(op))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"), op)
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    B.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json")
    }
  }
}
