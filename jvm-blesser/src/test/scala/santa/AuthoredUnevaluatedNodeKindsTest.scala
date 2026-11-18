package santa

import io.circe.Json

/** Anchors the unevaluated-node eval family. `extract()` already fails loud if the JVM evaluates
  * any of the thirteen; this pins the set itself (so a node kind cannot quietly leave the family),
  * the v3 size-flagged form of each tree, and the envelope's oracle stamp. */
class AuthoredUnevaluatedNodeKindsTest extends munit.FunSuite {
  private lazy val vectors = AuthoredUnevaluatedNodeKinds.extract()
  private def entries: List[Json] = vectors(AuthoredUnevaluatedNodeKinds.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))

  /** The thirteen, in the blesser's order. A reader registered for a node with no `eval` is what
    * puts a kind on this list; one leaving it means sigmastate grew an `eval`. */
  private val Slugs = List(
    "bit_shift_left", "bit_shift_right", "bit_shift_right_zeroed",
    "mod_q", "plus_mod_q", "minus_mod_q",
    "sigma_prop_is_proven", "tagged_variable", "create_avl_tree_four_value",
    "bit_or", "bit_and", "bit_xor", "bit_inversion")

  /** ergots' request (2026-11-18): the ModQ nodes over an operand of another type, appended. */
  private val OtherOperand = List("mod_q_on_boolean", "mod_q_on_int", "plus_mod_q_boolean_left",
    "plus_mod_q_int_right", "minus_mod_q_booleans")

  test("thirteen node kinds in order, then the one extra v0 encoding, then the other-operand ModQ nodes") {
    assertEquals(entries.size, 14 + 2 * OtherOperand.size)
    entries.take(14).zip(Slugs :+ "create_avl_tree_four_value_getvar").zipWithIndex.foreach {
      case ((e, slug), i) => assertEquals(str(e, "name"), s"$slug-errored#$i")
    }
    entries.drop(14).zip(OtherOperand.flatMap(s => List(s"$s-errored", s"$s-v0-errored"))).zipWithIndex.foreach {
      case ((e, name), i) => assertEquals(str(e, "name"), s"$name#${14 + i}")
    }
    assertEquals(UnevaluatedNodeTrees.nodes.map(_.slug), Slugs)
    assertEquals(UnevaluatedNodeTrees.extraEncodings.map(_.slug),
      List("create_avl_tree_four_value_getvar"))
    assertEquals(UnevaluatedNodeTrees.otherOperand.map(_.slug), OtherOperand)
  }

  test("the other-operand trees hold a Boolean or Int constant where ModQ declares a BigInt, and expect errored") {
    // EQ(<node>, BigInt 1) under BoolToSigmaProp: d1 93 <node> 06 01 01; ModQ e7, PlusModQ e8, MinusModQ e9
    val want = Map("mod_q_on_boolean" -> "d193e70101060101", "mod_q_on_int" -> "d193e70402060101",
      "plus_mod_q_boolean_left" -> "d193e80101060101060101", "plus_mod_q_int_right" -> "d193e80601010402060101",
      "minus_mod_q_booleans" -> "d193e9010101000601 01".replace(" ", ""))
    UnevaluatedNodeTrees.otherOperand.foreach(n => assertEquals(n.body, want(n.slug), n.slug))
    entries.drop(14).foreach { e =>
      assertEquals(e.hcursor.downField("expected").get[String]("error").toOption, Some("errored"), str(e, "name"))
    }
  }

  test("thirteen v3 size-flagged trees plus one v0 unsized; every row expects errored") {
    entries.foreach { e =>
      assertEquals(e.hcursor.downField("expected").get[String]("error").toOption, Some("errored"),
        str(e, "name"))
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
    }
    val (v3s, v0s) = entries.take(14).partition(
      _.hcursor.downField("version").get[Int]("ergoTree").toOption.contains(3))
    assertEquals(v3s.size, 13)
    assertEquals(v0s.size, 1)
    v3s.foreach { e =>
      val hex = str(e, "tree_bytes_hex")
      assert(hex.startsWith("0b"), s"${str(e, "name")}: v3 + size bit header, got ${hex.take(2)}")
    }
    v0s.foreach { e =>
      val hex = str(e, "tree_bytes_hex")
      assert(hex.startsWith("00"), s"${str(e, "name")}: v0 unsized header, got ${hex.take(2)}")
      assert(hex.contains("e30104"), s"${str(e, "name")}: must carry getVar[Int](1) — $hex")
    }
  }

  test("the one tree the JVM cannot write at v0 is marked, and only that one") {
    assertEquals(UnevaluatedNodeTrees.nodes.filterNot(_.v0Representable).map(_.slug),
      List("create_avl_tree_four_value"))
  }

  test("envelope: the current oracle's stamp, not SpecExtract's 6.0.3 default") {
    val c = vectors(AuthoredUnevaluatedNodeKinds.Op).hcursor
    assertEquals(c.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("staging: writes the family file") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredUnevaluatedNodeKinds.writeVectors(out)
    assert(java.nio.file.Files.exists(out.resolve("Unevaluated.node_kinds.json")))
  }
}
