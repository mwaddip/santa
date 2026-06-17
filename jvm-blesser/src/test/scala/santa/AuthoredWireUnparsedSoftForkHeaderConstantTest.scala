package santa

import sigma.VersionContext

/** Anchors the single unparsed soft-fork HEADER-CONSTANT REJECT entry: the JVM rejects an SHeader-typed
  * segregated constant at deserialize (a direct SerializerException escapes the soft-fork fallback,
  * because rule 1009 does NOT special-case SHeader). A failure here means sigma-state started accepting
  * (degrading) the SHeader constant — the over-accept this vector guards against. */
class AuthoredWireUnparsedSoftForkHeaderConstantTest extends munit.FunSuite {
  private val A: Byte = AuthoredWireUnparsedSoftForkHeaderConstant.Activated
  private val E: Byte = AuthoredWireUnparsedSoftForkHeaderConstant.ErgoTreeV
  private lazy val env =
    AuthoredWireUnparsedSoftForkHeaderConstant.extract()(AuthoredWireUnparsedSoftForkHeaderConstant.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  test("1 entry: ErgoTree kind, REJECT (error=errored, no expected_bytes_hex), JVM throws at deserialize") {
    assertEquals(entries.size, 1)
    val e  = entries.head.hcursor
    val in = e.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
    assertEquals(e.get[String]("kind").toOption, Some("ErgoTree"))
    assertEquals(e.get[String]("error").toOption, Some("errored"), "reject entry must carry error=errored")
    assertEquals(e.get[String]("expected_bytes_hex").toOption, None, "a reject has no canonical output")
    VersionContext.withVersions(A, E) {
      val threw =
        try { sigma.santa.LenientErgoTree.deserialize(scorex.util.encode.Base16.decode(in).get); false }
        catch { case _: Throwable => true }
      assert(threw, s"$in must be REJECTED at deserialize (JVM throws), not degraded to Unparsed")
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireUnparsedSoftForkHeaderConstant.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("ErgoTree.unparsed_soft_fork_header_constant.json")),
      "staging ErgoTree.unparsed_soft_fork_header_constant.json not written")
  }
}
