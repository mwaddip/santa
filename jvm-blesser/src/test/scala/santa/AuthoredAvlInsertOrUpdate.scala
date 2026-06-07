package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored AvlTree.insertOrUpdate proof-carrying vectors — v6/authored.
// Op: "AvlTree.insertOrUpdate" (method 16, DynamicCost, v6-gated at ErgoTree
// version 3, VersionContext (3,3)).
//
// Closed trees (powHit/Tier-2 pattern): MethodCall(AvlTreeConstant(CAvlTree(data)),
// SAvlTreeMethods.insertOrUpdateMethod, constant args, Map()) at v3; ignored dummy
// Int var-1 input mirrors the sibling AuthoredAvlTier2 helpers.
//
// Prover material: BatchAVLProver deterministic construction identical to
// AuthoredAvlTier2 (key(i)=Blake2b256("santa-avl-key-$i"),
// value(i)=8-byte BE 0x5A17A000+i; byte-stable spike S1).
// The batch op is InsertOrUpdate from scorex.crypto.authds.avltree.batch.
//
// Semantics (from Extensions.scala + methods.scala):
//   pre-check: !isInsertAllowed || !isUpdateAllowed → None (before verifier creation)
//   valid InsertOrUpdate → Some(newTree) if verifier digest succeeds; None otherwise
//   bad-proof (wrong-tree proof) → verifier returns no digest → None
//
// Four entries:
//   fresh-key:    n=8 tree, insert absent key50   → Some(newDigest)
//   existing-key: n=8 tree, update existing key3  → Some(newDigest)
//   flags-deny:   tree with insertAllowed=false    → None (pre-check fires)
//   bad-proof:    valid flags, proof from a DIFFERENT tree instance → None
//
// All outcomes blessed by the JVM oracle (authoredEntry / authoredRejectEntry
// pattern): report any deviation from the table above prominently.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, InsertOrUpdate}
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

object AuthoredAvlInsertOrUpdate {

  val V3: Byte  = VersionContext.V6SoftForkVersion // 3 — required for insertOrUpdate
  val Source    = "santa:authored-avl-insert-or-update"
  val Op        = "AvlTree.insertOrUpdate"

  // ── deterministic prover material (identical to AuthoredAvlTier2 spike S1) ──
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

  private def treeData(digestHex: String, flags: AvlTreeFlags = AvlTreeFlags.AllOperationsAllowed): AvlTreeData =
    AvlTreeData(Colls.fromArray(Base16.decode(digestHex).get), flags, keyLength = 32, valueLengthOpt = Some(8))

  // ── AST builders (closed trees, v3 header) ──────────────────────────────────
  private val ByteColl = SCollection(SByte)

  private def serializeClosed(root: Value[SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, V3))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }

  private def mc(data: AvlTreeData, args: Value[SType]*): String =
    VersionContext.withVersions(V3, V3) {
      serializeClosed(MethodCall(AvlTreeConstant(CAvlTree(data)),
        SAvlTreeMethods.insertOrUpdateMethod, args.toIndexedSeq, Map()))
    }

  private def kvColl(pairs: (Array[Byte], Array[Byte])*): Value[SType] =
    ConcreteCollection(
      pairs.toArray.map { case (k, v) => Tuple(Vector(ByteArrayConstant(k), ByteArrayConstant(v))): Value[SType] },
      SPair(ByteColl, ByteColl))

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def entry(script: String, hex: String, name: String): Json =
    SpecExtract.authoredEntry(Op, script, hex, name, dummyInput, V3)

  // ── memoized lazy: all proofs computed once ──────────────────────────────────

  /** fresh-key: n=8 tree, InsertOrUpdate absent key50 → insert path → Some(newDigest). */
  private lazy val (dFreshKey, pFreshKey): (String, Array[Byte]) =
    proofFor(8)(p => p.performOneOperation(InsertOrUpdate(ADKey @@ key(50), ADValue @@ value(50))).get)

  /** existing-key: n=8 tree, InsertOrUpdate existing key3 with a new value → update path → Some(newDigest). */
  private lazy val (dExistingKey, pExistingKey): (String, Array[Byte]) =
    proofFor(8)(p => p.performOneOperation(InsertOrUpdate(ADKey @@ key(3), ADValue @@ value(99))).get)

  /** bad-proof: proof generated from a DIFFERENT n=4 tree (wrong-tree idiom from AuthoredAvlTier2
    * adversarial arm). The n=8 tree verifier will reject this proof and return no digest → None. */
  private lazy val (_, pBadProof): (String, Array[Byte]) =
    proofFor(4)(p => p.performOneOperation(InsertOrUpdate(ADKey @@ key(0), ADValue @@ value(0))).get)

  // ── flags variants ───────────────────────────────────────────────────────────

  /** insertAllowed=false, updateAllowed=true — pre-check (!isInsertAllowed) → None. */
  private val flagsDenyInsert: AvlTreeFlags =
    AvlTreeFlags(insertAllowed = false, updateAllowed = true, removeAllowed = true)

  // ── extract() ───────────────────────────────────────────────────────────────

  def extract(): Json = {
    // arm 1: fresh-key (insert path)
    val freshKeyHex = mc(treeData(dFreshKey), kvColl(key(50) -> value(50)), ByteArrayConstant(pFreshKey))
    val freshKeyEntry = entry(
      "{ (t: AvlTree) => t.insertOrUpdate([(key50,val50)], proof) }  // absent key50 → insert path",
      freshKeyHex, "insertOrUpdate#fresh-key")

    // arm 2: existing-key (update path)
    val existingKeyHex = mc(treeData(dExistingKey), kvColl(key(3) -> value(99)), ByteArrayConstant(pExistingKey))
    val existingKeyEntry = entry(
      "{ (t: AvlTree) => t.insertOrUpdate([(key3,newVal)], proof) }  // existing key3 → update path",
      existingKeyHex, "insertOrUpdate#existing-key")

    // arm 3: flags-deny (insertAllowed=false → pre-check → None; authoredEntry blesses the None)
    // Re-use dFreshKey digest (any valid digest works — the pre-check fires before the verifier).
    val flagsDenyHex = mc(treeData(dFreshKey, flagsDenyInsert), kvColl(key(50) -> value(50)), ByteArrayConstant(pFreshKey))
    val flagsDenyEntry = entry(
      "{ (t: AvlTree) => t.insertOrUpdate(ops, proof) }  // insertAllowed=false → pre-check → None",
      flagsDenyHex, "insertOrUpdate#flags-deny")

    // arm 4: bad-proof (wrong-tree proof — verifier built from n=4 proof against n=8 tree → digest=None → None)
    val badProofHex = mc(treeData(dFreshKey), kvColl(key(50) -> value(50)), ByteArrayConstant(pBadProof))
    val badProofEntry = entry(
      "{ (t: AvlTree) => t.insertOrUpdate(ops, wrongTreeProof) }  // valid flags, wrong-tree proof → None",
      badProofHex, "insertOrUpdate#bad-proof")

    SpecExtract.authoredEnvelope(Op, Seq(freshKeyEntry, existingKeyEntry, flagsDenyEntry, badProofEntry), Source)
  }

  /** Staging writer: writes AvlTree.insertOrUpdate.json under outDir. */
  def writeVectors(envelope: Json, outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val path = outDir.resolve(s"${SpecExtract.slug(Op)}.json")
    java.nio.file.Files.write(path, envelope.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
