package santa

import java.nio.file.Files

/** Guard + anchor tests for the Context property + preHeader accessor vectors.
  *
  * Pins the JVM dummy-context surface for CONTEXT.preHeader.* (7 entries) and
  * CONTEXT.{dataInputs,headers,selfBoxIndex,LastBlockUtxoRootHash,minerPubKey} (5 entries).
  * Exact cost anchors and tree hex are locked once observed from the first bless run —
  * a drift here means the JVM cost model or a tree shape moved: INVESTIGATE, not blindly accept.
  */
class AuthoredContextPropsTest extends munit.FunSuite {

  private lazy val vectors = AuthoredContextProps.extract()

  // ── count + well-formedness ────────────────────────────────────────────────

  test("7 preHeader entries, all bless Right (non-null value+cost, null error)") {
    val env     = vectors(AuthoredContextProps.OpPre)
    val entries = env.hcursor.downField("entries").values.get.toVector
    assertEquals(entries.size, 7)
    entries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse("?")
      val c = e.hcursor.downField("expected")
      assert(!c.downField("value").focus.get.isNull, s"unblessed value: $name")
      assert(!c.downField("cost").focus.get.isNull,  s"unblessed cost:  $name")
      assertEquals(c.downField("error").focus.map(_.noSpaces), Some("null"), s"unexpected error: $name")
    }
  }

  test("5 ctx-property entries, all bless Right (non-null value+cost, null error)") {
    val env     = vectors(AuthoredContextProps.OpCtx)
    val entries = env.hcursor.downField("entries").values.get.toVector
    assertEquals(entries.size, 5)
    entries.foreach { e =>
      val name = e.hcursor.get[String]("name").toOption.getOrElse("?")
      val c = e.hcursor.downField("expected")
      assert(!c.downField("value").focus.get.isNull, s"unblessed value: $name")
      assert(!c.downField("cost").focus.get.isNull,  s"unblessed cost:  $name")
      assertEquals(c.downField("error").focus.map(_.noSpaces), Some("null"), s"unexpected error: $name")
    }
  }

  // ── distinct-tree counts ───────────────────────────────────────────────────

  test("7 distinct preHeader trees (one per accessor)") {
    val entries = vectors(AuthoredContextProps.OpPre).hcursor.downField("entries").values.get.toVector
    val trees   = entries.flatMap(_.hcursor.get[String]("tree_bytes_hex").toOption).distinct
    assertEquals(trees.size, 7, s"expected 7 distinct preHeader trees, got ${trees.size}: $trees")
  }

  test("5 distinct ctx-property trees") {
    val entries = vectors(AuthoredContextProps.OpCtx).hcursor.downField("entries").values.get.toVector
    val trees   = entries.flatMap(_.hcursor.get[String]("tree_bytes_hex").toOption).distinct
    assertEquals(trees.size, 5, s"expected 5 distinct ctx-property trees, got ${trees.size}: $trees")
  }

  // ── value anchors ─────────────────────────────────────────────────────────

  private def preEntry(name: String) = {
    val entries = vectors(AuthoredContextProps.OpPre).hcursor.downField("entries").values.get.toVector
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"preHeader entry '$name' not found"))
  }

  private def ctxEntry(name: String) = {
    val entries = vectors(AuthoredContextProps.OpCtx).hcursor.downField("entries").values.get.toVector
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"ctx-prop entry '$name' not found"))
  }

  private def kind(e: io.circe.Json): String =
    e.hcursor.downField("expected").downField("value").get[String]("kind")
      .toOption.getOrElse(fail(s"no kind in ${e.noSpaces}"))

  test("preHeader value anchors: dummy-context surfaces (version=activated+1=3, parentId=empty, timestamp=3, nBits=0, height=0, minerPk=generator, votes=empty)") {
    // version → Byte 3: the contract pins preHeader.version = activated+1 (block-version
    // convention; sigma-rust derives script activation from it — runner-contract.md §2)
    val ver = preEntry("preHeader.version#dummy")
    assertEquals(kind(ver), "Byte")
    assertEquals(ver.hcursor.downField("expected").downField("value").get[Int]("value").toOption, Some(3), "version")

    // parentId → Coll[Byte] with 0 items
    val parentId = preEntry("preHeader.parentId#dummy")
    assertEquals(kind(parentId), "Coll")
    val parentItems = parentId.hcursor.downField("expected").downField("value").downField("items").values.get.toVector
    assertEquals(parentItems.size, 0, "parentId items")

    // timestamp → Long "3"
    val ts = preEntry("preHeader.timestamp#dummy")
    assertEquals(kind(ts), "Long")
    assertEquals(ts.hcursor.downField("expected").downField("value").get[String]("value").toOption, Some("3"), "timestamp")

    // nBits → Long "0"
    val nBits = preEntry("preHeader.nBits#dummy")
    assertEquals(kind(nBits), "Long")
    assertEquals(nBits.hcursor.downField("expected").downField("value").get[String]("value").toOption, Some("0"), "nBits")

    // height → Int 0
    val height = preEntry("preHeader.height#dummy")
    assertEquals(kind(height), "Int")
    assertEquals(height.hcursor.downField("expected").downField("value").get[Int]("value").toOption, Some(0), "height")

    // minerPk → GroupElement with generator bytes 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798
    val minerPk = preEntry("preHeader.minerPk#dummy")
    assertEquals(kind(minerPk), "GroupElement")
    assertEquals(
      minerPk.hcursor.downField("expected").downField("value").get[String]("bytes_hex").toOption,
      Some("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
      "minerPk generator"
    )

    // votes → Coll[Byte] with 0 items
    val votes = preEntry("preHeader.votes#dummy")
    assertEquals(kind(votes), "Coll")
    val votesItems = votes.hcursor.downField("expected").downField("value").downField("items").values.get.toVector
    assertEquals(votesItems.size, 0, "votes items")
  }

  test("ctx-property value anchors: dataInputs=empty, headers=empty, selfBoxIndex=0, LastBlockUtxoRootHash=AvlTree, minerPubKey=Coll[Byte]") {
    // dataInputs → Coll[Box] with 0 items
    val di = ctxEntry("CONTEXT.dataInputs#dummy")
    assertEquals(kind(di), "Coll")
    val diItems = di.hcursor.downField("expected").downField("value").downField("items").values.get.toVector
    assertEquals(diItems.size, 0, "dataInputs items")

    // headers → Coll[Header] with 0 items
    val hdr = ctxEntry("CONTEXT.headers#dummy")
    assertEquals(kind(hdr), "Coll")
    val hdrItems = hdr.hcursor.downField("expected").downField("value").downField("items").values.get.toVector
    assertEquals(hdrItems.size, 0, "headers items")

    // selfBoxIndex → Int 0
    val sbi = ctxEntry("CONTEXT.selfBoxIndex#dummy")
    assertEquals(kind(sbi), "Int")
    assertEquals(sbi.hcursor.downField("expected").downField("value").get[Int]("value").toOption, Some(0), "selfBoxIndex")

    // LastBlockUtxoRootHash → AvlTree
    val avl = ctxEntry("CONTEXT.LastBlockUtxoRootHash#dummy")
    assertEquals(kind(avl), "AvlTree")

    // minerPubKey → Coll[Byte] (the 33-byte generator encoding)
    val mpk = ctxEntry("CONTEXT.minerPubKey#dummy")
    assertEquals(kind(mpk), "Coll")
  }

  // ── cost anchors ──────────────────────────────────────────────────────────
  // Costs recorded from the first bless run — a drift means JVM cost model shifted:
  // INVESTIGATE, not blindly accept.
  // preHeader chain: Context.preHeader (15) + SPreHeaderMethods accessor (10) = 25 base,
  // plus any per-op adder.

  test("cost anchors: preHeader entries (all flat 34, two-level MethodCall chain)") {
    val entries = vectors(AuthoredContextProps.OpPre).hcursor.downField("entries").values.get.toVector
    def costOf(name: String): Long =
      entries.find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"no entry '$name'"))
        .hcursor.downField("expected").get[Long]("cost").toOption
        .getOrElse(fail(s"'$name' has no cost"))
    // Pinned from first bless run: Context.preHeader FixedCost(15) + SPreHeaderMethods accessor FixedCost(10)
    // + 9 base (dummy Int input decode + context bootstrap) = 34 for every accessor.
    val preCost = 34L
    Seq("version", "parentId", "timestamp", "nBits", "height", "minerPk", "votes").foreach { n =>
      assertEquals(costOf(s"preHeader.$n#dummy"), preCost, s"preHeader.$n cost drifted")
    }
  }

  test("cost anchors: ctx-property entries (single MethodCall, varies by op)") {
    val entries = vectors(AuthoredContextProps.OpCtx).hcursor.downField("entries").values.get.toVector
    def costOf(name: String): Long =
      entries.find(_.hcursor.get[String]("name").toOption.contains(name))
        .getOrElse(fail(s"no entry '$name'"))
        .hcursor.downField("expected").get[Long]("cost").toOption
        .getOrElse(fail(s"'$name' has no cost"))
    // Pinned from first bless run:
    //   dataInputs/headers/LastBlockUtxoRootHash → FixedCost(15) + 5 base = 20
    //   selfBoxIndex/minerPubKey                 → FixedCost(20) + 5 base = 25
    val ctxCosts: Map[String, Long] = Map(
      "CONTEXT.dataInputs#dummy"            -> 20L,
      "CONTEXT.headers#dummy"               -> 20L,
      "CONTEXT.selfBoxIndex#dummy"          -> 25L,
      "CONTEXT.LastBlockUtxoRootHash#dummy" -> 20L,
      "CONTEXT.minerPubKey#dummy"           -> 25L
    )
    ctxCosts.foreach { case (name, cost) =>
      assertEquals(costOf(name), cost, s"$name cost drifted")
    }
  }

  // ── tree-hex anchors ──────────────────────────────────────────────────────
  // Tree hex locked from first bless run — a change means the tree shape or serializer moved:
  // INVESTIGATE, not blindly accept.

  private def treeHexOf(op: String, name: String): String = {
    val entries = vectors(op).hcursor.downField("entries").values.get.toVector
    entries.find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(s"entry '$name' not found"))
      .hcursor.get[String]("tree_bytes_hex").toOption
      .getOrElse(fail(s"'$name' has no tree_bytes_hex"))
  }

  test("tree-hex anchors: one representative per family, locked from first bless") {
    // preHeader.timestamp: Context(0x10) → preHeader (SContextMethods id=3) → timestamp (SPreHeaderMethods id=3)
    assertEquals(treeHexOf(AuthoredContextProps.OpPre, "preHeader.timestamp#dummy"), "1a0800db6903db6503fe")
    // ctx selfBoxIndex: Context(0x10) → selfBoxIndex (SContextMethods id=8)
    assertEquals(treeHexOf(AuthoredContextProps.OpCtx, "CONTEXT.selfBoxIndex#dummy"), "1a0500db6508fe")
  }

  // ── staging ───────────────────────────────────────────────────────────────

  test("staging: writes both vector files") {
    val out = java.nio.file.Paths.get("target/authored-staging")
    AuthoredContextProps.writeVectors(out)
    assert(Files.exists(out.resolve("Context.preHeader_accessors.json")), "preHeader file missing")
    assert(Files.exists(out.resolve("Context.properties.json")), "ctx-props file missing")
  }
}
