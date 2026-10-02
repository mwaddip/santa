package santa

// AuthoredTxBlockVersionSource — transaction-tier vectors for which block version activates scripts (sigma-rust's
// probe, 2026-10-02): the VOTED PARAMETERS' block version, or the header's? The JVM takes both the activated script
// version (ErgoContext: activated = (stateContext.blockVersion - 1).toByte, and stateContext.blockVersion =
// currentParameters.blockVersion) and the monotonic creation-height rule (ErgoTransaction.scala:379-384, gated on
// blockVersion > Header.HardeningVersion = 2) from the parameters; the header's version only feeds PoW, serialization
// and CONTEXT.preHeader. These vectors set the two apart: parameters.blockVersion (the new TxEngine field) differs
// from preHeader.version. The context is the storage-rent synthetic one (ten version-4 headers below H), its preHeader
// version and its parameters' block version overridden per entry. Every spent tree is a SigmaProp constant, read from
// the UTXO set outside any version context, so an impl that judges by the header's version diverges.

import io.circe.Json
import scorex.crypto.authds.ADKey
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BoolToSigmaProp, ByteConstant, Context, EQ, ErgoTree, MethodCall, SContextMethods, SPreHeaderMethods}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.interpreter.ProverResult
import org.ergoplatform.{DataInput, ErgoBox, ErgoLikeTransaction, Input}
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxBlockVersionSource {
  val Path = "transaction/any/authored/block-version-source.json"
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H
  private val PlainTree = "0008d3"
  private val ScriptCost     = 12105L
  private val UnverifiedCost = 12100L // above the max supported version 3: accepted unverified at the initial cost

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  /** The storage-rent preHeader with its version overridden (the header's version). */
  private def preHeaderAt(version: Int): Json =
    AuthoredTxStorageRent.preHeader.mapObject(_.add("version", Json.fromInt(version)))
  /** The storage-rent parameters with the voted block version set (the new field TxEngine reads). */
  private def paramsAt(blockVersion: Int): Json =
    AuthoredTxStorageRent.params.mapObject(_.add("blockVersion", Json.fromInt(blockVersion)))

  /** A plain sigmaProp(true) box of value V at `height`, its 00 08 d3 tree swapped for `tree`. */
  private def boxBytes(label: String, tree: String, height: Int): Array[Byte] = {
    val plain = hex(VersionContext.withVersions(3, 3) { ErgoBox.sigmaSerializer.toBytes(box(label, V, height)) })
    val at = hex(vlqU32(V)).length
    require(plain.substring(at).startsWith(PlainTree), s"unexpected box layout $plain")
    b(plain.take(at) + tree + plain.drop(at + PlainTree.length))
  }
  /** A box whose script is `sigmaProp(CONTEXT.preHeader.version == v)` — it reads the HEADER's version, not the
    * parameters'. A v3 tree (at most the activated 3, so verified). */
  private def versionScriptBox(label: String, v: Int): Array[Byte] = VersionContext.withVersions(3, 3) {
    val preHeader = MethodCall(Context, SContextMethods.preHeaderMethod, IndexedSeq.empty, Map.empty)
    val version   = MethodCall(preHeader, SPreHeaderMethods.versionMethod, IndexedSeq.empty, Map.empty)
    val root      = BoolToSigmaProp(EQ(version, ByteConstant(v.toByte)))
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, 3.toByte))
    ErgoBox.sigmaSerializer.toBytes(box(label, V, 1, ErgoTree.fromProposition(header, root)))
  }
  private def idOf(raw: Array[Byte]): ADKey = {
    val id = ADKey @@ (Blake2b256.hash(raw): Array[Byte])
    require(java.util.Arrays.equals(ErgoBoxSerializer.parseBytes(raw).id, id), s"box id of ${hex(raw)}")
    id
  }
  /** One input spending `raw` with no proof into one plain output at `outHeight`. */
  private def spendTx(raw: Array[Byte], outHeight: Int): Array[Byte] =
    txBytes(new ErgoLikeTransaction(IndexedSeq(Input(idOf(raw), ProverResult(Array.emptyByteArray, ext()))),
      IndexedSeq.empty[DataInput], IndexedSeq(candidate(V, outHeight))))

  private def above(tree: Int, activated: Int) = s"ErgoTree version $tree is higher than activated $activated"

  /** Bless one entry: parameters.blockVersion = `paramsBV`, preHeader.version = `preBV`, set apart. */
  private def entry(slug: String, description: String, raw: Array[Byte], outHeight: Int,
                    paramsBV: Int, preBV: Int, want: Either[String, Long]): Json = {
    val preHeader  = preHeaderAt(preBV)
    val parameters = paramsAt(paramsBV)
    val txRaw      = spendTx(raw, outHeight)
    val v = TxEngine.validateBytes(hex(txRaw), Seq(hex(raw)), Nil, AuthoredTxStorageRent.headersHex, preHeader, parameters)
    want match {
      case Right(cost) if cost >= 0 => require(v.valid && v.cost.contains(cost),
        s"$slug: want valid at cost $cost, got valid=${v.valid} cost=${v.cost} ${v.reason.getOrElse("")}")
      case Right(_)     => require(v.valid, // a MethodCall script: cost not known a priori, captured below
        s"$slug: want valid, got valid=${v.valid} ${v.reason.getOrElse("")}")
      case Left(reason) => require(!v.valid && v.reason.exists(_.contains(reason)),
        s"$slug: want invalid for '$reason', got valid=${v.valid} ${v.reason.getOrElse("")}")
    }
    Json.obj(
      "name"                 -> Json.fromString(slug),
      "source"               -> Json.fromString(s"santa:authored-tx-block-version-source:$slug"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(hex(txRaw)),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hex(raw))),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
      "preHeader"            -> preHeader,
      "parameters"           -> parameters,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      // The activated version the JVM uses = (parameters.blockVersion - 1).toByte, not the header's.
      "version"              -> Json.obj("activated" -> Json.fromInt((paramsBV - 1).toByte.toInt), "ergoTree" -> Json.fromInt(0)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  private val note = "The input spends a box of value 1000000000 with no proof. parameters.blockVersion is the voted " +
    "block version; preHeader.version is the header's — set apart here. The JVM judges the spend by the parameters'."

  private def entries: Seq[Json] = Seq(
    // 1. Activated script version = (parameters.blockVersion - 1).toByte, NOT (preHeader.version - 1).
    entry("activated-params4-header3-v3-accept",
      s"Parameters' block version 4 (activated 3), header version 3. The spent box's tree is v3 (0b 02 08 d3). Valid: " +
      s"activated is 3, from the parameters. By the header (3 -> activated 2) a v3 tree would reject. $note",
      boxBytes("santa:bvsrc:r1", "0b0208d3", 1), 1, 4, 3, Right(ScriptCost)),
    entry("activated-params4-header5-v4-reject",
      s"Parameters' block version 4 (activated 3), header version 5. A v4 SigmaProp(false) tree (0c 02 08 d2). Invalid: " +
      s"'${above(4, 3)}', from the parameters. By the header (5 -> activated 4) a v4 tree would be accepted unverified. $note",
      boxBytes("santa:bvsrc:r2", "0c0208d2", 1), 1, 4, 5, Left(above(4, 3))),
    entry("activated-params4-header0-v0-accept",
      s"Parameters' block version 4 (activated 3), header version 0. A v0 tree (00 08 d3). Valid: activated 3, from " +
      s"the parameters. By the header (0 -> activated -1, a signed byte) even a v0 tree would reject. $note",
      boxBytes("santa:bvsrc:r3", PlainTree, 1), 1, 4, 0, Right(ScriptCost)),
    entry("activated-params4-header200-v0-accept",
      s"Parameters' block version 4 (activated 3), header version 200. A v0 tree. Valid: activated 3. By the header " +
      s"(200 -> activated -57, a signed byte) even a v0 tree would reject. $note",
      boxBytes("santa:bvsrc:r4", PlainTree, 1), 1, 4, 200, Right(ScriptCost)),
    entry("activated-params5-header4-v4-unverified-accept",
      s"Parameters' block version 5 (activated 4, above the max supported 3), header version 4. A v4 SigmaProp(false) " +
      s"tree (0c 02 08 d2). Valid, 12100, accepted unverified: activated 4, from the parameters. By the header " +
      s"(4 -> activated 3) a v4 tree would reject. $note",
      boxBytes("santa:bvsrc:r5", "0c0208d2", 1), 1, 5, 4, Right(UnverifiedCost)),
    // 2. The monotonic creation-height rule is gated on the parameters' block version too (> HardeningVersion = 2).
    entry("height-params4-header2-reject",
      s"The monotonic creation-height rule. An output at height 1 below the input's height 5. Parameters' block " +
      s"version 4 (> HardeningVersion 2), header version 2: the rule applies, invalid ('Creation height of any output " +
      s"should be not less than ...'). By the header (2) there would be no rule. $note",
      boxBytes("santa:bvsrc:ra", PlainTree, 5), 1, 4, 2, Left("Creation height of any output should be not less than")),
    entry("height-params2-header4-accept",
      s"The twin: the same output-below-input transaction, parameters' block version 2 (<= HardeningVersion 2), header " +
      s"version 4. No rule yet, valid. By the header (4 > 2) the rule would apply and reject. $note",
      boxBytes("santa:bvsrc:rb", PlainTree, 5), 1, 2, 4, Right(ScriptCost)),
    // 3. The script sees the HEADER's version, where the spend-check uses the parameters'.
    entry("preheader-version-script-accept",
      s"Parameters' block version 4, header version 3. The spent box's script is sigmaProp(CONTEXT.preHeader.version " +
      s"== 3): valid, because the script reads the HEADER's version (3), not the parameters' (4) that judged its " +
      s"activation. An impl that feeds the parameters' version to CONTEXT.preHeader would reduce it to false. $note",
      versionScriptBox("santa:bvsrc:r6", 3), 1, 4, 3, Right(-1L)))

  private def envelope(es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString("tx:authored:block-version-source"),
    "blessed_by" -> Json.fromString(AuthoredTxStorageRent.BlessedBy),
    "entries"    -> Json.arr(es: _*))

  def blessAll(): Seq[(String, Json)] = Seq(Path -> envelope(entries))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
