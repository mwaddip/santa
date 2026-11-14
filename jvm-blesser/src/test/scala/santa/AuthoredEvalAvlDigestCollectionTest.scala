package santa

import io.circe.Json

/** Anchors the digest families: counts and versions; the refusals are the lift's alone (a pair, an option, Unit, and
  * the v6-only kinds below tree v3); `.digest` hands back the collection it was given (a Coll[Int] reads as Ints);
  * the writer charges a byte digest's own length (n - 33 more). */
class AuthoredEvalAvlDigestCollectionTest extends munit.FunSuite {
  private val B = AuthoredEvalAvlDigestCollection
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def id(e: Json): String = e.hcursor.get[String]("script").toOption.get.takeWhile(_ != ']').drop(1)
  private def exp(op: String, i: String) = entries(op).find(id(_) == i).getOrElse(fail(s"$op $i")).hcursor.downField("expected")

  test("v6 160 rows, v5 86; the refusals are the lift's") {
    assertEquals(B.Rows.count(_.v6), 160)
    assertEquals(B.Rows.count(!_.v6), 86)
    B.Rows.filterNot(_.accept).foreach(r => assertEquals(r.kind, "lift", r.id))
    Seq((B.Op6, 3, 3), (B.Op5, 2, 0)).foreach { case (op, act, tv) =>
      entries(op).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(act))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(tv))
      }
    }
  }

  test(".digest hands back the collection: Ints read as Ints") {
    val x = exp(B.Op6, "g1|digest|ints")
    assertEquals(x.downField("value").downField("elem").get[String]("tag").toOption, Some("SInt"))
    assertEquals(x.downField("value").downField("items").as[List[Json]].toOption.map(_.size), Some(2))
  }

  test("the writer charges a byte digest's own length: 78 + n") {
    val b0 = exp(B.Op6, "g5|serialize|b0").get[Long]("cost").toOption.get
    val b1 = exp(B.Op6, "g5|serialize|b1").get[Long]("cost").toOption.get
    assertEquals(b0, 78L); assertEquals(b1, 79L)
  }

  test("envelope + write staging files") {
    B.Ops.foreach(op => assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    B.Ops.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
