package santa

import io.circe.Json

/** Anchors the AvlTree item-bind eval families. `SpecExtract` fails loud if an entry's outcome changes between the label
  * and the bless; pinned here is what would rot silently while still grading green:
  *
  *  - the two files, their entry counts, and each entry's version pair (its slot);
  *  - what is left out: exactly the row-activations AvlTree.method_call_charge_order holds, with the same outcome;
  *  - every outcome, exception class, value and cost agrees with the jars' own measurement (sigma-rust's probe on the
  *    same 6.0.7), an independent second reading (an AvlTree value is `<tree>` in both texts);
  *  - no row is refused at parse, and a tree-v0 row answers the same at activated 2 and 3;
  *  - the labels' premises: where our oracle refuses (read off its innermost failure) is sigma-rust's class of the row;
  *    CONTROL, NEVER READ and MOVE exactly there; the scripts reproducible (no identity hash, no loader note).
  */
class AuthoredEvalAvlTreeItemBindTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredEvalAvlTreeItemBind
  private lazy val all = B.allRows()
  private lazy val rows = B.rows()
  private lazy val outcomes: Map[(String, Int), B.Outcome] = all.map(r => (r.name, r.activated) -> B.outcome(r)).toMap
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  /** The row's name opens the script as `[<name>] `: names hold `]` themselves (`Coll[Byte]`). */
  private def rowName(e: Json): String = { val s = str(e, "script"); s.substring(1, s.indexOf("] `")) }

  test("two files; versions in their slot") {
    assertEquals(all.size, 4729)
    assertEquals(all.map(_.name).distinct.size, 3377)
    val want = Map(B.Op5 -> 1350, B.Op6 -> 3371)
    assertEquals(v.keySet, want.keySet)
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    assertEquals(want.values.sum, rows.size)
    v.keys.foreach { op =>
      val a = if (op == B.Op6) 3 else 2
      assertEquals(v(op).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"), op)
      entries(op).foreach { e =>
        assertEquals(ver(e, "activated"), a, s"$op ${str(e, "name")}")
        assert(Set(0, 3)(ver(e, "ergoTree")), s"$op ${str(e, "name")}")
        if (a == 2) assertEquals(ver(e, "ergoTree"), 0, s"$op ${str(e, "name")}: a v3 tree in the v5 slot")
        assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput))
      }
    }
  }

  test("left out: exactly the charge-order family's row-activations, with the same outcome") {
    val dup = all.filter(B.inChargeOrder)
    assertEquals(dup.size, 8)
    dup.foreach { r =>
      val c = B.chargeOrder((r.treeHex, r.activated))
      assertEquals(c.ergoTree, r.treeVersion, r.name)
      assertEquals(c.accept, outcomes((r.name, r.activated)).isInstanceOf[B.Answer], s"${r.name} a${r.activated}")
    }
  }

  test("every outcome, exception class, value and cost agrees with the jars' own measurement") {
    all.foreach { r =>
      val k = s"${r.name} a${r.activated}"
      outcomes((r.name, r.activated)) match {
        case B.ThrowsAtEval(t) =>
          assertEquals(r.jarsOutcome, "error", k)
          assert(B.chain(t).map(_.getClass.getSimpleName).contains(r.jarsValue), s"$k: ${B.chain(t)} vs ${r.jarsValue}")
        case B.Answer(value, cost) =>
          assertEquals(r.jarsOutcome, "value", k)
          assertEquals(B.text(value), r.jarsValue, k)
          assertEquals(cost.toString, r.jarsCost, k)
        case o => fail(s"$k: jars ${r.jarsOutcome}, ours ${B.said(o)}")
      }
    }
  }

  test("a tree-v0 row answers the same at activated 2 and 3") {
    def key(o: B.Outcome): String = o match {
      case B.Answer(value, cost) => s"${value.noSpaces} @$cost"
      case B.RefusedAtParse(_)   => "refused"
      case B.ThrowsAtEval(t)     => s"throws ${B.chain(t).map(_.getClass.getName).mkString("<-")}"
    }
    all.filter(_.treeVersion == 0).groupBy(_.name).foreach { case (n, rs) =>
      assertEquals(rs.map(_.activated).sorted, Vector(2, 3), n)
      assertEquals(key(outcomes((n, 2))), key(outcomes((n, 3))), n)
    }
  }

  test("the labels' premises: our refusal is sigma-rust's class of the row; CONTROL, NEVER READ and MOVE exactly there") {
    all.foreach { r =>
      val k = s"${r.name} a${r.activated}"
      val w = r.what
      outcomes((r.name, r.activated)) match {
        case B.Answer(_, _) if r.control => assertEquals(w, "control", k)
        case B.Answer(_, _) =>
          assert(w.startsWith("a VALUE: the item of another kind is never read"), s"$k: $w")
          assert(r.tree == "flag-off" || (r.tree == "other" && r.carrier == "t2"), k)
        case B.ThrowsAtEval(t) =>
          val want: B.Refusal =
            if (w.startsWith("an item that is no collection is refused where it is bound")) B.NotAColl
            else if (w.startsWith("remove: a key that is no collection")) B.NotAColl
            else if (w.startsWith("an entry that is no pair")) B.NotAPair
            else if (w.startsWith("a key or value that is a collection of another item type")) B.NotBytes
            else if (w.startsWith("the key argument of another class")) B.ReflectiveCall
            else if (w.startsWith("the proof does not belong to the tree")) B.ProofFails
            else fail(s"$k: an unknown class: $w")
          assertEquals(B.refusal(t), want, k)
          if (w.startsWith("remove:")) assertEquals(r.method, "remove", k)
          if (want == B.ReflectiveCall) assert(Set("contains", "get")(r.method), k)
        case o => fail(s"$k: ${B.said(o)}")
      }
    }
    val byKey = rows.map(r => (r.op, r.name) -> r).toMap
    v.keys.foreach(op => entries(op).foreach { e =>
      val s = str(e, "script")
      val r = byKey((op, rowName(e)))
      val answers = err(e).isEmpty
      assertEquals(s.contains("CONTROL:"), r.control, rowName(e))
      assertEquals(s.contains("NEVER READ:"), answers && !r.control, rowName(e))
      assertEquals(s.contains("MOVE:"), !answers && !r.control, rowName(e))
      assert(!s.matches(".*@[0-9a-f]{6,}.*"), s"${rowName(e)}: an identity hash in the script")
      assert(!s.contains("loader"), s"${rowName(e)}: a class loader in the script")
      assert(!s.contains("argument type mismatch"), s"${rowName(e)}: the reflective message in the script")
    })
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "avltree-item-bind")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 2L)
  }
}
