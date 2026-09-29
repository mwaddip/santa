package santa

// Authored wire vectors for sigma-rust's evaluated-values probe (2026-09-29): context-extension and register values
// that are EvaluatedValues but not Constants. sigmastate 6.0.6, under the node's v6 parse context (3, 3).
//
// 1. Parse. An extension value is read with getValue, cast to EvaluatedValue and checked by CheckV6Type
//    (`ContextExtension.scala:61-62`); a register value the same way (`ErgoBoxCandidate.scala:231-232`). The cast is a
//    real checkcast. EvaluatedValue is sealed (`values.scala:310`): Constant (with TrueLeaf and FalseLeaf, which are
//    ConstantNodes, `:771`, `:782`), GroupGenerator (`:738`), Tuple (`:807-809`, "required as Tuple can be in a
//    register") and ConcreteCollection (`:856`). So those parse and anything else throws ClassCastException. A Tuple's
//    or a collection's items are not cast at parse: Tuple(1, HEIGHT) parses. The tuple count is a signed byte with no
//    arity check (`TupleSerializer.scala:27-36`), so 0, 1 or 3 items parse and 0x80 reads -128. CheckV6Type walks a
//    Tuple's item types (`data/.../validation/ValidationRules.scala:190`), so an Option-typed item fails rule 1019. A
//    placeholder has no constant store to read from (`ConstantPlaceholderSerializer.scala:20`).
// 2. Write back. The transaction and a created box are written with putValue (`ContextExtension.scala:49`,
//    `ErgoBoxCandidate.scala:175`): a Constant goes through the constant path (`ValueSerializer.scala:362-371`), so 7f
//    and 80 come back as 01 01 and 01 00; a ConcreteCollection of Booleans whose items are all Constants uses the
//    packed 85 serializer (`values.scala:871-875`), an empty one included. Below tree v3, a leading Upcast of a
//    Constant is written as the Constant (`ValueSerializer.scala:157-169`: the strip decides only the constant case).
//    The version is the ambient VersionContext's, and the node computes a tx id at parse (`ErgoTransaction.scala:68`,
//    an eager val): v4+ blocks parse under (blockVersion - 1, blockVersion - 1), earlier blocks and anything outside
//    a context under the default (1, 1) (ergo `BlockTransactions.scala:184-202`, `VersionContext.scala:58-61`).
//
// Box entries are a bare box; Transaction entries a one-input transaction. extract() re-derives every verdict
// through WireCanonicalize and fails loud on a wrong-reason reject or a different re-serialization.

import io.circe.Json

import RentFixtures._

object AuthoredWireEvaluatedValues extends BoxTreeWireFixtures {
  val Source    = "santa:authored-evaluated-values"
  val OpExt     = "Transaction.extension_evaluated_values"
  val OpBoxRegs = "Box.register_evaluated_values"
  val OpTxRegs  = "Transaction.register_evaluated_values"

  /** The one-input transaction: 01 | box id (32) | proof 00 | extension 00 | data inputs 00 | tokens 00 | outputs 01. */
  private lazy val plainTx = txWith(placeholderBytes)
  /** The plain transaction with input 0's extension = {0: `value`}. */
  private def extTx(value: String): Array[Byte] = {
    require(hex(plainTx.slice(33, 35)) == "0000", s"unexpected transaction layout ${hex(plainTx)}")
    plainTx.take(34) ++ b("01" + "00" + value) ++ plainTx.drop(35)
  }
  /** A candidate with R4 = `value`: value 1000000, SigmaProp(true), height 1, no tokens. */
  private def withR4(value: String): Array[Byte] = Value ++ b("0008d3" + "01" + "00" + "01" + value)

  /** A non-identity accept under the version (`a`, `t`) instead of (3, 3). */
  private def acceptRewrittenAt(name: String, kind: String, description: String, bytes: Array[Byte],
                                rewritten: Array[Byte], a: Byte, t: Byte): Json = {
    val (in, want) = (hex(bytes), hex(rewritten))
    require(want != in, s"$name: a non-identity accept must change the bytes")
    val out = WireCanonicalize.canonicalize(kind, in, a, t)
    require(out == want, s"$name: the JVM must re-serialize to ${want.take(80)}…, got ${out.take(80)}…")
    entry(name, kind, description, in, "expected_bytes_hex" -> Json.fromString(want))
      .mapObject(_.add("version", Json.obj("activated" -> Json.fromInt(a.toInt), "ergoTree" -> Json.fromInt(t.toInt))))
  }

  private val Cast = "ClassCastException"

  def extract(): Map[String, Json] = {
    val kind = "Transaction"
    val ext = "A one-input transaction whose input 0 carries the extension {0: v}, with v"
    val parses = "An EvaluatedValue, so the JVM parses it (ContextExtension.scala:61)."
    val identity = "Round-trip identity. sigma-rust, which reads only a Constant here, rejects it: the over-reject."
    val extEntries = Seq(
      acceptRewritten("ext-x1-trueleaf-accept#0", kind,
        s"$ext = 7f, the TrueLeaf opcode. $parses TrueLeaf is a ConstantNode (values.scala:771), so putValue writes it " +
        "through the constant path (ValueSerializer.scala:362-371): 01 01. NON-IDENTITY; the tx id hashes the written " +
        "form.", extTx("7f"), extTx("0101")),
      acceptRewritten("ext-x2-falseleaf-accept#1", kind,
        s"$ext = 80, the FalseLeaf opcode. $parses Written back as 01 00. NON-IDENTITY.", extTx("80"), extTx("0100")),
      accept("ext-x3-group-generator-accept#2", kind,
        s"$ext = 82, GroupGenerator (values.scala:738). $parses $identity", extTx("82"), degrade = None),
      accept("ext-x4-coll-int-accept#3", kind,
        s"$ext = 83 02 04 04 02 04 04, a ConcreteCollection Coll[Int](1, 2). $parses $identity",
        extTx("83020404020404"), degrade = None),
      acceptRewritten("ext-x5-coll-boolean-constants-accept#4", kind,
        s"$ext = 83 02 01 01 01 01 00, a ConcreteCollection Coll[Boolean](true, false) of constant items. $parses A " +
        "Boolean collection whose items are all Constants is written with the packed serializer (values.scala:871-875): " +
        "85 02 01. NON-IDENTITY.", extTx("83020101010100"), extTx("850201")),
      acceptRewritten("ext-x6-coll-boolean-leaves-accept#5", kind,
        s"$ext = 83 02 01 7f 80, the same collection with TrueLeaf and FalseLeaf items, which are Constants too. " +
        s"$parses Written back packed: 85 02 01. NON-IDENTITY.", extTx("8302017f80"), extTx("850201")),
      acceptRewritten("ext-x7-coll-boolean-empty-accept#6", kind,
        s"$ext = 83 00 01, an empty Coll[Boolean]: forall over no items is true, so it is written packed: 85 00. " +
        "NON-IDENTITY.", extTx("830001"), extTx("8500")),
      accept("ext-x8-coll-boolean-packed-accept#7", kind,
        s"$ext = 85 02 01, the packed Boolean collection form. $parses $identity", extTx("850201"), degrade = None),
      accept("ext-x9-tuple-pair-accept#8", kind,
        s"$ext = 86 02 04 02 04 04, a Tuple node (1, 2). $parses $identity", extTx("860204020404"), degrade = None),
      accept("ext-x10-tuple-three-accept#9", kind,
        s"$ext = 86 03 04 02 04 04 04 06, a 3-item Tuple. The count is a signed byte and nothing checks the arity " +
        s"(TupleSerializer.scala:27-36). $parses $identity", extTx("8603040204040406"), degrade = None),
      accept("ext-x11-tuple-one-accept#10", kind,
        s"$ext = 86 01 04 02, a 1-item Tuple. $parses $identity", extTx("86010402"), degrade = None),
      accept("ext-x12-tuple-empty-accept#11", kind,
        s"$ext = 86 00, an empty Tuple. $parses $identity", extTx("8600"), degrade = None),
      accept("ext-x13-tuple-height-accept#12", kind,
        s"$ext = 86 02 04 02 a3, Tuple(1, HEIGHT). A Tuple's items are not cast at parse, and CheckV6Type only walks " +
        s"their types. $parses (Evaluating it fails; see the transaction vector evaluated-values-spend.) $identity",
        extTx("86020402a3"), degrade = None),
      accept("ext-x14-coll-int-height-accept#13", kind,
        s"$ext = 83 01 04 a3, Coll[Int](HEIGHT): a collection's items are not cast at parse either. $parses $identity",
        extTx("830104a3"), degrade = None),
      accept("ext-x15-tuple-upcast-v3-accept#14", kind,
        s"$ext = 86 02 04 02 7e 04 02 05, Tuple(1, Upcast(1, Long)). $parses From tree v3 the serializer keeps an " +
        "Upcast (ValueSerializer.scala:157-169), and v4 blocks parse their transactions under (3, 3) " +
        "(BlockTransactions.scala:184-202), where the tx id is computed (ErgoTransaction.scala:68, an eager val). " +
        s"$identity", extTx("860204027e040205"), degrade = None),
      reject("ext-n1-placeholder-reject#15", kind,
        s"$ext = 73 00, ConstantPlaceholder(0). An extension's reader has no constants, so the store lookup throws " +
        "(ConstantPlaceholderSerializer.scala:20): the JVM rejects. Twin that keeps a fix from over-accepting.",
        extTx("7300"), mention = Seq("ArrayIndexOutOfBoundsException")),
      reject("ext-n2-height-reject#16", kind,
        s"$ext = a3, HEIGHT: not an EvaluatedValue, so the cast (ContextExtension.scala:61) throws a " +
        "ClassCastException: the JVM rejects.", extTx("a3"), mention = Seq(Cast)),
      reject("ext-n3-plus-reject#17", kind,
        s"$ext = 9a 04 02 04 04, Plus(1, 2): the same cast fails, a reject.", extTx("9a04020404"), mention = Seq(Cast)),
      reject("ext-n4-tuple-placeholder-reject#18", kind,
        s"$ext = 86 02 04 02 73 00, a Tuple holding a placeholder: the item's store lookup throws, a reject.",
        extTx("860204027300"), mention = Seq("ArrayIndexOutOfBoundsException")),
      reject("ext-n5-tuple-getvar-reject#19", kind,
        s"$ext = 86 02 04 02 e3 00 04, a Tuple holding GetVar[Int](0), of type Option[Int]. CheckV6Type walks a " +
        "Tuple's item types (ValidationRules.scala:190), and an Option fails rule 1019: the JVM rejects.",
        extTx("86020402e30004"), mention = Seq("ValidationRule(1019")),
      reject("ext-n6-tuple-count-0x80-reject#20", kind,
        s"$ext = 86 80 followed by 128 × Int 1 (04 02). The Tuple's count is a getByte: -128, and the allocation " +
        "throws a NegativeArraySizeException: the JVM rejects. An impl that reads the count as an unsigned byte takes " +
        "128 items: the over-accept.", extTx("8680" + "0402" * 128), mention = Seq("NegativeArraySizeException")))

    def regEntries(kind: String, wrap: Array[Byte] => Array[Byte]): Seq[Json] = {
      val k = kind.toLowerCase
      val subject = (if (kind == "Box") "A bare box" else "A transaction output") +
        " (value 1000000, SigmaProp(true), height 1, no tokens) whose R4 is"
      val parses = "An EvaluatedValue, so the JVM parses it (ErgoBoxCandidate.scala:231)."
      val identity = "Round-trip identity."
      def c(v: String): Array[Byte] = wrap(withR4(v))
      Seq(
        accept(s"$k-g1-group-generator-accept#0", kind, s"$subject 82, GroupGenerator. $parses $identity",
          c("82"), degrade = None),
        accept(s"$k-g2-coll-int-accept#1", kind, s"$subject 83 02 04 04 02 04 04, Coll[Int](1, 2). $parses $identity",
          c("83020404020404"), degrade = None),
        acceptRewritten(s"$k-g3-trueleaf-accept#2", kind,
          s"$subject 7f, TrueLeaf. $parses Written back as the constant 01 01 (ErgoBoxCandidate.scala:175, putValue). " +
          "NON-IDENTITY." + (if (kind == "Box") " A standalone box keeps its bytes as received, and its id hashes " +
          "them; the round-trip re-serializes it." else " The output's box id and the tx id hash the written form."),
          c("7f"), c("0101")),
        acceptRewritten(s"$k-g4-coll-boolean-constants-accept#3", kind,
          s"$subject 83 02 01 01 01 01 00, Coll[Boolean](true, false) of constant items. $parses Written back packed: " +
          "85 02 01. NON-IDENTITY.", c("83020101010100"), c("850201")),
        accept(s"$k-g5-tuple-height-accept#4", kind,
          s"$subject 86 02 04 02 a3, Tuple(1, HEIGHT): items are not cast at parse. $parses $identity (Reading any " +
          "register of such a box fails; see the transaction vector evaluated-values-spend.)", c("86020402a3"),
          degrade = None),
        accept(s"$k-g6-tuple-one-accept#5", kind,
          s"$subject 86 01 04 02, a 1-item Tuple: no arity check. $parses $identity", c("86010402"), degrade = None),
        accept(s"$k-tuple-pair-accept#6", kind,
          s"$subject 86 02 04 02 04 04, Tuple(1, 2), a Tuple node of constants. $parses $identity",
          c("860204020404"), degrade = None),
        accept(s"$k-coll-int-height-accept#7", kind,
          s"$subject 83 01 04 a3, Coll[Int](HEIGHT). $parses $identity", c("830104a3"), degrade = None),
        reject(s"$k-placeholder-reject#8", kind, s"$subject 73 00, a placeholder with no store: the JVM rejects.",
          c("7300"), mention = Seq("ArrayIndexOutOfBoundsException")),
        reject(s"$k-height-reject#9", kind, s"$subject a3, HEIGHT: the EvaluatedValue cast throws, a reject.",
          c("a3"), mention = Seq(Cast)),
        reject(s"$k-plus-reject#10", kind, s"$subject 9a 04 02 04 04, Plus(1, 2): the cast throws, a reject.",
          c("9a04020404"), mention = Seq(Cast)),
        reject(s"$k-tuple-placeholder-reject#11", kind, s"$subject 86 02 04 02 73 00, a Tuple holding a placeholder: " +
          "a reject.", c("860204027300"), mention = Seq("ArrayIndexOutOfBoundsException")),
        reject(s"$k-tuple-getvar-reject#12", kind, s"$subject 86 02 04 02 e3 00 04, a Tuple holding GetVar[Int](0): " +
          "CheckV6Type (ErgoBoxCandidate.scala:232) fails rule 1019 on the Option item, a reject.",
          c("86020402e30004"), mention = Seq("ValidationRule(1019")),
        reject(s"$k-tuple-count-0x80-reject#13", kind, s"$subject 86 80 followed by 128 × Int 1: the count reads " +
          "-128, a reject.", c("8680" + "0402" * 128), mention = Seq("NegativeArraySizeException")))
    }

    Map(
      OpExt     -> envelope(OpExt, extEntries),
      OpBoxRegs -> envelope(OpBoxRegs, regEntries("Box", boxWith)),
      OpTxRegs  -> envelope(OpTxRegs, regEntries("Transaction", cand => txWith(cand))))
  }

  /** The v5 file (vectors/wire/v5/authored): X15 under (2, 2), a pre-6.0 parse context. */
  def extractV5(): Map[String, Json] = Map(OpExt -> envelope(OpExt, Seq(acceptRewrittenAt(
    "ext-x15-tuple-upcast-below-v3-accept#0", "Transaction",
    "A one-input transaction whose input 0 carries the extension {0: Tuple(1, Upcast(1, Long))} (86 02 04 02 7e 04 02 " +
    "05), under the v5 context (2, 2). Below tree v3 the serializer writes an Upcast of a Constant as the Constant " +
    "(ValueSerializer.scala:157-169): 86 02 04 02 04 02. NON-IDENTITY, and the tx id hashes that form. Blocks below v4 " +
    "parse their transactions outside any version context, under the default (1, 1) (VersionContext.scala:58-61, " +
    "BlockTransactions.scala:184-202), which strips the same way. The v6 file keeps it (tree v3).",
    extTx("860204027e040205"), extTx("860204020402"), 2, 2))))

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireEvaluatedValues", extract(), outDir)
  def writeVectorsV5(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireEvaluatedValuesV5", extractV5(), outDir)
}
