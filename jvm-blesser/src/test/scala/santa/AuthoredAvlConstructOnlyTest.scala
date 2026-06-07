package santa

import java.nio.file.Files

/** Guard for the construct-only AvlTree nodes (TreeLookup, CreateAvlTree).
  * Pins that both serialize but ALWAYS error at eval (notSupportedError costKind,
  * no eval override) — the basis for ergots' TreeLookup over-accept (Ask 4) and
  * the moot CreateAvlTree.keyLength question (B). */
class AuthoredAvlConstructOnlyTest extends munit.FunSuite {

  private lazy val vectors = AuthoredAvlConstructOnly.extract()
  private def entries(op: String): Vector[io.circe.Json] =
    vectors(op).hcursor.downField("entries").values.get.toVector

  test("two families: v5 (TreeLookup), v6 (TreeLookup + CreateAvlTree)") {
    assertEquals(vectors.size, 2)
    assertEquals(entries(AuthoredAvlConstructOnly.OpV5).size, 1)
    assertEquals(entries(AuthoredAvlConstructOnly.OpV6).size, 2)
  }

  test("all entries are rejects (errored), value+cost null") {
    (entries(AuthoredAvlConstructOnly.OpV5) ++ entries(AuthoredAvlConstructOnly.OpV6)).foreach { e =>
      val exp = e.hcursor.downField("expected")
      assertEquals(exp.get[String]("error").toOption, Some("errored"),
        s"${e.hcursor.get[String]("name").toOption.getOrElse("?")} must be errored")
      assert(exp.downField("value").focus.exists(_.isNull), "value must be null")
      assert(exp.downField("cost").focus.exists(_.isNull), "cost must be null")
    }
  }

  test("trees serialize (non-empty bytes) at their pinned versions") {
    (entries(AuthoredAvlConstructOnly.OpV5) ++ entries(AuthoredAvlConstructOnly.OpV6)).foreach { e =>
      val hex = e.hcursor.get[String]("tree_bytes_hex").toOption.get
      assert(hex.length > 10, "construct-only node must still serialize")
    }
  }

  test("staging: writes both family files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredAvlConstructOnly.writeVectors(out)
    assert(Files.exists(out.resolve("AvlTree.unsupported_eval_nodes.json")))
    assert(Files.exists(out.resolve("AvlTree.unsupported_eval_nodes_v6.json")))
  }
}
