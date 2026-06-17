package santa

import sigma.VersionContext

/** Anchors the single unparsed soft-fork OPTION-CONSTANT entry (identity round-trip: the JVM
  * degrades the SOption-typecode segregated constant to UnparsedErgoTree via rule 1009 and
  * preserves the raw bytes). A failure here means sigma-state stopped soft-forking the Option
  * typecode at rule 1009, or stopped preserving the unparsed tree's bytes. */
class AuthoredWireUnparsedSoftForkOptionConstantTest extends munit.FunSuite {
  private val A: Byte = AuthoredWireUnparsedSoftForkOptionConstant.Activated
  private val E: Byte = AuthoredWireUnparsedSoftForkOptionConstant.ErgoTreeV
  private lazy val env =
    AuthoredWireUnparsedSoftForkOptionConstant.extract()(AuthoredWireUnparsedSoftForkOptionConstant.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  test("1 entry: ErgoTree kind, genuinely UNPARSED, identity round-trip (JVM preserves)") {
    assertEquals(entries.size, 1)
    val e  = entries.head.hcursor
    val in = e.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
    assertEquals(e.get[String]("kind").toOption, Some("ErgoTree"))
    // identity: no expected_bytes_hex (absent => round-trip-to-self).
    assertEquals(e.get[String]("expected_bytes_hex").toOption, None, s"must be identity (no expected) for $in")
    VersionContext.withVersions(A, E) {
      val tree = sigma.santa.LenientErgoTree.deserialize(scorex.util.encode.Base16.decode(in).get)
      assert(tree.root.isLeft, s"$in must be an UnparsedErgoTree (soft-fork), not a parsed tree")
      assertEquals(WireCanonicalize.canonicalize("ErgoTree", in, A, E), in,
        s"JVM must preserve $in byte-identically (identity round-trip)")
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireUnparsedSoftForkOptionConstant.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("ErgoTree.unparsed_soft_fork_option_constant.json")),
      "staging ErgoTree.unparsed_soft_fork_option_constant.json not written")
  }
}
