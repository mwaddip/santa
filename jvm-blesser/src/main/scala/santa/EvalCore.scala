package santa

import scorex.util.bytesToId
import scorex.util.encode.Base16

import io.circe.Json

import org.ergoplatform.{ErgoBox, ErgoHeader, ErgoLikeContext, ErgoLikeTransaction}
import org.ergoplatform.validation.ValidationRules

import sigma.{Coll, Colls, Evaluation, GroupElement, Header, PreHeader, VersionContext}
import sigma.data.{CBigInt, CBox, CHeader, CSigmaProp, CUnsignedBigInt, SigmaBoolean}
import sigma.ast.{
  BigIntConstant, BooleanConstant, BoxConstant, ByteConstant, CollectionConstant, Constant,
  ErgoTree, EvaluatedValue, GroupElementConstant, HeaderConstant, IntConstant, JitCost,
  LongConstant, SBigInt, SBoolean, SBox, SByte, SCollection, SGroupElement, SHeader, SInt,
  SLong, SOption, SPreHeader, SShort, SSigmaProp, SType, SUnsignedBigInt, ShortConstant,
  SigmaPropConstant, STuple, UnsignedBigIntConstant
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
            tree.toProposition(replaceConstants = false), DefaultEvalSettings)
          (v, acc.totalCost.value)
        }
        (treeVer, Right((valueToJson(rawValue), jitCost.toLong)))
      } catch { case t: Throwable => (treeVer, Left(errClass(t))) }
    } catch { case t: Throwable => (0.toByte, Left(errClass(t))) }

  // ── SValue JSON decoder ────────────────────────────────────────────────────
  // Inverse of valueToJson: reconstructs the sigma-state runtime value wrapped
  // in an EvaluatedValue so it can be bound to context var 1 via ContextExtension.
  // Covers: Boolean, Byte, Short, Int, Long, BigInt, UnsignedBigInt, GroupElement,
  // Coll (incl. nested), Tuple (pair), Option (Some only), Box, Header.

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
    * Option (Some only — None-as-input errors), Box, Header (each from its `bytes_hex`).
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
