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

  // ── the eight-tag santa-authds/v1 operation vocabulary ────────────────────
  // toOperation used to cover only Insert/Lookup/Remove. One test per newly
  // supported tag: the tag must reach scrypto and produce a proof + digest,
  // not blow up in the decoder.

  private def provesCleanly(c: TreeConfig, ops: List[AvlOp], after: List[Int]): Unit = {
    val (proofs, digests) = AvlProofGenerator.generateCycles(c, Nil, ops, after)
    assertEquals(proofs.size, after.size)
    assertEquals(digests.size, after.size)
    assert(proofs.forall(_.nonEmpty), "every cycle must produce proof bytes")
    assertEquals(digests.map(_.length).distinct, List(66), "digest = 33 bytes hex")
  }

  test("Update reaches scrypto and proves") {
    provesCleanly(cfg, List(
      AvlOp("Insert", key(4), Some(value(4))),
      AvlOp("Update", key(4), Some(value(5)))
    ), List(0, 1))
  }

  test("InsertOrUpdate reaches scrypto and proves") {
    provesCleanly(cfg, List(
      AvlOp("InsertOrUpdate", key(4), Some(value(4))),
      AvlOp("InsertOrUpdate", key(4), Some(value(5)))
    ), List(0, 1))
  }

  test("RemoveIfExists reaches scrypto and proves, present key or not") {
    provesCleanly(cfg, List(
      AvlOp("Insert", key(4), Some(value(4))),
      AvlOp("RemoveIfExists", key(4), None), // present
      AvlOp("RemoveIfExists", key(7), None)  // absent — must not fail the batch
    ), List(0, 1, 2))
  }

  test("UpdateLongBy reaches scrypto and proves; delta is a decimal STRING") {
    val fixedCfg = TreeConfig(keyLength = 32, valueLengthOpt = Some(8))
    val zero = "0" * 16
    provesCleanly(fixedCfg, List(
      AvlOp("Insert", key(4), Some(zero)),
      AvlOp("UpdateLongBy", key(4), None, Some("50"))
    ), List(0, 1))
  }

  test("UpdateLongBy carries an i64 boundary that a JSON number would round") {
    // A JSON *number* is an IEEE-754 double in every JS consumer (ergots'
    // JSON.parse); 9223372036854775806 has no exact double, so the boundary
    // would arrive rounded. The decimal string is why `delta` is a String.
    val boundary = Long.MaxValue - 1 // 9223372036854775806
    val fixedCfg = TreeConfig(keyLength = 32, valueLengthOpt = Some(8))
    val zero = "0" * 16
    provesCleanly(fixedCfg, List(
      AvlOp("Insert", key(4), Some(zero)),
      AvlOp("UpdateLongBy", key(4), None, Some(boundary.toString))
    ), List(0, 1))
    assertEquals(boundary.toString.toLong, boundary, "decimal string round-trips exactly")
    assertNotEquals(boundary.toString.toDouble.toLong, boundary,
      "a JSON number would NOT round-trip — this is the reason delta is a string")
  }

  test("UnknownModification has a FIXED zero-length key and ignores the caller's") {
    // scrypto's UnknownModification is a case OBJECT: `key` is a val on the
    // singleton (an empty Array[Byte]), so the entry's key_hex never reaches it.
    // Pinned empirically against scrypto_2.12-3.0.0.
    val op = AvlProofGenerator.toOperation(AvlOp("UnknownModification", key(4), None))
    assert(op eq scorex.crypto.authds.avltree.batch.UnknownModification,
      "must be the singleton, not a per-key instance")
    assertEquals(op.key.length, 0, "scrypto's UnknownModification.key() is zero-length")
    assertEquals(scorex.util.encode.Base16.encode(op.key), "",
      "…so its key hex is the empty string, NOT the caller's key")
  }

  test("UnknownModification's prover outcome, pinned") {
    // Its fixed zero-length key sorts below the tree's negative-infinity key, so
    // scrypto REJECTS it — it is the one tag in the vocabulary that cannot produce
    // a proof. Pinned (type + message) so a scrypto change is loud rather than a
    // silently different expectation for whoever first authors an UnknownModification
    // vector. No vendored fixture uses this tag today.
    val ex = intercept[IllegalArgumentException] {
      AvlProofGenerator.generateCycles(cfg,
        Nil, List(AvlOp("UnknownModification", key(4), None)), List(0))
    }
    assert(ex.getMessage.contains("is less than -inf"),
      s"unexpected scrypto rejection reason: ${ex.getMessage}")
  }
}
