// Lenient ErgoTree deserialization for eval-tier vectors.
//
// Eval-tier vectors carry a serialized ErgoTree whose ROOT EXPRESSION is an
// arbitrary-typed value (GroupElement, Long, Coll, …), not a SigmaProp
// proposition. sigma-state's public `deserializeErgoTree` enforces a SigmaProp
// root (CheckDeserializedScriptIsSigmaProp) and rejects such trees. The
// `checkType = false` overload skips that check but is `private[sigma]`, so this
// shim lives in a `sigma.*` subpackage to reach it — reusing the library's exact
// parse (header / constant-segregation / body) instead of hand-rolling it.
package sigma.santa

import sigma.ast.ErgoTree
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}

object LenientErgoTree {
  def deserialize(bytes: Array[Byte]): ErgoTree = {
    val r = SigmaSerializer.startReader(bytes)
    ErgoTreeSerializer.DefaultSerializer
      .deserializeErgoTree(r, SigmaSerializer.MaxPropositionSize, checkType = false)
  }
}
