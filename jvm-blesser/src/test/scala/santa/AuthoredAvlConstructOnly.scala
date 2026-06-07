package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored "construct-only" AvlTree AST nodes — ergots f4-santa-asks.md Ask 4 + B.
//
// TreeLookup (trees.scala:1322) and CreateAvlTree (trees.scala:79) both have
// costKind = Value.notSupportedError and NO eval override, so the default
// Value.eval (values.scala:101 — sys.error "Should be overriden") fires at
// runtime. They serialize + deserialize fine (serializers ARE registered), but
// EVAL ALWAYS ERRORS. → any spend reaching one is a reject in the JVM.
//
//   Ask 4: ergots' tree-lookup.ts returns a value via verifyAvlLookup → an
//          OVER-ACCEPT fork (consensus-split: a hand-crafted block exercises it).
//   B:     ergots' CreateAvlTree.keyLength question is moot — the JVM never
//          produces a value+cost for CreateAvlTree at all.
//
// TreeLookup serializes at v5 AND v6 (pinned at both — the error is not version-
// gated). CreateAvlTree only serializes at v6 here (its v2 path trips a data-value
// validation rule before eval), so its reject is pinned at v6.
//
// authoredRejectEntry blesses the coarse errored shape; a success is a loud bug.
// ─────────────────────────────────────────────────────────────────────────────

import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Lookup}
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.encode.Base16

import io.circe.Json

import sigma.VersionContext
import sigma.ast.{AvlTreeConstant, ByteArrayConstant, ByteConstant, ConstantNode, CreateAvlTree,
  ErgoTree, IntConstant, SInt, SOption, SType, TreeLookup, Value}
import sigma.ast.ErgoTree.{HeaderType, ZeroHeader}
import sigma.data.{AvlTreeData, AvlTreeFlags, CAvlTree}
import sigma.Colls

object AuthoredAvlConstructOnly {

  val V2: Byte = VersionContext.JitActivationVersion // v5 (2,2)
  val V3: Byte = VersionContext.V6SoftForkVersion    // v6 (3,3)
  val Source   = "santa:authored-avl-construct-only"

  val OpV5 = "AvlTree.unsupported_eval_nodes"
  val OpV6 = "AvlTree.unsupported_eval_nodes_v6"

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
  // valid lookup proof for key2 — so the reject is the unsupported-eval, not a proof fault
  private lazy val (d8, pLookup2) = {
    val p = prover(8); val d = Base16.encode(p.digest)
    p.performOneOperation(Lookup(ADKey @@ key(2))).get
    (d, p.generateProof())
  }
  private def realDigest33: Array[Byte] = Base16.decode(d8).get

  private def treeData: AvlTreeData =
    AvlTreeData(Colls.fromArray(realDigest33), AvlTreeFlags.AllOperationsAllowed, keyLength = 32, valueLengthOpt = Some(8))

  private def serializeClosed(v: Byte, root: Value[SType]): String = {
    val header: HeaderType = ErgoTree.setSizeBit(ErgoTree.headerWithVersion(ZeroHeader, v))
    Base16.encode(sigma.santa.LenientErgoTree.serialize(header, root))
  }
  private def hexOf(v: Byte)(root: Value[SType]): String =
    VersionContext.withVersions(v, v) { serializeClosed(v, root) }

  private def treeLookupRoot: Value[SType] =
    TreeLookup(AvlTreeConstant(CAvlTree(treeData)), ByteArrayConstant(key(2)), ByteArrayConstant(pLookup2))
  private def createAvlTreeRoot: Value[SType] = {
    val noValueLen: Value[SOption[SInt.type]] = ConstantNode[SOption[SInt.type]](None, SOption(SInt))
    CreateAvlTree(ByteConstant(AvlTreeFlags.AllOperationsAllowed.serializeToByte),
      ByteArrayConstant(realDigest33), IntConstant(32), noValueLen)
  }

  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def reject(op: String, script: String, hex: String, name: String, v: Byte): Json =
    SpecExtract.authoredRejectEntry(op, script, hex, name, dummyInput, v)

  def extract(): Map[String, Json] = {
    val v5 = Seq(
      reject(OpV5, "{ TreeLookup(tree, key2, validProof) }  // notSupportedError costKind, no eval override → errored",
        hexOf(V2)(treeLookupRoot), "tree_lookup-errored#0", V2))
    val v6 = Seq(
      reject(OpV6, "{ TreeLookup(tree, key2, validProof) }  // unsupported-eval node → errored (v6)",
        hexOf(V3)(treeLookupRoot), "tree_lookup-errored#0", V3),
      reject(OpV6, "{ CreateAvlTree(flags, digest, 32, None) }  // no eval override → errored (B: .keyLength unreachable)",
        hexOf(V3)(createAvlTreeRoot), "create_avl_tree-errored#1", V3))
    Map(
      OpV5 -> SpecExtract.authoredEnvelope(OpV5, v5, Source),
      OpV6 -> SpecExtract.authoredEnvelope(OpV6, v6, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredAvlConstructOnly", extract(), outDir)
}
