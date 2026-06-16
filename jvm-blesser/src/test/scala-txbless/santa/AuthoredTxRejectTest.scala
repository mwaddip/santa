package santa

/** Property-assert + persist the two Track-B reject boundary pairs. The boundaries are COMPUTED
  * per seed (cost ceiling = the seed's own cost; dust floor = the searched flip), so this asserts
  * RELATIONS — adjacency, the cost==maxBlockCost identity, reason anchors — not absolute literals.
  * The EXPECTED side is oracle-emitted (blessAll fail-louds on a wrong verdict); the committed
  * vectors pin the bytes. Run under SANTA_TX_BLESSER=1; cwd = jvm-blesser/. */
class AuthoredTxRejectTest extends munit.FunSuite {
  lazy val blessed: Seq[(String, io.circe.Json)] = AuthoredTxReject.blessAll()

  private def envelope(rel: String): io.circe.Json =
    blessed.find(_._1 == rel).map(_._2).getOrElse(fail(s"path '$rel' not blessed"))
  private def entries(rel: String): Vector[io.circe.Json] =
    envelope(rel).hcursor.downField("entries").focus.flatMap(_.asArray).getOrElse(fail(s"$rel: entries"))
  private def entry(rel: String, name: String): io.circe.ACursor =
    entries(rel).find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"$rel: no entry '$name'")).hcursor
  private def pInt(e: io.circe.ACursor, k: String): Int =
    e.downField("parameters").get[Int](k).fold(er => fail(s"$k: $er"), identity)
  private def expValid(e: io.circe.ACursor): Option[Boolean] = e.downField("expected").get[Boolean]("valid").toOption
  private def expReason(e: io.circe.ACursor): String = e.downField("expected").get[String]("reason").toOption.getOrElse("")

  test("corpus: two files, accept+reject each") {
    assertEquals(blessed.map(_._1), Seq(AuthoredTxReject.CostLimitPath, AuthoredTxReject.DustPath))
    assertEquals(entries(AuthoredTxReject.CostLimitPath).map(_.hcursor.get[String]("name").toOption.get),
      Vector("cost-limit-accept", "cost-limit-reject"))
    assertEquals(entries(AuthoredTxReject.DustPath).map(_.hcursor.get[String]("name").toOption.get),
      Vector("min-value-dust-accept", "min-value-dust-reject"))
  }

  test("envelopes: santa-transaction/v1 + house blessed_by") {
    Seq(AuthoredTxReject.CostLimitPath, AuthoredTxReject.DustPath).foreach { rel =>
      assertEquals(envelope(rel).hcursor.get[String]("schema").toOption, Some("santa-transaction/v1"), rel)
      assertEquals(envelope(rel).hcursor.get[String]("blessed_by").toOption,
        Some("jvm:ergo-core-6.0.2.1-validateStateful"), rel)
    }
  }

  test("cost-limit boundary: accept@C / reject@C-1 adjacent; accept cost == its ceiling; reason names cost") {
    val acc = entry(AuthoredTxReject.CostLimitPath, "cost-limit-accept")
    val rej = entry(AuthoredTxReject.CostLimitPath, "cost-limit-reject")
    assertEquals(pInt(acc, "maxBlockCost"), pInt(rej, "maxBlockCost") + 1, "accept ceiling is one above reject")
    assertEquals(expValid(acc), Some(true)); assertEquals(expValid(rej), Some(false))
    // The accept ceiling IS the seed's exact cost, so the tx just fits: expected.cost == maxBlockCost.
    assertEquals(acc.downField("expected").get[Long]("cost").toOption, Some(pInt(acc, "maxBlockCost").toLong))
    assert(expReason(rej).toLowerCase.contains("cost"), s"cost-limit reject reason must name the cost limit: ${expReason(rej)}")
  }

  test("dust boundary: accept@floor / reject@floor+1 adjacent; reason names minValuePerByte") {
    val acc = entry(AuthoredTxReject.DustPath, "min-value-dust-accept")
    val rej = entry(AuthoredTxReject.DustPath, "min-value-dust-reject")
    assertEquals(pInt(rej, "minValuePerByte"), pInt(acc, "minValuePerByte") + 1, "reject floor is one above accept")
    assertEquals(expValid(acc), Some(true)); assertEquals(expValid(rej), Some(false))
    assert(expReason(rej).contains("minValuePerByte"), s"dust reject reason must name the dust rule: ${expReason(rej)}")
  }

  test("surgical: accept and reject differ ONLY in the moved param (same tx, boxes, headers)") {
    Seq(AuthoredTxReject.CostLimitPath, AuthoredTxReject.DustPath).foreach { rel =>
      val es = entries(rel)
      Seq("tx_bytes_hex", "input_boxes_hex", "headers_hex", "preHeader").foreach { f =>
        assertEquals(es(0).hcursor.downField(f).focus, es(1).hcursor.downField(f).focus, s"$rel: $f must match")
      }
    }
  }

  test("write step: files land at the committed vectors/transaction/v6/authored/ paths") {
    val vectorsRoot = java.nio.file.Paths.get("..", "vectors")
    AuthoredTxReject.writeVectors(blessed, vectorsRoot)
    Seq(AuthoredTxReject.CostLimitPath, AuthoredTxReject.DustPath).foreach { rel =>
      val f = vectorsRoot.resolve(rel)
      assert(java.nio.file.Files.exists(f), s"not written: $f")
      val raw = { val s = scala.io.Source.fromFile(f.toFile); try s.mkString finally s.close() }
      assertEquals(io.circe.parser.parse(raw).toOption.flatMap(_.hcursor.get[String]("schema").toOption),
        Some("santa-transaction/v1"), s"$rel written schema")
    }
  }
}
