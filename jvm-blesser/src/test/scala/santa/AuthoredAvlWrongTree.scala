package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored AvlTree wrong-tree-proof vectors — the full per-method asymmetry row.
//
// Wrong-tree proof = a VALID proof generated from a different tree instance
// (committed n=4) applied against the target digest (committed n=8) — the
// digest-mismatch flavor of proof failure (insertOrUpdate#bad-proof idiom),
// distinct from proof_adversarial's tampered-bytes / wrong-key arms.
//
// The JVM's surface is per-method asymmetric (CErgoTreeEvaluator.scala):
//   contains :89  lookup Failure → false            ACCEPT false      (both versions)
//   get      :106 lookup Failure → syntax.error     RAISE             (both versions)
//   getMany  :126 per-key, same                     RAISE             (both versions)
//   insert   :150 op Failure → error gated on       RAISE at v5,
//                 !isV3OrLaterErgoTreeVersion         None at v6      (issue #908!)
//   update   :183 op Failure discarded by forall    ACCEPT None       (both versions)
//   remove   :240 op results IGNORED (cfor, no      ACCEPT None       (both versions)
//                 break; charges digest_Info where update doesn't)
//
// insert is the soft-fork split: the SAME tree+proof rejects at v5 (2,2) and
// evals to None at v6 (3,3) — pinned as a cross-version pair:
//   v5/authored/AvlTree.wrong_tree_proof   (all six methods, the complete row)
//   v6/authored/AvlTree.insert_wrong_tree  (the post-908 half of the pair)
//
// All outcomes blessed by the JVM oracle (authoredEntry / authoredRejectEntry);
// prover material identical to AuthoredAvlTier2 (byte-stable, spike-verified).
// ─────────────────────────────────────────────────────────────────────────────

import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Lookup, Remove, Update}
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{AvlTreeConstant, ByteArrayConstant, ConcreteCollection, ErgoTree,
  MethodCall, SAvlTreeMethods, SByte, SCollection, SPair, SType, Tuple, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.data.{AvlTreeData, AvlTreeFlags, CAvlTree}
import sigma.Colls

object AuthoredAvlWrongTree {

  val V2: Byte = VersionContext.JitActivationVersion // v5 pin (2,2)
  val V3: Byte = VersionContext.V6SoftForkVersion    // v6 pin (3,3)
  val Source = "santa:authored-avl-wrong-tree"

  val OpV5 = "AvlTree.wrong_tree_proof"
  val OpV6 = "AvlTree.insert_wrong_tree"

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
  private def treeData(digestHex: String): AvlTreeData =
    AvlTreeData(Colls.fromArray(Base16.decode(digestHex).get), AvlTreeFlags.AllOperationsAllowed,
      keyLength = 32, valueLengthOpt = Some(8))

  // ── AST builders (closed trees, version-parameterized header) ──────────────
  private val ByteColl = SCollection(SByte)
  private def serializeClosed(v: Byte, root: Value[SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, v))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }
  private def mc(v: Byte, data: AvlTreeData, m: sigma.ast.SMethod, args: Value[SType]*): String =
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

  // ── wrong-tree proofs: per-op, generated from the committed n=4 tree ───────
  private lazy val d8 = Base16.encode(prover(8).digest)
  private lazy val (_, pLookup2)  = proofFor(4)(p => p.performOneOperation(Lookup(ADKey @@ key(2))).get)
  private lazy val (_, pLookup01) = proofFor(4)(p => (0 until 2).foreach(i => p.performOneOperation(Lookup(ADKey @@ key(i))).get))
  private lazy val (_, pIns50)    = proofFor(4)(p => p.performOneOperation(Insert(ADKey @@ key(50), ADValue @@ value(50))).get)
  private lazy val (_, pUpd3)     = proofFor(4)(p => p.performOneOperation(Update(ADKey @@ key(3), ADValue @@ value(77))).get)
  private lazy val (_, pRem0)     = proofFor(4)(p => p.performOneOperation(Remove(ADKey @@ key(0))).get)

  // insert args shared by the v5/v6 pair — the SAME tree+proof, split by version
  private def insertHex(v: Byte): String =
    mc(v, treeData(d8), SAvlTreeMethods.insertMethod, kvColl(key(50) -> value(50)), ByteArrayConstant(pIns50))

  def extract(): Map[String, Json] = {
    val v5Entries = Seq(
      entry(OpV5, "{ tree(n=8).contains(key2, wrongTreeProof) }  // lookup Failure → false",
        mc(V2, treeData(d8), SAvlTreeMethods.containsMethod, ByteArrayConstant(key(2)), ByteArrayConstant(pLookup2)),
        "contains-false#0", V2),
      reject(OpV5, "{ tree(n=8).get(key2, wrongTreeProof) }  // lookup Failure → raise",
        mc(V2, treeData(d8), SAvlTreeMethods.getMethod, ByteArrayConstant(key(2)), ByteArrayConstant(pLookup2)),
        "get-errored#1", V2),
      reject(OpV5, "{ tree(n=8).getMany([key0,key1], wrongTreeProof) }  // lookup Failure → raise",
        mc(V2, treeData(d8), SAvlTreeMethods.getManyMethod, bytesColl(key(0), key(1)), ByteArrayConstant(pLookup01)),
        "getMany-errored#2", V2),
      reject(OpV5, "{ tree(n=8).insert([(key50,val50)], wrongTreeProof) }  // pre-v3: op Failure → raise (#908 gate)",
        insertHex(V2),
        "insert-errored#3", V2),
      entry(OpV5, "{ tree(n=8).update([(key3,val77)], wrongTreeProof) }  // op Failure discarded → None",
        mc(V2, treeData(d8), SAvlTreeMethods.updateMethod, kvColl(key(3) -> value(77)), ByteArrayConstant(pUpd3)),
        "update-none#4", V2),
      entry(OpV5, "{ tree(n=8).remove([key0], wrongTreeProof) }  // op results ignored → None",
        mc(V2, treeData(d8), SAvlTreeMethods.removeMethod, bytesColl(key(0)), ByteArrayConstant(pRem0)),
        "remove-none#5", V2))

    val v6Entries = Seq(
      entry(OpV6, "{ tree(n=8).insert([(key50,val50)], wrongTreeProof) }  // v3+: op Failure discarded → None (#908 gate)",
        insertHex(V3),
        "insert-none#0", V3))

    Map(
      OpV5 -> SpecExtract.authoredEnvelope(OpV5, v5Entries, Source),
      OpV6 -> SpecExtract.authoredEnvelope(OpV6, v6Entries, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredAvlWrongTree", extract(), outDir)
}
