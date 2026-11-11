package santa

import io.circe.Json

import AuthoredTxKeptTreeBytes._

/** Anchors the kept-bytes input rows. `AuthoredTxKeptTreeBytes` already fails loud if a verdict is
  * not the one claimed; what this pins is the STRUCTURE that makes the rows evidence — every
  * accept arm must have BOTH a re-written twin and a canonical control that go the other way, and
  * the two lengthening forms must stay a pair. */
class AuthoredTxKeptTreeBytesTest extends munit.FunSuite {
  private lazy val es: List[Json] = AuthoredTxKeptTreeBytes.inputEntries.toList
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse(fail("name"))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))
  private def row(sub: String): Json = es.filter(e => name(e).contains(sub)) match {
    case List(e) => e
    case other   => fail(s"want one row matching '$sub', got ${other.map(name)}")
  }

  test("13 rows: five encodings, each with its twin, and every id unique") {
    assertEquals(es.size, 13)
    assertEquals(es.map(name).distinct.size, 13)
    // every row carries a real spend and the synthetic rent context
    es.foreach { e =>
      assert(e.hcursor.get[String]("tx_bytes_hex").toOption.exists(_.nonEmpty), name(e))
      assertEquals(e.hcursor.downField("input_boxes_hex").values.map(_.size), Some(1), name(e))
    }
  }

  test("each accept arm is answered by a twin that goes the other way") {
    Seq("overlong-constants-count", "declared-size-over", "declared-size-under").foreach { enc =>
      assert(valid(row(s"$enc-accept")), s"$enc accept must be VALID")
      assert(!valid(row(s"$enc-rewritten-reject")), s"$enc re-written twin must be INVALID")
      assert(!valid(row(s"$enc-canonical-control-reject")), s"$enc canonical control must be INVALID")
    }
  }

  test("the two lengthening forms stay a pair, and both separate") {
    Seq("trueleaf", "falseleaf").foreach { f =>
      assert(valid(row(s"last-byte-$f-accept")), s"$f accept must be VALID")
      assert(!valid(row(s"last-byte-$f-rewritten-reject")), s"$f twin must be INVALID")
    }
  }

  test("every invalid row says why it separates, and every control is marked") {
    es.foreach { e =>
      val d = e.hcursor.get[String]("description").toOption.getOrElse("")
      if (!valid(e)) assert(d.contains("SEPARATING") || d.contains("CONTROL"), s"${name(e)}: $d")
    }
    es.filter(e => name(e).contains("control")).foreach { e =>
      assert(e.hcursor.get[String]("description").toOption.exists(_.contains("CONTROL")), name(e))
    }
  }

  test("part 2: the substitution cost is per KEPT byte — exactly 2 per byte of difference") {
    val cs = substCostEntries.toList
    assertEquals(cs.size, 2)
    val costs = cs.map(e => e.hcursor.downField("expected").get[Long]("cost")
      .getOrElse(fail("cost")))
    cs.foreach(e => assert(e.hcursor.downField("expected").get[Boolean]("valid").contains(true),
      "both rows must be VALID — this is a cost-only divergence"))
    // four extra bytes of declared-size VLQ, charged at CostPerTreeByte = 2
    assertEquals(costs(1) - costs(0), 8L,
      s"the kept-length charge must be 2 per byte; got ${costs(0)} and ${costs(1)}")
  }

  test("the envelope carries the tier's premises") {
    blessAll().foreach { case (path, env) =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-transaction/v1"), path)
      assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some(AuthoredTxStorageRent.BlessedBy), path)
      env.hcursor.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
        assertEquals(e.hcursor.downField("headers_hex").as[List[String]].toOption.map(_.size), Some(10))
      }
    }
  }

  test("write vectors") {
    writeVectors(blessAll(), java.nio.file.Paths.get("..", "vectors"))
  }
}
