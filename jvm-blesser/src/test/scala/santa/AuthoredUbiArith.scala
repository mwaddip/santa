package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored UnsignedBigInt PLAIN-arith table (v6/authored) -- sigma-rust's
// tx-cost-decomposition request ("UBI arith table incl. non-Division
// siblings"). The v6 spec corpus covers only the modular/bitwise/conversion
// UBI surface (probe: UnsignedBigInt_methods.json) -- plain + - * / % on UBI
// is unpinned anywhere until this file. Closed ArithOp trees, v6 (3,3).
// Rejects pin the unsigned domain edges: overflow past 2^256-1, underflow
// below 0, divide/mod by zero -- all eval-fail (authoredRejectEntry asserts).
//
// HEADLINE FINDING: plain UBI arith (+ - * / %) is flat JIT cost 17 per op,
// operand-size-independent up to 2^256-1 — empirically blessed (the test
// anchors pin it); ArithOp carries a type-based cost kind, not operand-sized.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ArithOp, ErgoTree, SType, UnsignedBigIntConstant, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.serialization.OpCodes
import sigma.serialization.ValueCodes.OpCode

object AuthoredUbiArith {

  val V3: Byte = VersionContext.V6SoftForkVersion // (3,3)
  val Source   = "santa:authored-ubi-arith"
  val OpTable  = "UnsignedBigInt arith table"
  val OpReject = "UnsignedBigInt arith domain rejects"

  private val Max: java.math.BigInteger = // 2^256 - 1
    java.math.BigInteger.ONE.shiftLeft(256).subtract(java.math.BigInteger.ONE)

  private def ubi(s: String): Value[SType] =
    UnsignedBigIntConstant(new java.math.BigInteger(s))

  private def ubiB(b: java.math.BigInteger): Value[SType] =
    UnsignedBigIntConstant(b)

  private def tree(l: Value[SType], r: Value[SType], op: OpCode): String =
    VersionContext.withVersions(V3, V3) {
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      Base16.encode(sigma.santa.LenientErgoTree.serialize(header, ArithOp[SType](l, r, op)))
    }

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    import OpCodes._
    val accepts = Seq(
      ("plus-small#0",     ubi("3"),  ubi("5"),  PlusCode,     "{ 3.toUBI + 5.toUBI }"),
      ("plus-to-max#1",    ubiB(Max.subtract(java.math.BigInteger.ONE)), ubi("1"), PlusCode, "{ (2^256-2).toUBI + 1.toUBI }"),
      ("minus-small#2",    ubi("5"),  ubi("3"),  MinusCode,    "{ 5.toUBI - 3.toUBI }"),
      ("minus-to-zero#3",  ubi("7"),  ubi("7"),  MinusCode,    "{ 7.toUBI - 7.toUBI }"),
      ("multiply-small#4", ubi("3"),  ubi("5"),  MultiplyCode, "{ 3.toUBI * 5.toUBI }"),
      ("multiply-big#5",   ubiB(java.math.BigInteger.ONE.shiftLeft(128)),
                           ubiB(java.math.BigInteger.ONE.shiftLeft(127)),
                           MultiplyCode,                        "{ 2^128.toUBI * 2^127.toUBI }"),
      ("divide-floor#6",   ubi("7"),  ubi("2"),  DivisionCode, "{ 7.toUBI / 2.toUBI }"),
      ("mod-small#7",      ubi("7"),  ubi("5"),  ModuloCode,   "{ 7.toUBI % 5.toUBI }")
    ).map { case (name, l, r, op, script) =>
      SpecExtract.authoredEntry(OpTable, script, tree(l, r, op), name, dummyInput, V3)
    }

    val rejects = Seq(
      ("overflow-plus#0",      ubiB(Max),  ubi("1"),  PlusCode,     "{ MAX.toUBI + 1.toUBI }"),
      ("underflow-minus#1",    ubi("3"),   ubi("5"),  MinusCode,    "{ 3.toUBI - 5.toUBI }"),
      ("overflow-multiply#2",  ubiB(Max),  ubi("2"),  MultiplyCode, "{ MAX.toUBI * 2.toUBI }"),
      ("divide-by-zero#3",     ubi("7"),   ubi("0"),  DivisionCode, "{ 7.toUBI / 0.toUBI }"),
      ("mod-by-zero#4",        ubi("7"),   ubi("0"),  ModuloCode,   "{ 7.toUBI % 0.toUBI }")
    ).map { case (name, l, r, op, script) =>
      SpecExtract.authoredRejectEntry(OpReject, script, tree(l, r, op), name, dummyInput, V3)
    }

    Map(
      OpTable  -> SpecExtract.authoredEnvelope(OpTable, accepts, Source),
      OpReject -> SpecExtract.authoredEnvelope(OpReject, rejects, Source))
  }

  /** Staging via the shared writer (SpecExtract.writeStaging). */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredUbiArith", vectors, outDir)
}
