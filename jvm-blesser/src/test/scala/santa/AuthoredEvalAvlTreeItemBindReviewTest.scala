package santa

import io.circe.Json

/** Anchors the AvlTree item-bind addendum's eval families (`AvlTree.item_bind_context{,_v6}`, `_edges{,_v6}`):
  *
  *  - the four files, their entry counts, each entry's slot and schema;
  *  - what is left out: exactly the rows the jars refuse at parse, refused at parse here too;
  *  - the jars answer tree versions 1 and 2 as 0 (why tree v0 stands for them);
  *  - every outcome, exception class, value and cost agrees with the jars' own measurement (sigma-rust's review, on the
  *    same 6.0.7); an AvlTree value's DIGEST is compared byte for byte with the one the jars print, and differs only
  *    where the value holds our pre-header's parent id;
  *  - the extension travels only with the rows that read a variable, and every other context row answers the same
  *    with it;
  *  - the scripts are reproducible.
  */
class AuthoredEvalAvlTreeItemBindReviewTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredEvalAvlTreeItemBindReview
  private lazy val all = B.allRows()
  private lazy val rows = B.rows()
  private lazy val outcomes: Map[(String, String, Int), B.Outcome] = all.map(r => (r.set, r.name, r.activated) -> B.outcome(r)).toMap
  private def outcome(r: B.Row): B.Outcome = outcomes((r.set, r.name, r.activated))
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def rowName(e: Json): String = { val s = e.hcursor.get[String]("script").toOption.get; s.substring(1, s.indexOf("] ")) }

  test("four files; versions in their slot") {
    val want = rows.groupBy(_.op).map { case (op, rs) => op -> rs.size }
    assertEquals(v.keySet, Set("AvlTree.item_bind_context", "AvlTree.item_bind_context_v6", "AvlTree.item_bind_edges",
      "AvlTree.item_bind_edges_v6"))
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    println(s"entries: ${want.toSeq.sortBy(_._1).mkString(", ")}; left out (parse): ${all.size - rows.size}")
    v.keys.foreach { op =>
      val a = if (op.endsWith("_v6")) 3 else 2
      assertEquals(v(op).hcursor.get[String]("schema").toOption,
        Some(if (op.contains("_context")) "santa-eval/v6-fullctx" else "santa-eval/v2"), op)
      entries(op).foreach { e =>
        assertEquals(ver(e, "activated"), a, op)
        if (a == 2) assertEquals(ver(e, "ergoTree"), 0, op)
      }
    }
  }

  test("left out: exactly the rows the jars refuse at parse, refused at parse here") {
    all.filter(_.jarsParse).foreach { r =>
      assert(outcome(r).isInstanceOf[B.RefusedAtParse], s"${r.name} a${r.activated}: ${B.said(outcome(r))}")
      assert(r.name.startsWith("insertOrUpdate|") || r.name.contains("Header-const"), r.name)
      assertEquals(r.treeVersion, 0, r.name)
    }
    rows.foreach(r => assert(!outcome(r).isInstanceOf[B.RefusedAtParse], s"${r.name}: refused at parse here"))
  }

  test("the jars answer tree versions 1 and 2 as tree version 0") {
    Seq("rv-ctx", "rv-free").foreach { set =>
      Seq(2, 3).foreach { a =>
        val j = B.lines(s"jvm-$set-act$a.txt").map { l => val i = l.indexOf('\t'); l.take(i) -> l.drop(i + 1) }.toMap
        j.keys.filter(_.endsWith("|v0")).foreach { n =>
          val base = n.dropRight(3)
          // a generated lambda class's name differs between the runs
          def norm(s: String) = s.replaceAll("\\$\\$Lambda\\$[0-9/x]+", "")
          Seq(1, 2).foreach(tv => assertEquals(norm(j(s"$base|v$tv")), norm(j(n)), s"$set a$a $base v$tv"))
        }
      }
    }
  }

  /** The digests of the AvlTrees a value holds, in order: ours from the JSON, the jars' from their text. */
  private def ourDigests(j: Json): List[String] = j.hcursor.get[String]("kind").toOption match {
    case Some("AvlTree") => List(j.hcursor.get[String]("bytes_hex").toOption.get.take(66))
    case _ => j.asObject.toList.flatMap(_.values.toList.flatMap(ourDigests)) ++ j.asArray.toList.flatMap(_.flatMap(ourDigests))
  }
  private def jarsDigests(s: String): List[String] =
    "CAvlTree\\(AvlTreeData\\(Coll\\(([-0-9,]+)\\)".r.findAllMatchIn(s).map(_.group(1).split(',').map(x => f"${x.toInt & 0xff}%02x").mkString).toList

  test("every outcome, class, value and cost agrees with the jars; AvlTree digests byte for byte") {
    var moved = 0
    rows.foreach { r =>
      val k = s"${r.set} ${r.name} a${r.activated}"
      val j = r.jars
      outcome(r) match {
        case B.ThrowsAtEval(t) =>
          assert(j.startsWith("THROWS("), s"$k: jars $j")
          val cls = j.drop(7).takeWhile(_ != ':')
          assert(B.chain(t).map(_.getClass.getSimpleName).contains(cls), s"$k: ${B.chain(t)} vs $cls")
        case B.Answer(value, cost) =>
          val i = j.lastIndexOf(" @")
          assert(i > 0 && !j.startsWith("THROWS") && !j.startsWith("HARNESS"), s"$k: jars $j")
          assertEquals(cost.toString, j.drop(i + 2), k)
          val jt = j.take(i)
          val (ours, theirs) = (ourDigests(value), jarsDigests(jt))
          assertEquals(ours.size, theirs.size, k)
          if (r.name.contains("preheader-parentId") && ours.nonEmpty) { assertNotEquals(ours, theirs, k); moved += 1 }
          else assertEquals(ours, theirs, k)
          if (theirs.isEmpty) assertEquals(B.text(value), jt, k)
        case o => fail(s"$k: ${B.said(o)}")
      }
    }
    assert(moved > 0, "no value holds our pre-header's parent id")
  }

  test("the extension travels only with the rows that read a variable; the others answer the same with it") {
    val full = B.Ext.map { case (k, h) => k -> B.valueBinding(h) }
    rows.filter(_.inContext).foreach { r =>
      if (!r.readsVariable) {
        def key(o: B.Outcome) = o match {
          case B.Answer(v, c)    => s"${v.noSpaces} @$c"
          case B.ThrowsAtEval(t) => s"throws ${B.chain(t).last.getClass.getName}"
          case B.RefusedAtParse(_) => "parse"
        }
        assertEquals(key(B.outcomeWith(r, full)), key(outcome(r)), r.name)
      }
    }
    v.keys.filter(_.contains("_context")).foreach(op => entries(op).foreach { e =>
      val ext = e.hcursor.downField("context").downField("extension").keys.map(_.toSet).getOrElse(Set())
      assertEquals(ext.nonEmpty, rowName(e).contains("|src|var-"), rowName(e))
      assertEquals(e.hcursor.downField("context").downField("outputs").as[List[String]].toOption, Some(List(B.OutputHex)))
    })
  }

  test("reproducible scripts") {
    v.values.foreach(_.hcursor.downField("entries").as[List[Json]].toOption.get.foreach { e =>
      val s = e.hcursor.get[String]("script").toOption.get
      assert(!s.matches(".*@[0-9a-f]{6,}.*"), s.take(120))
      assert(!s.contains("loader") && !s.contains("argument type mismatch") && !s.matches(".*Lambda\\$[0-9].*"), s.take(120))
    })
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "avltree-item-bind-review")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 4L)
  }
}
