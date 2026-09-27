package santa

// Authored wire vectors — ContextExtension bounds (sigmastate v6.0.6
// `data/shared/src/main/scala/sigma/interpreter/ContextExtension.scala:44-66`).
//
// The JVM reads a spending proof's extension COUNT and every variable ID as SIGNED bytes and rejects a
// negative one AT PARSE: count >= 128 (every sigmastate version) and id >= 0x80 (since 6.0.5,
// sigmastate commit e4ef1b203, not version-gated). An impl reading either as u8 parses what the JVM
// rejects. Parse-level rejects live in the wire tier (the tx-tier blesser cannot decode them), as
// santa-wire/v1 REJECT entries (`error: errored`); the accept twins sit on the other side of each bound.
//
// Every transaction is a well-formed storage-rent spend (RentFixtures), so a lenient parser round-trips
// the reject bytes cleanly — the over-accept surfaces as a round-trip, never as an incidental EOF.
// Entry order inside every extension is the JVM's own (Scala Map1..Map4 for small maps, the HashMap
// iteration order for large ones), so both received-order and HAMT-order serializers round-trip the
// accepts byte-exactly: these vectors pin the bounds, not the parked extension-ordering question.
//
// extract() re-derives every verdict through WireCanonicalize — the path rudolph grades with — and
// fails loud if a reject is rejected for any reason other than the intended bound.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import sigma.VersionContext
import sigma.ast.{EvaluatedValue, IntConstant, SType, ShortConstant}
import sigma.interpreter.ContextExtension
import org.ergoplatform.ErgoBox

import RentFixtures._

object AuthoredWireContextExtensionBounds {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3
  val ErgoTreeV: Byte = 0                                // every tree in these txs is a v0 sigmaProp
  val OpCount = "Transaction.context_extension_count_bound"
  val OpId    = "Transaction.context_extension_id_bound"
  val Source  = "santa:authored-context-extension-bounds"

  // The spend: a height-0 sigmaProp(true) box recreated at StoragePeriod (a valid rent spend when the
  // extension is {127: Short(0)}), plus the storage fee as a second output. Only its parse matters here.
  private val rentBox: ErgoBox = box("santa:ceb:rent-box", 5000000000L, 0)
  private val outputs = {
    val fee = trueStorageFee(rentBox)
    Seq(recreate(rentBox, rentBox.value - fee, StoragePeriod), candidate(fee, StoragePeriod))
  }
  private def txWith(e: ContextExtension): Array[Byte] = txBytes(tx(Seq(input(rentBox, e)), outputs))

  /** Offset of input 0's extension count byte: inputs count (1 VLQ byte) + boxId (32) + proof length
    * (1 VLQ byte, empty proof). Checked against the bytes before use. */
  private val ExtAt = 34

  /** The tx with input 0's (empty) extension replaced by raw `extBytes` — for a count the JVM
    * serializer refuses to write (> 127 entries). */
  private def txWithRawExtension(extBytes: Array[Byte]): Array[Byte] = {
    val base = txWith(ContextExtension.empty)
    require(base(0) == 1 && base.slice(1, 33).sameElements(rentBox.id) && base(33) == 0 && base(ExtAt) == 0,
      s"unexpected tx layout — input 0's empty extension count is not at byte $ExtAt: ${hex(base)}")
    base.take(ExtAt) ++ extBytes ++ base.drop(ExtAt + 1)
  }

  /** One extension entry `[id][value]`, as the JVM serializer writes it. */
  private def entryBytes(id: Byte, v: EvaluatedValue[_ <: SType]): Array[Byte] =
    ContextExtension.serializer.toBytes(ContextExtension(Map(id -> v))).drop(1)

  /** ids 0 until n, each bound to IntConstant(id), in the JVM's own HashMap iteration order. */
  private def hashOrderIds(n: Int): Seq[Byte] = (0 until n).map(i => i.toByte -> i).toMap.keys.toSeq

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(Activated.toInt), "ergoTree" -> Json.fromInt(ErgoTreeV.toInt))

  private def causes(t: Throwable): List[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList

  /** An accept entry: the JVM must round-trip the bytes to themselves. */
  private def accept(name: String, description: String, bytes: Array[Byte]): Json = {
    val in = hex(bytes)
    val out = WireCanonicalize.canonicalize("Transaction", in, Activated, ErgoTreeV)
    require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
    Json.obj(
      "name" -> Json.fromString(name), "kind" -> Json.fromString("Transaction"),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in), "version" -> version)
  }

  /** A reject entry: the JVM must throw at parse, for the intended reason (`mustMention`). */
  private def reject(name: String, description: String, bytes: Array[Byte], mustMention: String): Json = {
    val in = hex(bytes)
    Try(WireCanonicalize.canonicalize("Transaction", in, Activated, ErgoTreeV)) match {
      case Success(out) =>
        sys.error(s"$name: the JVM must REJECT at parse, but it round-tripped to $out")
      case Failure(t) =>
        require(causes(t).exists(_.contains(mustMention)),
          s"$name: rejected for the wrong reason (want '$mustMention'): ${causes(t).mkString(" <- ")}")
    }
    Json.obj(
      "name" -> Json.fromString(name), "kind" -> Json.fromString("Transaction"),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in), "error" -> Json.fromString("errored"), "version" -> version)
  }

  private val CountMsg = "Negative amount of context extension values"
  private val IdMsg    = "Negative id of context extension variable"

  def extract(): Map[String, Json] = {
    // count 128 (byte 0x80) with 128 well-formed entries, ids 0..127: every id valid, only the count is out.
    val count128 = txWithRawExtension(
      Array(0x80.toByte) ++ hashOrderIds(128).flatMap(id => entryBytes(id, IntConstant(id.toInt))))
    // count 127, ids 0..126: the JVM serializer writes it (HashMap order), so its round-trip is identity.
    val count127 = txWith(ContextExtension(hashOrderIds(127).map(id => id -> IntConstant(id.toInt)).toMap))
    require(count127(ExtAt) == 127.toByte, s"count-127 extension must start with 0x7f at byte $ExtAt")

    val countEntries = Seq(
      reject("ext-count-128-reject#0",
        "Spending-proof extension whose count byte is 0x80 (128) followed by 128 well-formed entries, ids " +
        "0..127 bound to IntConstant(id). sigmastate reads the count as a signed byte and rejects a negative " +
        "count at parse (ContextExtension.scala:53-55, every version). An impl reading the count as u8 parses " +
        "all 128 entries and round-trips the tx: the over-accept.",
        count128, CountMsg),
      accept("ext-count-127-accept#1",
        "Spending-proof extension with 127 entries (ids 0..126, IntConstant(id)): the largest count the JVM " +
        "parses and serializes (serialize errors above Byte.MaxValue, ContextExtension.scala:46-47). Entries " +
        "are in the JVM's own HashMap iteration order, so the round-trip is identity.",
        count127))

    val rentExt = ext(StorageIndexVarId -> ShortConstant(0))
    val idEntries = Seq(
      reject("ext-id-0x80-reject#0",
        "Spending-proof extension {0x80: IntConstant(1)}. sigmastate >= 6.0.5 reads the id as a signed byte " +
        "and rejects a negative one at parse, before reading its value (ContextExtension.scala:58-60, commit " +
        "e4ef1b203, not version-gated). An impl reading the id as u8 parses it: the over-accept.",
        txWith(ext((-128).toByte -> IntConstant(1))), IdMsg),
      reject("ext-id-0xff-reject#1",
        "Spending-proof extension {0xFF: IntConstant(1)}: the top of the id byte, rejected at parse like 0x80.",
        txWith(ext((-1).toByte -> IntConstant(1))), IdMsg),
      reject("ext-rent-shaped-id-0x80-reject#2",
        "A storage-rent spend (empty proof, var 127 = Short(0) indexing a correct recreation) whose extension " +
        "also holds {0x80: IntConstant(1)}. The JVM rejects the whole tx at parse. Before 6.0.5 it accepted " +
        "this spend (the rent path never evaluates the script, so no context-var check ran); an impl that " +
        "only guards ids at evaluation time still accepts it.",
        txWith(ext(StorageIndexVarId -> ShortConstant(0), (-128).toByte -> IntConstant(1))), IdMsg),
      accept("ext-id-0x7f-accept#3",
        "Spending-proof extension {0x7F: Short(0)}: the largest valid id — the storage-rent index variable " +
        "(StorageIndexVarId = Byte.MaxValue). Round-trip identity.",
        txWith(rentExt)))

    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.6"),
      "entries"    -> Json.arr(entries: _*))
    Map(OpCount -> envelope(OpCount, countEntries), OpId -> envelope(OpId, idEntries))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireContextExtensionBounds", extract(), outDir)
}
