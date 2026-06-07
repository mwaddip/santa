package santa

import java.nio.file.Files

/** Guard for the SOption-semantics pins (nonzero tag accepts as Some; pre-v3 Option
  * DATA constant rejected). Values locked from the JVM oracle/spike. */
class AuthoredOptionSemanticsTest extends munit.FunSuite {

  private lazy val vectors = AuthoredOptionSemantics.extract()
  private def only(op: String): io.circe.ACursor =
    vectors(op).hcursor.downField("entries").values.get.toVector.head.hcursor

  test("two single-entry families") {
    assertEquals(vectors.size, 2)
    assertEquals(vectors(AuthoredOptionSemantics.OpNonzeroTag).hcursor.downField("entries").values.get.size, 1)
    assertEquals(vectors(AuthoredOptionSemantics.OpPreV3Gate).hcursor.downField("entries").values.get.size, 1)
  }

  test("2a: Option tag 0x02 → Some(Int 5), cost 1, at v6 (3,3); bytes carry 2802") {
    val e = only(AuthoredOptionSemantics.OpNonzeroTag)
    val exp = e.downField("expected")
    assertEquals(exp.downField("value").get[String]("kind").toOption, Some("Option"))
    assertEquals(exp.downField("value").downField("value").get[Int]("value").toOption, Some(5))
    assertEquals(exp.get[Long]("cost").toOption, Some(1L))
    assertEquals(e.downField("version").get[Int]("activated").toOption, Some(3))
    assert(e.get[String]("tree_bytes_hex").toOption.exists(_.contains("2802")), "patched Option tag 02 present")
  }

  test("2c: pre-v3 Option DATA constant → errored, at v5 (2,2); header byte 1a") {
    val e = only(AuthoredOptionSemantics.OpPreV3Gate)
    assertEquals(e.downField("expected").get[String]("error").toOption, Some("errored"))
    assertEquals(e.downField("version").get[Int]("ergoTree").toOption, Some(2))
    assert(e.get[String]("tree_bytes_hex").toOption.exists(_.startsWith("1a")), "header patched to v2 (1a)")
  }

  test("staging: writes both family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredOptionSemantics.writeVectors(out)
    assert(Files.exists(out.resolve("SOption.nonzero_data_tag.json")))
    assert(Files.exists(out.resolve("SOption.pre_v3_data_constant.json")))
  }
}
