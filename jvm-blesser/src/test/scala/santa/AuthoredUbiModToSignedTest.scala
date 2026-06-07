package santa

class AuthoredUbiModToSignedTest extends munit.FunSuite {
  private lazy val vectors = AuthoredUbiModToSigned.extract()

  /** Lookup map from entry name → entry JSON for the accept arm. */
  private lazy val byName: Map[String, io.circe.Json] =
    vectors(AuthoredUbiModToSigned.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap

  test("two ops: accept arm + reject arm, expected counts") {
    val accepts = vectors(AuthoredUbiModToSigned.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    val rejects = vectors(AuthoredUbiModToSigned.OpReject).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    assertEquals(accepts.size, 5)
    assertEquals(rejects.size, 2)
  }

  test("well-formedness: accept entries have non-null value+cost, null error") {
    val accepts = vectors(AuthoredUbiModToSigned.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    accepts.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse("?")
      val value = e.hcursor.downField("expected").downField("value").focus.get
      val cost  = e.hcursor.downField("expected").get[Long]("cost").toOption
      val error = e.hcursor.downField("expected").downField("error").focus.get
      assert(!value.isNull,  s"$name: value must not be null")
      assert(cost.isDefined, s"$name: cost must be present")
      assert(error.isNull,   s"$name: error must be null")
    }
  }

  test("well-formedness: reject entries have null value+cost, non-null error") {
    val rejects = vectors(AuthoredUbiModToSigned.OpReject).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    rejects.foreach { e =>
      val name  = e.hcursor.get[String]("name").toOption.getOrElse("?")
      val value = e.hcursor.downField("expected").downField("value").focus.get
      val cost  = e.hcursor.downField("expected").downField("cost").focus.get
      val error = e.hcursor.downField("expected").get[String]("error").toOption
      assert(value.isNull,  s"$name: value must be null")
      assert(cost.isNull,   s"$name: cost must be null")
      assertEquals(error, Some("errored"), s"$name: error must be 'errored'")
    }
  }

  // Post-bless anchors — a re-bless that changes these is a cost-model or
  // value change: INVESTIGATE, not blindly accept.
  test("ANCHOR: mod#basic value = UnsignedBigInt '2'") {
    val entry = byName("mod#basic")
    val kind  = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    assertEquals(kind, "UnsignedBigInt")
    assertEquals(value, "2")
  }

  test("ANCHOR: mod#wrap value = UnsignedBigInt '1' (2^255 mod 7)") {
    val entry = byName("mod#wrap")
    val kind  = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    val expect = java.math.BigInteger.ONE.shiftLeft(255).mod(new java.math.BigInteger("7")).toString
    assertEquals(kind, "UnsignedBigInt")
    assertEquals(value, expect, "mod#wrap: 2^255 mod 7 must equal '1'")
  }

  test("ANCHOR: mod#m-gt-a value = UnsignedBigInt '5'") {
    val entry = byName("mod#m-gt-a")
    val kind  = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    assertEquals(kind, "UnsignedBigInt")
    assertEquals(value, "5")
  }

  test("ANCHOR: toSigned#small value = BigInt '17'") {
    val entry = byName("toSigned#small")
    val kind  = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    assertEquals(kind, "BigInt")
    assertEquals(value, "17")
  }

  test("ANCHOR: toSigned#max-ok value = BigInt decimal string of 2^255 - 1") {
    val entry  = byName("toSigned#max-ok")
    val kind   = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value  = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    val expect = java.math.BigInteger.ONE.shiftLeft(255).subtract(java.math.BigInteger.ONE).toString
    assertEquals(kind, "BigInt")
    assertEquals(value, expect, "toSigned#max-ok value must be decimal string of 2^255-1")
  }

  // Oracle-blessed cost decomposition:
  // method FixedCost + MethodCall dispatch JitCost(4) + ConstantPlaceholder JitCost(1) per segregated constant:
  // mod = 20+4+2×1 = 26; toSigned = 10+4+1 = 15.
  test("ANCHOR: mod entries cost == 26, toSigned entries cost == 15") {
    def cost(name: String): Long =
      byName(name).hcursor.downField("expected").get[Long]("cost").toOption
        .getOrElse(sys.error(s"$name: cost missing"))
    assertEquals(cost("mod#basic"),  26L, "mod#basic cost")
    assertEquals(cost("mod#wrap"),   26L, "mod#wrap cost")
    assertEquals(cost("mod#m-gt-a"), 26L, "mod#m-gt-a cost")
    assertEquals(cost("toSigned#small"),  15L, "toSigned#small cost")
    assertEquals(cost("toSigned#max-ok"), 15L, "toSigned#max-ok cost")
  }

  // 7 entries, all with unique (receiver,method,arg) → 7 distinct trees.
  test("all 7 entries carry unique tree hex (copy-paste/wrong-tree mixup guard)") {
    val allEntries =
      vectors(AuthoredUbiModToSigned.Op).hcursor
        .downField("entries").as[Seq[io.circe.Json]].toOption.get ++
      vectors(AuthoredUbiModToSigned.OpReject).hcursor
        .downField("entries").as[Seq[io.circe.Json]].toOption.get
    val trees = allEntries
      .map(_.hcursor.get[String]("tree_bytes_hex").toOption.get)
      .distinct
    assertEquals(trees.size, 7, s"expected 7 distinct trees, got ${trees.size}: ${trees.mkString(", ")}")
  }

  // Stages TWO vector files: accepts + domain rejects — both must land in vectors/.
  test("staging: writes both vector files (accepts + domain rejects)") {
    val outDir = java.nio.file.Paths.get("target", "authored-staging")
    AuthoredUbiModToSigned.writeVectors(vectors, outDir)
    val files = java.nio.file.Files.list(outDir).toArray.map(_.toString)
    assert(
      files.exists(_.endsWith("UnsignedBigInt.mod_toSigned.json")),
      s"expected UnsignedBigInt.mod_toSigned.json in $outDir, got: ${files.mkString(", ")}")
    assert(
      files.exists(_.endsWith("UnsignedBigInt.mod_toSigned_domain_rejects.json")),
      s"expected UnsignedBigInt.mod_toSigned_domain_rejects.json in $outDir, got: ${files.mkString(", ")}")
  }

  // Reject-class guard: EvalCore.evalApplied must return Left for each reject entry.
  // Note: errClass wraps the underlying ArithmeticException inside InvocationTargetException
  // (the JVM reflection layer used by sigma's eval machinery), so the Left prefix is
  // "InvocationTargetException: " rather than the raw "ArithmeticException: ..." cause.
  // The raw causes are: mod#div-by-zero → "ArithmeticException: BigInteger: modulus not positive";
  //                     toSigned#ge-2^255 → "ArithmeticException: BigInteger out of 256 bit range".
  test("reject entries error via EvalCore.evalApplied (InvocationTargetException wrapping ArithmeticException)") {
    import io.circe.Json
    val V3 = AuthoredUbiModToSigned.V3
    val dummyInput = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
    val rejects = vectors(AuthoredUbiModToSigned.OpReject).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    rejects.foreach { e =>
      val name    = e.hcursor.get[String]("name").toOption.get
      val treeHex = e.hcursor.get[String]("tree_bytes_hex").toOption.get
      val (_, result) = EvalCore.evalApplied(treeHex, dummyInput, V3)
      result match {
        case Left(msg) =>
          assert(msg.startsWith("InvocationTargetException:"),
            s"$name: expected Left starting with 'InvocationTargetException:', got: '$msg'")
        case Right(_) =>
          fail(s"$name: expected Left (eval-fail), got Right (success)")
      }
    }
  }
}
