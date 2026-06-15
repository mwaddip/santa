package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored block-header `version` signedness witness (ergots version-signedness ask 21).
//
// `HeaderWithoutPow.parse` reads `version = r.getByte()` (SIGNED) and the unparsedBytes
// gates are signed: `version > 1` reads the u8 length prefix, `version > 4` consumes the
// payload. A version byte >= 0x80 is NEGATIVE as a signed Byte, so the JVM SKIPS the whole
// unparsedBytes block — the bytes after `votes` flow straight into the AutolykosSolution,
// shifting its offset → a divergent `minerPk`. ergots (now fixed) and sigma-rust read the
// version unsigned (`get_u8`/`u8`), so `128 > 1` is true → they consume unparsedBytes →
// the real minerPk → they DIVERGE from the JVM at version >= 0x80. No pre-parse
// version-range validation gates this; reachable via a Header input / deserializeTo[Header].
//
// Oracle-confirmed (spike, sigma-state 6.0.3), same body (the EvalCoreTest v2 fixture), only
// the version byte (offset 0) patched:
//   version 0x7f (signed 127 > 1)  -> reads unparsedBytes -> minerPk = the real Ecp
//                                     (CONTROL: an unsigned reader reads 0x7f = 127 > 1 too → AGREES)
//   version 0x80 (signed -128 <= 1) -> SKIPS unparsedBytes -> minerPk = Ecp(INF) (a DIFFERENT point)
// (0x02 and 0x7f yield the same minerPk — both consume the empty-unparsedBytes prefix — so the
//  signedness boundary is exactly 0x7f/0x80.)
//
// Both entries ACCEPT (a clean VALUE fork, not accept-vs-error). The vector is a Header INPUT
// carrying the RAW patched bytes (not a re-serialized ErgoHeader, which would normalize the
// gate) + `h.minerPk`; mirrors AuthoredHeaderProps. v6 (SHeader needs ergoTree 3).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ErgoTree, GetVar, MethodCall, OptionGet, SHeader, SHeaderMethods, SType, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}

object AuthoredHeaderVersionSignedness {

  /** Pinned target: full v6 (activated=3, ergoTree=3) — SHeader requires ergoTree 3. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-header-version-signedness"
  val Op     = "Header.version_unparsedbytes_gate"

  // The EvalCoreTest mainnet v2 header fixture (version byte = 0x02 at offset 0), shared with
  // AuthoredHeaderProps — a real-shaped header whose AutolykosSolution parses cleanly.
  private val baseHeaderHex =
    "02ac2101807f0000ca01ff0119db227f202201007f62000177a080005d440896d05d3f80dcff7f5e7f59007294c180808d0158d1ff6ba10000f901c7f0ef87dcfff17fffacb6ff7f7f1180d2ff7f1e24ffffe1ff937f807f0797b9ff6ebdae007e5c8c00b8403d3701557181c8df800001b6d5009e2201c6ff807d71808c00019780f087adb3fcdbc0b3441480887f80007f4b01cf7f013ff1ffff564a0000b9a54f00770e807f41ff88c00240000080c0250000000003bedaee069ff4829500b3c07c4d5fe6b3ea3d3bf76c5c28c1d4dcdb1bed0ade0c0000000000003105"

  /** baseHeaderHex with the version byte (offset 0) replaced — a same-length edit. */
  private def patchVersion(verByte: Int): String = {
    val b = Base16.decode(baseHeaderHex).get
    b(0) = verByte.toByte
    Base16.encode(b)
  }

  private def headerInput(hex: String): Json =
    Json.obj("kind" -> Json.fromString("Header"), "bytes_hex" -> Json.fromString(hex))

  private val treeHeaderV3: ErgoTree.HeaderType =
    ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))

  /** `{ (h: Header) => h.<name> }` — applied form reading var 1 (mirrors AuthoredHeaderProps). */
  private def accessorTreeHex(name: String): String = {
    val m    = SHeaderMethods.getMethodByName(name)
    val root = MethodCall(OptionGet(GetVar(1.toByte, SHeader)), m, IndexedSeq.empty, Map.empty)
    Base16.encode(sigma.santa.LenientErgoTree.serialize(treeHeaderV3, root))
  }

  def extract(): Map[String, Json] = {
    val minerPkTree = accessorTreeHex("minerPk")
    val entries = Seq(
      SpecExtract.authoredEntry(Op,
        "{ (h: Header) => h.minerPk }  // version byte 0x7f: signed getByte() = 127 > 1 -> reads unparsedBytes, solution at its real offset. CONTROL: ergots/sigma-rust read 0x7f unsigned = 127 > 1 too -> they AGREE here.",
        minerPkTree, "version-0x7f-reads-unparsedbytes#0", headerInput(patchVersion(0x7f)), V3),
      SpecExtract.authoredEntry(Op,
        "{ (h: Header) => h.minerPk }  // version byte 0x80: signed getByte() = -128 <= 1 -> SKIPS unparsedBytes (HeaderWithoutPow gates `version > 1`), AutolykosSolution shifts earlier -> a DIFFERENT minerPk (here Ecp INF). ergots/sigma-rust read 0x80 unsigned = 128 > 1 -> consume unparsedBytes -> the real minerPk -> DIVERGE (ask 21).",
        minerPkTree, "version-0x80-skips-unparsedbytes#1", headerInput(patchVersion(0x80)), V3))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredHeaderVersionSignedness", extract(), outDir)
}
