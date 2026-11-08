package santa

import io.circe.Json

/** Anchors the PropertyCall receiver-type wire vectors. `extract()` already re-derives every
  * verdict through WireCanonicalize (an accept must round-trip to itself; the control must throw
  * the receiver-type CAST). This pins the three encodings byte-exactly — they are the sigma-rust
  * session's, not the blesser's — and pins each one against the AST the JVM actually builds, so a
  * serializer change fails here instead of leaving an entry that no longer means its description.
  */
class AuthoredWirePropertyCallReceiverTypeTest extends munit.FunSuite {
  private val B = AuthoredWirePropertyCallReceiverType
  private lazy val vectors = B.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, field: String): String =
    e.hcursor.get[String](field).toOption.getOrElse(fail(field))

  private val Slugs = List("none-over-filter", "getreg-propcall", "indices-over-filter")

  test("three ops, one entry per case, in order") {
    assertEquals(vectors.keySet, Set(B.OpErgoTree, B.OpBox, B.OpTransaction))
    Seq(B.OpErgoTree -> "tree", B.OpBox -> "box", B.OpTransaction -> "tx").foreach { case (op, suffix) =>
      val es = entries(op)
      assertEquals(es.size, Slugs.size, op)
      es.zip(Slugs).zipWithIndex.foreach { case ((e, slug), i) =>
        assertEquals(str(e, "name"), s"receiver-type-$slug-$suffix#$i", op)
      }
    }
  }

  /** The probe's encodings. `0b` is a v3 size-flagged header with no constant segregation;
    * `e6` is PropertyCall. 1 and 3 share the receiver `b50402d9010104010104…` —
    * Filter(IntConstant(1), (x: Int) => true) — and differ only in the call over it. */
  private val ExpectedTrees = List(
    "0b0fd1e6db6a0ab50402d9010104010104",       // none: e6 db 6a 0a <receiver> |04
    "0b07d1e6db6313a704",                       // getReg: e6 db 63 13 a7(SELF) |04
    "0b11d193b1db0c0eb50402d90101040101010400") // indices: e6 db 0c 0e <receiver>, no type byte

  test("the three trees are byte-exact") {
    entries(B.OpErgoTree).map(str(_, "bytes_hex")).zip(ExpectedTrees).zip(Slugs).foreach {
      case ((got, want), slug) => assertEquals(got, want, slug)
    }
    assertEquals(B.caseTrees.map(_._2), ExpectedTrees)
  }

  test("the two accepting trees are the calls their descriptions claim, over the receiver claimed") {
    val none = B.astOf(ExpectedTrees.head)
    assert(none.contains("SGlobalMethods,none"), s"not a call to SGlobal.none: $none")
    assert(none.contains("MethodCall(Filter(IntConstant(1)"),
      s"the receiver must be a Filter over an Int: $none")
    val getReg = B.astOf(ExpectedTrees(1))
    assert(getReg.contains("SBoxMethods,getReg"), s"not a call to SBox.getReg: $getReg")
    assert(getReg.contains("MethodCall(Self$("), s"the receiver must be SELF: $getReg")
    // Neither degrades: a degrade would mean the tree never parsed, and the accept arms would be
    // green for the wrong reason (an echo of the input bytes).
    assert(!none.startsWith("DEGRADED") && !getReg.startsWith("DEGRADED"), "an accept arm degraded")
  }

  test("the control throws the receiver-type cast, not a validation rule") {
    val t = intercept[ClassCastException](B.astOf(ExpectedTrees(2)))
    assert(Option(t.getMessage).exists(_.contains("SCollection")),
      s"expected the SInt -> SCollection cast: ${t.getMessage}")
  }

  test("entries 0 and 1 are accept arms; entry 2 is the reject arm, on every kind") {
    vectors.foreach { case (op, _) =>
      val es = entries(op)
      es.take(2).foreach { e =>
        assert(e.hcursor.get[String]("error").toOption.isEmpty, s"${str(e, "name")}: accept arm")
      }
      assertEquals(es(2).hcursor.get[String]("error").toOption, Some("errored"), op)
      es.foreach { e =>
        assert(e.hcursor.get[String]("expected_bytes_hex").toOption.isEmpty,
          s"${str(e, "name")}: identity round-trip or reject, never a non-identity expected")
      }
    }
  }

  test("each tree appears verbatim inside its box and transaction entry") {
    val trees = entries(B.OpErgoTree).map(str(_, "bytes_hex"))
    Seq(B.OpBox, B.OpTransaction).foreach { op =>
      entries(op).zip(trees).foreach { case (e, treeHex) =>
        assert(str(e, "bytes_hex").contains(treeHex), s"${str(e, "name")}: must carry $treeHex")
      }
    }
  }

  test("envelopes: santa-wire/v1, the node's v6 context (3, 3), authored source, 6.0.7 blessing") {
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"), op)
      assertEquals(c.get[String]("op").toOption, Some(op))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"), op)
      entries(op).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
        assertEquals(e.hcursor.get[String]("source").toOption, Some(B.Source))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    B.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
