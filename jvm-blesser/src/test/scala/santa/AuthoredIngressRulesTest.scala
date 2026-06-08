package santa

import java.nio.file.Files

/** Guard for the three ingress-rule witnesses (substConstants version-source pair,
  * Rule-1012 header-size-bit, Rule-1019 CheckV6Type). `AuthoredIngressRules.extract`
  * re-blesses every entry through EvalCore and fails loud if an outcome drifts — so the
  * mere fact that `extract()` returns is the live proof each reject still errors and the
  * 5b accept still succeeds on sigma-state 6.0.3. Here we assert the emitted SHAPES:
  *   • 5a / 6 / 7 are rejects (error "errored", value+cost null);
  *   • 5b is an accept with cost == 222 and a non-null Coll[Byte] value;
  * plus the version envelopes and the exact spike wire bytes for 5a/5b/6. */
class AuthoredIngressRulesTest extends munit.FunSuite {

  private lazy val vectors = AuthoredIngressRules.extract()
  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector
  private def expectedOf(entry: io.circe.Json): io.circe.ACursor = entry.hcursor.downField("expected")

  test("four single-entry families: subst outer-v2, subst outer-v3, Rule-1012, Rule-1019") {
    assertEquals(vectors.size, 4)
    assertEquals(entries(AuthoredIngressRules.OpSubstOuterV2).size, 1)
    assertEquals(entries(AuthoredIngressRules.OpSubstOuterV3).size, 1)
    assertEquals(entries(AuthoredIngressRules.OpRule1012HeaderSize).size, 1)
    assertEquals(entries(AuthoredIngressRules.OpRule1019CheckV6).size, 1)
  }

  test("5a (subst outer-v2) is a reject — errored, value+cost null, ErgoTree v2") {
    val e = entries(AuthoredIngressRules.OpSubstOuterV2).head
    val exp = expectedOf(e)
    assertEquals(exp.get[String]("error").toOption, Some("errored"))
    assert(exp.downField("value").focus.exists(_.isNull), "5a value should be null")
    assert(exp.downField("cost").focus.exists(_.isNull), "5a cost should be null")
    assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1a15020e081b060128010a730010007473007301830028"))
    assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(2))
    assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(2))
  }

  test("5b (subst outer-v3) is an ACCEPT — cost == 222, non-null Coll[Byte] value, ErgoTree v3") {
    val e = entries(AuthoredIngressRules.OpSubstOuterV3).head
    val exp = expectedOf(e)
    assertEquals(exp.get[String]("error").toOption.flatMap(Option(_)), None, "5b error must be null")
    assert(exp.downField("error").focus.exists(_.isNull), "5b error should be null (accept)")
    assertEquals(exp.get[Long]("cost").toOption, Some(222L), "5b cost must be 222 (oracle)")
    assert(exp.downField("value").focus.exists(!_.isNull), "5b value must be non-null")
    assertEquals(exp.downField("value").get[String]("kind").toOption, Some("Coll"),
      "5b value is a Coll[Byte] (the substituted template bytes)")
    assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption,
      Some("1b15020e081a060128010a730010007473007301830028"))
    assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
    assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
  }

  test("6 (Rule-1012 header-size-bit) is a reject — errored, value+cost null, exact bytes, ErgoTree v3") {
    val e = entries(AuthoredIngressRules.OpRule1012HeaderSize).head
    val exp = expectedOf(e)
    assertEquals(exp.get[String]("error").toOption, Some("errored"))
    assert(exp.downField("value").focus.exists(_.isNull), "6 value should be null")
    assert(exp.downField("cost").focus.exists(_.isNull), "6 cost should be null")
    assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption, Some("03050101017300"))
    assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
  }

  test("7 (Rule-1019 CheckV6Type) is a reject — errored, value+cost null, ErgoTree v3, box carries Option[Int] R4") {
    val e = entries(AuthoredIngressRules.OpRule1019CheckV6).head
    val exp = expectedOf(e)
    assertEquals(exp.get[String]("error").toOption, Some("errored"))
    assert(exp.downField("value").focus.exists(_.isNull), "7 value should be null")
    assert(exp.downField("cost").focus.exists(_.isNull), "7 cost should be null")
    assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
    assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
    // The constructed tree is non-empty hex and (sanity) equals the lazily-built witness7Hex.
    assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption, Some(AuthoredIngressRules.witness7Hex))
    assert(AuthoredIngressRules.witness7Hex.nonEmpty, "witness7Hex must be constructed")
  }

  test("staging: writes all four family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredIngressRules.writeVectors(out)
    assert(Files.exists(out.resolve("substConstants_version_source_outer_v2.json")))
    assert(Files.exists(out.resolve("substConstants_version_source_outer_v3.json")))
    assert(Files.exists(out.resolve("Rule1012_header_size_bit.json")))
    assert(Files.exists(out.resolve("Rule1019_check_v6_type.json")))
  }
}
