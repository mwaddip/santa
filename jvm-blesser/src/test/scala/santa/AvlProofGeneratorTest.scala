package santa

import santa.AvlProofGenerator.{AvlOp, Kv, TreeConfig}

/** Multi-cycle proof generation. `genProofAfter` holds ascending operation
  * indices; a proof + digest is captured after the operation at each index.
  * Pins that a two-cycle run differs from one-shot — the cycle boundary is
  * load-bearing state (wasModified / label cache), not cosmetic. */
class AvlProofGeneratorTest extends munit.FunSuite {

  private val cfg = TreeConfig(keyLength = 32, valueLengthOpt = None)
  private def key(b: Int): String = f"$b%02x" * 32
  private def value(b: Int): String = f"$b%02x" * 8

  test("genProofAfter captures one proof+digest per index, in order") {
    val ops = List(
      AvlOp("Insert", key(4), Some(value(4))),
      AvlOp("Remove", key(4), None)
    )
    val (proofs, digests) =
      AvlProofGenerator.generateCycles(cfg, Nil, ops, List(0, 1))

    assertEquals(proofs.size, 2)
    assertEquals(digests.size, 2)
    assert(proofs.forall(_.nonEmpty), "every cycle must produce proof bytes")
    assertEquals(digests.map(_.length).distinct, List(66), "digest = 33 bytes hex")
    assertNotEquals(digests(0), digests(1), "the Remove must move the digest")
  }

  test("a single trailing cycle equals the legacy one-shot generate") {
    val ops = List(AvlOp("Insert", key(4), Some(value(4))))
    val (proofs, digests) = AvlProofGenerator.generateCycles(cfg, Nil, ops, List(0))
    val (proof, _, digest) = AvlProofGenerator.generate(cfg, Nil, ops)
    assertEquals(proofs.head, proof)
    assertEquals(digests.head, digest)
  }

  test("empty genProofAfter yields empty lists") {
    val (proofs, digests) = AvlProofGenerator.generateCycles(cfg, Nil, Nil, Nil)
    assertEquals(proofs, Nil)
    assertEquals(digests, Nil)
  }

  test("deriveFromEntry decodes snake_case settings/payload and proves") {
    val entry = io.circe.parser.parse(s"""{
      "settings": {"key_length": 32, "value_length": null},
      "payload": {
        "operations": [{"tag":"Insert","key_hex":"${key(4)}","value_hex":"${value(4)}"}],
        "gen_proof_after": [0]
      }}""").toOption.get
    val (proofs, digests) = AvlProofGenerator.deriveFromEntry(entry)
    val (proof, _, digest) = AvlProofGenerator.generate(cfg, Nil,
      List(AvlOp("Insert", key(4), Some(value(4)))))
    assertEquals(proofs, List(proof))
    assertEquals(digests, List(digest))
  }

  test("fromJson without gen_proof_after keeps the legacy 3-field shape") {
    val body = s"""{"key_length":32,"value_length":null,
                    "operations":[{"kind":"Insert","key":"${key(4)}","value":"${value(4)}"}]}"""
    val out = AvlProofGenerator.fromJson(body)
    val c = out.hcursor
    assert(c.get[String]("proof_bytes").isRight,  "legacy proof_bytes must survive")
    assert(c.get[String]("proof_digest").isRight, "legacy proof_digest must survive")
    assert(c.get[String]("tree_digest").isRight,  "legacy tree_digest must survive")
  }

  test("fromJson with gen_proof_after returns parallel proofs+digests") {
    val body = s"""{"key_length":32,"value_length":null,
                    "operations":[{"kind":"Insert","key":"${key(4)}","value":"${value(4)}"},
                                  {"kind":"Remove","key":"${key(4)}"}],
                    "gen_proof_after":[0,1]}"""
    val c = AvlProofGenerator.fromJson(body).hcursor
    assertEquals(c.downField("proofs").values.map(_.size), Some(2))
    assertEquals(c.downField("digests").values.map(_.size), Some(2))
  }
}
