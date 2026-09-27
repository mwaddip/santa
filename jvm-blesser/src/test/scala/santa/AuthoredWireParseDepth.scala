package santa

// Authored wire vectors — one accept/reject pair for each path the JVM's parse-depth counter runs through.
//
// The reader carries one level for the whole transaction (sigmastate v6.0.6 `CoreByteReader.scala:127-129`:
// a level above MaxTreeDepth = 110 throws DeserializeCallDepthExceeded). Three sites raise it on entry and
// lower it on normal exit: `ValueSerializer.deserialize` (every value/expression node, :396-409),
// `CoreDataSerializer.deserialize` (every data value, :94-148; `DataSerializer` once around a Box or Header),
// and `SigmaBoolean.serializer.parse` (every node, :72-103). Type descriptors are not counted, and segregated
// tree constants are read without a value level (`ErgoTreeSerializer.scala:256`). Box trees parse on the
// transaction's reader (`ErgoBoxCandidate.scala:194`), so levels carry into them.
//
// DeserializeCallDepthExceeded is a SerializerException, never a ValidationException, so a too-deep
// size-flagged tree is a hard reject, not a soft-fork degrade. The degrade path itself (a ValidationException,
// e.g. an unknown opcode) does NOT lower the level again: `deserializeErgoTree`'s `finally` restores only the
// position limit (`ErgoTreeSerializer.scala:209-211`) and `ValueSerializer.deserialize` has no `finally`, so a
// tree degrading k levels deep leaves k levels on the reader for the rest of the transaction.
//
// Pairs, as the node parses a v6 tx (version context (3, 3)):
//   register        output R4 = Coll^n[Byte]: 1 + n levels                       n = 109 / 110
//   tree body       BoolToSigmaProp(LogicalNot^m(placeholder)), size-flagged: m + 2   m = 108 / 109
//   segregated      unflagged tree with an unused constant Coll^n[Byte]: n levels   n = 110 / 111
//   SigmaBoolean    extension SigmaProp, k nested CAND(inner, TrueProp): k + 3      k = 107 / 108
//   nested box      extension Box whose size-flagged tree carries Coll^n: 2 + n     n = 108 / 109
//   degrade leak    output 0 degrades 10 deep; output 1 R4 = Coll^n: 10 + 1 + n      n = 99 / 100
//
// extract() re-derives every verdict through WireCanonicalize — the path rudolph grades with — and fails loud
// if a reject is rejected for any reason other than the depth cap, if an accept is not canonical, or if a
// size-flagged tree that should parse degraded instead (an accept that tests nothing). The degrade-leak
// reject also has a control: the same output 1 behind a normally parsing output 0 must accept.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BoolToSigmaProp, Constant, ConstantPlaceholder, ErgoTree, LogicalNot, SBoolean, SBox,
  SSigmaProp, SType, SigmaPropConstant, TrueLeaf, Value}
import sigma.ast.syntax.SigmaPropValue
import sigma.data.{CAND, CBox, SigmaBoolean, TrivialProp}
import sigma.interpreter.ContextExtension
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction}
import org.ergoplatform.ErgoBox.R4

import RentFixtures._

object AuthoredWireParseDepth {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 tx parse context
  val OpRegister           = "Transaction.register_depth_bound"
  val OpTreeBody           = "Transaction.tree_body_depth_bound"
  val OpSegregatedConstant = "Transaction.segregated_constant_depth_bound"
  val OpSigmaBoolean       = "Transaction.sigma_boolean_depth_bound"
  val OpNestedBox          = "Transaction.nested_box_depth_bound"
  val OpDegradeLeak        = "Transaction.degraded_tree_depth_leak"
  val Source               = "santa:authored-parse-depth"

  /** The variable id every single-entry extension uses. */
  private val Id: Byte = 1
  /** Depth of the unknown opcode in the degrading tree: BoolToSigmaProp, 8 LogicalNot, then 0xfd. */
  private val LeakDepth = 10

  private val spent: ErgoBox = box("santa:cpd:box", 1000000000L, 1)
  private val plainOutput = candidate(spent.value, 1)

  private def tx(e: ContextExtension, outs: Seq[ErgoBoxCandidate]): Array[Byte] =
    VersionContext.withVersions(V3, V3)(txBytes(RentFixtures.tx(Seq(input(spent, e)), outs)))

  private val SegregationHeader = ErgoTree.setConstantSegregation(ErgoTree.ZeroHeader)       // 0x10
  private val SizedSegregationHeader = ErgoTree.setSizeBit(SegregationHeader)                // 0x18
  private val TrueSigmaProp: Constant[SType] = SigmaPropConstant(TrivialProp.TrueProp).asInstanceOf[Constant[SType]]

  /** A v0 segregated tree whose constant 0 is `c`, unused by the body `placeholder(1): SigmaProp(true)`. */
  private def treeCarrying(header: ErgoTree.HeaderType, c: Constant[SType]): ErgoTree =
    VersionContext.withVersions(V3, V3) {
      ErgoTree(header, IndexedSeq(c, TrueSigmaProp),
        ConstantPlaceholder(1, SSigmaProp).asInstanceOf[SigmaPropValue])
    }

  /** BoolToSigmaProp(LogicalNot^m(placeholder 0 = true)), size-flagged: its deepest node is at level m + 2. */
  private def notChainTree(m: Int): ErgoTree =
    VersionContext.withVersions(V3, V3) {
      val leaf: Value[SBoolean.type] = ConstantPlaceholder(0, SBoolean)
      val chain = (1 to m).foldLeft(leaf)((inner, _) => LogicalNot(inner))
      ErgoTree(SizedSegregationHeader, IndexedSeq(TrueLeaf.asInstanceOf[Constant[SType]]), BoolToSigmaProp(chain))
    }

  /** CAND(inner, TrueProp) nested k deep around TrueProp. */
  private def nestedCand(k: Int): SigmaBoolean =
    (1 to k).foldLeft[SigmaBoolean](TrivialProp.TrueProp)((inner, _) => CAND(Seq(inner, TrivialProp.TrueProp)))

  /** v3 size-flagged tree degrading at depth LeakDepth: BoolToSigmaProp, LogicalNot x8, unknown opcode 0xfd. */
  private val DegradingTreeHex = "0b" + "%02x".format(LeakDepth) + "d1" + "ef" * (LeakDepth - 2) + "fd"
  private lazy val degradingTree: ErgoTree = VersionContext.withVersions(V3, V3) {
    val t = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(Base16.decode(DegradingTreeHex).get)
    require(t.root.isLeft, s"$DegradingTreeHex must degrade to UnparsedErgoTree — the leak needs a degrade")
    t
  }

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  private def causes(t: Throwable): List[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList

  private def canonical(in: String): String = WireCanonicalize.canonicalize("Transaction", in, V3, V3)

  private def parsed(bytes: Array[Byte]): ErgoLikeTransaction = VersionContext.withVersions(V3, V3) {
    ErgoLikeTransaction.serializer.parse(SigmaSerializer.startReader(bytes))
  }

  private def entry(name: String, description: String, in: String, extra: (String, Json)*): Json =
    Json.obj(Seq(
      "name" -> Json.fromString(name), "kind" -> Json.fromString("Transaction"),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in)) ++ extra ++ Seq("version" -> version): _*)

  /** An accept entry: the JVM must round-trip the bytes to themselves. */
  private def accept(name: String, description: String, bytes: Array[Byte]): Json = {
    val in = hex(bytes)
    val out = canonical(in)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    entry(name, description, in)
  }

  /** A reject entry: the JVM must throw at parse because of the depth cap. */
  private def reject(name: String, description: String, bytes: Array[Byte]): Json = {
    val in = hex(bytes)
    Try(canonical(in)) match {
      case Success(out) =>
        sys.error(s"$name: the JVM must REJECT at parse, but it round-tripped to $out")
      case Failure(t) =>
        require(causes(t).exists(_.contains(DepthMsg)),
          s"$name: rejected for the wrong reason (want '$DepthMsg'): ${causes(t).mkString(" <- ")}")
    }
    entry(name, description, in, "error" -> Json.fromString("errored"))
  }

  private val DepthMsg = "nested value deserialization call depth"

  def extract(): Map[String, Json] = {
    def withR4(n: Int): ErgoBoxCandidate = candidate(spent.value, 1, regs = Map(R4 -> nestedCollOfBytes(n)))
    val registerEntries = Seq(
      accept("register-coll109-accept#0",
        "Output R4 = Coll^109[Byte] (each outer collection holding one element, the innermost empty). The register " +
        "is read with getValue (ErgoBoxCandidate.scala:231): one value level plus one per collection level, 110 = " +
        "MaxTreeDepth. Round-trip identity.",
        tx(ContextExtension.empty, Seq(withR4(109)))),
      reject("register-coll110-reject#1",
        "Output R4 = Coll^110[Byte]: 111 levels, and the JVM throws DeserializeCallDepthExceeded at parse. An impl " +
        "without the counter round-trips it: the over-accept.",
        tx(ContextExtension.empty, Seq(withR4(110)))))

    def withTree(t: ErgoTree): Seq[ErgoBoxCandidate] = Seq(candidate(spent.value, 1, tree = t))
    val bodyAccept = tx(ContextExtension.empty, withTree(notChainTree(108)))
    require(parsed(bodyAccept).outputCandidates(0).ergoTree.root.isRight,
      "the 108-LogicalNot tree must parse, not degrade — else the accept tests nothing")
    val bodyEntries = Seq(
      accept("tree-body-108-not-accept#0",
        "Output tree, size-flagged and segregated (header 0x18): BoolToSigmaProp(LogicalNot^108(placeholder 0 = " +
        "true)). Every expression node takes a value level and the placeholder has no data level, so the deepest " +
        "node sits at 110 = MaxTreeDepth. Round-trip identity.",
        bodyAccept),
      reject("tree-body-109-not-reject#1",
        "The same tree with 109 LogicalNot: 111 levels. DeserializeCallDepthExceeded is a SerializerException, not a " +
        "ValidationException, so the size flag does not soften it into an UnparsedErgoTree: the tx is rejected at " +
        "parse. An impl that degrades on it, or has no counter, round-trips the tx: the over-accept.",
        tx(ContextExtension.empty, withTree(notChainTree(109)))))

    val segregatedEntries = Seq(
      accept("segregated-coll110-accept#0",
        "Output tree, segregated without the size flag (header 0x10), constants [Coll^110[Byte], SigmaProp(true)], " +
        "body placeholder 1. Segregated constants are read without a value level (ErgoTreeSerializer.scala:256), so " +
        "Coll^110 takes exactly 110 data levels. Round-trip identity.",
        tx(ContextExtension.empty, withTree(treeCarrying(SegregationHeader, nestedCollOfBytes(110))))),
      reject("segregated-coll111-reject#1",
        "The same tree with constant Coll^111[Byte]: 111 levels, rejected at parse. An impl that also charges a value " +
        "level here rejects the accept entry instead.",
        tx(ContextExtension.empty, withTree(treeCarrying(SegregationHeader, nestedCollOfBytes(111))))))

    def candExt(k: Int): ContextExtension = ext(Id -> SigmaPropConstant(nestedCand(k)))
    val sigmaBooleanEntries = Seq(
      accept("sigma-boolean-cand107-accept#0",
        "Spending-proof extension {1: SigmaProp} holding CAND(inner, TrueProp) nested 107 deep around TrueProp. " +
        "getValue takes a level, the SigmaProp data value one, and every SigmaBoolean node one (SigmaBoolean.scala:72-" +
        "103), so the innermost TrueProp sits at 110 = MaxTreeDepth. Round-trip identity.",
        tx(candExt(107), Seq(plainOutput))),
      reject("sigma-boolean-cand108-reject#1",
        "The same SigmaProp with 108 nested CAND: 111 levels, rejected at parse. An impl without the counter on " +
        "SigmaBoolean parsing round-trips it: the over-accept.",
        tx(candExt(108), Seq(plainOutput))))

    def boxExt(n: Int): ContextExtension = {
      val inner = box("santa:cpd:inner-box", 1000000L, 1, tree = treeCarrying(SizedSegregationHeader, nestedCollOfBytes(n)))
      ext(Id -> Constant[SType](CBox(inner).asInstanceOf[SType#WrappedType], SBox))
    }
    val nestedAccept = tx(boxExt(108), Seq(plainOutput))
    val nestedBox = parsed(nestedAccept).inputs(0).spendingProof.extension.values(Id)
      .asInstanceOf[Constant[SType]].value.asInstanceOf[CBox].ebox
    require(nestedBox.ergoTree.root.isRight,
      "the Coll^108 box tree must parse, not degrade — else the accept tests nothing")
    val nestedEntries = Seq(
      accept("nested-box-coll108-accept#0",
        "Spending-proof extension {1: Box} whose tree is size-flagged and segregated (header 0x18), constants " +
        "[Coll^108[Byte], SigmaProp(true)]. The Box is read on the transaction's reader: getValue and the Box data " +
        "value take 2 levels, the constant 108 more, 110 = MaxTreeDepth. Round-trip identity.",
        nestedAccept),
      reject("nested-box-coll109-reject#1",
        "The same Box with constant Coll^109[Byte]: 111 levels, rejected at parse. An impl that parses a size-flagged " +
        "tree on a fresh reader counts only 109 and round-trips the tx: the over-accept.",
        tx(boxExt(109), Seq(plainOutput))))

    def leakOutputs(n: Int, first: ErgoBoxCandidate): Seq[ErgoBoxCandidate] =
      Seq(first, candidate(spent.value - 1000000L, 1, regs = Map(R4 -> nestedCollOfBytes(n))))
    val degradedFirst = new ErgoBoxCandidate(1000000L, degradingTree, 1)
    val leakAccept = tx(ContextExtension.empty, leakOutputs(99, degradedFirst))
    require(parsed(leakAccept).outputCandidates(0).ergoTree.root.isLeft, "output 0 must degrade in the tx")
    // Control: behind a normally parsing output 0, the reject's output 1 must accept — the leak is the cause.
    val control = hex(tx(ContextExtension.empty, leakOutputs(100, candidate(1000000L, 1))))
    require(canonical(control) == control, "control: Coll^100 in output 1 must accept on a fresh level")
    val leakEntries = Seq(
      accept("degrade-leak-coll99-accept#0",
        s"Output 0's tree is size-flagged v3 ($DegradingTreeHex): BoolToSigmaProp, 8 LogicalNot, then unknown opcode " +
        "0xfd at depth 10. CheckValidOpCode throws a ValidationException, the tree degrades to UnparsedErgoTree, and " +
        "the 10 levels are never lowered again (ErgoTreeSerializer.scala:209-211 restores only the position limit). " +
        "Output 1 R4 = Coll^99[Byte] then takes 10 + 1 + 99 = 110. Round-trip identity.",
        leakAccept),
      reject("degrade-leak-coll100-reject#1",
        "The same tx with output 1 R4 = Coll^100[Byte]: 10 leaked + 1 + 100 = 111, rejected at parse. On a fresh " +
        "level it would take 101 and parse (the blesser's control), so an impl that restores the level after the " +
        "degrade round-trips the tx: the over-accept.",
        tx(ContextExtension.empty, leakOutputs(100, degradedFirst))))

    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.6"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpRegister           -> envelope(OpRegister, registerEntries),
      OpTreeBody           -> envelope(OpTreeBody, bodyEntries),
      OpSegregatedConstant -> envelope(OpSegregatedConstant, segregatedEntries),
      OpSigmaBoolean       -> envelope(OpSigmaBoolean, sigmaBooleanEntries),
      OpNestedBox          -> envelope(OpNestedBox, nestedEntries),
      OpDegradeLeak        -> envelope(OpDegradeLeak, leakEntries))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireParseDepth", extract(), outDir)
}
