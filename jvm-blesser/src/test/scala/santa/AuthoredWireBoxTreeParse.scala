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
//
// Every candidate has value 1000000 (3 VLQ bytes), so its tree window ends 3 bytes after its box window (candidate
// offsets 4099 and 4096). Box entries are a bare box; Transaction entries carry the candidate as an output.
// extract() re-derives each blessing through WireCanonicalize under the node's v6 parse context (3, 3) and fails
// loud on a wrong-reason reject, a non-identity accept, or a tree that parsed where it should degrade (checking the
// degrade's rule) or the reverse.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BlockValue, ErgoTree, SSigmaProp, SigmaPropConstant, UnparsedErgoTree, ValDef, ValUse}
import sigma.ast.syntax.SigmaPropValue
import sigma.data.TrivialProp
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction}

import RentFixtures._

object AuthoredWireBoxTreeParse {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 parse context
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
  val Source      = "santa:authored-box-tree-parse"

  private def hexOf(parts: Array[Byte]*): String = Base16.encode(parts.reduce(_ ++ _))
  private def b(hex: String): Array[Byte] = Base16.decode(hex).get
  private def vlq(n: Int): Array[Byte] = vlqU32(n.toLong)
  private def zeros(n: Int): Array[Byte] = Array.fill(n)(0.toByte)

  private val Value  = vlq(1000000).clone() // c0843d, 3 bytes
  private val Fields = b("010000")          // creation height 1, no tokens, no registers
  private val MaxSize = 4096
  require(Value.length == 3)

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

  private val spent = box("santa:btp:spent", 1000000000L, 1)
  private val placeholder = candidate(1000000L, 1)                 // c0843d 0008d3 01 00 00
  private val placeholderBytes = b(hex(Value) + "0008d3" + "010000")

  private def replaceUnique(bytes: Array[Byte], from: Array[Byte], to: Array[Byte]): Array[Byte] = {
    val at = bytes.indexOfSlice(from)
    require(at >= 0 && bytes.indexOfSlice(from, at + 1) < 0, s"${hex(from)} must occur exactly once in ${hex(bytes)}")
    bytes.take(at) ++ to ++ bytes.drop(at + from.length)
  }
  /** A bare box: `cand`, then the placeholder box's tx id and index. */
  private def boxWith(cand: Array[Byte]): Array[Byte] = VersionContext.withVersions(V3, V3) {
    val plain = ErgoBox.sigmaSerializer.toBytes(box("santa:btp:box", 1000000L, 1))
    require(plain.startsWith(placeholderBytes), s"unexpected box layout: ${hex(plain)}")
    cand ++ plain.drop(placeholderBytes.length)
  }
  /** A tx whose outputs are `cand` followed by `after`. */
  private def txWith(cand: Array[Byte], after: Seq[ErgoBoxCandidate] = Nil): Array[Byte] =
    VersionContext.withVersions(V3, V3) {
      replaceUnique(txBytes(RentFixtures.tx(Seq(input(spent, ext())), placeholder +: after)), placeholderBytes, cand)
    }

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

  /** A size-flagged tree: `header`, the content's size, the content. */
  private def sizedTree(header: Int, content: Array[Byte]): Array[Byte] =
    Array(header.toByte) ++ vlq(content.length) ++ content
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

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))
  private def causes(t: Throwable): List[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList
  private def canonical(kind: String, in: String): String = WireCanonicalize.canonicalize(kind, in, V3, V3)

  /** The crafted candidate's tree as the JVM parsed it (a Box's tree, or a Transaction's output 0). */
  private def parsedTree(kind: String, bytes: Array[Byte]): ErgoTree = VersionContext.withVersions(V3, V3) {
    val r = SigmaSerializer.startReader(bytes)
    kind match {
      case "Box"         => ErgoBox.sigmaSerializer.parse(r).ergoTree
      case "Transaction" => ErgoLikeTransaction.serializer.parse(r).outputCandidates(0).ergoTree
    }
  }
  private def degradedBy(t: ErgoTree): Option[Int] = t.root match {
    case Left(UnparsedErgoTree(_, error)) => Some(error.rule.id.toInt)
    case Right(_)                         => None
  }

  private def entry(name: String, kind: String, description: String, in: String, extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version): _*)

  /** An accept entry: identity round-trip, and the tree parsed (`degrade = None`) or degraded by that rule. */
  private def accept(name: String, kind: String, description: String, bytes: Array[Byte], degrade: Option[Int]): Json = {
    val in = hex(bytes)
    val out = canonical(kind, in)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in ${in.take(80)}…, out ${out.take(80)}…")
    val got = degradedBy(parsedTree(kind, bytes))
    require(got == degrade, s"$name: tree degrade rule must be $degrade, got $got")
    entry(name, kind, description, in)
  }

  /** A reject entry: the JVM must throw, with every `mention` in the cause chain and no `forbid`. */
  private def reject(name: String, kind: String, description: String, bytes: Array[Byte],
                     mention: Seq[String], forbid: Seq[String] = Nil): Json = {
    val in = hex(bytes)
    Try(canonical(kind, in)) match {
      case Success(out) => sys.error(s"$name: the JVM must REJECT, but it round-tripped to ${out.take(80)}…")
      case Failure(t) =>
        val chain = causes(t)
        mention.foreach(m => require(chain.exists(_.contains(m)), s"$name: want '$m' in ${chain.mkString(" <- ")}"))
        forbid.foreach(f => require(!chain.exists(_.contains(f)), s"$name: must not fail with '$f': ${chain.mkString(" <- ")}"))
    }
    entry(name, kind, description, in, "error" -> Json.fromString("errored"))
  }

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

    def envelope(op: String, es: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.6"),
      "entries"    -> Json.arr(es: _*))
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
      OpTxGate    -> envelope(OpTxGate, gateEntries("Transaction", cand => txWith(cand))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireBoxTreeParse", extract(), outDir)
}
