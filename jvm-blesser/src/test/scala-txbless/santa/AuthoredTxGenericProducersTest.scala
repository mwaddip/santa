package santa

import io.circe.Json

/** Anchors the generic-producers SPENDS. `extract()` requires each verdict to be the eval tier's (a row) or true (the
  * requested trees). Pinned: the selection, the raw registers and extension, and that the wrap is neutral: on every
  * authored row but the item calls, `{ val v = <row>; true }` answers `true` exactly where the row answers. */
class AuthoredTxGenericProducersTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredTxGenericProducers
  private val E = AuthoredEvalGenericProducers
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries(a: Int): List[Json] =
    blessed(a).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))

  test("the wrap is neutral on every spendable row; on every other row that answers it throws") {
    E.authored().foreach { case (r, o) =>
      val w = EvalCore.evalFullContextOutcome(AuthoredTxGenericProducers.treeAt(r.treeVersion, "d801d60b" + r.body + "0101"),
        0, Seq(r.selfHex), Nil, Nil, Seq(AuthoredEvalSubstConstantsLift.HeaderHex(r.activated)),
        AuthoredEvalSubstConstantsLift.preHeaderHex(r.activated), Seq(r.ext), None, r.activated.toByte)._2
      if (B.spendable(r)) o match {
        case E.Answer(_, cost) =>
          assertEquals(w.map(x => (AuthoredEvalPatchArrayClass.text(x._1), x._2)), Right(("true", cost + 12)), r.name)
        case _ => assert(w.isLeft, r.name)
      } else if (o.isInstanceOf[E.Answer]) assert(w.isLeft, s"${r.name}: an excluded row the wrap carries")
    }
    assert(spends.filter(_.family == "ROW").forall(s => E.rows().exists(r => r.name == s.name && B.spendable(r))))
    assert(spends.exists(_.name.contains("|byIndex.in|")), "the ByIndex rows in range are spent")
  }

  test("the selection: per receiver, method, tree version and gradeable class; the requested trees") {
    val rows = spends.filter(_.family == "ROW")
    val names = rows.map(_.name).distinct
    val byName = E.rows().groupBy(_.name).map { case (n, rs) => n -> rs.head }
    val keys = names.map(n => byName(n)).map(r => (r.receiver, r.method, r.treeVersion, r.grade))
    assertEquals(keys.distinct.size, keys.size)
    assertEquals(spends.count(_.family == "REQ"), 10)
    Seq(2, 3).foreach { a =>
      spends.filter(_.activated == a).zip(entries(a)).foreach { case (s, e) =>
        assertEquals(valid(e), s.valid, s"${s.name} a$a")
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assert(inBox.contains(s.treeHex), s"${s.name}: the tree in the box")
        assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption.isDefined, valid(e), s.name)
        assertEquals(e.hcursor.downField("headers_hex").as[List[String]].toOption.map(_.size), Some(9))
        if (s.rv) {
          assert(inBox.contains(E.RvSelfHex.drop(6 + 72)), s"${s.name}: SELF's registers and token as sigma-rust wrote them")
          assert(str(e, "tx_bytes_hex").contains(E.RvExtHex), s"${s.name}: the raw extension")
        }
      }
    }
    println(s"spends: ${entries(3).size} at activated 3 (${entries(3).count(valid)} valid), " +
      s"${entries(2).size} at activated 2 (${entries(2).count(valid)} valid)")
  }

  test("envelope + write") {
    Seq(2, 3).foreach { a =>
      assertEquals(blessed(a).hcursor.get[String]("op").toOption, Some(B.Op))
      assertEquals(blessed(a).hcursor.get[String]("blessed_by").toOption, Some("jvm:ergo-core-6.0.7-validateStateful"))
    }
    // the first selection's entries (c3e291e) stay where they were: the new spends are appended
    Seq(2, 3).foreach { a =>
      val path = java.nio.file.Paths.get("..", "vectors").resolve(B.Paths(a))
      val before = io.circe.parser.parse(new String(java.nio.file.Files.readAllBytes(path), "UTF-8")).toOption.get
        .hcursor.downField("entries").as[List[Json]].toOption.get
      assertEquals(entries(a).take(before.size), before, s"activated $a: the committed entries are a prefix")
    }
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
