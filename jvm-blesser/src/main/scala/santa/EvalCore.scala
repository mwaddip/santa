package santa

import scorex.crypto.authds.ADDigest
import scorex.util.bytesToId
import scorex.util.encode.Base16

import io.circe.Json

import org.ergoplatform.{DataInput, ErgoBox, ErgoBoxCandidate, ErgoHeader, ErgoLikeContext, ErgoLikeTransaction, Input}
import org.ergoplatform.validation.ValidationRules

import sigma.{Coll, Colls, Evaluation, GroupElement, Header, PreHeader, VersionContext}
import sigma.data.{CAvlTree, CBigInt, CBox, CHeader, CSigmaProp, CUnsignedBigInt, SigmaBoolean}
import sigma.ast.{
  AvlTreeConstant, BigIntConstant, BooleanConstant, BoxConstant, ByteArrayConstant, ByteConstant,
  CollectionConstant, Constant,
  ErgoTree, EvaluatedValue, GroupElementConstant, HeaderConstant, IntConstant, JitCost,
  LongConstant, SAvlTree, SBigInt, SBoolean, SBox, SByte, SCollection, SGroupElement, SHeader, SInt,
  SLong, SOption, SPreHeader, SShort, SSigmaProp, SType, SUnsignedBigInt, ShortConstant,
  SigmaPropConstant, StringConstant, STuple, UnsignedBigIntConstant
}
import sigma.crypto.CryptoConstants
import sigma.data.{AvlTreeData, AvlTreeFlags}
import sigma.interpreter.{ContextExtension, ProverResult}
import sigma.serialization.{DataSerializer, GroupElementSerializer, SigmaSerializer}
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

  // The contract's canonical eval context (runner-contract.md §2): preHeader.version is
  // pinned to activated+1 (block-version convention) because sigma-rust DERIVES script
  // activation from the block version — version 0 would break its gating; the JVM treats
  // it as data (activation rides the separate activatedScriptVersion field either way).
  private def dummyPreHeader(height: Int, activated: Byte): PreHeader = CPreHeader(
    version = (activated + 1).toByte,
    parentId = Colls.fromArray(Array.fill(32)(0: Byte)), // chain wire width (BlockId)
    timestamp = 3L,
    nBits = 0L,
    height = height,
    minerPk = GroupElementSerializer.parse(SigmaSerializer.startReader(dummyPubkey)).toGroupElement,
    votes = Colls.fromArray(Array.fill(3)(0: Byte)) // chain wire width (Votes)
  )

  /** Mirror of the production constant-substitution conditionality
    * (`Interpreter.fullReduction`, sigma-state Interpreter.scala:218): trees WITHOUT
    * deserialize ops are evaluated lazily (ConstantPlaceholders resolved against the
    * constants array, CP visit = JitCost 1); deserialize-bearing segregated trees are
    * evaluated from the SUBSTITUTED proposition (Constant visit = JitCost 5) — that is
    * what consensus charges on-chain. Blessing through this seam keeps the eval tier's
    * cost dimension production-faithful on the only tree class where the two differ.
    * Decision 2026-06-06 (the ergots DC dead-branch consult; probe: lazy 12 vs
    * substituted 20, Δ = 2 ex-placeholders × 4). */
  private def productionReplaceConstants(tree: ErgoTree): Boolean =
    tree.isConstantSegregation && tree.hasDeserialize

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
      preHeader = dummyPreHeader(0, activatedVersion),
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
      case SUnsignedBigInt => tag("SUnsignedBigInt")
      case SUnit       => tag("SUnit")
      case SAny        => tag("SAny")
      case SGroupElement => tag("SGroupElement")
      case SSigmaProp    => tag("SSigmaProp")
      case SBox        => tag("SBox")
      case SHeader     => tag("SHeader")
      case SPreHeader  => tag("SPreHeader")
      // STuple MUST precede SCollection: STuple <: SCollection, so a `SCollection`
      // case placed first would shadow it (STuple unreachable → tuples mis-encode as
      // {tag:"SColl"}). The cross-check can't catch this (both sides share the
      // encoder); it's guarded by a unit test in EvalCoreTest.
      case tup: STuple       => Json.obj("tag" -> Json.fromString("STuple"),
                                         "items" -> Json.arr(tup.items.map(stypeToJson): _*))
      case c: SCollection[_] => Json.obj("tag" -> Json.fromString("SColl"),   "elem" -> stypeToJson(c.elemType))
      case o: SOption[_]     => Json.obj("tag" -> Json.fromString("SOption"), "elem" -> stypeToJson(o.elemType))
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
    case u: CUnsignedBigInt => Json.obj("kind" -> Json.fromString("UnsignedBigInt"),
                                        "value" -> Json.fromString(u.wrappedValue.toString))
    case sp: CSigmaProp =>
      Json.obj("kind"    -> Json.fromString("SigmaProp"),
               "raw_hex" -> Json.fromString(Base16.encode(SigmaBoolean.serializer.toBytes(sp.sigmaTree))))
    case b: CBox =>
      Json.obj("kind"      -> Json.fromString("Box"),
               "bytes_hex" -> Json.fromString(Base16.encode(b.ebox.bytes)))
    case h: CHeader =>
      Json.obj("kind"      -> Json.fromString("Header"),
               "bytes_hex" -> Json.fromString(Base16.encode(h.ergoHeader.bytes)))
    case avl: CAvlTree =>
      // AvlTreeData (digest + flags + keyLength + optional valueLength) via the same
      // CoreSerializer Global.serialize delegates to. Mirrors Box/Header (bytes_hex).
      Json.obj("kind"      -> Json.fromString("AvlTree"),
               "bytes_hex" -> Json.fromString(Base16.encode(AvlTreeData.serializer.toBytes(avl.treeData))))
    case c: Coll[_] =>
      Json.obj("kind"  -> Json.fromString("Coll"),
               "elem"  -> stypeToJson(Evaluation.rtypeToSType(c.tItem)),
               "items" -> Json.arr(c.toArray.toIndexedSeq.map(valueToJson): _*))
    case (a, b) =>
      Json.obj("kind"  -> Json.fromString("Tuple"),
               "items" -> Json.arr(valueToJson(a), valueToJson(b)))
    case opt: Option[_] => opt match {
      case Some(x) => Json.obj("kind" -> Json.fromString("Option"), "value" -> valueToJson(x))
      case None    => Json.obj("kind" -> Json.fromString("Option"), "value" -> Json.Null)
    }
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
            tree.toProposition(replaceConstants = productionReplaceConstants(tree)), DefaultEvalSettings)
          (v, acc.totalCost.value)
        }
        (treeVer, Right((valueToJson(rawValue), jitCost.toLong)))
      } catch { case t: Throwable => (treeVer, Left(errClass(t))) }
    } catch { case t: Throwable => (0.toByte, Left(errClass(t))) }

  // ── SValue JSON decoder ────────────────────────────────────────────────────
  // Inverse of valueToJson: reconstructs the sigma-state runtime value wrapped
  // in an EvaluatedValue so it can be bound to context var 1 via ContextExtension.
  // Covers: Boolean, Byte, Short, Int, Long, BigInt, UnsignedBigInt, GroupElement,
  // Coll (incl. nested), Tuple (pair), Option (Some only), Box, Header, AvlTree.

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
      case "SUnsignedBigInt" => SUnsignedBigInt
      case "SOption" =>
        val elem = j.hcursor.downField("elem").as[Json]
          .fold(e => sys.error(s"stypeFromJson: missing elem in Option type: $e"), identity)
        SOption(stypeFromJson(elem))
      case "SGroupElement" => SGroupElement
      case "SBox"          => SBox
      case "SHeader"       => SHeader
      case "SPreHeader"    => SPreHeader
      case "SAvlTree"      => SAvlTree
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

  /** Build a `Constant[S]` from a runtime value typed as `Any` and an SType.
    *
    * Why a generic helper instead of `Constant[STuple](pair.asInstanceOf[STuple#WrappedType], …)`
    * at the call site: on Scala 2.12, casting to a *concrete* `S#WrappedType` (e.g.
    * `STuple#WrappedType` == `Coll[Any]`) emits a checkcast to `sigma.Coll`, which throws
    * `ClassCastException` when the value is a Scala `Tuple2`. Here `S` stays a type
    * parameter, so `S#WrappedType` erases to `java.lang.Object` and the cast is a no-op.
    * `ConstantNode` validates only via the surface `isCorrectType`, which accepts a
    * `Tuple2` for an `STuple` and any `Coll` for an `SCollection`. */
  private def mkConstant[S <: SType](value: Any, tpe: S): Constant[S] =
    Constant(value.asInstanceOf[S#WrappedType], tpe)

  /** Decode a `{"kind":"…", …}` SValue JSON (as emitted by valueToJson) back to an
    * EvaluatedValue so it can be used as a context-var binding in evalApplied.
    *
    * Covered kinds: Boolean, Byte, Short, Int, Long, BigInt, UnsignedBigInt,
    * GroupElement, Coll (with `elem` SType tag, incl. nested Coll[Coll[_]]), Tuple (pair),
    * Option (Some only — None-as-input errors), Box, Header, AvlTree (each from its `bytes_hex`).
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
        // Read as Int then range-check: `.toByte` would silently wrap out-of-range
        // input (e.g. 200 -> -56), reintroducing a silent wrong value. Error instead.
        val v = cur.downField("value").as[Int]
          .fold(e => sys.error(s"decodeInputConstant Byte: $e"), identity)
        if (v < Byte.MinValue || v > Byte.MaxValue)
          sys.error(s"decodeInputConstant Byte: value $v out of Byte range")
        ByteConstant(v.toByte)

      case "Short" =>
        // Range-check for the same reason as Byte (`.toShort` would silently wrap).
        val v = cur.downField("value").as[Int]
          .fold(e => sys.error(s"decodeInputConstant Short: $e"), identity)
        if (v < Short.MinValue || v > Short.MaxValue)
          sys.error(s"decodeInputConstant Short: value $v out of Short range")
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

      case "Coll[Byte]" =>
        // Compact byte-collection form (runner contract §2 input encoding): semantically
        // identical to {"kind":"Coll","elem":{"tag":"SByte"},"items":[Byte...]} — exists
        // because per-item JSON is ~35× the payload for large byte blobs (the SBox
        // token-window family carries >4KB box bytes as context input).
        val hex = cur.downField("value_hex").as[String]
          .fold(e => sys.error(s"decodeInputConstant Coll[Byte]: missing value_hex: $e"), identity)
        val bytes = Base16.decode(hex)
          .getOrElse(sys.error("decodeInputConstant Coll[Byte]: value_hex decode failed"))
        ByteArrayConstant(Colls.fromArray(bytes))

      case "Tuple" =>
        val itemsJson = cur.downField("items").as[List[Json]]
          .fold(e => sys.error(s"decodeInputConstant Tuple: missing items: $e"), identity)
        if (itemsJson.size != 2)
          sys.error(s"decodeInputConstant Tuple: expected pair, got ${itemsJson.size} items")
        // The runtime value of a pair MUST be a Scala Tuple2, not a Coll[Any].
        // valueToJson emits pairs via `case (a, b)` (a Scala Tuple2), so the decode
        // inverse must produce a Tuple2 too. Building `Tuple(items)` here is WRONG:
        // sigma.ast.Tuple's `.value` (read when this is bound as a context-var) is a
        // Coll[Object], so getVar[(A,B)] would surface a Coll — a silent wrong value.
        // Instead wrap the Scala pair directly as a Constant[STuple].
        val a = decodeInputConstant(itemsJson(0))
        val b = decodeInputConstant(itemsJson(1))
        val pair: Any = (a.value, b.value)
        mkConstant[STuple](pair, STuple(a.tpe, b.tpe))

      case "UnsignedBigInt" =>
        // decimal string, like BigInt; CUnsignedBigInt's ctor throws on negative / >256-bit
        val s = cur.downField("value").as[String]
          .fold(e => sys.error(s"decodeInputConstant UnsignedBigInt: $e"), identity)
        UnsignedBigIntConstant(new java.math.BigInteger(s))

      case "Option" =>
        // Some: element type implied by the inner value (decode it, re-wrap as SOption).
        // None: untyped at the value level — unsupported as input. No skipped case needs
        // None-as-input (the lone None is an OUTPUT); add an explicit elem if one ever does.
        cur.downField("value").focus match {
          case Some(inner) if !inner.isNull =>
            val c = decodeInputConstant(inner)
            mkConstant[SOption[SType]](Some(c.value), SOption(c.tpe))
          case _ =>
            sys.error("decodeInputConstant Option: None-as-input is unsupported " +
              "(untyped; no element type to reconstruct)")
        }

      case "Box" =>
        // Canonical ErgoBox bytes (identity-by-slice: parse stores the input slice as
        // _bytes, so encode(box.bytes) == the input hex). Same entry-point as GroupElement.
        val hex = cur.downField("bytes_hex").as[String]
          .fold(e => sys.error(s"decodeInputConstant Box: $e"), identity)
        val bytes = Base16.decode(hex).get
        val ebox  = ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(bytes))
        BoxConstant(CBox(ebox))

      case "Header" =>
        // Canonical ErgoHeader bytes (identity-by-slice, as for Box).
        val hex = cur.downField("bytes_hex").as[String]
          .fold(e => sys.error(s"decodeInputConstant Header: $e"), identity)
        val bytes  = Base16.decode(hex).get
        val header = ErgoHeader.sigmaSerializer.parse(SigmaSerializer.startReader(bytes))
        HeaderConstant(new CHeader(header))

      case "AvlTree" =>
        // Canonical AvlTreeData bytes via the same CoreSerializer Global.serialize delegates
        // to (mirrors Box/Header). AvlTreeConstant wraps it as CAvlTree at type SAvlTree.
        val hex = cur.downField("bytes_hex").as[String]
          .fold(e => sys.error(s"decodeInputConstant AvlTree: $e"), identity)
        val bytes = Base16.decode(hex).get
        val data  = AvlTreeData.serializer.parse(SigmaSerializer.startReader(bytes))
        AvlTreeConstant(data)

      case "SigmaProp" =>
        // Inverse of valueToJson's SigmaProp encode:
        //   raw_hex = Base16(SigmaBoolean.serializer.toBytes(sp.sigmaTree))
        // so parse the bytes back to a SigmaBoolean (same serializer the encoder used),
        // wrap in CSigmaProp, and build a SigmaPropConstant. SigmaSerializer.startReader
        // yields a SigmaByteReader (a CoreByteReader), accepted by serializer.parse — the
        // same reader the GroupElement/Box/Header decodes above pass to their parsers.
        val hex = cur.downField("raw_hex").as[String]
          .fold(e => sys.error(s"decodeInputConstant SigmaProp: $e"), identity)
        val bytes = Base16.decode(hex).get
        val sigmaTree: SigmaBoolean =
          SigmaBoolean.serializer.parse(SigmaSerializer.startReader(bytes))
        SigmaPropConstant(CSigmaProp(sigmaTree))

      case "String" =>
        // SString IS data-serializable (CoreDataSerializer) though no ErgoScript op produces a
        // runtime String value — supported so the SigmaProp-eq String-reachability probe can feed one.
        StringConstant(cur.downField("value").as[String]
          .fold(e => sys.error(s"decodeInputConstant String: $e"), identity))

      case other =>
        sys.error(s"decodeInputConstant: '$other' not yet supported")
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

      case SUnsignedBigInt =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[sigma.UnsignedBigInt]).toArray
        val coll = Colls.fromArray(arr)(sigma.UnsignedBigIntRType)
        CollectionConstant[SUnsignedBigInt.type](coll, SUnsignedBigInt)

      case SGroupElement =>
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[GroupElement]).toArray
        val coll = Colls.fromArray(arr)(sigma.GroupElementRType)
        CollectionConstant[SGroupElement.type](coll, SGroupElement)

      case SBox =>
        // Coll[Box]: element runtime values are CBox (<: Box), decoded from each item's
        // {kind:"Box",bytes_hex}. RType is sigma.BoxRType (== stypeToRType(SBox)).
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[sigma.Box]).toArray
        val coll = Colls.fromArray(arr)(sigma.BoxRType)
        CollectionConstant[SBox.type](coll, SBox)

      case SHeader =>
        // Coll[Header]: element values are CHeader (<: Header), from {kind:"Header",bytes_hex}.
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[Header]).toArray
        val coll = Colls.fromArray(arr)(sigma.HeaderRType)
        CollectionConstant[SHeader.type](coll, SHeader)

      case SPreHeader =>
        // Coll[PreHeader]: present so the TYPE-TAG decode doesn't crash. valueToJson emits
        // PreHeader VALUES as Opaque (it doesn't model them), so any populated Coll[PreHeader]
        // is skipped upstream by hasOpaque; this branch carries the (typically empty) coll.
        val arr  = itemsJson.map(j => decodeInputConstant(j).value.asInstanceOf[PreHeader]).toArray
        val coll = Colls.fromArray(arr)(sigma.PreHeaderRType)
        CollectionConstant[SPreHeader.type](coll, SPreHeader)

      case inner: SCollection[_] =>
        // Nested Coll (elem type is itself a Coll). The element runtime values are the
        // decoded inner Colls; the OUTER coll's element RType is `stypeToRType(inner)`.
        // Crucially, the Constant's tpe must be the FULL collection type
        // `SCollection(inner)` (e.g. Coll[Coll[Int]]) — NOT `inner` (Coll[Int]). With
        // the wrong (element-only) tpe, toSigmaContext derives the wrong RType and
        // getVar[Coll[Coll[_]]] fails with InvalidType. (Recursion handles any depth.)
        val values  = itemsJson.map(j => decodeInputConstant(j).value: Any).toArray
        val elemRT  = Evaluation.stypeToRType(inner).asInstanceOf[sigma.data.RType[Any]]
        val coll    = Colls.fromArray(values)(elemRT)
        mkConstant[SCollection[SType]](coll, SCollection(inner.asInstanceOf[SType]))

      case other =>
        sys.error(s"decodeColl: elem type '$other' not yet supported")
    }
  }

  // ── Wire-encodability gate ─────────────────────────────────────────────────

  /** Serialize `c.value` as `c.tpe` through sigma-state's DataSerializer.
    *
    * Generic over `S <: SType` for the SAME reason as `mkConstant`: `DataSerializer.serialize`
    * takes `v: S#WrappedType`, and casting `c.value` to a *concrete* `S#WrappedType` would emit
    * a checkcast that throws (e.g. STuple#WrappedType == Coll[Any] vs a Scala Tuple2). With `S`
    * a type parameter, `S#WrappedType` erases to `java.lang.Object`, so the cast is a no-op and
    * DataSerializer dispatches on the runtime `tpe` value instead. `startWriter()` returns a
    * `SigmaByteWriter` (<: CoreByteWriter), which the SBox/SHeader arms re-cast to SigmaByteWriter. */
  private def serializeData[S <: SType](c: EvaluatedValue[S]): Unit =
    DataSerializer.serialize[S](c.value.asInstanceOf[S#WrappedType], c.tpe, SigmaSerializer.startWriter())

  /** True iff the given decoded input constant can be serialized by sigma-state's
    * DataSerializer at the given ErgoTree version — i.e. it's a valid wire-encodable
    * constant there. Mirrors the gate ergots/sigma-rust enforce (e.g. SHeader requires
    * ergoTreeVersion >= 3, so a Header input is NOT wire-encodable at v5). Used to drop
    * inputs the JVM itself can't wire-encode at the target version, so the corpus is exactly
    * what every implementation can deserialize. `activated` sets both the activated and
    * ErgoTree version (matching how the corpus stamps version.activated == version.ergoTree). */
  def isWireEncodable(c: EvaluatedValue[_ <: SType], activated: Byte): Boolean =
    try {
      VersionContext.withVersions(activated, activated) { serializeData(c) }
      true
    } catch { case _: Throwable => false }

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
      preHeader = dummyPreHeader(0, activatedVersion),
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
            tree.toProposition(replaceConstants = productionReplaceConstants(tree)), DefaultEvalSettings)
          (v, acc.totalCost.value)
        }
        (treeVer, Right((valueToJson(rawValue), jitCost.toLong)))
      } catch { case t: Throwable => (treeVer, Left(errClass(t))) }
    } catch { case t: Throwable => (0.toByte, Left(errClass(t))) }

  // ── Context with custom SELF box + var 1 (santa-eval/v4, Box.getReg dynamic-index) ────

  /** Build a context whose SELF box has custom `additionalRegisters` AND whose ContextExtension
    * carries var 1 = `varBinding`. Used for santa-eval/v4 vectors: the tree reads SELF (with known
    * registers) and a dynamic index from context var 1. */
  private def contextWithSelfRegistersAndVar1(tree: ErgoTree, activatedVersion: Byte,
      additionalRegisters: Map[ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue[_ <: SType]],
      varBinding: EvaluatedValue[_ <: SType]): ErgoLikeContext = {
    val selfBox = new ErgoBox(
      value = 1000000L,
      ergoTree = tree,
      additionalTokens = sigma.Colls.emptyColl,
      additionalRegisters = additionalRegisters,
      transactionId = bytesToId(Array.fill(32)(0: Byte)),
      index = 0.toShort,
      creationHeight = 0
    )
    new ErgoLikeContext(
      lastBlockUtxoRoot = AvlTreeData.dummy,
      headers = Colls.emptyColl[Header],
      preHeader = dummyPreHeader(0, activatedVersion),
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

  /** Eval a tree against a context whose SELF box carries `additionalRegisters` AND whose
    * ContextExtension has var 1 = `var1Json`. Returns (treeVersion, Either[(valueJson, cost), err]).
    * Used for santa-eval/v4 vectors where the tree reads SELF's register by a dynamic index
    * (var 1). The `registersJson` map is {registerId (0-based Int) -> SValue JSON}. */
  def evalWithSelfRegistersAndVar1(treeBytesHex: String,
      registersJson: Map[Int, Json], var1Json: Json,
      activated: Byte): (Byte, Either[String, (Json, Long)]) =
    try {
      val bytes   = Base16.decode(treeBytesHex).get
      val tree    = sigma.santa.LenientErgoTree.deserialize(bytes)
      val treeVer = tree.version
      try {
        val var1 = decodeInputConstant(var1Json)
        // Build additionalRegisters: only non-mandatory (R4-R9, ids 4-9). Mandatory R0-R3 are
        // auto-populated by ErgoBox from its value/ergoTree/creationInfo fields.
        val additionalRegisters: Map[ErgoBox.NonMandatoryRegisterId, sigma.ast.EvaluatedValue[_ <: SType]] =
          registersJson.collect { case (id, j) if id >= 4 && id <= 9 =>
            val regId = id match {
              case 4 => ErgoBox.R4
              case 5 => ErgoBox.R5
              case 6 => ErgoBox.R6
              case 7 => ErgoBox.R7
              case 8 => ErgoBox.R8
              case 9 => ErgoBox.R9
            }
            regId -> decodeInputConstant(j).asInstanceOf[sigma.ast.EvaluatedValue[_ <: SType]]
          }
        val (rawValue, jitCost) = VersionContext.withVersions(activated, treeVer) {
          val ctx = contextWithSelfRegistersAndVar1(tree, activated, additionalRegisters, var1)
          val acc = new CostAccumulator(
            initialCost = JitCost.fromBlockCost(Math.toIntExact(ctx.initCost)),
            costLimit   = Some(JitCost.fromBlockCost(Math.toIntExact(ctx.costLimit))))
          val (v, _blockCost) = CErgoTreeEvaluator.eval(
            ctx.toSigmaContext(), acc, tree.constants,
            tree.toProposition(replaceConstants = productionReplaceConstants(tree)), DefaultEvalSettings)
          (v, acc.totalCost.value)
        }
        (treeVer, Right((valueToJson(rawValue), jitCost.toLong)))
      } catch { case t: Throwable => (treeVer, Left(errClass(t))) }
    } catch { case t: Throwable => (0.toByte, Left(errClass(t))) }

  // ── Context with per-input extensions (santa-eval/v3, getVarFromInput) ──────────

  /** Dummy context whose spending-tx inputs each carry a ContextExtension (read by
    * getVarFromInput). Mirrors contextWithVar1, but the extensions go on the inputs,
    * not the top-level (var 1). */
  private def contextWithInputExtensions(tree: ErgoTree, activatedVersion: Byte,
      inputExtensions: Seq[Map[Byte, EvaluatedValue[_ <: SType]]]): ErgoLikeContext = {
    val selfBox = new ErgoBox(
      value = 1000000L, ergoTree = tree,
      transactionId = bytesToId(Array.fill(32)(0: Byte)), index = 0.toShort, creationHeight = 0)
    val inputs = inputExtensions.map(ext =>
      Input(selfBox.id, new ProverResult(Array.emptyByteArray, ContextExtension(ext)))).toIndexedSeq
    new ErgoLikeContext(
      lastBlockUtxoRoot = AvlTreeData.dummy,
      headers = Colls.emptyColl[Header],
      preHeader = dummyPreHeader(0, activatedVersion),
      dataBoxes = IndexedSeq.empty,
      boxesToSpend = IndexedSeq(selfBox),
      spendingTransaction = ErgoLikeTransaction(inputs, IndexedSeq()),
      selfIndex = 0,
      extension = ContextExtension.empty,
      validationSettings = ValidationRules.currentSettings,
      costLimit = DefaultEvalSettings.scriptCostLimitInEvaluator,
      initCost = 0L,
      activatedScriptVersion = activatedVersion
    ).withErgoTreeVersion(tree.version)
  }

  /** Eval a tree against a context carrying per-input extensions → (value JSON, cost) or a
    * coarse error. Parallel to evalApplied; for santa-eval/v3 (getVarFromInput) vectors. */
  def evalWithInputExtensions(treeBytesHex: String,
      inputExtensions: Seq[Map[Byte, EvaluatedValue[_ <: SType]]],
      activated: Byte): (Byte, Either[String, (Json, Long)]) =
    try {
      val bytes   = Base16.decode(treeBytesHex).get
      val tree    = sigma.santa.LenientErgoTree.deserialize(bytes)
      val treeVer = tree.version
      try {
        val (rawValue, jitCost) = VersionContext.withVersions(activated, treeVer) {
          val ctx = contextWithInputExtensions(tree, activated, inputExtensions)
          val acc = new CostAccumulator(
            initialCost = JitCost.fromBlockCost(Math.toIntExact(ctx.initCost)),
            costLimit   = Some(JitCost.fromBlockCost(Math.toIntExact(ctx.costLimit))))
          val (v, _) = CErgoTreeEvaluator.eval(
            ctx.toSigmaContext(), acc, tree.constants,
            tree.toProposition(replaceConstants = productionReplaceConstants(tree)), DefaultEvalSettings)
          (v, acc.totalCost.value)
        }
        (treeVer, Right((valueToJson(rawValue), jitCost.toLong)))
      } catch { case t: Throwable => (treeVer, Left(errClass(t))) }
    } catch { case t: Throwable => (0.toByte, Left(errClass(t))) }

  // ── Context with a custom TOP-LEVEL self extension (santa-eval/v5, the ContextExtension key domain) ──

  /** Dummy context whose top-level `extension` (the SELF box's spending extension — the source
    * `toSigmaContext` builds the `contextVars` array from, `ErgoLikeContext.scala:140-146`) is the
    * given {key -> value} map. A key >= 0x80 decodes to a signed-negative `Byte`, so the array
    * (`new Array(maxKey+1)`, then `res(id)=v`) throws at construction — `NegativeArraySizeException`
    * (max key) or `ArrayIndexOutOfBoundsException` (id == -1) — BEFORE any bytecode. Parallel to
    * `contextWithVar1`, but the key is the variable (not hardcoded 1). */
  private def contextWithTopExtension(tree: ErgoTree, activatedVersion: Byte,
      extension: Map[Byte, EvaluatedValue[_ <: SType]]): ErgoLikeContext = {
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
      preHeader = dummyPreHeader(0, activatedVersion),
      dataBoxes = IndexedSeq.empty,
      boxesToSpend = IndexedSeq(selfBox),
      spendingTransaction = ErgoLikeTransaction(IndexedSeq(), IndexedSeq()),
      selfIndex = 0,
      extension = ContextExtension(extension),
      validationSettings = ValidationRules.currentSettings,
      costLimit = DefaultEvalSettings.scriptCostLimitInEvaluator,
      initCost = 0L,
      activatedScriptVersion = activatedVersion
    ).withErgoTreeVersion(tree.version)
  }

  /** Eval a tree against a context whose top-level self extension is `extensionJson`, a
    * {key (0..255, the unsigned wire byte) -> SValue JSON} map. The key is taken `.toByte` — so a
    * key >= 128 is a signed-negative `Byte` and crashes `toSigmaContext` (caught here as the JVM's
    * verdict on the input -> `Left`, which the runner renders `errored`). This is the
    * ContextExtension key-domain divergence: ergots/sigma-rust represent keys as unsigned 0..255 and
    * ACCEPT where the JVM rejects-by-crashing. Parallel to evalApplied (same (treeVer, Either) shape). */
  def evalWithTopExtension(treeBytesHex: String, extensionJson: Map[Int, Json],
                           activated: Byte): (Byte, Either[String, (Json, Long)]) =
    try {
      val bytes   = Base16.decode(treeBytesHex).get
      val tree    = sigma.santa.LenientErgoTree.deserialize(bytes)
      val treeVer = tree.version
      try {
        val extension: Map[Byte, EvaluatedValue[_ <: SType]] =
          extensionJson.map { case (k, j) => k.toByte -> decodeInputConstant(j) }
        val (rawValue, jitCost) = VersionContext.withVersions(activated, treeVer) {
          val ctx = contextWithTopExtension(tree, activated, extension)
          val acc = new CostAccumulator(
            initialCost = JitCost.fromBlockCost(Math.toIntExact(ctx.initCost)),
            costLimit   = Some(JitCost.fromBlockCost(Math.toIntExact(ctx.costLimit))))
          val (v, _blockCost) = CErgoTreeEvaluator.eval(
            ctx.toSigmaContext(), acc, tree.constants,
            tree.toProposition(replaceConstants = productionReplaceConstants(tree)), DefaultEvalSettings)
          (v, acc.totalCost.value)
        }
        (treeVer, Right((valueToJson(rawValue), jitCost.toLong)))
      } catch { case t: Throwable => (treeVer, Left(errClass(t))) }
    } catch { case t: Throwable => (0.toByte, Left(errClass(t))) }

  // ── Full real context (santa-eval/v6-fullctx — the walker JVM oracle) ───────────
  //
  // The SECOND ErgoLikeContext construction path beside dummyContext: reconstruct the
  // REAL spending context from the walker envelope (prompts/walker-jvm-oracle-santa.md)
  // and eval the tree against it, so a harvested tree reads its true INPUTS / OUTPUTS /
  // dataInputs / HEIGHT / headers / preHeader / extension.
  //
  // Boxes parse-and-HOLD their input bytes (ErgoBox.sigmaSerializer.parse caches the
  // slice in `_bytes`), so a non-canonical on-chain box's id stays Blake2b256(exact
  // bytes) — the F5 batch-4 id basis (req 2). preHeader.version is the REAL block
  // version decoded from pre_header_hex (NOT the dummy path's activated+1 convention).
  //
  // lastBlockUtxoRoot (CONTEXT.LastBlockUtxoRootHash): the envelope carries no explicit
  // field — RESOLVED (ergots 2026-06-14, option b) by deriving it from the parent header's
  // stateRoot: AvlTreeData{digest = headers[0].stateRoot, flags 0x07 (all ops), keyLength
  // 32, valueLengthOpt None} — the dummy's shape with the real parent digest. Drift-free
  // (both sides derive from headers[0].stateRoot + fixed params). An explicit
  // lastBlockUtxoRootHex (option a) still overrides if ever needed.

  private def parseBox(hex: String): ErgoBox =
    ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(hex).get))

  /** Derive lastBlockUtxoRoot from a parent header's 33-byte stateRoot (option b).
    * Byte golden: stateRoot 01×33 → serialized AvlTreeData `<01×33>072000`. */
  def avlTreeFromStateRoot(stateRoot: ADDigest): AvlTreeData =
    AvlTreeData(Colls.fromArray(stateRoot), AvlTreeFlags.AllOperationsAllowed, keyLength = 32, valueLengthOpt = None)

  private def preHeaderFromHex(hex: String): PreHeader = {
    val f = PreHeaderCodec.decode(Base16.decode(hex).get)
    CPreHeader(
      version   = f.version,
      parentId  = Colls.fromArray(f.parentId),
      timestamp = f.timestamp,
      nBits     = f.nBits,
      height    = f.height,
      minerPk   = GroupElementSerializer.parse(SigmaSerializer.startReader(f.minerPk)).toGroupElement,
      votes     = Colls.fromArray(f.votes))
  }

  private def fullContext(
      tree: ErgoTree, activated: Byte, selfIndex: Int,
      inputs: IndexedSeq[ErgoBox], dataInputs: IndexedSeq[ErgoBox],
      outputs: IndexedSeq[ErgoBox], headers: Coll[Header], preHeader: PreHeader,
      lastBlockUtxoRoot: AvlTreeData, selfExtension: ContextExtension): ErgoLikeContext = {
    // Each spending input → Input(boxId, proof); the SELF input carries the envelope's
    // ContextExtension, the rest are empty (the envelope models the SELF extension only).
    val txInputs = inputs.indices.map { i =>
      val ext = if (i == selfIndex) selfExtension else ContextExtension.empty
      Input(inputs(i).id, new ProverResult(Array.emptyByteArray, ext))
    }.toIndexedSeq
    val txDataInputs = dataInputs.map(b => DataInput(b.id))
    // ErgoBox <: ErgoBoxCandidate; the tx recomputes output ids from its (proof-free) id —
    // verified to match the parsed boxes' ids in EvalFullContextTest.
    val outCandidates: IndexedSeq[ErgoBoxCandidate] = outputs.map(b => b: ErgoBoxCandidate)
    new ErgoLikeContext(
      lastBlockUtxoRoot = lastBlockUtxoRoot,
      headers = headers,
      preHeader = preHeader,
      dataBoxes = dataInputs,
      boxesToSpend = inputs,
      spendingTransaction = new ErgoLikeTransaction(txInputs, txDataInputs, outCandidates),
      selfIndex = selfIndex,
      extension = selfExtension,
      validationSettings = ValidationRules.currentSettings,
      costLimit = DefaultEvalSettings.scriptCostLimitInEvaluator,
      initCost = 0L,
      activatedScriptVersion = activated
    ).withErgoTreeVersion(tree.version)
  }

  /** Eval a tree against the REAL reconstructed context from the walker envelope.
    * Same (treeVersion, Either[(valueJson, cost), err]) shape as the other eval* entries.
    * `lastBlockUtxoRootHex` is the optional AvlTreeData hex (see the OPEN CONTRACT ITEM). */
  def evalFullContext(
      treeBytesHex: String, selfIndex: Int,
      inputsHex: Seq[String], dataInputsHex: Seq[String], outputsHex: Seq[String],
      headersHex: Seq[String], preHeaderHex: String,
      extensionJson: Map[Int, Json], lastBlockUtxoRootHex: Option[String],
      activated: Byte): (Byte, Either[String, (Json, Long)]) =
    try {
      val bytes   = Base16.decode(treeBytesHex).get
      val tree    = sigma.santa.LenientErgoTree.deserialize(bytes)
      val treeVer = tree.version
      try {
        val inputs = inputsHex.map(parseBox).toIndexedSeq
        require(selfIndex >= 0 && selfIndex < inputs.length,
          s"selfIndex $selfIndex out of range for ${inputs.length} inputs")
        val dataInputs = dataInputsHex.map(parseBox).toIndexedSeq
        val outputs    = outputsHex.map(parseBox).toIndexedSeq
        val ergoHeaders = headersHex.map(h =>
          ErgoHeader.sigmaSerializer.parse(SigmaSerializer.startReader(Base16.decode(h).get))).toIndexedSeq
        val headers    = Colls.fromArray(ergoHeaders.map(h => (new CHeader(h): Header)).toArray)
        val preHeader  = preHeaderFromHex(preHeaderHex)
        // lastBlockUtxoRoot: explicit hex (option a) wins; else derive from the parent
        // header's stateRoot (option b — ergots 2026-06-14); else dummy (empty headers,
        // which shouldn't occur for v6 — trees are post-activation, h >> 10).
        val lastRoot   = lastBlockUtxoRootHex
          .map(h => AvlTreeData.serializer.parse(SigmaSerializer.startReader(Base16.decode(h).get)))
          .getOrElse(if (ergoHeaders.nonEmpty) avlTreeFromStateRoot(ergoHeaders.head.stateRoot)
                     else AvlTreeData.dummy)
        val selfExt    = ContextExtension(
          extensionJson.map { case (k, j) => k.toByte -> decodeInputConstant(j) })
        val (rawValue, jitCost) = VersionContext.withVersions(activated, treeVer) {
          val ctx = fullContext(tree, activated, selfIndex, inputs, dataInputs, outputs,
            headers, preHeader, lastRoot, selfExt)
          val acc = new CostAccumulator(
            initialCost = JitCost.fromBlockCost(Math.toIntExact(ctx.initCost)),
            costLimit   = Some(JitCost.fromBlockCost(Math.toIntExact(ctx.costLimit))))
          val (v, _blockCost) = CErgoTreeEvaluator.eval(
            ctx.toSigmaContext(), acc, tree.constants,
            tree.toProposition(replaceConstants = productionReplaceConstants(tree)), DefaultEvalSettings)
          (v, acc.totalCost.value)
        }
        (treeVer, Right((valueToJson(rawValue), jitCost.toLong)))
      } catch { case t: Throwable => (treeVer, Left(errClass(t))) }
    } catch { case t: Throwable => (0.toByte, Left(errClass(t))) }
}
