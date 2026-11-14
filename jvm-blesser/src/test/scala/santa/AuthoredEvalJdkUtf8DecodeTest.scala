package santa

import io.circe.Json

/** Anchors the two JDK-UTF-8 families: the counts and versions, every value and cost (each pair of a separating row
  * and its other-decoder twin must keep answering opposite ways), the length bail (17), the one-chunk cost of 40
  * decoded units against two for 120, and the writer's charge per string. */
class AuthoredEvalJdkUtf8DecodeTest extends munit.FunSuite {
  private val B = AuthoredEvalJdkUtf8Decode
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def id(e: Json): String = e.hcursor.get[String]("script").toOption.get.takeWhile(_ != ']').drop(1)
  private def got(op: String, i: String): (Boolean, Long) = {
    val x = entries(op).find(id(_) == i).getOrElse(fail(s"no $op $i")).hcursor.downField("expected")
    (x.downField("value").get[Boolean]("value").toOption.get, x.get[Long]("cost").toOption.get)
  }

  test("v6 34 rows at (3, tree 3), v5 17 rows at (2, tree 0); no row errors") {
    Seq((B.Op6, 34, 3, 3), (B.Op5, 17, 2, 0)).foreach { case (op, n, act, tv) =>
      assertEquals(entries(op).size, n, op)
      entries(op).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(act))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(tv))
        assertEquals(e.hcursor.downField("expected").get[Option[String]]("error").toOption, Some(None))
      }
    }
  }

  test("every value and cost, at both versions where both exist") {
    val want: Map[String, (Boolean, Long)] = Map(
      "ctl-true" -> (true, 41), "ctl-false" -> (false, 41), "sur-vs-1repl" -> (true, 41),
      "sur-vs-3repl" -> (false, 24), "sur-same" -> (true, 41), "sur40-same" -> (true, 41),
      "ctl-repl40-same" -> (true, 41), "ctl-repl120-same" -> (true, 43), "overlong3-vs-3repl" -> (true, 41),
      "overlong3-vs-1repl" -> (false, 24), "neq-sur-vs-1repl" -> (false, 41), "pair-sur-vs-1repl" -> (true, 35),
      "pair-sur-vs-3repl" -> (false, 15), "pair-ctl" -> (true, 35),
      "dto-sur-vs-1repl" -> (true, 182), "dto-sur-vs-3repl" -> (false, 165), "dto-ctl-repl" -> (true, 182),
      "ser-sur-vs-1repl" -> (true, 56), "ser-sur-vs-3repl" -> (false, 39), "ser-ctl-repl" -> (true, 56),
      "subst-sur-vs-1repl" -> (true, 238), "subst-sur-vs-3repl" -> (false, 221), "subst-ctl-repl" -> (true, 238),
      "ser-bare-sur-vs-1repl" -> (true, 53), "ser-bare-sur-vs-3repl" -> (false, 36), "ser-bare-ctl" -> (true, 53),
      "ser-dto-string-sur" -> (true, 194), "ser-dto-string-ctl" -> (true, 194),
      "ser-1item-ctl" -> (true, 54), "ser-2items-ctl" -> (true, 58), "ser-empty-coll" -> (true, 50),
      "opt-sur-vs-1repl" -> (true, 32), "opt-sur-vs-3repl" -> (false, 15), "opt-ctl" -> (true, 32))
    B.Rows.foreach(r => assertEquals(got(r.op, r.id), want(r.id), s"${r.op} ${r.id}"))
    assertEquals(B.Rows6.map(_.id).toSet, want.keySet)
  }

  test("the costs the rule implies: the length bail, the chunk, the writer's charge per string") {
    assertEquals(got(B.Op6, "sur-vs-1repl")._2 - got(B.Op6, "sur-vs-3repl")._2, 17L)
    assertEquals(got(B.Op6, "sur40-same")._2, got(B.Op6, "ctl-true")._2, "40 decoded units cost one chunk")
    assertEquals(got(B.Op6, "ctl-repl120-same")._2 - got(B.Op6, "ctl-repl40-same")._2, 2L, "120 units: a second chunk")
    assertEquals(got(B.Op6, "ser-1item-ctl")._2 - got(B.Op6, "ser-empty-coll")._2, 4L)
    assertEquals(got(B.Op6, "ser-2items-ctl")._2 - got(B.Op6, "ser-1item-ctl")._2, 4L)
  }

  test("envelope + write staging files") {
    B.Ops.foreach(op => assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source)))
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    B.Ops.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
