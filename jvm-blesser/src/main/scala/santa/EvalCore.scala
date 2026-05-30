package santa

import scorex.util.bytesToId
import scorex.util.encode.Base16

import io.circe.Json

import org.ergoplatform.{ErgoBox, ErgoLikeContext, ErgoLikeTransaction}
import org.ergoplatform.validation.ValidationRules

import sigma.{Coll, Colls, Evaluation, GroupElement, Header, PreHeader, VersionContext}
import sigma.data.{CBigInt, CSigmaProp, SigmaBoolean}
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

  private def tag(s: String): Json = Json.obj("tag" -> Json.fromString(s))

  /** Encode an SType as the TS `SType` union JSON (mirrors the canonical `{tag:"S…"}` schema). */
  def stypeToJson(t: sigma.ast.SType): Json = {
    import sigma.ast._
    t match {
      case SBoolean    => tag("SBoolean")
      case SByte       => tag("SByte")
      case SShort      => tag("SShort")
      case SInt        => tag("SInt")
      case SLong       => tag("SLong")
      case SBigInt     => tag("SBigInt")
      case SUnit       => tag("SUnit")
      case SAny        => tag("SAny")
      case SGroupElement => tag("SGroupElement")
      case SSigmaProp    => tag("SSigmaProp")
      case SBox        => tag("SBox")
      case SHeader     => tag("SHeader")
      case SPreHeader  => tag("SPreHeader")
      case c: SCollection[_] => Json.obj("tag" -> Json.fromString("SColl"),   "elem" -> stypeToJson(c.elemType))
      case o: SOption[_]     => Json.obj("tag" -> Json.fromString("SOption"), "elem" -> stypeToJson(o.elemType))
      case tup: STuple       => Json.obj("tag" -> Json.fromString("STuple"),
                                         "items" -> Json.arr(tup.items.map(stypeToJson): _*))
      case _ => Json.obj("tag" -> Json.fromString("SUnknown"), "repr" -> Json.fromString(t.toString))
    }
  }

  /** Encode an evaluated value as the typed `{ kind, … }` form (mirrors the fork's
    * SValue JSON). GroupElement → 33-byte SEC1 hex; richer kinds added as scaled. */
  def valueToJson(v: Any): Json = v match {
    case g: GroupElement =>
      Json.obj("kind" -> Json.fromString("GroupElement"),
               "bytes_hex" -> Json.fromString(Base16.encode(g.getEncoded.toArray)))
    case b: Boolean => Json.obj("kind" -> Json.fromString("Boolean"), "value" -> Json.fromBoolean(b))
    case n: Byte    => Json.obj("kind" -> Json.fromString("Byte"),    "value" -> Json.fromInt(n.toInt))
    case n: Short   => Json.obj("kind" -> Json.fromString("Short"),   "value" -> Json.fromInt(n.toInt))
    case n: Int     => Json.obj("kind" -> Json.fromString("Int"),     "value" -> Json.fromInt(n))
    case n: Long    => Json.obj("kind" -> Json.fromString("Long"),    "value" -> Json.fromString(n.toString))
    case b: CBigInt => Json.obj("kind" -> Json.fromString("BigInt"),
                                "value" -> Json.fromString(b.wrappedValue.toString))
    case sp: CSigmaProp =>
      Json.obj("kind"    -> Json.fromString("SigmaProp"),
               "raw_hex" -> Json.fromString(Base16.encode(SigmaBoolean.serializer.toBytes(sp.sigmaTree))))
    case c: Coll[_] =>
      Json.obj("kind"  -> Json.fromString("Coll"),
               "elem"  -> stypeToJson(Evaluation.rtypeToSType(c.tItem)),
               "items" -> Json.arr(c.toArray.toIndexedSeq.map(valueToJson): _*))
    case (a, b) =>
      Json.obj("kind"  -> Json.fromString("Tuple"),
               "items" -> Json.arr(valueToJson(a), valueToJson(b)))
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
