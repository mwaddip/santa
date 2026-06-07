package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored AvlTree op-shape sweep — ergots f4-santa-asks.md Ask 5 (18 pins) + S5.
// VALID-construction trees (n=8, keyLength=32, valueLengthOpt=Some(8); prover
// material matches AuthoredAvlTier2) with a malformed OP input → per-op Failure.
// Confirms the failure model routes identically whether the failure is at
// construction (wrong-tree / bad-bytes) or at the OP (wrong key/value, broken tree):
//
//   contains → false · get/getMany → errored · insert → raise@v5 / None@v6 (#908)
//   update/remove → None · insertOrUpdate → None (v6-only)
//
// Shapes (ergots' example used keyLength=8 + 4-byte key; we use the corpus-standard
// keyLength=32, so a 4-byte key is wrong-length and ±inf keys are 32×0x00 / 32×0xFF —
// routing is identical, costs are the keyLength=32 values):
//   wrong-len-key (4 of 32) · -inf (32×0x00) · +inf (32×0xFF) · wrong-val-len (4 of 8)
//
// S5: ops on a tree whose keyLength field is the wrapped-negative 0x80000000 (built
// via the patched-bytes trick — sentinel 0x70000000, VLQ 80 80 80 80 07 → …08).
//
// authoredEntry / authoredRejectEntry; costs locked in the test from the bless.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Lookup, Remove, Update}
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

object AuthoredAvlOpShapes {

  val V2: Byte = VersionContext.JitActivationVersion // v5 (2,2)
  val V3: Byte = VersionContext.V6SoftForkVersion    // v6 (3,3)
  val Source   = "santa:authored-avl-op-shapes"

  val OpPerOpV5 = "AvlTree.per_op_failure"
  val OpPerOpV6 = "AvlTree.per_op_failure_v6"
  val OpNegKlV5 = "AvlTree.negative_keylength_tree"
  val OpNegKlV6 = "AvlTree.negative_keylength_tree_v6"

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

  // shared n=8 tree + valid proofs (construction succeeds; the OP then fails)
  private lazy val (d8, pLookup2) = proofFor(8)(p => p.performOneOperation(Lookup(ADKey @@ key(2))).get)
  private lazy val (_, pIns50)    = proofFor(8)(p => p.performOneOperation(Insert(ADKey @@ key(50), ADValue @@ value(50))).get)
  private lazy val (_, pUpd3)     = proofFor(8)(p => p.performOneOperation(Update(ADKey @@ key(3), ADValue @@ value(77))).get)
  private lazy val (_, pRem4)     = proofFor(8)(p => p.performOneOperation(Remove(ADKey @@ key(4))).get)
  private lazy val (_, pIou60)    = proofFor(8)(p => p.performOneOperation(Insert(ADKey @@ key(60), ADValue @@ value(60))).get)

  private def treeData(digestHex: String, keyLength: Int = 32): AvlTreeData =
    AvlTreeData(Colls.fromArray(Base16.decode(digestHex).get), AvlTreeFlags.AllOperationsAllowed,
      keyLength = keyLength, valueLengthOpt = Some(8))

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

  // S5: patched-bytes tree with keyLength field 0x80000000 (sentinel 0x70000000)
  private val SentinelKeyLen = 0x70000000
  private val FromVlq = "8080808007"; private val ToVlq = "8080808008"
  private def negKlTree(v: Byte, m: SMethod, args: Value[SType]*): String = {
    val clean = mc(v, treeData(d8, keyLength = SentinelKeyLen), m, args: _*)
    val occ = FromVlq.r.findAllMatchIn(clean).size
    if (occ != 1) sys.error(s"AuthoredAvlOpShapes negKl: expected 1 keyLength VLQ ('$FromVlq'), found $occ in $clean")
    clean.replace(FromVlq, ToVlq)
  }

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def entry(op: String, script: String, hex: String, name: String, v: Byte): Json =
    SpecExtract.authoredEntry(op, script, hex, name, dummyInput, v)
  private def reject(op: String, script: String, hex: String, name: String, v: Byte): Json =
    SpecExtract.authoredRejectEntry(op, script, hex, name, dummyInput, v)

  // malformed inputs
  private val k4    = Array[Byte](1, 2, 3, 4)              // wrong-length key (4 of 32)
  private val kZero = Array.fill[Byte](32)(0x00.toByte)    // -inf
  private val kFF   = Array.fill[Byte](32)(0xFF.toByte)    // +inf
  private val v4    = Array[Byte](9, 9, 9, 9)              // wrong-length value (4 of 8)

  def extract(): Map[String, Json] = {
    val td = treeData(d8)

    // ── per-op failure: lookup methods (v5) ──────────────────────────────────
    val contains = Seq("wrong-len" -> k4, "-inf" -> kZero, "+inf" -> kFF).map { case (tag, k) =>
      entry(OpPerOpV5, s"{ tree.contains($tag, validProof) }  // op Failure → false",
        mc(V2, td, SAvlTreeMethods.containsMethod, ByteArrayConstant(k), ByteArrayConstant(pLookup2)), s"contains-$tag-false", V2)
    }
    val get = Seq("wrong-len" -> k4, "-inf" -> kZero, "+inf" -> kFF).map { case (tag, k) =>
      reject(OpPerOpV5, s"{ tree.get($tag, validProof) }  // op Failure → raise",
        mc(V2, td, SAvlTreeMethods.getMethod, ByteArrayConstant(k), ByteArrayConstant(pLookup2)), s"get-$tag-errored", V2)
    }
    val getMany = Seq("wrong-len" -> k4, "-inf" -> kZero, "+inf" -> kFF).map { case (tag, k) =>
      reject(OpPerOpV5, s"{ tree.getMany([$tag], validProof) }  // per-key Failure → raise",
        mc(V2, td, SAvlTreeMethods.getManyMethod, bytesColl(k), ByteArrayConstant(pLookup2)), s"getMany-$tag-errored", V2)
    }

    // ── per-op failure: insert@v5 (raise) ─────────────────────────────────────
    val insertShapes = Seq("wrong-len-key" -> kvColl(k4 -> value(50)), "-inf-key" -> kvColl(kZero -> value(50)),
      "+inf-key" -> kvColl(kFF -> value(50)), "wrong-val-len" -> kvColl(key(50) -> v4))
    val insertV5 = insertShapes.map { case (tag, ops) =>
      reject(OpPerOpV5, s"{ tree.insert($tag, validProof) }  // pre-v3: op Failure → raise (#908)",
        mc(V2, td, SAvlTreeMethods.insertMethod, ops, ByteArrayConstant(pIns50)), s"insert-$tag-errored", V2)
    }

    // ── per-op failure: update / remove (v5; version-independent None) ─────────
    val updateShapes = Seq("wrong-len-key" -> kvColl(k4 -> value(77)), "-inf-key" -> kvColl(kZero -> value(77)),
      "+inf-key" -> kvColl(kFF -> value(77)), "wrong-val-len" -> kvColl(key(3) -> v4))
    val update = updateShapes.map { case (tag, ops) =>
      entry(OpPerOpV5, s"{ tree.update($tag, validProof) }  // op Failure discarded → None",
        mc(V2, td, SAvlTreeMethods.updateMethod, ops, ByteArrayConstant(pUpd3)), s"update-$tag-none", V2)
    }
    val remove = Seq("wrong-len" -> k4, "-inf" -> kZero, "+inf" -> kFF).map { case (tag, k) =>
      entry(OpPerOpV5, s"{ tree.remove([$tag], validProof) }  // op results ignored → None",
        mc(V2, td, SAvlTreeMethods.removeMethod, bytesColl(k), ByteArrayConstant(pRem4)), s"remove-$tag-none", V2)
    }

    // ── per-op failure: insert@v6 (None) + insertOrUpdate@v6 (None) ────────────
    val insertV6 = insertShapes.map { case (tag, ops) =>
      entry(OpPerOpV6, s"{ tree.insert($tag, validProof) }  // v3+: op Failure → None (#908)",
        mc(V3, td, SAvlTreeMethods.insertMethod, ops, ByteArrayConstant(pIns50)), s"insert-$tag-none", V3)
    }
    val iouShapes = Seq("wrong-len-key" -> kvColl(k4 -> value(60)), "-inf-key" -> kvColl(kZero -> value(60)),
      "+inf-key" -> kvColl(kFF -> value(60)), "wrong-val-len" -> kvColl(key(60) -> v4))
    val insertOrUpdate = iouShapes.map { case (tag, ops) =>
      entry(OpPerOpV6, s"{ tree.insertOrUpdate($tag, validProof) }  // op Failure → None",
        mc(V3, td, SAvlTreeMethods.insertOrUpdateMethod, ops, ByteArrayConstant(pIou60)), s"insertOrUpdate-$tag-none", V3)
    }

    // ── S5: ops on a negative-keyLength tree ──────────────────────────────────
    val negKlV5 = Seq(
      entry(OpNegKlV5, "{ negKlTree.contains(key2, proof) }  // broken tree → false",
        negKlTree(V2, SAvlTreeMethods.containsMethod, ByteArrayConstant(key(2)), ByteArrayConstant(pLookup2)), "contains-false#0", V2),
      reject(OpNegKlV5, "{ negKlTree.get(key2, proof) }  // broken tree → raise",
        negKlTree(V2, SAvlTreeMethods.getMethod, ByteArrayConstant(key(2)), ByteArrayConstant(pLookup2)), "get-errored#1", V2),
      reject(OpNegKlV5, "{ negKlTree.insert([(key50,val50)], proof) }  // pre-v3 → raise (#908)",
        negKlTree(V2, SAvlTreeMethods.insertMethod, kvColl(key(50) -> value(50)), ByteArrayConstant(pIns50)), "insert-errored#2", V2),
      entry(OpNegKlV5, "{ negKlTree.remove([key4], proof) }  // broken tree → None",
        negKlTree(V2, SAvlTreeMethods.removeMethod, bytesColl(key(4)), ByteArrayConstant(pRem4)), "remove-none#3", V2),
      entry(OpNegKlV5, "{ negKlTree.keyLength }  // wrapped-negative field → -2147483648",
        negKlTree(V2, SAvlTreeMethods.keyLengthMethod), "keyLength-negative#4", V2))
    val negKlV6 = Seq(
      entry(OpNegKlV6, "{ negKlTree.insert([(key50,val50)], proof) }  // v3+ → None (#908)",
        negKlTree(V3, SAvlTreeMethods.insertMethod, kvColl(key(50) -> value(50)), ByteArrayConstant(pIns50)), "insert-none#0", V3))

    val perOpV5 = contains ++ get ++ getMany ++ insertV5 ++ update ++ remove
    val perOpV6 = insertV6 ++ insertOrUpdate
    Map(
      OpPerOpV5 -> SpecExtract.authoredEnvelope(OpPerOpV5, perOpV5, Source),
      OpPerOpV6 -> SpecExtract.authoredEnvelope(OpPerOpV6, perOpV6, Source),
      OpNegKlV5 -> SpecExtract.authoredEnvelope(OpNegKlV5, negKlV5, Source),
      OpNegKlV6 -> SpecExtract.authoredEnvelope(OpNegKlV6, negKlV6, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredAvlOpShapes", extract(), outDir)
}
