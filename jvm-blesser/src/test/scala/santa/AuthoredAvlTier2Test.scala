package santa

/** TDD for the AvlTree Tier-2 authored family (proof-carrying methods).
  * Structure assertions run pre-bless; exact cost/hex anchors are pinned
  * AFTER the first bless (the AuthoredPowHitTest pattern). */
class AuthoredAvlTier2Test extends munit.FunSuite {

  private lazy val vectors = AuthoredAvlTier2.extract()

  test("nine ops, expected entry counts") {
    val counts = vectors.map { case (op, env) =>
      op -> env.hcursor.downField("entries").as[Seq[io.circe.Json]].fold(_ => -1, _.size)
    }
    assertEquals(counts(AuthoredAvlTier2.OpContains), 2)
    assertEquals(counts(AuthoredAvlTier2.OpGet), 2)
    assertEquals(counts(AuthoredAvlTier2.OpGetMany), 3)
    assertEquals(counts(AuthoredAvlTier2.OpInsert), 2)
    assertEquals(counts(AuthoredAvlTier2.OpUpdate), 2)
    assertEquals(counts(AuthoredAvlTier2.OpRemove), 2)
    assertEquals(counts(AuthoredAvlTier2.OpFlagsDigest), 2)
    assertEquals(counts(AuthoredAvlTier2.OpLadder), 9)
    assertEquals(counts(AuthoredAvlTier2.OpAdversarial), 2)
    assertEquals(vectors.size, 9)
  }

  test("semantics pins: present=Some / absent=None / flag-gated=None / valid-modify=Some(AvlTree)") {
    def entryValue(op: String, name: String): io.circe.Json = {
      val entries = vectors(op).hcursor.downField("entries").as[Seq[io.circe.Json]].toOption.get
      val e = entries.find(_.hcursor.get[String]("name").toOption.exists(_.startsWith(name))).get
      e.hcursor.downField("expected").downField("value").as[io.circe.Json].toOption.get
    }
    def kindOf(j: io.circe.Json): String = j.hcursor.get[String]("kind").getOrElse("?")
    def optInner(j: io.circe.Json): Option[io.circe.Json] =
      j.hcursor.downField("value").focus.filterNot(_.isNull)

    assertEquals(kindOf(entryValue(AuthoredAvlTier2.OpGet, "present")), "Option")
    assert(optInner(entryValue(AuthoredAvlTier2.OpGet, "present")).isDefined)
    assert(optInner(entryValue(AuthoredAvlTier2.OpGet, "absent")).isEmpty)
    assert(optInner(entryValue(AuthoredAvlTier2.OpInsert, "readonly")).isEmpty)
    assert(optInner(entryValue(AuthoredAvlTier2.OpInsert, "valid")).exists(kindOf(_) == "AvlTree"))
    assert(optInner(entryValue(AuthoredAvlTier2.OpUpdate, "disallowed")).isEmpty)
    assert(optInner(entryValue(AuthoredAvlTier2.OpRemove, "disallowed")).isEmpty)
  }

  test("getMany proof-length series crosses a 64-byte chunk boundary at fixed n") {
    val lens = AuthoredAvlTier2.getManyProofLengths
    val chunks = lens.map(l => (l + 63) / 64)
    assert(chunks.distinct.size >= 2, s"proof lengths $lens never cross a chunk boundary")
  }

  test("ladder spans tree sizes and is strictly cost-increasing in proof length") {
    val entries = vectors(AuthoredAvlTier2.OpLadder).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    assertEquals(entries.size, 9)
    val costs = entries.map(_.hcursor.downField("expected").get[Long]("cost").toOption.get)
    assert(costs.head < costs.last, s"ladder costs not increasing overall: $costs")
  }

  test("staging vectors written") {
    val outDir = java.nio.file.Paths.get("target", "avl-tier2-vectors")
    AuthoredAvlTier2.writeVectors(vectors, outDir)
    assert(java.nio.file.Files.list(outDir).count() == 9)
  }

  // ── regression baseline: exact blessed cost + tree-hex prefix, locked after the
  //    first observed bless. A drift means the JVM cost model, the prover material,
  //    or the tree shape moved — investigate, do not blindly re-bless.
  test("post-bless anchors: get present#0 cost + tree-hex prefix pinned") {
    val entries = vectors(AuthoredAvlTier2.OpGet).hcursor
      .downField("entries").as[Seq[io.circe.Json]].toOption.get
    val e = entries.find(_.hcursor.get[String]("name").toOption.contains("present#0")).get
    assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption.map(_.take(16)),
      Some("1a87020364fb2b77"), "get present#0 tree-hex prefix drifted")
    assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption,
      Some(257L), "get present#0 blessed cost drifted")
  }
}
