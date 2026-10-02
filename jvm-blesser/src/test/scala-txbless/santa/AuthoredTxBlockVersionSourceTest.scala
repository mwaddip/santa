package santa

import io.circe.Json

/** Bless + lock the block-version-source vectors. blessAll() fails loud unless the JVM gives each entry its verdict for
  * its reason; this pins that the activated script version and the monotonic creation-height rule are judged by the
  * parameters' block version, with preHeader.version set apart. A failure means TxEngine, ergo-core or sigma-state
  * changed where the block version is read. */
class AuthoredTxBlockVersionSourceTest extends munit.FunSuite {
  import AuthoredTxBlockVersionSource._
  private lazy val blessed = blessAll().toMap
  private def entries: List[Json] =
    blessed(Path).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def valid(e: Json): Boolean = e.hcursor.downField("expected").get[Boolean]("valid").toOption.get
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption

  test("the block version that activates scripts and gates the height rule is the parameters', not the header's") {
    val es = entries
    assertEquals(es.length, 8)
    assertEquals(es.map(valid), List(true, false, true, true, true, false, true, true))
    // the two block versions are set APART in every entry
    es.foreach { e =>
      val pbv = e.hcursor.downField("parameters").get[Int]("blockVersion").toOption.getOrElse(fail("parameters.blockVersion"))
      val phv = e.hcursor.downField("preHeader").get[Int]("version").toOption.getOrElse(fail("preHeader.version"))
      assert(pbv != phv, s"params $pbv must differ from preHeader $phv")
    }
    // the unverified accept (params 5 -> activated 4, above max supported 3) costs 12100; the verified accepts 12105
    assertEquals(cost(es(4)), Some(12100L), "R5 unverified")
    List(0, 2, 3, 6).foreach(i => assertEquals(cost(es(i)), Some(12105L), s"#$i verified"))
  }

  test("write vectors") {
    writeVectors(blessed.toSeq, java.nio.file.Paths.get("..", "vectors"))
  }
}
