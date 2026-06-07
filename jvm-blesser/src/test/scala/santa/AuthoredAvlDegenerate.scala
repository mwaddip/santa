package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored AvlTree degenerate-edge vectors — ergots f4-santa-asks.md Ask 1 + Ask 2
// + sigma-rust's three twin candidates. All on VALID-construction trees (n=8,
// keyLength=32, valueLengthOpt=Some(8)); the prover material matches AuthoredAvlTier2.
//
//   Ask 1 — bad PROOF BYTES (construction fails): contains → false (no throw, the
//     no-throw contract); get raises; insert raises@v5 / None@v6 (#908). Distinct
//     cost from wrong-tree (bad bytes fail construction earlier: contains 217 vs 257).
//   Ask 2 — empty ops + a VALID starting-digest proof → Some(starting tree,
//     unchanged digest); updateDigest(40) charged. Distinguishes valid-empty from
//     bad-proof-None.
//   Twins (sigma-rust, 2026-06-07):
//     T1 remove valid-proof + mismatched op (key∉proven set) → None (cfor ignores
//        op results; poisoned verifier → None) — pins their second find at corpus level.
//     T2 getMany over empty keys → empty Coll.
//     T3 insert/update empty entries + wrong-tree proof → None at EVERY version
//        (insert None even @v5 — the forall-over-empty never reaches the raise).
//
// authoredEntry / authoredRejectEntry; costs locked in the test from the spike.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Lookup, Remove}
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{AvlTreeConstant, ByteArrayConstant, ConcreteCollection, ErgoTree,
  MethodCall, SAvlTreeMethods, SByte, SCollection, SMethod, SPair, SType, Tuple, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.data.{AvlTreeData, AvlTreeFlags, CAvlTree}
import sigma.Colls

object AuthoredAvlDegenerate {

  val V2: Byte = VersionContext.JitActivationVersion // v5 (2,2)
  val V3: Byte = VersionContext.V6SoftForkVersion    // v6 (3,3)
  val Source   = "santa:authored-avl-degenerate"

  val OpBadBytesV5  = "AvlTree.bad_proof_bytes"
  val OpBadBytesV6  = "AvlTree.bad_proof_bytes_v6"
  val OpEmptyOpsV5  = "AvlTree.empty_ops_valid_proof"
  val OpEmptyOpsV6  = "AvlTree.empty_ops_valid_proof_v6"
  val OpEdgesV5     = "AvlTree.degenerate_edges"
  val OpEdgesV6     = "AvlTree.degenerate_edges_v6"

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
  private def proofFor(n: Int)(ops: BatchAVLProver[Digest32, Blake2b256.type] => Unit): (String, Array[Byte]) = {
    val p = prover(n)
    val digest = Base16.encode(p.digest)
    ops(p)
    (digest, p.generateProof())
  }

  // shared proofs
  private lazy val (d8, pLookup2) = proofFor(8)(p => p.performOneOperation(Lookup(ADKey @@ key(2))).get)
  private lazy val (dStart, pEmpty) = proofFor(8)(_ => ())                 // empty batch (Ask 2): starting digest preamble
  private lazy val (_, pRem4) = proofFor(8)(p => p.performOneOperation(Remove(ADKey @@ key(4))).get)
  private lazy val (_, pWrong4) = proofFor(4)(p => p.performOneOperation(Lookup(ADKey @@ key(0))).get) // different tree → construct fails

  private def treeData(digestHex: String, flags: AvlTreeFlags = AvlTreeFlags.AllOperationsAllowed): AvlTreeData =
    AvlTreeData(Colls.fromArray(Base16.decode(digestHex).get), flags, keyLength = 32, valueLengthOpt = Some(8))

  private val ByteColl = SCollection(SByte)
  private def serializeClosed(v: Byte, root: Value[SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, v))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }
  private def mc(v: Byte, data: AvlTreeData, m: SMethod, args: Value[SType]*): String =
    VersionContext.withVersions(v, v) {
      serializeClosed(v, MethodCall(AvlTreeConstant(CAvlTree(data)), m, args.toIndexedSeq, Map()))
    }
  private def bytesColl(items: Array[Byte]*): Value[SType] =
    ConcreteCollection(items.toArray.map(b => ByteArrayConstant(b): Value[SType]), ByteColl)
  private def kvColl(pairs: (Array[Byte], Array[Byte])*): Value[SType] =
    ConcreteCollection(
      pairs.toArray.map { case (k, v) => Tuple(Vector(ByteArrayConstant(k), ByteArrayConstant(v))): Value[SType] },
      SPair(ByteColl, ByteColl))

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def entry(op: String, script: String, hex: String, name: String, v: Byte): Json =
    SpecExtract.authoredEntry(op, script, hex, name, dummyInput, v)
  private def reject(op: String, script: String, hex: String, name: String, v: Byte): Json =
    SpecExtract.authoredRejectEntry(op, script, hex, name, dummyInput, v)

  def extract(): Map[String, Json] = {
    val zero1 = Array[Byte](0x00)
    val trunc = pLookup2.take(pLookup2.length / 2)
    val empty = Array.emptyByteArray

    // ── Ask 1: bad proof bytes ────────────────────────────────────────────────
    val badBytesV5 = Seq(
      entry(OpBadBytesV5, "{ tree.contains(key2, 0x00) }  // construction fails → false (no throw)",
        mc(V2, treeData(d8), SAvlTreeMethods.containsMethod, ByteArrayConstant(key(2)), ByteArrayConstant(zero1)), "contains-0x00-false#0", V2),
      entry(OpBadBytesV5, "{ tree.contains(key2, truncatedProof) }  // → false",
        mc(V2, treeData(d8), SAvlTreeMethods.containsMethod, ByteArrayConstant(key(2)), ByteArrayConstant(trunc)), "contains-truncated-false#1", V2),
      entry(OpBadBytesV5, "{ tree.contains(key2, emptyProof) }  // → false",
        mc(V2, treeData(d8), SAvlTreeMethods.containsMethod, ByteArrayConstant(key(2)), ByteArrayConstant(empty)), "contains-empty-false#2", V2),
      reject(OpBadBytesV5, "{ tree.get(key2, 0x00) }  // construction fails → raise",
        mc(V2, treeData(d8), SAvlTreeMethods.getMethod, ByteArrayConstant(key(2)), ByteArrayConstant(zero1)), "get-0x00-errored#3", V2),
      reject(OpBadBytesV5, "{ tree.insert([(key50,val50)], 0x00) }  // pre-v3: construction fails → raise (#908)",
        mc(V2, treeData(d8), SAvlTreeMethods.insertMethod, kvColl(key(50) -> value(50)), ByteArrayConstant(zero1)), "insert-0x00-errored#4", V2))
    val badBytesV6 = Seq(
      entry(OpBadBytesV6, "{ tree.insert([(key50,val50)], 0x00) }  // v3+: construction fails → None (#908)",
        mc(V3, treeData(d8), SAvlTreeMethods.insertMethod, kvColl(key(50) -> value(50)), ByteArrayConstant(zero1)), "insert-0x00-none#0", V3))

    // ── Ask 2: empty ops + valid starting-digest proof → Some(starting tree) ──
    val emptyOpsV5 = Seq(
      entry(OpEmptyOpsV5, "{ tree.insert([], validEmptyProof) }  // → Some(starting tree)",
        mc(V2, treeData(dStart), SAvlTreeMethods.insertMethod, kvColl(), ByteArrayConstant(pEmpty)), "insert-empty-ops-some#0", V2),
      entry(OpEmptyOpsV5, "{ tree.update([], validEmptyProof) }  // → Some(starting tree)",
        mc(V2, treeData(dStart), SAvlTreeMethods.updateMethod, kvColl(), ByteArrayConstant(pEmpty)), "update-empty-ops-some#1", V2),
      entry(OpEmptyOpsV5, "{ tree.remove([], validEmptyProof) }  // → Some(starting tree)",
        mc(V2, treeData(dStart), SAvlTreeMethods.removeMethod, bytesColl(), ByteArrayConstant(pEmpty)), "remove-empty-ops-some#2", V2))
    val emptyOpsV6 = Seq(
      entry(OpEmptyOpsV6, "{ tree.insertOrUpdate([], validEmptyProof) }  // → Some(starting tree)",
        mc(V3, treeData(dStart), SAvlTreeMethods.insertOrUpdateMethod, kvColl(), ByteArrayConstant(pEmpty)), "insertOrUpdate-empty-ops-some#0", V3))

    // ── Twins ─────────────────────────────────────────────────────────────────
    val edgesV5 = Seq(
      entry(OpEdgesV5, "{ tree.remove([key5], proofForRemoveKey4) }  // mismatched op → None (T1: cfor ignores results)",
        mc(V2, treeData(d8), SAvlTreeMethods.removeMethod, bytesColl(key(5)), ByteArrayConstant(pRem4)), "remove-mismatched-op-none#0", V2),
      entry(OpEdgesV5, "{ tree.getMany([], proof) }  // empty keys → empty Coll (T2)",
        mc(V2, treeData(d8), SAvlTreeMethods.getManyMethod, bytesColl(), ByteArrayConstant(pLookup2)), "getMany-empty-keys-empty-coll#1", V2),
      entry(OpEdgesV5, "{ tree.insert([], wrongTreeProof) }  // empty entries → None even @v5 (T3)",
        mc(V2, treeData(d8), SAvlTreeMethods.insertMethod, kvColl(), ByteArrayConstant(pWrong4)), "insert-empty-entries-none#2", V2),
      entry(OpEdgesV5, "{ tree.update([], wrongTreeProof) }  // empty entries → None (T3)",
        mc(V2, treeData(d8), SAvlTreeMethods.updateMethod, kvColl(), ByteArrayConstant(pWrong4)), "update-empty-entries-none#3", V2))
    val edgesV6 = Seq(
      entry(OpEdgesV6, "{ tree.insert([], wrongTreeProof) }  // empty entries → None @v6 too (T3 pair)",
        mc(V3, treeData(d8), SAvlTreeMethods.insertMethod, kvColl(), ByteArrayConstant(pWrong4)), "insert-empty-entries-none#0", V3))

    Map(
      OpBadBytesV5 -> SpecExtract.authoredEnvelope(OpBadBytesV5, badBytesV5, Source),
      OpBadBytesV6 -> SpecExtract.authoredEnvelope(OpBadBytesV6, badBytesV6, Source),
      OpEmptyOpsV5 -> SpecExtract.authoredEnvelope(OpEmptyOpsV5, emptyOpsV5, Source),
      OpEmptyOpsV6 -> SpecExtract.authoredEnvelope(OpEmptyOpsV6, emptyOpsV6, Source),
      OpEdgesV5    -> SpecExtract.authoredEnvelope(OpEdgesV5, edgesV5, Source),
      OpEdgesV6    -> SpecExtract.authoredEnvelope(OpEdgesV6, edgesV6, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredAvlDegenerate", extract(), outDir)
}
