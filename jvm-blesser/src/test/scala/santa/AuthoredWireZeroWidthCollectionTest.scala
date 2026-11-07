package santa

import io.circe.Json

/** Anchors the rule-1020 wire vectors. extract() re-derives every verdict through
  * WireCanonicalize and fails loud if a degrade does not round-trip, if a reject is rejected
  * for a reason other than rule 1020, or if a control stops parsing. A failure means
  * sigma-state changed where CheckZeroWidthCollection fires. */
class AuthoredWireZeroWidthCollectionTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireZeroWidthCollection.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, field: String): String =
    e.hcursor.get[String](field).toOption.getOrElse(fail(field))
  private def isReject(e: Json): Boolean =
    e.hcursor.get[String]("error").toOption.contains("errored")

  private val Ops = Seq(AuthoredWireZeroWidthCollection.OpBox,
    AuthoredWireZeroWidthCollection.OpTransaction)

  test("both kinds carry the same eight rows in the same arms") {
    Ops.foreach { op =>
      val es = entries(op)
      assertEquals(es.size, 8, op)
      // 4 sized degrades accept, 1 unsized rejects, 2 controls accept, 1 value row rejects
      assertEquals(es.map(isReject), List(false, false, false, false, true, false, false, true), op)
    }
  }

  test("the four zero-width shapes are all covered, in both kinds") {
    Ops.foreach { op =>
      val names = entries(op).map(str(_, "name"))
      Seq("coll-unit-3", "coll-unit-0", "coll-coll-unit", "coll-tuple-unit-unit").foreach { shape =>
        assert(names.exists(n => n.contains(s"sized-$shape")), s"$op: missing sized $shape")
      }
      assert(names.exists(_.contains("control-coll-tuple-unit-int")), s"$op: missing the tuple control")
      assert(names.exists(_.contains("control-bare-unit")), s"$op: missing the bare SUnit control")
      assert(names.exists(_.contains("unsized")), s"$op: missing the unsized reject")
    }
    assert(entries(AuthoredWireZeroWidthCollection.OpBox).map(str(_, "name"))
      .exists(_.contains("register")), "Box: missing the register reject")
    assert(entries(AuthoredWireZeroWidthCollection.OpTransaction).map(str(_, "name"))
      .exists(_.contains("extension")), "Transaction: missing the extension reject")
  }

  test("entry names are slugs — other sessions copy them verbatim") {
    Ops.foreach { op =>
      entries(op).map(str(_, "name")).foreach { n =>
        assert(n.matches("[a-z0-9-]+#[0-9]+"), s"$op: '$n' is not a slug#index")
      }
    }
  }

  test("the sized and unsized trees differ in exactly the size bit (0x18 vs 0x10)") {
    Ops.foreach { op =>
      val byName = entries(op).map(e => str(e, "name") -> str(e, "bytes_hex")).toMap
      def find(frag: String): String =
        byName.collectFirst { case (n, h) if n.contains(frag) => h }.getOrElse(fail(s"$op: $frag"))
      // the same constant, carried by a sized and an unsized tree: 18 <size> 02 .. vs 10 02 ..
      val const = "0c6203"
      assert(find("sized-coll-unit-3").contains("18" + "08" + "02" + const), s"$op: sized 0x18")
      assert(find("unsized").contains("10" + "02" + const), s"$op: unsized 0x10")
    }
  }

  test("the zero-width constants appear verbatim in their carriers") {
    // Coll[Unit] = 0c62, Coll[Coll[Unit]] = 0c0c62, Coll[(Unit,Unit)] = 0c3c6262,
    // Coll[(Unit,Int)] = 0c4c62, bare SUnit = 62 — the encodings the probe measured.
    Ops.foreach { op =>
      val byName = entries(op).map(e => str(e, "name") -> str(e, "bytes_hex")).toMap
      def find(frag: String): String =
        byName.collectFirst { case (n, h) if n.contains(frag) => h }.getOrElse(fail(s"$op: $frag"))
      assert(find("sized-coll-unit-3").contains("0c6203"), "Coll[Unit] length 3")
      assert(find("sized-coll-unit-0").contains("0c6200"), "Coll[Unit] length 0")
      assert(find("sized-coll-coll-unit").contains("0c0c62" + "01" + "02"), "Coll[Coll[Unit]]")
      assert(find("sized-coll-tuple-unit-unit").contains("0c3c6262" + "01"), "Coll[(Unit,Unit)]")
      assert(find("control-coll-tuple-unit-int").contains("0c4c62" + "01" + "0a"), "Coll[(Unit,Int)]")
    }
  }

  test("envelopes: santa-wire/v1, the v6 context, authored source, 6.0.7 blessing") {
    Ops.foreach { op =>
      val c = vectors(op).hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("op").toOption, Some(op))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
      entries(op).foreach { e =>
        assertEquals(e.hcursor.get[String]("source").toOption,
          Some(AuthoredWireZeroWidthCollection.Source))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireZeroWidthCollection.writeVectors(outDir)
    Ops.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op"))
  }
}
