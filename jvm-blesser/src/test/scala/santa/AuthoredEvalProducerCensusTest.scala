package santa

import io.circe.Json
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16

/** Anchors the generic-producers pairs and census eval families (the second addendum to the patch-array-class round).
  * Pinned:
  *
  *  - the transaction: every part is sigma-rust's rows2.env byte for byte, and its id (with the extensions) the one its
  *    probe printed; the outputs each context carries are the boxes the evaluator builds;
  *  - the rows, the files, their entry counts, each entry's slot and context (the extensions only where a variable is
  *    read), and what is left out (an earlier family's same bytes with the same outcome);
  *  - every outcome and cost agrees with the jars' own measurement, and every value but our header's and a transaction
  *    id's (the other transaction's, on the rows without the extensions);
  *  - THE RULE: R2 read as a register answers as `tokens` do, row for row; the census partition by class.
  */
class AuthoredEvalProducerCensusTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")
  private val B = AuthoredEvalProducerCensus
  private lazy val all = B.rows().map(r => r -> B.outcome(r))
  private lazy val authored = B.authored()
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))
  private def rowName(e: Json): String = str(e, "script").drop(1).takeWhile(_ != ']')
  private def answers(o: B.Outcome): Boolean = o.isInstanceOf[B.Answer]
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase

  test("the transaction is sigma-rust's rows2.env, and its id the one its probe printed") {
    // sigma-rust's rows2.env, verbatim (PROBE_OUTS split at its comma)
    val env = Map(
      "PROBE_SELF" -> ("c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817986401222222222222222222" +
        "222222222222222222222222222222222222222222222201068603830202020102020402040483011a83010e830202020102" +
        "0283011983010d83020101010100830002860283020202010202040283020e830202020102020e0201021111111111111111" +
        "11111111111111111111111111111111111111111111111100"),
      "PROBE_IN1" -> ("c0843d00d1010164000183020202010202555555555555555555555555555555555555555555555555555555555555555500"),
      "PROBE_DATA" -> ("c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817986401333333333333333333" +
        "33333333333333333333333333333333333333333333330105830202020102028502018302010101010083010e8302020201" +
        "02020e020102444444444444444444444444444444444444444444444444444444444444444401"),
      "PROBE_EXT" -> ("0c018302020201020202860383020202010202040204040383011a83010e8302020201020204830202020102010583000206" +
        "850201070e0201020883011983010d830201010101000a83010202010b8321020202027902be0266027e02f902dc02bb02ac" +
        "025502a00262029502ce0287020b02070202029b02fc02db022d02ce022802d9025902f20281025b021602f8021702980c83" +
        "280202100201020802cd0202027902be0266027e02f902dc02bb02ac025502a00262029502ce0287020b02070202029b02fc" +
        "02db022d02ce022802d9025902f20281025b021602f80217029802d1027302000d83020e830202020102020e020102"),
      "PROBE_EXT1" -> ("06018302020203020402860383020202010202040204040383011a83010e8302020201020206850201070e02030409830201" +
        "01010100"),
      "PROBE_OUT0" -> ("c0843d0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817986401222222222222222222" +
        "222222222222222222222222222222222222222222222201058302020201020285020183010e830202020102028603830202" +
        "02010202040204040e020102"),
      "PROBE_OUT1" -> ("c0843d00d10101640000"))
    assertEquals(B.SelfHex, env("PROBE_SELF"))
    assertEquals(B.In1Hex, env("PROBE_IN1"))
    assertEquals(B.DataHex, env("PROBE_DATA"))
    assertEquals(B.Ext0Hex, env("PROBE_EXT"))
    assertEquals(B.Ext1Hex, env("PROBE_EXT1"))
    assertEquals(B.OutCandidates, Seq(env("PROBE_OUT0"), env("PROBE_OUT1")))
    Seq(B.SelfHex, B.In1Hex, B.DataHex).foreach { h =>
      val bx = sigma.VersionContext.withVersions(3, 3)(org.ergoplatform.ErgoBox.sigmaSerializer.parse(
        sigma.serialization.SigmaSerializer.startReader(Base16.decode(h).get)))
      assertEquals(sigma.VersionContext.withVersions(3, 3)(hx(bx.bytes)), h, "a box re-written identical")
    }
    assertEquals(B.WithVars.idHex, "f8ac82624635985a3ba7b0caaf3a8ef13df039e2fc7ccac9a7b9a9839ae186e2")
    assertNotEquals(B.NoVars.idHex, B.WithVars.idHex)
    assertEquals(B.split(B.Ext0Hex).map(_._1), Seq(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13))
    assertEquals(B.split(B.Ext1Hex).map(_._1), Seq(1, 2, 3, 6, 7, 9))
  }

  test("the outputs each context carries are the boxes the evaluator builds") {
    val idRows = all.filter { case (r, _) => r.name == "value|B.OUT0.id.ctl|v0" }
    assertEquals(idRows.size, 2) // activated 3 and 2
    idRows.foreach { case (r, o) =>
      val out0 = r.tx.outputsHex.head
      o match {
        case B.Answer(value, _) =>
          assertEquals(AuthoredEvalPatchArrayClass.text(value),
            Blake2b256(Base16.decode(out0).get).map(_.toString).mkString("Coll(", ",", ")"), r.name)
        case other => fail(s"${r.name}: $other")
      }
      assert(out0.endsWith(r.tx.idHex + "00"), "output 0 carries the transaction's id and index 0")
    }
  }

  test("the rows; what is left out (an earlier family's same bytes, activated version and outcome)") {
    assertEquals(all.count(_._1.pairs), 644)
    assertEquals(all.count(!_._1.pairs), 1320)
    assertEquals(all.filter(_._1.pairs).map(_._1.name).distinct.size, 448)
    assertEquals(all.filterNot(_._1.pairs).map(_._1.name).distinct.size, 970)
    assertEquals(all.map(_._1.receiver).distinct.size, 4 + 62)
    assert(all.forall(r => B.Receiver.contains(r._1.receiver)), "every receiver described")
    val held = all.filter { case (r, o) => B.heldEarlier(r, o) }
    assertEquals(held.size, 89)
    assertEquals(held.map(_._1.receiver).distinct.toSet, Set("B.SELF.R1", "B.SELF.R2(0)._1.ctl", "B.SELF.R3._2",
      "B.SELF.getReg1", "B.SELF.propositionBytes.ctl", "B.SELF.tokens(0)._1.ctl", "B.preHeader.parentId",
      "B.preHeader.votes", "B.var1.cc"))
    assert(held.forall(!_._1.pairs))
    assertEquals(authored.size + held.size, all.size)
  }

  test("every outcome, cost and value agrees with the jars in their transaction (but our header's bytes)") {
    all.foreach { case (r, _) =>
      B.outcomeIn(r, withVars = true) match {
        case B.ThrowsAtEval(t) =>
          assert(r.jarsThrows, s"${r.name} a${r.activated}: throws, the jars ${r.jars}")
          assert(Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(_.getClass.getSimpleName).contains(r.jarsThrowable),
            s"${r.name}: $t vs ${r.jarsThrowable}")
        case B.Answer(value, cost) =>
          assert(!r.jarsThrows, s"${r.name} a${r.activated}: answers, the jars ${r.jars}")
          assertEquals(cost.toString, r.jarsCost, s"${r.name} a${r.activated}")
          if (r.receiver == "B.preHeader.parentId") () // our header's bytes; outcome and cost are compared
          else assertEquals(AuthoredEvalPatchArrayClass.text(value), r.jarsValue.replace(" ", ""), s"${r.name} a${r.activated}")
        case B.RefusedAtParse(t) => fail(s"${r.name}: refused at parse ($t)")
      }
    }
  }

  test("without the extensions (the rows that read no variable): the same outcome and cost; another value only " +
    "where it holds the transaction's id") {
    val moved = all.filterNot(_._1.readsVariable).flatMap { case (r, o) =>
      (o, B.outcomeIn(r, withVars = true)) match {
        case (B.Answer(v1, c1), B.Answer(v2, c2)) =>
          assertEquals(c1, c2, s"${r.name} a${r.activated}")
          if (v1 != v2) Some(r.receiver) else None
        case (a, b) =>
          assertEquals(answers(a), answers(b), s"${r.name} a${r.activated}")
          None
      }
    }.toSet
    assertEquals(moved, Set("B.OUT0.R3._2", "B.OUT0.creationInfo._2", "B.OUT0.id.ctl"))
  }

  test("THE RULE, pairs: R2 read as a register answers as `tokens` do, row for row; the zip control separates") {
    val pairs = all.filter(_._1.pairs)
    pairs.groupBy(_._1.group).foreach { case (g, ros) =>
      val by = ros.map { case (r, o) => r.receiver -> o }.toMap
      val generic = Seq("SELF.R2", "SELF.tokens", "OUT0.tokens").flatMap(by.get)
      assertEquals(generic.size, 3, s"$g")
      assertEquals(generic.map(answers).distinct.size, 1, s"$g: R2 and the tokens disagree")
      (by("SELF.R2"), by("SELF.tokens")) match {
        case (B.Answer(a, _), B.Answer(t, _)) => assertEquals(a, t, s"$g: R2's value is the tokens'")
        case _                                =>
      }
    }
    val sep = B.separatingGroups(all)
    val byAct = sep.groupBy(_._4).map { case (a, s) => a -> s.size }
    assertEquals(byAct, Map(2 -> 11, 3 -> 22)) // at 3, sigma-rust's "22 rows a value on the jars, an error on 9aba4211"
    // each separating group: the generic receivers answer where the pair of arrays throws
    sep.foreach { g =>
      pairs.filter(_._1.group == g).foreach { case (r, o) => assertEquals(answers(o), B.kind(r) == B.Generic, s"$g ${r.name}") }
    }
  }

  test("THE RULE, census: generic answers every call; a specialised control answers only where no call reads the " +
    "class; a tuple expression's `._1` throws whatever the call") {
    all.filterNot(_._1.pairs).foreach { case (r, o) =>
      val want = B.kind(r) match {
        case B.Generic     => true
        case B.Specialised => !B.readsClass(r)
        case B.TupleRead   => false
      }
      assertEquals(answers(o), want, s"${r.name} a${r.activated}")
    }
    val kinds = all.filterNot(_._1.pairs).map(_._1).groupBy(B.kind).map { case (k, rs) => k -> rs.map(_.receiver).distinct.size }
    assertEquals(kinds, Map[B.Kind, Int](B.Generic -> 40, B.Specialised -> 16, B.TupleRead -> 6))
  }

  test("four files; slots; the context of each entry") {
    assertEquals(v.keySet, Set("Coll.generic_producers_pairs", "Coll.generic_producers_pairs_v6",
      "Coll.generic_producers_census", "Coll.generic_producers_census_v6"))
    assertEquals(v.keys.toSeq.map(entries(_).size).sum, authored.size)
    val byKey = authored.map { case (r, _) => (r.op, r.name, r.activated) -> r }.toMap
    v.keys.foreach { op =>
      val a = if (op.endsWith("_v6")) 3 else 2
      entries(op).foreach { e =>
        val r = byKey((op, rowName(e), a))
        assertEquals(ver(e, "activated"), a, r.name)
        assertEquals(ver(e, "ergoTree"), r.treeVersion, r.name)
        if (a == 2) assertEquals(r.treeVersion, 0, r.name)
        assertEquals(str(e, "tree_bytes_hex"), r.treeHex, r.name)
        assertEquals(e.hcursor.downField("context").focus, Some(r.context), r.name)
        val c = e.hcursor.downField("context")
        val ext = c.downField("extension").keys.map(_.toSet).getOrElse(Set())
        assertEquals(ext, if (r.readsVariable) Set(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13).map(_.toString) else Set[String](),
          r.name)
        assertEquals(c.downField("inputs").as[List[String]].toOption, Some(List(B.SelfHex, B.In1Hex)), r.name)
        assert(!str(e, "script").matches(".*@[0-9a-f]{6,}.*"), s"${r.name}: an identity hash in the script")
      }
    }
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "producer-census")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 4L)
  }
}
