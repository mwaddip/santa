package santa

import sigma.VersionContext
import sigma.serialization.SigmaSerializer
import org.ergoplatform.ErgoBox

/** Anchors the Box-path SHeader REJECT entry: the JVM rejects an ErgoBox whose propositionBytes are an
  * SHeader-constant tree (the strict box->tree parse throws a SerializerException). Grades the strict
  * sigma_parse over-accept the lenient ErgoTree (b) arm misses. A failure means sigma-state started
  * accepting (degrading) the box. */
class AuthoredWireBoxSoftForkHeaderRejectTest extends munit.FunSuite {
  private val A: Byte = AuthoredWireBoxSoftForkHeaderReject.Activated
  private val E: Byte = AuthoredWireBoxSoftForkHeaderReject.ErgoTreeV
  private lazy val env =
    AuthoredWireBoxSoftForkHeaderReject.extract()(AuthoredWireBoxSoftForkHeaderReject.Op)
  private def entries =
    env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)

  test("1 entry: Box kind, REJECT (error=errored, no expected_bytes_hex), JVM box parse throws") {
    assertEquals(entries.size, 1)
    val e  = entries.head.hcursor
    val in = e.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
    assertEquals(e.get[String]("kind").toOption, Some("Box"))
    assertEquals(e.get[String]("error").toOption, Some("errored"), "reject entry must carry error=errored")
    assertEquals(e.get[String]("expected_bytes_hex").toOption, None, "a reject has no canonical output")
    // the box embeds the raw SHeader tree (boxId would be over it) and the strict parse rejects it
    assert(in.contains(AuthoredWireUnparsedSoftForkHeaderConstant.Hex),
      "the box must embed the raw SHeader tree bytes as propositionBytes")
    VersionContext.withVersions(A, E) {
      val threw =
        try { ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(scorex.util.encode.Base16.decode(in).get)); false }
        catch { case _: Throwable => true }
      assert(threw, s"box $in must be REJECTED at parse (JVM throws), not degraded/accepted")
    }
  }

  test("write staging file") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireBoxSoftForkHeaderReject.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("Box.softfork_header_constant_reject.json")),
      "staging Box.softfork_header_constant_reject.json not written")
  }
}
