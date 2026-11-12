package santa

// Authored eval vectors for sigma-rust's probe santa-probe-receiver-kind-2 (2026-11-12): the eight
// numeric methods with a `userDefinedInvoke` handler — toBytes, toBits, bitwiseInverse, bitwiseOr,
// bitwiseAnd, bitwiseXor, shiftLeft, shiftRight (methods.scala:312-449 at v6.0.7, unchanged from
// 6.0.6) — called on a receiver of another numeric WIDTH. Measured in santa.spike.ReceiverKindProbe2.
//
// THE RULE, read and measured: each handler does `m.objType match` and casts the receiver to the
// OWNER's class (`case SLongMethods => …(obj.asInstanceOf[Long])`). The owner is the type code in the
// bytes, not the receiver's type, so a wrong-width receiver parses and throws `ClassCastException` at
// evaluation, naming the owner's class. The two-operand handlers cast the argument too
// (`other.head.asInstanceOf[Long]` for bitwiseOr/And/Xor; `[Int]` for the shifts), so a wrong-width
// ARGUMENT on a right-width receiver throws the same way (A1). The eval tier grades a refusal
// coarsely: the class and message are the JVM's, and not part of the vector.
//
// This is new coverage: the receiver-kind round's 199-method sweep gave every non-Boolean owner a
// Boolean receiver (ReceiverKindProbe.wrongReceiver), so no numeric owner met another numeric width.
// Each tree's hex is the sigma-rust session's own encoding, re-built here from its AST and checked
// equal in the anchoring test. Every tree ignores its input (Int 0 at var 1).

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalNumericReceiverWidth {
  val V3: Byte = VersionContext.V6SoftForkVersion
  val Op = "MethodCall.numeric_receiver_width"
  val Source = "santa:authored-eval-numeric-receiver-width"
  val Stamp = "jvm:sigma-state-6.0.7"

  private def vc[T](f: => T): T = VersionContext.withVersions(V3, V3)(f)
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** A size-flagged v3 tree, constants inline: the header, the body's size as a VLQ, the body. */
  private[santa] def tree(root: Value[SType]): String = vc {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
    val body = { val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(root, w); w.toBytes }
    val size = { val w = SigmaSerializer.startWriter(); w.putUInt(body.length.toLong); w.toBytes }
    "%02x".format(header: Byte) + Base16.encode(size) + Base16.encode(body)
  }
  private def call(typeId: Int, methodId: Int, recv: Value[SType], args: Value[SType]*): Value[SType] = vc {
    any(MethodCall(recv, SMethod.fromIds(typeId.toByte, methodId.toByte), args.toIndexedSeq, Map()))
  }
  private val L1 = any(LongConstant(1))
  private val I1 = any(IntConstant(1))

  /** (id, the call, sigma-rust's hex, the AST, the owner class the cast names; None for a control) */
  val Rows: Seq[(String, String, String, () => Value[SType], Option[String])] = Seq(
    ("N0", "SLong.toBytes on Long 1", "0b05db05060502", () => call(5, 6, L1), None),
    ("N1", "SLong.toBytes on Int 1", "0b05db05060402", () => call(5, 6, I1), Some("java.lang.Long")),
    ("N2", "SInt.toBytes on Long 1", "0b05db04060502", () => call(4, 6, L1), Some("java.lang.Integer")),
    ("N3", "SInt.toBits on Long 1", "0b05db04070502", () => call(4, 7, L1), Some("java.lang.Integer")),
    ("N4", "SInt.bitwiseInverse on Long 1", "0b05db04080502", () => call(4, 8, L1), Some("java.lang.Integer")),
    ("N5", "SLong.shiftLeft(1) on Long 1", "0b08dc050c0502010402", () => call(5, 12, L1, I1), None),
    ("N6", "SLong.shiftLeft(1) on Int 1", "0b08dc050c0402010402", () => call(5, 12, I1, I1), Some("java.lang.Long")),
    ("N7", "SLong.shiftRight(1) on Int 1", "0b08dc050d0402010402", () => call(5, 13, I1, I1), Some("java.lang.Long")),
    ("N8", "SBigInt.toBytes on Int 1", "0b05db06060402", () => call(6, 6, I1), Some("sigma.BigInt")),
    ("N9", "SByte.toBytes on Int 1", "0b05db02060402", () => call(2, 6, I1), Some("java.lang.Byte")),
    ("N10", "SLong.bitwiseOr(1) on Int 1", "0b08dc05090402010402", () => call(5, 9, I1, I1), Some("java.lang.Long")),
    ("A1", "SLong.bitwiseOr(Int 1) on Long 1", "0b08dc05090502010402", () => call(5, 9, L1, I1), Some("java.lang.Long")))

  def extract(): Map[String, Json] = {
    val entries = Rows.zipWithIndex.map { case ((id, what, _, ast, owner), i) =>
      val t = tree(ast())
      owner match {
        case None =>
          val (value, n) = if (id == "N0") ("the 8 big-endian bytes 00…01, at cost 14", "long-tobytes-on-long")
                           else ("Long 2, at cost 19", "long-shiftleft-on-long")
          SpecExtract.authoredEntryV(Op, s"[$id] `$what` — $value. CONTROL: the receiver is the owner's width.",
            t, s"$n-accept#$i", input, V3, ergoTree = 3)
        case Some(cls) =>
          val (side, cast) = if (id == "A1") ("the ARGUMENT", "`other.head.asInstanceOf[Long]`")
                             else ("the receiver", "the owner's `obj.asInstanceOf[…]`")
          val slug = what.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripSuffix("-")
          SpecExtract.authoredRejectEntryV(Op, s"[$id] `$what` — THROWS `ClassCastException` to $cls: the " +
            s"handler casts $side with $cast (methods.scala:312-449), and the owner is the type code in the " +
            "bytes, not the operand's type. SEPARATING: an evaluator that picks its implementation by the " +
            "VALUE's kind returns a value.", t, s"$slug-errored#$i", input, V3, ergoTree = 3)
      }
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source)
      .mapObject(_.add("blessed_by", Json.fromString(Stamp))))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalNumericReceiverWidth", extract(), outDir)
}
