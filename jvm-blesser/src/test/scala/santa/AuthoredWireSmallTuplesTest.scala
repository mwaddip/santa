package santa

import io.circe.Json

/** Anchors the small-tuples WIRE family. `extract()` already re-derives every verdict through
  * `WireCanonicalize` and asserts each reject's REASON out of the cause chain; this pins the
  * things a re-derivation cannot catch — the composed bodies against the hex measured by
  * `SmallTuplesProbe`, and the SHAPE of the family, i.e. which rows are the separating arm and
  * which are controls. The shape is the part that rots silently: a row that quietly becomes a
  * control stops being evidence while still grading green. */
class AuthoredWireSmallTuplesTest extends munit.FunSuite {
  private val B = AuthoredWireSmallTuples
  private lazy val vectors = B.extract()
  private val ops = Seq(B.OpErgoTree, B.OpBox, B.OpTransaction)

  private def entries(op: String): List[Json] = vectors(op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def treeVer(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("ergoTree"))
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")

  test("the composed bodies are the bytes the probe measured") {
    // Every one of these was measured on the JVM at 6.0.7 by santa.spike.SmallTuplesProbe, and all
    // six that the sigma-rust session supplied matched byte-for-byte at both versions.
    val expected = Map(
      "const_one_item_type"           -> "d1938c60010402010402",
      "const_zero_item_type"          -> "d19360006000",
      "tuple_expr_one_item"           -> "d1938c86010402010402",
      "tuple_expr_zero_item"          -> "d19386008600",
      "const_pair_type_control"       -> "d1938c580204010402",
      "const_pair_eq_control"         -> "d193580204580204",
      "tuple_expr_two_items_control"  -> "d1938c860204020402010402",
      "const_triple_type"             -> "d1938c48040404020406010402")
    expected.foreach { case (slug, body) =>
      assertEquals(SmallTupleTrees.caseBySlug(slug).body, body, slug)
    }
    // the two long ones, by construction rather than by a 500-character literal
    assertEquals(SmallTupleTrees.caseBySlug("tuple_expr_127_items").body,
      "d1938c867f" + ("0402" * 127) + "01" + "0402")
    assertEquals(SmallTupleTrees.caseBySlug("tuple_expr_128_items").body,
      "d1938c8680" + ("0402" * 128) + "01" + "0402")
    // and the pieces the bodies are built out of
    assertEquals(SmallTupleTrees.PairConst, "580204")
    assertEquals(SmallTupleTrees.OneItemConst, "60010402")
    assertEquals(SmallTupleTrees.ZeroItemConst, "6000")
    assertEquals(SmallTupleTrees.NestedTupleRegisterHex, "8602860104020402")
    assertEquals(SmallTupleTrees.RegisterPlaceholderHex, "0e020102")
  }

  test("the headers: v0 is bare 00, v3 is 0b plus a VLQ size") {
    val body = SmallTupleTrees.caseBySlug("const_one_item_type").body
    assertEquals(SmallTupleTrees.v0(body), "00" + body)
    assertEquals(SmallTupleTrees.v3(body), "0b0a" + body)
    // the 127/128 rows are the ones whose size needs two VLQ bytes — the probe's 8602 / 8802
    assert(SmallTupleTrees.v3(SmallTupleTrees.caseBySlug("tuple_expr_127_items").body)
      .startsWith("0b8602"))
    assert(SmallTupleTrees.v3(SmallTupleTrees.caseBySlug("tuple_expr_128_items").body)
      .startsWith("0b8802"))
  }

  test("the arms: 2 TypeWrite, 1 CountBound, 3 ExprAccept, 4 Control") {
    import SmallTupleTrees._
    assertEquals(cases.count(_.arm == TypeWrite), 2)
    assertEquals(cases.count(_.arm == CountBound), 1)
    assertEquals(cases.count(_.arm == ExprAccept), 3)
    assertEquals(cases.count(_.arm == Control), 4)
    assertEquals(cases.size, 10)
    assertEquals(cases.map(_.slug).distinct.size, 10, "slugs must be distinct")
    assertEquals(cases.count(_.rejects), 3, "TypeWrite + CountBound reject")
  }

  test("every kind: 10 cases x 2 versions, plus 2 register rows on the carriers") {
    assertEquals(vectors.keySet, ops.toSet)
    assertEquals(entries(B.OpErgoTree).size, 20, "ErgoTree has no register arm")
    Seq(B.OpBox, B.OpTransaction).foreach(op => assertEquals(entries(op).size, 22, op))
    ops.foreach { op =>
      val es = entries(op)
      // 3 rejecting cases x 2 versions = 6 tree rejects; the carriers add the register reject
      assertEquals(es.count(isReject), if (op == B.OpErgoTree) 6 else 7, s"$op rejects")
      assertEquals(es.count(e => treeVer(e) == 0), if (op == B.OpErgoTree) 10 else 12, s"$op v0")
      assertEquals(es.count(e => treeVer(e) == 3), 10, s"$op v3")
      es.foreach { e =>
        assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty,
          s"${str(e, "name")}: identity round-trip or reject, never a non-identity expected")
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
        assertEquals(e.hcursor.get[String]("kind").toOption,
          Some(op.takeWhile(_ != '.')), str(e, "name"))
      }
    }
  }

  test("the shape that must not rot: which rows claim to separate, and which claim to be controls") {
    ops.foreach { op =>
      entries(op).foreach { e =>
        val (name, d) = (str(e, "name"), str(e, "description"))
        val separating = d.contains("SEPARATING") || d.contains("THE SEPARATING ROW")
        val control = d.contains("THE CONTROL OF THIS PAIR") || d.contains("A WRITABLE TWIN") ||
          d.contains("ALSO A CONTROL")
        assert(separating != control || name.contains("register_one_item_type"),
          s"$name: a row is either evidence or a control, and must say which: $d")
        // The two versioned pairs are MIRRORS, and getting either backwards silently turns
        // evidence into a control. TypeWrite: v0 control, v3 evidence. ExprAccept: the reverse.
        if (name.contains("_type-v0-") && !name.contains("triple"))
          assert(control && !separating, s"$name: the v0 type row is the CONTROL")
        if (name.contains("_type-v3-") && !name.contains("triple"))
          assert(separating && !control, s"$name: the v3 type row is the SEPARATING one")
        if (name.contains("tuple_expr_one_item-v0-") || name.contains("tuple_expr_zero_item-v0-"))
          assert(separating && !control, s"$name: the v0 expression row is the SEPARATING one")
        if (name.contains("tuple_expr_one_item-v3-") || name.contains("tuple_expr_zero_item-v3-"))
          assert(control && !separating, s"$name: the v3 expression row is the CONTROL")
      }
    }
  }

  test("the v0/v3 type rows differ ONLY in the header, so nothing else can explain a red") {
    Seq("const_one_item_type", "const_zero_item_type").foreach { slug =>
      val body = SmallTupleTrees.caseBySlug(slug).body
      ops.foreach { op =>
        val rows = entries(op).filter(e => str(e, "name").contains(s"-$slug-"))
        assertEquals(rows.size, 2, s"$op/$slug")
        rows.foreach(e => assert(str(e, "bytes_hex").contains(body),
          s"${str(e, "name")}: must carry the body verbatim"))
      }
    }
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
