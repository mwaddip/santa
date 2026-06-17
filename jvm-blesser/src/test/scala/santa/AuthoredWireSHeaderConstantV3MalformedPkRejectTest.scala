package santa

import sigma.VersionContext

/** Anchors the v3 SHeader-constant MALFORMED-pk REJECT entry: at treeVersion 3 the JVM accepts an SHeader
  * constant only if its Header value parses; an AutolykosSolution pk with an invalid compressed-point prefix
  * makes GroupElementSerializer.parse throw -> the JVM rejects. A failure here means sigma-state started
  * accepting the bad point (or stopped accepting the valid base). */
class AuthoredWireSHeaderConstantV3MalformedPkRejectTest extends munit.FunSuite {
  private val A: Byte = AuthoredWireSHeaderConstantV3MalformedPkReject.Activated
  private val E: Byte = AuthoredWireSHeaderConstantV3MalformedPkReject.ErgoTreeV
  private lazy val env =
    AuthoredWireSHeaderConstantV3MalformedPkReject.extract()(AuthoredWireSHeaderConstantV3MalformedPkReject.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  test("1 entry: ErgoTree kind, REJECT (off-curve pk), valid base still accepts") {
    assertEquals(entries.size, 1)
    val e  = entries.head.hcursor
    val in = e.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
    assertEquals(e.get[String]("kind").toOption, Some("ErgoTree"))
    assertEquals(e.get[String]("error").toOption, Some("errored"))
    assertEquals(e.get[String]("expected_bytes_hex").toOption, None, s"a reject has no canonical output for $in")
    assertEquals(in.take(2), "1b", s"must be a v3 (header 0x1b) tree: $in")
    VersionContext.withVersions(A, E) {
      val b16 = scorex.util.encode.Base16
      // valid base accepts (differential anchor)
      assert(sigma.santa.LenientErgoTree.deserialize(b16.decode(AuthoredWireSHeaderConstantV3Accept.Hex).get).root.isRight,
        "the VALID v3 SHeader base must still parse")
      // only the pk prefix differs from the valid base
      assertEquals(in.length, AuthoredWireSHeaderConstantV3Accept.Hex.length, "malformed must be same length as valid base")
      // the malformed tree rejects
      intercept[Throwable] {
        sigma.santa.LenientErgoTree.deserialize(b16.decode(in).get)
      }
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireSHeaderConstantV3MalformedPkReject.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("ErgoTree.sheader_constant_v3_malformed_pk_reject.json")),
      "staging ErgoTree.sheader_constant_v3_malformed_pk_reject.json not written")
  }
}
