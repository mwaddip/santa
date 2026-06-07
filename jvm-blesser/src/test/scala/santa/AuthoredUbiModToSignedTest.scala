package santa

class AuthoredUbiModToSignedTest extends munit.FunSuite {
  private lazy val vectors = AuthoredUbiModToSigned.extract()

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
    val byName = vectors(AuthoredUbiModToSigned.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap
    val entry = byName("mod#basic")
    val kind  = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    assertEquals(kind, "UnsignedBigInt")
    assertEquals(value, "2")
  }

  test("ANCHOR: mod#m-gt-a value = UnsignedBigInt '5'") {
    val byName = vectors(AuthoredUbiModToSigned.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap
    val entry = byName("mod#m-gt-a")
    val kind  = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    assertEquals(kind, "UnsignedBigInt")
    assertEquals(value, "5")
  }

  test("ANCHOR: toSigned#small value = BigInt '17'") {
    val byName = vectors(AuthoredUbiModToSigned.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap
    val entry = byName("toSigned#small")
    val kind  = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    assertEquals(kind, "BigInt")
    assertEquals(value, "17")
  }

  test("ANCHOR: toSigned#max-ok value = BigInt decimal string of 2^255 - 1") {
    val byName = vectors(AuthoredUbiModToSigned.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap
    val entry  = byName("toSigned#max-ok")
    val kind   = entry.hcursor.downField("expected").downField("value").get[String]("kind").toOption.get
    val value  = entry.hcursor.downField("expected").downField("value").get[String]("value").toOption.get
    val expect = java.math.BigInteger.ONE.shiftLeft(255).subtract(java.math.BigInteger.ONE).toString
    assertEquals(kind, "BigInt")
    assertEquals(value, expect, "toSigned#max-ok value must be decimal string of 2^255-1")
  }

  // Oracle-blessed: ModMethodCall JitCost(20) + MethodCall dispatch overhead = 26;
  // ToSignedMethodCall JitCost(10) + dispatch = 15.
  test("ANCHOR: mod entries cost == 26, toSigned entries cost == 15") {
    val byName = vectors(AuthoredUbiModToSigned.Op).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
      .map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap
    def cost(name: String): Long =
      byName(name).hcursor.downField("expected").get[Long]("cost").toOption
        .getOrElse(sys.error(s"$name: cost missing"))
    assertEquals(cost("mod#basic"),  26L, "mod#basic cost")
    assertEquals(cost("mod#wrap"),   26L, "mod#wrap cost")
    assertEquals(cost("mod#m-gt-a"), 26L, "mod#m-gt-a cost")
    assertEquals(cost("toSigned#small"),  15L, "toSigned#small cost")
    assertEquals(cost("toSigned#max-ok"), 15L, "toSigned#max-ok cost")
  }

  test("ANCHOR: exactly 7 distinct tree hex strings (one mod tree per-entry; one toSigned tree per-entry, but the trees with same operands share hex)") {
    val allEntries =
      vectors(AuthoredUbiModToSigned.Op).hcursor
        .downField("entries").as[Seq[io.circe.Json]].toOption.get ++
      vectors(AuthoredUbiModToSigned.OpReject).hcursor
        .downField("entries").as[Seq[io.circe.Json]].toOption.get
    val trees = allEntries
      .map(_.hcursor.get[String]("tree_bytes_hex").toOption.get)
      .distinct
    // 4 mod entries each have distinct operands → up to 4 distinct mod trees
    // (mod#basic and mod#div-by-zero share receiver 17 but differ in arg, so differ;
    //  mod#wrap, mod#m-gt-a also distinct); toSigned entries share toSignedTree(pow255)
    // between toSigned#ge-2^255 reject and none of the accepts (toSigned#small and
    // toSigned#max-ok use different receivers) — in practice every entry has a unique tree.
    // Pin the total distinct count: 7 entries, all with unique (receiver,method,arg) → 7 trees.
    assertEquals(trees.size, 7, s"expected 7 distinct trees, got ${trees.size}: ${trees.mkString(", ")}")
  }

  test("staging written: file named UnsignedBigInt.mod_toSigned.json produced") {
    val outDir = java.nio.file.Paths.get("target", "authored-staging")
    AuthoredUbiModToSigned.writeVectors(vectors, outDir)
    val files = java.nio.file.Files.list(outDir).toArray.map(_.toString)
    assert(
      files.exists(_.endsWith("UnsignedBigInt.mod_toSigned.json")),
      s"expected UnsignedBigInt.mod_toSigned.json in $outDir, got: ${files.mkString(", ")}")
  }
}
