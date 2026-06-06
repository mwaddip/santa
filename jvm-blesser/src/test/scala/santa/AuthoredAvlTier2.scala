package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored AvlTree Tier-2 (proof-carrying) vectors — v5/authored.
// Convergent request: sigma-rust (tx-cost decomposition follow-up: proof
// lengths vs 64-B cost chunks, h>1 trees) + ergots (zero Tier-2 coverage;
// TDD corpus). Spec: docs/superpowers/specs/2026-06-06-avl-ubi-eq-vector-batch-design.md
//
// Closed trees (powHit pattern): MethodCall(AvlTreeConstant(CAvlTree(data)),
// SAvlTreeMethods.<m>, constant args, Map()) at v5 (2,2); dummy Int var-1 input.
// Proofs from a deterministic BatchAVLProver (key(i)=Blake2b256("santa-avl-key-$i"),
// value(i)=8-byte BE 0x5A17A000+i) — byte-stable across re-bless (spike S1).
// Measured semantics pinned here (spike S3/S4): get present→Some / absent→None
// (same cost); tampered or wrong-key proof→errored (reject arm); flag-gated
// modify→None BEFORE verifier creation (tiny cost); valid modify→Some(newTree).
// Cost has per-proof-chunk AND tree-height components (S5) — the ladder spans
// both; getMany varies proof length at FIXED height (the chunk instrument).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Lookup, Remove, Update}
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{AvlTreeConstant, ByteArrayConstant, ByteConstant, ConcreteCollection, ErgoTree,
  MethodCall, SAvlTreeMethods, SByte, SCollection, SPair, SType, Tuple, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.data.{AvlTreeData, AvlTreeFlags, CAvlTree}
import sigma.Colls

object AuthoredAvlTier2 {

  val V2: Byte = VersionContext.JitActivationVersion // v5 pin (2,2)
  val Source = "santa:authored-avl-tier2"

  val OpContains    = "AvlTree.contains"
  val OpGet         = "AvlTree.get"
  val OpGetMany     = "AvlTree.getMany"
  val OpInsert      = "AvlTree.insert"
  val OpUpdate      = "AvlTree.update"
  val OpRemove      = "AvlTree.remove"
  val OpFlagsDigest = "AvlTree.updateOperations updateDigest"
  val OpLadder      = "AvlTree.get proof ladder"
  val OpAdversarial = "AvlTree.proof adversarial"

  // ── deterministic prover material (spike S1: byte-stable) ──────────────────
  private def key(i: Int): Array[Byte] = Blake2b256(s"santa-avl-key-$i").toArray
  private def value(i: Int): Array[Byte] = {
    val b = java.nio.ByteBuffer.allocate(8); b.putLong(0x5A17A000L + i); b.array()
  }
  /** Committed prover over n entries (one generateProof() commit). */
  private def prover(n: Int): BatchAVLProver[Digest32, Blake2b256.type] = {
    val p = new BatchAVLProver[Digest32, Blake2b256.type](keyLength = 32, valueLengthOpt = Some(8))
    (0 until n).foreach(i => p.performOneOperation(Insert(ADKey @@ key(i), ADValue @@ value(i))).get)
    p.generateProof()
    p
  }
  /** (digestHex of the committed n-tree, proof for the given ops on it). */
  private def proofFor(n: Int)(ops: BatchAVLProver[Digest32, Blake2b256.type] => Unit): (String, Array[Byte]) = {
    val p = prover(n)
    val digest = Base16.encode(p.digest)
    ops(p)
    (digest, p.generateProof())
  }
  private def lookupProof(n: Int, i: Int): (String, Array[Byte]) =
    proofFor(n)(p => p.performOneOperation(Lookup(ADKey @@ key(i))).get)

  private def treeData(digestHex: String, flags: AvlTreeFlags = AvlTreeFlags.AllOperationsAllowed): AvlTreeData =
    AvlTreeData(Colls.fromArray(Base16.decode(digestHex).get), flags, keyLength = 32, valueLengthOpt = Some(8))

  // ── AST builders (closed trees) ─────────────────────────────────────────────
  private val ByteColl = SCollection(SByte)

  private def serializeClosed(root: Value[SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V2))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }
  private def mc(data: AvlTreeData, m: sigma.ast.SMethod, args: Value[SType]*): String =
    VersionContext.withVersions(V2, V2) {
      serializeClosed(MethodCall(AvlTreeConstant(CAvlTree(data)), m, args.toIndexedSeq, Map()))
    }
  private def bytesColl(items: Array[Byte]*): Value[SType] =
    ConcreteCollection(items.toArray.map(b => ByteArrayConstant(b): Value[SType]), ByteColl)
  private def kvColl(pairs: (Array[Byte], Array[Byte])*): Value[SType] =
    ConcreteCollection(
      pairs.toArray.map { case (k, v) => Tuple(Vector(ByteArrayConstant(k), ByteArrayConstant(v))): Value[SType] },
      SPair(ByteColl, ByteColl))

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def entry(op: String, script: String, hex: String, name: String): Json =
    SpecExtract.authoredEntry(op, script, hex, name, dummyInput, V2)
  private def reject(op: String, script: String, hex: String, name: String): Json =
    SpecExtract.authoredRejectEntry(op, script, hex, name, dummyInput, V2)

  // ── getMany tuning (test-asserted: series must cross a 64-B chunk boundary) ─
  /** k lookups in ONE proof at fixed n=16. Exposed for the chunk-crossing test. */
  private val getManyKs = Seq(1, 2, 3)
  private lazy val getManyProofs: Seq[(Int, (String, Array[Byte]))] = getManyKs.map { k =>
    k -> proofFor(16)(p => (0 until k).foreach(i => p.performOneOperation(Lookup(ADKey @@ key(i))).get))
  }
  def getManyProofLengths: Seq[Int] = getManyProofs.map(_._2._2.length)

  // ── families ────────────────────────────────────────────────────────────────
  def extract(): Map[String, Json] = {
    // contains / get @ n=8: present (key 2) + absent (key 99, valid non-inclusion proof)
    val (d8, pPresent) = lookupProof(8, 2)
    val (_, pAbsent)   = proofFor(8)(p => p.performOneOperation(Lookup(ADKey @@ key(99))).get)
    val contains = Seq(
      entry(OpContains, "{ tree(n=8).contains(key2, proof) }",  mc(treeData(d8), SAvlTreeMethods.containsMethod, ByteArrayConstant(key(2)), ByteArrayConstant(pPresent)),  "present#0"),
      entry(OpContains, "{ tree(n=8).contains(key99, proof) }", mc(treeData(d8), SAvlTreeMethods.containsMethod, ByteArrayConstant(key(99)), ByteArrayConstant(pAbsent)), "absent#1"))
    val get = Seq(
      entry(OpGet, "{ tree(n=8).get(key2, proof) }",  mc(treeData(d8), SAvlTreeMethods.getMethod, ByteArrayConstant(key(2)),  ByteArrayConstant(pPresent)), "present#0"),
      entry(OpGet, "{ tree(n=8).get(key99, proof) }", mc(treeData(d8), SAvlTreeMethods.getMethod, ByteArrayConstant(key(99)), ByteArrayConstant(pAbsent)),  "absent#1"))

    // getMany @ n=16, k=1/2/3 — fixed height, proof length varies (chunk instrument)
    val getMany = getManyProofs.zipWithIndex.map { case ((k, (d16, proof)), i) =>
      val keys = bytesColl((0 until k).map(key): _*)
      entry(OpGetMany, s"{ tree(n=16).getMany([key0..key${k - 1}], proof) }",
        mc(treeData(d16), SAvlTreeMethods.getManyMethod, keys, ByteArrayConstant(proof)), s"k=$k#$i")
    }

    // insert / update / remove @ n=8: valid → Some(newTree); flag-gated → None
    val (dIns, pIns) = proofFor(8)(p => p.performOneOperation(Insert(ADKey @@ key(50), ADValue @@ value(50))).get)
    val insert = Seq(
      entry(OpInsert, "{ tree(n=8).insert([(key50,val50)], proof) }",
        mc(treeData(dIns), SAvlTreeMethods.insertMethod, kvColl(key(50) -> value(50)), ByteArrayConstant(pIns)), "valid#0"),
      entry(OpInsert, "{ readOnlyTree(n=8).insert([(key50,val50)], proof) }",
        mc(treeData(dIns, AvlTreeFlags.ReadOnly), SAvlTreeMethods.insertMethod, kvColl(key(50) -> value(50)), ByteArrayConstant(pIns)), "readonly-none#1"))

    val (dUpd, pUpd) = proofFor(8)(p => p.performOneOperation(Update(ADKey @@ key(3), ADValue @@ value(77))).get)
    val noUpdate = AvlTreeFlags(insertAllowed = true, updateAllowed = false, removeAllowed = true)
    val update = Seq(
      entry(OpUpdate, "{ tree(n=8).update([(key3,val77)], proof) }",
        mc(treeData(dUpd), SAvlTreeMethods.updateMethod, kvColl(key(3) -> value(77)), ByteArrayConstant(pUpd)), "valid#0"),
      entry(OpUpdate, "{ noUpdateTree(n=8).update([(key3,val77)], proof) }",
        mc(treeData(dUpd, noUpdate), SAvlTreeMethods.updateMethod, kvColl(key(3) -> value(77)), ByteArrayConstant(pUpd)), "disallowed-none#1"))

    val (dRem, pRem) = proofFor(8)(p => p.performOneOperation(Remove(ADKey @@ key(4))).get)
    val noRemove = AvlTreeFlags(insertAllowed = true, updateAllowed = true, removeAllowed = false)
    val remove = Seq(
      entry(OpRemove, "{ tree(n=8).remove([key4], proof) }",
        mc(treeData(dRem), SAvlTreeMethods.removeMethod, bytesColl(key(4)), ByteArrayConstant(pRem)), "valid#0"),
      entry(OpRemove, "{ noRemoveTree(n=8).remove([key4], proof) }",
        mc(treeData(dRem, noRemove), SAvlTreeMethods.removeMethod, bytesColl(key(4)), ByteArrayConstant(pRem)), "disallowed-none#1"))

    // Tier-1.5: updateOperations(Byte) / updateDigest(Coll[Byte]) → AvlTree (no proof)
    val altDigest: Array[Byte] = Array.fill(AvlTreeData.DigestSize)(7.toByte)
    val flagsDigest = Seq(
      entry(OpFlagsDigest, "{ tree(n=8).updateOperations(0) }",
        mc(treeData(d8), SAvlTreeMethods.updateOperationsMethod, ByteConstant(0.toByte)), "updateOperations-readonly#0"),
      entry(OpFlagsDigest, "{ tree(n=8).updateDigest(altDigest33) }",
        mc(treeData(d8), SAvlTreeMethods.updateDigestMethod, ByteArrayConstant(altDigest)), "updateDigest#1"))

    // ladder: get(present key0) over n — spans heights AND proof-chunk boundaries (spike S5)
    val ladder = Seq(1, 2, 4, 8, 16, 32, 64, 128, 256).zipWithIndex.map { case (n, i) =>
      val (d, p) = lookupProof(n, 0)
      entry(OpLadder, s"{ tree(n=$n).get(key0, proof) }",
        mc(treeData(d), SAvlTreeMethods.getMethod, ByteArrayConstant(key(0)), ByteArrayConstant(p)), s"n=$n#$i")
    }

    // adversarial rejects (spike S4: both ERRORED)
    val tampered = pPresent.clone(); tampered(tampered.length / 2) = (tampered(tampered.length / 2) ^ 0x55).toByte
    val adversarial = Seq(
      reject(OpAdversarial, "{ tree(n=8).get(key2, tamperedProof) }",
        mc(treeData(d8), SAvlTreeMethods.getMethod, ByteArrayConstant(key(2)), ByteArrayConstant(tampered)), "tampered-proof#0"),
      reject(OpAdversarial, "{ tree(n=8).get(key3, proofForKey2) }",
        mc(treeData(d8), SAvlTreeMethods.getMethod, ByteArrayConstant(key(3)), ByteArrayConstant(pPresent)), "wrong-key-for-proof#1"))

    Map(
      OpContains    -> SpecExtract.authoredEnvelope(OpContains, contains, Source),
      OpGet         -> SpecExtract.authoredEnvelope(OpGet, get, Source),
      OpGetMany     -> SpecExtract.authoredEnvelope(OpGetMany, getMany, Source),
      OpInsert      -> SpecExtract.authoredEnvelope(OpInsert, insert, Source),
      OpUpdate      -> SpecExtract.authoredEnvelope(OpUpdate, update, Source),
      OpRemove      -> SpecExtract.authoredEnvelope(OpRemove, remove, Source),
      OpFlagsDigest -> SpecExtract.authoredEnvelope(OpFlagsDigest, flagsDigest, Source),
      OpLadder      -> SpecExtract.authoredEnvelope(OpLadder, ladder, Source),
      OpAdversarial -> SpecExtract.authoredEnvelope(OpAdversarial, adversarial, Source))
  }

  /** Same staging writer as AuthoredPowHit (slug-collision-guarded). */
  def writeVectors(vectors: Map[String, Json], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.keys.groupBy(SpecExtract.slug).filter(_._2.size > 1)
    if (collisions.nonEmpty)
      sys.error("AuthoredAvlTier2.writeVectors: slug collision — " +
        collisions.map { case (stem, ops) => s"'$stem.json' ← ${ops.mkString(" / ")}" }.mkString("; "))
    vectors.foreach { case (op, json) =>
      val path = outDir.resolve(s"${SpecExtract.slug(op)}.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
