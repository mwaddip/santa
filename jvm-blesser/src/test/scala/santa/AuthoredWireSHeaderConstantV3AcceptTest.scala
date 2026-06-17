package santa

import sigma.VersionContext

/** Anchors the single v3 SHeader-constant ACCEPT entry: at treeVersion 3 the JVM PARSES a segregated
  * SHeader constant (SHeader's DataSerializer is gated on isV3OrLaterErgoTreeVersion) and round-trips
  * byte-identical — the positive side of the boundary whose v2 form rejects. A failure here means
  * sigma-state stopped serializing SHeader constants at v3, or stopped preserving the bytes. */
class AuthoredWireSHeaderConstantV3AcceptTest extends munit.FunSuite {
  private val A: Byte = AuthoredWireSHeaderConstantV3Accept.Activated
  private val E: Byte = AuthoredWireSHeaderConstantV3Accept.ErgoTreeV
  private lazy val env =
    AuthoredWireSHeaderConstantV3Accept.extract()(AuthoredWireSHeaderConstantV3Accept.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  test("1 entry: ErgoTree kind, PARSED at v3 (SHeader serializable), identity round-trip") {
    assertEquals(entries.size, 1)
    val e  = entries.head.hcursor
    val in = e.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
    assertEquals(e.get[String]("kind").toOption, Some("ErgoTree"))
    assertEquals(in.take(2), "1b", s"must be a v3 (header 0x1b) tree: $in")
    // ACCEPT + identity: neither an `error` marker nor an `expected_bytes_hex`.
    assertEquals(e.get[String]("error").toOption, None, s"must ACCEPT (no error marker) for $in")
    assertEquals(e.get[String]("expected_bytes_hex").toOption, None, s"must be identity (no expected) for $in")
    VersionContext.withVersions(A, E) {
      val tree = sigma.santa.LenientErgoTree.deserialize(scorex.util.encode.Base16.decode(in).get)
      assert(tree.root.isRight, s"$in must PARSE at v3 (root = Right), not degrade to UnparsedErgoTree")
      assertEquals(WireCanonicalize.canonicalize("ErgoTree", in, A, E), in,
        s"JVM must round-trip $in byte-identically")
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireSHeaderConstantV3Accept.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("ErgoTree.sheader_constant_v3_accept.json")),
      "staging ErgoTree.sheader_constant_v3_accept.json not written")
  }
}
