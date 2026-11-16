package santa

import io.circe.Json

/** Anchors the serialize-costed-writer eval families. `SpecExtract` fails loud if an entry's outcome changes between the
  * label and the bless, and the label fails loud where the oracle's outcome is not what it says; pinned here is what
  * would rot silently while still grading green:
  *
  *  - the five files, their entry counts, and each entry's version pair (its slot);
  *  - the selection: every row but the 140 `limit` rows and the 17 held ones, and the held ones exactly those whose
  *    nested value is past 64 MiB;
  *  - OUR trees: the JVM writes every vendored expression back byte-identically, but for the `Option[Coll[Int]]` type
  *    of the eight `coll-of-options` rows (written short) and the 26 `String-surrogate` rows (kept as sent);
  *  - every outcome, value and cost at the default limit (the 17 held rows too) agrees with the jars' own measurement
  *    (sigma-rust's context-free probe on the same 6.0.7), an independent second reading;
  *  - the labels' counts, and where sigma-rust's table says fork cc29855f differs in what this tier grades.
  */
class AuthoredEvalSerializeCostedWriterTest extends munit.FunSuite {
  private val B = AuthoredEvalSerializeCostedWriter
  private lazy val rows = B.rows()
  private lazy val authored = rows.filter(_.authored)
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  private def rowName(e: Json): String = str(e, "script").drop(1).takeWhile(_ != ']')
  private def labelled(tag: String): Seq[String] =
    v.keys.toSeq.flatMap(op => entries(op).filter(e => str(e, "script").contains(s". $tag")).map(rowName)).sorted

  test("920 rows; 763 authored into five files at tree v3 / activated 3") {
    assertEquals(rows.size, 920)
    val want = Map(
      "Global.serialize_runtime_class" -> 686,
      "Global.serialize_no_writer_arm" -> 25,
      "Global.serialize_count_range_at_depth" -> 18,
      "Global.serialize_tuple_arm" -> 12,
      "Global.serialize_shared_nested" -> 22)
    assertEquals(v.keySet, want.keySet)
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    assertEquals(authored.size, want.values.sum)
    v.keys.foreach(op => entries(op).foreach { e =>
      assertEquals((ver(e, "activated"), ver(e, "ergoTree")), (3, 3), s"$op ${str(e, "name")}")
      assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput))
    })
    val names = v.keys.toSeq.flatMap(op => entries(op).map(rowName))
    assertEquals(names.distinct.size, names.size)
    assertEquals(names.toSet, authored.map(_.name).toSet)
  }

  test("left out: the limit rows (another cost limit) and the 17 held (a nested value past 64 MiB)") {
    val limit = rows.filter(_.set == "limit")
    assertEquals(limit.size, 140)
    assert(limit.forall(r => r.limit != B.DefaultJitLimit && !r.authored))
    assert(rows.filter(_.set != "limit").forall(_.limit == B.DefaultJitLimit))
    val held: Seq[String] = rows.filter(_.held).map(_.name).sorted
    assertEquals(held, Seq(
      "E3|serialize-long-is-nested|32768x1024x1024",
      "E|serialize-bytes-is-nested|32768x32768", "E|serialize-long-is-nested|32768x32768",
      "G3|size-only|32768x1024x1024", "G3|size-only|32768x256x256", "G3|size-only|32768x32768x32768",
      "G3|size-serialize|32768x1024x1024", "G3|size-serialize|32768x256x256", "G3|size-serialize|32768x32768x32768",
      "G|size-only|32768x16384", "G|size-only|32768x32768", "G|size-only|32768x4096", "G|size-only|32768x8192",
      "G|size-serialize|32768x16384", "G|size-serialize|32768x32768", "G|size-serialize|32768x4096",
      "G|size-serialize|32768x8192"))
    // every row on which fork cc29855f ABORTS is held; the largest authored nested value is 64 MiB
    assertEquals(rows.filter(_.forkBase.startsWith("ABORT")).filterNot(_.held), Vector.empty)
    assertEquals(authored.flatMap(_.nestedBytes).max, 64L << 20)
  }

  test("our trees: written back as read, but for coll-of-options' type (short form) and String-surrogate (kept)") {
    var identical = 0
    rows.filter(_.set != "limit").foreach { r =>
      if (r.keepsVendored) {
        assertEquals(r.expr, r.body, r.name)
        assertEquals(r.written, r.body.replace("eda080", "efbfbd"), s"${r.name}: only the three bytes differ")
      } else if (r.name.startsWith("A|coll-of-options|")) {
        // the collection's item type Option[Coll[Int]]: `24 10` as sent, `34` (Option of Coll[Int]) as the JVM writes it
        assertEquals(r.written, r.body.replaceFirst("83042410", "830434"), r.name)
      } else { assertEquals(r.written, r.body, r.name); identical += 1 }
      // a wrapped row is SizeOf (opcode B1) of the JVM's writing of the vendored expression
      if (r.wrapped) assertEquals(r.expr, "b1" + r.written, r.name) else if (!r.keepsVendored) assertEquals(r.expr, r.written)
    }
    assertEquals(rows.filter(_.wrapped).map(_.name).sorted.mkString(" "), Seq(
      "C|coll-item0|65536", "C|coll-item1-of-big|65536", "C|coll-item1|65536", "C|order|cast-then-count",
      "C|order|count-then-cast", "C|order|count-then-noarm", "C|order|noarm-then-count", "C|pair-first|65536",
      "C|pair-second|65536", "C|some|65536", "C|top|65536",
      "E3|serialize-long-is-nested|32768x1024x1024",
      "E|serialize-bytes-is-nested|32768x1024", "E|serialize-bytes-is-nested|32768x16",
      "E|serialize-bytes-is-nested|32768x32768", "E|serialize-long-is-nested|32768x1024",
      "E|serialize-long-is-nested|32768x16", "E|serialize-long-is-nested|32768x32768").mkString(" "))
    assertEquals(rows.count(r => r.set != "limit" && r.keepsVendored), 26)
    assertEquals(rows.count(_.name.startsWith("A|coll-of-options|")), 8)
    assertEquals(identical, 780 - 26 - 8)
  }

  test("every outcome, value and cost at the default limit agrees with the jars' own measurement") {
    rows.filter(_.set != "limit").foreach { r =>
      // the jars measured the vendored expression bare; a wrapped row is read against them that way
      r.jarsOutcome match {
        case "value" =>
          assert(!r.wrapped, r.name)
          val (value, cost) = r.bareOutcome.toOption.getOrElse(fail(s"${r.name}: refused, the jars answer"))
          assertEquals(B.jarsText(value), r.jarsValue, r.name)
          assertEquals(cost.toString, r.jarsCost, r.name)
        case "limit" => assert(B.costLimit(r.bareOutcome), s"${r.name}: not the cost limit")
        case t if t.startsWith("throws ") =>
          val cls = t.stripPrefix("throws ")
          Seq(r.bareOutcome, r.outcome).foreach { out =>
            val o = out.left.toOption.getOrElse(fail(s"${r.name}: answers, the jars throw"))
            assert(B.chain(o).exists(_.getClass.getSimpleName == cls), s"${r.name}: no $cls in the cause chain")
          }
      }
    }
    // the vector's expectation is that outcome
    val byName = authored.map(r => r.name -> r).toMap
    v.keys.foreach(op => entries(op).foreach { e =>
      val r = byName(rowName(e))
      assertEquals(str(e, "tree_bytes_hex"), r.treeHex, r.name)
      assertEquals(err(e).isDefined, r.jarsOutcome != "value", r.name)
    })
  }

  test("labels: what each family's rows rest on") {
    val a = authored.filter(_.family == "A")
    assertEquals(labelled("ANOTHER CLASS:").size, a.count(_.outcome.isLeft))
    assertEquals(a.count(_.outcome.isLeft), 318) // 343 bad rows, but the 25 whose arm casts nothing
    assertEquals(labelled("STATIC TYPE:").size, 25)
    assertEquals(labelled("RESULT TOO LONG"), Seq(
      "G|size-serialize|32768x16", "G|size-serialize|32768x256", "G|size-serialize|32768x4",
      "G|size-serialize|32768x64"))
    assertEquals(labelled("COST LIMIT"), Seq(
      "G|size-serialize|32768x1024", "G|size-serialize|32768x2048", "G|size-serialize|32768x512"))
    assertEquals(labelled("COUNT REFUSED").size, 7)
    assertEquals(labelled("TUPLE LENGTH").size, 4)
    assertEquals(labelled("NOT A TUPLE").size, 3)
    assertEquals(labelled("NO WRITER ARM").size, 15)
    assertEquals(labelled("ZERO-WIDTH ITEMS").size, 5)
  }

  test("sigma-rust's table: what fork cc29855f answers differently, of what this tier grades") {
    // in the authored set, only the four RESULT TOO LONG rows (the fork's open item 33; still red at its tip f8ce0c08)
    assertEquals(authored.filterNot(r => r.gradeable.startsWith("none")).map(_.name).sorted: Seq[String],
      labelled("RESULT TOO LONG"))
    assert(authored.filter(_.gradeable.startsWith("outcome")).forall(_.forkBase.startsWith("value")))
  }

  test("envelope + write staging files") {
    v.foreach { case (op, f) =>
      assertEquals(f.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp), op)
      assertEquals(f.hcursor.get[String]("source").toOption, Some(B.Source), op)
      assertEquals(f.hcursor.get[String]("schema").toOption, Some("santa-eval/v2"), op)
    }
    // a re-bless is byte-identical: no identity hash (they differ run to run) is left in a script
    v.keys.foreach(op => entries(op).foreach(e =>
      assert("""(?<=[\w$;\[])@[0-9a-f]{1,8}(?!\w)""".r.findFirstIn(str(e, "script")).isEmpty, str(e, "name"))))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    v.keys.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), op))
  }
}
