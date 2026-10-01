package santa.runner

import scorex.util.encode.Base16
import org.ergoplatform.modifiers.history.BlockTransactionsSerializer

/** Wire round-trip of a block's transactions section, through ergo-core's own `BlockTransactionsSerializer`.
  *
  * The section is the header id, a VLQ `10,000,000 + blockVersion` marker (block version > 1), the VLQ tx count and
  * the transactions (ergo v6.0.6 `BlockTransactions.scala:142-200`). Each transaction parses through
  * `ErgoTransactionSerializer.parse`, which wraps the section's reader in a fresh `SigmaByteReader`
  * (`ErgoTransaction.scala:497-503`): nesting level 0, new constant and ValDef stores. So nothing a transaction leaves
  * on its reader reaches the next one.
  *
  * From block version 4 each transaction runs under (blockVersion - 1, blockVersion - 1); below that the serializer sets
  * no context (`BlockTransactions.scala:184-202`), and a node's thread is then at the default (1, 1). So call this outside
  * any version context, as `santa.WireCanonicalize` does: inside one, a section below version 4 would inherit it.
  *
  * Compiled only under SANTA_TX_BLESSER (it imports ergo-core); `santa.WireCanonicalize` reaches it by reflection. */
object WireBlockTransactions {
  def canonicalize(bytesHex: String): String = {
    val bt = BlockTransactionsSerializer.parseBytes(Base16.decode(bytesHex).get)
    Base16.encode(BlockTransactionsSerializer.toBytes(bt))
  }
}
