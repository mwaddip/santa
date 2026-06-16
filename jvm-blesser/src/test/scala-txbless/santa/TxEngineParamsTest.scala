package santa

import io.circe.Json
import io.circe.parser.parse
import santa.runner.TxEngine

/** The engine now HONORS the vector's `parameters` (was `val _ = parameters`). Reject-arm
  * precondition: moving maxBlockCost / minValuePerByte flips the verdict; and the 8 committed
  * captured seeds re-validate at their OWN params with UNCHANGED cost (no drift). cwd = jvm-blesser/. */
class TxEngineParamsTest extends munit.FunSuite {
  private def slurp(p: String): String = { val s = scala.io.Source.fromFile(p); try s.mkString finally s.close() }

  private def entryOf(slug: String): io.circe.ACursor =
    parse(slurp(s"../vectors/transaction/v6/captured/$slug.json"))
      .fold(e => sys.error(s"$slug: $e"), identity).hcursor.downField("entries").downArray

  private def validateWith(e: io.circe.ACursor, params: Json): TxEngine.Verdict =
    TxEngine.validateBytes(
      e.get[String]("tx_bytes_hex").toOption.get,
      e.downField("input_boxes_hex").as[List[String]].getOrElse(Nil),
      e.downField("data_input_boxes_hex").as[List[String]].getOrElse(Nil),
      e.downField("headers_hex").as[List[String]].getOrElse(Nil),
      e.downField("preHeader").focus.get, params)

  private val launch: Json = Json.obj(
    "maxBlockCost" -> Json.fromInt(1000000), "storageFeeFactor" -> Json.fromInt(1250000),
    "minValuePerByte" -> Json.fromInt(360), "inputCost" -> Json.fromInt(2000),
    "dataInputCost" -> Json.fromInt(100), "outputCost" -> Json.fromInt(100), "tokenAccessCost" -> Json.fromInt(100))
  private def moved(k: String, v: Int): Json = launch.deepMerge(Json.obj(k -> Json.fromInt(v)))

  test("honors maxBlockCost: 14846 accept (cost 14846), 14845 reject") {
    val e = entryOf("bigint-downcast-2666")
    val acc = validateWith(e, moved("maxBlockCost", 14846))
    assert(acc.valid, "maxBlockCost=14846 must accept"); assertEquals(acc.cost, Some(14846L))
    assert(!validateWith(e, moved("maxBlockCost", 14845)).valid, "maxBlockCost=14845 must REJECT (params honored)")
  }

  test("honors minValuePerByte: 6896 accept, 6897 reject") {
    val e = entryOf("bigint-downcast-2666")
    assert(validateWith(e, moved("minValuePerByte", 6896)).valid, "mvpb=6896 must accept")
    assert(!validateWith(e, moved("minValuePerByte", 6897)).valid, "mvpb=6897 must REJECT")
  }

  test("no drift: 8 captured seeds re-validate at committed params, cost unchanged") {
    Seq("getvarfrominput-92847","multi-input-10-402900","multi-input-3-402800","bigint-downcast-2666",
        "powhit-return-type-28474","deserialize-context-111927","atleast-degenerate-bound-184137","order-ext-224312"
    ).foreach { slug =>
      val e = entryOf(slug)
      val v = validateWith(e, e.downField("parameters").focus.get)
      assert(v.valid, s"$slug must still accept")
      assertEquals(v.cost, e.downField("expected").get[Long]("cost").toOption, s"$slug cost must be unchanged")
    }
  }
}
