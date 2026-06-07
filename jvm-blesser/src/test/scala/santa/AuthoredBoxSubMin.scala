package santa

import io.circe.Json
import scorex.util.bytesToId
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog
import sigma.serialization.SigmaSerializer

/** Sub-min box value adjudication vector (the MIN_RAW floor).
  *
  * The JVM has NO minimum-value floor at the box parse/eval surface: `getULong`
  * reads any carrier and a `value = 1` box hydrates and evals like any other —
  * the dust rule (value ≥ MinValuePerByte × boxSize) is a transaction-tier
  * stateful check, not a box-decode rule. An implementation with a type-level
  * floor at parse (sigma-rust's `BoxValue::MIN_RAW` = 10800 pre-#885) never
  * hydrates the box, so every eval over it fails. This single entry pins the
  * adjudicated surface: the box hydrates, `b.value` evals to 1.
  *
  * Companion to [[AuthoredBoxSignedView]] (same hydration seam, opposite end of
  * the u64 range — that family's carrier deliberately CLEARS the floor; this
  * one IS the floor violation).
  */
object AuthoredBoxSubMin {

  val Op     = "Box.sub_min_value"
  val Source = "santa:authored-box-sub-min"

  // Pinned target version: v5 (activated=2, ergoTree=2) — the broadest surface;
  // the floor is version-independent.
  private val V2: Byte = VersionContext.JitActivationVersion

  private val anyTree: ErgoTree =
    ErgoTree.fromSigmaBoolean(ProveDlog(CryptoConstants.dlogGroup.generator))

  private val treeHeaderV2: ErgoTree.HeaderType =
    ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, V2))

  private val txId = bytesToId(Array.fill(32)(0: Byte))

  private def boxWith(value: Long): org.ergoplatform.ErgoBox =
    new org.ergoplatform.ErgoBox(value = value, ergoTree = anyTree,
      transactionId = txId, index = 0.toShort, creationHeight = 0)

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

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredEntry(Op, "{ (b: Box) => b.value }", valueTree,
        "b.value#sub-min", boxInput(boxWith(1L)), V2))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredBoxSubMin", extract(), outDir)
}
