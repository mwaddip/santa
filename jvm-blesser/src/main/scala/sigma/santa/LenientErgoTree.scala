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
import sigma.ast.ErgoTree.HeaderType
import sigma.ast.syntax.{SValue, SigmaPropValue}
import sigma.ast.{Constant, SType}
import sigma.serialization.{ConstantStore, ErgoTreeSerializer, SigmaSerializer, ValueSerializer}

object LenientErgoTree {
  def deserialize(bytes: Array[Byte]): ErgoTree = {
    val r = SigmaSerializer.startReader(bytes)
    ErgoTreeSerializer.DefaultSerializer
      .deserializeErgoTree(r, SigmaSerializer.MaxPropositionSize, checkType = false)
  }

  /** Build a constant-segregated ErgoTree from an arbitrary-typed root expression
    * (the symmetric SERIALIZE side of `deserialize` above). The eval-tier function
    * trees from `LanguageSpecificationV6` have a root of type B (Byte, Coll, …), NOT
    * SigmaProp, so the public `ErgoTree.withSegregation` — which `.asSigmaProp`-casts
    * but, more importantly, is the only path — still works because `asSigmaProp` /
    * the constructor's `SigmaPropValue` typing are erased casts (no runtime type
    * check); serialization dispatches purely on the value's opcode. We replicate the
    * `withSegregation` segregation dance here so the cast is explicit and local.
    *
    * @param header an ErgoTree header (e.g. `ErgoTree.headerWithVersion(ZeroHeader, v)`,
    *               size bit set for v>0 via `setSizeBit`); the constant-segregation
    *               flag is OR-ed in by this method.
    */
  def serialize(header: HeaderType, root: SValue): Array[Byte] =
    fromExpr(header, root).bytes

  /** As `serialize`, but returns the ErgoTree (so the caller can also re-deserialize
    * / inspect it). */
  def fromExpr(header: HeaderType, root: SValue): ErgoTree = {
    val constantStore = new ConstantStore()
    val w             = SigmaSerializer.startWriter(Some(constantStore))
    ValueSerializer.serialize(root, w)
    val extractedConstants: IndexedSeq[Constant[SType]] = constantStore.getAll
    val r = SigmaSerializer.startReader(w.toBytes)
    r.constantStore = new ConstantStore(extractedConstants)
    val valueWithPlaceholders = ValueSerializer.deserialize(r)
    new ErgoTree(
      header = ErgoTree.setRequiredBits(ErgoTree.setConstantSegregation(header)),
      constants = extractedConstants,
      root = Right(valueWithPlaceholders.asInstanceOf[SigmaPropValue]))
  }
}
