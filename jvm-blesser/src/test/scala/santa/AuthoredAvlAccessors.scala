package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored AvlTree Tier-1 accessor edge vectors — ergots f4-santa-asks.md Ask 5
// tail (A / C). These pin two ergots forks the JVM source contradicts:
//
//   A. updateDigest accepts ANY digest length (CAvlTree.scala:31-34 — no require).
//      ergots throws 'avl-tree-bad-digest-length' on non-33-byte → OVER-REJECT.
//      Pins: updateDigest(k-byte) → AvlTree with a k-byte digest, for k ∈ {0,3,40};
//      .digest reads those exact bytes back.
//
//   C. negative keyLength is a DESERIALIZE-only asymmetry. The serializer require()s
//      unsigned-int range, so an AvlTreeConstant with keyLength<0 CANNOT be emitted —
//      it only arises when AvlTreeData.parse:84 (getUInt().toInt) wraps a wire value
//      in [2^31,2^32). The :86-89 comment confirms the parser "succeeds with invalid
//      AvlTreeData". So the vector carries HAND-PATCHED bytes: a sentinel tree with
//      keyLength=0x70000001 (VLQ 81 80 80 80 07) whose trailing VLQ byte is patched
//      07→08, making the field read 0x80000001 → keyLength = -2147483647 on parse.
//      .keyLength accessor → Int -2147483647. ergots stores >>> 0 = 2147483649.
//
// All outcomes blessed by the JVM oracle (authoredEntry). Costs locked in the test
// from the spike observation; drift ⇒ INVESTIGATE.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert}
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{AvlTreeConstant, ByteArrayConstant, ErgoTree, MethodCall, SAvlTreeMethods,
  SType, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.data.{AvlTreeData, AvlTreeFlags, CAvlTree}
import sigma.Colls

object AuthoredAvlAccessors {

  val V2: Byte = VersionContext.JitActivationVersion // v5 pin (2,2)
  val Source   = "santa:authored-avl-accessors"

  val OpUpdateDigest = "AvlTree.updateDigest_any_length"
  val OpKeyLengthNeg = "AvlTree.keyLength_wrapped_negative"

  // ── deterministic prover material (identical to AuthoredAvlTier2) ──────────
  private def key(i: Int): Array[Byte] = Blake2b256(s"santa-avl-key-$i").toArray
  private def value(i: Int): Array[Byte] = {
    val b = java.nio.ByteBuffer.allocate(8); b.putLong(0x5A17A000L + i); b.array()
  }
  private def prover(n: Int): BatchAVLProver[Digest32, Blake2b256.type] = {
    val p = new BatchAVLProver[Digest32, Blake2b256.type](keyLength = 32, valueLengthOpt = Some(8))
    (0 until n).foreach(i => p.performOneOperation(Insert(ADKey @@ key(i), ADValue @@ value(i))).get)
    p.generateProof()
    p
  }
  private lazy val d8 = Base16.encode(prover(8).digest)

  private def treeData(digestHex: String, keyLength: Int = 32): AvlTreeData =
    AvlTreeData(Colls.fromArray(Base16.decode(digestHex).get), AvlTreeFlags.AllOperationsAllowed,
      keyLength = keyLength, valueLengthOpt = Some(8))

  private def serializeClosed(root: Value[SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }
  private def mc(data: AvlTreeData, m: sigma.ast.SMethod, args: Value[SType]*): String =
    VersionContext.withVersions(V2, V2) {
      serializeClosed(MethodCall(AvlTreeConstant(CAvlTree(data)), m, args.toIndexedSeq, Map()))
    }

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def entry(op: String, script: String, hex: String, name: String): Json =
    SpecExtract.authoredEntry(op, script, hex, name, dummyInput, V2)

  // ── A: updateDigest accepts any length ─────────────────────────────────────
  private def updateDigestHex(digest: Array[Byte]): String =
    mc(treeData(d8), SAvlTreeMethods.updateDigestMethod, ByteArrayConstant(digest))
  private def updateDigestThenReadHex(digest: Array[Byte]): String =
    VersionContext.withVersions(V2, V2) {
      serializeClosed(MethodCall(
        MethodCall(AvlTreeConstant(CAvlTree(treeData(d8))), SAvlTreeMethods.updateDigestMethod,
          IndexedSeq(ByteArrayConstant(digest)), Map()),
        SAvlTreeMethods.digestMethod, IndexedSeq(), Map()))
    }

  // ── C: hand-patched bytes carrying keyLength field 0x80000001 ───────────────
  // sentinel keyLength 0x70000001 (legal Int, VLQ 8180808007); 0x80000001 differs
  // only in the trailing VLQ byte (07→08) → 1-char-pair patch, exactly one match.
  private val SentinelKeyLen = 0x70000001
  private val FromVlq = "8180808007"
  private val ToVlq   = "8180808008"
  private def negKeyLengthHex: String = {
    val clean = mc(treeData(d8, keyLength = SentinelKeyLen), SAvlTreeMethods.keyLengthMethod)
    val occ = FromVlq.r.findAllMatchIn(clean).size
    if (occ != 1)
      sys.error(s"AuthoredAvlAccessors negKeyLength: expected 1 keyLength VLQ ('$FromVlq'), found $occ in $clean")
    clean.replace(FromVlq, ToVlq)
  }

  def extract(): Map[String, Json] = {
    val threeBytes = Array[Byte](1, 2, 3)
    val emptyBytes = Array.emptyByteArray
    val fortyBytes = Array.tabulate[Byte](40)(i => (i + 1).toByte)

    val updateDigest = Seq(
      entry(OpUpdateDigest, "{ tree.updateDigest(Coll[Byte](1,2,3)) }  // 3-byte digest accepted",
        updateDigestHex(threeBytes), "updateDigest-3byte#0"),
      entry(OpUpdateDigest, "{ tree.updateDigest(Coll[Byte](1,2,3)).digest }  // reads the 3 bytes back",
        updateDigestThenReadHex(threeBytes), "updateDigest-3byte-readback#1"),
      entry(OpUpdateDigest, "{ tree.updateDigest(Coll[Byte]()) }  // empty digest accepted",
        updateDigestHex(emptyBytes), "updateDigest-empty#2"),
      entry(OpUpdateDigest, "{ tree.updateDigest(40-byte) }  // over-length digest accepted",
        updateDigestHex(fortyBytes), "updateDigest-40byte#3"))

    val keyLengthNeg = Seq(
      entry(OpKeyLengthNeg, "{ avlConstant(keyLength=0x80000001).keyLength }  // parse wraps → negative Int",
        negKeyLengthHex, "keyLength-wrapped-negative#0"))

    Map(
      OpUpdateDigest -> SpecExtract.authoredEnvelope(OpUpdateDigest, updateDigest, Source),
      OpKeyLengthNeg -> SpecExtract.authoredEnvelope(OpKeyLengthNeg, keyLengthNeg, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredAvlAccessors", extract(), outDir)
}
