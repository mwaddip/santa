package santa

import io.circe.Json

/** Anchors the three checkType / element-type-gate eval families.
  *
  * `extract()` re-derives every outcome through EvalCore, so this test pins what the 6.0.7 oracle
  * said: which rows reject, which accept and with what value, and the exact serialized tree of
  * each row. The trees are built from AST nodes (they are ill-typed, so no compiler can emit
  * them), which means a change in sigma-state's serializer OR in how it type-checks an evaluated
  * value will fail here rather than silently re-bless. */
class AuthoredEvalCheckTypeGatesTest extends munit.FunSuite {
  import AuthoredEvalCheckTypeGates._
  private lazy val vectors = extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def tree(e: Json): String =
    e.hcursor.get[String]("tree_bytes_hex").toOption.getOrElse(fail("tree_bytes_hex"))
  private def boolValue(e: Json): Option[Boolean] =
    e.hcursor.downField("expected").downField("value").get[Boolean]("value").toOption
  private def cost(e: Json): Option[Long] =
    e.hcursor.downField("expected").get[Long]("cost").toOption
  private def etv(e: Json): Option[Int] =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption

  /** (name, errored, ergoTree version, tree bytes) as the 6.0.7 oracle blessed them. */
  private val Pinned: Map[String, List[(String, Boolean, Int, String)]] = Map(
    OpSites -> List(
      ("control-honest-if-accept#0", false, 3, "0b0a93950100040204040404"),
      ("valdef-errored#1", true, 3, "0b0dd801d601950100040205040101"),
      ("block-result-errored#2", true, 3, "0b1093d801d6010402950100040205040404"),
      ("eq-left-errored#3", true, 3, "0b0a93950100040205040404"),
      ("eq-right-errored#4", true, 3, "0b0a93040495010004020504"),
      ("neq-left-errored#5", true, 3, "0b0a94950100040205040406"),
      ("if-branch-errored#6", true, 3, "0b0f939501019501000402050404060404"),
      ("tuple-item0-errored#7", true, 3, "0b10938c8602950100040205040406010404"),
      ("tuple-item1-errored#8", true, 3, "0b10938c8602040695010004020504020404"),
      ("coll-item-errored#9", true, 3, "0b0e93b1830104950100040205040402"),
      ("lambda-arg-errored#10", true, 3, "0b0fdad901010401010195010004020504"),
      ("lambda-body-errored#11", true, 3, "0b1293dad9010104950100040205040104020404"),
      ("fold-zero-errored#12", true, 3, "0b1693b083000495010004020504d90101588c7201010404")),
    OpLazy -> List(
      ("byindex-v3-out-of-range-errored#0", true, 3, "0b1193b2830004040001950100040205040404"),
      ("byindex-v3-in-range-accept#1", false, 3, "0b1393b2830104040a04000195010004020504040a"),
      ("byindex-v2-in-range-errored#2", true, 2, "0a1393b2830104040a04000195010004020504040a"),
      ("optiongetorelse-v3-none-errored#3", true, 3, "0b0f93e5c6a70404950100040205040404"),
      ("optiongetorelse-v3-some-accept#4", false, 3, "0b0f93e5c6a7000595010005020404c1a7"),
      ("optiongetorelse-v2-some-errored#5", true, 2, "0a0f93e5c6a7000595010005020404c1a7"),
      ("optiongetorelse-v3-none-control-accept#6", false, 3, "0b0a93e5c6a70404040e040e"),
      ("selfr4-absent-isdefined-accept#7", false, 3, "0b0893e6c6a704040100")),
    OpGates -> List(
      ("exists-runtime-elem-accept#0", false, 3, "0b14ae95010083010404028301050504d90101050101"),
      ("forall-runtime-elem-accept#1", false, 3, "0b14af95010083010404028301050504d90101050101"),
      ("map-runtime-elem-accept#2", false, 3, "0b1893b1ad95010083010404028301050504d901010572010402"),
      ("filter-runtime-elem-accept#3", false, 3, "0b1893b1b595010083010404028301050504d901010501010402"),
      ("exists-node-elem-errored#4", true, 3, "0b14ae95010083010404028301050504d90101040101"),
      ("forall-node-elem-errored#5", true, 3, "0b14af95010083010404028301050504d90101040101"),
      ("map-node-elem-errored#6", true, 3, "0b1893b1ad95010083010404028301050504d901010472010402"),
      ("filter-node-elem-errored#7", true, 3, "0b1893b1b595010083010404028301050504d901010401010402"),
      ("exists-empty-accept#8", false, 3, "0b1593ae9501008301040402830005d901010401010100"),
      ("forall-empty-accept#9", false, 3, "0b12af9501008301040402830005d90101040101"),
      ("map-empty-accept#10", false, 3, "0b1693b1ad9501008301040402830005d901010472010400"),
      ("filter-empty-accept#11", false, 3, "0b1693b1b59501008301040402830005d901010401010400")))

  test("every row's verdict, ErgoTree version and serialized tree are the blessed ones") {
    Pinned.foreach { case (op, want) =>
      val got = entries(op).map { e =>
        (e.hcursor.get[String]("name").toOption.getOrElse("?"), errored(e), etv(e).getOrElse(-1), tree(e))
      }
      assertEquals(got, want, op)
    }
  }

  /** The eager rows are the SAME tree as their lazy control, one ErgoTree version lower — that is
    * the whole claim, so hold the bytes to it: only the header byte may differ. */
  test("each eager reject is its lazy control's tree with a lower version header") {
    val byName = entries(OpLazy).map(e =>
      e.hcursor.get[String]("name").toOption.getOrElse("?") -> tree(e)).toMap
    Seq("byindex-v3-in-range-accept#1" -> "byindex-v2-in-range-errored#2",
        "optiongetorelse-v3-some-accept#4" -> "optiongetorelse-v2-some-errored#5")
      .foreach { case (lazyName, eagerName) =>
        assertEquals(byName(eagerName).drop(2), byName(lazyName).drop(2), s"$eagerName body")
        assertEquals(byName(lazyName).take(2), "0b", lazyName)
        assertEquals(byName(eagerName).take(2), "0a", eagerName)
      }
  }

  test("every accept evaluates to Boolean true with a positive cost") {
    Seq(OpSites, OpLazy, OpGates).foreach { op =>
      entries(op).filterNot(errored).foreach { e =>
        assertEquals(boolValue(e), Some(true), s"$op ${tree(e)}")
        assert(cost(e).exists(_ > 0), s"$op cost ${tree(e)}")
      }
    }
  }

  test("envelope: the 6.0.7 blessing, activated 3 throughout") {
    Seq(OpSites, OpLazy, OpGates).foreach { op =>
      val env = vectors(op).hcursor
      assertEquals(env.get[String]("schema").toOption, Some("santa-eval/v2"), op)
      assertEquals(env.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"), op)
      assertEquals(env.get[String]("source").toOption, Some(Source), op)
      entries(op).foreach(e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3), op))
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    writeVectors(outDir)
    Seq(OpSites, OpLazy, OpGates).foreach(op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"${SpecExtract.slug(op)}.json")),
        s"staging not written for $op"))
  }
}
