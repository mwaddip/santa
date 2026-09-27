package santa

import io.circe.Json

/** Anchors the sized-tree declared-size wire vectors. extract() re-derives each JVM round-trip through
  * WireCanonicalize; this pins the shape the JVM answered with: the declared size of a size-flagged tree
  * that parses is ignored, the parse continues after the bytes the body consumed, and re-serialization writes
  * the recomputed size. A failure means sigma-state started honouring the declared size (or the blesser
  * spliced the wrong byte). */
class AuthoredWireSizedTreeDeclaredSizeTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireSizedTreeDeclaredSize.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, field: String): Option[String] = e.hcursor.get[String](field).toOption

  // Header 0x09 (v1 + size flag), declared size, body 08 d3 (a SigmaProp constant: TrueProp).
  private val Control = "09" + "02" + "08d3"
  private val Over    = "09" + "03" + "08d3"
  private val Under   = "09" + "01" + "08d3"

  Seq(AuthoredWireSizedTreeDeclaredSize.OpBox, AuthoredWireSizedTreeDeclaredSize.OpTransaction).foreach { op =>
    test(s"$op: control round-trips; declared +1 / -1 re-serialize to the control's bytes") {
      val es = entries(op)
      assertEquals(es.size, 3)
      val control = str(es(0), "bytes_hex").get
      assert(control.contains(Control), "control carries the canonical size")
      assertEquals(str(es(0), "expected_bytes_hex"), None, "control is an identity round-trip")
      assertEquals(str(es(0), "error"), None)
      Seq(1 -> Over, 2 -> Under).foreach { case (i, tree) =>
        val in = str(es(i), "bytes_hex").get
        assertEquals(in, control.replace(Control, tree), s"entry $i differs from the control only in the size byte")
        assertEquals(str(es(i), "expected_bytes_hex"), Some(control), s"entry $i: the JVM writes the recomputed size")
        assertEquals(str(es(i), "error"), None, s"entry $i is accepted")
      }
    }
  }

  test("envelopes: santa-wire/v1, version (3, 3), authored source, 6.0.6 blessing, kind matches the op") {
    assertEquals(vectors.keySet,
      Set(AuthoredWireSizedTreeDeclaredSize.OpBox, AuthoredWireSizedTreeDeclaredSize.OpTransaction))
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some(op.takeWhile(_ != '.')))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
        assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:")))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireSizedTreeDeclaredSize.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
