package santa

// ─────────────────────────────────────────────────────────────────────────────
// CapturedTxFull — re-bless ALL 7 captured tx seeds into the BYTES-anchored
// santa-transaction/v1 shape: tx_bytes_hex + input/data box hex + the PROVIDED real
// context (headers_hex newest-first + preHeader + parameters), graded by
// TxEngine.validateBytes. Bytes are the consensus-unambiguous form (they preserve
// context-extension wire order, which a JSON object key-reorders) — so dasher and
// any bytes-first conformer ride them. JSON tx/boxes are dropped.
//
//   3 ergots seeds  — tx/boxes/context all from their stateful fixtures (hex).
//   4 original seeds — tx/boxes re-serialized from their committed JSON vectors;
//                      the 10 last headers + preHeader fetched from the testnet node
//                      (ergo-node-rust @ :9053) and re-serialized to bytes.
// parameters = the testnet launch table (what validateBytes uses; the fixtures carry
// exactly these). FAIL-LOUD on a non-accept (captured ⇒ chain-valid).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import io.circe.parser.parse
import scorex.util.encode.Base16

import sigma.VersionContext
import org.ergoplatform.ErgoBox
import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.history.header.{Header, HeaderSerializer}
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, ErgoTransactionSerializer}

object CapturedTxFull extends ApiCodecs {
  private val Node      = "http://127.0.0.1:9053"
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-validateStateful"
  private val ErgoTreeV = 3 // v6 scripts

  private val LaunchParams = Json.obj(
    "maxBlockCost" -> Json.fromInt(1000000), "storageFeeFactor" -> Json.fromInt(1250000),
    "minValuePerByte" -> Json.fromInt(360), "inputCost" -> Json.fromInt(2000),
    "dataInputCost" -> Json.fromInt(100), "outputCost" -> Json.fromInt(100),
    "tokenAccessCost" -> Json.fromInt(100))

  // (slug, findings-dir) — context+bytes come straight from the fixture.
  private val ErgotsSeeds = Seq(
    ("getvarfrominput-92847", "testnet-getvarfrominput"),
    ("multi-input-10-402900", "testnet-multi-input-10"),
    ("multi-input-3-402800",  "testnet-multi-input-3"))

  // (slug, height) — tx/boxes re-serialized from the committed vector; context from the node.
  private val OriginalSeeds = Seq(
    ("bigint-downcast-2666",           2666),
    ("powhit-return-type-28474",       28474),
    ("deserialize-context-111927",     111927),
    ("atleast-degenerate-bound-184137",184137))

  private def slurp(p: String): String = { val s = scala.io.Source.fromFile(p); try s.mkString finally s.close() }
  private def httpGet(url: String): String = { val s = scala.io.Source.fromURL(url); try s.mkString finally s.close() }
  private def jget(url: String): Json = parse(httpGet(url)).fold(e => sys.error(s"GET $url: $e"), identity)

  /** Fetch the header at `id`, re-serialize to faithful bytes (id round-trips). */
  private def headerHex(id: String): String = {
    val h = jget(s"$Node/blocks/$id/header").as[Header].fold(e => sys.error(s"Header $id: $e"), identity)
    Base16.encode(HeaderSerializer.toBytes(h))
  }

  /** From the node: the 10 last headers (newest-first) + the preHeader for the block at `height`. */
  private def fetchContext(height: Int): (Seq[String], Json) = {
    val hid = jget(s"$Node/blocks/at/$height").hcursor.downArray.as[String].fold(e => sys.error(s"at $height: $e"), identity)
    val h   = jget(s"$Node/blocks/$hid/header").hcursor
    val preHeader = Json.obj(
      "version"   -> Json.fromInt(h.get[Int]("version").toOption.get),
      "parentId"  -> Json.fromString(h.get[String]("parentId").toOption.get),
      "timestamp" -> Json.fromString(h.get[Long]("timestamp").toOption.get.toString),
      "nBits"     -> Json.fromLong(h.get[Long]("nBits").toOption.get),
      "height"    -> Json.fromInt(h.get[Int]("height").toOption.get),
      "minerPk"   -> Json.fromString(h.downField("powSolutions").get[String]("pk").toOption.get),
      "votes"     -> Json.fromString(h.get[String]("votes").toOption.getOrElse("000000")))
    var cur = h.get[String]("parentId").toOption.get
    val hexes = (1 to 10).map { _ =>
      val hex = headerHex(cur)
      cur = jget(s"$Node/blocks/$cur/header").hcursor.get[String]("parentId").toOption.get
      hex
    }
    (hexes, preHeader)
  }

  private def entry(slug: String, height: Int, txHex: String, inHex: Seq[String], dtHex: Seq[String],
                    headersHex: Seq[String], preHeader: Json): Json = {
    val v = santa.runner.TxEngine.validateBytes(txHex, inHex, dtHex, headersHex, preHeader, LaunchParams)
    if (!v.valid) sys.error(s"CapturedTxFull[$slug]: oracle REJECTED a captured tx — reason=${v.reason.getOrElse("?")}")
    val cost = v.cost.getOrElse(sys.error(s"[$slug] valid:true but no cost"))
    Json.obj(
      "name"                 -> Json.fromString(slug),
      "source"               -> Json.fromString(s"testnet:$slug@$height"),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(inHex.map(Json.fromString): _*),
      "data_input_boxes_hex" -> Json.arr(dtHex.map(Json.fromString): _*),
      "headers_hex"          -> Json.arr(headersHex.map(Json.fromString): _*),
      "preHeader"            -> preHeader,
      "parameters"           -> LaunchParams,
      "context"              -> Json.obj("height" -> Json.fromInt(height)),
      "version"              -> Json.obj("activated" -> Json.fromInt(ErgoTreeV), "ergoTree" -> Json.fromInt(ErgoTreeV)),
      "expected"             -> Json.obj("valid" -> Json.fromBoolean(true), "cost" -> Json.fromLong(cost), "reason" -> Json.Null))
  }

  private def envelope(slug: String, e: Json): (String, Json) = slug -> Json.obj(
    "schema" -> Json.fromString("santa-transaction/v1"), "op" -> Json.fromString(s"tx:captured:$slug"),
    "blessed_by" -> Json.fromString(BlessedBy), "entries" -> Json.arr(e))

  private def fromErgots(slug: String, dir: String): (String, Json) = {
    val c = parse(slurp(s"../docs/findings/$dir/ergots-stateful-fixture.json")).fold(e => sys.error(e.toString), identity).hcursor
    val height = c.get[Int]("height").toOption.get
    envelope(slug, entry(slug, height,
      c.get[String]("txBytesHex").toOption.get,
      c.downField("inputBoxesHex").as[List[String]].toOption.get,
      c.downField("dataInputBoxesHex").as[List[String]].getOrElse(Nil),
      c.downField("headersHex").as[List[String]].toOption.get,
      c.downField("preHeader").focus.get))
  }

  private def fromOriginal(slug: String, height: Int): (String, Json) = {
    val e0 = parse(slurp(s"../vectors/transaction/v6/captured/$slug.json")).fold(e => sys.error(e.toString), identity)
      .hcursor.downField("entries").downArray
    val (txHex, inHex, dtHex) = VersionContext.withVersions(ErgoTreeV.toByte, ErgoTreeV.toByte) {
      val tx = e0.downField("tx").focus.get.as[ErgoTransaction].fold(er => sys.error(s"$slug tx: $er"), identity)
      def boxHex(j: Json) = Base16.encode(ErgoBox.sigmaSerializer.toBytes(j.as[ErgoBox].fold(er => sys.error(s"$slug box: $er"), identity)))
      (Base16.encode(ErgoTransactionSerializer.toBytes(tx)),
        e0.downField("inputBoxes").values.getOrElse(Nil).toSeq.map(boxHex),
        e0.downField("dataInputBoxes").values.getOrElse(Nil).toSeq.map(boxHex))
    }
    val (headersHex, preHeader) = fetchContext(height)
    envelope(slug, entry(slug, height, txHex, inHex, dtHex, headersHex, preHeader))
  }

  def blessAll(): Seq[(String, Json)] =
    ErgotsSeeds.map { case (s, d) => fromErgots(s, d) } ++ OriginalSeeds.map { case (s, h) => fromOriginal(s, h) }

  def writeVectors(outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    blessAll().foreach { case (slug, env) =>
      java.nio.file.Files.write(outDir.resolve(s"$slug.json"), env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
