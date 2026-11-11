package santa

import io.circe.Json

/** Anchors the xor cost family. Every claim here is a COST, and a cost family that stops being
  * about the chunk boundary grades green while saying nothing — so the boundary, the chunk width
  * and the min-operand rule are each pinned by a PAIR rather than by a single number.
  */
class AuthoredEvalXorPerItemCostTest extends munit.FunSuite {
  private val B = AuthoredEvalXorPerItemCost
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Long =
    e.hcursor.downField("expected").get[Long]("cost").toOption.getOrElse(fail("cost"))
  private def row(id: String): Json = entries.filter(e => str(e, "script").startsWith(s"[$id]")) match {
    case List(e) => e
    case other   => fail(s"want one [$id], got ${other.map(str(_, "name"))}")
  }

  test("10 rows: 5 node, 5 method, all accepts") {
    assertEquals(entries.size, 10)
    assert(entries.forall(e => e.hcursor.downField("expected").get[String]("error").toOption.isEmpty))
  }

  test("THE CHUNK: 128 wide at 2 apiece, pinned by a pair on both routes") {
    assertEquals(cost(row("N1")), 22L)
    assertEquals(cost(row("N2")), 22L, "1..128 items cost the same — the chunk is 128 wide")
    assertEquals(cost(row("N3")), 24L, "129 crosses into a second chunk, at 2")
    assertEquals(cost(row("N3")) - cost(row("N2")), 2L)
    assertEquals(cost(row("M1")), 31L)
    assertEquals(cost(row("M2")), 31L)
    assertEquals(cost(row("M3")), 33L, "the METHOD charges the same per-chunk 2 — a fixed cost cannot")
    assertEquals(cost(row("M3")) - cost(row("M2")), 2L)
  }

  test("THE MIN RULE: both operand orders cost the same, on both routes") {
    assertEquals(cost(row("N4")), 22L, "129 on the LEFT is free — the shorter operand is charged")
    assertEquals(cost(row("N5")), 22L)
    assertEquals(cost(row("N4")), cost(row("N5")))
    assertEquals(cost(row("M4")), 31L)
    assertEquals(cost(row("M5")), 31L)
    assertEquals(cost(row("M4")), cost(row("M5")))
    // and the asymmetric rows must cost the floor, not the long-operand price
    assertEquals(cost(row("N4")), cost(row("N1")))
    assertEquals(cost(row("M4")), cost(row("M1")))
  }

  test("the method's base is the node's plus the MethodCall's own cost, at every length") {
    Seq(("N1", "M1"), ("N2", "M2"), ("N3", "M3"), ("N4", "M4"), ("N5", "M5")).foreach {
      case (n, m) => assertEquals(cost(row(m)) - cost(row(n)), 9L, s"$m - $n")
    }
  }

  test("the trees really are the lengths they claim, and the method rows are MethodCalls") {
    // a mis-built operand would change a length without changing a verdict, so check the bytes
    assert(str(row("N3"), "tree_bytes_hex").count(_ => true) > str(row("N1"), "tree_bytes_hex").length)
    Seq("M1", "M2", "M3", "M4", "M5").foreach { id =>
      assert(str(row(id), "tree_bytes_hex").contains("dc6a02"),
        s"$id must be a MethodCall on SGlobal (0x6a) method 2")
    }
    Seq("N1", "N2", "N3", "N4", "N5").foreach { id =>
      assert(str(row(id), "tree_bytes_hex").contains("9b0e"),
        s"$id must be the Xor NODE (0x9b) over byte collections")
    }
    assert(str(row("M1"), "script").contains("CORPUS'S FIRST"),
      "M1 must record that the method had no row before this family")
  }

  test("envelope + write") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
