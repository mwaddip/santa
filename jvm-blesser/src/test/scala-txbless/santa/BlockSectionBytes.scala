package santa

import io.circe.Json
import scorex.util.ModifierId
import scorex.util.encode.Base16

import org.ergoplatform.http.api.ApiCodecs
import org.ergoplatform.modifiers.history.{BlockTransactions, BlockTransactionsSerializer}
import org.ergoplatform.modifiers.mempool.ErgoTransaction

/** The block tier's `block.blockTransactions`: the transactions section as a node receives it, framed by
  * ergo-core's `BlockTransactionsSerializer` (runner-contract-block §2). The block blessers derive it here and
  * `santa.runner.BlockEngine` parses it back.
  *
  * Call it OUTSIDE any version context. The serializer sets the block's own context from block version 4, and
  * inside one a section below version 4 would inherit it (see `santa.runner.WireBlockTransactions`). */
object BlockSectionBytes extends ApiCodecs {

  private def hx(b: Array[Byte]): String = Base16.encode(b).toLowerCase

  /** The section the JVM writer frames for these transactions. A writer RE-WRITES every tree, so a tree read
    * from a non-canonical encoding comes out canonical here; an as-received section is this with the
    * encoding spliced back (AuthoredBlockKeptTreeBytes). */
  def hexOf(headerId: String, blockVersion: Byte, txs: Seq[ErgoTransaction]): String =
    hx(BlockTransactionsSerializer.toBytes(BlockTransactions(ModifierId @@ headerId, blockVersion, txs)))

  def parse(sectionHex: String): BlockTransactions =
    BlockTransactionsSerializer.parseBytes(
      Base16.decode(sectionHex).getOrElse(sys.error("block section: hex decode failed")))

  /** A captured block's node-API `blockTransactions` object → its section, derived through the writer (the local
    * node serves block transactions as JSON only). Fails loud unless the derivation agrees with everything the
    * capture states about the section:
    *  - its header id and block version are the header's;
    *  - every transaction decodes to the id the node gave it;
    *  - the section is exactly the node's own `size` long, where the capture carries one — the closest a JSON
    *    capture comes to proving the writer reproduced the stored bytes, since ids hash a re-serialization and
    *    so cannot see a tree's encoding;
    *  - it parses back to the same section (what BlockEngine will read is what was written). */
  def fromNodeJson(bt: Json, header: Json, label: String): String = {
    def str(j: Json, f: String): String = j.hcursor.get[String](f).fold(e => sys.error(s"$label: $f: $e"), identity)
    val headerId = str(bt, "headerId")
    require(headerId == str(header, "id"), s"$label: blockTransactions.headerId $headerId != header.id")
    val version = bt.hcursor.get[Int]("blockVersion").fold(e => sys.error(s"$label: blockVersion: $e"), identity)
    val headerVersion = header.hcursor.get[Int]("version").fold(e => sys.error(s"$label: header.version: $e"), identity)
    require(version == headerVersion, s"$label: blockTransactions.blockVersion $version != header.version $headerVersion")

    val txsJson = bt.hcursor.downField("transactions").focus.flatMap(_.asArray)
      .getOrElse(sys.error(s"$label: blockTransactions.transactions missing"))
    val txs = txsJson.map(_.as[ErgoTransaction].fold(e => sys.error(s"$label: tx decode: $e"), identity))
    txsJson.zip(txs).zipWithIndex.foreach { case ((j, tx), i) =>
      require(str(j, "id") == tx.id, s"$label: tx[$i] decodes to id ${tx.id}, the node says ${str(j, "id")}")
    }

    val section = hexOf(headerId, version.toByte, txs)
    bt.hcursor.get[Int]("size").toOption.foreach { size =>
      require(section.length / 2 == size,
        s"$label: the derived section is ${section.length / 2} bytes, the node's is $size")
    }
    val back = parse(section)
    require(hexOf(back.headerId, back.blockVersion, back.txs) == section,
      s"$label: the derived section does not parse back to itself")
    section
  }
}
