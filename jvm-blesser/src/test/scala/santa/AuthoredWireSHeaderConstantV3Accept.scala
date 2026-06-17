package santa

// Authored wire ACCEPT vector — the POSITIVE side of the SHeader-constant version boundary.
// bytes_hex is the SHeader-constant tree of AuthoredWireUnparsedSoftForkHeaderConstant (header 0x1a =
// v2 + size + const-seg, ONE segregated constant of type SHeader, typeCode 0x68) with the ErgoTree
// version bumped 2 -> 3 (header 0x1b = v3 + size + const-seg). Everything after byte 0 is identical —
// the constant + body + size VLQ are version-independent.
//
// The boundary (settled live on the oracle, SHeaderV3RejectSpike, + source DataSerializer.scala:19,39):
// SHeader's DataSerializer is GATED on VersionContext.isV3OrLaterErgoTreeVersion. So an SHeader-typed
// segregated constant is:
//   - at treeVersion 2: NOT serializable -> a direct SerializerException ("Not defined DataSerializer
//     for type SHeader") -> JVM REJECTS (the v2 reject twins: ErgoTree.unparsed_soft_fork_header_constant
//     + Box.softfork_header_constant_reject).
//   - at treeVersion >= 3 (the v6.0 era; MaxSupportedScriptVersion == 3): serializable as a CHeader via
//     ErgoHeader.sigmaSerializer -> JVM PARSES it (root = Right) and round-trips byte-IDENTICAL.
//
// This vector pins the ACCEPT side: SHeader-as-constant is a real v6.0 / ErgoTree-v3 serialization
// feature, NOT a universal reject (correcting the assumption that the JVM rejects at every version).
// It catches any impl that OVER-REJECTS a valid v3 SHeader constant (a stricter ergo-ser), and pins the
// byte-exact round-trip. Together with the v2 reject twins it brackets the exact version gate.
//
// extract() RE-DERIVES the blessing (asserts genuinely PARSED at v3 + JVM canonicalize == input) so a
// sigma-state change that stopped serializing SHeader constants at v3, or stopped preserving, fails loud.

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext

object AuthoredWireSHeaderConstantV3Accept {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3 — v6.0 activated
  val ErgoTreeV: Byte = VersionContext.V6SoftForkVersion // 3 — SHeader serializable at treeVersion >= 3
  val Source = "santa:authored-sheader-constant-v3-accept"
  val Op     = "ErgoTree.sheader_constant_v3_accept"

  // The v2 SHeader-constant tree (header 0x1a) with the version nibble bumped to 3 (header 0x1b). The
  // post-header bytes (size VLQ db01, 1 constant typeCode 0x68 + Header value, body 73 00) are identical.
  val Hex = "1b" + AuthoredWireUnparsedSoftForkHeaderConstant.Hex.substring(2)

  def extract(): Map[String, Json] = {
    VersionContext.withVersions(Activated, ErgoTreeV) {
      val tree = sigma.santa.LenientErgoTree.deserialize(Base16.decode(Hex).get)
      require(tree.root.isRight,
        s"$Hex must PARSE at treeVersion 3 (SHeader serializable via isV3OrLaterErgoTreeVersion) — got an " +
        s"UnparsedErgoTree; the ACCEPT vector is meaningless")
      val canon = WireCanonicalize.canonicalize("ErgoTree", Hex, Activated, ErgoTreeV)
      require(canon == Hex,
        s"JVM must round-trip the v3 SHeader-constant tree $Hex byte-IDENTICALLY — got $canon")
    }
    val entry = Json.obj(
      "name"        -> Json.fromString("sheader-constant-v3-accept#0"),
      "kind"        -> Json.fromString("ErgoTree"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(
        "Size-flagged ErgoTree (header 0x1b = v3 + size + const-seg) with one segregated constant of type " +
        "SHeader (typeCode 0x68 = 104). SHeader's DataSerializer is gated on isV3OrLaterErgoTreeVersion, so " +
        "at treeVersion >= 3 (the v6.0 era) the JVM PARSES the SHeader constant (as a CHeader via " +
        "ErgoHeader.sigmaSerializer) and round-trips byte-IDENTICAL — where the v2 form (header 0x1a) rejects " +
        "with \"Not defined DataSerializer for type SHeader\". The ACCEPT side of the SHeader-constant version " +
        "boundary (positive twin of the v2 reject vectors unparsed_soft_fork_header_constant / " +
        "Box.softfork_header_constant_reject). An impl that REJECTS this OVER-REJECTS a valid v6.0 SHeader " +
        "constant; one that mangles the round-trip diverges on propositionBytes -> boxId."),
      "bytes_hex"   -> Json.fromString(Hex),
      // expected_bytes_hex OMITTED: identity round-trip (the JVM preserves the bytes). No `error`: this ACCEPTS.
      "version"     -> Json.obj(
        "activated" -> Json.fromInt(Activated.toInt),
        "ergoTree"  -> Json.fromInt(ErgoTreeV.toInt)))
    Map(Op -> Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.3"),
      "entries"    -> Json.arr(entry)))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireSHeaderConstantV3Accept", extract(), outDir)
}
