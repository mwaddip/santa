package santa

import scorex.util.encode.Base16
import org.ergoplatform.{ErgoBox, ErgoLikeTransaction}
import sigma.VersionContext
import sigma.ast.DeserializationSigmaBuilder
import sigma.data.SigmaBoolean
import sigma.serialization.{ConstantSerializer, ErgoTreeSerializer, SigmaSerializer}

/** Parse `bytesHex` as `kind` under the (activated, ergoTree) version context and
  * reserialize — the JVM's canonical bytes for that object. The wire tier's bless +
  * round-trip core. Throws (loud) if the bytes don't parse as `kind`; the caller decides
  * how to record that. Idempotent on canonical input.
  *
  * Serializer access mirrors AuthoredSerialize.scala:
  *   ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(bytes)) / .toBytes(box)
  *   SigmaBoolean.serializer.parse(...) / .toBytes(...) */
object WireCanonicalize {
  /** Reflection seam to the gated [[santa.runner.WireBlockTransactions]] (ergo-core's BlockTransactionsSerializer,
    * compiled only under SANTA_TX_BLESSER; a static reference would not compile in an ungated build). */
  private lazy val blockTransactionsFn: Option[String => String] =
    scala.util.Try {
      val clazz  = Class.forName("santa.runner.WireBlockTransactions$")
      val module = clazz.getField("MODULE$").get(null)
      val m      = clazz.getMethod("canonicalize", classOf[String])
      (hex: String) => m.invoke(module, hex).asInstanceOf[String]
    }.toOption

  /** Whether this build can round-trip `kind`: a runner reports any other kind as not-implemented. */
  def supports(kind: String): Boolean = kind match {
    case "Box" | "SigmaBoolean" | "Transaction" | "Constant" | "ErgoTree" => true
    case "BlockTransactions"                                             => blockTransactionsFn.isDefined
    case _                                                               => false
  }

  def canonicalize(kind: String, bytesHex: String, activated: Byte, ergoTree: Byte): String =
    VersionContext.withVersions(activated, ergoTree) {
      val bytes = Base16.decode(bytesHex).get
      val r = SigmaSerializer.startReader(bytes)
      kind match {
        case "Box" =>
          Base16.encode(ErgoBox.sigmaSerializer.toBytes(ErgoBox.sigmaSerializer.parse(r)))
        case "SigmaBoolean" =>
          Base16.encode(SigmaBoolean.serializer.toBytes(SigmaBoolean.serializer.parse(r)))
        case "Transaction" =>
          Base16.encode(ErgoLikeTransaction.serializer.toBytes(ErgoLikeTransaction.serializer.parse(r)))
        case "Constant" =>
          // A self-describing constant is bare [type][data] (no opcode); ConstantSerializer's own
          // deserialize/serialize handle exactly that pairing (putType + DataSerializer).
          val cs = ConstantSerializer(DeserializationSigmaBuilder)
          val w  = SigmaSerializer.startWriter()
          cs.serialize(cs.deserialize(r), w)
          Base16.encode(w.toBytes)
        case "ErgoTree" =>
          // STRUCTURAL re-serialize: decode to a tree, re-encode FROM structure — NOT the cached
          // ErgoTree.bytes echo (which preserves the raw input and would miss type/name re-encode
          // forks like the STypeVar UTF-8 surrogate, and turn rudolph red on its own canonical).
          // LenientErgoTree reaches the checkType=false deserialize so an arbitrary-root tree parses.
          // See docs/specs/wire-roundtrip-nonidentity.md.
          Base16.encode(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(
            sigma.santa.LenientErgoTree.deserialize(bytes)))
        case "BlockTransactions" =>
          // A block section: every transaction on a fresh reader, under the block version's context (see
          // WireBlockTransactions). The entry's version pair only frames the call.
          blockTransactionsFn.getOrElse(sys.error("WireCanonicalize: BlockTransactions needs the SANTA_TX_BLESSER " +
            "build (ergo-core)"))(bytesHex)
        case other =>
          sys.error(s"WireCanonicalize: unsupported kind '$other' " +
            "(Box/SigmaBoolean/Transaction/Constant/ErgoTree/BlockTransactions implemented; Header arrives with captures)")
      }
    }
}
