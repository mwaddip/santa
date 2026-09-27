package santa

// CapturedTxMainnetRent — a stratified sample of REAL mainnet storage-rent spends, blessed as captured
// transaction-tier vectors: the real-history gate for storage-rent ports.
//
// Input: `src/test/resources/mainnet-rent/captures.json`, built off-line from a mainnet node + its indexer
// (every byte string verified against its id: tx signing message -> tx id, box bytes -> box id, header
// bytes -> header id), carrying for each tx its bytes, all input and data-input boxes, the 10 parent
// headers (NEWEST-first), the block's preHeader and the voting-epoch parameter table in effect.
//
// A captured tx is chain history, so the oracle MUST accept it: a rejection fails the bless loudly. The
// blesser also re-derives, from the bytes alone, which inputs take the rent path (ErgoInterpreter.scala:
// 72-80: signed age >= StoragePeriod, empty proof, var 127 a Short indexing an output) and requires the
// capture's recorded rent inputs to match, so a stratum label cannot drift from what the JVM does.

import io.circe.Json
import io.circe.parser.parse
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{ErgoBox, ErgoLikeTransaction}
import santa.runner.TxEngine

import RentFixtures.{StorageIndexVarId, StoragePeriod}

object CapturedTxMainnetRent {
  val BlessedBy    = "jvm:ergo-core-6.0.6-validateStateful"
  val CapturesPath = "src/test/resources/mainnet-rent/captures.json"

  final case class Capture(j: Json) {
    private val c = j.hcursor
    private def str(k: String): String = c.get[String](k).fold(e => sys.error(s"capture.$k: $e"), identity)
    private def strs(k: String): List[String] = c.downField(k).as[List[String]].fold(e => sys.error(s"capture.$k: $e"), identity)
    val stratum: String          = str("stratum")
    val strata: List[String]     = c.downField("strata").as[List[String]].getOrElse(List(stratum))
    val height: Int              = c.get[Int]("height").fold(e => sys.error(s"capture.height: $e"), identity)
    val txId: String             = str("txId")
    val note: String             = c.get[String]("note").getOrElse("")
    val rentInputs: List[Int]    = c.downField("rentInputs").as[List[Int]].fold(e => sys.error(s"rentInputs: $e"), identity)
    val activated: Int           = c.get[Int]("activated").fold(e => sys.error(s"activated: $e"), identity)
    val txHex: String            = str("tx_bytes_hex")
    val inputBoxes: List[String] = strs("input_boxes_hex")
    val dataBoxes: List[String]  = strs("data_input_boxes_hex")
    val headers: List[String]    = strs("headers_hex")
    val preHeader: Json          = c.downField("preHeader").focus.get
    val parameters: Json         = c.downField("parameters").focus.get
    def name: String = s"mainnet-rent-${stratum.toLowerCase}-h$height-${txId.take(8)}"
  }

  def captures(): Seq[Capture] = {
    val s = scala.io.Source.fromFile(CapturesPath)
    val raw = try s.mkString finally s.close()
    parse(raw).fold(e => sys.error(s"$CapturesPath: $e"), identity).asArray.get.map(Capture)
  }

  /** The inputs the JVM sends down the rent path, re-derived from the bytes (ErgoInterpreter.scala:72-80). */
  def rentPathInputs(cap: Capture): List[Int] = VersionContext.withVersions(3, 3) {
    val tx    = ErgoLikeTransaction.serializer.parse(SigmaSerializer.startReader(Base16.decode(cap.txHex).get))
    val boxes = cap.inputBoxes.map(h => ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(h).get)))
    require(tx.id == cap.txId, s"${cap.txId}: tx bytes parse to id ${tx.id}")
    require(boxes.map(b => scorex.util.bytesToId(b.id)) == tx.inputs.map(i => scorex.util.bytesToId(i.boxId)),
      s"${cap.txId}: input boxes do not match the tx's inputs")
    tx.inputs.indices.toList.filter { i =>
      val sp  = tx.inputs(i).spendingProof
      val age = cap.height - boxes(i).creationHeight // Int arithmetic, as the JVM
      age >= StoragePeriod && sp.proof.isEmpty && (sp.extension.values.get(StorageIndexVarId) match {
        case Some(v) => v.value match {
          case idx: Short => idx >= 0 && idx < tx.outputCandidates.size
          case _          => false
        }
        case None => false
      })
    }
  }

  private def entry(cap: Capture): Json = {
    val derived = rentPathInputs(cap)
    require(derived == cap.rentInputs,
      s"${cap.name}: the JVM's rent-path inputs are $derived, the capture recorded ${cap.rentInputs}")
    val v = TxEngine.validateBytes(cap.txHex, cap.inputBoxes, cap.dataBoxes, cap.headers, cap.preHeader, cap.parameters)
    if (!v.valid) sys.error(s"CapturedTxMainnetRent[${cap.name}]: oracle REJECTED a mainnet tx — ${v.reason.getOrElse("?")}")
    val cost = v.cost.getOrElse(sys.error(s"${cap.name}: valid but no cost"))
    Json.obj(
      "name"                 -> Json.fromString(cap.name),
      "source"               -> Json.fromString(s"mainnet:rent-${cap.stratum.toLowerCase}@${cap.height}:${cap.txId}"),
      "description"          -> Json.fromString(
        s"Mainnet tx ${cap.txId} at height ${cap.height}; strata ${cap.strata.mkString(", ")}; rent-path inputs " +
        s"${derived.mkString("[", ", ", "]")} of ${cap.inputBoxes.size}. ${cap.note}".trim),
      "tx_bytes_hex"         -> Json.fromString(cap.txHex),
      "input_boxes_hex"      -> Json.arr(cap.inputBoxes.map(Json.fromString): _*),
      "data_input_boxes_hex" -> Json.arr(cap.dataBoxes.map(Json.fromString): _*),
      "headers_hex"          -> Json.arr(cap.headers.map(Json.fromString): _*),
      "preHeader"            -> cap.preHeader,
      "parameters"           -> cap.parameters,
      "context"              -> Json.obj("height" -> Json.fromInt(cap.height)),
      "version"              -> Json.obj("activated" -> Json.fromInt(cap.activated), "ergoTree" -> Json.fromInt(cap.activated)),
      "expected"             -> Json.obj("valid" -> Json.fromBoolean(true), "cost" -> Json.fromLong(cost), "reason" -> Json.Null))
  }

  /** One file per (protocol version, stratum): `transaction/v5|v6/captured/mainnet-rent-<stratum>.json`. */
  def blessAll(): Seq[(String, Json)] =
    captures().groupBy(c => (c.activated, c.stratum)).toSeq.sortBy(_._1).map { case ((act, stratum), caps) =>
      val dir = act match { case 2 => "v5"; case 3 => "v6"; case o => sys.error(s"unexpected activated version $o") }
      val slug = s"mainnet-rent-${stratum.toLowerCase}"
      s"transaction/$dir/captured/$slug.json" -> Json.obj(
        "schema"     -> Json.fromString("santa-transaction/v1"),
        "op"         -> Json.fromString(s"tx:captured:$slug"),
        "blessed_by" -> Json.fromString(BlessedBy),
        "entries"    -> Json.arr(caps.sortBy(_.height).map(entry): _*))
    }

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
