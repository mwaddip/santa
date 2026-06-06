package santa

import scorex.util.encode.Base16

import sigma.ast.UnparsedErgoTree

/** Guard + premise-verification + smoke test for the authored adversarial
  * Box.getReg-adjacent MethodCall reject vectors (P7a-4).
  *
  * Premises verified EMPIRICALLY here (the header comment of
  * [[AuthoredGetRegAdversarial]] carries the source-level verification):
  *   (a) MethodCall 99:7 ("getRegV5") deserializes under EVERY header version 0..3 and
  *       ALWAYS throws when evaluated on the live path (v5 and v6 activations alike);
  *       on a dead branch the tree ACCEPTs (lazy If never evaluates the node).
  *   (b) MethodCall 99:19 (v6-only "getReg") inside a version-2-headed tree fails
  *       method-id validation at DESERIALIZE: the parse returns Left(UnparsedErgoTree)
  *       carrying the ValidationException (deferred throw — soft-fork tolerance), and
  *       any use of the tree errors. Flipping the header version bits back to 3
  *       restores the committed P7a-2 accept tree byte-for-byte.
  */
class AuthoredGetRegAdversarialTest extends munit.FunSuite {

  lazy val vectors: Map[String, io.circe.Json] = AuthoredGetRegAdversarial.extract()
  lazy val env: io.circe.Json                  = vectors(AuthoredGetRegAdversarial.Op)

  /** The committed P7a-2 dynamic-index Long-tree baseline (AuthoredGetRegDynamicTest). */
  private val P7a2LongTreeHex = "1b0b00dc6313a701e4e3010405"

  private def intJson(v: Int): io.circe.Json =
    io.circe.Json.obj("kind" -> io.circe.Json.fromString("Int"), "value" -> io.circe.Json.fromInt(v))

  private def entries: List[io.circe.Json] =
    env.hcursor.downField("entries").as[List[io.circe.Json]]
      .fold(e => fail(s"entries missing/invalid: $e"), identity)

  private def entryByName(n: String): io.circe.Json =
    entries.find(_.hcursor.get[String]("name").toOption.exists(_.startsWith(n)))
      .getOrElse(fail(s"no entry named '$n'"))

  test("envelope shape: schema=v2, op, source, 3 entries") {
    assertEquals(env.hcursor.get[String]("schema").toOption,     Some("santa-eval/v2"))
    assertEquals(env.hcursor.get[String]("op").toOption,         Some(AuthoredGetRegAdversarial.Op))
    assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.3"))
    assertEquals(env.hcursor.get[String]("source").toOption,     Some(AuthoredGetRegAdversarial.Source))
    val names = entries.flatMap(_.hcursor.get[String]("name").toOption)
    assertEquals(names, List(
      "getRegV5-live-reject#0", "getRegV5-dead-branch-accept#1", "getReg-v6-method-in-v2-tree-reject#2"))
  }

  test("arm live-reject: value null, cost null, error errored; v3/v3") {
    val ec = entryByName("getRegV5-live-reject#0").hcursor
    assertEquals(ec.downField("expected").downField("value").focus.map(_.noSpaces), Some("null"))
    assertEquals(ec.downField("expected").downField("cost").focus.map(_.noSpaces),  Some("null"))
    assertEquals(ec.downField("expected").get[String]("error").toOption, Some("errored"))
    assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(3))
    assertEquals(ec.downField("version").get[Int]("ergoTree").toOption,  Some(3))
  }

  test("arm dead-branch-accept: Boolean true, cost > 0, error null; v3/v3") {
    val ec = entryByName("getRegV5-dead-branch-accept#1").hcursor
    val valField = ec.downField("expected").downField("value")
    assertEquals(valField.get[String]("kind").toOption,   Some("Boolean"))
    assertEquals(valField.get[Boolean]("value").toOption, Some(true))
    val cost = ec.downField("expected").get[Long]("cost").toOption.getOrElse(-1L)
    assert(cost > 0, s"cost must be positive, got $cost")
    assertEquals(ec.downField("expected").downField("error").focus.map(_.noSpaces), Some("null"))
    assertEquals(ec.downField("version").get[Int]("ergoTree").toOption, Some(3))
  }

  test("arm v6-method-in-v2-tree: reject shape with version {activated:3, ergoTree:2}") {
    val ec = entryByName("getReg-v6-method-in-v2-tree-reject#2").hcursor
    assertEquals(ec.downField("expected").downField("value").focus.map(_.noSpaces), Some("null"))
    assertEquals(ec.downField("expected").downField("cost").focus.map(_.noSpaces),  Some("null"))
    assertEquals(ec.downField("expected").get[String]("error").toOption, Some("errored"))
    assertEquals(ec.downField("version").get[Int]("activated").toOption, Some(3))
    assertEquals(ec.downField("version").get[Int]("ergoTree").toOption,  Some(2))
  }

  // ── premise (a): 99:7 deserializes at EVERY header version ────────────────────────────

  test("premise: MethodCall 99:7 deserializes under header versions 0..3") {
    (0 to 3).foreach { v =>
      val hex  = AuthoredGetRegAdversarial.serializeAt(v.toByte, AuthoredGetRegAdversarial.getRegV5Call)
      val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(hex).get)
      assert(tree.root.isRight, s"99:7 tree with header v$v must parse (Right root), got Left: ${tree.root}")
      assertEquals(tree.version.toInt, v, s"parsed tree version for header v$v")
    }
  }

  // ── premise (a): 99:7 ALWAYS throws when evaluated on the live path ───────────────────

  test("premise: live 99:7 eval throws under (act=3,tree=3), (act=2,tree=2), (act=3,tree=2)") {
    val liveV3 = AuthoredGetRegAdversarial.serializeAt(3.toByte, AuthoredGetRegAdversarial.getRegV5Call)
    val liveV2 = AuthoredGetRegAdversarial.serializeAt(2.toByte, AuthoredGetRegAdversarial.getRegV5Call)
    val cases = Seq(("act=3 tree=3", liveV3, 3.toByte), ("act=2 tree=2", liveV2, 2.toByte),
                    ("act=3 tree=2", liveV2, 3.toByte))
    cases.foreach { case (label, hex, act) =>
      val (_, outcome) = EvalCore.evalApplied(hex, intJson(4), act)
      outcome match {
        case Left(err) =>
          assert(err.contains("getRegV5") || err.contains("NoSuchMethod"),
            s"$label: expected the reflection-lookup failure, got: $err")
          println(s"  [premise] live 99:7 $label → $err")
        case Right(vc) => fail(s"$label: MAJOR — 99:7 evaluated successfully: $vc — premise wrong")
      }
    }
  }

  // ── premise (b): 99:19 in a v2-headed tree fails method-id validation at parse ────────

  test("premise: 99:19-in-v2 parses to Left(UnparsedErgoTree) carrying the ValidationException") {
    val v2Hex = AuthoredGetRegAdversarial.serializeAt(
      AuthoredGetRegAdversarial.V2, AuthoredGetRegAdversarial.getRegV6Call)
    val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(v2Hex).get)
    assert(tree.root.isLeft, s"v2-headed 99:19 tree must NOT parse, got Right: ${tree.root}")
    val UnparsedErgoTree(_, ve) = tree.root.left.get
    println(s"  [premise] 99:19-in-v2 deserialize → ValidationException: ${ve.getMessage}")

    // Control: restoring the header version bits to 3 yields the committed P7a-2 accept
    // tree, which parses fine — the ONLY adversarial difference is the header byte.
    val restored = sigma.santa.LenientErgoTree.deserialize(Base16.decode("1b" + v2Hex.drop(2)).get)
    assert(restored.root.isRight, "header-restored (v3) tree must parse")
  }

  // ── bytes cross-reference: serializer-emitted v2 tree == committed v3 tree ± header ───

  test("bytes: v3 99:19 serialization matches the committed P7a-2 baseline; v2 differs only in the header byte") {
    val v3Hex = AuthoredGetRegAdversarial.serializeAt(
      AuthoredGetRegAdversarial.V3, AuthoredGetRegAdversarial.getRegV6Call)
    assertEquals(v3Hex, P7a2LongTreeHex, "v3 serialization drifted from the committed P7a-2 Long-tree baseline")
    val v2Hex = AuthoredGetRegAdversarial.serializeAt(
      AuthoredGetRegAdversarial.V2, AuthoredGetRegAdversarial.getRegV6Call)
    assertEquals(v2Hex, "1a" + P7a2LongTreeHex.drop(2),
      "v2 bytes must equal the committed v3 tree with header 0x1b → 0x1a (version bits 3 → 2)")
    val committed = entryByName("getReg-v6-method-in-v2-tree-reject#2")
      .hcursor.get[String]("tree_bytes_hex").toOption
    assertEquals(committed, Some(v2Hex), "vector entry must carry the v2-headed bytes")
  }

  // ── regression baseline: exact blessed value/cost/tree, locked after the first observed
  //    run. A change means the tree shape or the JVM's gating/eval moved — investigate,
  //    don't blindly re-bless. Observed (sigma-state 6.0.3):
  //      live 99:7  → NoSuchMethodException: sigma.Box.getRegV5(int)  → errored
  //      dead 99:7  → true / cost 12 (lazy If never evaluates the node)
  //      99:19-in-v2 → ValidationException rule 1011 (CheckAndGetMethod, SBoxMethods/19)
  //                    at deserialize, deferred via UnparsedErgoTree → errored
  private val baseline: Seq[(String, String, String)] = Seq(
    // name, expected(noSpaces), tree_bytes_hex
    ("getRegV5-live-reject#0",
      """{"value":null,"cost":null,"error":"errored"}""",
      "1b0a00dc6307a701e4e30104"),
    ("getRegV5-dead-branch-accept#1",
      """{"value":{"kind":"Boolean","value":true},"cost":12,"error":null}""",
      "1b1402010101019573007301e6dc6307a701e4e30104"),
    ("getReg-v6-method-in-v2-tree-reject#2",
      """{"value":null,"cost":null,"error":"errored"}""",
      "1a0b00dc6313a701e4e3010405"))

  test("blessed value/cost/tree match the recorded baseline") {
    baseline.foreach { case (name, expected, hex) =>
      val ec = entryByName(name).hcursor
      assertEquals(ec.downField("expected").focus.map(_.noSpaces), Some(expected), s"$name expected drifted")
      assertEquals(ec.get[String]("tree_bytes_hex").toOption, Some(hex), s"$name tree drifted")
    }
  }

  test("print oracle outputs") {
    val sb = new StringBuilder("\n========== Box.getReg adversarial oracle outputs ==========\n")
    entries.foreach { e =>
      val c = e.hcursor
      val n   = c.get[String]("name").getOrElse("?")
      val exp = c.downField("expected").focus.map(_.noSpaces).getOrElse("?")
      val hex = c.get[String]("tree_bytes_hex").getOrElse("?")
      sb.append(s"  $n: $exp\n      tree=$hex\n")
    }
    sb.append("============================================================\n")
    println(sb.toString)
  }

  test("write staging + the committed vector") {
    val stagingDir = java.nio.file.Paths.get("target", "get-reg-adversarial-vectors")
    AuthoredGetRegAdversarial.writeVectors(vectors, stagingDir)
    assert(java.nio.file.Files.exists(
      stagingDir.resolve(SpecExtract.slug(AuthoredGetRegAdversarial.Op) + ".json")))

    val outDir = java.nio.file.Paths.get("..", "vectors", "eval", "v6", "authored")
    java.nio.file.Files.createDirectories(outDir)
    java.nio.file.Files.write(outDir.resolve("Box.getReg_adversarial.json"),
      env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    assert(java.nio.file.Files.exists(outDir.resolve("Box.getReg_adversarial.json")))
  }
}
