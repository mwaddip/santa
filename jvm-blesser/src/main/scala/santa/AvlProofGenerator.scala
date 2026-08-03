package santa

import io.circe.Json
import io.circe.parser.{parse => parseJson}
import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Lookup, Remove}
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.util.encode.Base16

/** JVM-canonical AVL proof generation. Wraps the reference `BatchAVLProver`
  * so external callers can produce canonical proof bytes for a given tree
  * state + operation list without syncing a full JVM node.
  *
  * Used by the `POST /avl-proof` oracle endpoint and by the block-tier
  * authoring flow (tree dump from a UTXO node → JVM prover → canonical
  * `adProofs.proofBytes`). */
object AvlProofGenerator {

  /** Input shape for /avl-proof. */
  case class TreeConfig(keyLength: Int, valueLengthOpt: Option[Int])
  case class Kv(key: String, value: String)

  /** One AVL operation. `delta` is a decimal STRING (parsed with `.toLong`) so an
    * i64-boundary `UpdateLongBy` survives JSON — a JSON number would lose precision.
    * Defaulted so every pre-existing call site keeps compiling. */
  case class AvlOp(kind: String, key: String, value: Option[String],
                   delta: Option[String] = None)

  private def dec(hex: String): Array[Byte] =
    Base16.decode(hex).getOrElse(sys.error(s"invalid hex: ${hex.take(32)}..."))

  private def hex(b: Array[Byte]): String = Base16.encode(b)

  /** Generate canonical proof bytes for a tree + operations. */
  def generate(config: TreeConfig,
               initialEntries: List[Kv],
               operations: List[AvlOp]): (String, String, String) = {

    val tree = new BatchAVLProver[Digest32, Blake2b256.type](
      keyLength = config.keyLength,
      valueLengthOpt = config.valueLengthOpt
    )

    // Seed the tree with initial entries
    for (kv <- initialEntries) {
      tree.performOneOperation(
        Insert(ADKey @@ dec(kv.key), ADValue @@ dec(kv.value))
      ).get
    }
    // Commit the seed state (generateProof resets tree internals so
    // subsequent operations produce a correct proof over the committed state)
    if (initialEntries.nonEmpty) {
      tree.generateProof()
    }

    // Perform the operations we want a proof for
    for (op <- operations) {
      tree.performOneOperation(toOperation(op)).get
    }

    val proofBytes = tree.generateProof()
    val treeDigest = tree.digest // 33 bytes: 32-byte hash + 1-byte height

    // blake2b256(proofBytes) — the wire digest
    val proofDigest = Blake2b256(proofBytes)

    (hex(proofBytes), hex(proofDigest), hex(treeDigest))
  }

  /** Multi-cycle proof generation. Performs `operations` in order and captures
    * `(generateProof(), digest)` immediately after the operation whose index
    * appears in `genProofAfter` (ascending, 0-based). Returns parallel lists.
    *
    * The cycle boundary is load-bearing: `generateProof()` resets the prover's
    * modification tracking, so a proof taken mid-sequence is NOT recoverable
    * from a one-shot run. This is the shape ergots' prover fixtures exercise.
    */
  def generateCycles(config: TreeConfig,
                     initialEntries: List[Kv],
                     operations: List[AvlOp],
                     genProofAfter: List[Int]): (List[String], List[String]) = {

    require(genProofAfter == genProofAfter.sorted.distinct,
      s"genProofAfter must be ascending and distinct: $genProofAfter")
    genProofAfter.foreach { i =>
      require(i >= 0 && i < operations.size,
        s"genProofAfter index $i out of range for ${operations.size} operations")
    }

    val tree = new BatchAVLProver[Digest32, Blake2b256.type](
      keyLength = config.keyLength,
      valueLengthOpt = config.valueLengthOpt
    )
    for (kv <- initialEntries) {
      tree.performOneOperation(Insert(ADKey @@ dec(kv.key), ADValue @@ dec(kv.value))).get
    }
    if (initialEntries.nonEmpty) tree.generateProof()

    val triggers = genProofAfter.toSet
    val proofs = List.newBuilder[String]
    val digests = List.newBuilder[String]

    operations.zipWithIndex.foreach { case (op, i) =>
      tree.performOneOperation(toOperation(op)).get
      if (triggers.contains(i)) {
        proofs += hex(tree.generateProof())
        digests += hex(tree.digest)
      }
    }
    (proofs.result(), digests.result())
  }

  /** Shared op decoding — the full eight-tag `santa-authds/v1` operation vocabulary
    * (docs/specs/authds-tier.md "Operation encoding").
    *
    * `UnknownModification` is a case OBJECT with a FIXED, zero-length `key()`; it
    * ignores the caller's key entirely, so the entry's `key_hex` is deliberately not
    * passed. See `AvlProofGeneratorTest` for the pin. */
  private[santa] def toOperation(op: AvlOp): scorex.crypto.authds.avltree.batch.Operation = {
    import scorex.crypto.authds.avltree.batch.{
      InsertOrUpdate, RemoveIfExists, UnknownModification, Update, UpdateLongBy}
    def value: Array[Byte] =
      dec(op.value.getOrElse(sys.error(s"${op.kind} requires value")))
    op.kind match {
      case "Insert"         => Insert(ADKey @@ dec(op.key), ADValue @@ value)
      case "Update"         => Update(ADKey @@ dec(op.key), ADValue @@ value)
      case "InsertOrUpdate" => InsertOrUpdate(ADKey @@ dec(op.key), ADValue @@ value)
      case "Remove"         => Remove(ADKey @@ dec(op.key))
      case "RemoveIfExists" => RemoveIfExists(ADKey @@ dec(op.key))
      case "Lookup"         => Lookup(ADKey @@ dec(op.key))
      case "UpdateLongBy"   =>
        UpdateLongBy(ADKey @@ dec(op.key),
          op.delta.getOrElse(sys.error("UpdateLongBy requires delta")).toLong)
      case "UnknownModification" => UnknownModification
      case other            => sys.error(s"unknown operation kind: $other")
    }
  }

  /** Decode a `santa-authds/v1` `avl_prove` entry (snake_case `settings` /
    * `payload`) and run the prover. Main scope on purpose: the vendored blesser
    * and the rudolph control arm share this ONE decode path, so a control
    * divergence can never mean "the vector builder and the runner disagree
    * about decoding". */
  def deriveFromEntry(entry: io.circe.Json): (List[String], List[String]) = {
    val c = entry.hcursor
    val s = c.downField("settings")
    val cfg = TreeConfig(
      s.get[Int]("key_length").toOption.getOrElse(sys.error("settings.key_length missing")),
      s.get[Option[Int]]("value_length").toOption.flatten
    )
    val ops = c.downField("payload").downField("operations").values
      .getOrElse(sys.error("payload.operations missing")).toList.map { j =>
        val o = j.hcursor
        AvlOp(
          o.get[String]("tag").toOption.getOrElse(sys.error("op.tag missing")),
          o.get[String]("key_hex").toOption.getOrElse(sys.error("op.key_hex missing")),
          o.get[String]("value_hex").toOption,
          o.get[String]("delta").toOption
        )
      }
    val cycles = c.downField("payload").get[List[Int]]("gen_proof_after")
      .getOrElse(sys.error("payload.gen_proof_after missing"))
    generateCycles(cfg, Nil, ops, cycles)
  }

  // ── JSON adapter ──────────────────────────────────────────────────────

  private def strField(c: io.circe.HCursor, name: String): String =
    c.get[String](name).fold(e => sys.error(s"missing '$name': $e"), identity)

  /** Parse a /avl-proof request body → generate → result JSON. */
  def fromJson(body: String): Json = {
    val j = parseJson(body).fold(e => sys.error(s"bad JSON: ${e.getMessage}"), (j: Json) => j)
    val c = j.hcursor

    val keyLen = c.downField("key_length").as[Int].getOrElse(sys.error("missing key_length"))
    val valLen = c.downField("value_length").as[Option[Int]]
      .fold(e => sys.error(s"value_length: $e"), identity)

    val config = TreeConfig(keyLen, valLen)

    val initial: List[Kv] = c.downField("initial_entries").as[List[Json]]
      .getOrElse(Nil)
      .map { e =>
        Kv(
          e.hcursor.get[String]("key").fold(e => sys.error(s"initial key: $e"), identity),
          e.hcursor.get[String]("value").fold(e => sys.error(s"initial value: $e"), identity)
        )
      }

    val ops: List[AvlOp] = c.downField("operations").as[List[Json]]
      .getOrElse(Nil)
      .map { e =>
        AvlOp(
          e.hcursor.get[String]("kind").fold(e => sys.error(s"op kind: $e"), identity),
          e.hcursor.get[String]("key").fold(e => sys.error(s"op key: $e"), identity),
          e.hcursor.get[String]("value").toOption,
          // decimal string (UpdateLongBy) — same convention as santa-authds/v1's `delta`
          e.hcursor.get[String]("delta").toOption
        )
      }

    val genProofAfter: List[Int] = c.downField("gen_proof_after").as[List[Int]].getOrElse(Nil)

    if (genProofAfter.nonEmpty) {
      val (proofs, digests) = generateCycles(config, initial, ops, genProofAfter)
      Json.obj(
        "proofs"  -> Json.arr(proofs.map(Json.fromString): _*),
        "digests" -> Json.arr(digests.map(Json.fromString): _*)
      )
    } else {
      val (proofBytes, proofDigest, treeDigest) = generate(config, initial, ops)
      Json.obj(
        "proof_bytes"  -> Json.fromString(proofBytes),
        "proof_digest" -> Json.fromString(proofDigest),
        "tree_digest"  -> Json.fromString(treeDigest)
      )
    }
  }
}
