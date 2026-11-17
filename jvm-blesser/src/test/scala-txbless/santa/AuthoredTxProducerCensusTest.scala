package santa

import io.circe.Json

/** Anchors the generic-producers pairs and census SPENDS. `extract()` requires each verdict to be the class rule's and
  * the one sigma-rust's SpendProbe measured (where it measured one; at activated 2 the context set's verdicts are
  * activated 3's). Pinned: the selection, the transaction (SELF's registers and the extensions as raw bytes), the
  * files. */
class AuthoredTxProducerCensusTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredTxProducerCensus
  private val E = AuthoredEvalProducerCensus
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries(op: String, a: Int): List[Json] =
    blessed((op, a)).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))

  test("the selection: sigma-rust's trees, at activated 3 and (tree v0) 2") {
    val n = spends.groupBy(s => (s.set, s.activated)).map { case (k, v) => k -> v.size }
    assertEquals(n, Map(("pairs", 3) -> 25, ("pairs", 2) -> 9, ("census", 3) -> 180, ("census", 2) -> 53,
      ("context", 3) -> 51, ("context", 2) -> 34))
    assert(spends.filter(_.activated == 2).forall(_.treeVersion == 0))
    assertEquals(spends.count(_.measuredValid.isEmpty), 34) // the context set at activated 2
    assertEquals(spends.map(s => (s.set, s.name, s.activated)).distinct.size, spends.size)
  }

  test("every entry: the tree in SELF's raw bytes, the raw extensions, nine headers; the verdicts") {
    val counts = Seq(2, 3).flatMap(a => B.Ops.map { op =>
      val es = entries(op, a)
      val ss = spends.filter(s => s.op == op && s.activated == a)
      assertEquals(es.size, ss.size, s"$op a$a")
      ss.zip(es).foreach { case (s, e) =>
        val in = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get
        assertEquals(in.size, 2)
        assertEquals(in.head, "c0843d" + s.treeHex + E.SelfHex.drop(6 + E.P2pkTree.length), s.name)
        assertEquals(in(1), E.In1Hex)
        assertEquals(e.hcursor.downField("data_input_boxes_hex").as[List[String]].toOption, Some(List(E.DataHex)))
        val tx = str(e, "tx_bytes_hex")
        assert(tx.contains(E.Ext0Hex) && tx.contains(E.Ext1Hex), s"${s.name}: the raw extensions")
        assertEquals(e.hcursor.downField("headers_hex").as[List[String]].toOption.map(_.size), Some(9))
        assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption.isDefined, valid(e), s.name)
        assert(str(e, "description").startsWith(s"[${s.set}|${s.name}]"), s.name)
      }
      s"$op a$a: ${es.size} (${es.count(valid)} valid)"
    })
    assertEquals(counts, Seq(
      "generic-producers-pairs a2: 9 (9 valid)", "generic-producers-census a2: 87 (41 valid)",
      "generic-producers-pairs a3: 25 (25 valid)", "generic-producers-census a3: 231 (127 valid)"))
  }

  test("envelope + write") {
    blessed.foreach { case ((op, _), j) =>
      assertEquals(j.hcursor.get[String]("op").toOption, Some(op))
      assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some("jvm:ergo-core-6.0.7-validateStateful"))
    }
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
