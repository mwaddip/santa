package santa

import io.circe.Json

/** Anchors the AND / OR item-by-item addendum's eval families:
  *
  *  - every file keeps its committed entries (and envelope) first, unchanged (append-only); its schema and slot;
  *  - what is left out: exactly the row-activations a destination file (or an earlier row) already holds, with the
  *    same outcome; and the whole trees whose root the JVM's own parse refuses;
  *  - every outcome, exception class, value and cost agrees with the jars' own measurement (sigma-rust's addendum on
  *    the same 6.0.7; a whole tree's cost is its block cost, our JIT cost / 10);
  *  - the extension travels only with the rows whose tree reads a variable, and the others answer the same with it;
  *  - the scripts are reproducible.
  */
class AuthoredEvalAndOrItemByItemRound2Test extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredEvalAndOrItemByItemRound2
  private val R1 = AuthoredEvalAndOrItemByItemRound
  private lazy val all = B.allRows()
  private lazy val evalRows = B.evalRows()
  private lazy val plan = B.plan()
  private lazy val rows = plan.values.flatMap(_._1).toVector
  private lazy val dropped = plan.values.flatMap(_._2).toVector
  private lazy val outcomes: Map[B.Row, B.Outcome] = evalRows.map(r => r -> B.outcome(r)).toMap
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] = B.entriesOf(v(op))

  test("the files keep their 854bcff entries and envelope first, unchanged; schema and slot; no other file") {
    assertEquals(B.Before.keySet -- v.keySet, Set.empty[String])
    v.keys.foreach { op =>
      val before = B.base(op)
      assertEquals(entries(op).take(before.size), before, op)
      B.committed(op).filter(_ => B.Before.contains(op)).foreach(j =>
        assertEquals(v(op).hcursor.downField("entries").delete.focus, j.hcursor.downField("entries").delete.focus, op))
      assertEquals(entries(op).size, before.size + plan(op)._1.size, op)
      val a = if (op.endsWith("_v6")) 3 else 2
      assertEquals(v(op).hcursor.get[String]("schema").toOption,
        Some(if (op.contains("_context")) "santa-eval/v6-fullctx" else "santa-eval/v2"), op)
      entries(op).foreach(e => assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a), op))
      val names = entries(op).map(_.hcursor.get[String]("name").toOption.get)
      assertEquals(names.distinct.size, names.size, s"$op: entry names unique")
    }
    println(s"files: ${v.keySet.toSeq.sorted.map(op => s"$op ${B.base(op).size}+${plan(op)._1.size}").mkString(", ")}")
    assertEquals(rows.size, 4709)
    assertEquals(dropped.size, 194)
    println(s"rows ${all.size} row-activations; eval tier ${evalRows.size}; appended ${rows.size}; left out as held ${dropped.size}")
  }

  test("left out: the whole trees the JVM's parse refuses (eval tier), and the row-activations already held, same outcome") {
    val refused = all.filter(r => r.part == "whole tree, no context read" && !evalRows.contains(r))
    println(s"whole trees refused by the JVM's parse: ${refused.map(r => s"${r.name} a${r.activated}").mkString(", ")}")
    refused.foreach { r => assert(r.name.contains("|ph|dnnK|"), r.name); assert(r.jarsOutcome != "value", r.name) }
    assert(refused.nonEmpty)
    val held: Map[(String, Int, String), Json] =
      (v.values.flatMap(B.entriesOf).map(e => B.entryKey(e) -> e)).toMap
    dropped.foreach { r =>
      val e = held(B.rowKey(r))
      val err = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
      outcomes(r) match {
        case R1.Answer(value, cost) =>
          assertEquals(err, None, r.name)
          assertEquals(e.hcursor.downField("expected").get[Json]("value").toOption, Some(value), r.name)
          assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(cost), r.name)
        case _ => assertEquals(err, Some("errored"), r.name)
      }
    }
  }

  test("every outcome, class, value and cost agrees with the jars' measurement") {
    evalRows.foreach { r =>
      val k = s"${r.part}: ${r.name} a${r.activated}"
      outcomes(r) match {
        case R1.Answer(value, cost) =>
          assertEquals(r.jarsOutcome, "value", k)
          assertEquals(if (r.whole) (cost / 10).toString else cost.toString, r.jarsCost, k)
          assertEquals(B.text(value), r.jarsValue, k)
        case R1.ThrowsAtEval(t) =>
          assertEquals(r.jarsOutcome, "error", k)
          assert(R1.chain(t).map(_.getClass.getSimpleName).contains(r.jarsValue), s"$k: ${R1.chain(t)} vs ${r.jarsValue}")
        case R1.RefusedAtParse(_) =>
          assert(Set("error", "refused at parse")(r.jarsOutcome), k)
      }
    }
  }

  test("the extension travels only with the rows that read a variable; the others answer the same with it") {
    def key(o: B.Outcome) = o match {
      case R1.Answer(x, c)      => s"${x.noSpaces} @$c"
      case R1.ThrowsAtEval(t)   => s"throws ${R1.chain(t).last.getClass.getName}"
      case R1.RefusedAtParse(_) => "parse"
    }
    val ctxRows = rows.filter(_.inContext)
    val readers = ctxRows.filter(B.readsVariable)
    println(s"context rows ${ctxRows.size}; reading a variable ${readers.size}: ${readers.map(_.f(1)).distinct.sorted.mkString(", ")}")
    ctxRows.filterNot(B.readsVariable).foreach { r =>
      val full = r.ctx.ext.map { case (k, h) => k -> B.valueBinding(h) }
      assertEquals(key(B.outcomeWith(r, full)), key(outcomes(r)), r.name)
    }
    v.keys.filter(_.contains("_context")).foreach(op => entries(op).drop(B.base(op).size).foreach { e =>
      val s = e.hcursor.get[String]("script").toOption.get
      val name = s.substring(1, s.indexOf("] "))
      val r = rows.find(x => x.inContext && x.name == name && e.hcursor.get[String]("tree_bytes_hex").toOption.contains(x.treeHex)).get
      val ext = e.hcursor.downField("context").downField("extension").keys.map(_.toSet).getOrElse(Set())
      assertEquals(ext.nonEmpty, B.readsVariable(r), name)
    })
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
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "and-or-item-by-item-round2")
    B.writeVectors(outDir)
  }
}
