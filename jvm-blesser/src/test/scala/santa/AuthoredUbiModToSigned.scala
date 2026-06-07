package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `UnsignedBigInt.mod` + `UnsignedBigInt.toSigned` vectors
// (v6/authored) — sigma-rust tx-cost request P9:18/P9:19.
//
// `mod` (methodId=18, JitCost=20): a.mod(b) → a % b (unsigned modulo, no
//   domain truncation — div-by-zero is a hard reject at eval time).
// `toSigned` (methodId=19, JitCost=10): converts UBI → BigInt;
//   requires the value to be < 2^255 (bitLength ≤ 255); values ≥ 2^255
//   throw at eval time (toSignedBigIntValueExact gate in the JVM oracle).
//
// Construction: closed MethodCall trees, V3-gated (SUnsignedBigIntMethods),
// serialized inside VersionContext.withVersions(3, 3) exactly as
// AuthoredUbiArith does. Entry input = ignored dummy Int at var 1.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ErgoTree, MethodCall, SBigInt, SType, SUnsignedBigInt,
  SUnsignedBigIntMethods, UnsignedBigIntConstant, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.data.CUnsignedBigInt

object AuthoredUbiModToSigned {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). Both methods are V3-gated. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source   = "santa:authored-ubi-mod-tosigned"
  val Op       = "UnsignedBigInt.mod_toSigned"
  val OpReject = "UnsignedBigInt.mod_toSigned domain rejects"

  // 2^255 — the boundary: toSigned accepts values < 2^255, rejects >= 2^255;
  // mod's div-by-zero rejects modulus == 0.
  private val pow255: java.math.BigInteger = java.math.BigInteger.ONE.shiftLeft(255)

  private def ubiv(b: java.math.BigInteger): Value[SUnsignedBigInt.type] =
    UnsignedBigIntConstant(CUnsignedBigInt(b))

  private def ubis(s: String): Value[SUnsignedBigInt.type] =
    UnsignedBigIntConstant(new java.math.BigInteger(s))

  /** Closed `a.mod(b)` tree — both operands constant; serialized at v6 via the
    * lenient (non-SigmaProp-root) encoder. Returns tree hex string. */
  private def modTree(a: java.math.BigInteger, b: java.math.BigInteger): String =
    VersionContext.withVersions(V3, V3) {
      val root: Value[SUnsignedBigInt.type] =
        MethodCall.typed[Value[SUnsignedBigInt.type]](
          ubiv(a),
          SUnsignedBigIntMethods.getMethodByName("mod"),
          IndexedSeq(ubiv(b)),
          Map.empty)
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  /** Closed `a.toSigned` tree — receiver constant; serialized at v6 via the
    * lenient (non-SigmaProp-root) encoder. Returns tree hex string. */
  private def toSignedTree(a: java.math.BigInteger): String =
    VersionContext.withVersions(V3, V3) {
      val root: Value[SBigInt.type] =
        MethodCall.typed[Value[SBigInt.type]](
          ubiv(a),
          SUnsignedBigIntMethods.getMethodByName("toSigned"),
          IndexedSeq.empty,
          Map.empty)
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
    }

  /** Ignored dummy input at var 1 — the trees are closed; authoredEntry requires one. */
  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    // ── Accepts ──────────────────────────────────────────────────────────────
    val acceptEntries: Seq[Json] = Seq(
      // mod#basic: 17 mod 5 = 2
      ("mod#basic",
       "{ (x: (UnsignedBigInt, UnsignedBigInt)) => x._1.mod(x._2) }",
       modTree(new java.math.BigInteger("17"), new java.math.BigInteger("5"))),
      // mod#wrap: 2^255 mod 7 — a >Long operand; exact result is 2^255 % 7
      ("mod#wrap",
       "{ (x: (UnsignedBigInt, UnsignedBigInt)) => x._1.mod(x._2) }",
       modTree(pow255, new java.math.BigInteger("7"))),
      // mod#m-gt-a: 5 mod 17 = 5 (modulus larger than dividend)
      ("mod#m-gt-a",
       "{ (x: (UnsignedBigInt, UnsignedBigInt)) => x._1.mod(x._2) }",
       modTree(new java.math.BigInteger("5"), new java.math.BigInteger("17"))),
      // toSigned#small: 17.toSigned = BigInt(17)
      ("toSigned#small",
       "{ (x: UnsignedBigInt) => x.toSigned }",
       toSignedTree(new java.math.BigInteger("17"))),
      // toSigned#max-ok: (2^255 - 1).toSigned — the largest convertible value
      ("toSigned#max-ok",
       "{ (x: UnsignedBigInt) => x.toSigned }",
       toSignedTree(pow255.subtract(java.math.BigInteger.ONE)))
    ).map { case (name, script, treeHex) =>
      SpecExtract.authoredEntry(Op, script, treeHex, name, dummyInput, V3)
    }

    // ── Rejects ──────────────────────────────────────────────────────────────
    val rejectEntries: Seq[Json] = Seq(
      // mod#div-by-zero: 17 mod 0 — eval-fail (unsigned division by zero)
      ("mod#div-by-zero",
       "{ (x: (UnsignedBigInt, UnsignedBigInt)) => x._1.mod(x._2) }",
       modTree(new java.math.BigInteger("17"), java.math.BigInteger.ZERO)),
      // toSigned#ge-2^255: 2^255 — bitLength == 256 > 255, toSignedBigIntValueExact rejects
      ("toSigned#ge-2^255",
       "{ (x: UnsignedBigInt) => x.toSigned }",
       toSignedTree(pow255))
    ).map { case (name, script, treeHex) =>
      SpecExtract.authoredRejectEntry(OpReject, script, treeHex, name, dummyInput, V3)
    }

    Map(
      Op       -> SpecExtract.authoredEnvelope(Op, acceptEntries, Source),
      OpReject -> SpecExtract.authoredEnvelope(OpReject, rejectEntries, Source))
  }

  /** Staging via the shared writer (SpecExtract.writeStaging). */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredUbiModToSigned", vectors, outDir)
}
