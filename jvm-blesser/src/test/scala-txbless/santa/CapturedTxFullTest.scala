package santa

/** Re-blesses all 7 tx seeds to the bytes-anchored full-context shape and writes staging vectors.
  * Needs the testnet node (:9053) for the 4 originals' headers/preHeader. Green ⇒ every captured tx
  * accepts under its provided real context via the bytes path. */
class CapturedTxFullTest extends munit.FunSuite {
  test("all 7 tx seeds re-bless ACCEPT in the bytes+context shape") {
    val blessed = CapturedTxFull.blessAll()
    assertEquals(blessed.size, 7)
    blessed.foreach { case (slug, env) =>
      val e = env.hcursor.downField("entries").downArray
      assertEquals(e.downField("expected").get[Boolean]("valid").toOption, Some(true), s"$slug must accept")
      assert(e.get[String]("tx_bytes_hex").toOption.exists(_.nonEmpty), s"$slug missing tx_bytes_hex")
      assertEquals(e.downField("headers_hex").values.map(_.size), Some(10), s"$slug must carry 10 headers")
      val cost = e.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
      val nIn  = e.downField("input_boxes_hex").values.map(_.size).getOrElse(0)
      println(s"[$slug] accept · cost=$cost · inputs=$nIn · headers=10")
    }
  }

  test("write staging vectors") {
    CapturedTxFull.writeVectors(java.nio.file.Paths.get("target", "tx-vectors-full"))
    Seq("getvarfrominput-92847", "multi-input-10-402900", "multi-input-3-402800",
        "bigint-downcast-2666", "powhit-return-type-28474", "deserialize-context-111927",
        "atleast-degenerate-bound-184137").foreach { s =>
      assert(java.nio.file.Files.exists(java.nio.file.Paths.get("target", "tx-vectors-full", s"$s.json")), s"staging $s.json")
    }
  }
}
