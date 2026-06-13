package santa

import java.nio.file.Files

/** Guard for the ContextExtension key-domain witnesses (ergots f5-batch6 Ask 20). Load-bearing facts:
  *  - key 0x80 (128) PRESENT in the top-level extension -> `errored` (toSigmaContext crash) — the
  *    consensus-fork divergence (ergots/sigma-rust accept unsigned keys);
  *  - key 0x7f (127) PRESENT -> accept (the inclusive boundary), tree evals to a value;
  *  - GetVar(0x80) with the key ABSENT -> Option None (the divergence is purely at construction);
  *  - schema santa-eval/v5; extension keys carried as the unsigned wire form (128 / 127). */
class AuthoredContextExtensionKeyTest extends munit.FunSuite {

  private lazy val env = AuthoredContextExtensionKey.extract()
  private lazy val envelope = env(AuthoredContextExtensionKey.Op)
  private lazy val entries: Vector[io.circe.Json] =
    envelope.hcursor.downField("entries").values.get.toVector
  private def byName(name: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.contains(name)).getOrElse(fail(s"missing $name"))
  private def errorOf(name: String): Option[String] =
    byName(name).hcursor.downField("expected").get[String]("error").toOption
  private def valueKind(name: String): Option[String] =
    byName(name).hcursor.downField("expected").downField("value").get[String]("kind").toOption

  test("one family, three entries, schema santa-eval/v5") {
    assertEquals(env.size, 1)
    assertEquals(entries.size, 3)
    assertEquals(envelope.hcursor.get[String]("schema").toOption, Some("santa-eval/v5"))
  }

  test("0x80 present -> errored (the divergence), value/cost null") {
    val e = byName("key-0x80-present-errored#0")
    assertEquals(errorOf("key-0x80-present-errored#0"), Some("errored"))
    assert(e.hcursor.downField("expected").downField("value").focus.exists(_.isNull))
    assert(e.hcursor.downField("expected").downField("cost").focus.exists(_.isNull))
  }

  test("0x7f present -> accept (a real value, not errored)") {
    assertEquals(errorOf("key-0x7f-present-accept#1"), None)
    assertEquals(valueKind("key-0x7f-present-accept#1"), Some("SigmaProp"))
  }

  test("isolation: GetVar(0x80) with key absent -> Option None (accept, no crash)") {
    assertEquals(errorOf("getvar-0x80-absent-none#2"), None)
    assertEquals(valueKind("getvar-0x80-absent-none#2"), Some("Option"))
    assert(byName("getvar-0x80-absent-none#2").hcursor
      .downField("expected").downField("value").downField("value").focus.exists(_.isNull),
      "Option value must be null (None)")
  }

  test("extension keys are the unsigned wire form (128 / 127); isolation is empty") {
    assert(byName("key-0x80-present-errored#0").hcursor.downField("extension").downField("128").focus.isDefined)
    assert(byName("key-0x7f-present-accept#1").hcursor.downField("extension").downField("127").focus.isDefined)
    assertEquals(byName("getvar-0x80-absent-none#2").hcursor.downField("extension").keys.map(_.toList), Some(Nil))
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
    AuthoredContextExtensionKey.writeVectors(out)
    assert(Files.exists(out.resolve("Context.extension_key_domain.json")))
  }
}
