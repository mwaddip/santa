package santa

import io.circe.Json

/** Anchors the substConstants-lift SPENDS. `extract()` requires each verdict to be the jars' (or, for the two rows
  * nine headers change, the JVM's length requirement); pinned here: the split, where each tree travels, the
  * nine-position twins, and that the eval families' context is this tier's (the same header and pre-header fields). */
class AuthoredTxSubstConstantsLiftTest extends munit.FunSuite {
  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")
  private val B = AuthoredTxSubstConstantsLift
  private lazy val blessed = B.extract()
  private lazy val spends = B.spends()
  private def entries(a: Int): List[Json] =
    blessed(a).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").toOption.getOrElse(fail("valid"))

  test("64 spends at activated 3, 48 at activated 2; each tree in its box; costs only on a spend") {
    assertEquals(entries(3).size, 64)
    assertEquals(entries(2).size, 48)
    Seq(2, 3).foreach { a =>
      val ss = spends.filter(_.activated == a)
      ss.zip(entries(a)).foreach { case (s, e) =>
        val inBox = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
        assert(inBox.contains(s.treeHex), s"${s.name}: the tree must appear verbatim in the input box")
        assert(str(e, "description").startsWith(s"[${s.name}]"), s.name)
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(a))
        assertEquals(e.hcursor.downField("expected").get[Long]("cost").toOption.isDefined, valid(e), s.name)
        assertEquals(e.hcursor.downField("headers_hex").as[List[String]].toOption.map(_.size), Some(9))
      }
    }
  }

  test("the nine-position twins: refused below tree version 3, spent at 3; the two length-requirement rows") {
    Seq(2, 3).foreach { a =>
      spends.filter(_.activated == a).zip(entries(a)).foreach { case (s, e) =>
        if (s.nine) {
          assert(s.treeHex.contains(B.NinePositions), s.name)
          assertEquals(valid(e), s.treeVersion >= 3, s"${s.name} a$a")
          assertEquals(str(e, "description").contains("SEPARATING ROW"), s.treeVersion < 3, s.name)
        }
        if (s.name.startsWith("item|headers|") && s.treeVersion == 3) {
          assert(!valid(e), s.name)
          assert(e.hcursor.downField("expected").get[String]("reason").toOption.exists(
            _.contains("expected positions and newVals to have the same length")), s.name)
        }
      }
    }
    assertEquals(spends.count(_.nine), 7)
  }

  test("the eval families' context is this tier's: the newest header, and the pre-header's fields") {
    val E = AuthoredEvalSubstConstantsLift
    Map(3 -> AuthoredTxTreeVersion.Block4, 2 -> AuthoredTxTreeVersion.Block3).foreach { case (a, c) =>
      assertEquals(E.HeaderHex(a), c.headersHex.head, s"activated $a")
      val p = c.preHeader.hcursor
      val f = PreHeaderCodec.decodeHex(E.preHeaderHex(a))
      assertEquals(p.get[Int]("version").toOption, Some(f.version.toInt))
      assertEquals(p.get[String]("parentId").toOption, Some(scorex.util.encode.Base16.encode(f.parentId)))
      assertEquals(p.get[String]("timestamp").toOption, Some(f.timestamp.toString))
      assertEquals(p.get[Long]("nBits").toOption, Some(f.nBits))
      assertEquals(p.get[Int]("height").toOption, Some(f.height))
    }
  }

  test("envelope + write") {
    Seq(2, 3).foreach { a =>
      assertEquals(blessed(a).hcursor.get[String]("op").toOption, Some(B.Op))
      assertEquals(blessed(a).hcursor.get[String]("blessed_by").toOption, Some("jvm:ergo-core-6.0.7-validateStateful"))
    }
    B.writeVectors(java.nio.file.Paths.get("..", "vectors"))
  }
}
