package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored `GroupElement.expUnsigned` vectors (v6/authored) — ergots vector
// request P7a-1.
//
// `GroupElement.expUnsigned(k)` is the unsigned-exponent variant of group
// exponentiation (typeId=7, methodId=6), V3-gated
// (SGroupElementMethods.ExponentiateUnsignedMethod).
//
// LanguageSpecificationV6:2475-2492 blesses three cases over the generator:
//   g^1   → g (the generator itself)
//   g^0   → identity (the neutral element)
//   g^order → identity (group order collapses to identity)
//
// Construction: closed trees — `MethodCall(GroupElementConstant(gen), expUnsignedMethod,
// [UnsignedBigIntConstant(k)])` — all args constant, root type is SGroupElement →
// serialized via the lenient encoder. Entry input is an ignored dummy Int at var 1
// (authoredEntry/v2 schema requires one). V3 (activated=3, ergoTree=3).
// ─────────────────────────────────────────────────────────────────────────────

import java.math.BigInteger

import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{ErgoTree, GroupElementConstant, MethodCall, SGroupElement, SGroupElementMethods,
  SType, UnsignedBigIntConstant, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.crypto.CryptoConstants
import sigma.data.CUnsignedBigInt

object AuthoredExpUnsigned {

  /** Pinned target version: full v6 (activated=3, ergoTree=3). `expUnsigned` is V3-gated. */
  val V3: Byte = VersionContext.V6SoftForkVersion

  val Source = "santa:authored-expunsigned"
  val Op     = "GroupElement.expUnsigned"

  private val gen  = CryptoConstants.dlogGroup.generator
  /** secp256k1 group order — g^order = g^0 = identity. */
  private val order: BigInteger = CryptoConstants.dlogGroup.order

  /** Closed `gen.expUnsigned(k)` serialized at v6 via the lenient (non-SigmaProp-root) encoder.
    * Returns (script, treeHex). */
  private def tree(k: BigInteger): (String, String) =
    VersionContext.withVersions(V3, V3) {
      val ge: Value[SGroupElement.type]  = GroupElementConstant(gen)
      val exp: Value[SType]              = UnsignedBigIntConstant(CUnsignedBigInt(k))
      val root: Value[SGroupElement.type] =
        MethodCall.typed[Value[SGroupElement.type]](
          ge, SGroupElementMethods.ExponentiateUnsignedMethod,
          IndexedSeq(exp), Map())
      val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
      (s"""{ GroupGenerator.expUnsigned($k) }""",
       Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root)))
    }

  /** Ignored dummy input at var 1 — the trees are closed; authoredEntry requires one. */
  private def dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** op -> v2 envelope. Three entries: g^1, g^0, g^order. */
  def extract(): Map[String, Json] = {
    val cases: Seq[(String, BigInteger)] = Seq(
      ("exp-1",     BigInteger.ONE),
      ("exp-0",     BigInteger.ZERO),
      ("exp-order", order))
    val entries = cases.zipWithIndex.map { case ((name, k), i) =>
      val (script, treeHex) = tree(k)
      SpecExtract.authoredEntry(Op, script, treeHex, s"$name#$i", dummyInput, V3)
    }
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  /** Persist authored vectors to a staging dir (build artifact — copied into
    * vectors/eval/v6/authored/ once inspected). */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredExpUnsigned.writeVectors: slug collision — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
