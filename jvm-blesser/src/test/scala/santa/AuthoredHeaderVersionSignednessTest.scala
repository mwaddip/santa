package santa

/** Guard for the block-header version signedness witness (ergots ask 21). Same body, version byte
  * 0x7f (control — reads unparsedBytes) vs 0x80 (skips → shifted AutolykosSolution). Both ACCEPT a
  * GroupElement minerPk; the two minerPk values MUST DIFFER (the signedness fork — an unsigned reader
  * gives the 0x7f value for 0x80). Prints the blessed pair; writes staging. */
class AuthoredHeaderVersionSignednessTest extends munit.FunSuite {
  lazy val vectors: Map[String, io.circe.Json] = AuthoredHeaderVersionSignedness.extract()

  test("0x7f control / 0x80 divergence; both accept GroupElement, minerPk differs") {
    val env = vectors(AuthoredHeaderVersionSignedness.Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("source").toOption, Some(AuthoredHeaderVersionSignedness.Source))
    val es = env.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"entries: $e"), identity)
    assertEquals(es.flatMap(_.hcursor.get[String]("name").toOption),
      List("version-0x7f-reads-unparsedbytes#0", "version-0x80-skips-unparsedbytes#1"))
    es.foreach { e =>
      val exp = e.hcursor.downField("expected")
      val nm = e.hcursor.get[String]("name").toOption.getOrElse("?")
      assertEquals(exp.downField("value").get[String]("kind").toOption, Some("GroupElement"),
        s"$nm: minerPk is a GroupElement")
      assert(exp.downField("error").focus.exists(_.isNull), s"$nm: must accept (error null)")
    }
    def pkHex(e: io.circe.Json): Option[String] =
      e.hcursor.downField("expected").downField("value").get[String]("bytes_hex").toOption
    val pk7f = pkHex(es.head); val pk80 = pkHex(es(1))
    assert(pk7f != pk80, s"0x7f and 0x80 minerPk must differ (the signedness fork)\n  0x7f=$pk7f\n  0x80=$pk80")
    // locked baseline: 0x7f reads the real Ecp; 0x80 skips unparsedBytes → the point at infinity (33 zero bytes)
    assertEquals(pk7f, Some("03bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c"), "0x7f minerPk drifted")
    assertEquals(pk80, Some("0" * 66), "0x80 minerPk (INF) drifted")
    println(s"\n=== Ask 21 blessed minerPk ===\n  0x7f: ${pk7f.getOrElse("?")}\n  0x80: ${pk80.getOrElse("?")}\n")
  }

  test("write staging") {
    val out = java.nio.file.Paths.get("target", "version-signedness-vectors")
    AuthoredHeaderVersionSignedness.writeVectors(out)
    assert(java.nio.file.Files.exists(out.resolve("Header.version_unparsedbytes_gate.json")),
      "staging Header.version_unparsedbytes_gate.json not written")
  }
}
