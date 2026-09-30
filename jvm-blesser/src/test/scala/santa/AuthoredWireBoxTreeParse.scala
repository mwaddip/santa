package santa

// Authored wire vectors — two rules of how a box parses its ErgoTree (sigmastate v6.0.6), probed at the sigma-rust
// session's request and confirmed on the JVM.
//
// 1. The read windows. `parseBodyWithIndexedDigests` sets `positionLimit = position + MaxBoxSize` (4096) at the
//    candidate's start and restores the outer limit at its end (`ErgoBoxCandidate.scala:190-192`, `:235`).
//    `deserializeErgoTree` replaces it with `position + MaxPropositionSize` (4096) at the tree's start and puts the
//    box window back in its `finally` (`ErgoTreeSerializer.scala:141-145`, `:210-212`). Every get* checks
//    `position > positionLimit` BEFORE reading (rule 1014, `CheckPositionLimit`), so a bulk read that starts in
//    time crosses the limit and only the next read fails. Inside a tree the rule-1014 ValidationException degrades
//    a size-flagged tree to UnparsedErgoTree (its declared size then gives the raw bytes) and rejects an unsized
//    one. `peekByte` is NOT checked (`CoreByteReader.scala:41`): ValueSerializer peeks before each value, so a peek
//    past the last byte of the input throws a raw index exception instead — no degrade, a reject.
// 2. The root type. `deserializeErgoTree` runs rule 1001 `CheckDeserializedScriptIsSigmaProp` on sized and unsized
//    trees alike (`:173-175`): a sized tree degrades, an unsized one rejects ("Cannot handle ValidationException,
//    ErgoTree serialized without size bit.", `:204-207`).
// 3. The function type code 0x70. Under the node's (3, 3) context an extension value or register typed 0x70 parses as
//    SFunc, which has no data encoding: rule 1009, a reject. As a size-flagged tree's constant it degrades, by rule
//    1018 below tree v3 (0x70 is no type code there) and by rule 1009 at v3.
// 4. A ValUse whose ValDef is not in scope throws NoSuchElementException from the ValDef type store. That is not a
//    ValidationException, so even a size-flagged tree rejects instead of degrading.
// 5. The degrade gate, the class rule 4 belongs to. `deserializeErgoTree` degrades a size-flagged tree only on a
//    ValidationException (`ErgoTreeSerializer.scala:197`). It rethrows an IllegalArgumentException as a
//    SerializerException (`:191`), and every other exception propagates, so the object is rejected: an out-of-store
//    placeholder, type code 0, an unknown SigmaBoolean opcode, a BigInt over 32 bytes, a ValDef id past Int.MaxValue,
//    a malformed FunDef or function type parameter, a Box constant with a bad height or registers. Each reject is
//    built so that an impl missing the bound parses it or degrades it (either way it accepts), and each has an accept
//    twin across the bound.
// 6. Parse acceptance: which node constructors check their operands at parse. Most take their operands by an erased
//    cast and check nothing, so a node on an operand of the wrong type parses: Exists or LogicalNot on an Int, a
//    PropertyCall whose method does not unify with its object (`SMethod.specializeFor` keeps the method,
//    `SMethod.scala:193-199`), an Apply of a non-function (its `tpe` is lazy, NoType), OptionIsDefined on an Int (its
//    opType builds `SFunc(input.tpe, SBoolean)` with no cast). TrueLeaf and FalseLeaf parse from their own opcodes
//    7f and 80 (`ValueSerializer.scala:79-80`). The exceptions:
//    - Append and Slice hold `val tpe = input.tpe` (`transformers.scala:62`, `:89`), a strict val whose checkcast to
//      SCollection throws ClassCastException at construction;
//    - DeserializationSigmaBuilder checks equality and comparison operands (`SigmaBuilder.scala:686-702`), with no
//      upcast from tree v3 on (`:757-758`): EQ(Int, Long) is ConstraintFailed at v3 and upcast at v0, and GT on
//      Booleans fails the numeric check;
//    - BitOp requires numeric operands (`trees.scala:913`): an IllegalArgumentException, rethrown as a
//      SerializerException;
//    - ConcreteCollectionSerializer asserts each item's type against the declared element type
//      (`ConcreteCollectionSerializer.scala:38`, a Scala `assert` the build does not elide): an AssertionError, which
//      no handler in deserializeErgoTree catches.
//    None of these is a ValidationException, so a size-flagged tree rejects too.
// 7. More construction and serializer checks at parse (ergots' node-construction requests, 2026-09-30): Upcast and
//    Downcast require a numeric input (`trees.scala:398`, `:431`) and NumericCastSerializer casts the target type
//    with asNumType (`:22`); below tree v3 the builder upcasts mixed numeric operands (`SigmaBuilder.scala:674-683`,
//    a no-op from v3, `:757-758`), so Coll[Long](Plus(Int, Long)) passes the item assert at v0 and fails it at v3, and
//    ByIndexSerializer upcasts the index to Int (`ByIndexSerializer.scala:29-33`), which a Long fails; BlockValue
//    casts each item to BlockItem (`BlockValueSerializer.scala:39`); ExtractRegisterAs and DeserializeRegister look
//    the register id up with `findRegisterByIndex(id).get`; from tree v3 a MethodCall must have arguments
//    (`MethodCallSerializer.scala:52-55`), and below v3 one without them is written back as a PropertyCall
//    (`values.scala:1350`). A node is built right after its own bytes are read, so a construction failure rejects even
//    when a later read would have degraded the tree. And the reverse: a soft failure read first (a method not found,
//    rule 1016; a type missing below v3, 1017; a type without methods, 1010) degrades the tree before a later
//    construction failure is reached.
//
// Box entries are a bare box; Transaction entries carry the candidate as an output (BoxTreeWireFixtures).
// extract() re-derives each blessing through WireCanonicalize under the node's v6 parse context (3, 3) and fails
// loud on a wrong-reason reject, a non-identity accept, or a tree that parsed where it should degrade (checking the
// degrade's rule) or the reverse.

import io.circe.Json
import sigma.VersionContext
import sigma.ast.{BlockValue, ErgoTree, SSigmaProp, SigmaPropConstant, ValDef, ValUse}
import sigma.ast.syntax.SigmaPropValue
import sigma.data.TrivialProp

import RentFixtures._

object AuthoredWireBoxTreeParse extends BoxTreeWireFixtures {
  val OpBoxWindow = "Box.tree_read_window"
  val OpTxWindow  = "Transaction.tree_read_window"
  val OpBoxRoot   = "Box.tree_root_type_check"
  val OpTxRoot    = "Transaction.tree_root_type_check"
  val OpBoxFunc   = "Box.func_type_code"
  val OpTxFunc    = "Transaction.func_type_code"
  val OpBoxValUse = "Box.tree_valuse_unbound"
  val OpTxValUse  = "Transaction.tree_valuse_unbound"
  val OpBoxGate   = "Box.tree_degrade_gate"
  val OpTxGate    = "Transaction.tree_degrade_gate"
  val OpBoxAcceptance = "Box.tree_parse_acceptance"
  val OpTxAcceptance  = "Transaction.tree_parse_acceptance"
  val Source      = "santa:authored-box-tree-parse"

  /** WinDegrade: sized v0 tree declared 5 bytes; body BoolToSigmaProp(EQ(Coll[Byte](n), …)) whose n-byte bulk read starts
    * at candidate offset 10 and runs past the tree window (4099), so the next read trips it. The degrade resumes at
    * offset 10, where the same bytes read as height 1, no tokens, R4 = Coll[Byte](n - 6) up to the end. */
  private val WinDegrade: Array[Byte] = {
    val n = MaxSize - 6                          // 4090: the bulk read ends at offset 4100
    val boxFields = b("01" + "00" + "01" + "0e") ++ vlq(n - 6) ++ zeros(n - 6)
    require(boxFields.length == n)
    Value ++ b("08" + "05" + "d1" + "93" + "0e") ++ vlq(n) ++ boxFields
  }
  /** WinUnsized: the same body in an unsized v0 tree, completed with EQ's second operand (empty Coll[Byte]). */
  private val WinUnsized: Array[Byte] = {
    val n = MaxSize - 5                          // 4091: the bulk read starts at offset 9 and ends at 4100
    Value ++ b("00" + "d1" + "93" + "0e") ++ vlq(n) ++ zeros(n) ++ b("0e00") ++ Fields
  }
  /** Sized, segregated v0 tree: constants [Coll[Byte](n), SigmaProp(true)], body placeholder 1. */
  private def segregatedTree(n: Int): Array[Byte] = {
    val content = b("02" + "0e") ++ vlq(n) ++ zeros(n) ++ b("08d3" + "7301")
    b("18") ++ vlq(content.length) ++ content
  }
  private val WinBoxRead = Value ++ segregatedTree(MaxSize - 10) ++ Fields // tree ends at 4100: last reads in (4096, 4099]
  private val WinWithin  = Value ++ segregatedTree(MaxSize - 16) ++ Fields // tree ends at 4094: box reads 4094..4096

  /** The placeholder tx with input 0's (empty) extension replaced by {1: `value`}, written raw. */
  private def txWithExtValue(value: Array[Byte]): Array[Byte] = {
    val base = txWith(placeholderBytes)
    require(base(0) == 1 && base(33) == 0 && base(34) == 0, s"unexpected tx layout: ${hex(base)}")
    base.take(34) ++ b("0101") ++ value ++ base.drop(35)
  }

  /** SFunc(Int => Int): type code 0x70, one domain type Int, range Int, no type params. */
  private val FuncType = b("70" + "01" + "04" + "04" + "00")
  /** Sized, segregated tree of version `v`: constant 0 of the function type, then 02 and body placeholder 0. */
  private def funcConstTree(v: Int): Array[Byte] = {
    val content = b("01") ++ FuncType ++ b("02" + "7300")
    Array((0x18 | v).toByte) ++ vlq(content.length) ++ content
  }
  /** Sized tree BlockValue(ValDef(1, SigmaProp(true)), ValUse(1)): the bound twin of an unbound ValUse. */
  private lazy val boundValUseTree: Array[Byte] = VersionContext.withVersions(V3, V3) {
    ErgoTree(ErgoTree.setSizeBit(ErgoTree.ZeroHeader), IndexedSeq(),
      BlockValue(IndexedSeq(ValDef(1, SigmaPropConstant(TrivialProp.TrueProp))), ValUse(1, SSigmaProp))
        .asInstanceOf[SigmaPropValue]).bytes
  }

  /** Sized v0 tree: a BigInt constant of declared size `size`, then `value`. */
  private def bigIntTree(size: Int, value: Array[Byte]): Array[Byte] = sizedTree(0x08, b("06") ++ vlq(size) ++ value)
  /** Sized v0 tree BlockValue(ValDef(id, SigmaProp(true)), ValUse(id)). */
  private def valDefIdTree(id: Long): Array[Byte] =
    sizedTree(0x08, b("d801" + "d6") ++ vlqU32(id) ++ b("08d3" + "72") ++ vlqU32(id))
  /** Sized v0 tree BlockValue(FunDef(1, <type arguments>, SigmaProp(true)), ValUse(1)); `tpeArgs` is the count byte
    * and the types. */
  private def funDefTree(tpeArgs: String): Array[Byte] = sizedTree(0x08, b("d801" + "d701" + tpeArgs + "08d3" + "7201"))
  /** Sized, segregated v3 tree: a constant of type SFunc(Int => Int) with the one type parameter `param`, then 02 and
    * body placeholder 0 (as funcConstTree). */
  private def funcParamTree(param: String): Array[Byte] = sizedTree(0x1b, b("01" + "7001040401" + param + "02" + "7300"))
  /** A box as a Box constant's data: value 1000000, tree 00 08 d3, `height`, no tokens, `regs`, zero tx id, index 0. */
  private def nestedBox(height: Long, regs: String): Array[Byte] =
    Value ++ b("0008d3") ++ vlqU32(height) ++ b("00" + regs) ++ zeros(32) ++ b("00")
  /** Sized, segregated v0 tree: constant 0 = Box(`nested`), constant 1 = SigmaProp(true), body placeholder 1. */
  private def boxConstTree(nested: Array[Byte]): Array[Byte] = sizedTree(0x18, b("02" + "63") ++ nested ++ b("08d3" + "7301"))

  private val Rule1014  = "Check that the Reader has not exceeded the position limit"
  private val NoSizeBit = "ErgoTree serialized without size bit"
  private val Rule1001  = "Deserialized script should have SigmaProp type"

  /** `wrap` puts a candidate in a bare box or as a transaction's last output; `wrapMid` (for the degrade accept)
    * puts something after it, so the unchecked peek before the tripping read lands on a real byte. */
  private def windowEntries(kind: String, wrap: Array[Byte] => Array[Byte],
                            wrapMid: Array[Byte] => Array[Byte]): Seq[Json] = {
    val k = kind.toLowerCase
    val subject = if (kind == "Box") "A bare box" else "A transaction output"
    val degrade = accept(s"$k-tree-window-degrade-accept#0", kind,
      s"$subject (value 1000000, so the tree window ends at candidate offset 4099, 3 bytes after the box window) " +
      "whose size-flagged v0 tree is declared 5 bytes; the body BoolToSigmaProp(EQ(Coll[Byte](4090), ...)) makes a " +
      "bulk read from offset 10 that starts in time and crosses both windows. The next read, at 4100, trips the tree " +
      "window (rule 1014, checked before each read): the tree degrades to its declared 5 bytes. The box then resumes " +
      "at offset 10, where the same bytes read as height 1, no tokens and R4 = Coll[Byte](4084), whose bulk read " +
      "starts in time and crosses the box window. Round-trip identity." +
      (if (kind == "Transaction") " A second output follows, so the parser's unchecked peek before that trip lands on " +
        "a real byte." else ""),
      wrapMid(WinDegrade), degrade = Some(1014))
    // Only a transaction can end exactly where the degrading tree's bulk read ends: a bare box's tx id follows it.
    val peek = if (kind != "Transaction") Nil else Seq(reject(s"$k-tree-window-peek-past-end-reject#1", kind,
      "The same size-flagged tree as entry #0, but as the LAST output, so its bulk read ends at the last byte of the " +
      "tx. Before the next value the parser peeks, and peekByte is not position-checked (CoreByteReader.scala:41): the " +
      "peek runs past the end of the input and throws a raw index exception, not the rule-1014 ValidationException a " +
      "degrade needs. So the JVM rejects this tx, while it accepts entry #0. An impl that checks the window before " +
      "peeking degrades the tree and round-trips it.",
      // By class, not message: once the path is hot, HotSpot's fast throw drops the exception's message.
      wrap(WinDegrade), mention = Seq("IndexOutOfBoundsException")))
    val i = 1 + peek.size
    Seq(degrade) ++ peek ++ Seq(
      reject(s"$k-tree-window-unsized-reject#$i", kind,
        s"$subject whose unsized v0 tree has the same kind of body, completed (EQ's second operand is an empty " +
        "Coll[Byte]): the read at candidate offset 4100 trips the tree window and, without a size bit, the tree " +
        "cannot degrade — rejected. An impl with no windows parses the whole tree and round-trips it.",
        wrap(WinUnsized), mention = Seq(Rule1014, NoSizeBit)),
      reject(s"$k-tree-window-box-read-reject#${i + 1}", kind,
        s"$subject whose size-flagged, segregated v0 tree (constants Coll[Byte](4086), SigmaProp(true); body " +
        "placeholder 1) ends at candidate offset 4100. Its last reads start in (4096, 4099], legal under the tree " +
        "window, so the tree parses. The finally then restores the box window, and the creation-height read at 4100 " +
        "is past 4096: a hard reject, outside any tree.",
        wrap(WinBoxRead), mention = Seq(Rule1014), forbid = Seq(NoSizeBit)),
      accept(s"$k-tree-window-within-accept#${i + 2}", kind,
        s"$subject with the same tree carrying Coll[Byte](4080): it ends at candidate offset 4094, and the box reads " +
        "after it start at 4094, 4095 and 4096, all in time. Round-trip identity.",
        wrap(WinWithin), degrade = None))
  }

  def extract(): Map[String, Json] = {
    val boxWindow = windowEntries("Box", boxWith, boxWith)
    val txWindow = windowEntries("Transaction", cand => txWith(cand), cand => txWith(cand, Seq(candidate(1000000L, 2))))

    def rootEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val k = kind.toLowerCase
      val subject = if (kind == "Box") "A bare box" else "A transaction output"
      Seq(
        accept(s"$k-unsized-sigmaprop-root-accept#0", kind,
          s"$subject with the unsized v0 tree 00 08 d3 (root SigmaProp(true)): the control. Round-trip identity.",
          wrap(Value ++ b("0008d3") ++ Fields), degrade = None),
        reject(s"$k-unsized-int-root-reject#1", kind,
          s"$subject with the unsized v0 tree 00 04 02 (root Int 1). Rule 1001 CheckDeserializedScriptIsSigmaProp runs " +
          "on unsized trees too (ErgoTreeSerializer.scala:173-175); with no size bit the ValidationException cannot " +
          "degrade the tree, so the JVM rejects (\"Cannot handle ValidationException, ErgoTree serialized without size " +
          "bit.\"). An impl that checks the root type only on size-flagged trees round-trips it: the over-accept.",
          wrap(Value ++ b("000402") ++ Fields), mention = Seq(Rule1001, NoSizeBit)),
        accept(s"$k-sized-int-root-degrade-accept#2", kind,
          s"$subject with the size-flagged v0 tree 08 02 04 02 (root Int 1): the same rule-1001 failure degrades it to " +
          "UnparsedErgoTree, so the object is accepted. Round-trip identity.",
          wrap(Value ++ b("08020402") ++ Fields), degrade = Some(1001)))
    }

    def funcEntries(kind: String, wrap: Array[Byte] => Array[Byte], valueAt: Array[Byte] => Array[Byte]): Seq[Json] = {
      val k = kind.toLowerCase
      val (subject, where) = if (kind == "Box") ("A bare box", "R4") else ("A transaction output", "input 0's extension value 1")
      Seq(
        reject(s"$k-func-type-value-reject#0", kind,
          s"$subject whose $where is typed 0x70 = SFunc(Int => Int) (70 01 04 04 00). Under the node's (3, 3) context the " +
          "type parses (function types exist from tree v3), but a function has no data encoding: rule 1009 " +
          "(CheckSerializableTypeCode), and outside a tree there is nothing to degrade, so the JVM rejects. An impl " +
          "that panics on the type code (sigma-rust before 09a61b05) is red as panicked.",
          valueAt(FuncType), mention = Seq("Data value of the type with the code 112 cannot be deserialized")),
        accept(s"$k-int-value-accept#1", kind,
          s"$subject whose $where is Int 1 (04 02): the control. Round-trip identity.",
          valueAt(b("0402")), degrade = None),
        accept(s"$k-func-const-v2-degrade-accept#2", kind,
          s"$subject whose size-flagged, segregated v2 tree has a constant of type 0x70. Below tree v3 0x70 is no type " +
          "code: CheckTypeCodeV6 (rule 1018) throws a ValidationException and the tree degrades to UnparsedErgoTree. " +
          "Round-trip identity.",
          wrap(Value ++ funcConstTree(2) ++ Fields), degrade = Some(1018)),
        accept(s"$k-func-const-v3-degrade-accept#3", kind,
          s"$subject with the same tree at v3: the function type parses, its data cannot (rule 1009), and the tree " +
          "degrades too. Round-trip identity.",
          wrap(Value ++ funcConstTree(3) ++ Fields), degrade = Some(1009)))
    }

    def valUseEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val k = kind.toLowerCase
      val subject = if (kind == "Box") "A bare box" else "A transaction output"
      Seq(
        reject(s"$k-valuse-unbound-sized-reject#0", kind,
          s"$subject whose size-flagged v0 tree is 08 02 72 01: the body is ValUse(1) with no ValDef(1) in scope. The " +
          "ValDef type store throws NoSuchElementException, not a ValidationException, so the size flag does not " +
          "degrade it: the JVM rejects. An impl that degrades any body failure round-trips it: the over-accept.",
          wrap(Value ++ b("08027201") ++ Fields), mention = Seq("NoSuchElementException")),
        accept(s"$k-valuse-bound-sized-accept#1", kind,
          s"$subject whose size-flagged tree binds it first: BlockValue(ValDef(1, SigmaProp(true)), ValUse(1)). Round-trip " +
          "identity.",
          wrap(Value ++ boundValUseTree ++ Fields), degrade = None),
        reject(s"$k-valuse-unbound-unsized-reject#2", kind,
          s"$subject whose unsized v0 tree is 00 72 01, the same unbound ValUse(1): rejected as well.",
          wrap(Value ++ b("007201") ++ Fields), mention = Seq("NoSuchElementException")))
    }

    // Mentions are exception CLASS names: HotSpot's fast throw drops the message of a hot implicit exception.
    def gateEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val k = kind.toLowerCase
      val subject = if (kind == "Box") "A bare box" else "A transaction output"
      def cand(tree: Array[Byte]): Array[Byte] = wrap(Value ++ tree ++ Fields)
      val bigValue = b("0001") ++ zeros(31)       // 2^248 in 33 bytes: fits 256 bits, one redundant leading zero
      Seq(
        reject(s"$k-gate-placeholder-out-of-store-reject#0", kind,
          s"$subject whose size-flagged v0 tree is 08 02 73 05: the body is ConstantPlaceholder(5) and the tree has no " +
          "constants, so ConstantStore.get(5) indexes past the empty store (ArrayIndexOutOfBoundsException). " +
          "deserializeErgoTree degrades a size-flagged tree only on a ValidationException (ErgoTreeSerializer.scala:197), " +
          "so the JVM rejects. An impl that degrades any body failure round-trips it: the over-accept.",
          cand(b("08027305")), mention = Seq("ArrayIndexOutOfBoundsException")),
        accept(s"$k-gate-placeholder-in-store-accept#1", kind,
          s"The twin: $subject whose size-flagged, segregated v0 tree 18 05 01 08 d3 73 00 has constant 0 = " +
          "SigmaProp(true) and the body ConstantPlaceholder(0). It parses. Round-trip identity.",
          cand(b("18050108d37300")), degrade = None),
        reject(s"$k-gate-type-code-zero-reject#2", kind,
          s"$subject whose size-flagged v0 tree is 08 01 00: a constant of type code 0. TypeSerializer throws " +
          "InvalidTypePrefix, a SerializerException, which does not degrade the tree: the JVM rejects.",
          cand(b("080100")), mention = Seq("InvalidTypePrefix")),
        reject(s"$k-gate-sigmaboolean-opcode-reject#3", kind,
          s"$subject whose size-flagged v0 tree is 08 02 08 01: a SigmaProp constant whose SigmaBoolean opcode is 0x01. " +
          "The SigmaBoolean parser matches the opcode with no default case: a MatchError, and the JVM rejects.",
          cand(b("08020801")), mention = Seq("MatchError")),
        accept(s"$k-gate-sigmaprop-control-accept#4", kind,
          s"The control for #2 and #3: $subject whose size-flagged v0 tree is 08 02 08 d3, SigmaProp(true). It parses. " +
          "Round-trip identity.",
          cand(b("080208d3")), degrade = None),
        reject(s"$k-gate-bigint-size-33-reject#5", kind,
          s"$subject whose size-flagged v0 tree is a BigInt constant of declared size 33 (06 21), followed by 33 value " +
          "bytes: 00 01 and 31 zeros, 2^248, which fits 256 bits. So an impl without the size bound parses it whole. " +
          "CoreDataSerializer throws a SerializerException for any size over 32: the JVM rejects.",
          cand(bigIntTree(33, bigValue)), mention = Seq("BigInt value doesn't not fit into 32 bytes: 33")),
        accept(s"$k-gate-bigint-size-32-degrade-accept#6", kind,
          s"The twin: $subject whose tree holds the same value in 32 bytes (06 20 01, then 31 zeros). It parses, but the " +
          "root is a BigInt: rule 1001 (CheckDeserializedScriptIsSigmaProp), a ValidationException, degrades the tree, so " +
          "the object is accepted. Round-trip identity.",
          cand(bigIntTree(32, bigValue.drop(1))), degrade = Some(1001)),
        reject(s"$k-gate-valdef-id-overflow-reject#7", kind,
          s"$subject whose size-flagged v0 tree is BlockValue(ValDef(2^31, SigmaProp(true)), ValUse(2^31)). " +
          "ValDefSerializer reads the id with getUIntExact, which throws ArithmeticException (Int overflow) past " +
          "Int.MaxValue: the JVM rejects. An impl that reads ids as u32 parses the whole tree (the ValUse names the same " +
          "id): the over-accept.",
          cand(valDefIdTree(1L << 31)), mention = Seq("ArithmeticException")),
        accept(s"$k-gate-valdef-id-int-max-accept#8", kind,
          s"The twin: $subject whose tree is the same with id 2^31 - 1 (ff ff ff ff 07), which fits. It parses. " +
          "Round-trip identity.",
          cand(valDefIdTree((1L << 31) - 1)), degrade = None),
        reject(s"$k-gate-fundef-negative-tpe-count-reject#9", kind,
          s"$subject whose size-flagged v0 tree is BlockValue(FunDef(1, ...), ValUse(1)) with the FunDef (d7) " +
          "type-argument count 0xff, the signed byte -1. safeNewArray(-1) throws NegativeArraySizeException: the JVM " +
          "rejects.",
          cand(funDefTree("ff")), mention = Seq("NegativeArraySizeException")),
        reject(s"$k-gate-fundef-tpe-arg-not-typevar-reject#10", kind,
          s"$subject whose tree is the same FunDef with one type argument, Int (04), which is not a type variable. The " +
          "cast to STypeVar throws ClassCastException: the JVM rejects. An impl that takes any type there parses the tree.",
          cand(funDefTree("01" + "04")), mention = Seq("ClassCastException")),
        accept(s"$k-gate-fundef-tpe-arg-typevar-accept#11", kind,
          s"The twin for #9 and #10: $subject whose FunDef has one type argument, the type variable T (67 01 54). It " +
          "parses. Round-trip identity.",
          cand(funDefTree("01" + "670154")), degrade = None),
        reject(s"$k-gate-sfunc-tpe-param-not-typevar-reject#12", kind,
          s"$subject whose size-flagged, segregated v3 tree (header 1b) has a constant typed SFunc(Int => Int) with one " +
          "type parameter, Int (70 01 04 04 01 04). TypeSerializer's require(ident.isInstanceOf[STypeVar]) throws " +
          "IllegalArgumentException, and deserializeErgoTree rethrows it as a SerializerException (with the message " +
          "'Tree version (3) is above activated script version (3)'): the JVM rejects. An impl that takes any type as " +
          "the parameter degrades the tree on the function's data (rule 1009) instead.",
          cand(funcParamTree("04")), mention = Seq("IllegalArgumentException")),
        accept(s"$k-gate-sfunc-tpe-param-typevar-degrade-accept#13", kind,
          s"The twin: $subject whose type parameter is T (67 01 54). The type parses and the function's data cannot " +
          "(rule 1009, a ValidationException), so the tree degrades and the object is accepted. Round-trip identity.",
          cand(funcParamTree("670154")), degrade = Some(1009)),
        reject(s"$k-gate-box-const-height-overflow-reject#14", kind,
          s"$subject whose size-flagged, segregated v0 tree has constant 0 = a Box (type 63), constant 1 = " +
          "SigmaProp(true) and the body ConstantPlaceholder(1). The nested box is created at height 2^31, and " +
          "ErgoBoxCandidate's parse reads the height with getUIntExact (ErgoBoxCandidate.scala:195): " +
          "ArithmeticException, and the JVM rejects. An impl that reads " +
          "it as u32 parses the tree.",
          cand(boxConstTree(nestedBox(1L << 31, "00"))), mention = Seq("ArithmeticException")),
        accept(s"$k-gate-box-const-height-int-max-accept#15", kind,
          s"The twin: $subject whose nested box is created at height 2^31 - 1. The tree parses. Round-trip identity.",
          cand(boxConstTree(nestedBox((1L << 31) - 1, "00"))), degrade = None),
        reject(s"$k-gate-box-const-register-not-constant-reject#16", kind,
          s"$subject whose tree has the same Box constant, created at height 1, with R4 = Height (a3), an expression " +
          "and not a constant. ErgoBoxCandidate's parse casts each register to EvaluatedValue " +
          "(ErgoBoxCandidate.scala:231): ClassCastException, and the JVM rejects.",
          cand(boxConstTree(nestedBox(1, "01" + "a3"))), mention = Seq("ClassCastException")),
        reject(s"$k-gate-box-const-seven-registers-reject#17", kind,
          s"$subject whose nested box has 7 registers (Int 1 each). There are 6 non-mandatory register ids, R4 to R9, " +
          "so looking up the seventh (ErgoBoxCandidate.scala:230) throws ArrayIndexOutOfBoundsException: the JVM rejects.",
          cand(boxConstTree(nestedBox(1, "07" + "0402" * 7))), mention = Seq("ArrayIndexOutOfBoundsException")),
        accept(s"$k-gate-box-const-six-registers-accept#18", kind,
          s"The twin for #16 and #17: $subject whose nested box has 6 registers, R4 to R9 = Int 1. The tree parses. " +
          "Round-trip identity.",
          cand(boxConstTree(nestedBox(1, "06" + "0402" * 6))), degrade = None))
    }

    // Trees wrap the node under test in BoolToSigmaProp (d1), so the root is a SigmaProp. v0 unsized unless noted.
    // `wrapMid` (for the ordering pair) puts something after the candidate, as windowEntries does.
    def acceptanceEntries(kind: String, wrap: Array[Byte] => Array[Byte],
                          wrapMid: Array[Byte] => Array[Byte]): Seq[Json] = {
      val k = kind.toLowerCase
      val subject = if (kind == "Box") "A bare box" else "A transaction output"
      def cand(tree: String): Array[Byte] = wrap(Value ++ b(tree) ++ Fields)
      val sizedV0 = (content: String) => hex(sizedTree(0x08, b(content)))
      val appendBody = "d193b1b3040204040400"
      val sliceBody  = "d193b1b40402040004020400"
      val gtBoolBody = "d19101010101"
      val bitOrBody  = "d193f2010101010400"
      val collItemBody = "d193b1" + "8301040502" + "0402"
      val m11Body = "d1e6" + "dc650bfe" + "01" + "0200"
      val unchecked = "The JVM builds the node with an erased cast and checks nothing, so the tree parses. Round-trip " +
        "identity. An impl that type-checks the operand at parse rejects it: the over-reject."
      Seq(
        accept(s"$k-c1-exists-on-int-accept#0", kind,
          s"$subject whose unsized v0 tree is BoolToSigmaProp(Exists(Int 1, (x: Int) => true)): Exists over an Int. " +
          unchecked, cand("00d1ae0402d90101040101"), degrade = None),
        accept(s"$k-c2-logicalnot-on-int-accept#1", kind,
          s"$subject whose tree is BoolToSigmaProp(LogicalNot(Int 1)). " + unchecked, cand("00d1ef0402"), degrade = None),
        acceptRewritten(s"$k-c3-trueleaf-opcode-accept#2", kind,
          s"$subject whose tree is BoolToSigmaProp(TrueLeaf) with TrueLeaf as its own opcode 7f " +
          "(CaseObjectSerialization, ValueSerializer.scala:79). It parses. NON-IDENTITY: TrueLeaf is the Boolean " +
          "constant true (values.scala:771) and the box serializer writes a parsed tree back from its structure " +
          "(ErgoBoxCandidate.scala:142), so the tree comes back as 00 d1 01 01. A transaction's id and its output " +
          "boxes' ids are computed over those bytes. An impl that keeps the 7f, or does not parse it, diverges.",
          cand("00d17f"), rewritten = cand("00d10101")),
        acceptRewritten(s"$k-c3-falseleaf-opcode-accept#3", kind,
          s"$subject whose tree is BoolToSigmaProp(FalseLeaf), opcode 80 (ValueSerializer.scala:80). It parses and, " +
          "like #2, comes back as the Boolean constant: 00 d1 01 00. NON-IDENTITY.",
          cand("00d180"), rewritten = cand("00d10100")),
        accept(s"$k-c4-property-call-on-wrong-type-accept#4", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(PropertyCall(SBox.value) on Int 1, Long 1)) (db 63 01). " +
          "SMethod.specializeFor finds no unification of SBox with SInt and keeps the method as it is " +
          "(SMethod.scala:193-199), typed Long, so the EQ is well-typed and the tree parses. Round-trip identity. An " +
          "impl that fails the unification rejects it: the over-reject.",
          cand("00d193db630104020502"), degrade = None),
        accept(s"$k-c5-apply-non-function-accept#5", kind,
          s"$subject whose tree is BoolToSigmaProp(Apply(Int 1, [])). Apply's tpe is a lazy val, NoType for a " +
          "non-function, and nothing reads it at parse (values.scala:1247-1251). " + unchecked,
          cand("00d1da040200"), degrade = None),
        reject(s"$k-e1-append-on-int-reject#6", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(SizeOf(Append(Int 1, Int 2)), Int 0)). Append holds `val tpe = " +
          "input.tpe` (transformers.scala:62), a strict val whose checkcast to SCollection throws ClassCastException " +
          "when the node is built: the JVM rejects.",
          cand("00" + appendBody), mention = Seq("ClassCastException")),
        reject(s"$k-e1-append-on-int-sized-reject#7", kind,
          s"$subject whose tree is the same, size-flagged: a ClassCastException is not a ValidationException, so the " +
          "tree does not degrade and the JVM rejects.",
          cand(sizedV0(appendBody)), mention = Seq("ClassCastException")),
        reject(s"$k-e2-slice-on-int-reject#8", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(SizeOf(Slice(Int 1, 0, 1)), Int 0)). Slice holds `val tpe = " +
          "input.tpe` too (transformers.scala:89): ClassCastException, a reject.",
          cand("00" + sliceBody), mention = Seq("ClassCastException")),
        reject(s"$k-e2-slice-on-int-sized-reject#9", kind,
          s"$subject whose tree is the same, size-flagged: rejected as well.",
          cand(sizedV0(sliceBody)), mention = Seq("ClassCastException")),
        accept(s"$k-e3-option-isdefined-on-int-accept#10", kind,
          s"$subject whose tree is BoolToSigmaProp(OptionIsDefined(Int 1)). Its opType is SFunc(input.tpe, SBoolean) " +
          "(transformers.scala:656), which takes any type with no cast to SOption, and its tpe is SBoolean. " + unchecked,
          cand("00d1e60402"), degrade = None),
        reject(s"$k-l1-eq-int-long-v3-reject#11", kind,
          s"$subject whose size-flagged v3 tree (0b) is BoolToSigmaProp(EQ(Int 1, Long 1)). From tree v3 the " +
          "deserializing builder does not upcast (SigmaBuilder.scala:757-758), so the same-type check throws " +
          "ConstraintFailed (:691), which is not a ValidationException: the JVM rejects. An impl without the check " +
          "parses it: the over-accept.",
          cand("0b06d19304020502"), mention = Seq("ConstraintFailed")),
        accept(s"$k-l1-eq-int-long-v0-upcast-accept#12", kind,
          s"The twin: $subject whose tree is the same EQ in an unsized v0 tree. Below v3 the builder upcasts the Int to " +
          "Long first, so the tree parses. Round-trip identity.",
          cand("00d19304020502"), degrade = None),
        accept(s"$k-l1-eq-int-int-v3-accept#13", kind,
          s"The other twin: $subject whose v3 tree is BoolToSigmaProp(EQ(Int 1, Int 1)): the same types, so it " +
          "parses. Round-trip identity.",
          cand("0b06d19304020402"), degrade = None),
        reject(s"$k-l2-gt-boolean-reject#14", kind,
          s"$subject whose tree is BoolToSigmaProp(GT(true, true)). The builder's comparison check requires numeric " +
          "operands (SigmaBuilder.scala:699): ConstraintFailed, a reject. An impl without the check parses it.",
          cand("00" + gtBoolBody), mention = Seq("ConstraintFailed")),
        reject(s"$k-l2-gt-boolean-sized-reject#15", kind,
          s"$subject whose tree is the same, size-flagged: ConstraintFailed does not degrade, so rejected as well.",
          cand(sizedV0(gtBoolBody)), mention = Seq("ConstraintFailed")),
        accept(s"$k-l2-gt-int-accept#16", kind,
          s"The twin: $subject whose tree is BoolToSigmaProp(GT(Int 1, Int 1)). It parses. Round-trip identity.",
          cand("00d19104020402"), degrade = None),
        reject(s"$k-l3-bitor-boolean-reject#17", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(BitOr(true, true), Int 0)). BitOp requires numeric operands " +
          "(trees.scala:913): an IllegalArgumentException, which deserializeErgoTree rethrows as a SerializerException " +
          "reading 'Tree version (0) is above activated script version (3)': the JVM rejects.",
          cand("00" + bitOrBody), mention = Seq("IllegalArgumentException")),
        reject(s"$k-l3-bitor-boolean-sized-reject#18", kind,
          s"$subject whose tree is the same, size-flagged: the SerializerException does not degrade, so rejected as well.",
          cand(sizedV0(bitOrBody)), mention = Seq("IllegalArgumentException")),
        accept(s"$k-l3-bitor-int-accept#19", kind,
          s"The twin: $subject whose tree is BoolToSigmaProp(EQ(BitOr(Int 1, Int 1), Int 0)). It parses. Round-trip " +
          "identity.", cand("00d193f2040204020400"), degrade = None),
        reject(s"$k-coll-item-wrong-type-reject#20", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(SizeOf(Coll[Int](Long 1)), Int 1)): a ConcreteCollection (83) " +
          "declaring Int items (04) whose item is Long 1 (05 02). ConcreteCollectionSerializer asserts each item's type " +
          "(ConcreteCollectionSerializer.scala:38), and the build does not elide the assert: an AssertionError, which no " +
          "handler in deserializeErgoTree catches: the JVM rejects. An impl that does not compare the item types parses " +
          "it: the over-accept.", cand("00" + collItemBody), mention = Seq("AssertionError")),
        reject(s"$k-coll-item-wrong-type-sized-reject#21", kind,
          s"$subject whose tree is the same, size-flagged: an AssertionError is not a ValidationException, so the tree " +
          "does not degrade and the JVM rejects.", cand(sizedV0(collItemBody)), mention = Seq("AssertionError")),
        accept(s"$k-coll-item-right-type-accept#22", kind,
          s"The twin: $subject whose tree is BoolToSigmaProp(EQ(SizeOf(Coll[Int](Int 1)), Int 1)). It parses. " +
          "Round-trip identity.", cand("00d193b1" + "8301040402" + "0402"), degrade = None),
        accept(s"$k-context-getvar-v5-method-accept#23", kind,
          s"$subject whose tree is BoolToSigmaProp(OptionIsDefined(MethodCall(CONTEXT, SContext method 11, [Byte 0]))) " +
          "(dc 65 0b fe 01 02 00). Method 11 is getVarV5Method, declared with info but no IR builder or Java method " +
          "(methods.scala:1750-1753) and listed for v5 and v6 (:1766-1774): it resolves, and its type variable is left " +
          "unbound, so the tree parses. Round-trip identity. Evaluating it fails (NoSuchMethodException for " +
          "Context.getVar(byte); see the transaction vector evaluated-values-spend).",
          cand("00" + m11Body), degrade = None),
        accept(s"$k-context-getvar-v5-method-sized-accept#24", kind,
          s"$subject whose tree is the same, size-flagged: it parses as well. Round-trip identity.",
          cand(sizedV0(m11Body)), degrade = None)) ++ constructionEntries(kind, wrap, wrapMid)
    }

    // ergots' node-construction requests (2026-09-30), entries #25 on.
    def constructionEntries(kind: String, wrap: Array[Byte] => Array[Byte],
                            wrapMid: Array[Byte] => Array[Byte]): Seq[Json] = {
      val k = kind.toLowerCase
      val subject = if (kind == "Box") "A bare box" else "A transaction output"
      def cand(tree: Array[Byte]): Array[Byte] = wrap(Value ++ tree ++ Fields)
      def sized(header: Int, content: String): Array[Byte] = sizedTree(header, b(content))
      val notValidation = "deserializeErgoTree rethrows an IllegalArgumentException as a SerializerException " +
        "(ErgoTreeSerializer.scala:191-195), and only a ValidationException degrades a size-flagged tree (:197)"
      val collPlus  = "d193b1" + "830105" + "9a04020504" + "0402"
      val byIdxLong = "d1b2" + "0d0101" + "0500" + "00"
      val m11NoArgs = "d1e6" + "dc650bfe" + "00"
      /** The ordering candidate: a size-flagged v0 tree declared 12 bytes, BoolToSigmaProp(If(EQ(Upcast(input,
        * Long), Long 0), Coll[Byte](n), ...)), whose bulk read runs from candidate offset 17 to 4100; after a degrade
        * the box resumes at 17: height 1, no tokens, R4 = Coll[Byte](n - 6). */
      def orderingCand(input: String): Array[Byte] = {
        val n = MaxSize - 13                          // 4083: the bulk read ends at offset 4100
        val boxFields = b("01" + "00" + "01" + "0e") ++ vlq(n - 6) ++ zeros(n - 6)
        val body = b("d1" + "95" + "93" + "7e" + input + "05" + "0500" + "0e") ++ vlq(n)
        require(boxFields.length == n && body.length == 12)
        Value ++ b("08") ++ vlq(body.length) ++ body ++ boxFields
      }
      Seq(
        reject(s"$k-upcast-true-long-sized-root-reject#25", kind,
          s"$subject whose size-flagged v0 tree is the root Upcast(true, Long) (08 04 7e 01 01 05). Upcast's " +
          "constructor requires a numeric input (trees.scala:398): an IllegalArgumentException as the node is built. " +
          s"$notValidation, so the JVM rejects. An impl that checks the root only after reading the body degrades " +
          "the tree (its root is not a SigmaProp: rule 1001) and accepts: the over-accept.",
          cand(sized(0x08, "7e" + "0101" + "05")), mention = Seq("Cannot create Upcast node for non-numeric type")),
        reject(s"$k-upcast-coll-int-long-sized-root-reject#26", kind,
          s"$subject whose size-flagged v0 tree is the root Upcast(Coll[Int](), Long) (08 04 7e 10 00 05): rejected " +
          "the same way.",
          cand(sized(0x08, "7e" + "1000" + "05")), mention = Seq("Cannot create Upcast node for non-numeric type")),
        reject(s"$k-downcast-true-byte-sized-root-reject#27", kind,
          s"$subject whose size-flagged v0 tree is the root Downcast(true, Byte) (08 04 7d 01 01 02). Downcast has " +
          "the same require (trees.scala:431): rejected.",
          cand(sized(0x08, "7d" + "0101" + "02")), mention = Seq("Cannot create Downcast node for non-numeric type")),
        reject(s"$k-downcast-coll-int-byte-sized-root-reject#28", kind,
          s"$subject whose size-flagged v0 tree is the root Downcast(Coll[Int](), Byte) (08 04 7d 10 00 02): rejected.",
          cand(sized(0x08, "7d" + "1000" + "02")), mention = Seq("Cannot create Downcast node for non-numeric type")),
        accept(s"$k-upcast-int-long-sized-root-degrade-accept#29", kind,
          s"The twin for #25 to #28: $subject whose size-flagged v0 tree is the root Upcast(Int 1, Long) (08 04 7e 04 " +
          "02 05). The node builds, and the root is a Long: rule 1001 degrades the tree, so the object is accepted. " +
          "Round-trip identity.",
          cand(sized(0x08, "7e" + "0402" + "05")), degrade = Some(1001)),
        reject(s"$k-upcast-boolean-target-sized-root-reject#30", kind,
          s"$subject whose size-flagged v0 tree is the root Upcast(Int 1, Boolean) (08 04 7e 04 02 01). " +
          "NumericCastSerializer casts the target type with asNumType (NumericCastSerializer.scala:22): a " +
          "ClassCastException for a non-numeric type, and the JVM rejects.",
          cand(sized(0x08, "7e" + "0402" + "01")), mention = Seq("ClassCastException", "SNumericType")),
        accept(s"$k-v0-coll-long-plus-int-long-accept#31", kind,
          s"$subject whose unsized v0 tree is BoolToSigmaProp(EQ(SizeOf(Coll[Long](Plus(Int 1, Long 2))), Int 1)) " +
          "(83 01 05 9a 04 02 05 04: one Long item). Below tree v3 the deserializing builder upcasts mixed numeric " +
          "operands to the wider type (SigmaBuilder.scala:674-683, :707-712), so Plus is a Long and the item assert " +
          "passes (ConcreteCollectionSerializer.scala:38). It parses. Round-trip identity: below v3 the serializer " +
          "writes the Upcast of the constant Int 1 as the constant.",
          cand(b("00" + collPlus)), degrade = None),
        reject(s"$k-v3-coll-long-plus-int-long-reject#32", kind,
          s"$subject whose size-flagged v3 tree (0b) is the same. From tree v3 the builder does not upcast " +
          "(SigmaBuilder.scala:757-758), so Plus is typed as its left operand, an Int, and the item assert throws " +
          "AssertionError: the JVM rejects. An impl that types Plus as the wider operand at v3, or does not compare " +
          "item types, parses it.",
          cand(sized(0x0b, collPlus)), mention = Seq("AssertionError", "Invalid type of collection value")),
        reject(s"$k-v0-byindex-long-index-reject#33", kind,
          s"$subject whose unsized v0 tree is BoolToSigmaProp(ByIndex(Coll[Boolean](true), Long 0)) (b2 0d 01 01 05 " +
          "00 00). Below tree v3 ByIndexSerializer upcasts the index to Int as soon as it is read " +
          "(ByIndexSerializer.scala:29-33), and upcastTo asserts that the target is at least as wide as the index " +
          "(syntax.scala:168-177): a Long fails, an AssertionError, and the JVM rejects. An impl without the check " +
          "parses it.",
          cand(b("00" + byIdxLong)), mention = Seq("AssertionError", "target type should be larger than source type")),
        accept(s"$k-v3-byindex-long-index-accept#34", kind,
          s"The twin: $subject whose size-flagged v3 tree is the same ByIndex. From v3 the index is taken as it is " +
          "(ByIndexSerializer.scala:29-30), so the tree parses. Round-trip identity.",
          cand(sized(0x0b, byIdxLong)), degrade = None),
        reject(s"$k-blockvalue-int-item-reject#35", kind,
          s"$subject whose unsized v0 tree is BlockValue([Int 1], SigmaProp(true)) (d8 01 04 02 08 d3). " +
          "BlockValueSerializer casts each item to BlockItem as it is read (BlockValueSerializer.scala:39), and a " +
          "constant is not one: ClassCastException, the JVM rejects.",
          cand(b("00" + "d8010402" + "08d3")), mention = Seq("ClassCastException", "sigma.ast.BlockItem")),
        reject(s"$k-blockvalue-int-item-sized-reject#36", kind,
          s"$subject whose tree is the same, size-flagged: a ClassCastException does not degrade, so rejected as well.",
          cand(sized(0x08, "d8010402" + "08d3")), mention = Seq("ClassCastException", "sigma.ast.BlockItem")),
        accept(s"$k-blockvalue-valdef-item-accept#37", kind,
          s"The twin: $subject whose tree is BlockValue([ValDef(1, Int 1)], SigmaProp(true)). It parses. Round-trip " +
          "identity.",
          cand(b("00" + "d801d6010402" + "08d3")), degrade = None),
        reject(s"$k-extract-register-as-id-10-reject#38", kind,
          s"$subject whose unsized v0 tree is BoolToSigmaProp(OptionIsDefined(SELF.R10[Int])) (c6 a7 0a 04). " +
          "ExtractRegisterAsSerializer looks the id up with ErgoBox.findRegisterByIndex(id).get right after reading it " +
          "(ExtractRegisterAsSerializer.scala:28). The registers are R0 to R9, so id 10 is None.get: " +
          "NoSuchElementException, and the JVM rejects.",
          cand(b("00" + "d1e6c6a70a04")), mention = Seq("NoSuchElementException")),
        reject(s"$k-extract-register-as-id-0x80-reject#39", kind,
          s"$subject whose tree is the same with the id byte 0x80, the signed byte -128: rejected the same way.",
          cand(b("00" + "d1e6c6a78004")), mention = Seq("NoSuchElementException")),
        accept(s"$k-extract-register-as-id-9-accept#40", kind,
          s"The twin: $subject whose tree reads SELF.R9[Int].isDefined (c6 a7 09 04). It parses. Round-trip identity.",
          cand(b("00" + "d1e6c6a70904")), degrade = None),
        reject(s"$k-deserialize-register-id-10-reject#41", kind,
          s"$subject whose unsized v0 tree is the root DeserializeRegister(R10, SigmaProp) (d5 0a 08 00). " +
          "DeserializeRegisterSerializer makes the same lookup (DeserializeRegisterSerializer.scala:28): rejected.",
          cand(b("00" + "d50a0800")), mention = Seq("NoSuchElementException")),
        accept(s"$k-deserialize-register-id-9-accept#42", kind,
          s"The twin: $subject whose tree is the root DeserializeRegister(R9, SigmaProp) (d5 09 08 00). It parses. " +
          "Round-trip identity.",
          cand(b("00" + "d5090800")), degrade = None),
        reject(s"$k-v3-methodcall-no-args-reject#43", kind,
          s"$subject whose size-flagged v3 tree is BoolToSigmaProp(OptionIsDefined(MethodCall(CONTEXT, SContext " +
          "method 11, []))) (dc 65 0b fe 00: no arguments). From tree v3 MethodCallSerializer asserts that there are " +
          "arguments (MethodCallSerializer.scala:52-55), the check its serializer makes (:27): AssertionError, and the " +
          "JVM rejects. An impl that checks the arity only when it evaluates parses it.",
          cand(sized(0x0b, m11NoArgs)), mention = Seq("AssertionError")),
        acceptRewritten(s"$k-v0-methodcall-no-args-propertycall-accept#44", kind,
          s"$subject whose unsized v0 tree is the same MethodCall with no arguments. Below v3 there is no check, and " +
          "it parses. NON-IDENTITY: a MethodCall with no arguments is written as a PropertyCall (its companion is " +
          "PropertyCall when args is empty, values.scala:1350), so the tree comes back as 00 d1 e6 db 65 0b fe, " +
          "one byte shorter.",
          cand(b("00" + m11NoArgs)), rewritten = cand(b("00" + "d1e6" + "db650bfe"))),
        accept(s"$k-v3-methodcall-one-arg-accept#45", kind,
          s"The twin for #43: $subject whose size-flagged v3 tree is the MethodCall with its argument, Byte 0 " +
          "(dc 65 0b fe 01 02 00). It parses. Round-trip identity.",
          cand(sized(0x0b, "d1e6" + "dc650bfe" + "010200")), degrade = None),
        reject(s"$k-order-upcast-true-then-window-reject#46", kind,
          s"$subject whose size-flagged v0 tree is declared 12 bytes: BoolToSigmaProp(If(EQ(Upcast(true, Long), " +
          "Long 0), Coll[Byte](4083), ...)). The Upcast is built at candidate offset 10, right after its input and " +
          "target type, and its require throws (trees.scala:398): the JVM rejects before it reaches the window. An " +
          "impl that makes its type checks only after reading the body meets the window first: the Coll[Byte]'s bulk " +
          "read runs from offset 17 to 4100, past the tree window (4099), the next read trips rule 1014, and the " +
          "size-flagged tree degrades, as #47 does.",
          wrapMid(orderingCand("0101")), mention = Seq("Cannot create Upcast node for non-numeric type"),
          forbid = Seq(Rule1014)),
        accept(s"$k-order-upcast-int-then-window-degrade-accept#47", kind,
          s"The twin: $subject with Upcast(Int 1, Long). The node builds, the Coll[Byte]'s bulk read crosses the tree " +
          "window, and the read of If's third child trips rule 1014: the tree degrades to its declared 12 bytes. The " +
          "box resumes at offset 17: height 1, no tokens, R4 = Coll[Byte](4077), whose bulk read starts in time and " +
          "crosses the box window. Round-trip identity." +
          (if (kind == "Transaction") " A second output follows, so the parser's unchecked peek before the trip " +
            "lands on a real byte." else ""),
          wrapMid(orderingCand("0402")), degrade = Some(1014))) ++ softThenHardEntries(kind, wrap)
    }

    // ergots' second request of 2026-09-30, entries #48 on: the mirror of #46: a soft failure read BEFORE a construction
    // failure. The ValidationException degrades the size-flagged tree at once, so the construction failure is never
    // reached. Each has a v3 twin, where nothing soft fails and the construction failure rejects.
    def softThenHardEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val k = kind.toLowerCase
      val subject = if (kind == "Box") "A bare box" else "A transaction output"
      def cand(header: Int, body: String): Array[Byte] = wrap(Value ++ sizedTree(header, b(body)) ++ Fields)
      val toBytesGt = "d191" + "db0406" + "0402" + "0400"
      val ubiGt     = "d191" + "090105" + "0101"
      val type9Gt   = "d191" + "db0901" + "0402" + "0101"
      val gtFails   = "GT's builder check needs numeric operands (SigmaBuilder.scala:696-704): ConstraintFailed, a reject"
      Seq(
        accept(s"$k-v0-method-lookup-1016-then-gt-degrade-accept#48", kind,
          s"$subject whose size-flagged v0 tree is sigmaProp(1.toBytes > 0) (d1 91 db 04 06 04 02 04 00: a " +
          "PropertyCall on Int, method 6). Below tree v3 no numeric method is found by id: the v5 method list keeps " +
          "the generic numeric container as each method's objType (methods.scala:237-241), whose type is SNumericType " +
          "(:263), and the lookup map groups methods by objType (:95-99). So the lookup fails rule 1016, a " +
          "ValidationException, and the tree degrades before GT is built. Round-trip identity. An impl that finds the " +
          "method, or does not treat the failed lookup as a soft failure, goes on to GT (#49) and rejects.",
          cand(0x08, toBytesGt), degrade = Some(1016)),
        reject(s"$k-v3-tobytes-gt-int-reject#49", kind,
          s"The twin: $subject whose tree is the same at v3 (0b): toBytes is found, a Coll[Byte], and $gtFails.",
          cand(0x0b, toBytesGt), mention = Seq("ConstraintFailed")),
        accept(s"$k-v0-type-read-1017-then-gt-degrade-accept#50", kind,
          s"$subject whose size-flagged v0 tree is sigmaProp(UnsignedBigInt(5) > true) (d1 91 09 01 05 01 01). Below v3 " +
          "there is no primitive type 9 (TypeSerializer.scala:257-267): rule 1017 fails at the constant's type, and the " +
          "tree degrades before GT is built. Round-trip identity.",
          cand(0x08, ubiGt), degrade = Some(1017)),
        reject(s"$k-v3-ubi-gt-boolean-reject#51", kind,
          s"The twin: $subject whose tree is the same at v3: the UnsignedBigInt parses, and $gtFails on the Boolean.",
          cand(0x0b, ubiGt), mention = Seq("ConstraintFailed")),
        accept(s"$k-v0-no-methods-1010-then-gt-degrade-accept#52", kind,
          s"$subject whose size-flagged v0 tree is sigmaProp(PropertyCall(type 9, method 1, Int 1) > true) (d1 91 db 09 " +
          "01 04 02 01 01). Below v3 type 9 has no methods (MethodsContainer's v5 list, methods.scala:146-175), so " +
          "CheckTypeWithMethods (rule 1010, SMethod.scala:345) fails after the object is read, and the tree degrades. " +
          "Round-trip identity.",
          cand(0x08, type9Gt), degrade = Some(1010)),
        reject(s"$k-v3-type-9-method-gt-boolean-reject#53", kind,
          s"The twin: $subject whose tree is the same at v3: the method is found, and $gtFails on the Boolean.",
          cand(0x0b, type9Gt), mention = Seq("ConstraintFailed")))
    }

    Map(
      OpBoxWindow -> envelope(OpBoxWindow, boxWindow),
      OpTxWindow  -> envelope(OpTxWindow, txWindow),
      OpBoxRoot   -> envelope(OpBoxRoot, rootEntries("Box", boxWith)),
      OpTxRoot    -> envelope(OpTxRoot, rootEntries("Transaction", cand => txWith(cand))),
      OpBoxFunc   -> envelope(OpBoxFunc, funcEntries("Box", boxWith,
        v => boxWith(Value ++ b("0008d3") ++ b("01" + "00" + "01") ++ v))),
      OpTxFunc    -> envelope(OpTxFunc, funcEntries("Transaction", cand => txWith(cand), txWithExtValue)),
      OpBoxValUse -> envelope(OpBoxValUse, valUseEntries("Box", boxWith)),
      OpTxValUse  -> envelope(OpTxValUse, valUseEntries("Transaction", cand => txWith(cand))),
      OpBoxGate   -> envelope(OpBoxGate, gateEntries("Box", boxWith)),
      OpTxGate    -> envelope(OpTxGate, gateEntries("Transaction", cand => txWith(cand))),
      OpBoxAcceptance -> envelope(OpBoxAcceptance, acceptanceEntries("Box", boxWith, boxWith)),
      OpTxAcceptance  -> envelope(OpTxAcceptance, acceptanceEntries("Transaction", cand => txWith(cand),
        cand => txWith(cand, Seq(candidate(1000000L, 2))))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireBoxTreeParse", extract(), outDir)
}
