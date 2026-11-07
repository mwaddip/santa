package santa

import io.circe.Json
import sigma.ast._

/** Anchors both arms of the SigmaProp method-id batch, and the dispatch rule they rest on.
  *
  * The rule — a method evaluates through a MethodCall only if its cost kind is a `FixedCost` or it
  * has a `<name>_eval` handler — is asserted here against a RUNTIME enumeration of every method
  * container, not against a source reading. If sigma-state adds a handler or changes a cost kind,
  * the predicted set moves and this test says so. */
class AuthoredSigmaPropMethodCallTest extends munit.FunSuite {
  import AuthoredSigmaPropMethodCall._

  private lazy val evalVectors = extractEval()
  private lazy val wireVectors = extractWire()

  private def entries(v: Map[String, Json], op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse("?")
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption

  test("eval: both PropertyCalls reject, the dedicated node accepts at cost 115") {
    val es = entries(evalVectors, OpEval)
    assertEquals(es.map(e => (name(e), errored(e), cost(e))), List(
      ("propbytes-propertycall-errored#0", true, None),
      ("isproven-propertycall-errored#1", true, None),
      ("propbytes-dedicated-node-accept#2", false, Some(115L))))
  }

  test("wire: 5 entries, all identity round-trips") {
    val es = entries(wireVectors, OpWire)
    assertEquals(es.map(name), List(
      "propBytes-v3-sized#0", "isProven-v3-sized#1",
      "propBytes-v0-unsized#2", "isProven-v0-unsized#3",
      "propBytes-v0-sized#4"))
    es.foreach { e =>
      assertEquals(e.hcursor.get[String]("kind").toOption, Some("ErgoTree"), name(e))
      assert(e.hcursor.downField("expected_bytes_hex").focus.isEmpty,
        s"${name(e)}: an identity entry must carry no expected_bytes_hex")
    }
  }

  test("wire: the size bit is forced above tree version 0, optional at version 0") {
    assertEquals(treeHex(callPropBytes, V3, sized = false), treeHex(callPropBytes, V3, sized = true),
      "at v3 the two requests must collapse to one encoding")
    assertNotEquals(treeHex(callPropBytes, V0, sized = false), treeHex(callPropBytes, V0, sized = true),
      "at v0 the size flag must be optional, or there is no reject arm")
  }

  /** The exhaustive form of the rule, re-derived at runtime. 199 methods at 6.0.7, of which 38 are
    * neither FixedCost nor have a handler: 30 numeric conversions, SCollection 4-9, and the two
    * this file vectors. A change in that count means the parse-then-fail surface moved. */
  test("the dispatch rule over every method container, enumerated at runtime") {
    import scala.util.Try
    import sigma.VersionContext
    val containers: Seq[MethodsContainer] = Seq(
      SByteMethods, SShortMethods, SIntMethods, SLongMethods, SBigIntMethods,
      SBooleanMethods, SStringMethods, SGroupElementMethods, SSigmaPropMethods,
      SBoxMethods, SAvlTreeMethods, SHeaderMethods, SPreHeaderMethods,
      SGlobalMethods, SContextMethods, SCollectionMethods, SOptionMethods,
      STupleMethods, SUnitMethods, SAnyMethods, SUnsignedBigIntMethods)
    val rows = VersionContext.withVersions(V3, V3) {
      containers.flatMap { c =>
        Try(c.methods).getOrElse(Nil).map { m =>
          val ck = Try(m.costKind).toOption.flatMap(Option(_)) // costKind can be a plain null
          (c.ownerType.toString, m.name, m.methodId,
            ck.exists(_.isInstanceOf[FixedCost]), Try(m.genericMethod.evalMethod).isSuccess)
        }
      }
    }
    assertEquals(rows.size, 199, "the method surface at 6.0.7")
    val failing = rows.filter { case (_, _, _, isFixed, handler) => !isFixed && !handler }
    assertEquals(failing.size, 38, "methods that parse and then fail at eval")

    val numeric = Set("toByte", "toShort", "toInt", "toLong", "toBigInt")
    assertEquals(failing.count { case (_, n, id, _, _) => numeric(n) && id >= 1 && id <= 5 }, 30,
      "the numeric conversions on six containers")
    assertEquals(failing.count { case (o, _, _, _, _) => o.contains("SCollection") }, 6,
      "SCollection exists 4, fold 5, forall 6, slice 7, filter 8, append 9")
    assertEquals(
      failing.collect { case (o, n, id, _, _) if o.contains("SigmaProp") => (n, id) }.sortBy(_._2),
      Seq(("propBytes", 1.toByte), ("isProven", 2.toByte)),
      "the two this file vectors")
  }

  test("envelopes: the 6.0.7 blessing") {
    assertEquals(evalVectors(OpEval).hcursor.get[String]("schema").toOption, Some("santa-eval/v2"))
    assertEquals(evalVectors(OpEval).hcursor.get[String]("blessed_by").toOption,
      Some("jvm:sigma-state-6.0.7"))
    assertEquals(wireVectors(OpWire).hcursor.get[String]("schema").toOption, Some("santa-wire/v1"))
    assertEquals(wireVectors(OpWire).hcursor.get[String]("blessed_by").toOption,
      Some("jvm:sigma-state-6.0.7"))
    entries(evalVectors, OpEval).foreach(e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3), name(e)))
  }

  test("write staging files") {
    val evalDir = java.nio.file.Paths.get("target", "eval-authored")
    val wireDir = java.nio.file.Paths.get("target", "wire-authored")
    writeVectors(evalDir, wireDir)
    assert(java.nio.file.Files.exists(evalDir.resolve(s"${SpecExtract.slug(OpEval)}.json")), "eval staging")
    assert(java.nio.file.Files.exists(wireDir.resolve(s"${SpecExtract.slug(OpWire)}.json")), "wire staging")
  }
}
