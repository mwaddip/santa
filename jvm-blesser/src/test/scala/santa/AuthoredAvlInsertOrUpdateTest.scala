package santa

/** TDD for the AvlTree.insertOrUpdate authored proof-carrying vectors (v6/authored).
  * Structure assertions run pre-bless; exact cost/hex anchors are pinned
  * AFTER the first bless (the AuthoredPowHitTest / AuthoredAvlTier2Test pattern). */
class AuthoredAvlInsertOrUpdateTest extends munit.FunSuite {

  private lazy val envelope = AuthoredAvlInsertOrUpdate.extract()

  private lazy val entries: Seq[io.circe.Json] =
    envelope.hcursor.downField("entries").as[Seq[io.circe.Json]].toOption.get

  private lazy val byName: Map[String, io.circe.Json] =
    entries.map(e => e.hcursor.get[String]("name").toOption.get -> e).toMap

  // ── well-formedness ──────────────────────────────────────────────────────────

  test("4 entries, one op") {
    assertEquals(entries.size, 4)
    assertEquals(envelope.hcursor.get[String]("op").toOption, Some(AuthoredAvlInsertOrUpdate.Op))
    assertEquals(envelope.hcursor.get[String]("source").toOption, Some(AuthoredAvlInsertOrUpdate.Source))
  }

  test("all entries have non-null cost and null error (all are accept arms)") {
    entries.foreach { e =>
      val name  = e.hcursor.get[String]("name").toOption.getOrElse("?")
      val cost  = e.hcursor.downField("expected").get[Long]("cost").toOption
      val error = e.hcursor.downField("expected").downField("error").focus.get
      assert(cost.isDefined, s"$name: cost must be present")
      assert(error.isNull,   s"$name: error must be null (None return is a valid value, not an error)")
    }
  }

  // ── semantics pins ───────────────────────────────────────────────────────────

  test("fresh-key and existing-key return Some(AvlTree) (non-null Option inner)") {
    def optInner(name: String): Option[io.circe.Json] = {
      val v = byName(name).hcursor.downField("expected").downField("value")
      v.downField("value").focus.filterNot(_.isNull)
    }
    assert(optInner("insertOrUpdate#fresh-key").isDefined,    "fresh-key must return Some(AvlTree)")
    assert(optInner("insertOrUpdate#existing-key").isDefined, "existing-key must return Some(AvlTree)")
  }

  test("fresh-key and existing-key inner value is AvlTree") {
    def innerKind(name: String): String = {
      byName(name).hcursor.downField("expected").downField("value")
        .downField("value").downField("kind").as[String].toOption.getOrElse("?")
    }
    assertEquals(innerKind("insertOrUpdate#fresh-key"),    "AvlTree", "fresh-key inner kind")
    assertEquals(innerKind("insertOrUpdate#existing-key"), "AvlTree", "existing-key inner kind")
  }

  test("flags-deny returns None (insertAllowed=false pre-check)") {
    val v = byName("insertOrUpdate#flags-deny").hcursor.downField("expected").downField("value")
    val inner = v.downField("value").focus.filterNot(_.isNull)
    assert(inner.isEmpty, "flags-deny must return None (null inner value)")
  }

  test("bad-proof returns None (wrong-tree proof → verifier returns no digest)") {
    val v = byName("insertOrUpdate#bad-proof").hcursor.downField("expected").downField("value")
    val inner = v.downField("value").focus.filterNot(_.isNull)
    assert(inner.isEmpty, "bad-proof must return None (null inner value)")
  }

  // ── distinct-trees guard ─────────────────────────────────────────────────────

  test("all 4 entries carry distinct tree hex (copy-paste / wrong-tree mixup guard)") {
    val trees = entries.map(_.hcursor.get[String]("tree_bytes_hex").toOption.get).distinct
    assertEquals(trees.size, 4, s"expected 4 distinct trees, got ${trees.size}")
  }

  // ── staging assert ───────────────────────────────────────────────────────────

  test("staging: AvlTree.insertOrUpdate.json written to target/authored-staging/") {
    val outDir = java.nio.file.Paths.get("target", "authored-staging")
    AuthoredAvlInsertOrUpdate.writeVectors(envelope, outDir)
    val files = java.nio.file.Files.list(outDir).toArray.map(_.toString)
    assert(
      files.exists(_.endsWith("AvlTree.insertOrUpdate.json")),
      s"expected AvlTree.insertOrUpdate.json in $outDir, got: ${files.mkString(", ")}")
  }

  // ── post-bless anchors (costs + Some-digest bytes_hex + tree-hex prefix) ───────
  // Pinned from the FIRST bless run against sigma-state-6.0.3. A drift means the
  // cost model, the prover material, or the AST encoding moved — investigate,
  // do not blindly re-bless.
  //
  // Cost breakdown (DynamicCost op; pinned from first bless run):
  //   fresh-key:    cost 483 (InsertOrUpdate with valid proof, n=8 tree)
  //   existing-key: cost 483 (InsertOrUpdate update path, same tree size/proof length)
  //   flags-deny:   cost  73 (pre-check fires before verifier creation — tiny cost)
  //   bad-proof:    cost 443 (verifier built but digest=None; short proof → lower cost)

  test("ANCHOR: fresh-key tree-hex prefix and cost (pinned from first bless run)") {
    val e = byName("insertOrUpdate#fresh-key")
    assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption.map(_.take(16)),
      Some("1b9a020464fb2b77"), "fresh-key tree-hex prefix drifted")
    assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption,
      Some(483L), "fresh-key cost drifted")
  }

  test("ANCHOR: fresh-key Some-digest bytes_hex (pinned from first bless run)") {
    val inner = byName("insertOrUpdate#fresh-key")
      .hcursor.downField("expected").downField("value").downField("value")
    assertEquals(inner.get[String]("bytes_hex").toOption,
      Some("f1b5df03eaef0fc804d5db5ad0be313d36e9be3aecbd10ec9175fd2a489a3cc60407200108"),
      "fresh-key digest drifted")
  }

  test("ANCHOR: existing-key Some-digest bytes_hex and cost (pinned from first bless run)") {
    val e = byName("insertOrUpdate#existing-key")
    val inner = e.hcursor.downField("expected").downField("value").downField("value")
    assertEquals(inner.get[String]("bytes_hex").toOption,
      Some("cf089b593216660a7b7662afe595c9626f2042734661b29994312d4994be6c9b0407200108"),
      "existing-key digest drifted")
    assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption,
      Some(483L), "existing-key cost drifted")
  }

  test("ANCHOR: flags-deny cost (pre-check; pinned from first bless run)") {
    assertEquals(byName("insertOrUpdate#flags-deny").hcursor.downField("expected").get[Long]("cost").toOption,
      Some(73L), "flags-deny cost drifted")
  }

  test("ANCHOR: bad-proof cost (wrong-tree proof; pinned from first bless run)") {
    assertEquals(byName("insertOrUpdate#bad-proof").hcursor.downField("expected").get[Long]("cost").toOption,
      Some(443L), "bad-proof cost drifted")
  }
}
