package santa

// Shared construction kit for the context-extension (bounds, parse rules), parse-depth, creation-height-bound
// and storage-rent vectors (wire + transaction tiers). Pure sigma-state (no ergo-core), so the ungated wire blessers
// can use it; the gated tx blesser (AuthoredTxStorageRent) adds only the header context on top.
//
// Everything is deterministic: tx ids and token ids are Blake2b256 over a label, so re-running a
// blesser reproduces its committed bytes exactly.

import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.{Colls, Evaluation}
import sigma.ast.{Constant, ErgoTree, EvaluatedValue, SByte, SCollection, SType, SigmaPropConstant}
import sigma.data.{Digest32Coll, TrivialProp}
import sigma.interpreter.{ContextExtension, ProverResult}
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{DataInput, ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction, Input}
import org.ergoplatform.ErgoBox.{NonMandatoryRegisterId, Token}

object RentFixtures {
  /** `Constants.StoragePeriod` (ergo v6.0.6 ergo-wallet `protocol/Constants.scala:19`, 4 × 262800). */
  val StoragePeriod: Int = 1051200
  /** `Constants.StorageIndexVarId` (`Constants.scala:23`): the variable holding the recreated output's index. */
  val StorageIndexVarId: Byte = Byte.MaxValue
  /** `Constants.StorageContractCost` (`Constants.scala:21`): block-cost units for one storage-rent spend. */
  val StorageContractCost: Long = 50
  /** The live mainnet `storageFeeFactor` (key 0001, and the launch default). */
  val StorageFeeFactor: Int = 1250000

  type Regs = Map[NonMandatoryRegisterId, EvaluatedValue[_ <: SType]]

  /** `sigmaProp(true)`: spendable with an empty proof, so no signing is needed anywhere. */
  val TrueTree: ErgoTree  = ErgoTree.fromProposition(ErgoTree.ZeroHeader, SigmaPropConstant(TrivialProp.TrueProp))
  /** `sigmaProp(false)`: a different R1 (script) for the register-preservation arm. */
  val FalseTree: ErgoTree = ErgoTree.fromProposition(ErgoTree.ZeroHeader, SigmaPropConstant(TrivialProp.FalseProp))

  def digest(label: String): Array[Byte] = Blake2b256(label.getBytes("UTF-8"))
  def tokenId(label: String): Digest32Coll = Digest32Coll @@ Colls.fromArray(digest(label))

  def candidate(value: Long, height: Int, tree: ErgoTree = TrueTree, tokens: Seq[Token] = Nil,
                regs: Regs = Map()): ErgoBoxCandidate =
    new ErgoBoxCandidate(value, tree, height, Colls.fromArray(tokens.toArray), regs)

  /** An unspent box, as if output 0 of the transaction whose id is Blake2b256(`label`). */
  def box(label: String, value: Long, height: Int, tree: ErgoTree = TrueTree, tokens: Seq[Token] = Nil,
          regs: Regs = Map()): ErgoBox =
    candidate(value, height, tree, tokens, regs).toBox(scorex.util.bytesToId(digest(label)), 0.toShort)

  /** `b` recreated at `height` holding `value`: script (R1), tokens (R2) and R4..R9 kept, as
    * `ErgoInterpreter.checkExpiredBox` requires. */
  def recreate(b: ErgoBox, value: Long, height: Int): ErgoBoxCandidate =
    new ErgoBoxCandidate(value, b.ergoTree, height, b.additionalTokens, b.additionalRegisters)

  /** A context extension in the given order (≤ 4 entries: Scala's Map1..Map4 keep insertion order,
    * so the serializer emits exactly this order). */
  def ext(vars: (Byte, EvaluatedValue[_ <: SType])*): ContextExtension = {
    require(vars.size <= 4, "ext() keeps insertion order only up to 4 entries; build larger maps explicitly")
    ContextExtension(vars.toMap)
  }

  def input(b: ErgoBox, extension: ContextExtension, proof: Array[Byte] = Array.emptyByteArray): Input =
    Input(b.id, ProverResult(proof, extension))

  def tx(inputs: Seq[Input], outputs: Seq[ErgoBoxCandidate]): ErgoLikeTransaction =
    new ErgoLikeTransaction(inputs.toIndexedSeq, IndexedSeq.empty[DataInput], outputs.toIndexedSeq)

  /** Canonical transaction bytes (`ErgoLikeTransactionSerializer`; ergo-core's
    * `ErgoTransactionSerializer` writes the same bytes). */
  def txBytes(t: ErgoLikeTransaction): Array[Byte] = ErgoLikeTransaction.serializer.toBytes(t)

  /** The JVM storage fee exactly as `checkExpiredBox` computes it (`ErgoInterpreter.scala:43`):
    * `params.storageFeeFactor * box.bytes.length` is Scala `Int * Int`, so it wraps at 32 bits. */
  def jvmStorageFee(b: ErgoBox, factor: Int = StorageFeeFactor): Int = factor * b.bytes.length
  /** The same product without the wrap. */
  def trueStorageFee(b: ErgoBox, factor: Int = StorageFeeFactor): Long = factor.toLong * b.bytes.length

  /** Coll^n[Byte]: n nested collections, each outer one holding one element, the innermost empty. */
  def nestedCollOfBytes(n: Int): Constant[SType] = {
    require(n >= 2, s"Coll^$n[Byte]")
    val (value, tpe) = (2 to n).foldLeft[(Any, SType)]((Colls.emptyColl(Evaluation.stypeToRType(SByte)), SCollection(SByte))) {
      case ((inner, tInner), _) =>
        (Colls.fromItems[Any](inner)(Evaluation.stypeToRType(tInner).asInstanceOf[sigma.data.RType[Any]]),
          SCollection(tInner))
    }
    Constant[SType](value.asInstanceOf[SType#WrappedType], tpe)
  }

  def vlqU32(v: Long): Array[Byte] = { val w = SigmaSerializer.startWriter(); w.putUInt(v); w.toBytes }

  /** Replace the single occurrence of `from` in `bytes` with `to` (same length). Fails loud on zero or
    * several occurrences, so a splice can never land on the wrong field. */
  def spliceUnique(bytes: Array[Byte], from: Array[Byte], to: Array[Byte]): Array[Byte] = {
    require(from.length == to.length, s"splice must keep the length (${from.length} != ${to.length})")
    val at = bytes.indexOfSlice(from)
    require(at >= 0, s"splice source ${hex(from)} not found in ${hex(bytes)}")
    require(bytes.indexOfSlice(from, at + 1) < 0, s"splice source ${hex(from)} occurs more than once")
    bytes.take(at) ++ to ++ bytes.drop(at + from.length)
  }

  def hex(b: Array[Byte]): String = Base16.encode(b)
}
