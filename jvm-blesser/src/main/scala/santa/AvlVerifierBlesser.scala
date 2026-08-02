package santa

import scorex.crypto.authds.avltree.batch.BatchAVLVerifier
import scorex.crypto.authds.{ADDigest, ADKey, ADValue, SerializedAdProof}
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
    val results = operations.map { op =>
      verifier.performOneOperation(toOperation(op)) match {
        case scala.util.Success(Some(v)) => OpResult(ok = true, Some(hex(v)))
        case scala.util.Success(None)    => OpResult(ok = true, None)
        case scala.util.Failure(_)       => OpResult(ok = false, None)
      }
    }

    // Level 3 — final digest, None if any operation poisoned the verifier.
    VerifyOutcome(proofAccepted = true, results, verifier.digest.map(hex))
  }

  private def toOperation(op: AvlProofGenerator.AvlOp): scorex.crypto.authds.avltree.batch.Operation = {
    import scorex.crypto.authds.avltree.batch.{Insert, Lookup, Remove, Update}
    op.kind match {
      case "Insert" =>
        Insert(ADKey @@ dec(op.key), ADValue @@ dec(op.value.getOrElse(sys.error("Insert requires value"))))
      case "Update" =>
        Update(ADKey @@ dec(op.key), ADValue @@ dec(op.value.getOrElse(sys.error("Update requires value"))))
      case "Lookup" => Lookup(ADKey @@ dec(op.key))
      case "Remove" => Remove(ADKey @@ dec(op.key))
      case other    => sys.error(s"unknown operation kind: $other")
    }
  }

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
          o.get[String]("value_hex").toOption
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
