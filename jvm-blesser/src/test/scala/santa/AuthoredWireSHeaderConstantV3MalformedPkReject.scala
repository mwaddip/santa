package santa

// Authored wire REJECT arm — the v3 SHeader-constant MALFORMED-Header-VALUE over-accept probe (the
// narrower residual the accept vector ErgoTree.sheader_constant_v3_accept left open).
//
// At treeVersion >= 3 the JVM ACCEPTS an SHeader-as-constant — but ONLY if its Header value parses
// (DataSerializer -> ErgoHeader.sigmaSerializer.parse). This vector is the VALID v3 accept tree with the
// AutolykosSolution pk's compressed-point prefix corrupted 0x00 (the fixture's infinity point) -> 0x05 (an
// invalid compressed-point prefix). GroupElementSerializer.parse rejects the bad point with an
// IllegalArgumentException, which deserializeErgoTree wraps as a SerializerException -> the JVM REJECTS.
//
// NOTE the wrapper's message is misleadingly "Tree version (3) is above activated script version (3)" — a
// cosmetic mislabel: deserializeErgoTree re-labels ANY IllegalArgumentException during deserialize as a
// version error. The real cause is the off-curve pk, preserved as getCause. The bless asserts on that cause
// (+ the valid base accepting), NOT the misleading top message.
//
// An impl that decodes the pk without on-curve / valid-prefix validation ACCEPTS a v3 SHeader constant the
// JVM rejects -> over-accept. santa-wire/v1 REJECT entry: error "errored", no expected_bytes_hex.
//
// extract() RE-DERIVES the differential: the VALID base tree ACCEPTS (root=Right) and the pk-corrupted tree
// THROWS with an IllegalArgumentException cause — so a sigma-state change that started accepting bad points,
// or stopped accepting the valid base, fails loud.

import scorex.util.encode.Base16
import io.circe.Json

import sigma.VersionContext

object AuthoredWireSHeaderConstantV3MalformedPkReject {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3
  val ErgoTreeV: Byte = VersionContext.V6SoftForkVersion // 3
  val Source = "santa:authored-sheader-constant-v3-malformed-pk-reject"
  val Op     = "ErgoTree.sheader_constant_v3_malformed_pk_reject"

  private val ValidHex = AuthoredWireSHeaderConstantV3Accept.Hex
  // v2 Header AutolykosSolution = pk(33) + nonce(8) = the last 41 bytes; the pk compressed-point prefix is
  // tree byte 179. Corrupt it 0x00 (infinity) -> 0x05 (invalid compressed-point prefix).
  private val PkPrefixByte = 179
  val Hex = ValidHex.substring(0, PkPrefixByte * 2) + "05" + ValidHex.substring(PkPrefixByte * 2 + 2)

  def extract(): Map[String, Json] = {
    VersionContext.withVersions(Activated, ErgoTreeV) {
      val valid = sigma.santa.LenientErgoTree.deserialize(Base16.decode(ValidHex).get)
      require(valid.root.isRight,
        s"VALID base $ValidHex must parse (root=Right) — the malformed-vs-valid differential is meaningless otherwise")
      val (threw, cause) =
        try { sigma.santa.LenientErgoTree.deserialize(Base16.decode(Hex).get); (false, "deserialized — no throw") }
        catch { case t: Throwable => (true, Option(t.getCause).map(_.getClass.getSimpleName).getOrElse(s"<no cause; ${t.getClass.getSimpleName}>")) }
      require(threw && cause.contains("IllegalArgument"),
        s"$Hex must be REJECTED with an IllegalArgumentException cause (the off-curve pk decode) — got threw=$threw cause=$cause")
    }
    val entry = Json.obj(
      "name"        -> Json.fromString("sheader-constant-v3-malformed-pk-reject#0"),
      "kind"        -> Json.fromString("ErgoTree"),
      "source"      -> Json.fromString(Source),
      "description" -> Json.fromString(
        "v3 SHeader-as-constant (header 0x1b = v3 + size + seg) whose AutolykosSolution pk carries an INVALID " +
        "compressed-point prefix (0x05). The JVM accepts SHeader constants at treeVersion >= 3 but only if the " +
        "Header value parses — ErgoHeader.sigmaSerializer.parse -> GroupElementSerializer.parse rejects the bad " +
        "point (IllegalArgumentException, wrapped as a SerializerException whose top message is a misleading " +
        "\"tree version\" mislabel) so the JVM REJECTS. The MALFORMED-VALUE residual of the SHeader-constant v3 " +
        "boundary (the accept twin sheader_constant_v3_accept carries a valid Header value). An impl that decodes " +
        "the pk without on-curve / valid-prefix validation accepts a v3 SHeader constant the JVM rejects -> a " +
        "crafted-bytes over-accept."),
      "bytes_hex"   -> Json.fromString(Hex),
      "error"       -> Json.fromString("errored"),
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
    SpecExtract.writeStaging("AuthoredWireSHeaderConstantV3MalformedPkReject", extract(), outDir)
}
