package santa

import scorex.util.bytesToId
import scorex.util.encode.Base16

import io.circe.Json

import org.ergoplatform.{ErgoBox, ErgoLikeContext, ErgoLikeTransaction}
import org.ergoplatform.validation.ValidationRules

import sigma.{Coll, Colls, Evaluation, GroupElement, Header, PreHeader, VersionContext}
import sigma.data.{CBigInt, CSigmaProp, SigmaBoolean}
import sigma.ast.{
  BigIntConstant, BooleanConstant, ByteConstant, CollectionConstant, Constant, ErgoTree,
  EvaluatedValue, GroupElementConstant, IntConstant, JitCost, LongConstant, SBigInt, SBoolean,
  SByte, SCollection, SGroupElement, SInt, SLong, SShort, SType, ShortConstant, STuple, Tuple
}
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

  // ── SValue JSON decoder ────────────────────────────────────────────────────
  // Inverse of valueToJson: reconstructs the sigma-state runtime value wrapped
  // in an EvaluatedValue so it can be bound to context var 1 via ContextExtension.
  // Covers the Stage-1 kinds: Boolean, Byte, Short, Int, Long, BigInt,
  // GroupElement, Coll (incl. nested), Tuple (pair).

  /** Decode a `{"tag":"S…"}` SType JSON (as emitted by stypeToJson) back to SType. */
  def stypeFromJson(j: Json): SType = {
    val tag = j.hcursor.downField("tag").as[String]
      .fold(e => sys.error(s"stypeFromJson: missing/invalid tag in $j: $e"), identity)
    tag match {
      case "SBoolean"      => SBoolean
      case "SByte"         => SByte
      case "SShort"        => SShort
      case "SInt"          => SInt
      case "SLong"         => SLong
      case "SBigInt"       => SBigInt
      case "SGroupElement" => SGroupElement
      case "SColl"         =>
        val elem = j.hcursor.downField("elem").as[Json]
          .fold(e => sys.error(s"stypeFromJson: missing elem in Coll type: $e"), identity)
        SCollection(stypeFromJson(elem))
      case "STuple"        =>
        val items = j.hcursor.downField("items").as[List[Json]]
          .fold(e => sys.error(s"stypeFromJson: missing items in Tuple type: $e"), identity)
        STuple(items.map(stypeFromJson).toIndexedSeq)
      case other => sys.error(s"stypeFromJson: unsupported type tag '$other' — not yet supported")
    }
  }

  /** Decode a `{"kind":"…", …}` SValue JSON (as emitted by valueToJson) back to an
    * EvaluatedValue so it can be used as a context-var binding in evalApplied.
    *
    * Covered kinds: Boolean, Byte, Short, Int, Long, BigInt, GroupElement,
    * Coll (with `elem` SType tag), Tuple (pair).
    * Unsupported kinds surface an immediate sys.error (not a silent wrong value). */
  def decodeInputConstant(j: Json): EvaluatedValue[_ <: SType] = {
    val cur  = j.hcursor
    val kind = cur.downField("kind").as[String]
      .fold(e => sys.error(s"decodeInputConstant: missing/invalid kind in $j: $e"), identity)
    kind match {
      case "Boolean" =>
        val v = cur.downField("value").as[Boolean]
          .fold(e => sys.error(s"decodeInputConstant Boolean: $e"), identity)
        BooleanConstant.fromBoolean(v)

      case "Byte" =>
        val v = cur.downField("value").as[Int]
          .fold(e => sys.error(s"decodeInputConstant Byte: $e"), identity)
        ByteConstant(v.toByte)

      case "Short" =>
        val v = cur.downField("value").as[Int]
          .fold(e => sys.error(s"decodeInputConstant Short: $e"), identity)
        ShortConstant(v.toShort)

      case "Int" =>
        val v = cur.downField("value").as[Int]
          .fold(e => sys.error(s"decodeInputConstant Int: $e"), identity)
        IntConstant(v)

      case "Long" =>
        // Long is encoded as a decimal string in valueToJson
        val s = cur.downField("value").as[String]
          .fold(e => sys.error(s"decodeInputConstant Long: $e"), identity)
        LongConstant(s.toLong)

      case "BigInt" =>
        // BigInt encoded as decimal string
        val s = cur.downField("value").as[String]
          .fold(e => sys.error(s"decodeInputConstant BigInt: $e"), identity)
        BigIntConstant(new java.math.BigInteger(s))

      case "GroupElement" =>
        // GroupElement encoded as 33-byte SEC1 hex
        val hex = cur.downField("bytes_hex").as[String]
          .fold(e => sys.error(s"decodeInputConstant GroupElement: $e"), identity)
        val bytes  = Base16.decode(hex).get
        val ecPoint = GroupElementSerializer.parse(SigmaSerializer.startReader(bytes))
        GroupElementConstant(ecPoint)

      case "Coll" =>
        val elemJson = cur.downField("elem").as[Json]
          .fold(e => sys.error(s"decodeInputConstant Coll: missing elem: $e"), identity)
        val itemsJson = cur.downField("items").as[List[Json]]
          .fold(e => sys.error(s"decodeInputConstant Coll: missing items: $e"), identity)
        val elemSType = stypeFromJson(elemJson)
        decodeColl(elemSType, itemsJson)

      case "Tuple" =>
        val itemsJson = cur.downField("items").as[List[Json]]
          .fold(e => sys.error(s"decodeInputConstant Tuple: missing items: $e"), identity)
        if (itemsJson.size != 2)
          sys.error(s"decodeInputConstant Tuple: expected pair, got ${itemsJson.size} items")
        val decodedItems = itemsJson.map(decodeInputConstant)
        Tuple(decodedItems.toIndexedSeq)

      case other =>
        sys.error(s"decode: '$other' not yet supported")
    }
  }

  /** Build a CollectionConstant from the given element SType and decoded items.
    * Dispatches on the concrete element type to produce the correctly-typed
    * CollectionConstant without unchecked casts at the call site.
    *
    * The RType instances come from the `sigma` package object where they are
    * declared as implicit vals (sigma.ByteType, sigma.IntType, etc.). */
  private def decodeColl(elemSType: SType, itemsJson: List[Json]): EvaluatedValue[_ <: SType] = {
    elemSType match {
      case SBoolean =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[Boolean]).toArray
        val coll = Colls.fromArray(arr)(sigma.BooleanType)
        CollectionConstant[SBoolean.type](coll, SBoolean)

      case SByte =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[Byte]).toArray
        val coll = Colls.fromArray(arr)(sigma.ByteType)
        CollectionConstant[SByte.type](coll, SByte)

      case SShort =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[Short]).toArray
        val coll = Colls.fromArray(arr)(sigma.ShortType)
        CollectionConstant[SShort.type](coll, SShort)

      case SInt =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[Int]).toArray
        val coll = Colls.fromArray(arr)(sigma.IntType)
        CollectionConstant[SInt.type](coll, SInt)

      case SLong =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[Long]).toArray
        val coll = Colls.fromArray(arr)(sigma.LongType)
        CollectionConstant[SLong.type](coll, SLong)

      case SBigInt =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[sigma.BigInt]).toArray
        val coll = Colls.fromArray(arr)(sigma.BigIntRType)
        CollectionConstant[SBigInt.type](coll, SBigInt)

      case SGroupElement =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[GroupElement]).toArray
        val coll = Colls.fromArray(arr)(sigma.GroupElementRType)
        CollectionConstant[SGroupElement.type](coll, SGroupElement)

      case inner: SCollection[_] =>
        // Nested Coll: use unchecked cast; the SType tag is authoritative for eval/serialization.
        // We go through Constant.apply directly to avoid CollectionConstant's type check.
        val values: List[Any] = itemsJson.map(j => (decodeInputConstant(j): Any) match {
          case ev: EvaluatedValue[_] => ev.value
          case other                 => other
        })
        val arr     = values.toArray.asInstanceOf[Array[Any]]
        val outerRT = Evaluation.stypeToRType(inner).asInstanceOf[sigma.data.RType[Any]]
        val coll    = Colls.fromArray(arr)(outerRT)
        val scolTyped = inner.asInstanceOf[SCollection[SType]]
        Constant[SCollection[SType]](coll.asInstanceOf[SCollection[SType]#WrappedType], scolTyped)

      case other =>
        sys.error(s"decodeColl: elem type '$other' not yet supported")
    }
  }

  // ── Context with var 1 bound to an input value ─────────────────────────────

  /** Dummy context identical to dummyContext but with var 1 bound to `varBinding`.
    * Used by evalApplied to supply the function input via ContextExtension. */
  private def contextWithVar1(tree: ErgoTree, activatedVersion: Byte,
                              varBinding: EvaluatedValue[_ <: SType]): ErgoLikeContext = {
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
      extension = ContextExtension(Map(1.toByte -> varBinding)),
      validationSettings = ValidationRules.currentSettings,
      costLimit = DefaultEvalSettings.scriptCostLimitInEvaluator,
      initCost = 0L,
      activatedScriptVersion = activatedVersion
    ).withErgoTreeVersion(tree.version)
  }

  /** Apply a serialized function tree to an input value (bound to context var 1) and
    * evaluate it, returning the typed value JSON + raw JIT cost (or a coarse error).
    *
    * The function tree encodes `{ val func = <script>; func(getVar[A](1).get) }`.
    * The input is decoded from its SValue JSON and bound to var 1 via ContextExtension.
    *
    * Same (treeVersion, Either) shape as evalEntry; treeVersion defaults to 0 on
    * deserialize failure, is preserved on eval failure. */
  def evalApplied(treeBytesHex: String, inputJson: Json,
                  activated: Byte): (Byte, Either[String, (Json, Long)]) =
    try {
      val bytes   = Base16.decode(treeBytesHex).get
      val tree    = sigma.santa.LenientErgoTree.deserialize(bytes)
      val treeVer = tree.version
      try {
        val input   = decodeInputConstant(inputJson)
        val (rawValue, jitCost) = VersionContext.withVersions(activated, treeVer) {
          val ctx = contextWithVar1(tree, activated, input)
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
