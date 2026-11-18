package santa

import io.circe.Json

/** Anchors the AND / OR item-by-item round's eval families:
  *
  *  - every file, its schema and slot; AND and OR start with 5fd218a's entries, unchanged (append-only);
  *  - what is left out: exactly the row-activations 5fd218a holds, with the same outcome;
  *  - every outcome, exception class, value and cost agrees with the jars' own measurement (sigma-rust's round on the
  *    same 6.0.7; a SigmaProp value is compared by cost only, its text being the sigma tree's);
  *  - sigma-rust's class of each row is our outcome's: COST / NEVER READ / VALUE answer, REFUSED does not;
  *  - the extension travels only with the rows that read a variable; the scripts are reproducible.
  */
class AuthoredEvalAndOrItemByItemRoundTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredEvalAndOrItemByItemRound
  private lazy val all = B.allRows()
  private lazy val rows = B.rows()
  private lazy val outcomes: Map[B.Row, B.Outcome] = all.map(r => r -> B.outcome(r)).toMap
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)

  test("the files; AND and OR keep 5fd218a's entries first, unchanged") {
    val first = AuthoredEvalAndOrItemByItem.extract()
    first.keys.foreach { op =>
      val prior = first(op).hcursor.downField("entries").as[List[Json]].toOption.get
      assertEquals(entries(op).take(prior.size), prior, op)
      assertEquals(v(op).hcursor.downField("entries").delete.focus, first(op).hcursor.downField("entries").delete.focus, op)
    }
    val byOp = rows.groupBy(_.op).map { case (op, rs) => op -> rs.size }
    byOp.foreach { case (op, n) =>
      assertEquals(entries(op).size, n + first.get(op).map(_.hcursor.downField("entries").as[List[Json]].toOption.get.size).getOrElse(0), op)
    }
    v.keys.foreach { op =>
      val a = if (op.endsWith("_v6")) 3 else 2
      assertEquals(v(op).hcursor.get[String]("schema").toOption,
        Some(if (op.contains("_context")) "santa-eval/v6-fullctx" else "santa-eval/v2"), op)
      entries(op).foreach(e => assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a), op))
    }
    println(s"files: ${v.keySet.toSeq.sorted.map(op => s"$op ${entries(op).size}").mkString(", ")}")
  }

  test("left out: exactly the row-activations 5fd218a holds, with the same outcome") {
    val dup = all.filter(B.inFirstFamily)
    assertEquals(dup.size, 879)
    dup.foreach { r =>
      val e = B.firstFamily((r.treeHex, r.activated))
      val err = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
      outcomes(r) match {
        case B.Answer(value, cost) =>
          assertEquals(err, None, r.name)
          assertEquals(e.hcursor.downField("expected").get[Json]("value").toOption, Some(value), r.name)
          assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(cost), r.name)
        case _ => assertEquals(err, Some("errored"), r.name)
      }
    }
  }

  test("every outcome, class, value and cost agrees with the jars' measurement; the class is our outcome's") {
    rows.foreach { r =>
      val k = s"${r.name} a${r.activated}"
      outcomes(r) match {
        case B.Answer(value, cost) =>
          assertEquals(r.jarsOutcome, "value", k)
          assertEquals(cost.toString, r.jarsCost, k)
          val ours = B.text(value)
          if (!ours.contains("<sigmaprop>")) assertEquals(ours, r.jarsValue, k)
          assert(Set("COST", "NEVER READ", "VALUE")(r.cls), s"$k: ${r.cls}")
        case B.ThrowsAtEval(t) =>
          assertEquals(r.jarsOutcome, "error", k)
          assert(B.chain(t).map(_.getClass.getSimpleName).contains(r.jarsValue), s"$k: ${B.chain(t)} vs ${r.jarsValue}")
          assertEquals(r.cls, "REFUSED", k)
        case B.RefusedAtParse(_) =>
          assertEquals(r.jarsOutcome, "error", k)
          assertEquals(r.cls, "REFUSED", k)
      }
    }
  }

  test("the extension travels only with the rows that read a variable; the others answer the same with it") {
    val full = B.Ext.map { case (k, h) => k -> B.valueBinding(h) }
    def key(o: B.Outcome) = o match {
      case B.Answer(x, c)      => s"${x.noSpaces} @$c"
      case B.ThrowsAtEval(t)   => s"throws ${B.chain(t).last.getClass.getName}"
      case B.RefusedAtParse(_) => "parse"
    }
    rows.filter(r => r.inContext && !r.readsVariable).foreach(r => assertEquals(key(B.outcomeWith(r, full)), key(outcomes(r)), r.name))
    v.keys.filter(_.contains("_context")).foreach(op => entries(op).foreach { e =>
      val s = e.hcursor.get[String]("script").toOption.get
      val ext = e.hcursor.downField("context").downField("extension").keys.map(_.toSet).getOrElse(Set())
      assertEquals(ext.nonEmpty, s.substring(1, s.indexOf("] ")).split('|')(1) == "var", s.take(60))
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
    v.values.foreach(j => assert(j.hcursor.get[String]("blessed_by").toOption.isDefined))
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "and-or-item-by-item-round")
    B.writeVectors(outDir)
  }
}
