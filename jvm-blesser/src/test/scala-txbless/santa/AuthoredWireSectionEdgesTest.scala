package santa

import io.circe.Json

/** Anchors the wire vectors for the block transactions section parser's edges. extract() re-derives every verdict
  * through ergo-core; this pins each entry's verdict and the section framing, hand-derived. */
class AuthoredWireSectionEdgesTest extends munit.FunSuite {
  import AuthoredWireSectionEdges._
  private lazy val v = extract()

  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def verdict(e: Json): String =
    if (e.hcursor.get[String]("error").toOption.contains("errored")) "reject"
    else if (e.hcursor.get[String]("expected_bytes_hex").isRight) "rewrite" else "identity"
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
  /** The framing after the 32-byte header id. */
  private def framing(e: Json, n: Int): String = bytesHex(e).substring(64, 64 + 2 * n)

  test("format edges: count 0, the 10,000,000 boundary, trailing bytes, versions 1, 2 and 5") {
    val es = entries(OpFormat)
    assertEquals(es.map(verdict), List("reject", "reject", "rewrite", "rewrite", "identity", "identity", "identity"))
    assertEquals(framing(es(0), 5), "84ade20400")   // marker 10,000,004, count 0
    assertEquals(framing(es(1), 4), "80ade204")     // 10,000,000: a count
    assertEquals(framing(es(2), 5), "81ade20401")   // marker 10,000,001, count 1
    assertEquals(framing(es(4), 1), "01")           // count 1, no marker
    assertEquals(framing(es(5), 5), "82ade20401")   // marker 10,000,002
    assertEquals(framing(es(6), 5), "85ade20401")   // marker 10,000,005
    // #2 is written back without the marker; #3 without its two trailing bytes
    assertEquals(es(2).hcursor.get[String]("expected_bytes_hex").toOption.map(_.substring(64, 66)), Some("01"))
    assert(bytesHex(es(3)).endsWith("0102"))
    assertEquals(es(3).hcursor.get[String]("expected_bytes_hex").toOption, Some(bytesHex(es(3)).dropRight(4)))
  }

  test("the testnet block read under (1, 1): its v7 output parses; under another id it rejects") {
    val es = entries(OpTestnet)
    assertEquals(es.map(verdict), List("identity", "reject"))
    assert(bytesHex(es(0)).startsWith(TestnetHeaderId), "the testnet header id")
    assert(!bytesHex(es(1)).startsWith(TestnetHeaderId), "another header id")
    es.foreach(e => assert(bytesHex(e).contains("0f0208d3"), "a v7 output tree"))
    assertEquals(v(OpTestnet).hcursor.get[Boolean]("activation_rule").toOption, Some(true))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored-section-edges")
    writeVectors(outDir)
    Seq(OpFormat, OpTestnet).foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"$op.json"))
  }
}
