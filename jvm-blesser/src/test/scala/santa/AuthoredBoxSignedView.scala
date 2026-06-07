package santa

import io.circe.Json
import scorex.util.bytesToId
import scorex.util.encode.Base16
import sigma.{Colls, VersionContext}
import sigma.ast._
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog
import sigma.serialization.SigmaSerializer

/** Box signed-view (u64→Long) adjudication vectors.
  *
  * The JVM parses box value and token amounts from the wire via unbounded VLQ-u64
  * reads and surfaces carriers in [2^63, 2^64) as NEGATIVE signed Longs at every eval
  * surface (value accessor / R0 / tokens), rather than rejecting them: 2^63 surfaces
  * as Long.MinValue, 2^64-1 as Long(-1). These vectors pin that surface at the
  * input-box hydration seam — the exact place an implementation that range-checks at
  * parse (e.g. a TryFrom-style bounded amount type) diverges: its box never hydrates.
  * A conformer red here is a genuine cross-impl divergence, not a harness artifact.
  */
object AuthoredBoxSignedView {

  val Op     = "Box.signed_view_u64"
  val Source = "santa:authored-box-signed-view"

  // Pinned target version: v5 (activated=2, ergoTree=2) — these are v5 semantics.
  private val V2: Byte = VersionContext.JitActivationVersion

  private val anyTree: ErgoTree =
    ErgoTree.fromSigmaBoolean(ProveDlog(CryptoConstants.dlogGroup.generator))

  private val treeHeaderV2: ErgoTree.HeaderType =
    ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, V2))

  private val txId = bytesToId(Array.fill(32)(0: Byte))

  // carrier-box value for ALL entries: must clear sigma-rust's BoxValue::MIN_RAW (10800)
  // so only the pinned field diverges
  private val CarrierValue = 1000000L

  private def boxWith(value: Long, tokenAmount: Option[Long]): org.ergoplatform.ErgoBox =
    tokenAmount match {
      case Some(a) =>
        val id = sigma.data.Digest32Coll @@ Colls.fromArray(Array.fill(32)(7: Byte))
        new org.ergoplatform.ErgoBox(
          value = value, ergoTree = anyTree, additionalTokens = Colls.fromItems((id, a)),
          additionalRegisters = Map.empty,
          transactionId = txId, index = 0.toShort, creationHeight = 0)
      case None =>
        new org.ergoplatform.ErgoBox(value = value, ergoTree = anyTree,
          transactionId = txId, index = 0.toShort, creationHeight = 0)
    }

  private def boxInput(b: org.ergoplatform.ErgoBox): Json = {
    val w = SigmaSerializer.startWriter()
    org.ergoplatform.ErgoBox.sigmaSerializer.serialize(b, w)
    Json.obj("kind" -> Json.fromString("Box"),
             "bytes_hex" -> Json.fromString(Base16.encode(w.toBytes)))
  }

  // no VersionContext wrap needed: no version-gated constants; the oracle round-trip at activated=2 proves the serialization. Wrap if you add version-gated constants.
  private def hex(root: sigma.ast.syntax.SValue): String =
    Base16.encode(sigma.santa.LenientErgoTree.serialize(treeHeaderV2, root))

  // { (b: Box) => b.value }
  private val valueTree = hex(ExtractAmount(OptionGet(GetVar(1.toByte, SBox))))
  // { (b: Box) => b.R0[Long].get }
  private val r0Tree = hex(OptionGet(ExtractRegisterAs(
    OptionGet(GetVar(1.toByte, SBox)), org.ergoplatform.ErgoBox.R0, SOption(SLong))))
  // { (b: Box) => b.tokens(0)._2 }
  private val tokensTree = {
    val b      = OptionGet(GetVar(1.toByte, SBox))
    val tokens = MethodCall(b, SBoxMethods.getMethodByName("tokens"),
      IndexedSeq.empty, Map.empty).asInstanceOf[Value[SCollection[STuple]]]
    hex(SelectField(ByIndex(tokens, IntConstant(0), None), 2))
  }

  def extract(): Map[String, Json] = {
    val min63  = java.lang.Long.MIN_VALUE       // 2^63 as u64 → Long.MinValue signed view
    val u64max = -1L                            // 2^64-1 as u64 → Long(-1) signed view
    val entries = Seq(
      ("b.value#nominal", valueTree, boxWith(CarrierValue, None), "{ (b: Box) => b.value }"),
      ("b.value#2^63",    valueTree, boxWith(min63, None),    "{ (b: Box) => b.value }"),
      ("b.value#u64-max", valueTree, boxWith(u64max, None),   "{ (b: Box) => b.value }"),
      ("b.R0#nominal",    r0Tree,    boxWith(CarrierValue, None), "{ (b: Box) => b.R0[Long].get }"),
      ("b.R0#2^63",       r0Tree,    boxWith(min63, None),    "{ (b: Box) => b.R0[Long].get }"),
      ("b.R0#u64-max",    r0Tree,    boxWith(u64max, None),   "{ (b: Box) => b.R0[Long].get }"),
      ("b.tokens(0)._2#nominal", tokensTree, boxWith(CarrierValue, Some(42L)),    "{ (b: Box) => b.tokens(0)._2 }"),
      ("b.tokens(0)._2#2^63",    tokensTree, boxWith(CarrierValue, Some(min63)),  "{ (b: Box) => b.tokens(0)._2 }"),
      ("b.tokens(0)._2#u64-max", tokensTree, boxWith(CarrierValue, Some(u64max)), "{ (b: Box) => b.tokens(0)._2 }")
    ).map { case (name, tree, box, script) =>
      SpecExtract.authoredEntry(Op, script, tree, name, boxInput(box), V2)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredBoxSignedView", extract(), outDir)
}
