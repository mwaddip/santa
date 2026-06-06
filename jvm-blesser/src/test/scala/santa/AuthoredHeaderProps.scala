package santa

import io.circe.Json
import scorex.util.encode.Base16
import sigma.ast._
import sigma.serialization.SigmaSerializer

/** Header property-accessor vectors (ergots 31-key batch, priority 1).
  *
  * One applied entry per SHeader accessor (methodIds 1..15) over the upstream v2
  * mainnet-header fixture, plus two extra `timestamp` entries pinning the signed-Long
  * view: >2^53 (the F2 panic class at eval proper) and u64-max (surfaces as Long(-1);
  * spike-verified — see prompts/santa-header-signedview-spike-findings.md).
  *
  * GOTCHA (spike S0): ErgoHeader caches its parsed-from serialization in `_bytes`;
  * `.bytes` on a `copy()` returns the ORIGINAL bytes — always serialize crafted
  * headers explicitly through ErgoHeader.sigmaSerializer.
  */
object AuthoredHeaderProps {

  val Op     = "Header.property_accessors"
  val Source = "santa:authored-header-props"

  // EvalCoreTest's mainnet v2 header literal (the Header_new_methods input)
  private val mainnetHeaderHex =
    "02ac2101807f0000ca01ff0119db227f202201007f62000177a080005d440896d05d3f80dcff7f5e7f59007294c180808d0158d1ff6ba10000f901c7f0ef87dcfff17fffacb6ff7f7f1180d2ff7f1e24ffffe1ff937f807f0797b9ff6ebdae007e5c8c00b8403d3701557181c8df800001b6d5009e2201c6ff807d71808c00019780f087adb3fcdbc0b3441480887f80007f4b01cf7f013ff1ffff564a0000b9a54f00770e807f41ff88c00240000080c0250000000003bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c0000000000003105"

  private def parseHeader(hex: String): org.ergoplatform.ErgoHeader =
    org.ergoplatform.ErgoHeader.sigmaSerializer.parse(
      SigmaSerializer.startReader(Base16.decode(hex).get))

  private def headerBytesHex(h: org.ergoplatform.ErgoHeader): String = {
    val w = SigmaSerializer.startWriter()
    org.ergoplatform.ErgoHeader.sigmaSerializer.serialize(h, w)
    Base16.encode(w.toBytes)
  }

  private val treeHeaderV3: ErgoTree.HeaderType =
    ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, 3.toByte))

  /** { (h: Header) => h.<name> } — applied form reading var 1. */
  def accessorTreeHex(name: String): String = {
    val m    = SHeaderMethods.getMethodByName(name)
    val root = MethodCall(OptionGet(GetVar(1.toByte, SHeader)), m, IndexedSeq.empty, Map.empty)
    Base16.encode(sigma.santa.LenientErgoTree.serialize(treeHeaderV3, root))
  }

  private def headerInput(h: org.ergoplatform.ErgoHeader): Json = Json.obj(
    "kind"      -> Json.fromString("Header"),
    "bytes_hex" -> Json.fromString(headerBytesHex(h)))

  val AccessorNames: Seq[String] = Seq(
    "id", "version", "parentId", "ADProofsRoot", "stateRoot", "transactionsRoot",
    "timestamp", "nBits", "height", "extensionRoot", "minerPk", "powOnetimePk",
    "powNonce", "powDistance", "votes")

  def vectors: Map[String, Json] = {
    val base = parseHeader(mainnetHeaderHex)
    val nominal = AccessorNames.map { name =>
      SpecExtract.authoredEntry(Op, s"{ (h: Header) => h.$name }",
        accessorTreeHex(name), s"h.$name#nominal", headerInput(base), 3.toByte)
    }
    val tsRanges = Seq(
      ("h.timestamp#gt-2^53", (1L << 53) + 1L),
      ("h.timestamp#u64-max", -1L)
    ).map { case (name, ts) =>
      val crafted = base.copy(timestamp = ts)
      SpecExtract.authoredEntry(Op, "{ (h: Header) => h.timestamp }",
        accessorTreeHex("timestamp"), name, headerInput(crafted), 3.toByte)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, nominal ++ tsRanges, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredHeaderProps", vectors, outDir)
}
