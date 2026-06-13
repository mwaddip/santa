package santa

import java.nio.file.Files

/** Guard for the SBox accessor METHOD-form (PropertyCall) pins (ergots f5-batch6 Ask 19).
  * Load-bearing facts:
  *  - costs 13/15/17/17/17/21 — ergots' node-total (envelope 4 + the method's costKind)
  *    PLUS ONE for the box receiver's ConstantPlaceholder visit (PropertyCall = op-form + 4);
  *  - the byte-basis trio (bytes/bytesWithoutRef/id) VALUE-matches the op-form
  *    Box.bytes_byte_basis twins (the method-form pins the same retained/canonical bases);
  *  - the trees are genuine PropertyCall(0xdb) on SBox(0x63), method ids 1..6 (NOT 0xdc). */
class AuthoredSBoxMethodFormTest extends munit.FunSuite {

  private lazy val vectors = AuthoredSBoxMethodForm.extract()
  private lazy val entries: Vector[io.circe.Json] =
    vectors(AuthoredSBoxMethodForm.Op).hcursor.downField("entries").values.get.toVector
  private def byName(name: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name)).getOrElse(fail(s"missing entry $name"))
  private def costOf(name: String): Long =
    byName(name).hcursor.downField("expected").get[Long]("cost").toOption.get
  private def treeOf(name: String): String =
    byName(name).hcursor.get[String]("tree_bytes_hex").toOption.get
  private def valueOf(name: String): String =
    byName(name).hcursor.downField("expected").downField("value").focus.get.noSpaces

  // (name, method id, expected full-tree cost)
  private val cases = Seq(
    ("value-99-1#0", 1, 13L),
    ("propositionBytes-99-2#1", 2, 15L),
    ("bytes-99-3-garbage-retained#2", 3, 17L),
    ("bytesWithoutRef-99-4-garbage-canonical#3", 4, 17L),
    ("id-99-5-garbage#4", 5, 17L),
    ("creationInfo-99-6#5", 6, 21L))

  test("one family, six method-form entries") {
    assertEquals(vectors.size, 1)
    assertEquals(entries.size, 6)
  }

  test("costs are the measured full-tree totals (ergots node-total + 1 CP visit)") {
    cases.foreach { case (name, _, c) => assertEquals(costOf(name), c, name) }
  }

  test("trees are PropertyCall(0xdb) on SBox(0x63), method ids 1..6 — not 0xdc") {
    cases.foreach { case (name, id, _) =>
      val t = treeOf(name)
      assert(t.endsWith(s"db630${id}7300"), s"$name must end with PropertyCall(db) SBox(63) method $id placeholder: …${t.takeRight(12)}")
    }
  }

  test("99:1 value control evaluates to the box value (Long 1000000)") {
    assertEquals(valueOf("value-99-1#0"), """{"kind":"Long","value":"1000000"}""")
  }

  test("byte-basis trio VALUE-matches the op-form Box.bytes_byte_basis twins") {
    val opForm = AuthoredBoxBytesBasis.extract()(AuthoredBoxBytesBasis.Op)
      .hcursor.downField("entries").values.get.toVector
    def opVal(name: String): String =
      opForm.find(_.hcursor.get[String]("name").toOption.contains(name)).getOrElse(fail(s"op-form $name"))
        .hcursor.downField("expected").downField("value").focus.get.noSpaces
    assertEquals(valueOf("bytes-99-3-garbage-retained#2"), opVal("bytes-garbage-retained#0"))
    assertEquals(valueOf("bytesWithoutRef-99-4-garbage-canonical#3"), opVal("bytesnoref-garbage-canonical#2"))
    assertEquals(valueOf("id-99-5-garbage#4"), opVal("id-garbage#4"))
  }

  test("version pinned at {activated 2, ergoTree 0}") {
    entries.foreach { e =>
      val v = e.hcursor.downField("version")
      assertEquals(v.get[Int]("activated").toOption, Some(2))
      assertEquals(v.get[Int]("ergoTree").toOption, Some(0))
    }
  }

  test("staging: writes the family file") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredSBoxMethodForm.writeVectors(out)
    assert(Files.exists(out.resolve("Box.accessor_method_form.json")))
  }
}
