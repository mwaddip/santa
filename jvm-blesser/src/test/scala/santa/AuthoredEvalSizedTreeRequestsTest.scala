package santa

import io.circe.Json

/** Anchors the eval vectors built for ergots' sized-tree requests and sigma-rust's source findings: substConstants on
  * a size-flagged template whose declared size is above u32, at u32's maximum, or true; on a template whose header has
  * bit 5; and on templates whose constants count wraps negative. extract() re-derives each outcome through EvalCore;
  * this pins the verdicts and the substituted bytes, hand-derived. A failure means sigma-state changed how
  * substConstants reads a template or writes the result. */
class AuthoredEvalSizedTreeRequestsTest extends munit.FunSuite {
  import AuthoredEvalSizedTreeRequests._
  private lazy val vectors = extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def errored(e: Json): Boolean = e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def valueHex(e: Json): String =
    e.hcursor.downField("expected").downField("value").downField("items").as[List[Json]].toOption.get
      .map(_.hcursor.get[Int]("value").toOption.get).map(v => f"${v & 0xff}%02x").mkString

  test("the template's declared size is read as a u32 and otherwise ignored; the result gets the true size") {
    val es = entries(OpSubstSize)
    assertEquals(es.map(errored), List(true, false, false))
    // constant 0 SigmaProp(true) (08 d3) becomes SigmaProp(false) (08 d2); header 18, size 05 = |01 08 d2 73 00|
    assertEquals(valueHex(es(1)), "18" + "05" + "01" + "08d2" + "7300")
    assertEquals(valueHex(es(2)), "18" + "05" + "01" + "08d2" + "7300")
  }

  test("a template's header byte is written back as read; a constants count that wraps negative means none") {
    val es = entries(OpSubstForms)
    assertEquals(es.map(errored), List(false, false, false))
    assertEquals(valueHex(es(0)), "38" + "05" + "01" + "08d2" + "7300") // header bit 5 kept, constant 0 replaced
    assertEquals(valueHex(es(1)), "18" + "03" + "00" + "08d3")          // no constants, nothing replaced; size 3
    assertEquals(valueHex(es(2)), "10" + "00" + "08d3")                 // the same, unsized: no size
  }

  test("envelopes: santa-eval, v6 activated, the tree is the spec vector's substConstants function") {
    assertEquals(vectors.keySet, Set(OpSubstSize, OpSubstForms))
    vectors.foreach { case (op, envJson) =>
      val env = envJson.hcursor
      assert(env.get[String]("source").toOption.exists(_.startsWith("santa:")))
      assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
      entries(op).foreach { e =>
        assertEquals(e.hcursor.get[String]("tree_bytes_hex").toOption, Some(SubstTree))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    writeVectors(outDir)
    vectors.keys.foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"${SpecExtract.slug(op)}.json")), s"$op staging not written"))
  }
}
