package santa

import io.circe.Json

/** Anchors the creation-height parse-bound wire vectors. extract() re-derives every verdict through
  * WireCanonicalize (rejects must fail with getUIntExact's Int overflow); this pins the boundary-pair
  * shape and writes staging. A failure means sigma-state changed how it parses a box's creation height. */
class AuthoredWireCreationHeightBoundTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireCreationHeightBound.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))

  test("Box: 0x7fffffff accepts; 0x80000000 and 0xffffffff reject; each differs from the twin in the VLQ only") {
    val es = entries(AuthoredWireCreationHeightBound.OpBox)
    assertEquals(es.map(isReject), List(false, true, true))
    val twin = bytesHex(es.head)
    assertEquals(bytesHex(es(1)), twin.replace("ffffffff07", "8080808008"))
    assertEquals(bytesHex(es(2)), twin.replace("ffffffff07", "ffffffff0f"))
  }

  test("Transaction: output, extension Box constant and register Box constant — accept/reject pairs") {
    val es = entries(AuthoredWireCreationHeightBound.OpTx)
    assertEquals(es.map(isReject), List(false, true, false, true, false, true))
    es.grouped(2).foreach { case List(acc, rej) =>
      assertEquals(bytesHex(rej), bytesHex(acc).replace("ffffffff07", "8080808008"))
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireCreationHeightBound.writeVectors(outDir)
    Seq(AuthoredWireCreationHeightBound.OpBox, AuthoredWireCreationHeightBound.OpTx).foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
