package santa

import io.circe.Json

/** Anchors the collection-length-limit eval families:
  *
  *  - every row agrees with the jars' own measurement (sigma-rust's, on the same 6.0.7): the outcome, the exception class
  *    and the item count its message names, the value, and the evaluator's counter, at the throw too; the cost-limit rows
  *    (not authored) under their own limit; a whole tree's cost is its block cost (our JIT cost / 10, rounded down);
  *  - the authored tree: a collection-rooted row under `SizeOf` answers as the row (the same class, message and counter;
  *    its length at `SizeOfCost` more), every other row is its own bytes;
  *  - the files: new, their schema and slot, names unique; left out exactly the cost-limit rows and the repeats of a tree
  *    and activated version in a file (the same outcome); a committed file begins with this generator's output;
  *  - the scripts are reproducible.
  */
class AuthoredEvalCollectionLengthLimitTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(60, "min")
  private val B = AuthoredEvalCollectionLengthLimit
  private val R1 = AuthoredEvalAndOrItemByItemRound
  private lazy val all = B.allRows()
  private lazy val plan = B.plan()
  private lazy val rows = plan.values.flatMap(_._1).toVector
  private lazy val repeats = plan.values.flatMap(_._2).toVector
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] = B.entriesOf(v(op))

  private def agrees(r: B.Row, m: B.Measured): Unit = {
    val k = s"${r.name} a${r.activated}"
    assertEquals(m.outcome, r.jarsOutcome, k)
    r.jarsOutcome match {
      case "value" => assertEquals(m.what, r.jarsValue, k)
      case "error" =>
        val cls = r.jarsValue.takeWhile(_ != ' ')
        assert(m.what.split("<-").contains(cls), s"$k: ${m.what} vs ${r.jarsValue}")
        "the limit, ([0-9]+) items".r.findFirstMatchIn(r.jarsValue).map(_.group(1)).foreach(n =>
          assert(m.msg.contains(s" $n "), s"$k: '${m.msg}' names $n items"))
      case "refused at parse" => assert(m.what.split("<-").contains(r.jarsValue), s"$k: ${m.what} vs ${r.jarsValue}")
    }
    if (r.jarsCost.nonEmpty) assertEquals((if (r.whole) m.counter / 10 else m.counter).toString, r.jarsCost, k)
  }

  test("every row agrees with the jars: outcome, class and count, value, the counter (at the throw too)") {
    all.foreach(r => agrees(r, B.measure(r.rowTreeHex, r.activated, r.treeVersion, r.limit)))
    println(s"${all.size} row-activations agree: ${all.count(_.limit.isEmpty)} without a limit, ${all.count(_.limit.isDefined)} under their own")
  }

  test("the authored tree: under SizeOf a collection-rooted row answers as the row; every other row is its own bytes") {
    val evalRows = B.evalRows()
    val (wrapped, own) = evalRows.partition(B.collectionRooted)
    own.foreach(r => assertEquals(B.treeHex(r), r.rowTreeHex, r.name))
    wrapped.foreach { r =>
      val k = s"${r.name} a${r.activated}"
      val row = B.measure(r.rowTreeHex, r.activated, r.treeVersion, None)
      val w = B.measure(B.treeHex(r), r.activated, r.treeVersion, None)
      assertEquals(w.outcome, row.outcome, k)
      if (row.outcome == "value") {
        assertEquals(s"a collection of ${w.what} items", row.what, k)
        assertEquals(w.counter, row.counter + B.SizeOfCost, k)
      } else assertEquals((w.what, w.msg, w.counter), (row.what, row.msg, row.counter), k)
    }
    println(s"wrapped ${wrapped.size}, own bytes ${own.size} (${own.map(_.section).distinct.sorted.mkString(", ")})")
    assertEquals(wrapped.size, 1471)
  }

  test("the files: new, schema and slot, names unique; left out the cost-limit rows and the repeats (same outcome)") {
    v.keys.foreach { op =>
      assertEquals(v(op).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"), op)
      val a = if (op.endsWith("_v6")) 3 else 2
      entries(op).foreach(e => assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a), op))
      val names = entries(op).map(_.hcursor.get[String]("name").toOption.get)
      assertEquals(names.distinct.size, names.size, s"$op: entry names unique")
      assertEquals(entries(op).size, plan(op)._1.size, op)
    }
    println(s"files: ${v.keySet.toSeq.sorted.map(op => s"$op ${plan(op)._1.size}").mkString(", ")}")
    assertEquals(all.filter(_.limit.isDefined).map(_.section).distinct, Vector("limit"))
    repeats.foreach { r =>
      val first = plan(B.op(r))._1.find(x => B.key(x) == B.key(r)).get
      assertEquals(B.said(B.outcome(r)), B.said(B.outcome(first)), s"${r.name}: a repeat of ${first.name}")
    }
    println(s"rows ${all.size} row-activations; eval tier ${B.evalRows().size}; authored ${rows.size}; repeats ${repeats.size}: " +
      repeats.map(r => s"${r.name} a${r.activated}").mkString(", "))
  }

  test("a committed file begins with this generator's output (a later addendum appends)") {
    val held = v.keys.toSeq.sorted.flatMap(op => B.committed(op).map(op -> _))
    held.foreach { case (op, j) =>
      assertEquals(B.entriesOf(j).take(entries(op).size), entries(op), op)
      assertEquals(j.hcursor.downField("entries").delete.focus, v(op).hcursor.downField("entries").delete.focus, op)
    }
    println(s"committed: ${held.size} of ${v.size}; appended to since: " +
      held.filter { case (op, j) => B.entriesOf(j).size > entries(op).size }.map(_._1).mkString(", "))
  }

  test("reproducible scripts") {
    v.values.foreach(B.entriesOf(_).foreach { e =>
      val s = e.hcursor.get[String]("script").toOption.get
      assert(!s.matches(".*@[0-9a-f]{6,}.*"), s.take(120))
      assert(!s.contains("loader") && !s.contains("argument type mismatch") && !s.matches(".*Lambda\\$[0-9].*"), s.take(120))
    })
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assert(j.hcursor.get[String]("blessed_by").toOption.isDefined))
    B.writeVectors(java.nio.file.Paths.get("target", "eval-authored", "collection-length-limit"))
  }
}
