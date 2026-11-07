package santa

import io.circe.Json

/** Anchors the explicit-type-argument wire vectors. extract() re-derives every verdict through
  * WireCanonicalize and fails loud on a non-identity round-trip; this pins the trailing type byte
  * in each tree's bytes, and the two reference trees against the shapes the sigma-rust session
  * measured. A failure means sigmastate changed where it writes an explicit type argument, or
  * the blesser built a different tree than the entry describes. */
class AuthoredWireExplicitTypeArgsTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireExplicitTypeArgs.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, field: String): String =
    e.hcursor.get[String](field).toOption.getOrElse(fail(field))

  /** The six methods with explicitTypeArgs in 6.0.7, in the blesser's order. */
  private val Methods =
    List("none", "some", "deserializeTo", "fromBigEndianBytes", "getReg", "getVarFromInput")

  test("every op carries one entry per method with explicitTypeArgs, in order") {
    assertEquals(vectors.keySet, Set(AuthoredWireExplicitTypeArgs.OpErgoTree,
      AuthoredWireExplicitTypeArgs.OpBox, AuthoredWireExplicitTypeArgs.OpTransaction))
    Seq(AuthoredWireExplicitTypeArgs.OpErgoTree -> "tree",
        AuthoredWireExplicitTypeArgs.OpBox -> "box",
        AuthoredWireExplicitTypeArgs.OpTransaction -> "tx").foreach { case (op, suffix) =>
      val es = entries(op)
      assertEquals(es.size, Methods.size, op)
      es.zip(Methods).zipWithIndex.foreach { case ((e, m), i) =>
        assertEquals(str(e, "name"), s"explicit-type-args-$m-$suffix#$i", op)
      }
    }
  }

  /** Each tree's bytes, v3 sized+segregated. `none` and `some` are the two the sigma-rust
    * session measured on the fork; a mismatch there means the blesser built a different tree
    * than the report. The `04` after each call's arguments is T = SInt — the byte an
    * implementation without explicit-type support drops. */
  private val ExpectedTrees = List(
    "1b0800d1e6db6a0add04",                             // none: db 6a 0a dd |04
    "1b0d01040ad1e6dc6a09dd01730004",                   // some: dc 6a 09 dd 01 7300 |04
    "1b15020e0401020304040ed193dc6a04dd017300047301",   // deserializeTo: dc 6a 04 dd 01 7300 |04
    "1b15020e0401020304040ed193dc6a05dd017300047301",   // fromBigEndianBytes: ... 05 ... |04
    "1b0d010408d1e6dc6313a701730004",                   // getReg: dc 63 13 a7(SELF) 01 7300 |04
    "1b110203000201d1e6dc650cfe027300730104")           // getVarFromInput: dc 65 0c fe 02 7300 7301 |04

  test("the six trees are byte-exact, each carrying T = SInt after its arguments") {
    val es = entries(AuthoredWireExplicitTypeArgs.OpErgoTree)
    es.zip(ExpectedTrees).zip(Methods).foreach { case ((e, want), m) =>
      assertEquals(str(e, "bytes_hex"), want, s"$m tree")
    }
  }

  test("the type byte follows the ARGUMENTS, not the tree") {
    val es = entries(AuthoredWireExplicitTypeArgs.OpErgoTree).map(str(_, "bytes_hex"))
    // A call that is the tree's last node ends with the type byte...
    assert(es.head.endsWith("04"), "none: PropertyCall is last, type byte ends the tree")
    assert(es(1).endsWith("04"), "some: MethodCall is last, type byte ends the tree")
    // ...but deserializeTo's EQ has a second operand after the call, so the type byte sits
    // mid-body: the position is "after this call's args", never "at the end of the tree".
    assert(es(2).endsWith("7301") && es(2).contains("7300047301"),
      s"deserializeTo: type byte precedes the EQ's second operand (${es(2)})")
    // getVarFromInput takes TWO arguments; the single type byte follows both.
    assert(es(5).endsWith("7300730104"),
      s"getVarFromInput: one type byte after two placeholders (${es(5)})")
  }

  test("each tree appears verbatim inside its box and transaction entry") {
    val trees = entries(AuthoredWireExplicitTypeArgs.OpErgoTree).map(str(_, "bytes_hex"))
    Seq(AuthoredWireExplicitTypeArgs.OpBox, AuthoredWireExplicitTypeArgs.OpTransaction).foreach { op =>
      entries(op).zip(trees).foreach { case (e, treeHex) =>
        assert(str(e, "bytes_hex").contains(treeHex),
          s"${str(e, "name")}: must carry the tree $treeHex")
      }
    }
  }

  test("envelopes: santa-wire/v1, the node's v6 context (3, 3), authored source, 6.0.7 blessing") {
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"), op)
      assertEquals(c.get[String]("op").toOption, Some(op))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"), op)
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
        assertEquals(e.hcursor.get[String]("source").toOption, Some(AuthoredWireExplicitTypeArgs.Source))
        // every entry is an accept arm: no `error`, no `expected_bytes_hex`
        assert(e.hcursor.get[String]("error").toOption.isEmpty, s"${str(e, "name")}: accept arm")
        assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty,
          s"${str(e, "name")}: identity round-trip, no expected_bytes_hex")
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireExplicitTypeArgs.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
