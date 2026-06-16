package santa

import sigma.VersionContext

/** Anchors the 2 Box-bearing unparsed soft-fork entries (identity round-trip: the JVM preserves the raw
  * box bytes, so boxId is over the raw tree) and writes the staging file to cp into
  * vectors/wire/v6/authored/. The Box kind exercises the soft-fork wrap (size flag intact) that the bare
  * ErgoTree arm can strip past. A failure here means the JVM stopped preserving the box round-trip. */
class AuthoredWireBoxUnparsedSoftForkTest extends munit.FunSuite {
  private val V3: Byte = VersionContext.V6SoftForkVersion
  private lazy val env = AuthoredWireBoxUnparsedSoftFork.extract()(AuthoredWireBoxUnparsedSoftFork.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  test("all 2 entries: Box kind, identity round-trip (JVM preserves the box / boxId over raw tree)") {
    assertEquals(entries.size, 2)
    entries.foreach { e =>
      val c   = e.hcursor
      val in  = c.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
      assertEquals(c.get[String]("kind").toOption, Some("Box"))
      assertEquals(c.get[String]("expected_bytes_hex").toOption, None, s"must be identity (no expected) for $in")
      VersionContext.withVersions(V3, V3) {
        assertEquals(WireCanonicalize.canonicalize("Box", in, V3, V3), in,
          s"JVM must preserve the box $in byte-identically (identity round-trip)")
      }
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireBoxUnparsedSoftFork.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("Box.unparsed_soft_fork_boxid.json")),
      "staging Box.unparsed_soft_fork_boxid.json not written")
  }
}
