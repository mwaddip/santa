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
  case class AvlOp(kind: String, key: String, value: Option[String])

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
      val avlOp = op.kind match {
        case "Insert" =>
          val v = op.value.getOrElse(sys.error("Insert requires value"))
          Insert(ADKey @@ dec(op.key), ADValue @@ dec(v))
        case "Lookup"  => Lookup(ADKey @@ dec(op.key))
        case "Remove"  => Remove(ADKey @@ dec(op.key))
        case other     => sys.error(s"unknown operation kind: $other")
      }
      tree.performOneOperation(avlOp).get
    }

    val proofBytes = tree.generateProof()
    val treeDigest = tree.digest // 33 bytes: 32-byte hash + 1-byte height

    // blake2b256(proofBytes) — the wire digest
    val proofDigest = Blake2b256(proofBytes)

    (hex(proofBytes), hex(proofDigest), hex(treeDigest))
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
          e.hcursor.get[String]("value").toOption
        )
      }

    val (proofBytes, proofDigest, treeDigest) = generate(config, initial, ops)
    Json.obj(
      "proof_bytes"  -> Json.fromString(proofBytes),
      "proof_digest" -> Json.fromString(proofDigest),
      "tree_digest"  -> Json.fromString(treeDigest)
    )
  }
}
