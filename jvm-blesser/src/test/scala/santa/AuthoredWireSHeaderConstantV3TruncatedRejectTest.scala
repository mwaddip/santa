package santa

import sigma.VersionContext

/** Anchors the v3 SHeader-constant TRUNCATED REJECT entry: the valid v3 accept tree truncated mid-Header-field
  * throws a hard EOF (non-ValidationException) so the JVM rejects rather than degrades — the (b)-vs-(c)
  * discriminator. A failure here means sigma-state started degrading truncated constants (or stopped accepting
  * the valid base). */
class AuthoredWireSHeaderConstantV3TruncatedRejectTest extends munit.FunSuite {
  private val A: Byte = AuthoredWireSHeaderConstantV3TruncatedReject.Activated
  private val E: Byte = AuthoredWireSHeaderConstantV3TruncatedReject.ErgoTreeV
  private lazy val env =
    AuthoredWireSHeaderConstantV3TruncatedReject.extract()(AuthoredWireSHeaderConstantV3TruncatedReject.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  test("1 entry: ErgoTree kind, REJECT (truncated mid-field, non-ValidationException), valid base accepts") {
    assertEquals(entries.size, 1)
    val e  = entries.head.hcursor
    val in = e.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
    assertEquals(e.get[String]("kind").toOption, Some("ErgoTree"))
    assertEquals(e.get[String]("error").toOption, Some("errored"))
    assertEquals(e.get[String]("expected_bytes_hex").toOption, None, s"a reject has no canonical output for $in")
    assertEquals(in.take(2), "1b", s"must be a v3 (header 0x1b) tree: $in")
    assert(in.length < AuthoredWireSHeaderConstantV3Accept.Hex.length, "truncated must be shorter than the valid base")
    VersionContext.withVersions(A, E) {
      val b16 = scorex.util.encode.Base16
      assert(sigma.santa.LenientErgoTree.deserialize(b16.decode(AuthoredWireSHeaderConstantV3Accept.Hex).get).root.isRight,
        "the VALID v3 SHeader base must still parse")
      val t = intercept[Throwable] {
        sigma.santa.LenientErgoTree.deserialize(b16.decode(in).get)
      }
      val isVE = t.getClass.getName.contains("ValidationException") ||
        Option(t.getCause).exists(_.getClass.getName.contains("ValidationException"))
      assert(!isVE, s"truncation must REJECT (hard EOF), not degrade (ValidationException) — got $t")
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireSHeaderConstantV3TruncatedReject.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("ErgoTree.sheader_constant_v3_truncated_reject.json")),
      "staging ErgoTree.sheader_constant_v3_truncated_reject.json not written")
  }
}
