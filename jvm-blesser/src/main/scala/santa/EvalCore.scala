package santa

import scorex.util.bytesToId
import scorex.util.encode.Base16

import io.circe.Json

import org.ergoplatform.{ErgoBox, ErgoLikeContext, ErgoLikeTransaction}
import org.ergoplatform.validation.ValidationRules

import sigma.{Colls, GroupElement, Header, PreHeader, VersionContext}
import sigma.ast.{ErgoTree, JitCost}
import sigma.crypto.CryptoConstants
import sigma.data.AvlTreeData
import sigma.interpreter.ContextExtension
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}
import sigma.util.Extensions.EcpOps

import sigmastate.eval._ // CPreHeader
import sigmastate.interpreter.{CErgoTreeEvaluator, CostAccumulator}
import sigmastate.interpreter.CErgoTreeEvaluator.DefaultEvalSettings

/** Shared eval core: deserialize an ErgoTree and evaluate its root expression
  * through the canonical reference interpreter (`sigma-state`), returning the typed
  * value + raw JIT cost (or a coarse error). Used by the blesser (to produce the
  * nice list) and the JVM reference runner (Rudolph, to produce actuals).
  *
  * The context is a minimal dummy (replicated from sigma's test-scoped
  * `ErgoLikeContextTesting.dummy`, which isn't in the published jar). Cost is the
  * RAW jit cost (the companion `eval` would scale it to block cost ÷10 on return).
  */
object EvalCore {

  private val dummyPubkey: Array[Byte] =
    GroupElementSerializer.toBytes(CryptoConstants.dlogGroup.generator)

  private def dummyPreHeader(height: Int): PreHeader = CPreHeader(
    version = 0,
    parentId = Colls.emptyColl[Byte],
    timestamp = 3L,
    nBits = 0L,
    height = height,
    minerPk = GroupElementSerializer.parse(SigmaSerializer.startReader(dummyPubkey)).toGroupElement,
    votes = Colls.emptyColl[Byte]
  )

  private def dummyContext(tree: ErgoTree, activatedVersion: Byte): ErgoLikeContext = {
    val selfBox = new ErgoBox(
      value = 1000000L,
      ergoTree = tree,
      transactionId = bytesToId(Array.fill(32)(0: Byte)),
      index = 0.toShort,
      creationHeight = 0
    )
    new ErgoLikeContext(
      lastBlockUtxoRoot = AvlTreeData.dummy,
      headers = Colls.emptyColl[Header],
      preHeader = dummyPreHeader(0),
      dataBoxes = IndexedSeq.empty,
      boxesToSpend = IndexedSeq(selfBox),
      spendingTransaction = ErgoLikeTransaction(IndexedSeq(), IndexedSeq()),
      selfIndex = 0,
      extension = ContextExtension.empty,
      validationSettings = ValidationRules.currentSettings,
      costLimit = DefaultEvalSettings.scriptCostLimitInEvaluator,
      initCost = 0L,
      activatedScriptVersion = activatedVersion
    ).withErgoTreeVersion(tree.version)
  }

  def errClass(t: Throwable): String =
    s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}"

  /** Encode an evaluated value as the typed `{ kind, … }` form (mirrors the fork's
    * SValue JSON). GroupElement → 33-byte SEC1 hex; richer kinds added as scaled. */
  def valueToJson(v: Any): Json = v match {
    case g: GroupElement =>
      Json.obj("kind" -> Json.fromString("GroupElement"),
               "bytes_hex" -> Json.fromString(Base16.encode(g.getEncoded.toArray)))
    case other =>
      Json.obj("kind" -> Json.fromString("Opaque"),
               "repr" -> Json.fromString(s"${other.getClass.getSimpleName}:$other"))
  }

  /** Evaluate one entry under the given activated script version (the ErgoTree
    * version is the tree's own). Returns the tree version and the outcome:
    * Right(typed value, raw jit cost) on success, Left(coarse error detail) on
    * failure. The tree version is returned even on eval failure (the tree still
    * deserialized); on deserialize failure it defaults to 0. */
  def evalEntry(treeBytesHex: String, activated: Byte): (Byte, Either[String, (Json, Long)]) =
    try {
      val bytes   = Base16.decode(treeBytesHex).get
      val tree    = sigma.santa.LenientErgoTree.deserialize(bytes)
      val treeVer = tree.version
      try {
        val (rawValue, jitCost) = VersionContext.withVersions(activated, treeVer) {
          val ctx = dummyContext(tree, activated)
          val acc = new CostAccumulator(
            initialCost = JitCost.fromBlockCost(Math.toIntExact(ctx.initCost)),
            costLimit   = Some(JitCost.fromBlockCost(Math.toIntExact(ctx.costLimit))))
          val (v, _blockCost) = CErgoTreeEvaluator.eval(
            ctx.toSigmaContext(), acc, tree.constants,
            tree.toProposition(replaceConstants = false), DefaultEvalSettings)
          (v, acc.totalCost.value)
        }
        (treeVer, Right((valueToJson(rawValue), jitCost.toLong)))
      } catch { case t: Throwable => (treeVer, Left(errClass(t))) }
    } catch { case t: Throwable => (0.toByte, Left(errClass(t))) }
}
