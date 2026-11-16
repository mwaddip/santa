package santa

import io.circe.Json

/** Anchors the node-static-type SPENDS. `extract()` requires each verdict to be the one its family predicts; pinned
  * here: the split, the trees as built from their parts, and where each script travels. */
class AuthoredTxNodeStaticTypeTest extends munit.FunSuite {
  private val B = AuthoredTxNodeStaticType
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries: List[Json] =
    blessed.hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))

  test("48 spends: REQ 8, EQ 8, DCTX 16, DREG 16; 36 valid, 12 invalid") {
    assertEquals(entries.size, 48)
    assertEquals(spends.groupBy(_.family).map { case (f, ss) => f -> ss.size }, Map("REQ" -> 8, "EQ" -> 8, "DCTX" -> 16,
      "DREG" -> 16))
    assertEquals(entries.count(valid), 36)
    // invalid: exactly the int-typed deserialize rows over a non-Int script
    val invalid = spends.zip(entries).filterNot(p => valid(p._2)).map(_._1)
    assertEquals(invalid.map(s => (s.family, s.id)).distinct.toSet,
      Set("DCTX", "DREG").flatMap(f => Set("int.exp.int", "int.mul.int", "int.exp.ctl").map(f -> _)))
    entries.foreach(e => assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption.isDefined, valid(e)))
  }

  test("the trees, built from their parts; each script where its family reads it") {
    assertEquals(B.DctxGE, "d193" + "d40701" + B.Exp2g)
    assertEquals(B.DctxInt, "d1ec0101939a" + "d40401" + "04020402")
    assertEquals(B.DregGE, "d193" + "d5040700" + B.Exp2g)
    assertEquals(B.DregInt, "d1ec0101939a" + "d5040400" + "04020402")
    spends.zip(entries).foreach { case (s, e) =>
      val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
      assert(inBox.contains(s.treeHex), s"${s.name}: the tree must appear verbatim in the input box")
      s.extVar.foreach(sc => assert(str(e, "tx_bytes_hex").contains(sc), s"${s.name}: var 1 in the transaction"))
      s.r4.foreach(sc => assert(inBox.contains(sc), s"${s.name}: R4 in the box"))
      assert(str(e, "description").startsWith(s"[${s.name}]"), s.name)
      assertEquals(str(e, "description").contains("SEPARATING ROW"), !s.id.contains("ctl") && s.id != "true-prop" &&
        s.id != "int.int-1", s.name)
    }
  }

  test("envelope + write") {
    assertEquals(blessed.hcursor.get[String]("op").toOption, Some(B.Op))
    assertEquals(blessed.hcursor.get[String]("blessed_by").toOption, Some("jvm:ergo-core-6.0.7-validateStateful"))
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
