package santa

import io.circe.Json

/** Anchors the ContextExtension-bounds wire vectors. extract() itself re-derives every verdict through
  * WireCanonicalize and fails loud on a wrong-reason reject; this pins the shape and writes staging.
  * A failure means sigma-state changed how it parses an extension's count or ids. */
class AuthoredWireContextExtensionBoundsTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireContextExtensionBounds.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))

  test("count bound: count-128 rejects, count-127 accepts") {
    val es = entries(AuthoredWireContextExtensionBounds.OpCount)
    assertEquals(es.map(isReject), List(true, false))
    // the extension count byte sits at offset 34 (inputs count + boxId + empty-proof length)
    assertEquals(bytesHex(es(0)).substring(68, 70), "80", "count-128 vector carries count byte 0x80")
    assertEquals(bytesHex(es(1)).substring(68, 70), "7f", "count-127 vector carries count byte 0x7f")
  }

  test("id bound: 0x80, 0xff, rent-shaped 0x80 reject; 0x7f accepts") {
    val es = entries(AuthoredWireContextExtensionBounds.OpId)
    assertEquals(es.map(isReject), List(true, true, true, false))
    assertEquals(bytesHex(es(0)).substring(68, 72), "0180", "one entry, id 0x80")
    assertEquals(bytesHex(es(1)).substring(68, 72), "01ff", "one entry, id 0xff")
    assertEquals(bytesHex(es(2)).substring(68, 72), "027f", "two entries, var 127 first")
    assertEquals(bytesHex(es(3)).substring(68, 72), "017f", "one entry, id 0x7f")
  }

  test("envelopes: santa-wire/v1, v6 activation, authored source, 6.0.6 blessing") {
    vectors.values.foreach { env =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:")))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireContextExtensionBounds.writeVectors(outDir)
    Seq(AuthoredWireContextExtensionBounds.OpCount, AuthoredWireContextExtensionBounds.OpId).foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
