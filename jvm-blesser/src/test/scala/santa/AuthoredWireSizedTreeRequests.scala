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
//    size bit."), which does not degrade anything, and neither does an SHeader register in a pre-v3 tree (no data
//    serializer).
// 2. Count bounds. SigmaAnd's item count and Apply's argument count go through safeNewArray, which throws above
//    MaxArrayLength 100000 (`sigma/util/package.scala:7-12`, `SigmaTransformerSerializer.scala:21-25`,
//    `SigmaByteReader.scala:53-59`); a collection count goes through getUShort (`ConcreteCollectionSerializer.scala:28`).
//    None of those is a ValidationException. At the bound the parser reads on, and a tree window degrades it.
// 3. Counts that wrap. The constants count is `getUInt().toInt`; a negative count means no constants
//    (`ErgoTreeSerializer.scala:248-261`), and the tree comes back with count 0. scorex-util 0.2.0's getUShort is
//    `getULong().toInt` and only then the 0..65535 check, so a count of 2^32 + k reads as k.
// 4. Header bits 5-7 are kept as they are. 5. Root forms under rule 1001. 6. The 85 pair form belongs to the nine
//    relations only; an arithmetic operand starting with 85 is a Boolean collection. 7. CTHRESHOLD requires
//    0 <= k <= n <= 255 after reading the children (`SigmaBoolean.scala:223`); CAND and COR check nothing.

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

  /** height 1, no tokens, R4 = Coll[Byte](n - 6): the n bytes a bulk read crosses the window with, and that a degrade
    * resumes the box at (as AuthoredWireBoxTreeParse's WinDegrade). */
  private def payload(n: Int): Array[Byte] = b("01" + "00" + "01" + "0e") ++ vlq(n - 6) ++ zeros(n - 6)
  /** Sized v0 tree whose declared size covers `prefix`, a Coll[Byte] bulk read's type and length; then the payload.
    * The bulk read starts at candidate offset 3 + 2 + |prefix| + 3 and ends at 4100, past the tree window (4099). */
  private def countTree(prefix: String, collType: String): Array[Byte] = {
    val n = MaxSize + 4 - 3 - 2 - prefix.length / 2 - collType.length / 2 - 2 // ends at 4100: value, header+size, VLQ n
    require(vlq(n).length == 2, s"n = $n must take a 2-byte VLQ")
    sizedTree(0x08, b(prefix + collType) ++ vlq(n)) ++ payload(n)
  }

  /** An accept entry for a kind with no tree (SigmaBoolean): identity round-trip. */
  private def acceptPlain(name: String, kind: String, description: String, bytes: Array[Byte]): Json = {
    val in = hex(bytes)
    require(canonical(kind, in) == in, s"$name: the JVM must round-trip an accept vector to itself")
    entry(name, kind, description, in)
  }

  private val NoSizeBit = "ErgoTree serialized without size bit"
  private val MaxArray  = "max limit is 100000"

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
          cand(boxConstTree(0x1b, nestedBox("0008d3", "07" + OptionIntSome1 + "0402" * 6))), degrade = Some(1019)))
    }

    def countBoundEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
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
          cand(countTree("da0402" + hexVlq(100001), "0e")), mention = Seq(MaxArray)))
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
          "Round-trip identity.", cand("00d1938503"), degrade = None))
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
          "Round-trip identity.", cand(b("00089600")), degrade = None))
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
          "CTHRESHOLD(0, []) (98 00 00). Round-trip identity.", b("980000")))
    }

    val box: Array[Byte] => Array[Byte] = boxWith
    val tx: Array[Byte] => Array[Byte] = cand => txWith(cand)
    val txMid: Array[Byte] => Array[Byte] = cand => txWith(cand, Seq(candidate(1000000L, 2)))
    Map(
      OpBoxNested       -> envelope(OpBoxNested, nestedEntries("Box", box)),
      OpTxNested        -> envelope(OpTxNested, nestedEntries("Transaction", tx)),
      OpBoxCountBounds  -> envelope(OpBoxCountBounds, countBoundEntries("Box", box)),
      OpTxCountBounds   -> envelope(OpTxCountBounds, countBoundEntries("Transaction", txMid)),
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
      OpConjectures     -> envelope(OpConjectures, conjectures))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireSizedTreeRequests", extract(), outDir)
}
