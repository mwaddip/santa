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
import sigma.ast.{ErgoTree, UnparsedErgoTree}
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction}

import RentFixtures._

object AuthoredWireBoxTreeParse {
  val V3: Byte = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 parse context
  val OpBoxWindow = "Box.tree_read_window"
  val OpTxWindow  = "Transaction.tree_read_window"
  val OpBoxRoot   = "Box.tree_root_type_check"
  val OpTxRoot    = "Transaction.tree_root_type_check"
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

    def envelope(op: String, es: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.6"),
      "entries"    -> Json.arr(es: _*))
    Map(
      OpBoxWindow -> envelope(OpBoxWindow, boxWindow),
      OpTxWindow  -> envelope(OpTxWindow, txWindow),
      OpBoxRoot   -> envelope(OpBoxRoot, rootEntries("Box", boxWith)),
      OpTxRoot    -> envelope(OpTxRoot, rootEntries("Transaction", cand => txWith(cand))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireBoxTreeParse", extract(), outDir)
}
