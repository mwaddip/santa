package santa

import io.circe.Json

/** Bless + lock the transaction vectors for a box whose tree is above the activated script version. blessAll() fails
  * loud unless the JVM (TxEngine.validateBytes, ergo-core 6.0.6) gives each entry its verdict, for its reason; this
  * pins the verdicts, the trees, the costs and the contexts, hand-derived, and writes the vectors. A failure means
  * ergo-core or sigma-state changed where a tree's version is checked, or the blesser built different bytes. */
class AuthoredTxTreeVersionTest extends munit.FunSuite {
  import AuthoredTxTreeVersion._
  private lazy val blessed = blessAll().toMap

  private def entries(path: String): List[Json] =
    blessed(path).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def valid(e: Json): Boolean = e.hcursor.downField("expected").get[Boolean]("valid").toOption.get
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def reason(e: Json): String = e.hcursor.downField("expected").get[String]("reason").toOption.getOrElse("")
  private def str(e: Json, k: String): String = e.hcursor.get[String](k).toOption.getOrElse(fail(k))
  private def boxes(e: Json, k: String): List[String] = e.hcursor.downField(k).as[List[String]].toOption.get
  private def inputBox(e: Json): String = boxes(e, "input_boxes_hex").head

  private val V = "8094ebdc03" // 1000000000, the value of every box and output but the rent box's
  // 10000 to start, 2000 for the input, 100 for the output; then 5 for a SigmaProp constant's script, or 50 for a
  // storage-rent spend; 100 more for a data input.
  private val ScriptCost    = 12105L
  private val DataInputCost = 12205L
  private val RentCost      = 12150L
  private def above(tree: Int, activated: Int) = s"ErgoTree version $tree is higher than activated $activated"

  test("block version 4: a tree above v3 does not spend; a data input with one, and its storage rent, do") {
    val es = entries(V6Path)
    assertEquals(es.map(valid), List(false, false, false, false, true, true, true, false))
    // #0..#4: the spent box's tree, v4 to v7 and the v3 control
    List("0c0208d3" -> 4, "0d0208d3" -> 5, "0e0208d3" -> 6, "0f0208d3" -> 7).zip(es).foreach { case ((tree, v), e) =>
      assert(inputBox(e).startsWith(V + tree), s"input box with tree $tree")
      assert(reason(e).contains(above(v, 3)), s"reason of $tree: ${reason(e)}")
      assertEquals(cost(e), None)
    }
    assert(inputBox(es(4)).startsWith(V + "0b0208d3"), "the v3 control")
    assertEquals(cost(es(4)), Some(ScriptCost))
    // #5: a plain spent box, and a data input whose tree is v7
    assert(inputBox(es(5)).startsWith(V + "0008d3"), "a plain spent box")
    assertEquals(boxes(es(5), "data_input_boxes_hex").map(_.take(18)), List(V + "0f0208d3"))
    assertEquals(str(es(5), "tx_bytes_hex").substring(70, 72), "01", "one data input")
    assertEquals(cost(es(5)), Some(DataInputCost))
    // #6, #7: a 44-byte height-0 box whose tree is v4 and whose value is its storage fee, 1250000 * 44 = 55000000
    Seq(es(6), es(7)).foreach { e =>
      assert(inputBox(e).startsWith("c0f79c1a" + "0c0208d3" + "00"), "the rent box: value 55000000, v4 tree, height 0")
      assertEquals(inputBox(e).length, 88, "44 bytes")
    }
    assertEquals(str(es(6), "tx_bytes_hex").substring(66, 76), "00" + "01" + "7f0300", "no proof, var 127 = Short(0)")
    assertEquals(cost(es(6)), Some(RentCost))
    assertEquals(str(es(7), "tx_bytes_hex").substring(66, 70), "00" + "00", "no proof, no extension")
    assert(reason(es(7)).contains(above(4, 3)), s"reason: ${reason(es(7))}")
  }

  test("block version 3: a v3 tree does not spend, and an output may carry a tree of any version") {
    val es = entries(V5Path)
    assertEquals(es.map(valid), List(false, true, true, true))
    assert(inputBox(es(0)).startsWith(V + "0b0208d3"), "the spent box's tree is v3")
    assert(reason(es(0)).contains(above(3, 2)), s"reason: ${reason(es(0))}")
    assert(inputBox(es(1)).startsWith(V + "0a0208d3"), "the v2 control")
    // #2, #3: a plain spent box; the one output's tree is v3, then v4
    List("0b0208d3", "0c0208d3").zip(es.drop(2)).foreach { case (tree, e) =>
      assert(inputBox(e).startsWith(V + "0008d3"), "a plain spent box")
      assert(str(e, "tx_bytes_hex").endsWith("01" + V + tree + "01" + "00" + "00"), s"one output with tree $tree")
    }
    es.drop(1).foreach(e => assertEquals(cost(e), Some(ScriptCost)))
  }

  test("envelopes and contexts: santa-transaction/v1; block version 4 at activated 3, block version 3 at activated 2") {
    assertEquals(blessed.keySet, Set(V6Path, V5Path))
    for ((path, blockVersion) <- Seq(V6Path -> 4, V5Path -> 3)) {
      val env = blessed(path)
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-transaction/v1"))
      assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some(AuthoredTxStorageRent.BlessedBy))
      entries(path).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(blockVersion - 1))
        assertEquals(e.hcursor.downField("preHeader").get[Int]("version").toOption, Some(blockVersion))
        assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
        val headers = boxes(e, "headers_hex")
        assertEquals(headers.size, 10)
        headers.foreach(h => assertEquals(h.take(2), f"$blockVersion%02x", "each header carries the block version"))
        assert(str(e, "source").startsWith("santa:"))
      }
    }
  }

  test("write vectors") {
    writeVectors(blessed.toSeq, java.nio.file.Paths.get("..", "vectors"))
  }
}
