package santa

// Authored wire vectors — the parse-time rules for a spending proof's ContextExtension VALUES (sigmastate
// v6.0.6 `data/shared/src/main/scala/sigma/interpreter/ContextExtension.scala:52-66`), beside the
// count/id bounds of AuthoredWireContextExtensionBounds:
//   - CheckV6Type (rule 1019, `ValidationRules.scala:165-205`, applied at ContextExtension.scala:62): a
//     value whose type contains Option, Header or UnsignedBigInt, also inside collections and tuples, is
//     rejected at parse. The empty-collection forms carry no element data, so the type alone decides.
//   - value depth: each getValue and each nested data value takes one level of the reader's
//     MaxTreeDepth (110, `CoreByteReader.scala:127-129`), so Coll^109[Byte] is the deepest value that parses.
//   - repeated ids: the parsed pairs go through `values.toMap`, so a repeated id keeps its LAST value at
//     its FIRST position (Scala Map1..Map4). The JVM re-serializes the collapsed map; the tx id follows.
//
// Version context (3, 3) is the node's own for a v6 tx: a v4 block's txs parse under
// `Header.scriptAndTreeFromBlockVersions(4)` (ergo v6.0.6 `BlockTransactions.scala:195`), the post-6.0
// mempool under (activatedScriptVersion, activatedScriptVersion). The tree version matters here:
// UnsignedBigInt types and data exist only at ergoTree >= 3, so under (3, 0) the UnsignedBigInt rejects
// would fire at type parse instead of in CheckV6Type.
//
// Every tx spends one sigmaProp(true) box to one output; only its parse matters. extract() re-derives
// every verdict through WireCanonicalize — the path rudolph grades with — and fails loud if a reject is
// rejected for any reason other than the intended rule, or an accept is not canonical.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import sigma.{Colls, Evaluation}
import sigma.VersionContext
import sigma.ast.{BigIntConstant, CollectionConstant, Constant, EvaluatedValue, IntConstant, SBigInt, SByte,
  SCollection, SHeader, SInt, SOption, SType, STuple, SUnsignedBigInt, UnsignedBigIntConstant}
import sigma.data.{CBigInt, CUnsignedBigInt}
import sigma.interpreter.ContextExtension
import org.ergoplatform.ErgoBox

import RentFixtures._

object AuthoredWireContextExtensionParse {
  val V3: Byte  = VersionContext.V6SoftForkVersion // activated AND ergoTree: the node's v6 tx parse context
  val OpV6Type  = "Transaction.context_extension_v6_type"
  val OpDepth   = "Transaction.context_extension_depth_bound"
  val OpDupIds  = "Transaction.context_extension_duplicate_ids"
  val Source    = "santa:authored-context-extension-parse"

  /** The variable id every single-entry extension uses. */
  private val Id: Byte = 1

  private val spent: ErgoBox = box("santa:cep:box", 1000000000L, 1)
  private val outputs = Seq(candidate(spent.value, 1))

  /** UnsignedBigInt data only serializes at ergoTree >= 3, so the txs are written under (3, 3) too. */
  private def txWith(e: ContextExtension): Array[Byte] =
    VersionContext.withVersions(V3, V3)(txBytes(tx(Seq(input(spent, e)), outputs)))

  /** Offset of input 0's extension count byte: inputs count (1 VLQ byte) + boxId (32) + proof length
    * (1 VLQ byte, empty proof). Checked against the bytes before use. */
  private val ExtAt = 34

  /** The tx with input 0's (empty) extension replaced by raw `extBytes` — for a repeated id, which the
    * JVM serializer cannot write (its map holds each id once). */
  private def txWithRawExtension(extBytes: Array[Byte]): Array[Byte] = {
    val base = txWith(ContextExtension.empty)
    require(base(0) == 1 && base.slice(1, 33).sameElements(spent.id) && base(33) == 0 && base(ExtAt) == 0,
      s"unexpected tx layout — input 0's empty extension count is not at byte $ExtAt: ${hex(base)}")
    base.take(ExtAt) ++ extBytes ++ base.drop(ExtAt + 1)
  }

  /** One extension entry `[id][value]`, as the JVM serializer writes it. */
  private def entryBytes(id: Byte, v: EvaluatedValue[_ <: SType]): Array[Byte] =
    VersionContext.withVersions(V3, V3)(ContextExtension.serializer.toBytes(ContextExtension(Map(id -> v))).drop(1))

  private def emptyColl[T <: SType](tElem: T): Constant[SCollection[T]] =
    CollectionConstant[T](Colls.emptyColl(Evaluation.stypeToRType(tElem)), tElem)

  private def pair(a: Any, b: Any, t: STuple): Constant[SType] =
    Constant[SType]((a, b).asInstanceOf[SType#WrappedType], t)

  /** Coll^n[Byte]: n nested collections, each outer one holding one element, the innermost empty. */
  private def nestedCollOfBytes(n: Int): Constant[SType] = {
    require(n >= 2, s"Coll^$n[Byte]")
    val (value, tpe) = (2 to n).foldLeft[(Any, SType)]((Colls.emptyColl(Evaluation.stypeToRType(SByte)), SCollection(SByte))) {
      case ((inner, tInner), _) =>
        (Colls.fromItems[Any](inner)(Evaluation.stypeToRType(tInner).asInstanceOf[sigma.data.RType[Any]]),
          SCollection(tInner))
    }
    Constant[SType](value.asInstanceOf[SType#WrappedType], tpe)
  }

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(V3.toInt), "ergoTree" -> Json.fromInt(V3.toInt))

  private def causes(t: Throwable): List[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList

  private def canonical(in: String): String = WireCanonicalize.canonicalize("Transaction", in, V3, V3)

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

  /** A reject entry: the JVM must throw at parse, for the intended reason (`mustMention`). */
  private def reject(name: String, description: String, bytes: Array[Byte], mustMention: String): Json = {
    val in = hex(bytes)
    Try(canonical(in)) match {
      case Success(out) =>
        sys.error(s"$name: the JVM must REJECT at parse, but it round-tripped to $out")
      case Failure(t) =>
        require(causes(t).exists(_.contains(mustMention)),
          s"$name: rejected for the wrong reason (want '$mustMention'): ${causes(t).mkString(" <- ")}")
    }
    entry(name, description, in, "error" -> Json.fromString("errored"))
  }

  /** A non-identity entry: the JVM accepts, re-serializes to different bytes, and those are canonical. */
  private def collapse(name: String, description: String, bytes: Array[Byte]): Json = {
    val in = hex(bytes)
    val out = canonical(in)
    require(out != in, s"$name: the JVM must re-serialize these bytes differently — in $in")
    require(canonical(out) == out, s"$name: the JVM's output must round-trip to itself — out $out")
    entry(name, description, in, "expected_bytes_hex" -> Json.fromString(out))
  }

  private val V6TypeMsg = "V6 type used in register or context var extension"
  private val DepthMsg  = "nested value deserialization call depth"

  def extract(): Map[String, Json] = {
    val ubi = UnsignedBigIntConstant(1L)
    val v6TypeEntries = Seq(
      reject("ext-ubi-reject#0",
        "Spending-proof extension {1: UnsignedBigInt(1)}. Under the node's v6 tx parse context (3, 3) the " +
        "UnsignedBigInt data decodes, then CheckV6Type (rule 1019, ContextExtension.scala:62) rejects the tx " +
        "at parse: the type is v6-only. An impl without the extension-side check round-trips it: the over-accept.",
        txWith(ext(Id -> ubi)), V6TypeMsg),
      accept("ext-bigint-accept#1",
        "Spending-proof extension {1: BigInt(1)}: the signed twin of the UnsignedBigInt reject. Round-trip identity.",
        txWith(ext(Id -> BigIntConstant(1L)))),
      reject("ext-coll-option-int-empty-reject#2",
        "Spending-proof extension {1: Coll[Option[Int]]()}. The collection is empty, so no Option data is " +
        "read and the type alone decides: CheckV6Type walks into the element type and rejects Option at parse.",
        txWith(ext(Id -> emptyColl(SOption(SInt)))), V6TypeMsg),
      reject("ext-coll-header-empty-reject#3",
        "Spending-proof extension {1: Coll[Header]()}. Empty, so no Header data is read: CheckV6Type rejects " +
        "the Header element type at parse.",
        txWith(ext(Id -> emptyColl(SHeader))), V6TypeMsg),
      accept("ext-coll-int-empty-accept#4",
        "Spending-proof extension {1: Coll[Int]()}: the empty-collection twin of the Option and Header " +
        "rejects. Round-trip identity.",
        txWith(ext(Id -> emptyColl(SInt)))),
      reject("ext-tuple-int-ubi-reject#5",
        "Spending-proof extension {1: (1, UnsignedBigInt(1)): (Int, UnsignedBigInt)}. CheckV6Type walks the " +
        "tuple items and rejects the UnsignedBigInt one at parse.",
        txWith(ext(Id -> pair(1, CUnsignedBigInt(java.math.BigInteger.ONE), STuple(SInt, SUnsignedBigInt)))),
        V6TypeMsg),
      accept("ext-tuple-int-bigint-accept#6",
        "Spending-proof extension {1: (1, BigInt(1)): (Int, BigInt)}: the tuple twin. Round-trip identity.",
        txWith(ext(Id -> pair(1, CBigInt(java.math.BigInteger.ONE), STuple(SInt, SBigInt))))))

    val depthEntries = Seq(
      accept("ext-depth-coll109-accept#0",
        "Spending-proof extension {1: Coll^109[Byte]}, each outer collection holding one element and the " +
        "innermost Coll[Byte] empty. getValue takes one level and each collection level one more, so the value " +
        "uses exactly MaxTreeDepth = 110 (ValueSerializer.scala:396-398, CoreDataSerializer.scala:95-96). " +
        "Round-trip identity.",
        txWith(ext(Id -> nestedCollOfBytes(109)))),
      reject("ext-depth-coll110-reject#1",
        "Spending-proof extension {1: Coll^110[Byte]}: one level deeper, 111 > 110, and the JVM throws " +
        "DeserializeCallDepthExceeded at parse (CoreByteReader.scala:127-129). An impl with a higher depth limit, " +
        "or none, round-trips it: the over-accept.",
        txWith(ext(Id -> nestedCollOfBytes(110))), DepthMsg))

    def repeated(ids: Seq[Byte]): Array[Byte] = txWithRawExtension(
      Array(ids.size.toByte) ++ ids.zipWithIndex.flatMap { case (id, i) => entryBytes(id, IntConstant(i + 1)) })
    val dupEntries = Seq(
      collapse("ext-dup-ids-05-07-05-collapse#0",
        "Spending-proof extension with count 3 and entries 05 -> Int 1, 07 -> Int 2, 05 -> Int 3. The JVM builds " +
        "the map with values.toMap (ContextExtension.scala:65): the repeated id keeps its last value at its first " +
        "position, so the tx re-serializes with 2 entries, 05 -> Int 3, 07 -> Int 2, and the JVM tx id comes from " +
        "those collapsed bytes. Non-identity round-trip.",
        repeated(Seq(5, 7, 5))),
      collapse("ext-dup-ids-07-05-07-collapse#1",
        "The same collapse with the higher id first: 07 -> Int 1, 05 -> Int 2, 07 -> Int 3 re-serializes as " +
        "07 -> Int 3, 05 -> Int 2. An id-sorted map matches the JVM on entry #0 by coincidence, not on this one.",
        repeated(Seq(7, 5, 7))))

    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.6"),
      "entries"    -> Json.arr(entries: _*))
    Map(
      OpV6Type -> envelope(OpV6Type, v6TypeEntries),
      OpDepth  -> envelope(OpDepth, depthEntries),
      OpDupIds -> envelope(OpDupIds, dupEntries))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireContextExtensionParse", extract(), outDir)
}
