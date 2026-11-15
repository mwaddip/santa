package santa

import io.circe.Json

/** Anchors the writer-count-range wire families: every row agrees with the jars (the bytes written back, the id, or
  * the refusal), refused exactly when the name's text is past 255 bytes; a name holding FF is written as its text;
  * the ops land in their slot. */
class AuthoredWireWriterCountRangeTest extends munit.FunSuite {
  private val B = AuthoredWireWriterCountRange
  private lazy val rows = B.rows()

  test("the rows: 138 written (23 positions x 6 names), 36 transactions") {
    assertEquals(B.writtenRows().size, 138)
    assertEquals(B.txRows().size, 36)
    assertEquals(B.writtenRows().count(_.treeVersion == 0), 54)
    assertEquals(B.writtenRows().count(_.treeVersion == 3), 84)
  }

  test("every row agrees with the jars: the bytes written back (wrapped), the id, or the refusal") {
    rows.foreach { r =>
      val o = B.outcome(r)
      r.jars match {
        case "WRITE-THROWS" | "ID-THROWS" => assertEquals(o, Left(()), r.name)
        case w if w.startsWith("WRITES ") =>
          val want = r.tree(B.Row.wrap(w.stripPrefix("WRITES ")))
          assertEquals(o.map(_.getOrElse(r.bytes)), Right(want), r.name)
        case i if i.startsWith("ID ") => assertEquals(B.txId(r), i.stripPrefix("ID "), r.name)
      }
    }
  }

  test("refused exactly when the text is past 255 bytes; a name holding FF is written as its text") {
    rows.foreach { r =>
      val o = B.outcome(r)
      assertEquals(o.isLeft, r.text > 255, s"${r.name}: text ${r.text}")
      if (r.text <= 255) {
        val holdsFF = r.nameToken != "a255"
        assertEquals(o.toOption.get.isDefined, holdsFF, s"${r.name}: re-written iff the name holds FF")
      }
    }
    assertEquals(rows.count(r => B.outcome(r).isLeft), 69 + 18)
  }

  test("ops per version, entry shape; staging files") {
    val v6 = B.extract(v6 = true); val v5 = B.extract(v6 = false)
    assertEquals(v6.keySet, Set(B.OpErgoTree, B.OpTransaction))
    assertEquals(v5.keySet, Set(B.OpErgoTree))
    def entries(j: Json): List[Json] = j.hcursor.downField("entries").as[List[Json]].toOption.get
    assertEquals(entries(v5(B.OpErgoTree)).size, 54)
    assertEquals(entries(v6(B.OpErgoTree)).size, 84)
    assertEquals(entries(v6(B.OpTransaction)).size, 36)
    (entries(v5(B.OpErgoTree)) ++ entries(v6(B.OpErgoTree)) ++ entries(v6(B.OpTransaction))).foreach { e =>
      val c = e.hcursor
      val refused = c.get[String]("error").toOption.contains("errored")
      assert(!(refused && c.downField("expected_bytes_hex").succeeded), c.get[String]("name").toOption.get)
      assert(c.get[String]("description").toOption.get.contains(if (refused) "WRITER REFUSES" else "CONTROL"))
    }
    val outDir = java.nio.file.Paths.get("target", "wire-authored", "writer-count-range")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve("v5").resolve(s"${B.OpErgoTree}.json")))
    assert(java.nio.file.Files.exists(outDir.resolve("v6").resolve(s"${B.OpTransaction}.json")))
  }
}
