package santa

import io.circe.Json

/** Anchors the header-EOF family. `extract()` re-derives every verdict through `WireCanonicalize`
  * and asserts each reject's exception CLASS, so this test pins the rest: that the accept control
  * really is the same tree the rejects are truncations OF (otherwise the pair proves nothing), and
  * that the family still carries BOTH failure classes. */
class AuthoredWireHeaderEofTest extends munit.FunSuite {
  private val B = AuthoredWireHeaderEof
  private lazy val vectors = B.extract()

  private def entries: List[Json] = vectors(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def one(frag: String): Json = entries.filter(e => str(e, "name").contains(frag)) match {
    case List(e) => e
    case other   => fail(s"want one entry matching '$frag', got ${other.map(str(_, "name"))}")
  }

  test("7 entries: 2 accept controls, 5 rejects") {
    assertEquals(vectors.keySet, Set(B.Op))
    assertEquals(entries.size, 7)
    assertEquals(entries.count(isReject), 5)
    assertEquals(entries.count(e => !isReject(e)), 2)
    entries.foreach { e =>
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("ErgoTree"), str(e, "name"))
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty,
        s"${str(e, "name")}: identity round-trip or reject, never a non-identity expected")
    }
  }

  test("the control is the same tree the body-truncation rows are cut from") {
    // If this drifts, the accept row stops being a control for those rejects and the family
    // quietly becomes "two unrelated trees, one of which parses".
    assert(B.CompleteV1.startsWith(str(one("truncated-body-v1"), "bytes_hex")),
      "the v1 truncation must be a PREFIX of the v1 control")
    assert(B.CompleteV3.startsWith(str(one("truncated-body-v3"), "bytes_hex")),
      "the v3 truncation must be a PREFIX of the v3 control")
    // the two controls differ in the header byte alone
    assertEquals(B.CompleteV1.drop(2), B.CompleteV3.drop(2))
    assertEquals(B.CompleteV1.take(2), "09")
    assertEquals(B.CompleteV3.take(2), "0b")
  }

  test("both failure classes are still represented — re-derived, not read off the prose") {
    // The whole argument for the fix shape rests on there being TWO of them. A family that
    // drifted to one class would still be green and would still be read as evidence. Counting
    // mentions in the descriptions does NOT test that (one row legitimately names both while
    // explaining why an allow-list fails), so re-parse each reject and take the class it actually
    // throws.
    val classes = entries.filter(isReject).map { e =>
      val tv = e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("ergoTree"))
      scala.util.Try(WireCanonicalize.canonicalize("ErgoTree", str(e, "bytes_hex"), 3.toByte, tv.toByte))
        .fold(t => Iterator.iterate(t: Throwable)(_.getCause).takeWhile(_ != null)
                     .map(_.getClass.getSimpleName).toList.last,
              out => fail(s"${str(e, "name")}: expected a reject, round-tripped to $out"))
    }
    assertEquals(classes.count(_ == "BufferUnderflowException"), 3, classes.toString)
    assertEquals(classes.count(_ == "ArrayIndexOutOfBoundsException"), 2, classes.toString)
    assertEquals(classes.distinct.size, 2, s"exactly two failure classes: ${classes.distinct}")
  }

  test("the probe's three trees are present verbatim, at the versions it asked for") {
    assertEquals(str(one("ends-at-nested-tree-header-v1"), "bytes_hex"), "09026301")
    assertEquals(str(one("ends-at-nested-tree-header-v3"), "bytes_hex"), "0b026301")
    assertEquals(str(one("ends-at-box-value-v1"), "bytes_hex"), "090163")
    def tv(frag: String): Int =
      one(frag).hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("ergoTree"))
    assertEquals(tv("ends-at-nested-tree-header-v1"), 1)
    assertEquals(tv("ends-at-nested-tree-header-v3"), 3)
    assertEquals(tv("ends-at-box-value-v1"), 1)
  }

  test("which rows claim to separate, and which are controls") {
    val sep = entries.filter(e => str(e, "description").contains("SEPARATING ROW")).map(e => str(e, "name"))
    assertEquals(sep.size, 2, s"exactly the two ends-at-nested-tree-header rows: $sep")
    assert(sep.forall(_.contains("ends-at-nested-tree-header")), sep.toString)
    // and the box-value row is explicitly NOT evidence
    assert(str(one("ends-at-box-value-v1"), "description").contains("CONTROL"))
  }

  test("envelope: santa-wire/v1, the 6.0.7 stamp") {
    val c = vectors(B.Op).hcursor
    assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
    assertEquals(c.get[String]("op").toOption, Some(B.Op))
    assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
