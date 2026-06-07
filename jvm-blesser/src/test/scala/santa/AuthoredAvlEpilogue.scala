package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored AvlTree epilogue vectors — ergots f4 epilogue asks 1 + 3.
//
//   Ask 1: valueLengthOpt wrapped-negative accessor. The valueLengthOpt twin of the
//     committed keyLength_wrapped_negative pin — same JVM parse line
//     (AvlTreeData.scala:84-85, getUInt().toInt wraps). DESERIALIZE-only (the
//     serializer require()s unsigned range), so the vector carries hand-patched
//     bytes: a sentinel valueLengthOpt = Some(0x70000001) (VLQ 81 80 80 80 07) whose
//     trailing VLQ byte is patched 07→08 → the field reads 0x80000001. `.valueLengthOpt`
//     accessor → Some(Int -2147483647), cost 20. (ergots fix: `valueLengthOpt | 0`.)
//
//   Ask 3: composite updateDigest(3-byte).contains(key, proof). updateDigest now
//     accepts any digest length; the resulting AvlTree carries the 3-byte digest. A
//     following Tier-2 contains then runs against that (short, non-matching) digest
//     → false, cost 262. (ergots unit-pins 170 assuming treeHeight=0 base-only — the
//     JVM charges 262, so this is a COST divergence, not just confirmation.)
//
// Both at {activated 2, ergoTree 2}; JVM-oracle-blessed (fail-loud on drift).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Lookup}
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

object AuthoredAvlEpilogue {

  val V2: Byte = VersionContext.JitActivationVersion // v5 (2,2)
  val Source   = "santa:authored-avl-epilogue"

  val OpValueLen  = "AvlTree.valueLengthOpt_wrapped_negative"
  val OpComposite = "AvlTree.updateDigest_then_contains"

  private def key(i: Int): Array[Byte] = Blake2b256(s"santa-avl-key-$i").toArray
  private def value(i: Int): Array[Byte] = {
    val b = java.nio.ByteBuffer.allocate(8); b.putLong(0x5A17A000L + i); b.array()
  }
  private def prover(n: Int): BatchAVLProver[Digest32, Blake2b256.type] = {
    val p = new BatchAVLProver[Digest32, Blake2b256.type](keyLength = 32, valueLengthOpt = Some(8))
    (0 until n).foreach(i => p.performOneOperation(Insert(ADKey @@ key(i), ADValue @@ value(i))).get)
    p.generateProof(); p
  }
  private lazy val (d8, pLookup2) = {
    val p = prover(8); val d = Base16.encode(p.digest)
    p.performOneOperation(Lookup(ADKey @@ key(2))).get
    (d, p.generateProof())
  }
  private def realDigest33 = Base16.decode(d8).get

  private def serializeClosed(root: Value[SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }
  private def mc(data: AvlTreeData, m: sigma.ast.SMethod, args: Value[SType]*): String =
    VersionContext.withVersions(V2, V2) {
      serializeClosed(MethodCall(AvlTreeConstant(CAvlTree(data)), m, args.toIndexedSeq, Map()))
    }

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  // Ask 1: hand-patched valueLengthOpt VLQ (sentinel 0x70000001 → 0x80000001)
  private val FromVlq = "8180808007"; private val ToVlq = "8180808008"
  private def valueLenNegHex: String = {
    val td = AvlTreeData(Colls.fromArray(realDigest33), AvlTreeFlags.AllOperationsAllowed,
      keyLength = 32, valueLengthOpt = Some(0x70000001))
    val clean = mc(td, SAvlTreeMethods.valueLengthOptMethod)
    val occ = FromVlq.r.findAllMatchIn(clean).size
    if (occ != 1)
      sys.error(s"AuthoredAvlEpilogue valueLen: expected 1 valueLengthOpt VLQ ('$FromVlq'), found $occ in $clean")
    clean.replace(FromVlq, ToVlq)
  }

  // Ask 3: updateDigest(3-byte) then contains on the result
  private def compositeHex: String = {
    val td = AvlTreeData(Colls.fromArray(realDigest33), AvlTreeFlags.AllOperationsAllowed, keyLength = 32, valueLengthOpt = Some(8))
    val three = ByteArrayConstant(Array[Byte](1, 2, 3))
    VersionContext.withVersions(V2, V2) {
      serializeClosed(MethodCall(
        MethodCall(AvlTreeConstant(CAvlTree(td)), SAvlTreeMethods.updateDigestMethod, IndexedSeq(three), Map()),
        SAvlTreeMethods.containsMethod, IndexedSeq(ByteArrayConstant(key(2)), ByteArrayConstant(pLookup2)), Map()))
    }
  }

  def extract(): Map[String, Json] = {
    val valueLen = Seq(
      SpecExtract.authoredEntry(OpValueLen,
        "{ avlConstant(valueLengthOpt=Some(0x80000001)).valueLengthOpt }  // parse wraps → Some(negative Int)",
        valueLenNegHex, "valueLengthOpt-wrapped-negative#0", dummyInput, V2))
    val composite = Seq(
      SpecExtract.authoredEntry(OpComposite,
        "{ tree.updateDigest(Coll[Byte](1,2,3)).contains(key2, proof) }  // 3-byte digest → Tier-2 cost",
        compositeHex, "updateDigest_3byte_then_contains#0", dummyInput, V2))
    Map(
      OpValueLen  -> SpecExtract.authoredEnvelope(OpValueLen, valueLen, Source),
      OpComposite -> SpecExtract.authoredEnvelope(OpComposite, composite, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredAvlEpilogue", extract(), outDir)
}
