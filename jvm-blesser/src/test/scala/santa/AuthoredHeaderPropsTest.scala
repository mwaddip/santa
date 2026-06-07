package santa

import java.nio.file.Files

class AuthoredHeaderPropsTest extends munit.FunSuite {

  private lazy val vectors = AuthoredHeaderProps.extract()

  test("17 entries: 15 accessors + 2 timestamp ranges, all bless Right") {
    val env     = vectors(AuthoredHeaderProps.Op)
    val entries = env.hcursor.downField("entries").values.get.toVector
    assertEquals(entries.size, 17)
    entries.foreach { e =>
      val c = e.hcursor.downField("expected")
      assert(!c.downField("value").focus.get.isNull, s"unblessed: ${e.hcursor.get[String]("name")}")
      assert(!c.downField("cost").focus.get.isNull)
    }
  }

  test("timestamp u64-max blesses the SIGNED view Long(-1)") {
    val env     = vectors(AuthoredHeaderProps.Op)
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

  // post-bless anchors — a re-bless that changes these is a cost-model/value change to
  // INVESTIGATE, not blindly accept (flat 39 = GetVar+OptionGet+MethodCall fixed costs,
  // accessor-independent).
  test("anchors: flat cost 39, 15 distinct trees, per-accessor kinds, 2^53+1 exact") {
    val entries = vectors(AuthoredHeaderProps.Op)
      .hcursor.downField("entries").values.get.toVector
    entries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse("?")
      assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption, Some(39L), s"$name cost drifted") }
    assertEquals(entries.flatMap(_.hcursor.get[String]("tree_bytes_hex").toOption).distinct.size, 15,
      "15 distinct accessor trees (timestamp's reused 3x)")
    val kindOf = Map("h.version#nominal" -> "Byte", "h.timestamp#nominal" -> "Long",
      "h.nBits#nominal" -> "Long", "h.height#nominal" -> "Int", "h.stateRoot#nominal" -> "AvlTree",
      "h.minerPk#nominal" -> "GroupElement", "h.powOnetimePk#nominal" -> "GroupElement",
      "h.powDistance#nominal" -> "BigInt", "h.timestamp#u64-max" -> "Long")
    kindOf.foreach { case (name, kind) =>
      val e = entries.find(_.hcursor.get[String]("name").toOption.contains(name)).getOrElse(fail(s"no $name"))
      assertEquals(e.hcursor.downField("expected").downField("value").get[String]("kind").toOption, Some(kind), name) }
    val gt53 = entries.find(_.hcursor.get[String]("name").toOption.contains("h.timestamp#gt-2^53")).get
    assertEquals(gt53.hcursor.downField("expected").downField("value").get[String]("value").toOption,
      Some("9007199254740993"), "gt-2^53 must be exact — a double-rounding bless would emit ...92")
  }
}
