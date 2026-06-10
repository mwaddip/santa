package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored box bytes-basis witnesses (sigma-rust Ask 2, the id-basis follow-up).
// The JVM is ASYMMETRIC across the two byte accessors — spike-proven, and exactly why
// this pin had to land before any fix:
//
//   ExtractBytes (`box.bytes`)            → the parse-RETAINED slice (ErgoBox.scala:
//     214-225 keeps the consumed bytes; a non-canonical-but-accepted GE register
//     encoding SURVIVES — blake2b256(box.bytes) == box.id basis).
//   ExtractBytesWithNoRef (`bytesWithoutRef`) → a CANONICAL RE-SERIALIZATION of the
//     candidate (ErgoBoxCandidate serializer — no retained candidate slice exists), so
//     the garbage encoding is NORMALIZED AWAY and the two twins' bytesWithoutRef are
//     byte-IDENTICAL while their .bytes (and ids) differ.
//
// A "symmetric" fix serving the retained slice from BOTH accessors diverges on the
// second — the conformer must match the asymmetry, not the intuition. ExtractId twins
// pin the direct id values (blake2b256 over the retained slice — distinct for the
// twins). Same box twins + tree-hex splice mechanism as Box.eq_id_basis (the spliced
// register travels in committed tree bytes). v5 surface, {activated 2, ergoTree 0};
// spike-confirmed identical at activated 3; all six arms cost 13.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import scorex.util.encode.Base16
import scorex.util.bytesToId
import sigma.VersionContext
import sigma.ast.{BoolToSigmaProp, BoxConstant, ErgoTree, ExtractBytes, ExtractBytesWithNoRef,
  ExtractId, GroupElementConstant, SBox, SType, TrueLeaf, Value}
import sigma.ast.ErgoTree.ZeroHeader
import sigma.crypto.CryptoConstants
import sigma.data.CBox
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}
import org.ergoplatform.ErgoBox

object AuthoredBoxBytesBasis {

  val Activated: Byte = 2
  val ErgoTreeV0: Int = 0
  val Source = "santa:authored-box-bytes-basis"

  val Op = "Box.bytes_byte_basis"

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private val identityPt   = GroupElementSerializer.parse(SigmaSerializer.startReader(Array.fill[Byte](33)(0)))
  private val generatorPt  = CryptoConstants.dlogGroup.generator
  private val generatorHex = Base16.encode(GroupElementSerializer.toBytes(generatorPt))
  private val garbageIdentityHex = "00" + "aa" * 32

  private def splice(hex: String, from: String, to: String): String = {
    val first = hex.indexOf(from)
    if (first < 0) sys.error(s"splice source not found: ${from.take(16)}…")
    if (hex.indexOf(from, first + 1) >= 0) sys.error(s"splice source ambiguous: ${from.take(16)}…")
    hex.replace(from, to)
  }

  private def hexAtV0(root: Value[SType]): String =
    VersionContext.withVersions(0.toByte, 0.toByte) {
      Base16.encode(sigma.santa.LenientErgoTree.serialize(ZeroHeader, root))
    }

  private val trueTree = ErgoTree.fromProposition(BoolToSigmaProp(TrueLeaf))
  private def mkBox(r4Point: sigma.crypto.EcPointType) = new ErgoBox(
    value = 1000000L,
    ergoTree = trueTree,
    additionalTokens = sigma.Colls.emptyColl,
    additionalRegisters = Map(ErgoBox.R4 -> GroupElementConstant(r4Point)),
    transactionId = bytesToId(Array.fill[Byte](32)(0x11)),
    index = 0.toShort,
    creationHeight = 0)
  private def b1 = mkBox(identityPt)
  private def b2 = mkBox(generatorPt) // the in-tree splice target

  private def asBox(v: Value[SType]) = v.asInstanceOf[Value[SBox.type]]
  private def garbage(tree: Value[SType]): String =
    splice(hexAtV0(tree), generatorHex, garbageIdentityHex)

  def extract(): Map[String, Json] = {
    val entries = Seq(
      SpecExtract.authoredEntryV(Op,
        "{ <garbage-R4 box>.bytes }  // the parse-RETAINED slice — the 0x00‖aa×32 register encoding SURVIVES (80 bytes; blake2b256(bytes) == id basis)",
        garbage(ExtractBytes(asBox(BoxConstant(CBox(b2))))), "bytes-garbage-retained#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <canonical box>.bytes }  // control: the canonical twin's retained slice",
        hexAtV0(ExtractBytes(asBox(BoxConstant(CBox(b1))))), "bytes-canonical-control#1", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <garbage-R4 box>.bytesWithoutRef }  // CANONICAL re-serialization — the garbage is NORMALIZED AWAY (47 bytes, identical to the canonical twin's): the accessors are ASYMMETRIC",
        garbage(ExtractBytesWithNoRef(asBox(BoxConstant(CBox(b2))))), "bytesnoref-garbage-canonical#2", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <canonical box>.bytesWithoutRef }  // control — byte-identical to #2 (the twins converge on this accessor)",
        hexAtV0(ExtractBytesWithNoRef(asBox(BoxConstant(CBox(b1))))), "bytesnoref-canonical-control#3", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <garbage-R4 box>.id }  // blake2b256 over the RETAINED slice — distinct from the canonical twin's id",
        garbage(ExtractId(asBox(BoxConstant(CBox(b2))))), "id-garbage#4", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredEntryV(Op,
        "{ <canonical box>.id }  // the canonical twin's id",
        hexAtV0(ExtractId(asBox(BoxConstant(CBox(b1))))), "id-canonical#5", dummyInput, Activated, ErgoTreeV0))
    Map(Op -> SpecExtract.authoredEnvelope(Op, entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredBoxBytesBasis", extract(), outDir)
}
