package santa

/** Guard for the assembled vendored wire corpus: VendoredWireErgots + VendoredWireFleet merged
  * by op. The Box slice unions ergots (4) + Fleet (7); each entry keeps its own framework
  * `source`. Writes the merged staging (cp into vectors/wire/v5/vendored/), anchors the merged
  * counts, and prints the combined JVM-vs-vendor diff/reject summary. */
class VendoredWireTest extends munit.FunSuite {
  lazy val result: (Map[String, io.circe.Json], Seq[String], Seq[String]) = VendoredWire.corpus()
  private def vectors = result._1
  private def diffs   = result._2
  private def rejects = result._3

  private def entriesOf(op: String): Int =
    vectors.get(op).flatMap(_.hcursor.downField("entries").as[List[io.circe.Json]].toOption).map(_.size).getOrElse(0)

  test("merged Box slice unions ergots + Fleet, both sources present, all canonical fixpoints") {
    val env = vectors.getOrElse("Box", fail(s"no Box op; got ${vectors.keys.toSeq.sorted}"))
    val entries = env.hcursor.downField("entries").as[List[io.circe.Json]].fold(e => fail(s"Box entries: $e"), identity)
    val sources = entries.flatMap(_.hcursor.get[String]("source").toOption).toSet
    assertEquals(sources, Set("ergots:fixture-gen/wire", "fleet:serializer/_test-vectors"), s"Box sources: $sources")
    entries.foreach { e =>
      val ec = e.hcursor
      assertEquals(ec.get[String]("kind").toOption, Some("Box"), "Box entry kind")
      val hex = ec.get[String]("bytes_hex").toOption.getOrElse(fail("Box bytes_hex"))
      assertEquals(WireCanonicalize.canonicalize("Box", hex, 2, 2), hex, s"Box[$hex] not a canonical fixpoint")
    }
  }

  // ── merged-corpus anchors. A change means a harvester's contribution moved — investigate.
  test("merged round-trip entry counts anchored") {
    assertEquals(entriesOf("Box"), 11, "Box = ergots 4 + Fleet 7")
    assertEquals(entriesOf("SigmaBoolean"), 7, "SigmaBoolean (ergots)")
    assertEquals(entriesOf("Transaction"), 17, "Transaction (Fleet)")
    assertEquals(entriesOf("Constant"), 178, "Constant (Fleet)")
  }

  test("summary + combined diffs/rejects + write merged staging") {
    val sb = new StringBuilder("\n============== vendored wire corpus (merged) ==============\n")
    vectors.toSeq.sortBy(_._1).foreach { case (op, env) =>
      val n = env.hcursor.downField("entries").as[List[io.circe.Json]].map(_.size).getOrElse(0)
      sb.append(s"  $op: $n round-trip entries\n")
    }
    if (diffs.isEmpty) sb.append("  bytes-differ: none (JVM agrees with every vendored seed)\n")
    else { sb.append(s"  bytes-differ findings (${diffs.size}):\n"); diffs.foreach(d => sb.append(s"    $d\n")) }
    if (rejects.isEmpty) sb.append("  JVM-reject: none\n")
    else { sb.append(s"  JVM-reject findings (${rejects.size}, excluded from round-trip corpus):\n")
           rejects.foreach(r => sb.append(s"    $r\n")) }
    sb.append("===========================================================\n")
    println(sb.toString)

    val outDir = java.nio.file.Paths.get("target", "wire-vectors")
    VendoredWire.writeVectors(vectors, outDir)
    Seq("Box", "SigmaBoolean", "Transaction", "Constant").foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written"))
  }
}
