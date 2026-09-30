package santa

// Authored wire vectors for ergots' sized-tree requests (2026-09-28): how the JVM (sigmastate 6.0.6) parses a
// size-flagged tree where something nested fails, where a count is large, where a count wraps, and a few root and
// operand forms. All confirmed on the JVM under the node's v6 parse context (3, 3).
//
// 1. Nested failures. A Box constant carries a whole box, tree and registers included, parsed on the outer tree's
//    reader. A ValidationException raised inside it reaches the OUTER tree's handler and degrades a size-flagged
//    outer tree (`ErgoTreeSerializer.scala:197`). That holds for a nested v1 header without the size bit, whose
//    CheckHeaderSizeBit (rule 1012, `:219`) runs before the nested tree's own handler (`:145`), and for rule 1019
//    (`CheckV6Type`, `ErgoBoxCandidate.scala:232`) on a nested register. But a nested UNSIZED tree turns its
//    ValidationException into a SerializerException ("Cannot handle ValidationException, ErgoTree serialized without
//    size bit."), which does not degrade anything, whether the rule is 1002 or 1001 (a root that is not a SigmaProp),
//    and neither does an SHeader register in a pre-v3 tree (no data serializer). A nested box's registers are read
//    under the ENCLOSING tree's version, since a tree's version scopes only its own constants and body (`:154`): in a
//    v0 tree, a v3 box's UnsignedBigInt or SFunc-typed register fails rule 1017 or 1018 (ergots, 2026-09-30).
// 2. Count bounds. SigmaAnd's item count, Apply's argument count and the constants count go through safeNewArray,
//    which throws above MaxArrayLength 100000 (`sigma/util/package.scala:7-12`, `SigmaTransformerSerializer.scala:21-25`,
//    `SigmaByteReader.scala:53-59`, `ErgoTreeSerializer.scala:254`); a collection count goes through getUShort
//    (`ConcreteCollectionSerializer.scala:28`). None of those is a ValidationException. At the bound the parser reads
//    on: a tree window degrades it, or the input ends first, an IllegalArgumentException from scorex-util's reader that
//    deserializeErgoTree rethrows as a SerializerException (`ErgoTreeSerializer.scala:191-193`): a reject.
// 3. Counts that wrap. The constants count is `getUInt().toInt`; a negative count means no constants
//    (`ErgoTreeSerializer.scala:248-261`), and the tree comes back with count 0. scorex-util 0.2.0's getUShort is
//    `getULong().toInt` and only then the 0..65535 check, so a count of 2^32 + k reads as k.
// 4. Header bits 5-7 are kept as they are. 5. Root forms under rule 1001.
// 6. The 85 pair form belongs to the nine relations only (`Relation2Serializer.scala:40-51`). The other ten
//    two-argument opcodes read two values (`TwoArgumentsSerializer.scala:21-25`), so an operand starting with 85 is a
//    Boolean collection. The seven arithmetic operations are built unchecked (`SigmaBuilder.scala:707-712`), so they
//    parse; BitOr, BitAnd and BitXor require numeric operands (`trees.scala:913`), an IllegalArgumentException that
//    deserializeErgoTree rethrows as a SerializerException: a reject, sized or not.
// 7. CTHRESHOLD requires 0 <= k <= n <= 255 after reading the children (`SigmaBoolean.scala:223`); CAND and COR check
//    nothing (`:80-93`), so they take 0 children, or more than 255 up to getUShort's 65535. The SigmaAnd and SigmaOr
//    nodes read a getUIntExact count into safeNewArray and are built unchecked (`SigmaTransformerSerializer.scala:20-30`,
//    `SigmaBuilder.scala:515-519`): 0 or 256 items parse.
// 8. getUShort outside trees. The same truncation applies wherever scorex's getUShort is read: a transaction's input,
//    data-input and output counts (`ErgoLikeTransaction.scala:148`, `:155`, `:172`), a proof's length
//    (`ProverResult.scala:40`), a box's index (`ErgoBox.scala:218`), and in data a collection's length and a BigInt's
//    size (`CoreDataSerializer.scala:132`, `:112`). 2^32 + k reads as k and is written back as k (a parsed box keeps
//    its bytes for its id, but the round-trip re-serializes it); 2^32 + 2^16 truncates to 65536 and fails the check,
//    which a 16-bit mask would read as 0.

import io.circe.Json

import RentFixtures._

object AuthoredWireSizedTreeRequests extends BoxTreeWireFixtures {
  val Source = "santa:authored-sized-tree-requests"
  val OpBoxNested       = "Box.tree_nested_degrade"
  val OpTxNested        = "Transaction.tree_nested_degrade"
  val OpBoxCountBounds  = "Box.tree_count_bounds"
  val OpTxCountBounds   = "Transaction.tree_count_bounds"
  val OpBoxCountWrap    = "Box.tree_count_wrap"
  val OpTxCountWrap     = "Transaction.tree_count_wrap"
  val OpBoxHeaderBits   = "Box.tree_header_bits"
  val OpTxHeaderBits    = "Transaction.tree_header_bits"
  val OpBoxRootForms    = "Box.tree_root_forms"
  val OpTxRootForms     = "Transaction.tree_root_forms"
  val OpBoxBoolPair     = "Box.tree_bool_pair_form"
  val OpTxBoolPair      = "Transaction.tree_bool_pair_form"
  val OpBoxSigmaBoolean = "Box.tree_sigmaboolean_bounds"
  val OpTxSigmaBoolean  = "Transaction.tree_sigmaboolean_bounds"
  val OpConjectures     = "SigmaBoolean.conjecture_bounds"
  val OpBoxUShort       = "Box.ushort_wrap"
  val OpTxUShort        = "Transaction.ushort_wrap"
  val OpConstUShort     = "Constant.ushort_wrap"

  private def hexVlq(n: Long): String = hex(vlqU32(n))

  /** A box as a Box constant's data: value 1000000, `tree`, height 1, no tokens, `regs`, a zero tx id, index 0. */
  private def nestedBox(tree: String, regs: String): String = hex(Value) + tree + "01" + "00" + regs + "00" * 32 + "00"
  /** Segregated tree `header`: constant 0 = Box(`nested`), constant 1 = SigmaProp(true), body ConstantPlaceholder(1).
    * Size-flagged when the header has the 08 bit. */
  private def boxConstTree(header: Int, nested: String): Array[Byte] = {
    val content = b("02" + "63" + nested + "08d3" + "7301")
    if ((header & 0x08) != 0) sizedTree(header, content) else Array(header.toByte) ++ content
  }
  private val OptionIntSome1 = "28" + "01" + "02"
  /** The 215-byte Header value of AuthoredWireUnparsedSoftForkHeaderConstant (after 1a db01 01 68). */
  private val HeaderValue = AuthoredWireUnparsedSoftForkHeaderConstant.Hex.drop(10).dropRight(4)
  /** A v3 tree, SigmaProp(true): the nested box's own tree in the enclosing-version entries. */
  private val V3TreeHex = "0b02" + "08d3"
  /** sigmaProp(Upcast(true, Long)): the Upcast throws when built (`trees.scala:398`), so a parse that reaches it rejects. */
  private val UpcastTrueBody = "d1" + "7e" + "0101" + "05"
  /** Size-flagged, segregated tree `header`: one constant, Box(`nested`), and the body `body`. */
  private def boxConstBodyTree(header: Int, nested: String, body: String): Array[Byte] =
    sizedTree(header, b("01" + "63" + nested + body))

  /** height 1, no tokens, R4 = Coll[Byte](n - 6): the n bytes a bulk read crosses the window with, and that a degrade
    * resumes the box at (as AuthoredWireBoxTreeParse's WinDegrade). */
  private def payload(n: Int): Array[Byte] = b("01" + "00" + "01" + "0e") ++ vlq(n - 6) ++ zeros(n - 6)
  /** Sized tree (`header`, v0 unsegregated by default) whose declared size covers `prefix`, a Coll[Byte] bulk read's
    * type and length; then the payload. The bulk read starts at candidate offset 3 + 2 + |prefix| + 3 and ends at 4100,
    * past the tree window (4099). */
  private def countTree(prefix: String, collType: String, header: Int = 0x08): Array[Byte] = {
    val n = MaxSize + 4 - 3 - 2 - prefix.length / 2 - collType.length / 2 - 2 // ends at 4100: value, header+size, VLQ n
    require(vlq(n).length == 2, s"n = $n must take a 2-byte VLQ")
    sizedTree(header, b(prefix + collType) ++ vlq(n)) ++ payload(n)
  }

  /** An accept entry for a kind with no tree (SigmaBoolean, Constant): identity round-trip. */
  private def acceptPlain(name: String, kind: String, description: String, bytes: Array[Byte]): Json = {
    val in = hex(bytes)
    require(canonical(kind, in) == in, s"$name: the JVM must round-trip an accept vector to itself")
    entry(name, kind, description, in)
  }
  /** A non-identity accept for a kind with no tree: the JVM writes the object back as `rewritten`. */
  private def acceptPlainRewritten(name: String, kind: String, description: String, bytes: Array[Byte],
                                   rewritten: Array[Byte]): Json = {
    val (in, want) = (hex(bytes), hex(rewritten))
    require(want != in, s"$name: a non-identity accept must change the bytes")
    val out = canonical(kind, in)
    require(out == want, s"$name: the JVM must re-serialize to $want, got $out")
    entry(name, kind, description, in, "expected_bytes_hex" -> Json.fromString(want))
  }
  /** `bytes` with the bytes `was` at offset `at` replaced by `to`. */
  private def spliceAt(bytes: Array[Byte], at: Int, was: String, to: String): Array[Byte] = {
    require(hex(bytes.slice(at, at + was.length / 2)) == was, s"expected $was at $at in ${hex(bytes)}")
    bytes.take(at) ++ b(to) ++ bytes.drop(at + was.length / 2)
  }

  private val NoSizeBit = "ErgoTree serialized without size bit"
  private val MaxArray  = "max limit is 100000"
  private val UShort    = "out of unsigned short range"
  /** 2^32 + 1 and 2^32 + 2^16 as VLQs: getUShort reads them as 1 and as 65536 (out of range). */
  private val Wrap1     = "8180808010"
  private val Wrap65536 = "8080848010"

  def extract(): Map[String, Json] = {
    def subjectOf(kind: String) = if (kind == "Box") "A bare box" else "A transaction output"

    def nestedEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val (k, subject) = (kind.toLowerCase, subjectOf(kind))
      def cand(tree: Array[Byte]): Array[Byte] = wrap(Value ++ tree ++ Fields)
      val outer = s"$subject whose size-flagged, segregated tree has constant 0 = a Box, constant 1 = SigmaProp(true) " +
        "and the body ConstantPlaceholder(1)."
      Seq(
        reject(s"$k-nested-unsized-softfork-reject#0", kind,
          s"$outer The nested box's tree is unsized, 00 d1 fd, whose unknown opcode fd fails rule 1002. Unsized, the " +
          s"nested tree turns that ValidationException into a SerializerException ('$NoSizeBit.'), which does not " +
          "degrade the outer tree either: the JVM rejects. An impl that degrades the outer tree on any nested failure " +
          "accepts it: the over-accept.",
          cand(boxConstTree(0x18, nestedBox("00d1fd", "00"))), mention = Seq(NoSizeBit, "ValidationRule(1002")),
        accept(s"$k-nested-sized-softfork-accept#1", kind,
          s"The twin: $subject whose nested tree is the same, size-flagged (08 02 d1 fd). It degrades on its own, so the " +
          "outer tree parses. Round-trip identity.",
          cand(boxConstTree(0x18, nestedBox("0802d1fd", "00"))), degrade = None),
        accept(s"$k-nested-rule-1012-degrade-accept#2", kind,
          s"$outer The nested box's tree header is 01, a v1 tree without the size bit. CheckHeaderSizeBit (rule 1012, " +
          "ErgoTreeSerializer.scala:219) runs before the nested tree's own handler (:145), so its ValidationException " +
          "reaches the outer tree, which degrades. Round-trip identity. An impl that treats it as a hard error " +
          "rejects: the over-reject.",
          cand(boxConstTree(0x18, nestedBox("0108d3", "00"))), degrade = Some(1012)),
        reject(s"$k-nested-rule-1012-unsized-outer-reject#3", kind,
          s"The twin: $subject whose outer tree is the same but unsized (10): the ValidationException cannot degrade it, " +
          "so the JVM rejects.",
          cand(boxConstTree(0x10, nestedBox("0108d3", "00"))), mention = Seq(NoSizeBit, "ValidationRule(1012")),
        accept(s"$k-nested-rule-1019-degrade-accept#4", kind,
          s"$outer The outer tree is v3 (1b), and the nested box's R4 is Option[Int] Some(1) (28 01 02): well-formed " +
          "data that CheckV6Type (rule 1019, ErgoBoxCandidate.scala:232) refuses in a register. The ValidationException " +
          "degrades the outer tree. Round-trip identity.",
          cand(boxConstTree(0x1b, nestedBox("0008d3", "01" + OptionIntSome1))), degrade = Some(1019)),
        reject(s"$k-nested-option-register-unsized-outer-reject#5", kind,
          s"The twin: $subject whose outer tree is unsized v0 (10). There the Option data fails rule 1009 (no Option data " +
          "below tree v3), and unsized, the outer tree cannot degrade: the JVM rejects.",
          cand(boxConstTree(0x10, nestedBox("0008d3", "01" + OptionIntSome1))), mention = Seq(NoSizeBit, "ValidationRule(1009")),
        reject(s"$k-nested-sheader-register-v1-reject#6", kind,
          s"$outer The outer tree is v1 (19), and the nested box's R4 is an SHeader (68, then a 215-byte header). Below " +
          "tree v3 there is no data serializer for SHeader, a SerializerException, not a ValidationException: the size " +
          "flag does not help and the JVM rejects.",
          cand(boxConstTree(0x19, nestedBox("0008d3", "01" + "68" + HeaderValue))),
          mention = Seq("Not defined DataSerializer for type SHeader")),
        accept(s"$k-nested-sheader-register-v3-degrade-accept#7", kind,
          s"The twin: $subject whose outer tree is v3 (1b): the SHeader parses, then rule 1019 refuses it in a register, " +
          "and the outer tree degrades. Round-trip identity.",
          cand(boxConstTree(0x1b, nestedBox("0008d3", "01" + "68" + HeaderValue))), degrade = Some(1019)),
        accept(s"$k-nested-seven-registers-r4-degrade-accept#8", kind,
          s"$outer The outer tree is v3, and the nested box declares 7 registers, R4 = Option[Int] Some(1), then 6 × Int 1. " +
          "The registers are read in order and R4 fails rule 1019 before the seventh register's missing id is ever looked " +
          "up (which would reject, see tree_degrade_gate #17), so the outer tree degrades. Round-trip identity. An impl " +
          "that checks the register count first rejects.",
          cand(boxConstTree(0x1b, nestedBox("0008d3", "07" + OptionIntSome1 + "0402" * 6))), degrade = Some(1019)),
        reject(s"$k-nested-unsized-int-root-reject#9", kind,
          s"$outer The nested box's tree is unsized, 00 04 02, whose root is Int 1: rule 1001 (the root must be a " +
          "SigmaProp, ErgoTreeSerializer.scala:174) fails in the nested tree. Unsized, the nested tree turns that " +
          s"ValidationException into a SerializerException ('$NoSizeBit.'), as #0 does for rule 1002, so the outer tree " +
          "does not degrade: the JVM rejects. An impl that degrades the outer tree accepts it: the over-accept.",
          cand(boxConstTree(0x18, nestedBox("000402", "00"))), mention = Seq(NoSizeBit, "ValidationRule(1001")),
        accept(s"$k-nested-sized-int-root-accept#10", kind,
          s"The twin: $subject whose nested tree is the same, size-flagged (08 02 04 02). Rule 1001 degrades it on its " +
          "own, so the outer tree parses. Round-trip identity.",
          cand(boxConstTree(0x18, nestedBox("08020402", "00"))), degrade = None)) ++ enclosingEntries(kind, wrap)
    }

    // ergots' 2026-09-30 request, entries #11 on: which version reads a nested box's registers.
    def enclosingEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val (k, subject) = (kind.toLowerCase, subjectOf(kind))
      def cand(outerHeader: Int, r4: String): Array[Byte] =
        wrap(Value ++ boxConstBodyTree(outerHeader, nestedBox(V3TreeHex, "01" + r4), UpcastTrueBody) ++ Fields)
      val outer1 = s"$subject whose size-flagged, segregated tree has one constant, a Box, and the body " +
        "sigmaProp(Upcast(true, Long)), which rejects if the parse reaches it (trees.scala:398). The Box's own tree " +
        "is v3 (0b 02 08 d3)."
      val scope = "A tree's version governs only its own constants and body (ErgoTreeSerializer.scala:154); the box's " +
        "registers are read after its tree (ErgoBoxCandidate.scala:231), under the ENCLOSING tree's version."
      val ubi = "09" + "0105"
      val func = "70" + "01040400"
      val sheader = "68" + HeaderValue
      Seq(
        accept(s"$k-nested-enclosing-v0-ubi-register-degrade-accept#11", kind,
          s"$outer1 The outer tree is v0 (18), and the nested box's R4 is UnsignedBigInt 5 (09 01 05). $scope Below v3 " +
          "there is no primitive type 9 (TypeSerializer.scala:257-267), so rule 1017 fails, a ValidationException, and " +
          "the outer tree degrades before its body: the object is accepted. Round-trip identity. Read under v3, the " +
          "value would parse and rule 1019 would refuse it in a register (#16), also a degrade, so this entry does not " +
          "tell the two versions apart (#14 does). An impl with no soft failure at the register rejects at the body.",
          cand(0x18, ubi), degrade = Some(1017)),
        reject(s"$k-nested-enclosing-v0-int-register-body-reject#12", kind,
          s"The control: $subject with the same outer tree, whose nested R4 is Int 1. Nothing fails before the body, " +
          "and the body's Upcast throws: the JVM rejects.",
          cand(0x18, "0402"), mention = Seq("Cannot create Upcast node for non-numeric type")),
        accept(s"$k-nested-enclosing-v0-func-register-degrade-accept#13", kind,
          s"$outer1 The outer tree is v0, and the nested R4 is typed SFunc(Int => Int) (70 01 04 04 00). Below v3 type " +
          "code 112 is no type (TypeSerializer.scala:211, :228-229): rule 1018, and the outer tree degrades. Round-trip " +
          "identity. (Read under v3, the type would parse and its data would fail rule 1009, a degrade as well.)",
          cand(0x18, func), degrade = Some(1018)),
        reject(s"$k-nested-enclosing-v0-sheader-register-reject#14", kind,
          s"$outer1 The outer tree is v0, and the nested R4 is an SHeader (68, then a 215-byte header). $scope Below v3 " +
          "SHeader has no data serializer: a SerializerException, which does not degrade, and the JVM rejects. Read " +
          "under the nested tree's v3, the header would parse and rule 1019 would refuse it, a degrade (#15): so an impl " +
          "that reads the register under the nested tree's version accepts it: the over-accept.",
          cand(0x18, sheader), mention = Seq("Not defined DataSerializer for type SHeader")),
        accept(s"$k-nested-enclosing-v3-sheader-register-degrade-accept#15", kind,
          s"The twin: $subject whose outer tree is v3 (1b), with the same nested box. The SHeader parses, rule 1019 " +
          "(CheckV6Type, ErgoBoxCandidate.scala:232) refuses it in a register, and the outer tree degrades. Round-trip " +
          "identity.",
          cand(0x1b, sheader), degrade = Some(1019)),
        accept(s"$k-nested-enclosing-v3-ubi-register-degrade-accept#16", kind,
          s"$subject whose outer tree is v3, with the nested R4 = UnsignedBigInt 5. The value parses, and rule 1019 " +
          "refuses an UnsignedBigInt in a register, so the outer tree degrades. Round-trip identity. With #11: an " +
          "UnsignedBigInt register is a soft failure under either version, 1017 below v3 and 1019 from v3.",
          cand(0x1b, ubi), degrade = Some(1019)))
    }

    def countBoundEntries(kind: String, wrap: Array[Byte] => Array[Byte], wrapLast: Array[Byte] => Array[Byte]): Seq[Json] = {
      val (k, subject) = (kind.toLowerCase, subjectOf(kind))
      def cand(tree: Array[Byte]): Array[Byte] = wrap(Value ++ tree)
      val layout = "The declared size covers the prefix up to a Coll[Byte] whose bulk read starts in time and ends past " +
        "the tree window (4099); from the end of the declared size the same bytes are the box's fields (height 1, no " +
        "tokens, R4 = a Coll[Byte] that crosses the box window)." +
        (if (kind == "Transaction") " A second output follows, so the parser's unchecked peek lands on a real byte." else "")
      Seq(
        reject(s"$k-sigmaand-count-above-bound-reject#0", kind,
          s"$subject whose size-flagged tree is SigmaAnd with 100001 items, the first BoolToSigmaProp(EQ(Coll[Byte](n), " +
          s"...)). safeNewArray refuses more than 100000 items (a RuntimeException): the JVM rejects. $layout An impl " +
          "without the bound reads the first item, trips the window and degrades: the over-accept.",
          cand(countTree("ea" + hexVlq(100001) + "d193", "0e")), mention = Seq(MaxArray)),
        accept(s"$k-sigmaand-count-at-bound-degrade-accept#1", kind,
          s"The twin: $subject whose SigmaAnd has 100000 items: the JVM reads on, the next read after the bulk read " +
          s"trips the tree window (rule 1014), and the tree degrades. Round-trip identity. $layout",
          cand(countTree("ea" + hexVlq(100000) + "d193", "0e")), degrade = Some(1014)),
        reject(s"$k-collection-count-above-bound-reject#2", kind,
          s"$subject whose size-flagged tree is a ConcreteCollection of 65536 items of type Coll[Byte]. getUShort " +
          "refuses a count above 0xFFFF (an IllegalArgumentException, rethrown as a SerializerException): the JVM rejects. " +
          layout, cand(countTree("83" + hexVlq(65536) + "0e", "0e")), mention = Seq("out of unsigned short range")),
        accept(s"$k-collection-count-at-bound-degrade-accept#3", kind,
          s"The twin: $subject whose collection has 65535 items: the JVM reads on and the window degrades the tree. " +
          s"Round-trip identity. $layout", cand(countTree("83" + hexVlq(65535) + "0e", "0e")), degrade = Some(1014)),
        accept(s"$k-apply-count-in-range-degrade-accept#4", kind,
          s"$subject whose size-flagged tree is Apply(Int 1, ...) with 70000 arguments, in (65536, 100000]: " +
          "safeNewArray allows it, the JVM reads on and the window degrades the tree. Round-trip identity. An impl that " +
          s"bounds the count at 65535 rejects: the over-reject. $layout",
          cand(countTree("da0402" + hexVlq(70000), "0e")), degrade = Some(1014)),
        reject(s"$k-apply-count-above-bound-reject#5", kind,
          s"The twin: $subject whose Apply has 100001 arguments: above safeNewArray's limit, the JVM rejects. $layout",
          cand(countTree("da0402" + hexVlq(100001), "0e")), mention = Seq(MaxArray)),
        reject(s"$k-constants-count-above-bound-reject#6", kind,
          s"$subject whose size-flagged, segregated tree (18) declares 100001 constants, the first a Coll[Byte]. " +
          "deserializeConstants allocates them with safeNewArray (ErgoTreeSerializer.scala:254), which refuses more than " +
          s"100000 items before any constant is read: the JVM rejects. $layout An impl that bounds the count lower and " +
          "degrades the tree accepts it: the over-accept.",
          cand(countTree(hexVlq(100001), "0e", header = 0x18)), mention = Seq(MaxArray)),
        accept(s"$k-constants-count-at-bound-degrade-accept#7", kind,
          s"The twin: $subject whose tree declares 100000 constants: the JVM reads constant 0, the next read trips the " +
          s"tree window (rule 1014), and the tree degrades. Round-trip identity. $layout",
          cand(countTree(hexVlq(100000), "0e", header = 0x18)), degrade = Some(1014)),
        accept(s"$k-constants-count-4097-window-degrade-accept#8", kind,
          s"$subject whose tree declares 4097 constants. The JVM has no bound at 4096: it reads on, and the window " +
          s"degrades the tree. Round-trip identity. $layout",
          cand(countTree(hexVlq(4097), "0e", header = 0x18)), degrade = Some(1014)),
        reject(s"$k-constants-count-4097-input-ends-reject#9", kind,
          s"$subject whose size-flagged, segregated tree declares 4097 constants, the first a Coll[Byte] of 64 bytes, " +
          "and whose declared size (4) ends after that length. The candidate's fields follow (height 1, no tokens, no " +
          "registers)" + (if (kind == "Box") ", then the box's tx id and index: 36 bytes" else ", and it is the " +
          "transaction's last output: 3 bytes") + ". The JVM reads on and the input ends first: scorex-util's reader " +
          "throws an IllegalArgumentException ('Not enough bytes in the buffer'), which deserializeErgoTree rethrows as " +
          "a SerializerException (ErgoTreeSerializer.scala:191-193), not a ValidationException: the JVM rejects, " +
          "although the tree is size-flagged. An impl that degrades a tree whose count is above 4096 resumes after the " +
          "declared size and accepts: the over-accept.",
          wrapLast(Value ++ sizedTree(0x18, vlq(4097) ++ b("0e") ++ vlq(64)) ++ Fields),
          mention = Seq("Not enough bytes in the buffer")))
    }

    def wrapEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val (k, subject) = (kind.toLowerCase, subjectOf(kind))
      def cand(tree: String): Array[Byte] = wrap(Value ++ b(tree) ++ Fields)
      val constants = "deserializeConstants reads the count as getUInt().toInt and takes a negative count as no " +
        "constants (ErgoTreeSerializer.scala:248-261), so the tree parses, and the JVM writes it back with count 0: " +
        "NON-IDENTITY."
      val ushort = "scorex-util 0.2.0's getUShort is getULong().toInt followed by the 0..65535 check, so the 32-bit " +
        "truncation comes first: a count of 2^32 + k reads as k. The tree parses and comes back with the count written " +
        "as k: NON-IDENTITY. An impl that range-checks the full value rejects it: the over-reject."
      Seq(
        acceptRewritten(s"$k-constants-count-wraps-negative-accept#0", kind,
          s"$subject whose size-flagged, segregated tree declares 2^32 - 1 constants (ff ff ff ff 0f) and has the body " +
          s"SigmaProp(true). $constants", cand("1807ffffffff0f08d3"), cand("18030008d3")),
        acceptRewritten(s"$k-constants-count-2-31-accept#1", kind,
          s"$subject whose tree declares 2^31 constants, Int.MinValue once truncated. $constants",
          cand("1807808080800808d3"), cand("18030008d3")),
        acceptRewritten(s"$k-constants-count-wraps-negative-unsized-accept#2", kind,
          s"$subject whose unsized segregated tree (10) declares 2^32 - 1 constants. $constants",
          cand("10ffffffff0f08d3"), cand("100008d3")),
        acceptRewritten(s"$k-bool-collection-count-2-32-accept#3", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(SizeOf(<85, count 2^32>), 0)): a Boolean collection constant whose " +
          s"count VLQ is 2^32. $ushort Here k = 0: an empty collection, written back as 85 00.",
          cand("00d193b1" + "85" + "8080808010" + "0400"), cand("00d193b1" + "8500" + "0400")),
        acceptRewritten(s"$k-bool-collection-count-2-32-plus-1-accept#4", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(SizeOf(<85, count 2^32 + 1, bits 01>), 1)). $ushort Here k = 1: " +
          "one bit is read, written back as 85 01 01.",
          cand("00d193b1" + "85" + "8180808010" + "01" + "0402"), cand("00d193b1" + "850101" + "0402")),
        acceptRewritten(s"$k-collection-count-2-32-accept#5", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(SizeOf(<83, count 2^32, Int>), 0)): a ConcreteCollection of Int " +
          s"(ConcreteCollectionSerializer.scala:28). $ushort Written back as 83 00 04.",
          cand("00d193b1" + "83" + "8080808010" + "04" + "0400"), cand("00d193b1" + "8300" + "04" + "0400")))
    }

    def headerBitEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val (k, subject) = (kind.toLowerCase, subjectOf(kind))
      Seq(("28", "bit 5"), ("48", "bit 6"), ("88", "bit 7"), ("e8", "bits 5, 6 and 7")).zipWithIndex.map {
        case ((h, bits), i) =>
          accept(s"$k-header-$h-accept#$i", kind,
            s"$subject whose size-flagged v0 tree has header $h (the size bit and $bits) and body SigmaProp(true). The " +
            "JVM reads the version and the size and segregation bits and ignores the others; the header byte is kept as " +
            "stored. Round-trip identity. An impl that refuses the bits, or rewrites the header, diverges.",
            wrap(Value ++ b(h + "0208d3") ++ Fields), degrade = None)
      } :+ accept(s"$k-header-e0-unsized-accept#4", kind,
        s"$subject whose unsized v0 tree has header e0 (bits 5, 6 and 7, no size bit). It parses and keeps its header. " +
        "Round-trip identity.", wrap(Value ++ b("e008d3") ++ Fields), degrade = None)
    }

    def rootFormEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val (k, subject) = (kind.toLowerCase, subjectOf(kind))
      def cand(tree: String): Array[Byte] = wrap(Value ++ b(tree) ++ Fields)
      Seq(
        accept(s"$k-apply-coll-sigmaprop-root-accept#0", kind,
          s"$subject whose unsized tree is Apply(Coll(sigmaProp(true)), [0]) (da 14 01 d3 01 04 00). Apply of a " +
          "collection types as its element, SigmaProp, so rule 1001 passes and the tree parses. Round-trip identity.",
          cand("00da1401d3010400"), degrade = None),
        accept(s"$k-apply-int-root-degrade-accept#1", kind,
          s"$subject whose size-flagged tree is Apply(Int 0, [0]) (08 06 da 04 00 01 04 00): NoType, not a SigmaProp, " +
          "so rule 1001 degrades the tree. Round-trip identity.",
          cand("0806da0400010400"), degrade = Some(1001)),
        reject(s"$k-context-datainputs-root-reject#2", kind,
          s"$subject whose unsized tree is 00 db 65 01 fe: PropertyCall(SContext.dataInputs) on CONTEXT, a Coll[Box] " +
          "root. Rule 1001 fails, and unsized the tree cannot degrade: the JVM rejects. An impl that types an unknown " +
          "method's result as 'any' passes the root check: the over-accept.",
          cand("00db6501fe"), mention = Seq(NoSizeBit, "ValidationRule(1001")),
        accept(s"$k-context-datainputs-root-sized-degrade-accept#3", kind,
          s"The twin: $subject whose tree is the same, size-flagged: rule 1001 degrades it. Round-trip identity.",
          cand("0804db6501fe"), degrade = Some(1001)))
    }

    def boolPairEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val (k, subject) = (kind.toLowerCase, subjectOf(kind))
      def cand(tree: String): Array[Byte] = wrap(Value ++ b(tree) ++ Fields)
      val c = "C = Coll[Boolean](true) (85 01 01)"
      val c3 = "850101" * 3
      val arith = Seq("9c" -> "Multiply", "9d" -> "Division", "9e" -> "Modulo", "a1" -> "Min", "a2" -> "Max")
      val bit = Seq("f2" -> "BitOr", "f3" -> "BitAnd", "f5" -> "BitXor")
      Seq(
        accept(s"$k-plus-on-bool-collections-accept#0", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(Plus(C, C), C)), $c. Only the nine relations read an 85 after " +
          "their opcode as a packed Boolean pair; for Plus it is the start of a Boolean collection constant. The " +
          "builder does not check an arithmetic operation's operand types at parse, so the tree parses. Round-trip " +
          "identity. An impl that reads the pair form here consumes different bytes.",
          cand("00d1939a850101850101850101"), degrade = None),
        accept(s"$k-minus-on-bool-collections-accept#1", kind,
          s"$subject whose tree is the same with Minus (99). Round-trip identity.",
          cand("00d19399850101850101850101"), degrade = None),
        accept(s"$k-eq-bool-pair-accept#2", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ(true, true)) in the relation's packed pair form, 93 85 03. " +
          "Round-trip identity.", cand("00d1938503"), degrade = None)) ++
      arith.zipWithIndex.map { case ((op, name), i) =>
        accept(s"$k-${name.toLowerCase}-on-bool-collections-accept#${3 + i}", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ($name(C, C), C)), $c. $name ($op) goes through " +
          "TwoArgumentsSerializer, which reads two values (TwoArgumentsSerializer.scala:21-25), so its 85 starts a " +
          "Boolean collection, and the builder builds the ArithOp without checking the operands (SigmaBuilder.scala:" +
          "707-712): the tree parses. Round-trip identity. An impl that reads the pair form after the opcode consumes " +
          "different bytes.", cand("00d193" + op + c3), degrade = None)
      } ++
      bit.zipWithIndex.map { case ((op, name), i) =>
        reject(s"$k-${name.toLowerCase}-on-bool-collections-reject#${8 + i}", kind,
          s"$subject whose tree is BoolToSigmaProp(EQ($name(C, C), C)), $c. $name ($op) reads two values as well, but " +
          "BitOp requires numeric operands (trees.scala:913): an IllegalArgumentException, which deserializeErgoTree " +
          "rethrows as a SerializerException (ErgoTreeSerializer.scala:191-193): the JVM rejects.",
          cand("00d193" + op + c3), mention = Seq("IllegalArgumentException", "invalid types"))
      } ++
      bit.zipWithIndex.map { case ((op, name), i) =>
        reject(s"$k-${name.toLowerCase}-on-bool-collections-sized-reject#${11 + i}", kind,
          s"The twin: $subject whose tree is the same $name, size-flagged (08 0c). The SerializerException is not a " +
          "ValidationException, so the tree does not degrade: the JVM rejects. An impl that degrades it accepts: the " +
          "over-accept.", cand("080c" + "d193" + op + c3), mention = Seq("IllegalArgumentException", "invalid types"))
      }
    }

    def sigmaBooleanTreeEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val (k, subject) = (kind.toLowerCase, subjectOf(kind))
      def cand(tree: Array[Byte]): Array[Byte] = wrap(Value ++ tree ++ Fields)
      Seq(
        reject(s"$k-cthreshold-256-children-sized-reject#0", kind,
          s"$subject whose size-flagged tree is a SigmaProp constant CTHRESHOLD(1, 256 × TrueProp). CTHRESHOLD's " +
          "require (n <= 255, SigmaBoolean.scala:223) runs after the children are read: an IllegalArgumentException, " +
          "rethrown as a SerializerException, which does not degrade: the JVM rejects.",
          cand(sizedTree(0x08, b("08" + "98" + "01") ++ vlq(256) ++ b("d3" * 256))), mention = Seq("IllegalArgumentException")),
        accept(s"$k-cthreshold-255-children-accept#1", kind,
          s"The twin: $subject whose CTHRESHOLD has 255 children. It parses. Round-trip identity.",
          cand(sizedTree(0x08, b("08" + "98" + "01") ++ vlq(255) ++ b("d3" * 255))), degrade = None),
        accept(s"$k-cand-no-children-accept#2", kind,
          s"$subject whose unsized tree is the SigmaProp constant CAND() (08 96 00): CAND carries no check, so it parses. " +
          "Round-trip identity.", cand(b("00089600")), degrade = None),
        accept(s"$k-cand-256-children-accept#3", kind,
          s"$subject whose unsized tree is the SigmaProp constant CAND(256 × TrueProp) (08 96 80 02 d3…). CAND's child " +
          "count is a getUShort and the constructor checks nothing (SigmaBoolean.scala:80-86, :149), so up to 65535 " +
          "children parse. Round-trip identity. An impl that bounds CAND at 255 children, as CTHRESHOLD is bounded, " +
          "rejects it: the over-reject.", cand(b("00" + "08" + "96" + "8002" + "d3" * 256)), degrade = None),
        accept(s"$k-cor-256-children-accept#4", kind,
          s"$subject whose tree is the same with COR (97): it parses (:87-93, :185). Round-trip identity.",
          cand(b("00" + "08" + "97" + "8002" + "d3" * 256)), degrade = None),
        accept(s"$k-sigmaand-no-items-accept#5", kind,
          s"$subject whose tree is the SigmaAnd node with no items (ea 00). SigmaTransformerSerializer reads a " +
          "getUIntExact count into safeNewArray (SigmaTransformerSerializer.scala:20-30), and mkSigmaAnd builds " +
          "SigmaAnd(items) unchecked (SigmaBuilder.scala:515-516): it parses. Round-trip identity. (Spending it fails: " +
          "SigmaAnd evaluates through allZK and CAND.normalized, which requires a non-empty list; see the transaction " +
          "vector sized-tree-spend.)", cand(b("00ea00")), degrade = None),
        accept(s"$k-sigmaor-no-items-accept#6", kind,
          s"$subject whose tree is SigmaOr() (eb 00): it parses, as SigmaAnd() does. Round-trip identity.",
          cand(b("00eb00")), degrade = None),
        accept(s"$k-sigmaand-256-items-accept#7", kind,
          s"$subject whose tree is the SigmaAnd node of 256 × sigmaProp(true) (ea 80 02 08 d3…): the count is bounded " +
          "only by safeNewArray's 100000, so it parses. Round-trip identity. An impl that bounds the items at 255 " +
          "rejects it: the over-reject.", cand(b("00ea8002" + "08d3" * 256)), degrade = None),
        accept(s"$k-sigmaor-256-items-accept#8", kind,
          s"$subject whose tree is SigmaOr of 256 × sigmaProp(true): it parses. Round-trip identity.",
          cand(b("00eb8002" + "08d3" * 256)), degrade = None))
    }

    val conjectures = {
      val kind = "SigmaBoolean"
      Seq(
        reject("cthreshold-256-children-reject#0", kind,
          "CTHRESHOLD(1, 256 × TrueProp) (98 01 80 02 d3…). The children are read first, then CTHRESHOLD's require " +
          "(0 <= k <= n <= 255, SigmaBoolean.scala:223) fails: an IllegalArgumentException, and the JVM rejects.",
          b("98" + "01") ++ vlq(256) ++ b("d3" * 256), mention = Seq("IllegalArgumentException")),
        acceptPlain("cthreshold-255-children-accept#1", kind,
          "The twin: CTHRESHOLD(1, 255 × TrueProp). Round-trip identity.", b("98" + "01") ++ vlq(255) ++ b("d3" * 255)),
        reject("cthreshold-k-above-n-reject#2", kind,
          "CTHRESHOLD(2, [TrueProp]) (98 02 01 d3): k above the number of children fails the same require: the JVM " +
          "rejects.", b("980201d3"), mention = Seq("IllegalArgumentException")),
        acceptPlain("cand-no-children-accept#3", kind,
          "CAND() (96 00): the parser builds CAND(children) directly, and that constructor carries no check (only " +
          "CAND.normalized requires a non-empty list, SigmaBoolean.scala:165), so the JVM parses it. Round-trip " +
          "identity.", b("9600")),
        acceptPlain("cor-no-children-accept#4", kind, "COR() (97 00): parses, as CAND(). Round-trip identity.", b("9700")),
        acceptPlain("cthreshold-k-zero-accept#5", kind,
          "CTHRESHOLD(0, [TrueProp]) (98 00 01 d3): k = 0 passes the require. Round-trip identity.", b("980001d3")),
        acceptPlain("cthreshold-k-zero-no-children-accept#6", kind,
          "CTHRESHOLD(0, []) (98 00 00). Round-trip identity.", b("980000")),
        acceptPlain("cand-256-children-accept#7", kind,
          "CAND(256 × TrueProp) (96 80 02 d3…). CAND's child count is a getUShort and the constructor checks nothing " +
          "(SigmaBoolean.scala:80-86, :149), so up to 65535 children parse. Round-trip identity. An impl that bounds " +
          "CAND at 255 children, as CTHRESHOLD is bounded, rejects it: the over-reject.", b("96" + "8002" + "d3" * 256)),
        acceptPlain("cor-256-children-accept#8", kind,
          "COR(256 × TrueProp) (97 80 02 d3…): it parses, as CAND does (:87-93, :185). Round-trip identity.",
          b("97" + "8002" + "d3" * 256)),
        reject("cthreshold-k-256-reject#9", kind,
          "CTHRESHOLD(256, [TrueProp]) (98 80 02 01 d3). k is a getUShort, 256, and the require (k <= n, " +
          "SigmaBoolean.scala:223) fails: the JVM rejects. An impl that reads k as a byte gets 0 and accepts " +
          "CTHRESHOLD(0, [TrueProp]): the over-accept.", b("98" + "8002" + "01d3"), mention = Seq("IllegalArgumentException")),
        acceptPlainRewritten("cthreshold-k-wraps-accept#10", kind,
          s"CTHRESHOLD whose k is written as 2^32 + 1 ($Wrap1), over [TrueProp]. scorex-util's getUShort is " +
          "getULong().toInt and only then the 0..65535 check, so k reads as 1: CTHRESHOLD(1, [TrueProp]), written back " +
          "as 98 01 01 d3. NON-IDENTITY. An impl that range-checks the full value rejects it: the over-reject.",
          b("98" + Wrap1 + "01d3"), b("980101d3")),
        acceptPlainRewritten("cand-count-wraps-accept#11", kind,
          s"CAND whose child count is written as 2^32 + 1 ($Wrap1): it reads as 1, CAND([TrueProp]), written back as " +
          "96 01 d3. NON-IDENTITY.", b("96" + Wrap1 + "d3"), b("9601d3")),
        reject("cand-count-wraps-to-65536-reject#12", kind,
          s"CAND whose child count is written as 2^32 + 2^16 ($Wrap65536): truncated to 32 bits it is 65536, which " +
          "getUShort's check refuses: the JVM rejects before reading a child. An impl that keeps the low 16 bits reads " +
          "0 and accepts CAND(): the over-accept.", b("96" + Wrap65536), mention = Seq(UShort)))
    }

    def boxUShortEntries: Seq[Json] = {
      val kind = "Box"
      val plain = boxWith(placeholderBytes) // value 1000000, SigmaProp(true), height 1, the placeholder's tx id, index 0
      val index = (to: String) => spliceAt(plain, plain.length - 1, "00", to)
      val r4 = (len: String) => boxWith(Value ++ b("0008d3" + "01" + "00" + "01" + "0e" + len + "ab"))
      val subject = "A bare box (value 1000000, SigmaProp(true), height 1, no tokens)"
      Seq(
        acceptRewritten("box-index-2-32-accept#0", kind,
          s"$subject whose index is written as 2^32 (80 80 80 80 10). The index is a getUShort (ErgoBox.scala:218), " +
          "which scorex-util reads as getULong().toInt and only then checks 0..65535: 2^32 reads as 0. The box parses " +
          "and keeps the bytes as received for its id (:222); the round-trip re-serializes it with the index 00. " +
          "NON-IDENTITY. An impl that range-checks the full value rejects it: the over-reject.",
          index("8080808010"), plain),
        acceptRewritten("box-index-2-32-plus-1-accept#1", kind,
          s"$subject whose index is written as 2^32 + 1 ($Wrap1): it reads as 1 and is written back as 01. NON-IDENTITY.",
          index(Wrap1), index("01")),
        reject("box-index-wraps-to-65536-reject#2", kind,
          s"$subject whose index is written as 2^32 + 2^16 ($Wrap65536): truncated to 32 bits it is 65536, outside the " +
          "unsigned short range: the JVM rejects. An impl that keeps the low 16 bits reads 0 and accepts: the " +
          "over-accept.", index(Wrap65536), mention = Seq(UShort)),
        acceptRewritten("box-r4-coll-length-wraps-accept#3", kind,
          s"$subject whose R4 is a Coll[Byte] with its length written as 2^32 + 1 ($Wrap1), then the byte ab. A " +
          "collection's length in data is a getUShort too (CoreDataSerializer.scala:132): it reads as 1, and the " +
          "register is written back as 0e 01 ab. NON-IDENTITY.", r4(Wrap1), r4("01")))
    }

    def txUShortEntries: Seq[Json] = {
      val kind = "Transaction"
      // 01 | the input's box id (32) | proof length 00 | extension 00 | data inputs 00 | tokens 00 | outputs 01 | output
      val plain = txWith(placeholderBytes)
      require(hex(plain.slice(33, 38)) == "0000000001", s"unexpected transaction layout ${hex(plain)}")
      val subject = "A transaction with one input (no proof, no extension), no data inputs and one output"
      Seq(
        acceptRewritten("transaction-inputs-count-wraps-accept#0", kind,
          s"$subject, whose inputs count is written as 2^32 + 1 ($Wrap1). The count is a getUShort " +
          "(ErgoLikeTransaction.scala:148), which scorex-util reads as getULong().toInt and only then checks 0..65535: " +
          "it reads as 1. The transaction parses and is written back with the count 01, and its id is computed over " +
          "those re-encoded bytes (bytesToSign). NON-IDENTITY. An impl that range-checks the full value rejects it: the " +
          "over-reject.", spliceAt(plain, 0, "01", Wrap1), plain),
        reject("transaction-inputs-count-wraps-to-65537-reject#1", kind,
          s"$subject, whose inputs count is written as 2^32 + 2^16 + 1 (81 80 84 80 10): truncated to 32 bits it is " +
          "65537, outside the unsigned short range: the JVM rejects. An impl that keeps the low 16 bits reads 1 and " +
          "accepts: the over-accept.", spliceAt(plain, 0, "01", "8180848010"), mention = Seq(UShort)),
        acceptRewritten("transaction-data-inputs-count-wraps-accept#2", kind,
          s"$subject, whose data-inputs count is written as 2^32 (80 80 80 80 10, :155): it reads as 0 and is written " +
          "back as 00. NON-IDENTITY.", spliceAt(plain, 35, "00", "8080808010"), plain),
        acceptRewritten("transaction-outputs-count-wraps-accept#3", kind,
          s"$subject, whose outputs count is written as 2^32 + 1 (:172): it reads as 1 and is written back as 01. " +
          "NON-IDENTITY.", spliceAt(plain, 37, "01", Wrap1), plain),
        acceptRewritten("transaction-proof-length-wraps-accept#4", kind,
          s"$subject, whose input carries the 1-byte proof ab with its length written as 2^32 + 1. A proof's length is " +
          "a getUShort (ProverResult.scala:40): it reads as 1, and the input is written back as 01 ab. NON-IDENTITY. " +
          "(The wire kind parses the proof; it does not verify it.)",
          spliceAt(plain, 33, "00", Wrap1 + "ab"), spliceAt(plain, 33, "00", "01ab")))
    }

    val constUShort = {
      val kind = "Constant"
      Seq(
        acceptPlainRewritten("coll-byte-length-wraps-accept#0", kind,
          s"A Coll[Byte] constant (0e) whose length is written as 2^32 + 1 ($Wrap1), then the byte ab. A collection's " +
          "length in data is a getUShort (CoreDataSerializer.scala:132), which scorex-util reads as getULong().toInt and " +
          "only then checks 0..65535: it reads as 1. Written back as 0e 01 ab. NON-IDENTITY. An impl that range-checks " +
          "the full value rejects it: the over-reject.", b("0e" + Wrap1 + "ab"), b("0e01ab")),
        acceptPlainRewritten("coll-int-length-wraps-accept#1", kind,
          "A Coll[Int] constant (10) whose length is written as 2^32 + 1, then Int 1 (02): one item, written back as " +
          "10 01 02. NON-IDENTITY.", b("10" + Wrap1 + "02"), b("100102")),
        acceptPlainRewritten("bigint-size-wraps-accept#2", kind,
          "A BigInt constant (06) whose size is written as 2^32 + 1, then the byte 01. A BigInt's size is a getUShort as " +
          "well (CoreDataSerializer.scala:112): it reads as 1, BigInt 1, written back as 06 01 01. NON-IDENTITY.",
          b("06" + Wrap1 + "01"), b("060101")),
        reject("coll-byte-length-wraps-to-65536-reject#3", kind,
          s"A Coll[Byte] constant whose length is written as 2^32 + 2^16 ($Wrap65536): truncated to 32 bits it is " +
          "65536, outside the unsigned short range: the JVM rejects. An impl that keeps the low 16 bits reads 0 and " +
          "accepts an empty collection: the over-accept.", b("0e" + Wrap65536), mention = Seq(UShort)))
    }

    val box: Array[Byte] => Array[Byte] = boxWith
    val tx: Array[Byte] => Array[Byte] = cand => txWith(cand)
    val txMid: Array[Byte] => Array[Byte] = cand => txWith(cand, Seq(candidate(1000000L, 2)))
    Map(
      OpBoxNested       -> envelope(OpBoxNested, nestedEntries("Box", box)),
      OpTxNested        -> envelope(OpTxNested, nestedEntries("Transaction", tx)),
      OpBoxCountBounds  -> envelope(OpBoxCountBounds, countBoundEntries("Box", box, box)),
      OpTxCountBounds   -> envelope(OpTxCountBounds, countBoundEntries("Transaction", txMid, tx)),
      OpBoxCountWrap    -> envelope(OpBoxCountWrap, wrapEntries("Box", box)),
      OpTxCountWrap     -> envelope(OpTxCountWrap, wrapEntries("Transaction", tx)),
      OpBoxHeaderBits   -> envelope(OpBoxHeaderBits, headerBitEntries("Box", box)),
      OpTxHeaderBits    -> envelope(OpTxHeaderBits, headerBitEntries("Transaction", tx)),
      OpBoxRootForms    -> envelope(OpBoxRootForms, rootFormEntries("Box", box)),
      OpTxRootForms     -> envelope(OpTxRootForms, rootFormEntries("Transaction", tx)),
      OpBoxBoolPair     -> envelope(OpBoxBoolPair, boolPairEntries("Box", box)),
      OpTxBoolPair      -> envelope(OpTxBoolPair, boolPairEntries("Transaction", tx)),
      OpBoxSigmaBoolean -> envelope(OpBoxSigmaBoolean, sigmaBooleanTreeEntries("Box", box)),
      OpTxSigmaBoolean  -> envelope(OpTxSigmaBoolean, sigmaBooleanTreeEntries("Transaction", tx)),
      OpConjectures     -> envelope(OpConjectures, conjectures),
      OpBoxUShort       -> envelope(OpBoxUShort, boxUShortEntries),
      OpTxUShort        -> envelope(OpTxUShort, txUShortEntries),
      OpConstUShort     -> envelope(OpConstUShort, constUShort))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireSizedTreeRequests", extract(), outDir)
}
