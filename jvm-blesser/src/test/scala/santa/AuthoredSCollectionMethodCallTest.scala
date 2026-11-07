package santa

import io.circe.Json

/** Anchors both arms of the SCollection method-id 1-10 batch.
  *
  * The eval arm's verdicts and costs and the wire arm's identity round-trips are both re-derived
  * through the oracle by `extract()`, so this pins what sigma-state 6.0.7 said. The two claims
  * most worth holding: exactly four of the ten ids evaluate, and every wire entry round-trips to
  * itself (no parse-time rewrite into SizeOf/ByIndex). */
class AuthoredSCollectionMethodCallTest extends munit.FunSuite {

  private lazy val evalVectors = AuthoredEvalSCollectionMethodCall.extract()
  private lazy val wireVectors = AuthoredWireSCollectionMethodCall.extract()

  private def entriesOf(v: Map[String, Json], op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse("?")
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption

  // ── eval arm ────────────────────────────────────────────────────────────────

  /** (name, errored, cost) as the 6.0.7 oracle blessed them. */
  private val PinnedEval = List(
    ("size-accept#0", false, Some(48L)),
    ("getorelse-out-of-range-accept#1", false, Some(74L)),
    ("getorelse-in-range-accept#2", false, Some(74L)),
    ("map-accept#3", false, Some(120L)),
    ("apply-accept#4", false, Some(69L)),
    ("exists-errored#5", true, None),
    ("forall-errored#6", true, None),
    ("slice-errored#7", true, None),
    ("filter-errored#8", true, None),
    ("append-errored#9", true, None),
    ("fold-two-arg-op-errored#10", true, None),
    ("fold-pair-op-errored#11", true, None))

  test("eval: exactly four of the ten ids evaluate, with the blessed costs") {
    val es = entriesOf(evalVectors, AuthoredEvalSCollectionMethodCall.Op)
    assertEquals(es.map(e => (name(e), errored(e), cost(e))), PinnedEval)
    assertEquals(es.count(e => !errored(e)), 5, "five accept ENTRIES, over four distinct method ids")
    assertEquals(es.count(errored), 7, "six ids with no handler, plus fold's second failure mode")
  }

  test("eval: both fold operator forms are present and both reject") {
    val by = entriesOf(evalVectors, AuthoredEvalSCollectionMethodCall.Op).map(e => name(e) -> e).toMap
    assert(errored(by("fold-two-arg-op-errored#10")), "the declared 2-arg operator must reject")
    assert(errored(by("fold-pair-op-errored#11")), "the pair operator must reject too")
  }

  test("eval envelope: the 6.0.7 blessing, activated 3 / ErgoTree 3") {
    val env = evalVectors(AuthoredEvalSCollectionMethodCall.Op).hcursor
    assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
    entriesOf(evalVectors, AuthoredEvalSCollectionMethodCall.Op).foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3), name(e))
      assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3), name(e))
    }
  }

  // ── wire arm ────────────────────────────────────────────────────────────────

  test("wire: 21 entries — ten v3 size-flagged, ten v0 unsized, one v0 size-flagged control") {
    val es = entriesOf(wireVectors, AuthoredWireSCollectionMethodCall.Op)
    assertEquals(es.length, 21)
    assertEquals(es.count(e => name(e).endsWith("-v3-sized#" + name(e).split("#").last)), 10)
    assertEquals(es.count(e => name(e).contains("-v0-unsized")), 10)
    assertEquals(es.count(e => name(e).contains("-v0-sized")), 1, "the dimension-separating control")
  }

  /** An unsized tree is only representable at version 0: above it the size bit is forced
    * (`ErgoTree.scala:104`), so a "v3 unsized" request yields the size-flagged bytes. If that ever
    * changed, the reject arm would need rebuilding, so hold the constraint explicitly. */
  test("wire: the size bit is forced above tree version 0, flat at version 0") {
    import AuthoredEvalSCollectionMethodCall.treeHex
    import AuthoredWireSCollectionMethodCall.{V0, V3}
    val call = AuthoredEvalSCollectionMethodCall.callSize
    assertEquals(treeHex(call, V3, sized = false), treeHex(call, V3, sized = true),
      "at v3 the size bit is forced, so the two requests must collapse to one encoding")
    assertNotEquals(treeHex(call, V0, sized = false), treeHex(call, V0, sized = true),
      "at v0 the size flag must actually be optional, or there is no reject arm")
  }

  /** `accept` already requires identity when building; this states it as the arm's contract so a
    * future edit that introduced an `expected_bytes_hex` would have to change the test too. */
  test("wire: every entry is an identity round-trip — nothing is rewritten at parse") {
    entriesOf(wireVectors, AuthoredWireSCollectionMethodCall.Op).foreach { e =>
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("ErgoTree"), name(e))
      assert(e.hcursor.downField("expected_bytes_hex").focus.isEmpty,
        s"${name(e)}: an identity entry must carry no expected_bytes_hex")
    }
  }

  test("wire: the v0 size-flagged control differs from its v3 twin only in the header byte") {
    val by = entriesOf(wireVectors, AuthoredWireSCollectionMethodCall.Op).map(e =>
      name(e) -> e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes"))).toMap
    val v3 = by("size-v3-sized#0")
    val v0 = by("size-v0-sized#20")
    assertEquals(v0.drop(2), v3.drop(2), "body (declared size included) must match")
    assertNotEquals(v0.take(2), v3.take(2), "only the header byte may differ")
    // The unsized twin is a byte shorter: no declared size follows its header.
    assert(by("size-v0-unsized#10").length < v0.length,
      "the unsized entry must be shorter — it carries no declared size")
  }

  test("write staging files") {
    val evalDir = java.nio.file.Paths.get("target", "eval-authored")
    val wireDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredEvalSCollectionMethodCall.writeVectors(evalDir)
    AuthoredWireSCollectionMethodCall.writeVectors(wireDir)
    assert(java.nio.file.Files.exists(
      evalDir.resolve(s"${SpecExtract.slug(AuthoredEvalSCollectionMethodCall.Op)}.json")), "eval staging")
    assert(java.nio.file.Files.exists(
      wireDir.resolve(s"${SpecExtract.slug(AuthoredWireSCollectionMethodCall.Op)}.json")), "wire staging")
  }
}
