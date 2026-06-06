package santa

import java.nio.file.Files

class AuthoredHeaderPropsTest extends munit.FunSuite {

  test("17 entries: 15 accessors + 2 timestamp ranges, all bless Right") {
    val env     = AuthoredHeaderProps.vectors(AuthoredHeaderProps.Op)
    val entries = env.hcursor.downField("entries").values.get.toVector
    assertEquals(entries.size, 17)
    entries.foreach { e =>
      val c = e.hcursor.downField("expected")
      assert(!c.downField("value").focus.get.isNull, s"unblessed: ${e.hcursor.get[String]("name")}")
      assert(!c.downField("cost").focus.get.isNull)
    }
  }

  test("timestamp u64-max blesses the SIGNED view Long(-1)") {
    val env     = AuthoredHeaderProps.vectors(AuthoredHeaderProps.Op)
    val entries = env.hcursor.downField("entries").values.get.toVector
    val u64 = entries.find(_.hcursor.get[String]("name").toOption.contains("h.timestamp#u64-max")).get
    val v   = u64.hcursor.downField("expected").downField("value").focus.get
    assertEquals(v.hcursor.get[String]("value").toOption, Some("-1"))
  }

  test("stage to target/authored-staging") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredHeaderProps.writeVectors(out)
    assert(Files.exists(out.resolve("Header.property_accessors.json")))
  }
}
