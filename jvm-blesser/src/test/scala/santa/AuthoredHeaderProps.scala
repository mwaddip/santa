package santa

import io.circe.Json
import org.ergoplatform.ErgoHeader
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.serialization.SigmaSerializer

/** Header property-accessor vectors (ergots 31-key batch, priority 1).
  *
  * One applied entry per SHeader accessor (methodIds 1..15) over the upstream v2
  * mainnet-header fixture, plus two extra `timestamp` entries pinning the signed-Long
  * view of the VLQ-u64 wire field (verified against sigma-state 6.0.3; all 17 entries
  * eval at flat cost 39):
  *
  *   - >2^53: one past the JS exact-integer bound — the timestamp class that panicked
  *     ergots before its signed-i64 timestamp fix, previously pinned only via the
  *     serialize_* vectors, here pinned at eval proper;
  *   - u64-max: round-trips the header serializer byte-faithfully and evals to
  *     Long(-1) — the JVM surfaces u64 carriers in [2^63, 2^64) as negative signed
  *     Longs rather than rejecting them.
  *
  * GOTCHA: ErgoHeader caches its parsed-from serialization in `_bytes`; `.bytes` on a
  * `copy()` returns the ORIGINAL bytes — always serialize crafted headers explicitly
  * through ErgoHeader.sigmaSerializer.
  */
object AuthoredHeaderProps {

  val Op     = "Header.property_accessors"
  val Source = "santa:authored-header-props"

  /** Pinned target version: full v6 (activated=3, ergoTree=3). */
  private val V3: Byte = VersionContext.V6SoftForkVersion

  // EvalCoreTest's mainnet v2 header literal (the Header_new_methods input)
  private val mainnetHeaderHex =
    "02ac2101807f0000ca01ff0119db227f202201007f62000177a080005d440896d05d3f80dcff7f5e7f59007294c180808d0158d1ff6ba10000f901c7f0ef87dcfff17fffacb6ff7f7f1180d2ff7f1e24ffffe1ff937f807f0797b9ff6ebdae007e5c8c00b8403d3701557181c8df800001b6d5009e2201c6ff807d71808c00019780f087adb3fcdbc0b3441480887f80007f4b01cf7f013ff1ffff564a0000b9a54f00770e807f41ff88c00240000080c0250000000003bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c0000000000003105"

  private def parseHeader(hex: String): ErgoHeader =
    ErgoHeader.sigmaSerializer.parse(
      SigmaSerializer.startReader(Base16.decode(hex).get))

  // NOT h.bytes — stale _bytes cache on copy(), see object doc.
  private def headerBytesHex(h: ErgoHeader): String = {
    val w = SigmaSerializer.startWriter()
    ErgoHeader.sigmaSerializer.serialize(h, w)
    Base16.encode(w.toBytes)
  }

  private val treeHeaderV3: ErgoTree.HeaderType =
    ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ErgoTree.ZeroHeader, V3))

  // no VersionContext wrap needed: accessor trees carry zero constants and MethodCall
  // serialization is version-insensitive here; wrap if you add version-gated constants.
  /** { (h: Header) => h.<name> } — applied form reading var 1. */
  def accessorTreeHex(name: String): String = {
    val m    = SHeaderMethods.getMethodByName(name)
    val root = MethodCall(OptionGet(GetVar(1.toByte, SHeader)), m, IndexedSeq.empty, Map.empty)
    Base16.encode(sigma.santa.LenientErgoTree.serialize(treeHeaderV3, root))
  }

  private def headerInput(h: ErgoHeader): Json = Json.obj(
    "kind"      -> Json.fromString("Header"),
    "bytes_hex" -> Json.fromString(headerBytesHex(h)))

  val AccessorNames: Seq[String] = Seq(
    "id", "version", "parentId", "ADProofsRoot", "stateRoot", "transactionsRoot",
    "timestamp", "nBits", "height", "extensionRoot", "minerPk", "powOnetimePk",
    "powNonce", "powDistance", "votes")

  def extract(): Map[String, Json] = {
    val base = parseHeader(mainnetHeaderHex)
    val nominal = AccessorNames.map { name =>
      SpecExtract.authoredEntry(Op, s"{ (h: Header) => h.$name }",
        accessorTreeHex(name), s"h.$name#nominal", headerInput(base), V3)
    }
    val tsRanges = Seq(
      ("h.timestamp#gt-2^53", (1L << 53) + 1L),
      ("h.timestamp#u64-max", -1L)
    ).map { case (name, ts) =>
      val crafted = base.copy(timestamp = ts)
      SpecExtract.authoredEntry(Op, "{ (h: Header) => h.timestamp }",
        accessorTreeHex("timestamp"), name, headerInput(crafted), V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, nominal ++ tsRanges, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredHeaderProps", extract(), outDir)
}
