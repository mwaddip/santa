package santa

import scorex.util.encode.Base16
import org.ergoplatform.ErgoBox
import sigma.VersionContext
import sigma.data.SigmaBoolean
import sigma.serialization.SigmaSerializer

/** Parse `bytesHex` as `kind` under the (activated, ergoTree) version context and
  * reserialize — the JVM's canonical bytes for that object. The wire tier's bless +
  * round-trip core. Throws (loud) if the bytes don't parse as `kind`; the caller decides
  * how to record that. Idempotent on canonical input.
  *
  * Serializer access mirrors AuthoredSerialize.scala:
  *   ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(bytes)) / .toBytes(box)
  *   SigmaBoolean.serializer.parse(...) / .toBytes(...) */
object WireCanonicalize {
  def canonicalize(kind: String, bytesHex: String, activated: Byte, ergoTree: Byte): String =
    VersionContext.withVersions(activated, ergoTree) {
      val r = SigmaSerializer.startReader(Base16.decode(bytesHex).get)
      kind match {
        case "Box" =>
          Base16.encode(ErgoBox.sigmaSerializer.toBytes(ErgoBox.sigmaSerializer.parse(r)))
        case "SigmaBoolean" =>
          Base16.encode(SigmaBoolean.serializer.toBytes(SigmaBoolean.serializer.parse(r)))
        case other =>
          sys.error(s"WireCanonicalize: unsupported kind '$other' " +
            "(Box/SigmaBoolean implemented; Transaction/Header/Constant arrive with captures)")
      }
    }
}
