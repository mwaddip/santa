package santa

import io.circe.Json

/** Anchors the AND / OR item-by-item eval families. `SpecExtract` fails loud if an entry's outcome changes between the
  * label and the bless; pinned here is what would rot silently while still grading green:
  *
  *  - the four files, their entry counts, and each entry's version pair (its slot);
  *  - sigma-rust's three measurements on the 6.0.7 jars (an independent reading): `sigmaProp(OR((true, 5, true)))`
  *    answers, and over 100 items deciding at index 0 AND costs 20, OR 15;
  *  - every ladder cost is the PerItemCost of the items READ (`trees.scala`: AND base 10 + 5 per chunk of 32, OR base
  *    5 + 5 per chunk of 64), on top of the same constant tree cost: a second reading of the source;
  *  - every row answers the same at its three slots;
  *  - the labels' premises: each class answers or throws as it says, with the exception it says.
  */
class AuthoredEvalAndOrItemByItemTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredEvalAndOrItemByItem
  private lazy val slots = B.slots()
  private lazy val outcomes: Map[B.Slot, B.Outcome] = slots.map(s => s -> B.outcome(s)).toMap
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def at(name: String, tv: Int, a: Int): B.Outcome =
    outcomes(slots.find(s => s.row.name == name && s.treeVersion == tv && s.activated == a).getOrElse(fail(name)))

  test("four files; versions in their slot") {
    assertEquals(B.baseRows().map(_.name).distinct.size, 313)
    val want = Map("AND.item_by_item" -> 156, "AND.item_by_item_v6" -> 312, "OR.item_by_item" -> 157,
      "OR.item_by_item_v6" -> 314)
    assertEquals(v.keySet, want.keySet)
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    v.keys.foreach { op =>
      val a = if (op.endsWith("_v6")) 3 else 2
      assertEquals(v(op).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"), op)
      entries(op).foreach { e =>
        assertEquals(ver(e, "activated"), a, op)
        if (a == 2) assertEquals(ver(e, "ergoTree"), 0, op)
        assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput))
      }
    }
  }

  test("sigma-rust's three measurements on the jars") {
    Seq((0, 2), (0, 3), (3, 3)).foreach { case (tv, a) =>
      at(B.SigmaRustRow.name, tv, a) match {
        case B.Answer(value, _) =>
          // TrueProp: the same value `sigmaProp(true)` answers
          val trueProp = EvalCore.evalAppliedOutcome(if (tv == 0) "00d17f" else "0b02d17f", B.IntInput, a.toByte)._2
          assertEquals(Option(value), trueProp.toOption.map(_._1), s"tv$tv a$a")
        case o => fail(s"sigma-rust's row tv$tv a$a: ${B.said(o)}")
      }
      assertEquals(at("AND|ladder|L100|k0", tv, a), B.Answer(Json.obj("kind" -> Json.fromString("Boolean"),
        "value" -> Json.fromBoolean(false)), 20L))
      assertEquals(at("OR|ladder|L100|k0", tv, a), B.Answer(Json.obj("kind" -> Json.fromString("Boolean"),
        "value" -> Json.fromBoolean(true)), 15L))
    }
  }

  test("every ladder cost is the PerItemCost of the items read") {
    val tree = Map("AND" -> 5L, "OR" -> 5L) // the constant input's own cost, the same for every ladder row
    val base = Map("AND" -> 10L, "OR" -> 5L)
    slots.filter(_.row.group == "ladder").foreach { s =>
      val (_, _, read) = B.ladderOf(s.row)
      val want = tree(s.row.op) + base(s.row.op) + 5L * B.chunks(s.row.op, read)
      outcomes(s) match {
        case B.Answer(_, cost) => assertEquals(cost, want, s"${s.row.name} tv${s.treeVersion} a${s.activated}")
        case o                 => fail(s"${s.row.name}: ${B.said(o)}")
      }
    }
  }

  test("every row answers the same at its three slots") {
    slots.groupBy(_.row).foreach { case (r, ss) =>
      assertEquals(ss.map(outcomes).map {
        case B.Answer(value, cost) => s"${value.noSpaces} @$cost"
        case B.ThrowsAtEval(t)     => s"throws ${B.chain(t).map(_.getClass.getName).mkString("<-")}"
        case B.RefusedAtParse(_)   => "refused"
      }.distinct.size, 1, r.name)
    }
  }

  test("the labels' premises") {
    slots.foreach { s =>
      val k = s"${s.row.name} tv${s.treeVersion} a${s.activated}"
      val c = B.cls(s.row)
      (c, outcomes(s)) match {
        case ("CHUNKS" | "NEVER READ" | "CONTRAST", B.Answer(_, _)) =>
        case ("CONTROL", B.Answer(_, _)) if s.row.group != "expr" =>
        case ("CONTROL", B.ThrowsAtEval(t)) if s.row.group == "expr" =>
          assert(B.chain(t).last.isInstanceOf[ArithmeticException], k)
        case ("MOVE", B.ThrowsAtEval(t)) => assert(B.chain(t).last.isInstanceOf[ClassCastException], k)
        case ("EAGER", B.ThrowsAtEval(t)) => assert(B.chain(t).last.isInstanceOf[ArithmeticException], k)
        case ("EAGER", B.Answer(_, _)) => assert(Set("d-divone", "d-blake")(s.row.label), k)
        case (cl, o) => fail(s"$k: $cl but ${B.said(o)}")
      }
    }
    // the EAGER values charge the item past the decision: more than the same call over two constants
    Seq("AND", "OR").foreach { op =>
      val two = at(s"$op|tuple|dnn|Boolean", 0, 3).asInstanceOf[B.Answer].cost
      assert(at(s"$op|expr|d-divone", 0, 3).asInstanceOf[B.Answer].cost > two, op)
      assert(at(s"$op|expr|d-blake", 0, 3).asInstanceOf[B.Answer].cost >
        at(s"$op|expr|d-divone", 0, 3).asInstanceOf[B.Answer].cost, op)
    }
    v.values.foreach(_.hcursor.downField("entries").as[List[Json]].toOption.get.foreach { e =>
      val s = e.hcursor.get[String]("script").toOption.get
      assert(!s.contains("loader") && !s.matches(".*@[0-9a-f]{6,}.*"), s.take(80))
    })
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "and-or-item-by-item")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 4L)
  }
}
