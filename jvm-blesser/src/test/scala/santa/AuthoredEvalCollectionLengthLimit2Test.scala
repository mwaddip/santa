package santa

import io.circe.Json

/** Anchors the collection-length-limit addendum (when `flatMap` refuses, and the fan-out):
  *
  *  - every row agrees with the jars' own measurement: the outcome, the exception class and the item count its message
  *    names, the value, and the evaluator's counter, at the throw too;
  *  - every row is authored under `SizeOf` and answers as the row (the same class, message and counter; its length at
  *    `SizeOfCost` more);
  *  - the files keep their 829693b entries and envelope first, unchanged (append-only); left out exactly the repeats
  *    of a tree and activated version (the same outcome); no fan-out row above 64 items a call;
  *  - the scripts are reproducible.
  */
class AuthoredEvalCollectionLengthLimit2Test extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredEvalCollectionLengthLimit2
  private val E = AuthoredEvalCollectionLengthLimit
  private lazy val all = B.allRows()
  private lazy val plan = B.plan()
  private lazy val rows = plan.values.flatMap(_._1).toVector
  private lazy val repeats = plan.values.flatMap(_._2).toVector
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] = E.entriesOf(v(op))

  test("every row agrees with the jars: outcome, class and count, value, the counter (at the throw too)") {
    all.foreach { r =>
      val m = E.measure(r.rowTreeHex, r.activated, r.treeVersion, r.limit)
      val k = s"${r.name} a${r.activated}"
      assertEquals(m.outcome, r.jarsOutcome, k)
      if (r.jarsOutcome == "value") assertEquals(m.what, r.jarsValue, k)
      else {
        assert(m.what.split("<-").contains(r.jarsValue.takeWhile(_ != ' ')), s"$k: ${m.what} vs ${r.jarsValue}")
        "the limit, ([0-9]+) items".r.findFirstMatchIn(r.jarsValue).map(_.group(1)).foreach(n =>
          assert(m.msg.contains(s" $n "), s"$k: '${m.msg}' names $n items"))
      }
      assertEquals(m.counter.toString, r.jarsCost, k)
    }
    println(s"${all.size} row-activations agree")
  }

  test("every row under SizeOf answers as the row; no fan-out row above 64 items a call") {
    all.foreach { r =>
      val k = s"${r.name} a${r.activated}"
      assert(E.collectionRooted(r), k)
      val row = E.measure(r.rowTreeHex, r.activated, r.treeVersion, None)
      val w = E.measure(E.treeHex(r), r.activated, r.treeVersion, None)
      assertEquals(w.outcome, row.outcome, k)
      if (row.outcome == "value") {
        assertEquals(s"a collection of ${w.what} items", row.what, k)
        assertEquals(w.counter, row.counter + E.SizeOfCost, k)
      } else assertEquals((w.what, w.msg, w.counter), (row.what, row.msg, row.counter), k)
    }
    val fans = all.filter(_.section == "fan").map(_.fields(2)).distinct.sorted
    assertEquals(fans, Vector("65536x2", "65536x64"))
  }

  test("the files keep their 829693b entries and envelope first, unchanged; left out the repeats (same outcome)") {
    assertEquals(v.keySet, B.Before.keySet)
    v.keys.foreach { op =>
      val before = B.base(op)
      assertEquals(entries(op).take(before.size), before, op)
      assertEquals(v(op).hcursor.downField("entries").delete.focus, E.committed(op).get.hcursor.downField("entries").delete.focus, op)
      assertEquals(entries(op).size, before.size + plan(op)._1.size, op)
      val names = entries(op).map(_.hcursor.get[String]("name").toOption.get)
      assertEquals(names.distinct.size, names.size, s"$op: entry names unique")
    }
    repeats.foreach { r =>
      val held = entries(E.op(r)).find(e => B.entryKey(e) == E.key(r)).get
      val err = held.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
      E.outcome(r) match {
        case AuthoredEvalAndOrItemByItemRound.Answer(value, cost) =>
          assertEquals(err, None, r.name)
          assertEquals(held.hcursor.downField("expected").get[Json]("value").toOption, Some(value), r.name)
          assertEquals(held.hcursor.downField("expected").get[Long]("cost").toOption, Some(cost), r.name)
        case _ => assertEquals(err, Some("errored"), r.name)
      }
    }
    println(s"files: ${v.keySet.toSeq.sorted.map(op => s"$op ${B.base(op).size}+${plan(op)._1.size}").mkString(", ")}; " +
      s"rows ${all.size} row-activations; appended ${rows.size}; repeats ${repeats.size}: " +
      repeats.map(r => s"${r.name} a${r.activated}").mkString(", "))
  }

  test("reproducible scripts") {
    v.values.foreach(E.entriesOf(_).foreach { e =>
      val s = e.hcursor.get[String]("script").toOption.get
      assert(!s.matches(".*@[0-9a-f]{6,}.*"), s.take(120))
      assert(!s.contains("loader") && !s.contains("argument type mismatch") && !s.matches(".*Lambda\\$[0-9].*"), s.take(120))
    })
  }

  test("write staging files") {
    B.writeVectors(java.nio.file.Paths.get("target", "eval-authored", "collection-length-limit-2"))
  }
}
