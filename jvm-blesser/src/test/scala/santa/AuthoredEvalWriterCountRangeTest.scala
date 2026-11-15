package santa

import io.circe.Json
import scorex.util.encode.Base16

/** Anchors the writer-count-range eval families. `SpecExtract` fails loud if an entry's outcome changes between the
  * label and the bless; pinned here is what would rot silently while still grading green:
  *
  *  - the eight files, their entry counts, and each entry's version pair (its slot);
  *  - OUR trees: the JVM writer re-serializes every vendored expression byte-identically, but for the two Boolean rows
  *    whose packed constant sets a padding bit (and there only in that bit);
  *  - every outcome, value and cost agrees with the jars' own measurement (sigma-rust's context-free probe on the same
  *    6.0.7), an independent second reading; a wrapped row through its bare expression, its digest re-computed;
  *  - WRITER REFUSES exactly where the count is past the writer's range AND the path writes it, with the range check's
  *    own message in the cause chain.
  */
class AuthoredEvalWriterCountRangeTest extends munit.FunSuite {
  private val B = AuthoredEvalWriterCountRange
  private lazy val rows = B.rows()
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  private def value(e: Json): Json = e.hcursor.downField("expected").downField("value").focus.getOrElse(fail("value"))
  private def rowName(e: Json): String = str(e, "script").drop(1).takeWhile(_ != ']')
  private def entry(op: String, name: String, a: Int): Json =
    entries(op).find(e => rowName(e) == name && ver(e, "activated") == a).getOrElse(fail(s"no $name at $a in $op"))
  private def bytesOf(j: Json): Array[Byte] =
    j.hcursor.downField("items").as[List[Json]].toOption.get.map(_.hcursor.get[Int]("value").toOption.get.toByte).toArray

  test("eight files; versions in their slot") {
    val want = Map(
      "Global.deserializeTo_Box_type_var_name_range" -> 66,
      "SubstConstants.type_var_name_range_v4" -> 15, "SubstConstants.type_var_name_range" -> 15,
      "SubstConstants.type_var_name_range_v6" -> 30,
      "Global.serialize_coll_count_range" -> 12,
      "SubstConstants.coll_count_range_v4" -> 2, "SubstConstants.coll_count_range" -> 2,
      "SubstConstants.coll_count_range_v6" -> 4)
    assertEquals(v.keySet, want.keySet)
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    assertEquals(rows.size, want.values.sum)
    v.keys.foreach { op =>
      val allowed = if (op.endsWith("_v4")) Set(0, 1) else if (op.endsWith("_v6") || op.startsWith("Global.")) Set(3)
                    else Set(2)
      entries(op).foreach { e =>
        assert(allowed(ver(e, "activated")), s"$op ${str(e, "name")}")
        assert(ver(e, "ergoTree") <= ver(e, "activated"), s"$op ${str(e, "name")}: tree above activated")
        assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput))
      }
    }
  }

  test("our trees: every vendored expression written back as read, but for the padding bit of two Boolean rows") {
    rows.filterNot(_.wrapped).foreach { r =>
      if (B.PaddingBitRows(r.name)) {
        assertNotEquals(r.expr, r.body, r.name)
        assertEquals(r.expr.length, r.body.length, r.name)
        val diff = r.expr.indices.filter(i => r.expr(i) != r.body(i)).map(_ / 2).distinct
        assertEquals(diff.size, 1, r.name)
        val (ours, theirs) = (Base16.decode(r.expr).get(diff.head), Base16.decode(r.body).get(diff.head))
        assertEquals((ours & 0xff, theirs & 0xff), (0x7f, 0xff), s"${r.name}: the one byte is the padding bit")
      } else assertEquals(r.expr, r.body, s"${r.name} a${r.activated}")
    }
    // a wrapped row is blake2b256 (opcode CB) of the JVM's writing of the vendored expression
    rows.filter(_.wrapped).foreach(r => assert(r.expr.startsWith("cb"), r.name))
    assertEquals(rows.count(r => B.PaddingBitRows(r.name)), 2)
  }

  test("every outcome, value and cost agrees with the jars' own measurement; the tree is the vector's") {
    val byKey = rows.map(r => (r.op, r.name, r.activated) -> r).toMap
    v.keys.foreach(op => entries(op).foreach { e =>
      val r = byKey((op, rowName(e), ver(e, "activated")))
      assertEquals(str(e, "tree_bytes_hex"), r.treeHex, r.name)
      assertEquals(ver(e, "ergoTree"), r.treeVersion, r.name)
      // a wrapped row is read against the jars through its bare expression
      val bare = if (r.wrapped) B.outcome(r.bareTreeHex, r.activated) else B.outcome(r.treeHex, r.activated)
      r.jarsOutcome match {
        case "throws" =>
          assertEquals(err(e), Some("errored"), s"${r.name} a${r.activated}")
          assert(bare.isLeft, s"${r.name} a${r.activated}: the bare expression answers")
        case "value" =>
          assertEquals(err(e), None, s"${r.name} a${r.activated}")
          val (bv, bc) = bare.toOption.getOrElse(fail(s"${r.name}: the bare expression refused"))
          val ours = B.text(bv)
          // the jars print a collection's first 100 items
          assert(ours == r.jarsValue || ours.startsWith(r.jarsValue.stripSuffix(")") + ","),
            s"${r.name} a${r.activated}: $ours vs ${r.jarsValue}")
          assertEquals(bc.toString, r.jarsCost, s"${r.name} a${r.activated}")
          if (r.wrapped) {
            val digest = scorex.crypto.hash.Blake2b256(bytesOf(bv)).toSeq
            assertEquals(bytesOf(value(e)).toSeq, digest, s"${r.name}: the digest of the bare value")
          }
      }
    })
  }

  test("WRITER REFUSES exactly where a count past the range is written, the range check in the cause chain") {
    var refused = Map.empty[String, Int].withDefaultValue(0)
    rows.foreach { r =>
      val e = entry(r.op, r.name, r.activated)
      val o = B.outcome(r.treeHex, r.activated)
      val w = B.writerRefusal(o)
      assertEquals(str(e, "script").contains("WRITER REFUSES"), w.isDefined, s"${r.name} a${r.activated}")
      if (w.isDefined) {
        assert(r.overRange && r.writes, s"${r.name}: refused by the writer at ${r.count}")
        refused += r.op -> (refused(r.op) + 1)
      }
      // a count past the range on a path that writes it is refused (by the writer, or before it)
      if (r.overRange && r.writes) assert(o.isLeft, s"${r.name} a${r.activated}: written past the range")
    }
    // sigma-rust's table: 25 rows differ on its fork at 94ed4325, every one a writer refusal here
    assertEquals(refused.values.sum, 25)
    assertEquals(refused("Global.serialize_coll_count_range"), 3)
    assertEquals(refused("SubstConstants.coll_count_range_v6") + refused("SubstConstants.coll_count_range") +
      refused("SubstConstants.coll_count_range_v4"), 4)
    assertEquals(refused("Global.deserializeTo_Box_type_var_name_range"), 8)
    // a long name refuses in a constant at tree v3 (None of Option[T], a Box) and in a Box constant at tree v0
    assertEquals(refused("SubstConstants.type_var_name_range_v6"), 6)
    assertEquals(refused("SubstConstants.type_var_name_range"), 2)
    assertEquals(refused("SubstConstants.type_var_name_range_v4"), 2)
  }

  test("the pairs: one item / one byte of text either side of the range") {
    val ser = "Global.serialize_coll_count_range"
    Seq("bytes", "bools", "ints").foreach { k =>
      assertEquals(err(entry(ser, s"serc|$k|65535", 3)), None, k)
      assertEquals(err(entry(ser, s"serc|$k|65536", 3)), Some("errored"), k)
      assertEquals(B.text(value(entry(ser, s"sizec|$k|65536", 3))), "65536", k)
    }
    val dto = "Global.deserializeTo_Box_type_var_name_range"
    Seq("unsized", "sized").foreach { t =>
      assertEquals(err(entry(dto, s"dto|$t|serialize|ff85", 3)), None, t)
      assertEquals(err(entry(dto, s"dto|$t|serialize|mix256", 3)), Some("errored"), t)
      // the bytes as read answer even for a name the writer refuses; written back they differ (FF is U+FFFD)
      assertEquals(err(entry(dto, s"dto|$t|bytes|ff86", 3)), None, t)
      assertNotEquals(B.text(value(entry(dto, s"dto|$t|bytes|ff85", 3))),
        B.text(value(entry(dto, s"dto|$t|bytesWithoutRef|ff85", 3))), t)
    }
  }

  test("envelope + write staging files") {
    v.foreach { case (op, f) =>
      assertEquals(f.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp), op)
      assertEquals(f.hcursor.get[String]("source").toOption, Some(B.Source), op)
      assertEquals(f.hcursor.get[String]("schema").toOption, Some("santa-eval/v2"), op)
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    v.keys.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), op))
  }
}
