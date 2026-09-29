package santa

import io.circe.Json

/** Anchors the eval vectors for a tuple that is not a pair at Value.checkType: a constant triple's value is a Coll
  * (Evaluation.toDslTuple), and isValueOfType throws for an STuple of arity other than 2 at EQ's operands, a ValDef and
  * a lambda's argument; the pair twins evaluate. extract() re-derives each outcome through EvalCore; this pins the
  * verdicts and the trees, hand-derived. A failure means sigma-state changed how it checks a tuple's value. */
class AuthoredEvalNonPairTupleTest extends munit.FunSuite {
  import AuthoredEvalNonPairTuple._
  private lazy val vectors = extract()

  private def entries: List[Json] =
    vectors(Op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def errored(e: Json): Boolean = e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def tree(e: Json): String = e.hcursor.get[String]("tree_bytes_hex").toOption.getOrElse(fail("tree"))
  private def boolValue(e: Json): Option[Boolean] =
    e.hcursor.downField("expected").downField("value").get[Boolean]("value").toOption

  private val C3 = "48" + "040404" + "020406" // the (Int, Int, Int) constant (1, 2, 3)
  private val C2 = "58" + "0204"             // the (Int, Int) constant (1, 2)
  private def v3(body: String): String = "0b" + "%02x".format(body.length / 2) + body

  test("a triple fails Value.checkType at EQ, a ValDef and a lambda's argument; the pair twins evaluate to true") {
    val es = entries
    val want: List[(String, Boolean)] = List(
      v3("93" + C3 + C3) -> true,                                      // (1, 2, 3) == (1, 2, 3)
      v3("93" + C2 + C2) -> false,                                     // (1, 2) == (1, 2)
      v3("d801" + "d601" + C3 + "93" + "8c720101" + "0402") -> true,   // { val t = (1, 2, 3); t._1 == 1 }
      v3("d801" + "d601" + C2 + "93" + "8c720101" + "0402") -> false,  // { val t = (1, 2); t._1 == 1 }
      v3("93" + "da" + "d90101" + "48040404" + "8c720101" + "01" + C3 + "0402") -> true, // ((t: (Int,Int,Int)) => t._1)(..) == 1
      v3("93" + "da" + "d90101" + "58" + "8c720101" + "01" + C2 + "0402") -> false)     // ((t: (Int, Int)) => t._1)(..) == 1
    assertEquals(es.map(errored), want.map(_._2))
    assertEquals(es.map(tree), want.map(_._1))
    es.filterNot(errored).foreach(e => assertEquals(boolValue(e), Some(true), tree(e)))
  }

  test("envelope: santa-eval, v6 activated, the 6.0.6 blessing") {
    val env = vectors(Op).hcursor
    assert(env.get[String]("source").toOption.exists(_.startsWith("santa:")))
    assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
    entries.foreach(e => assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3)))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${SpecExtract.slug(Op)}.json")), "staging not written")
  }
}
