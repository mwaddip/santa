package santa

/** Guard + smoke for the vendored (ergots) wire vectors. Asserts both ops are well-formed
  * santa-wire/v1 envelopes with per-entry ergots source, every bytes_hex is a JVM canonical
  * fixpoint (round-trip-to-self), and the lone JVM-reject finding is sbox_boundary
  * (creation_height = u32::MAX), excluded from the round-trip corpus
  * (docs/findings/wire-jvm-vs-sigma-rust.md). The merged staging write lives in VendoredWireTest. */
class VendoredWireErgotsTest extends munit.FunSuite {
  lazy val result: (Map[String, io.circe.Json], Seq[String], Seq[String]) = VendoredWireErgots.extract()
  private def vectors = result._1
  private def rejects = result._3

  private val expectedOps = Set("Box", "SigmaBoolean")

  test("both ergots wire ops are well-formed santa-wire/v1 envelopes of canonical fixpoints") {
    assertEquals(vectors.keySet, expectedOps, s"got ${vectors.keys.toSeq.sorted}")
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"), s"$op schema")
      assertEquals(c.get[String]("op").toOption, Some(op), s"$op op")
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"), s"$op blessed_by")
      val entries = c.downField("entries").as[List[io.circe.Json]]
        .fold(e => fail(s"$op entries: $e"), identity)
      assert(entries.nonEmpty, s"$op has no entries")
      entries.foreach { e =>
        val ec = e.hcursor
        assert(ec.get[String]("name").toOption.exists(_.nonEmpty), s"$op entry name")
        assertEquals(ec.get[String]("kind").toOption, Some(op), s"$op entry kind")
        assertEquals(ec.get[String]("source").toOption, Some("ergots:fixture-gen/wire"), s"$op entry source")
        assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(2), s"$op activated")
        val hex = ec.get[String]("bytes_hex").toOption.getOrElse(fail(s"$op entry bytes_hex"))
        assert(hex.matches("^([0-9a-f]{2})+$"), s"$op bytes_hex malformed: $hex")
        // round-trip-to-self: the committed bytes are a JVM canonical fixpoint.
        assertEquals(WireCanonicalize.canonicalize(op, hex, 2, 2), hex, s"$op[$hex] not a canonical fixpoint")
      }
    }
  }

  // ── regression anchors. A change means the JVM serializer or a seed moved — investigate.
  test("round-trip entry counts anchored (ergots)") {
    def count(op: String) =
      vectors(op).hcursor.downField("entries").as[List[io.circe.Json]].map(_.size).getOrElse(-1)
    assertEquals(count("Box"), 4, "Box round-trip entries (sbox_boundary excluded — JVM rejects u32::MAX height)")
    assertEquals(count("SigmaBoolean"), 7, "SigmaBoolean round-trip entries (sigma-boolean-variants)")
  }

  test("sbox_boundary is the lone JVM-reject finding (u32::MAX creation_height)") {
    assertEquals(rejects.size, 1, s"expected exactly one JVM-reject, got: $rejects")
    assert(rejects.head.startsWith("Box/sbox_boundary"), s"reject should be Box/sbox_boundary: ${rejects.head}")
  }
}
