package santa

import io.circe.Json

/** Anchors the wrong-arity wire vectors. extract() re-derives every round-trip through
  * WireCanonicalize and fails loud if the JVM does not return an entry's own bytes; this pins
  * that sigmastate's MethodCall / PropertyCall parse checks no arity. A failure means the
  * parser gained an arity gate, which would be a consensus change. */
class AuthoredWireMethodCallArityTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireMethodCallArity.extract()

  private def entries: List[Json] =
    vectors(AuthoredWireMethodCallArity.Op).hcursor
      .downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, field: String): String =
    e.hcursor.get[String](field).toOption.getOrElse(fail(field))

  test("four entries: three wrong arities and the legal-arity control") {
    assertEquals(entries.size, 4)
    assertEquals(entries.map(str(_, "name")), List(
      "method-call-arity-user-defined-invoke-extra-arg#0",
      "method-call-arity-reflective-extra-arg#1",
      "method-call-arity-property-call-of-arg-taking-method#2",
      "method-call-arity-legal-arity-control#3"))
  }

  test("the trees are byte-exact and v3 size-flagged, unsegregated") {
    assertEquals(entries.map(str(_, "bytes_hex")), List(
      "0b10d801d601dc04090402020404040808d3",       // dc 0409: SInt method 9, TWO args
      "0b14d801d601dc0c1a100202040304040400040e08d3", // dc 0c1a: SCollection method 26, THREE args
      "0b0dd801d601db0c1a1002020408d3",              // db 0c1a: the same method as a PropertyCall
      "0b0ed801d601dc0409040201040408d3"))           // dc 0409 with ONE arg — the control
    entries.foreach { e =>
      // header 0x0b = tree version 3 + size bit, no constant segregation
      assert(str(e, "bytes_hex").startsWith("0b"), s"${str(e, "name")}: v3 sized, unsegregated")
    }
  }

  test("the surplus-argument row and its control differ only in the argument list") {
    val Seq(extra, _, _, control) = entries.map(str(_, "bytes_hex"))
    // both are `d801 d601 dc0409 0402 <n> <args> 08d3`; the control takes one argument, the row two
    assert(extra.contains("dc0409") && control.contains("dc0409"), "same method, SInt 9")
    assert(extra.contains("0402" + "02" + "0404" + "0408"), s"two arguments ($extra)")
    assert(control.contains("0402" + "01" + "0404"), s"one argument ($control)")
  }

  test("every entry is an accept arm with the v6 context and the authored source") {
    val env = vectors(AuthoredWireMethodCallArity.Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-wire/v1"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    entries.foreach { e =>
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("ErgoTree"), str(e, "name"))
      assertEquals(e.hcursor.get[String]("source").toOption, Some(AuthoredWireMethodCallArity.Source))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
      assert(e.hcursor.get[String]("error").toOption.isEmpty, s"${str(e, "name")}: accept arm")
      assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty,
        s"${str(e, "name")}: identity round-trip")
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireMethodCallArity.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${AuthoredWireMethodCallArity.Op}.json")))
  }
}
