package santa

import scorex.crypto.authds.avltree.batch.BatchAVLVerifier
import scorex.crypto.authds.{ADDigest, SerializedAdProof}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.encode.Base16

/** JVM-canonical AVL batch verification, blessing `santa-authds/v1`'s
  * `avl_verify` kind.
  *
  * Three levels, deliberately kept apart (see docs/specs/authds-tier.md):
  *   1. `proofAccepted` — `verifier.digest.isDefined` BEFORE any operation.
  *      scrypto's constructor does not throw; it leaves the tree unbuilt.
  *   2. per-operation `Try[Option[ADValue]]` — Failure / Some / None.
  *   3. `newDigestHex` — `digest(): Option`, None once the verifier is poisoned.
  */
object AvlVerifierBlesser {

  case class VerifyConfig(keyLength: Int,
                          valueLengthOpt: Option[Int],
                          maxNumOperations: Option[Int],
                          maxDeletes: Option[Int])

  case class OpResult(ok: Boolean, value: Option[String])
  case class VerifyOutcome(proofAccepted: Boolean,
                           results: List[OpResult],
                           newDigestHex: Option[String])

  private def dec(hex: String): Array[Byte] =
    Base16.decode(hex).getOrElse(sys.error(s"invalid hex: ${hex.take(32)}..."))
  private def hex(b: Array[Byte]): String = Base16.encode(b)

  def verify(config: VerifyConfig,
             startingDigestHex: String,
             proofHex: String,
             operations: List[AvlProofGenerator.AvlOp]): VerifyOutcome = {

    val verifier = new BatchAVLVerifier[Digest32, Blake2b256.type](
      startingDigest   = ADDigest @@ dec(startingDigestHex),
      proof            = SerializedAdProof @@ dec(proofHex),
      keyLength        = config.keyLength,
      valueLengthOpt   = config.valueLengthOpt,
      maxNumOperations = config.maxNumOperations,
      maxDeletes       = config.maxDeletes
    )

    // Level 1 — anchoring, evaluated BEFORE any operation is attempted.
    if (verifier.digest.isEmpty) return VerifyOutcome(proofAccepted = false, Nil, None)

    // Level 2 — one entry per operation. Once an operation fails the verifier is
    // poisoned and every subsequent operation fails too; we still record one
    // entry per operation so the array length always matches the payload.
    //
    // `toOperation` is evaluated OUTSIDE the Try on purpose: `performOneOperation`
    // captures what scrypto rejects (a key below -inf, an absent key, a negative
    // UpdateLongBy result), but a decoder error — unknown tag, non-hex key,
    // `UpdateLongBy` with no `delta` — means the VECTOR is malformed, not that an
    // implementation failed an operation. Blessing that as `{ok: false}` would ship
    // an authoring bug as a conformance expectation, so it propagates loudly.
    val results = operations.map { op =>
      val operation = toOperation(op)
      verifier.performOneOperation(operation) match {
        case scala.util.Success(Some(v)) => OpResult(ok = true, Some(hex(v)))
        case scala.util.Success(None)    => OpResult(ok = true, None)
        case scala.util.Failure(_)       => OpResult(ok = false, None)
      }
    }

    // Level 3 — final digest, None if any operation poisoned the verifier.
    VerifyOutcome(proofAccepted = true, results, verifier.digest.map(hex))
  }

  /** The full eight-tag `santa-authds/v1` operation vocabulary, delegated to the
    * prover's decoder rather than re-implemented: prove and verify hand scrypto
    * the same `Operation` values, so a tag that means one thing to the prover and
    * another to the verifier is not a shape this tier can express. One vocabulary,
    * one place — the same reason `deriveFromEntry` is the one decode path. */
  private def toOperation(op: AvlProofGenerator.AvlOp): scorex.crypto.authds.avltree.batch.Operation =
    AvlProofGenerator.toOperation(op)

  /** Decode a `santa-authds/v1` `avl_verify` entry (snake_case `settings` /
    * `payload`) and verify. Shared by the vendored blesser and the rudolph
    * control arm so there is exactly one decode path. */
  def deriveFromEntry(entry: io.circe.Json): VerifyOutcome = {
    val c = entry.hcursor
    val s = c.downField("settings")
    val cfg = VerifyConfig(
      s.get[Int]("key_length").toOption.getOrElse(sys.error("settings.key_length missing")),
      s.get[Option[Int]]("value_length").toOption.flatten,
      s.get[Option[Int]]("max_num_operations").toOption.flatten,
      s.get[Option[Int]]("max_deletes").toOption.flatten
    )
    val p = c.downField("payload")
    val ops = p.downField("operations").values
      .getOrElse(sys.error("payload.operations missing")).toList.map { j =>
        val o = j.hcursor
        AvlProofGenerator.AvlOp(
          o.get[String]("tag").toOption.getOrElse(sys.error("op.tag missing")),
          o.get[String]("key_hex").toOption.getOrElse(sys.error("op.key_hex missing")),
          o.get[String]("value_hex").toOption,
          // decimal STRING (UpdateLongBy). Dropping it here would make every
          // `UpdateLongBy` entry die in toOperation with "requires delta".
          o.get[String]("delta").toOption
        )
      }
    verify(
      cfg,
      p.get[String]("starting_digest_hex").toOption.getOrElse(sys.error("payload.starting_digest_hex missing")),
      p.get[String]("proof_hex").toOption.getOrElse(sys.error("payload.proof_hex missing")),
      ops
    )
  }
}
