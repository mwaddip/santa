package santa

import io.circe.Json

/** Anchors the in-block kept-bytes family. The blesser already fails loud if a verdict is not the
  * one claimed, and if an embedded mined header does not commit to the derived roots; what this
  * pins is the STRUCTURE — the accept/reject pair must stay opposite, the canonical control must
  * stay a reject, and the shadow row must stay an ACCEPT (an implementation preferring its store
  * is exactly what that row catches). */
class AuthoredBlockKeptTreeBytesTest extends munit.FunSuite {
  private lazy val es: List[Json] = AuthoredBlockKeptTreeBytes.entries.toList
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse(fail("name"))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def row(sub: String): Json = es.filter(e => name(e).contains(sub)) match {
    case List(e) => e
    case other   => fail(s"want one row matching '$sub', got ${other.map(name)}")
  }

  test("four rows, every name unique, every block carries its proof and its boxes") {
    assertEquals(es.size, 4)
    assertEquals(es.map(name).distinct.size, 4)
    es.foreach { e =>
      val b = e.hcursor.downField("block")
      assert(b.downField("adProofs").get[String]("proofBytes").toOption.exists(_.nonEmpty), name(e))
      val section = BlockSectionBytes.parse(b.get[String]("blockTransactions")
        .getOrElse(fail(s"${name(e)}: block.blockTransactions must be the section's hex")))
      assertEquals(section.txs.size, 2, s"${name(e)}: create-then-spend needs exactly two transactions")
      assert(e.hcursor.downField("boxes").values.exists(_.nonEmpty), name(e))
      // difficulty 1 is the whole reason this block is mineable; height 2 keeps it off the
      // voting-start path (height % 1024 == 1) and gives donner a non-empty headers window
      assertEquals(b.downField("header").get[Int]("height").toOption, Some(2), name(e))
      assertEquals(b.downField("header").get[String]("difficulty").toOption, Some("1"), name(e))
      assertEquals(e.hcursor.downField("headers").values.map(_.size), Some(1), name(e))
      assertEquals(section.blockVersion.toInt, 4, name(e))
    }
  }

  test("the pair stays opposite, and the control stays a reject") {
    assert(valid(row("as-received-accept")), "#0 must be VALID — the in-block box keeps its bytes")
    assert(!valid(row("rewritten-expectation-reject")), "#1 must be INVALID")
    assert(!valid(row("canonical-control-reject")), "#2 must be INVALID")
    // #0 and #2 differ ONLY in the section's encoding of the created box's tree: the same
    // transactions (the ids hash a re-serialization), with #0 reading the as-received bytes
    def txIds(sub: String) = BlockSectionBytes.parse(row(sub).hcursor.downField("block")
      .get[String]("blockTransactions").getOrElse(fail(s"$sub: section"))).txs.map(_.id)
    assertEquals(txIds("as-received-accept"), txIds("canonical-control-reject"),
      "#0 and #2 must carry the same transactions — the splice is invisible to the ids")
    assertNotEquals(row("as-received-accept").hcursor.downField("block").get[String]("blockTransactions").toOption,
      row("canonical-control-reject").hcursor.downField("block").get[String]("blockTransactions").toOption,
      "#0 must carry the as-received encoding and #2 the written one")
    assertEquals(row("as-received-accept").hcursor.downField("block").downField("header")
      .get[String]("id").toOption,
      row("canonical-control-reject").hcursor.downField("block").downField("header")
        .get[String]("id").toOption,
      "#0 and #2 must share the mined header — the splice is invisible to the transactions root")
  }

  test("the store-shadow row grades the lookup order and must stay an ACCEPT") {
    val shadow = row("shadowed-by-store-copy")
    assert(valid(shadow), "#3 must be VALID: the block's own outputs win over the store copy")
    // it carries one more box than #0: the shadowing store copy, under the same id
    val n0 = row("as-received-accept").hcursor.downField("boxes").values.map(_.size).getOrElse(0)
    val n3 = shadow.hcursor.downField("boxes").values.map(_.size).getOrElse(0)
    assertEquals(n3, n0 + 1, "#3 must add exactly the shadowing copy")
  }

  test("every reject states why it separates, and the control is marked") {
    // The block vector schema carries no prose field, so a row's role is in-source documentation
    // (AuthoredBlockKeptTreeBytes.rationales) rather than a vector field. Held to the same
    // discipline as the eval and transaction families.
    val r = AuthoredBlockKeptTreeBytes.rationales
    assertEquals(r.size, 4)
    es.filter(e => !valid(e)).foreach { e =>
      val d = r.getOrElse(name(e), fail(s"no rationale for ${name(e)}"))
      assert(d.contains("SEPARATING") || d.contains("CONTROL"), s"${name(e)}: $d")
    }
    assert(r.collect { case (k, v) if k.contains("canonical-control") => v }
      .forall(_.contains("CONTROL")))
    // the shadow row is an ACCEPT, so it must say what it separates on anyway
    assert(r.collect { case (k, v) if k.contains("shadowed-by-store") => v }
      .forall(_.contains("SEPARATING")))
  }

  test("write vectors") {
    AuthoredBlockKeptTreeBytes.writeVectors(AuthoredBlockKeptTreeBytes.blessAll(),
      java.nio.file.Paths.get("..", "vectors"))
  }
}
