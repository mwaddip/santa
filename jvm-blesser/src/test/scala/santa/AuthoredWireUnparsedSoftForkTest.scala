package santa

import sigma.VersionContext

/** Anchors the 2 unparsed soft-fork tree entries (identity round-trip: the JVM preserves the raw
  * bytes) and writes the staging file to cp into vectors/wire/v6/authored/. A failure here means
  * sigma-state stopped wrapping 0xfd as UnparsedErgoTree, or stopped preserving its bytes. */
class AuthoredWireUnparsedSoftForkTest extends munit.FunSuite {
  private val V3: Byte = VersionContext.V6SoftForkVersion
  private lazy val env = AuthoredWireUnparsedSoftFork.extract()(AuthoredWireUnparsedSoftFork.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  test("all 2 entries: ErgoTree kind, genuinely UNPARSED, identity round-trip (JVM preserves)") {
    assertEquals(entries.size, 2)
    entries.foreach { e =>
      val c   = e.hcursor
      val in  = c.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
      assertEquals(c.get[String]("kind").toOption, Some("ErgoTree"))
      // identity: no expected_bytes_hex (absent => round-trip-to-self).
      assertEquals(c.get[String]("expected_bytes_hex").toOption, None, s"must be identity (no expected) for $in")
      VersionContext.withVersions(V3, V3) {
        val tree = sigma.santa.LenientErgoTree.deserialize(scorex.util.encode.Base16.decode(in).get)
        assert(tree.root.isLeft, s"$in must be an UnparsedErgoTree (soft-fork), not a parsed tree")
        assertEquals(WireCanonicalize.canonicalize("ErgoTree", in, V3, V3), in,
          s"JVM must preserve $in byte-identically (identity round-trip)")
      }
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireUnparsedSoftFork.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("ErgoTree.unparsed_soft_fork_roundtrip.json")),
      "staging ErgoTree.unparsed_soft_fork_roundtrip.json not written")
  }
}
