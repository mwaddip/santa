package santa

import io.circe.Json

/** Anchors the receiver-kind WIRE family: seven accepts, all controls. `extract()` already fails
  * loud if any does not round-trip to itself; this pins that the family stays ALL-accept (a reject
  * appearing here would mean the refusal had moved earlier than eval) and that each row says it is
  * a control rather than evidence. */
class AuthoredWireReceiverKindTest extends munit.FunSuite {
  private val B = AuthoredWireReceiverKind
  private lazy val vectors = B.extract()
  private def entries: List[Json] = vectors(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))

  test("seven rows, every one an ACCEPT — the family is controls by design") {
    assertEquals(entries.size, 7)
    entries.foreach { e =>
      assert(e.hcursor.get[String]("error").toOption.isEmpty,
        s"${str(e, "name")}: a reject here would mean the refusal moved earlier than eval")
      assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty, str(e, "name"))
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("ErgoTree"))
      assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      assert(str(e, "description").contains("CONTROL"), s"${str(e, "name")}: must say it is a control")
    }
  }

  test("the trees are the probe's own, verbatim") {
    ReceiverKindTrees.wrappedTrees.foreach { case (n, tree) =>
      assert(entries.exists(e => str(e, "bytes_hex") == tree), s"row $n's tree must appear verbatim")
    }
    assertEquals(entries.map(e => str(e, "bytes_hex")).distinct.size, 7)
  }

  test("the spend control is present and is row 1") {
    val e = entries.find(x => str(x, "bytes_hex") ==
      ReceiverKindTrees.wrappedFor(ReceiverKindTrees.SpendControlRow)).getOrElse(fail("row 1"))
    assert(str(e, "description").contains("reduces to `SigmaProp(true)`"))
  }

  test("envelope: santa-wire/v1, the 6.0.7 stamp") {
    val c = vectors(B.Op).hcursor
    assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
    assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
