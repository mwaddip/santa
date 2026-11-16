package santa

import io.circe.Json

/** Anchors the substConstants-lift eval families. Pinned here is what would rot silently while still grading green:
  *
  *  - the four files, their entry counts, each entry's version pair (its slot) and its context;
  *  - every outcome (a value, a refusal at parse, a throw at evaluation), value and cost agrees with the jars' own
  *    measurement (sigma-rust's probe on the same 6.0.7, under its own context), an independent second reading; a value
  *    differs from the jars' exactly where it embeds this context (a written header or SELF), and then holds our bytes;
  *  - the eval path's own parse (on the thread's default context) refuses exactly what a parse under the row's
  *    (activated, tree version) refuses, so the reject arm says what the node would say;
  *  - a row below tree version 3 answers the same at activated 2 and 3.
  */
class AuthoredEvalSubstConstantsLiftTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredEvalSubstConstantsLift
  private lazy val rows = B.rows()
  private lazy val outcomes: Map[(String, Int), B.Outcome] = rows.map(r => (r.name, r.activated) -> B.outcome(r)).toMap
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  private def rowName(e: Json): String = str(e, "script").drop(1).takeWhile(_ != ']')

  test("four files; versions in their slot; the context of the slot") {
    val want = Map(
      "SubstConstants.lift_by_class" -> 939, "SubstConstants.lift_by_class_v6" -> 1252,
      "SubstConstants.template_tree_version" -> 42, "SubstConstants.template_tree_version_v6" -> 56)
    assertEquals(v.keySet, want.keySet)
    want.foreach { case (op, n) => assertEquals(entries(op).size, n, op) }
    assertEquals(rows.size, 2289)
    assertEquals(rows.map(_.name).distinct.size, 1308)
    v.foreach { case (op, j) =>
      assertEquals(j.hcursor.get[String]("schema").toOption, Some("santa-eval/v6-fullctx"), op)
      val a = if (op.endsWith("_v6")) 3 else 2
      entries(op).foreach { e =>
        assertEquals(ver(e, "activated"), a, s"$op ${str(e, "name")}")
        assert(ver(e, "ergoTree") <= a, s"$op ${str(e, "name")}: a tree above its activated version")
        assertEquals(e.hcursor.downField("context").focus, Some(B.context(a)), s"$op ${str(e, "name")}")
      }
    }
  }

  test("the context: the pre-header's parent is the header; its version is activated + 1; SELF has no registers") {
    Seq(2, 3).foreach { a =>
      val f = PreHeaderCodec.decodeHex(B.preHeaderHex(a))
      assertEquals(f.version.toInt, a + 1)
      assertEquals(scorex.util.encode.Base16.encode(f.parentId), scorex.util.encode.Base16.encode(B.headerId(a)))
      assertEquals(B.HeaderHex(a).take(2).toInt, a + 1, "the header's own block version")
    }
    val self = org.ergoplatform.ErgoBox.sigmaSerializer.fromBytes(scorex.util.encode.Base16.decode(B.SelfHex).get)
    assert(self.additionalRegisters.isEmpty)
  }

  test("every outcome, value and cost agrees with the jars' own measurement; a value differs only by our context") {
    val byKey = rows.map(r => (r.op, r.name) -> r).toMap
    var differing = 0
    v.keys.foreach(op => entries(op).foreach { e =>
      val r = byKey((op, rowName(e)))
      assertEquals(str(e, "tree_bytes_hex"), r.treeHex, r.name)
      assertEquals(ver(e, "ergoTree"), r.treeVersion, r.name)
      (r.jarsOutcome, outcomes((r.name, r.activated))) match {
        case ("refused at parse", B.RefusedAtParse(_)) => assertEquals(err(e), Some("errored"), r.name)
        case (j, B.ThrowsAtEval(t)) if j.startsWith("throws ") =>
          val cls = B.chain(t).map(_.getClass.getSimpleName)
          assert(cls.contains(j.stripPrefix("throws ")), s"${r.name} a${r.activated}: ${cls.mkString("<-")} vs $j")
          assertEquals(err(e), Some("errored"), r.name)
        case ("value", B.Answer(value, cost)) =>
          assertEquals(err(e), None, r.name)
          assertEquals(cost.toString, r.jarsCost, s"${r.name} a${r.activated}")
          val ours = B.shown(value)
          assertEquals(ours != r.jarsValue, r.embedsContext, s"${r.name} a${r.activated}: ours $ours jars ${r.jarsValue}")
          if (r.embedsContext) {
            differing += 1
            val mine = if (r.kind == "self") B.SelfHex else B.HeaderHex(r.activated)
            assert(ours.contains(mine), s"${r.name}: the value does not hold this context's bytes")
          }
        case (j, ours) => fail(s"${r.name} a${r.activated}: jars $j, ours ${B.said(ours)}")
      }
    })
    assertEquals(differing, 12)
  }

  test("the eval path's parse refuses exactly what the node's parse refuses") {
    rows.foreach { r =>
      val explicit = outcomes((r.name, r.activated)).isInstanceOf[B.RefusedAtParse]
      val default = scala.util.Try(sigma.santa.LenientErgoTree.deserialize(scorex.util.encode.Base16.decode(r.treeHex).get))
        .fold(_ => true, _.root.isLeft)
      assertEquals(default, explicit, s"${r.name} a${r.activated}")
    }
  }

  test("a row below tree version 3 answers the same at activated 2 and 3") {
    def key(o: B.Outcome): String = o match {
      case B.Answer(value, cost) => s"${value.noSpaces} @$cost"
      case B.RefusedAtParse(_)   => "refused"
      case B.ThrowsAtEval(t)     => s"throws ${B.chain(t).map(_.getClass.getName).mkString("<-")}"
    }
    rows.filter(_.treeVersion < 3).map(_.name).distinct.foreach { n =>
      assertEquals(key(outcomes((n, 2))), key(outcomes((n, 3))), n)
    }
  }

  test("scripts carry no identity hash and no label of another outcome") {
    v.keys.foreach(op => entries(op).foreach { e =>
      val s = str(e, "script")
      assert(!s.matches(".*@[0-9a-f]{6,}.*"), s"${rowName(e)}: an identity hash in the script")
      assertEquals(s.contains("— THROWS AT EVALUATION") || s.contains("— REFUSED AT PARSE"), err(e).isDefined, rowName(e))
    })
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "substconstants-lift")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 4L)
  }
}
