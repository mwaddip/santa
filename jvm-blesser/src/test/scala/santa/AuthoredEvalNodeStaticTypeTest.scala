package santa

import io.circe.Json

/** Anchors the node-static-type eval families. `SpecExtract` fails loud if an entry's outcome changes between the label
  * and the bless; pinned here is what would rot silently while still grading green:
  *
  *  - the four files, their entry counts, and each entry's version pair (its slot);
  *  - every outcome (a value, a refusal at parse, a throw at evaluation), value and cost agrees with the jars' own
  *    measurement (sigma-rust's probe on the same 6.0.7), an independent second reading;
  *  - the eval path's own parse (on the thread's default context) refuses exactly what a parse under the row's
  *    (activated, tree version) refuses, so the reject arm says what the node would say;
  *  - a tree-v0 row answers the same at activated 2 and 3;
  *  - each row holds the node it names (so the scripts describe its bytes), and a twin is true exactly where its row
  *    parses.
  */
class AuthoredEvalNodeStaticTypeTest extends munit.FunSuite {
  private val B = AuthoredEvalNodeStaticType
  private lazy val rows = B.rows()
  private lazy val outcomes: Map[(String, Int), B.Outcome] = rows.map(r => (r.name, r.activated) -> B.outcome(r)).toMap
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  private def rowName(e: Json): String = str(e, "script").drop(1).takeWhile(_ != ']')

  test("four files; versions in their slot") {
    val want = Map(
      "Exponentiate.static_type" -> 498, "Exponentiate.static_type_v6" -> 822,
      "MultiplyGroup.static_type" -> 498, "MultiplyGroup.static_type_v6" -> 822)
    assertEquals(v.keySet, want.keySet)
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    assertEquals(rows.size, 2640)
    assertEquals(rows.map(_.name).distinct.size, 1644)
    v.keys.foreach { op =>
      val a = if (op.endsWith("_v6")) 3 else 2
      entries(op).foreach { e =>
        assertEquals(ver(e, "activated"), a, s"$op ${str(e, "name")}")
        assert(Set(0, 3)(ver(e, "ergoTree")), s"$op ${str(e, "name")}")
        if (a == 2) assertEquals(ver(e, "ergoTree"), 0, s"$op ${str(e, "name")}: a v3 tree in the v5 slot")
        assertEquals(e.hcursor.downField("input").focus, Some(B.IntInput))
      }
    }
  }

  test("every outcome, value and cost agrees with the jars' own measurement; the tree is the vector's") {
    val byKey = rows.map(r => (r.op, r.name) -> r).toMap
    v.keys.foreach(op => entries(op).foreach { e =>
      val r = byKey((op, rowName(e)))
      assertEquals(str(e, "tree_bytes_hex"), r.treeHex, r.name)
      assertEquals(ver(e, "ergoTree"), r.treeVersion, r.name)
      val o = outcomes((r.name, r.activated))
      (r.jarsOutcome, o) match {
        case ("refused at parse", B.RefusedAtParse(_)) => assertEquals(err(e), Some("errored"), r.name)
        case (j, B.ThrowsAtEval(t)) if j.startsWith("throws ") =>
          val cls = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(_.getClass.getSimpleName).toSeq
          assert(cls.contains(j.stripPrefix("throws ")), s"${r.name} a${r.activated}: ${cls.mkString("<-")} vs $j")
          assertEquals(err(e), Some("errored"), r.name)
        case ("value", B.Answer(value, cost)) =>
          assertEquals(err(e), None, r.name)
          assertEquals(B.text(value), r.jarsValue, s"${r.name} a${r.activated}")
          assertEquals(cost.toString, r.jarsCost, s"${r.name} a${r.activated}")
        case (j, ours) => fail(s"${r.name} a${r.activated}: jars $j, ours ${B.said(ours)}")
      }
    })
  }

  test("the eval path's parse refuses exactly what the node's parse refuses") {
    rows.foreach { r =>
      val explicit = outcomes((r.name, r.activated)).isInstanceOf[B.RefusedAtParse]
      val default = scala.util.Try(sigma.santa.LenientErgoTree.deserialize(scorex.util.encode.Base16.decode(r.treeHex).get))
        .fold(_ => true, _.root.isLeft)
      assertEquals(default, explicit, s"${r.name} a${r.activated}")
    }
  }

  test("a tree-v0 row answers the same at activated 2 and 3") {
    def key(o: B.Outcome): String = o match {
      case B.Answer(value, cost)    => s"${value.noSpaces} @$cost"
      case B.RefusedAtParse(_)      => "refused"
      case B.ThrowsAtEval(t)        => s"throws ${t.getClass.getName}"
    }
    rows.filter(_.treeVersion == 0).map(_.name).distinct.foreach { n =>
      assertEquals(key(outcomes((n, 2))), key(outcomes((n, 3))), n)
    }
  }

  test("each row holds the node it names; a twin is true exactly where its row parses") {
    rows.foreach(r => assert(r.body.contains(B.nodeBytes(r.node, r.operand)), s"${r.name}: no ${r.x} in ${r.body}"))
    val twins = rows.filter(_.twin)
    assertEquals(twins.map(_.name).distinct.size, 700)
    twins.foreach { t =>
      val base = outcomes((t.base, t.activated))
      val want = if (base.isInstanceOf[B.RefusedAtParse]) None else Some("true")
      val got = outcomes((t.name, t.activated)) match {
        case B.Answer(value, _) => Some(B.text(value))
        case B.RefusedAtParse(_) => None
        case o => fail(s"${t.name}: a twin ${B.said(o)}")
      }
      assertEquals(got, want, s"${t.name} a${t.activated}")
    }
  }

  test("CONTROL exactly on the ctl rows") {
    v.keys.foreach(op => entries(op).foreach { e =>
      assertEquals(str(e, "script").contains("CONTROL:"), rowName(e).contains(".ctl|"), rowName(e))
      assert(!str(e, "script").matches(".*@[0-9a-f]{6,}.*"), s"${rowName(e)}: an identity hash in the script")
    })
  }

  test("envelope + write staging files") {
    v.values.foreach { j =>
      assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp))
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "node-static-type")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 4L)
  }
}
