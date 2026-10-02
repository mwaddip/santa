package santa

import io.circe.Json

/** Bless + lock probe A candidate 1: a nested v4 Box tree deserialized during a spend's reduction is version-checked
  * against the activated version and rejected, while the v3 twin is valid. blessAll() fails loud unless the JVM
  * (TxEngine.validateBytes, ergo-core 6.0.6) gives each entry its verdict, its reason mentions the script failure, and
  * a direct reduction's cause chain mentions the version error. A failure means the version check moved. */
class AuthoredTxTreeVersionEvalTest extends munit.FunSuite {
  import AuthoredTxTreeVersionEval._
  private lazy val blessed = blessAll().toMap

  private def entries(path: String): List[Json] =
    blessed(path).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def valid(e: Json): Boolean = e.hcursor.downField("expected").get[Boolean]("valid").toOption.get
  private def inputBox(e: Json): String = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head

  test("spend: a v4 Box tree deserialized during reduction rejects (Deserialize nodes, SubstConstants); the v3 twin is valid") {
    val es = entries(SpendPath)
    assertEquals(es.map(valid), List(false, true, false, true, false, true, false, true, true, false, true))
    def txOf(e: Json): String = e.hcursor.get[String]("tx_bytes_hex").toOption.get
    // candidate 1 (Deserialize readers): the spent box carries the Deserialize-node tree (value = 5 VLQ bytes, then it)
    val trees = List("00d40801", "00d40801", "00d5040800", "00d5040800",
      "00d1950100d401010101", "00d1950100d401010101")
    trees.zip(es.take(6)).foreach { case (t, e) => assert(inputBox(e).startsWith("8094ebdc03" + t), s"spent tree $t") }
    // a v4 reject carries a v4 Box tree (0c0208d3) in its decoded/template/box bytes; the v3 twin a v3 one (0b0208d3)
    List(0, 6, 9).foreach(i => assert(txOf(es(i)).contains("0c0208d3"), s"#$i carries a v4 Box tree"))
    List(1, 7, 10).foreach(i => assert(txOf(es(i)).contains("0b0208d3"), s"#$i carries a v3 Box tree"))
  }

  test("envelope: santa-transaction/v1, the storage-rent context, v6 activated") {
    val env = blessed(SpendPath)
    assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-transaction/v1"))
    assertEquals(env.hcursor.get[String]("op").toOption, Some("tx:authored:tree-version-above-activated-eval"))
    assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some(AuthoredTxStorageRent.BlessedBy))
    entries(SpendPath).foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
    }
  }

  test("write vectors") {
    writeVectors(blessed.toSeq, java.nio.file.Paths.get("..", "vectors"))
  }
}
