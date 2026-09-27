package santa

// Authored wire vectors — the creation-height parse bound (sigmastate v6.0.6
// `data/shared/src/main/scala/org/ergoplatform/ErgoBoxCandidate.scala:195`).
//
// Since v5 the JVM reads a box's creation height with `getUIntExact` (`getUInt().toIntExact`), so any
// height above Int.MaxValue — a V1-era "negative" height, 0x80000000..0xFFFFFFFF on the wire — throws
// `ArithmeticException: Int overflow` AT PARSE, wherever a box is parsed: a Box, a transaction output
// candidate, and a Box-typed constant (SBox data) in a context extension or a register. An impl reading
// the height as u32 parses them all. Runtime-confirmed on 6.0.6 (spike CreationHeightOverflowSpike):
// 0x7FFFFFFF parses, 0x80000000 and 0xFFFFFFFF throw. The same code is in v6.0.3: a pre-existing
// divergence, not a 6.0.3 -> 6.0.6 change.
//
// Replaces the plan's T7 (a V1 negative-height box spent through the storage-rent gate), which the
// oracle cannot express: it cannot load such a box at all. The Box-constant forms are the ones reachable
// at block version >= 2, where a plain output with such a height is also refused later by stateful rules.
//
// Every reject is its accept twin with ONE field changed: the 5-byte height VLQ ffffffff07 (Int.MaxValue)
// becomes 8080808008 (0x80000000) or ffffffff0f (0xFFFFFFFF). The twin's acceptance proves the rest of the
// bytes well-formed, so a lenient parser round-trips the reject cleanly.

import scala.util.{Failure, Success, Try}

import io.circe.Json
import sigma.VersionContext
import sigma.ast.BoxConstant
import sigma.data.CBox
import org.ergoplatform.ErgoBox

import RentFixtures._

object AuthoredWireCreationHeightBound {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3
  val ErgoTreeV: Byte = 0
  val OpBox = "Box.creation_height_int_bound"
  val OpTx  = "Transaction.creation_height_int_bound"
  val Source = "santa:authored-creation-height-int-bound"

  private val MaxVlq   = vlqU32(Int.MaxValue.toLong) // ff ff ff ff 07
  private val MinNeg   = vlqU32(0x80000000L)          // 80 80 80 80 08
  private val AllOnes  = vlqU32(0xFFFFFFFFL)          // ff ff ff ff 0f
  require(MaxVlq.length == 5 && MinNeg.length == 5 && AllOnes.length == 5, "height VLQs must all be 5 bytes")

  /** A box created at Int.MaxValue — the highest height the JVM parses. */
  private val boxAtMax: ErgoBox = box("santa:chb:box", 1000000000L, Int.MaxValue)

  private def version: Json =
    Json.obj("activated" -> Json.fromInt(Activated.toInt), "ergoTree" -> Json.fromInt(ErgoTreeV.toInt))

  private def causes(t: Throwable): List[String] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList

  private def entry(name: String, kind: String, description: String, bytes: Array[Byte], reject: Boolean): Json = {
    val in = hex(bytes)
    Try(WireCanonicalize.canonicalize(kind, in, Activated, ErgoTreeV)) match {
      case Success(out) =>
        require(!reject, s"$name: the JVM must REJECT at parse, but it round-tripped to $out")
        require(out == in, s"$name: the JVM must round-trip an accept vector to itself — in $in, out $out")
      case Failure(t) =>
        require(reject, s"$name: the JVM must accept, but threw: ${causes(t).mkString(" <- ")}")
        require(causes(t).exists(c => c.contains("ArithmeticException") && c.contains("Int overflow")),
          s"$name: rejected for the wrong reason (want the getUIntExact Int overflow): ${causes(t).mkString(" <- ")}")
    }
    val base = Json.obj(
      "name" -> Json.fromString(name), "kind" -> Json.fromString(kind),
      "source" -> Json.fromString(Source), "description" -> Json.fromString(description),
      "bytes_hex" -> Json.fromString(in))
    (if (reject) base.deepMerge(Json.obj("error" -> Json.fromString("errored"))) else base)
      .deepMerge(Json.obj("version" -> version))
  }

  def extract(): Map[String, Json] = {
    val boxBytes = ErgoBox.sigmaSerializer.toBytes(boxAtMax)
    val boxEntries = Seq(
      entry("box-creation-height-0x7fffffff-accept#0", "Box",
        "ErgoBox created at height 0x7FFFFFFF (Int.MaxValue): the highest creation height the JVM parses " +
        "(getUIntExact, ErgoBoxCandidate.scala:195). Round-trip identity.",
        boxBytes, reject = false),
      entry("box-creation-height-0x80000000-reject#1", "Box",
        "The same box with its creation height VLQ set to 0x80000000 (Int.MinValue as an Int). The JVM's " +
        "getUIntExact throws ArithmeticException: Int overflow at parse (v5+; v4 read it as a negative Int). " +
        "An impl reading the height as u32 parses the box: the over-accept.",
        spliceUnique(boxBytes, MaxVlq, MinNeg), reject = true),
      entry("box-creation-height-0xffffffff-reject#2", "Box",
        "The same box at creation height 0xFFFFFFFF: the V1-era height -1. The JVM rejects it at parse like " +
        "0x80000000, so a V1 negative-height box can never reach the storage-rent gate's signed age " +
        "arithmetic on the reference node.",
        spliceUnique(boxBytes, MaxVlq, AllOnes), reject = true))

    // Transactions: one plain input (empty proof and extension) and one output.
    val spent = box("santa:chb:spent", 2000000000L, 100)
    def txOf(inExt: sigma.interpreter.ContextExtension, out: org.ergoplatform.ErgoBoxCandidate): Array[Byte] =
      txBytes(tx(Seq(input(spent, inExt)), Seq(out)))
    val outputAtMax  = txOf(ext(), candidate(spent.value, Int.MaxValue))
    val extBoxAtMax  = txOf(ext(0.toByte -> BoxConstant(CBox(boxAtMax))), candidate(spent.value, 100))
    val regBoxAtMax  = txOf(ext(), candidate(spent.value, 100, regs = Map(ErgoBox.R4 -> BoxConstant(CBox(boxAtMax)))))
    // spliceUnique fails loud unless the height VLQ occurs exactly once, so a splice cannot land elsewhere.

    val txEntries = Seq(
      entry("tx-output-creation-height-0x7fffffff-accept#0", "Transaction",
        "Transaction whose output candidate has creation height 0x7FFFFFFF: parses (stateful rules would " +
        "refuse it as created in the future, but this is the parse bound). Round-trip identity.",
        outputAtMax, reject = false),
      entry("tx-output-creation-height-0x80000000-reject#1", "Transaction",
        "The same transaction with the output's creation height VLQ set to 0x80000000: the JVM rejects the " +
        "whole tx at parse (getUIntExact). A u32 reader parses it; in a v2+ block such an output is also " +
        "refused later by the stateful negative-height rule, so this form pins the parse divergence only.",
        spliceUnique(outputAtMax, MaxVlq, MinNeg), reject = true),
      entry("tx-extension-box-constant-creation-height-0x7fffffff-accept#2", "Transaction",
        "Transaction whose input 0 extension binds variable 0 to a Box constant (SBox data) created at " +
        "0x7FFFFFFF. Round-trip identity.",
        extBoxAtMax, reject = false),
      entry("tx-extension-box-constant-creation-height-0x80000000-reject#3", "Transaction",
        "The same transaction with the embedded box's creation height set to 0x80000000. SBox data is parsed " +
        "with the box serializer, so the JVM rejects the whole tx at parse. Reachable at every block version: " +
        "no stateful rule looks at an embedded box's height, so an impl that parses it can accept the spend.",
        spliceUnique(extBoxAtMax, MaxVlq, MinNeg), reject = true),
      entry("tx-register-box-constant-creation-height-0x7fffffff-accept#4", "Transaction",
        "Transaction whose output R4 holds a Box constant created at 0x7FFFFFFF. Round-trip identity.",
        regBoxAtMax, reject = false),
      entry("tx-register-box-constant-creation-height-0x80000000-reject#5", "Transaction",
        "The same transaction with the R4 box's creation height set to 0x80000000: the JVM rejects the tx at " +
        "parse. Reachable like the extension form — nothing checks an embedded box's height after parse.",
        spliceUnique(regBoxAtMax, MaxVlq, MinNeg), reject = true))

    def envelope(op: String, entries: Seq[Json]): Json = Json.obj(
      "schema"     -> Json.fromString("santa-wire/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:sigma-state-6.0.6"),
      "entries"    -> Json.arr(entries: _*))
    Map(OpBox -> envelope(OpBox, boxEntries), OpTx -> envelope(OpTx, txEntries))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredWireCreationHeightBound", extract(), outDir)
}
