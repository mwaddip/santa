package santa

import io.circe.Json

/** Anchors the missing-v5-methods WIRE family: 14 accepts, the v0 rows evidence and the v3 rows
  * controls. `extract()` already fails loud if any does not round-trip to itself; this pins the
  * mirror (getting it backwards silently turns evidence into a control) and that the two
  * eval-blind trees stay labelled. */
class AuthoredWireV5MissingMethodsTest extends munit.FunSuite {
  private val B = AuthoredWireV5MissingMethods
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def tv(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("ergoTree"))

  test("7 trees x 2 versions = 14, all accepts") {
    assertEquals(entries.size, 14)
    entries.foreach { e =>
      assert(e.hcursor.get[String]("error").toOption.isEmpty, str(e, "name"))
      assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty, str(e, "name"))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
    }
    assertEquals(entries.count(tv(_) == 0), 7)
    assertEquals(entries.count(tv(_) == 3), 7)
  }

  test("the mirror: v0 rows are evidence, v3 rows are controls") {
    entries.foreach { e =>
      val d = str(e, "description")
      if (tv(e) == 0) {
        assert(d.contains("THE SEPARATING ROW"), s"${str(e, "name")}: the v0 row is the evidence")
        assert(!d.contains("THE CONTROL OF THIS PAIR"), str(e, "name"))
      } else {
        assert(d.contains("THE CONTROL OF THIS PAIR"), s"${str(e, "name")}: the v3 row is the control")
        assert(!d.contains("THE SEPARATING ROW"), str(e, "name"))
      }
    }
  }

  test("each pair differs only in the header, so nothing else can explain a red") {
    V5MissingMethodTrees.wrapped.foreach { case (slug, t0, t3) =>
      val rows = entries.filter(e => str(e, "name").contains(s"-$slug-"))
      assertEquals(rows.size, 2, slug)
      assert(rows.exists(e => str(e, "bytes_hex") == t0), s"$slug v0")
      assert(rows.exists(e => str(e, "bytes_hex") == t3), s"$slug v3")
      // the v0 body and the v3 body are the same expression
      assertEquals(t0.drop(2), t3.drop(4), s"$slug: the two forms must share a body")
    }
  }

  test("the two eval-blind trees stay labelled as such") {
    val blind = entries.filter(e => str(e, "description").contains("NOT the method under test"))
    assertEquals(blind.size, 4, "both versions of both trees")
    blind.foreach(e => assert(
      V5MissingMethodTrees.evalBlindTrees.exists(s => str(e, "name").contains(s"-$s-")), str(e, "name")))
  }

  test("envelope: santa-wire/v1, the 6.0.7 stamp") {
    val c = v(B.Op).hcursor
    assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
    assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
