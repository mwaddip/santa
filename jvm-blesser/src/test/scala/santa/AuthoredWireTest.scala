package santa

/** Guard + smoke for the authored wire vectors. Asserts both ops are well-formed
  * santa-wire/v1 envelopes, every committed bytes_hex is a canonical fixpoint
  * (round-trip-to-self), reports JVM-vs-sigma-rust diffs + rejects, and writes the staging
  * vectors. sbox_boundary (creation_height = u32::MAX) is the lone JVM-reject finding,
  * excluded from the round-trip corpus (docs/findings/wire-jvm-vs-sigma-rust.md). Mirrors
  * AuthoredSerializeTest. */
class AuthoredWireTest extends munit.FunSuite {
  lazy val result: (Map[String, io.circe.Json], Seq[String], Seq[String]) = AuthoredWire.extract()
  private def vectors = result._1
  private def diffs   = result._2
  private def rejects = result._3

  private val expectedOps = Set("Box", "SigmaBoolean")

  test("both wire ops authored, each a well-formed santa-wire/v1 envelope") {
    assertEquals(vectors.keySet, expectedOps, s"got ${vectors.keys.toSeq.sorted}")
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"), s"$op schema")
      assertEquals(c.get[String]("op").toOption, Some(op), s"$op op")
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"), s"$op blessed_by")
      assertEquals(c.get[String]("source").toOption, Some("ergots:fixture-gen/wire"), s"$op source")
      val entries = c.downField("entries").as[List[io.circe.Json]]
        .fold(e => fail(s"$op entries: $e"), identity)
      assert(entries.nonEmpty, s"$op has no entries")
      entries.foreach { e =>
        val ec = e.hcursor
        assert(ec.get[String]("name").toOption.exists(_.nonEmpty), s"$op entry name")
        assertEquals(ec.get[String]("kind").toOption, Some(op), s"$op entry kind")
        assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(2), s"$op activated")
        val hex = ec.get[String]("bytes_hex").toOption.getOrElse(fail(s"$op entry bytes_hex"))
        assert(hex.matches("^([0-9a-f]{2})+$"), s"$op bytes_hex malformed: $hex")
        // round-trip-to-self: the committed bytes are a canonical fixpoint.
        assertEquals(WireCanonicalize.canonicalize(op, hex, 2, 2), hex, s"$op[$hex] not a canonical fixpoint")
      }
    }
  }

  test("summary + JVM-vs-sigma-rust diffs/rejects + write staging") {
    val sb = new StringBuilder("\n==================== authored wire vectors ====================\n")
    vectors.toSeq.sortBy(_._1).foreach { case (op, env) =>
      val n = env.hcursor.downField("entries").as[List[io.circe.Json]].map(_.size).getOrElse(0)
      sb.append(s"  $op: $n round-trip entries\n")
    }
    if (diffs.isEmpty) sb.append("  bytes-differ: none (JVM agrees with sigma-rust on every parsed seed)\n")
    else { sb.append(s"  bytes-differ findings (${diffs.size}):\n"); diffs.foreach(d => sb.append(s"    $d\n")) }
    if (rejects.isEmpty) sb.append("  JVM-reject: none\n")
    else { sb.append(s"  JVM-reject findings (${rejects.size}, excluded from round-trip corpus):\n")
           rejects.foreach(r => sb.append(s"    $r\n")) }
    sb.append("===============================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "wire-vectors")
    AuthoredWire.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("Box.json")), "staging Box.json not written")
    assert(java.nio.file.Files.exists(outDir.resolve("SigmaBoolean.json")), "staging SigmaBoolean.json not written")
  }

  // ── regression anchors (cf. AuthoredSerializeTest). A change means the JVM serializer or a
  //    seed moved — investigate, don't blind-rebless.
  test("round-trip entry counts anchored") {
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
