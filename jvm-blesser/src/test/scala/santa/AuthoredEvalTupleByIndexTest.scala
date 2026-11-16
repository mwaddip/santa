package santa

import io.circe.Json

/** Anchors the ByIndex-over-a-tuple family: each label's rule as the oracle answers it (a pair throws
  * ClassCastException from Tuple2 to Coll; three or more items read the item at cost 40, 60 with the index from a
  * variable; out of range ArrayIndexOutOfBounds; the default; `==` over Any throws; SizeOf), every row read at parse,
  * every slot answering the same but for the in-range default's cost, and the files' shape. */
class AuthoredEvalTupleByIndexTest extends munit.FunSuite {
  private val B = AuthoredEvalTupleByIndex
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def ver(e: Json, f: String): Int = e.hcursor.downField("version").get[Int](f).toOption.getOrElse(fail(f))

  test("43 rows; two files: v5 (tree v0, activated 2) and _v6 (tree v0 and v3, activated 3)") {
    assertEquals(B.Rows.size, 43)
    assertEquals(B.Rows.map(_.id).distinct.size, 43)
    assertEquals(v.keySet, Set("ByIndex.tuple_item", "ByIndex.tuple_item_v6"))
    assertEquals(entries("ByIndex.tuple_item").size, 43)
    assertEquals(entries("ByIndex.tuple_item_v6").size, 86)
    entries("ByIndex.tuple_item").foreach(e => assertEquals((ver(e, "ergoTree"), ver(e, "activated")), (0, 2)))
    assertEquals(entries("ByIndex.tuple_item_v6").map(e => (ver(e, "ergoTree"), ver(e, "activated"))).toSet,
      Set((0, 3), (3, 3)))
  }

  test("each label's rule, as the oracle answers it, at every slot") {
    B.Rows.foreach(r => B.Slots.foreach { case (tv, a) =>
      val o = B.outcome(r, tv, a)
      val at = s"${r.id} t$tv/a$a: ${B.said(o)}"
      def throws(cls: String, msg: String) = o match {
        case B.ThrowsAtEval(t) =>
          val c = B.innermost(t)
          assertEquals(c.getClass.getName, cls, at); assert(c.getMessage.contains(msg), at)
        case _ => fail(at)
      }
      def answers(text: String, cost: Long) = o match {
        case B.Answer(value, c) => assertEquals((AuthoredEvalPreActivation.text(value), c), (text, cost), at)
        case _ => fail(at)
      }
      val label = r.label.takeWhile(_ != ':')
      label match {
        case "PAIR" => throws("java.lang.ClassCastException", "scala.Tuple2 cannot be cast to class sigma.Coll")
        case "OUT OF RANGE" => throws("java.lang.ArrayIndexOutOfBoundsException", "out of bounds for length")
        case "ANY" => throws("java.lang.RuntimeException", "Unknown type SAny")
        case "ITEM" => o match {
          case B.Answer(_, c) => assertEquals(c, if (r.id.startsWith("V")) 60L else 40L, at)
          case _ => fail(at)
        }
        case "DEFAULT" => r.id match {
          case "D1" | "D3" => answers("7", 45)
          case "D2"        => answers("1", if (tv == 3) 40L else 45L)
          case "D4"        => throws("java.lang.ClassCastException", "scala.Tuple2")
        }
        case "SIZE" => r.id match {
          case "S3" => answers("3", 19); case "S5" => answers("5", 19)
          case "S2" => throws("java.lang.ClassCastException", "scala.Tuple2")
        }
      }
    })
  }

  test("the items read: g, true, 1L, the Ints, and a pair read as an item") {
    def text(id: String) = B.Rows.find(_.id == id).map(r => B.outcome(r, 3, 3)).collect {
      case B.Answer(value, _) => AuthoredEvalPreActivation.text(value) }.getOrElse(fail(id))
    assertEquals(Seq("G0", "G1", "G2", "T0", "T1", "T2", "N0", "Q3", "F4", "V1").map(text),
      Seq("<point>", "true", "1", "1", "true", "1", "(1,2)", "2", "3", "true"))
  }

  test("envelope + write staging files") {
    v.values.foreach(j => assertEquals(j.hcursor.get[String]("blessed_by").toOption, Some(B.Stamp)))
    v.values.flatMap(_.hcursor.downField("entries").as[List[Json]].toOption.get).foreach { e =>
      val s = e.hcursor.get[String]("script").toOption.get
      assert(!s.matches(".*@[0-9a-f]{6,}.*"), s"an identity hash in $s")
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored", "tuple-by-index")
    B.writeVectors(outDir)
    assertEquals(java.nio.file.Files.list(outDir).count(), 2L)
  }
}
