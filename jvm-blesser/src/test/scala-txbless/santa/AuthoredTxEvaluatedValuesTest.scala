package santa

import io.circe.Json

/** Bless + lock the transaction vectors for sigma-rust's evaluated-values probe. blessAll() fails loud unless the JVM
  * (TxEngine.validateBytes, ergo-core 6.0.6) gives each entry its verdict, for its reason; this pins the verdicts, each
  * input's extension and each spent box's tree and registers, hand-derived. A failure means the JVM changed how
  * extension or register values convert at evaluation, or the blesser built different bytes. */
class AuthoredTxEvaluatedValuesTest extends munit.FunSuite {
  import AuthoredTxEvaluatedValues._
  private lazy val blessed = blessAll().toMap

  private def entries: List[Json] =
    blessed(SpendPath).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def valid(e: Json): Boolean = e.hcursor.downField("expected").get[Boolean]("valid").toOption.get
  private def reason(e: Json): String = e.hcursor.downField("expected").get[String]("reason").toOption.getOrElse("")
  private def str(e: Json, k: String): String = e.hcursor.get[String](k).toOption.getOrElse(fail(k))
  private def inputBox(e: Json): String = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head

  private val Value = "8094ebdc03" // 1000000000
  private val True = "00d10101"    // sigmaProp(true), evaluated
  private val V3 = "00d193" + "8c" + "e4" + "e3" + "00" + "58" + "01" + "0402" // getVar[(Int, Int)](0).get._1 == 1
  private val V9 = "00d193" + "8c" + "e4" + "c6" + "a7" + "04" + "58" + "01" + "0402" // SELF.R4[(Int, Int)].get._1 == 1
  private val V10 = "00d193" + "e4" + "c6" + "a7" + "05" + "04" + "0402"             // SELF.R5[Int].get == 1

  test("spend: extension and register values convert at evaluation; a Tuple node is a Coll, not a pair") {
    val es = entries
    // (the spent box after its value: tree, height, no tokens, registers; input 0's extension; valid; reason)
    val want: List[(String, String, Boolean, String)] = List(
      (True + "01" + "00" + "00", "01" + "00" + "86020402a3", false, "AssertionError"),         // V1
      ("0008d3" + "01" + "00" + "00", "01" + "00" + "86020402a3", true, ""),                   // V2
      (V3 + "01" + "00" + "00", "01" + "00" + "860204020404", false, "Invalid type returned by evaluator"), // V3
      (V3 + "01" + "00" + "00", "01" + "00" + "580204", true, ""),                             // V3 twin
      ("00d193e4e3000782" + "01" + "00" + "00", "01" + "00" + "82", true, ""),                 // V4
      ("00d193b1e4e300100404" + "01" + "00" + "00", "01" + "00" + "83020404020404", true, ""), // V5
      ("00d1e4e30001" + "01" + "00" + "00", "01" + "00" + "7f", true, ""),                     // V6
      (True + "01" + "00" + "00", "01" + "00" + "8603040204040406", true, ""),                 // V7
      (True + "00" + "00" + "00", "01" + "7f" + "7f", true, ""),                               // V8, height 0
      ("0008cd", "01" + "7f" + "7f", false, "Success((false,"),                                // V8, a P2PK box
      (V9 + "01" + "00" + "01" + "860204020404", "00", false, "Invalid type returned by evaluator"), // V9
      (V9 + "01" + "00" + "01" + "580204", "00", true, ""),                                    // V9 twin
      (V10 + "01" + "00" + "02" + "86020402a3" + "0402", "00", false, "AssertionError"),       // V10
      (V10 + "01" + "00" + "02" + "860204020404" + "0402", "00", true, ""),                    // V10 twin
      (True + "01" + "00" + "02" + "86020402a3" + "0402", "00", true, ""))                     // V10, no register read
    assertEquals(es.map(valid), want.map(_._3))
    want.zip(es).foreach { case ((box, ext, _, why), e) =>
      assert(inputBox(e).startsWith(Value + box), s"spent box ${Value + box}: ${inputBox(e).take(80)}")
      // input 0: 1-byte count, 32-byte box id, an empty proof (00), then the extension; no data inputs or tokens
      val tx = str(e, "tx_bytes_hex")
      assertEquals(tx.slice(66, 68), "00", "an empty proof")
      assert(tx.drop(68).startsWith(ext + "00" + "00" + "01"), s"extension $ext: ${tx.drop(68).take(40)}")
      assert(reason(e).contains(why), s"reason must mention '$why': ${reason(e).take(200)}")
    }
  }

  test("envelope and context: santa-transaction/v1, the storage-rent synthetic context, v6 activated") {
    blessed.values.foreach { env =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-transaction/v1"))
      assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some(AuthoredTxStorageRent.BlessedBy))
      env.hcursor.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
      }
    }
  }

  test("write vectors") {
    writeVectors(blessed.toSeq, java.nio.file.Paths.get("..", "vectors"))
  }
}
