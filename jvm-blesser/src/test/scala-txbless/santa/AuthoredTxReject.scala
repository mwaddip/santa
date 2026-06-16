package santa

// AuthoredTxReject — the transaction tier's Track-B reject arm (contract §6 authored). Two
// no-re-sign, param-driven BOUNDARY PAIRS over a captured seed ALL impls accept: the block cost
// ceiling (maxBlockCost) and the dust floor (minValuePerByte). Each file is an accept control (the
// tx is valid in every OTHER respect) + the one-step reject. The signed tx and its boxes are the
// REAL capture — only one context parameter moves — so a lenient impl that skips the rule cleanly
// ACCEPTS the reject entry (over-accept = coal), not EOFs.
//
// SEED CHOICE: a PLAIN tx every conformer accepts at launch params (multi-input-3-402800), so the
// accept controls are clean and the reject entries actually test each impl's cost/dust enforcement.
// An exotic seed an impl already rejects (e.g. bigint-downcast's tree-version bug on develop) would
// red the accept controls for the wrong reason and false-green the rejects.
//
// SEED-AGNOSTIC BOUNDARIES: the cost ceiling is the seed's own validation cost (accept@C / reject@C-1);
// the dust floor is the binary-searched accept→reject flip in minValuePerByte. Re-basing = change
// BaseSeed. Unlike CapturedTxFull (FAIL-LOUD on valid:false), this asserts the EXPECTED verdict per
// entry; a wrong verdict fails the bless. Reuses the base seed's bytes/boxes/context from the
// committed captured vector — no node, no re-sign.

import io.circe.Json
import io.circe.parser.parse
import santa.runner.TxEngine

object AuthoredTxReject {
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-validateStateful"
  private val ErgoTreeV = 3
  private val BaseSeed  = "multi-input-3-402800"

  /** The captured seed's own (launch) economic params; authored entries move exactly one. */
  private val Launch: Map[String, Int] = Map(
    "maxBlockCost" -> 1000000, "storageFeeFactor" -> 1250000, "minValuePerByte" -> 360,
    "inputCost" -> 2000, "dataInputCost" -> 100, "outputCost" -> 100, "tokenAccessCost" -> 100)

  private def paramsJson(over: (String, Int)*): Json =
    Json.obj((Launch ++ over.toMap).toSeq.map { case (k, v) => k -> Json.fromInt(v) }: _*)

  private def slurp(p: String): String = { val s = scala.io.Source.fromFile(p); try s.mkString finally s.close() }

  /** Base seed's signed tx + boxes + real provided context, from the committed captured vector. */
  private lazy val base: (String, List[String], List[String], List[String], Json, Int) = {
    val e = parse(slurp(s"../vectors/transaction/v6/captured/$BaseSeed.json"))
      .fold(er => sys.error(s"$BaseSeed: $er"), identity).hcursor.downField("entries").downArray
    (e.get[String]("tx_bytes_hex").toOption.getOrElse(sys.error("tx_bytes_hex")),
     e.downField("input_boxes_hex").as[List[String]].getOrElse(Nil),
     e.downField("data_input_boxes_hex").as[List[String]].getOrElse(Nil),
     e.downField("headers_hex").as[List[String]].getOrElse(Nil),
     e.downField("preHeader").focus.getOrElse(sys.error("preHeader")),
     e.downField("context").get[Int]("height").toOption.getOrElse(sys.error("height")))
  }

  /** Validate the base seed under the given economic params. */
  private def validateUnder(params: Json): TxEngine.Verdict = {
    val (txHex, inHex, dtHex, hdrHex, preH, _) = base
    TxEngine.validateBytes(txHex, inHex, dtHex, hdrHex, preH, params)
  }

  /** The block-cost ceiling the seed exactly fits = its validation cost at launch params. */
  private lazy val baseCost: Int = validateUnder(paramsJson()).cost
    .getOrElse(sys.error(s"$BaseSeed: launch validation produced no cost")).toInt

  /** Largest minValuePerByte at which every output still clears the dust floor (so +1 dusts).
    * Binary-search the monotone accept→reject flip (minimalErgoAmount grows with minValuePerByte). */
  private lazy val dustFloor: Int = {
    def accepts(mvpb: Int): Boolean = validateUnder(paramsJson("minValuePerByte" -> mvpb)).valid
    require(accepts(Launch("minValuePerByte")), s"$BaseSeed: must accept at launch minValuePerByte")
    var hi = Launch("minValuePerByte") * 2
    while (accepts(hi) && hi <= 1000000000) hi *= 2
    require(!accepts(hi), s"$BaseSeed: no dust boundary below minValuePerByte=$hi (outputs too large?)")
    var lo = Launch("minValuePerByte")
    while (hi - lo > 1) { val mid = lo + (hi - lo) / 2; if (accepts(mid)) lo = mid else hi = mid }
    lo
  }

  /** One authored entry: validate the base seed under `params`, assert the verdict matches
    * `wantValid` (fail-loud), bake the oracle's cost (accept) or reason (reject). */
  private def entry(name: String, source: String, params: Json, wantValid: Boolean): Json = {
    val (txHex, inHex, dtHex, hdrHex, preH, height) = base
    val v = TxEngine.validateBytes(txHex, inHex, dtHex, hdrHex, preH, params)
    if (v.valid != wantValid)
      sys.error(s"AuthoredTxReject[$name]: want valid=$wantValid got valid=${v.valid} reason=${v.reason.getOrElse("")}")
    val reason = if (wantValid) Json.Null
                 else Json.fromString(v.reason.getOrElse(sys.error(s"$name: reject without a reason")))
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString(source),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(inHex.map(Json.fromString): _*),
      "data_input_boxes_hex" -> Json.arr(dtHex.map(Json.fromString): _*),
      "headers_hex"          -> Json.arr(hdrHex.map(Json.fromString): _*),
      "preHeader"            -> preH,
      "parameters"           -> params,
      "context"              -> Json.obj("height" -> Json.fromInt(height)),
      "version"              -> Json.obj("activated" -> Json.fromInt(ErgoTreeV), "ergoTree" -> Json.fromInt(ErgoTreeV)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> reason))
  }

  private def envelope(op: String, es: Json*): Json = Json.obj(
    "schema" -> Json.fromString("santa-transaction/v1"),
    "op" -> Json.fromString(op),
    "blessed_by" -> Json.fromString(BlessedBy),
    "entries" -> Json.arr(es: _*))

  val CostLimitPath = "transaction/v6/authored/cost-limit-boundary.json"
  val DustPath      = "transaction/v6/authored/min-value-dust-boundary.json"

  def blessAll(): Seq[(String, Json)] = Seq(
    CostLimitPath -> envelope("tx:authored:cost-limit-boundary",
      entry("cost-limit-accept", "santa:authored-tx-cost-limit:accept", paramsJson("maxBlockCost" -> baseCost),       wantValid = true),
      entry("cost-limit-reject", "santa:authored-tx-cost-limit:reject", paramsJson("maxBlockCost" -> (baseCost - 1)), wantValid = false)),
    DustPath -> envelope("tx:authored:min-value-dust-boundary",
      entry("min-value-dust-accept", "santa:authored-tx-min-value-dust:accept", paramsJson("minValuePerByte" -> dustFloor),       wantValid = true),
      entry("min-value-dust-reject", "santa:authored-tx-min-value-dust:reject", paramsJson("minValuePerByte" -> (dustFloor + 1)), wantValid = false)))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
