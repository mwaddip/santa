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

  test("spend: values convert at evaluation; a Tuple node is a Coll typed as a pair, and fails where its type is checked") {
    val es = entries.take(44)
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
      (True + "01" + "00" + "02" + "86020402a3" + "0402", "00", true, "")) ++                  // V10, no register read
      // T1..T8: where a Tuple node's value fails; each with the (Int, Int) constant as the twin
      List(
        ("00d1e6e30058", "valid", "valid"),                                  // T1 getVar(0).isDefined
        ("00d194e4e30058580000", "Invalid type returned by evaluator", "valid"), // T2 getVar(0).get != (0, 0)
        ("00d193e4dc2407e3005801d901015804020402", "InvocationTargetException", "valid"), // T3 map(p => 1).get == 1
        ("00d193e30058e30058", "valid", "valid"),                            // T4 getVar(0) == getVar(0)
        ("00d193b1830158e4e300580402", "Invalid type returned by evaluator", "valid"), // T5 Coll(getVar(0).get).size
        ("0b0cd1e6dc650cfe020300020058", "valid", "valid")                   // T8 getVarFromInput(0, 0).isDefined, v3
      ).flatMap { case (t, node, const) =>
        List((t + "01" + "00" + "00", "01" + "00" + "860204020404", node == "valid", if (node == "valid") "" else node),
             (t + "01" + "00" + "00", "01" + "00" + "580204", const == "valid", ""))
      } ++
      List(
        ("00d1e6c6a70458", "valid"),                                         // T6 SELF.R4[(Int, Int)].isDefined
        ("00d194e4c6a70458580000", "Invalid type returned by evaluator")     // T7 SELF.R4[(Int, Int)].get != (0, 0)
      ).flatMap { case (t, node) =>
        List((t + "01" + "00" + "01" + "860204020404", "00", node == "valid", if (node == "valid") "" else node),
             (t + "01" + "00" + "01" + "580204", "00", true, ""))
      } ++
      List(
        (True + "01" + "00" + "00", "01" + "00" + "830158860204020404", false, "ArrayStoreException"),       // C1
        ("0008d3" + "01" + "00" + "00", "01" + "00" + "830158860204020404", true, ""),                      // C1 twin
        (V10 + "01" + "00" + "02" + "830158860204020404" + "0402", "00", false, "ArrayStoreException"),    // C1 reg
        (True + "01" + "00" + "00", "01" + "00" + "8300700204040400", false, "MatchError"),                 // C2
        (True + "01" + "00" + "00", "01" + "00" + "83007001040400", true, ""),                              // C2 twin
        ("0008d3" + "01" + "00" + "00", "01" + "00" + "8300700204040400", true, ""),                        // C2, constant root
        ("00d1d5040100" + "01" + "00" + "01" + "83020202010201", "00", true, ""),                           // D1
        ("00d1d5040100" + "01" + "00" + "01" + "0e020101", "00", true, ""),                                 // D1 twin
        ("00d1d5040100" + "01" + "00" + "01" + "82", "00", false, "Should be overriden"),                   // D2
        ("00d1d40100" + "01" + "00" + "00", "01" + "00" + "83020202010201", true, ""),                      // D3
        ("00d40801" + "01" + "00" + "00", "02" + "00" + "86020402a3" + "01" + "0e0208d3", false, "AssertionError"), // D4
        ("00d40801" + "01" + "00" + "00", "01" + "01" + "0e0208d3", true, ""),                              // D4 twin
        ("00d1e6dc650bfe010200" + "01" + "00" + "00", "01" + "00" + "0402", false, "NoSuchMethodException")) // method 11
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

  test("third round: an output is written at the block path's (1, 1), the tx id at its read context (3, 3)") {
    val es = entries.slice(44, 47)
    assertEquals(es.map(valid), List(true, false, false))
    val x15 = "860204027e040205"                         // Tuple(1, Upcast(1, Long))
    val kept = "0008d3" + "010001" + x15                 // output 0 after its value, R4 as received
    val stripped = "0008d3" + "010001" + "860204020402"  // the same written below tree v3
    // output 0 (value 1000000) carries R4 as received; #46's R4 is an empty Coll[Int => Int]
    assert(str(es(0), "tx_bytes_hex").contains("c0843d" + kept))
    assert(str(es(1), "tx_bytes_hex").contains("c0843d" + kept))
    assert(str(es(2), "tx_bytes_hex").contains("c0843d" + "0008d3" + "010001" + "83007001040400"))
    // the spent script compares OUTPUTS(0).bytes.slice(3, n) with the stripped (#44) or the kept (#45) form
    assert(inputBox(es(0)).contains("0e0c" + stripped), "the stripped constant")
    assert(inputBox(es(1)).contains("0e0e" + kept), "the kept constant")
    // #44 and #45 carry a 56-byte Schnorr proof over the JVM's message, which keeps the Upcast
    assertEquals(str(es(0), "tx_bytes_hex").substring(66, 68), "38")
    assertEquals(str(es(1), "tx_bytes_hex").substring(66, 68), "38")
    assert(reason(es(2)).contains("MatchError"), reason(es(2)))
  }

  test("dust and size: an output is measured as written at (1, 1), where X15's Upcast is dropped") {
    val es = entries.slice(47, 51)
    assertEquals(es.map(valid), List(true, false, true, false))
    val x15 = "860204027e040205"
    def vlq(n: Long): String = RentFixtures.hex(RentFixtures.vlqU32(n))
    // output 0: its value, SigmaProp(true), height 1, no tokens, R4 = X15 (48 bytes at (1, 1), 50 at (3, 3))
    assert(str(es(0), "tx_bytes_hex").contains(vlq(17280) + "0008d3" + "010001" + x15), "48 x 360 = 17280")
    assert(str(es(1), "tx_bytes_hex").contains(vlq(17279) + "0008d3" + "010001" + x15), "one nanoERG below")
    // output 0: 1000000000, R4 = X15, R5 = a Coll[Byte] of 4043 (4096 bytes at (1, 1)) or 4044 zero bytes
    assert(str(es(2), "tx_bytes_hex").contains("8094ebdc03" + "0008d3" + "010002" + x15 + "0e" + vlq(4043) + "00" * 4043))
    assert(str(es(3), "tx_bytes_hex").contains("8094ebdc03" + "0008d3" + "010002" + x15 + "0e" + vlq(4044) + "00" * 4044))
    assert(reason(es(1)).contains("minValuePerByte"), reason(es(1)))
    assert(reason(es(3)).contains("Box size should not exceed 4096"), reason(es(3)))
  }

  test("bytesWithoutRef: the first script to read it writes it, under that script's tree version") {
    assertEquals(entries.size, 63)
    val es = entries.drop(51)
    def zz(n: Int): String = RentFixtures.hex(RentFixtures.vlqU32(2L * n)) // a positive Int, zigzag
    // sigmaProp(OUTPUTS(0).<op>.size == n): c4 = bytesWithoutRef, c3 = bytes; in a v0 tree or a size-flagged v3 tree
    def body(op: String, n: Int): String = "d1" + "93" + "b1" + op + "b2" + "a5" + "0400" + "00" + "04" + zz(n)
    def v0(op: String, n: Int): String = "00" + body(op, n)
    def v3(op: String, n: Int): String = "0b" + "0b" + body(op, n)
    val (l0, l3, lb) = (17, 19, 50) // bytesWithoutRef below v3 and at v3; bytes at (1, 1)
    val want: List[(List[String], Boolean)] = List(
      List(v0("c4", l0)) -> true, List(v0("c4", l3)) -> false,                       // one v0 reader
      List(v3("c4", l3)) -> true, List(v3("c4", l0)) -> false,                       // one v3 reader
      List(v0("c4", l0), v3("c4", l0)) -> true, List(v0("c4", l0), v3("c4", l3)) -> false, // v0 first
      List(v3("c4", l3), v0("c4", l3)) -> true, List(v3("c4", l3), v0("c4", l0)) -> false, // v3 first
      List(v0("c3", lb)) -> true, List(v3("c3", lb)) -> true, List(v3("c3", lb + 2)) -> false, // bytes
      List(v3("c3", lb), v0("c4", l0)) -> true)                                      // reading bytes fixes nothing
    assertEquals(es.map(valid), want.map(_._2))
    es.zip(want).foreach { case (e, (trees, _)) =>
      val boxes = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get
      assertEquals(boxes.size, trees.size)
      // each spent box: 1000000000, its tree, height 1, no tokens, no registers
      boxes.zip(trees).foreach { case (bx, t) => assert(bx.startsWith(Value + t + "01" + "00" + "00"), s"box $bx") }
      // output 0: all the value, SigmaProp(true), height 1, no tokens, R4 = X15
      assert(str(e, "tx_bytes_hex").contains("0008d3" + "010001" + "860204027e040205"), "output 0's R4 = X15")
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
