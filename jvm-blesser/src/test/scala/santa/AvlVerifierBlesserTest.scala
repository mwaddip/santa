package santa

import santa.AvlProofGenerator.{AvlOp, TreeConfig}
import santa.AvlVerifierBlesser.VerifyConfig

/** The three levels a single `expectedNewDigestHex: null` conflates in ergots'
  * corpus: proof rejected / operation failed / clean success. The JVM
  * distinguishes all three and so must the blesser. */
class AvlVerifierBlesserTest extends munit.FunSuite {

  private def key(b: Int): String = f"$b%02x" * 32
  private def value(b: Int): String = f"$b%02x" * 8
  private val proverCfg = TreeConfig(32, None)
  private def vcfg(maxOps: Int, maxDel: Int) = VerifyConfig(32, None, Some(maxOps), Some(maxDel))

  /** Empty-tree starting digest, produced by the reference prover. */
  private val emptyDigest: String = AvlProofGenerator.generate(proverCfg, Nil, Nil)._3

  test("clean success: proof accepted, all ops ok, digest present") {
    val ops = List(AvlOp("Insert", key(4), Some(value(4))))
    val (proof, _, _) = AvlProofGenerator.generate(proverCfg, Nil, ops)
    val out = AvlVerifierBlesser.verify(vcfg(1, 0), emptyDigest, proof, ops)

    assert(out.proofAccepted, "valid proof must anchor")
    assertEquals(out.results.size, 1)
    assert(out.results.head.ok, "the insert must succeed")
    assert(out.newDigestHex.isDefined, "successful batch must yield a digest")
  }

  test("proof rejected: truncated proof anchors nothing, results empty") {
    val ops = List(AvlOp("Insert", key(4), Some(value(4))))
    val (proof, _, _) = AvlProofGenerator.generate(proverCfg, Nil, ops)
    val truncated = proof.substring(0, proof.length - 8)
    val out = AvlVerifierBlesser.verify(vcfg(1, 0), emptyDigest, truncated, ops)

    assert(!out.proofAccepted, "a truncated proof must not anchor")
    assertEquals(out.results, Nil, "no operations are attempted when the proof is rejected")
    assertEquals(out.newDigestHex, None)
  }

  test("operation failure: proof is fine, the op fails, digest is None") {
    // Remove an absent key from an empty tree: valid proof, failing operation.
    val ops = List(AvlOp("Remove", key(0xaa), None))
    val lookupOnly = List(AvlOp("Lookup", key(0xaa), None))
    val (proof, _, _) = AvlProofGenerator.generate(proverCfg, Nil, lookupOnly)
    val out = AvlVerifierBlesser.verify(vcfg(1, 1), emptyDigest, proof, ops)

    assert(out.proofAccepted, "the proof itself is well-formed")
    assertEquals(out.results.size, 1)
    assert(!out.results.head.ok, "removing an absent key must fail")
    assertEquals(out.newDigestHex, None, "a poisoned verifier reports no digest")
  }

  test("deriveFromEntry decodes snake_case settings/payload and verifies") {
    val ops = List(AvlOp("Insert", key(4), Some(value(4))))
    val (proof, _, _) = AvlProofGenerator.generate(proverCfg, Nil, ops)
    val entry = io.circe.parser.parse(s"""{
      "settings": {"key_length": 32, "value_length": null,
                   "max_num_operations": 1, "max_deletes": 0},
      "payload": {"starting_digest_hex": "$emptyDigest", "proof_hex": "$proof",
                  "operations": [{"tag":"Insert","key_hex":"${key(4)}","value_hex":"${value(4)}"}]}
      }""").toOption.get
    val out = AvlVerifierBlesser.deriveFromEntry(entry)
    assertEquals(out, AvlVerifierBlesser.verify(vcfg(1, 0), emptyDigest, proof, ops))
  }
}
