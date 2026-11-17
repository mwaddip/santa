package santa

import io.circe.Json

/** Anchors the generic-producers eval families (the addendum to the patch-array-class round). Pinned:
  *
  *  - the two files, their entry counts, each entry's slot and context (SELF, and the extension only where a variable
  *    is read), and what is left out (the first family's rows, the answers no value can say) and why;
  *  - every outcome and cost agrees with the jars' own measurement, and every value not holding our header;
  *  - the class partition: every row over a receiver a node holds generic answers, every row over a specialised one
  *    throws, and sigma-rust's gradeable column agrees with that;
  *  - the rv-rc context read back as a node reads it: SELF's bytes, and the extension's raw values in sigma-rust's bytes.
  */
class AuthoredEvalGenericProducersTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredEvalGenericProducers
  private lazy val all = B.rows()
  private lazy val authored = B.authored()
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def err(e: Json): Option[String] = e.hcursor.downField("expected").get[Option[String]]("error").toOption.flatten
  private def rowName(e: Json): String = str(e, "script").drop(1).takeWhile(_ != ']')

  test("what is left out: the first family's rows, and the answers no value can say") {
    assertEquals(all.size, 2076)
    assertEquals(all.map(_.name).distinct.size, 1486)
    val dup = all.filter(B.inRoundOne)
    assertEquals(dup.map(_.name).distinct.size, 64)
    assert(dup.forall(r => r.set == "pre" && r.method == "patch"), dup.map(_.name).distinct.mkString(", "))
    // each one's outcome is the first family's entry for the same bytes, tree version and activated version
    val first = AuthoredEvalPatchArrayClass.rows().filter(_.inContext).map(r => (r.body, r.treeVersion, r.activated) -> r).toMap
    dup.foreach { r =>
      val p = first((r.body, r.treeVersion, r.activated))
      assertEquals(AuthoredEvalPatchArrayClass.outcome(p).isInstanceOf[AuthoredEvalPatchArrayClass.Answer],
        r.jarsOutcome == "value", r.name)
    }
    val unsayable = all.filterNot(B.inRoundOne).filter(r => B.unsayable(B.outcome(r)))
    assert(unsayable.forall(r => r.arg == "itemU.direct" && r.method.endsWith(".out")), unsayable.map(_.name).mkString(", "))
    assert(unsayable.forall(_.jarsValue == "()"))
    assertEquals(authored.size + dup.size + unsayable.size, all.size)
  }

  test("two files; slots; the context of each entry") {
    assertEquals(v.keySet, Set("Coll.generic_producers", "Coll.generic_producers_v6"))
    assertEquals(entries("Coll.generic_producers").size + entries("Coll.generic_producers_v6").size, authored.size)
    val byKey = authored.map { case (r, _) => (r.op, r.name) -> r }.toMap
    v.keys.foreach { op =>
      val a = if (op.endsWith("_v6")) 3 else 2
      entries(op).foreach { e =>
        val r = byKey((op, rowName(e)))
        assertEquals(ver(e, "activated"), a, r.name)
        assertEquals(ver(e, "ergoTree"), r.treeVersion, r.name)
        if (a == 2) assertEquals(r.treeVersion, 0, r.name)
        assertEquals(str(e, "tree_bytes_hex"), r.treeHex, r.name)
        assertEquals(e.hcursor.downField("context").focus, Some(r.context), r.name)
        val ext = e.hcursor.downField("context").downField("extension").keys.map(_.toSet).getOrElse(Set())
        assertEquals(ext, if (r.receiver.contains(".var")) Set("1", "2", "3", "4", "5") else Set[String](), r.name)
        val self = e.hcursor.downField("context").downField("inputs").downN(0).as[String].toOption
        assertEquals(self, Some(if (r.set == "pre") AuthoredEvalPatchArrayClass.SelfHex else B.RvSelfHex), r.name)
      }
    }
  }

  test("every outcome and cost agrees with the jars, and every value not holding our header") {
    authored.foreach { case (r, o) =>
      o match {
        case B.ThrowsAtEval(t) =>
          assertEquals(r.jarsOutcome, "throws", r.name)
          assert(Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(_.getClass.getSimpleName).contains(r.jarsValue),
            s"${r.name}: $t vs ${r.jarsValue}")
        case B.Answer(value, cost) =>
          assertEquals(r.jarsOutcome, "value", r.name)
          assertEquals(cost.toString, r.jarsCost, s"${r.name} a${r.activated}")
          val ours = AuthoredEvalPatchArrayClass.text(value)
          val theirs = r.jarsValue.replaceAll("CBigInt\\(([-0-9]+)\\)", "$1")
          if (r.holdsOurHeader) () // our header's bytes where the value holds them; outcome and cost are compared
          else if (theirs.endsWith(")") || !theirs.startsWith("Coll(")) assertEquals(ours, theirs, s"${r.name} a${r.activated}")
          else assert(ours.startsWith(theirs), s"${r.name}: $ours does not start with $theirs")
        case B.RefusedAtParse(t) => fail(s"${r.name}: refused at parse ($t)")
      }
    }
  }

  test("the class partition: generic answers, specialised throws; sigma-rust's column agrees") {
    authored.foreach { case (r, o) =>
      assertEquals(o.isInstanceOf[B.Answer], r.generic, s"${r.name} a${r.activated}")
      val want = if (r.generic) Set("a refusal on 9aba4211 against a value", "none on 9aba4211 (a node's class)")
                 else Set("value against a throw")
      assert(want(r.grade), s"${r.name}: ${r.grade}")
    }
    assertEquals(authored.map(_._1.receiver).distinct.size, 21)
    v.keys.foreach(op => entries(op).foreach { e =>
      val s = str(e, "script")
      assertEquals(s.contains("NODE'S CLASS:"), err(e).isEmpty, rowName(e))
      assertEquals(s.contains("CONTROL OF THE CLASS:"), err(e).contains("errored"), rowName(e))
      assert(!s.matches(".*@[0-9a-f]{6,}.*"), s"${rowName(e)}: an identity hash in the script")
    })
  }

  test("the rv-rc context: SELF re-read identical, the extension's raw values in sigma-rust's bytes") {
    val env = "PROBE_SELF=c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179864012222222222" +
      "22222222222222222222222222222222222222222222222222222201068302020201020285020183020101010100830" +
      "10e83020202010202830204040204040e0201021111111111111111111111111111111111111111111111111111111111111111" + "00"
    assertEquals("PROBE_SELF=" + B.RvSelfHex, env)
    val self = sigma.VersionContext.withVersions(3, 3)(org.ergoplatform.ErgoBox.sigmaSerializer.parse(
      sigma.serialization.SigmaSerializer.startReader(scorex.util.encode.Base16.decode(B.RvSelfHex).get)))
    assertEquals(self.additionalRegisters.size, 6)
    assertEquals(B.RvExt, Map(1 -> "83020202010202", 2 -> "850201", 3 -> "83010e83020202010202", 4 -> "0e020102",
      5 -> "83020101010100"))
    assertEquals("05" + (1 to 5).map(k => f"$k%02x" + B.RvExt(k)).mkString, B.RvExtHex)
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "generic-producers")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 2L)
  }
}
