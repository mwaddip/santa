package santa

import io.circe.Json

/** Anchors the node-static-type wire families: the 88 trees as sigma-rust sent them, each root's body built from the
  * parts its description names, the jars' verdict on every tree (re-derived per carrier in extract()), and STATIC TYPE
  * on exactly the 28 trees whose verdict the node's type decides. */
class AuthoredWireNodeStaticTypeTest extends munit.FunSuite {
  private val B = AuthoredWireNodeStaticType
  private lazy val ts = B.trees()
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))

  test("88 trees: 22 roots x 4 forms, each body built from the parts its description names") {
    assertEquals(ts.size, 88)
    assertEquals(ts.map(_.root).distinct.size, 22)
    assertEquals(ts.map(_.root).toSet, B.Roots.keySet)
    ts.foreach { t =>
      assertEquals(t.body, B.Roots(t.root)._1, t.name)
      val header = t.form match { case "v0" => "00"; case "v0-sized" => "08"; case "v1-sized" => "09"; case "v3-sized" => "0b" }
      assert(t.bytes.startsWith(header), t.name)
    }
  }

  test("the jars' verdict on every tree, both carriers; STATIC TYPE on the 28 the node's type decides") {
    Seq(B.OpBox, B.OpTx).foreach { op =>
      val es = entries(op)
      assertEquals(es.size, 88, op)
      es.zip(ts).foreach { case (e, t) =>
        val rejected = e.hcursor.get[String]("error").toOption.contains("errored")
        assertEquals(rejected, t.jars == "rejected", s"$op ${t.name}")
        assert(str(e, "description").startsWith(s"[${t.name}]"), s"$op ${t.name}")
        assertEquals(e.hcursor.get[String]("expected_bytes_hex").toOption, None, s"$op ${t.name}: read as written")
      }
      assertEquals(es.count(e => str(e, "description").contains("STATIC TYPE:")), 28, op)
      assertEquals(es.count(_.hcursor.get[String]("error").toOption.contains("errored")), 40, op)
    }
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    val outDir = java.nio.file.Paths.get("target", "wire-authored", "node-static-type")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 2L)
  }
}
