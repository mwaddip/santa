package santa

/** Blesses the 3 ergots stateful fixtures and writes the staging vectors. Green means each
  * chain-valid tx ACCEPTS under SANTA's height-synthetic context — for getVarFromInput that
  * confirms the cross-input context-variable read is captured without a full-context envelope. */
class CapturedTxErgotsTest extends munit.FunSuite {
  test("all 3 ergots tx fixtures bless ACCEPT under the height-synthetic context") {
    val blessed = CapturedTxErgots.blessAll()
    assertEquals(blessed.map(_._1).toSet,
      Set("getvarfrominput-92847", "multi-input-10-402900", "multi-input-3-402800"))
    blessed.foreach { case (slug, env) =>
      val e = env.hcursor.downField("entries").downArray
      assertEquals(e.downField("expected").get[Boolean]("valid").toOption, Some(true), s"$slug must bless accept")
      val cost = e.downField("expected").get[Long]("cost").toOption.getOrElse(fail(s"$slug: no cost"))
      assert(cost > 0L, s"$slug: expected positive cost, got $cost")
      val nIn = e.downField("inputBoxes").values.map(_.size).getOrElse(0)
      println(s"[$slug] accept · cost=$cost · inputBoxes=$nIn")
    }
  }

  test("write staging vectors") {
    val out = java.nio.file.Paths.get("target", "tx-vectors")
    CapturedTxErgots.writeVectors(out)
    Seq("getvarfrominput-92847", "multi-input-10-402900", "multi-input-3-402800").foreach { s =>
      assert(java.nio.file.Files.exists(out.resolve(s"$s.json")), s"staging $s.json not written")
    }
  }
}
