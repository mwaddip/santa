package santa

/** Guard + smoke for the Fleet-sourced vendored wire vectors (source
  * fleet:serializer/_test-vectors). Each op is a well-formed santa-wire/v1 envelope; every
  * bytes_hex is a JVM canonical fixpoint (round-trip-to-self); every vendored Fleet candidate
  * is accounted for (a round-trip entry XOR a reject). The merged staging write — Fleet's Box
  * unioned with ergots' — lives in VendoredWireTest. */
class VendoredWireFleetTest extends munit.FunSuite {
  lazy val result: (Map[String, io.circe.Json], Seq[String], Seq[String]) = VendoredWireFleet.extract()
  private def vectors = result._1
  private def diffs   = result._2
  private def rejects = result._3

  // op -> vendored seed file (each a JSON array; one candidate per element).
  private val opSeed = Map(
    "Transaction" -> "signedTransactions.json",
    "Constant"    -> "constants.json",
    "Box"         -> "boxes.json")

  private def seedCount(file: String): Int = {
    val text = new String(java.nio.file.Files.readAllBytes(
      java.nio.file.Paths.get("src", "test", "resources", "fleet-wire", file)),
      java.nio.charset.StandardCharsets.UTF_8)
    io.circe.parser.parse(text).toOption.flatMap(_.asArray).map(_.size).getOrElse(-1)
  }
  private def entriesOf(op: String): Int =
    vectors.get(op).flatMap(_.hcursor.downField("entries").as[List[io.circe.Json]].toOption).map(_.size).getOrElse(0)
  private def rejectsFor(op: String): Int = rejects.count(_.startsWith(s"$op/"))

  test("each Fleet op is a well-formed santa-wire/v1 envelope of canonical fixpoints") {
    opSeed.keys.foreach { op =>
      val env = vectors.getOrElse(op, fail(s"no $op op; got ${vectors.keys.toSeq.sorted}"))
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"), s"$op schema")
      assertEquals(c.get[String]("op").toOption, Some(op), s"$op op")
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"), s"$op blessed_by")
      val entries = c.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"$op entries: $e"), identity)
      assert(entries.nonEmpty, s"$op has no entries")
      entries.foreach { e =>
        val ec = e.hcursor
        assert(ec.get[String]("name").toOption.exists(_.nonEmpty), s"$op entry name")
        assertEquals(ec.get[String]("kind").toOption, Some(op), s"$op entry kind")
        assertEquals(ec.get[String]("source").toOption, Some("fleet:serializer/_test-vectors"), s"$op entry source")
        assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(2), s"$op activated")
        val hex = ec.get[String]("bytes_hex").toOption.getOrElse(fail(s"$op bytes_hex"))
        assert(hex.matches("^([0-9a-f]{2})+$"), s"$op bytes_hex malformed: $hex")
        // round-trip-to-self: the committed bytes are a JVM canonical fixpoint.
        assertEquals(WireCanonicalize.canonicalize(op, hex, 2, 2), hex, s"$op[$hex] not a canonical fixpoint")
      }
    }
  }

  test("every vendored Fleet candidate is accounted for (round-trip entry XOR reject)") {
    opSeed.foreach { case (op, file) =>
      assertEquals(entriesOf(op) + rejectsFor(op), seedCount(file),
        s"$op accounting: ${entriesOf(op)} round-trip + ${rejectsFor(op)} reject != ${seedCount(file)} vendored")
    }
  }

  // ── regression anchors. A change means the JVM serializer, Fleet's bytes, or a vendored
  //    seed moved — investigate, don't blind-rebless.
  test("round-trip entry counts anchored (Fleet)") {
    assertEquals(entriesOf("Transaction"), 17, "Fleet signed-tx round-trip entries")
    assertEquals(entriesOf("Constant"), 178, "Fleet constant round-trip entries")
    assertEquals(entriesOf("Box"), 7, "Fleet box round-trip entries")
    assertEquals(diffs.size, 0, s"unexpected JVM-vs-Fleet bytes-differ: $diffs")
    assertEquals(rejects.size, 0, s"unexpected JVM-reject: $rejects")
  }
}
