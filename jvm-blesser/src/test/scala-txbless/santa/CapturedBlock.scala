package santa

// ─────────────────────────────────────────────────────────────────────────────
// CapturedBlock — assemble + bless captured block-tier seeds (docs/findings/) into
// `santa-block/v1` vector envelopes, driving the JVM oracle BlockEngine (which
// wraps ergo-core `ErgoState.execTransactions`).
//
// One vector file per seed → target/block-vectors/<slug>.json. Seeds read the
// proof-complete `block-<h>-full.json` captures (real proofBytes, node-verified
// blake2b256(proofBytes) == header.adProofsRoot); the engine's proofs arm verifies
// them again at bless time (ADProofs.verify replaying the state changes from
// parent_digest to header.stateRoot). A proofless capture cannot bless: the engine
// rejects it, which trips the FAIL-LOUD gate below.
//
// ── Parameter table extraction ────────────────────────────────────────────────
// Parameters come from the epoch-boundary block (the last block at height
// N * EpochLength, where N*EpochLength <= seed.height). Its extension carries
// 00KK → 4-byte big-endian Int fields (SystemParametersPrefix = 0x00).
// We decode the extension JSON via Extension.jsonDecoder (ApiCodecs), then call
// Parameters.parseExtension(height, extension) — the real ergo-core reader.
// This path is verified at bless-time: table("4") == 1000000 (maxBlockCost),
// table("123") == 4 (blockVersion). Falls back: if parseExtension throws, we
// report the exception and re-raise (never fabricate).
//
// ── FAIL-LOUD-on-valid:false ──────────────────────────────────────────────────
// Captured history is by definition valid (these blocks are on-chain). If
// BlockEngine returns valid==false for a captured seed, that is a capture /
// recipe bug — never a legitimate vector. We sys.error; we do not emit it.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import io.circe.parser.parse
import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.settings.Parameters
import scorex.util.ModifierId

object CapturedBlock {

  /** Full v6: activated=3, ergoTree=3 (block-version-4 blocks → v3 scripts). */
  private val Activated = 3
  private val ErgoTreeV = 3

  /** Provenance stamp for the envelope `blessed_by`. Per-entry `source` is
    * `testnet:<seed.dir>@<height>` (captured provenance). */
  private val BlessedBy = "jvm:ergo-core-6.0.7-execTransactions-model"

  /** docs/findings/ lives at repo root; blesser runs from jvm-blesser/ cwd. */
  private val FindingsDir = "../docs/findings"

  /** One block seed. epochBoundary = the height of the epoch-boundary block
    * whose extension carries the in-force system parameters; description = the
    * entry's prose, why the block is in the corpus. */
  private final case class BlockSeed(slug: String, dir: String, height: Int, epochBoundary: Int,
                                     description: String)

  // powhit-return-type-28474 is EXCLUDED: no canonical proof exists yet — the rust
  // AVL prover serializes its lone data-input Lookup non-canonically (verifies, but
  // blake2b256 != adProofsRoot; see docs/findings/testnet-powhit-return-type/
  // ADPROOF-FINDING.md). It joins when a JVM-sourced canonical proof lands.
  //
  // epoch-boundary-2560 is capture material, not a divergence seed: the only seed at
  // an epoch boundary (2560 % 128 == 0), so its accept arm exercises the epochStarts
  // path — the version gate actually RUNS and passes (catches over-strict gating) —
  // and it donates the version-gate mutation, the one class only meaningful at a
  // boundary. In-force params for validating it come from the PREVIOUS boundary, 2432.
  private val Seeds: Seq[BlockSeed] = Seq(
    BlockSeed("bigint-downcast-2666", "testnet-bigint-downcast-v3", 2666, 2560,
      "Testnet block 2666, captured with its ADProofs. Transaction 1's input 0 runs `Downcast(BigInt, SLong)` " +
      "on 67,500,000,000 under ErgoTree V3; the value fits a Long, so the downcast returns it and the block " +
      "is VALID. The network (JVM sigma-state 6.0.3) accepted the block; ergo-node-rust, riding the sigma-rust " +
      "fork, rejected it (`cannot downcast BigInt(BigInt256(67500000000)) to Long`) and stopped at 2665. It " +
      "is also the donor of the block tier's mid-epoch mutations."),
    BlockSeed("deserialize-context-111927", "testnet-deserialize-context", 111927, 111872,
      "Testnet block 111,927, captured with its ADProofs. Transaction 2's input 0 spends a V3 box whose tree " +
      "is `if (upcast(HEIGHT) < SELF.R7.get + 50) { ... deserializeContext(0) } else { ... }`. At this height " +
      "the condition is false, so the live branch never reads context variable 0, and the input's empty proof " +
      "and empty extension reduce to `sigmaProp(true)`: VALID. The network (JVM 6.0.x) accepted it; " +
      "ergo-node-rust, riding the sigma-rust fork, rejected it and stopped at 111,926, because its eager " +
      "substitution evaluated the dead branch's `deserializeContext(0)` over the absent variable."),
    BlockSeed("atleast-degenerate-bound-184137", "testnet-atleast-degenerate-bound", 184137, 184064,
      "Testnet block 184,137, captured with its ADProofs while the spent box was still unspent. Transaction " +
      "1's input 0 spends a V0 self-replicating contract (10 ERG, R4-R7) whose script has `atLeast(1, " +
      "conditions)` over an EMPTY `Coll[SigmaProp]`. The JVM reduces the degenerate bound to `FalseProp`, " +
      "which the surrounding logic accommodates, so the spend is VALID. The network (JVM 6.0.x) accepted " +
      "it; ergo-node-rust, riding the sigma-rust fork, rejected it (`Atleast: bound 1 > input size 0`) and " +
      "stopped at 184,136."),
    BlockSeed("epoch-boundary-2560", "testnet-epoch-boundary-2560", 2560, 2432,
      "Testnet block 2560, captured with its ADProofs: an epoch boundary (2560 % 128 == 0), and the corpus's " +
      "only one. At an epoch's first block the node compares the epoch's declared block version with the " +
      "header's (`exBlockVersion`); it passes here, so an implementation that applies the gate wrongly at a " +
      "boundary fails this block. The in-force parameters come from the previous boundary, 2432. It is the " +
      "donor of the version-gate mutation."))

  // ── file / JSON helpers ────────────────────────────────────────────────────

  private def readFile(p: String): String = {
    val src = scala.io.Source.fromFile(p)
    try src.mkString finally src.close()
  }

  private def parseFile(p: String): Json =
    parse(readFile(p)).fold(e => sys.error(s"CapturedBlock: parse $p: $e"), identity)

  // ── parameter table extraction ─────────────────────────────────────────────

  /** Decode the epoch boundary block's extension and extract the in-force
    * system parameters via Parameters.parseExtension (the real ergo-core
    * reader). Returns (Map[Int,Int], Json) — the Scala map for BlockEngine
    * and the JSON object (string-keyed decimal) for the vector entry. */
  private def extractParams(epochBlockJson: Json, epochHeight: Int): (Map[Int, Int], Json) = {
    val extJson = epochBlockJson.hcursor.downField("extension").focus
      .getOrElse(sys.error(s"CapturedBlock: epoch block has no extension field"))

    val extension = extJson.as[Extension](Extension.jsonDecoder)
      .fold(e => sys.error(s"CapturedBlock: Extension decode failed: $e"), identity)

    val params = Parameters.parseExtension(epochHeight, extension)
      .fold(e => sys.error(s"CapturedBlock: Parameters.parseExtension failed: $e"), identity)

    val tableScala: Map[Int, Int] = params.parametersTable.map { case (k, v) => k.toInt -> v }

    // Verify the two known anchors before emitting.
    val maxBlockCostId = 4
    val blockVersionId = 123
    val gotMaxCost = tableScala.getOrElse(maxBlockCostId,
      sys.error(s"CapturedBlock: param id $maxBlockCostId (maxBlockCost) missing from table"))
    val gotBlockVer = tableScala.getOrElse(blockVersionId,
      sys.error(s"CapturedBlock: param id $blockVersionId (blockVersion) missing from table"))
    if (gotMaxCost != 1000000)
      sys.error(s"CapturedBlock: maxBlockCost cross-check FAILED: expected 1000000 got $gotMaxCost")
    if (gotBlockVer != 4)
      sys.error(s"CapturedBlock: blockVersion cross-check FAILED: expected 4 got $gotBlockVer")

    val tableJson = Json.obj(tableScala.toSeq.sortBy(_._1).map { case (k, v) =>
      k.toString -> Json.fromInt(v)
    }: _*)

    (tableScala, tableJson)
  }

  // ── header window helpers ──────────────────────────────────────────────────

  /** Parse the headers window file (ascending array). Return sorted NEWEST-FIRST
    * and extract the parent_digest (stateRoot of the newest header = H-1). */
  private def headersWindow(seedDir: String, height: Int): (Vector[Json], String) = {
    // Filename: headers-<H-10>-<H-1>.json
    val lo = height - 10
    val hi = height - 1
    val path = s"$seedDir/headers-$lo-$hi.json"
    val arr = parseFile(path).asArray
      .getOrElse(sys.error(s"CapturedBlock: headers file is not a JSON array: $path"))

    // Sort by height descending (newest-first).
    val sorted = arr.sortBy { h =>
      -(h.hcursor.get[Int]("height").getOrElse(
        sys.error(s"CapturedBlock: header missing height field in $path")))
    }
    if (sorted.isEmpty)
      sys.error(s"CapturedBlock: empty headers window in $path")

    // parent_digest = stateRoot of the newest header (height H-1)
    val parentDigest = sorted.head.hcursor.get[String]("stateRoot")
      .fold(e => sys.error(s"CapturedBlock: newest header has no stateRoot: $e"), identity)

    (sorted, parentDigest)
  }

  // ── box bytes collection ───────────────────────────────────────────────────

  /** Collect all box-*-bytes.json files in seedDir as {boxId, bytes} entries,
    * sorted by boxId for determinism. */
  private def collectBoxBytes(seedDir: String): Vector[Json] = {
    val dir = new java.io.File(seedDir)
    val files = Option(dir.listFiles).getOrElse(Array.empty[java.io.File])
      .filter(f => f.getName.startsWith("box-") && f.getName.endsWith("-bytes.json"))
      .sortBy(_.getName)  // deterministic pre-sort; final sort by boxId below

    val entries = files.map { f =>
      val j = parseFile(f.getPath)
      val boxId = j.hcursor.get[String]("boxId")
        .fold(e => sys.error(s"CapturedBlock: box file ${f.getName} has no boxId: $e"), identity)
      val bytes = j.hcursor.get[String]("bytes")
        .fold(e => sys.error(s"CapturedBlock: box file ${f.getName} has no bytes: $e"), identity)
      boxId -> Json.obj("boxId" -> Json.fromString(boxId), "bytes" -> Json.fromString(bytes))
    }

    // Final sort by boxId string for determinism across runs.
    entries.sortBy(_._1).map(_._2).toVector
  }

  // ── per-seed assembly ──────────────────────────────────────────────────────

  /** Read one seed, bless it, and return (slug, envelope Json).
    * FAIL LOUD on oracle valid==false (captured ⇒ valid; a reject = recipe bug —
    * including a proofless/tampered capture, which the engine's proofs arm rejects). */
  private def blessSeed(seed: BlockSeed): (String, Json) = {
    val seedDir  = s"$FindingsDir/${seed.dir}"
    val captured = parseFile(s"$seedDir/block-${seed.height}-full.json")
    val epochBlk = parseFile(s"$seedDir/epoch-block-${seed.epochBoundary}.json")

    // The capture's transactions are node-API JSON; the vector carries the section's BYTES
    // (runner-contract-block §2), derived through the JVM writer and checked against everything the
    // capture states about the section (BlockSectionBytes.fromNodeJson).
    val block: Json = {
      val c = captured.hcursor
      val section = BlockSectionBytes.fromNodeJson(
        c.downField("blockTransactions").focus.getOrElse(sys.error(s"CapturedBlock[${seed.slug}]: no blockTransactions")),
        c.downField("header").focus.getOrElse(sys.error(s"CapturedBlock[${seed.slug}]: no header")),
        s"CapturedBlock[${seed.slug}]")
      c.downField("blockTransactions").set(Json.fromString(section)).top
        .getOrElse(sys.error(s"CapturedBlock[${seed.slug}]: blockTransactions set failed"))
    }

    val (headerWindow, parentDigest) = headersWindow(seedDir, seed.height)
    val (tableScala, tableJson)      = extractParams(epochBlk, seed.epochBoundary)
    val boxEntries                   = collectBoxBytes(seedDir)

    // Decode box bytes for BlockEngine: Vector[(boxId, Array[Byte])].
    val boxesBytes: Vector[(String, Array[Byte])] = boxEntries.map { entry =>
      val id  = entry.hcursor.get[String]("boxId").toOption.get
      val hex = entry.hcursor.get[String]("bytes").toOption.get
      id -> scorex.util.encode.Base16.decode(hex)
              .getOrElse(sys.error(s"CapturedBlock[${seed.slug}]: hex decode failed for box $id"))
    }

    // ── drive the oracle ─────────────────────────────────────────────────────
    val verdict = santa.runner.BlockEngine.validate(block, headerWindow, tableScala, boxesBytes, parentDigest)
    if (!verdict.valid)
      sys.error(s"CapturedBlock[${seed.slug}]: oracle REJECTED a captured (on-chain) block — " +
        s"this is a capture/recipe bug, not a vector. reason=${verdict.reason.getOrElse("<none>")}")
    val cost = verdict.cost.getOrElse(
      sys.error(s"CapturedBlock[${seed.slug}]: valid:true but no cost returned"))
    val postDigest = verdict.postDigest.getOrElse(
      sys.error(s"CapturedBlock[${seed.slug}]: valid:true but no post_digest returned"))

    val source = s"testnet:${seed.dir}@${seed.height}"
    require(seed.description.nonEmpty, s"CapturedBlock[${seed.slug}]: every seed states why it is here")
    val entry  = Json.obj(
      "name"          -> Json.fromString(seed.slug),
      "source"        -> Json.fromString(source),
      "description"   -> Json.fromString(seed.description),
      "parent_digest" -> Json.fromString(parentDigest),
      "headers"       -> Json.arr(headerWindow: _*),
      "parameters"    -> Json.obj("table" -> tableJson),
      "block"         -> block,
      "boxes"         -> Json.arr(boxEntries: _*),
      "version"       -> Json.obj(
        "activated"  -> Json.fromInt(Activated),
        "ergoTree"   -> Json.fromInt(ErgoTreeV)),
      "expected"      -> Json.obj(
        "valid"       -> Json.fromBoolean(true),
        "post_digest" -> Json.fromString(postDigest),
        "cost"        -> Json.fromLong(cost),
        "reason"      -> Json.Null))

    val op = s"block:captured:${seed.slug}"
    val envelope = Json.obj(
      "schema"     -> Json.fromString("santa-block/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString(BlessedBy),
      "entries"    -> Json.arr(entry))

    (seed.slug, envelope)
  }

  // ── public API ─────────────────────────────────────────────────────────────

  /** Bless all seeds. Returns Seq of (slug, envelope). Order follows Seeds. */
  def blessAll(): Seq[(String, Json)] =
    Seeds.map(blessSeed)

  /** Persist blessed vectors → target/block-vectors/<slug>.json.
    * Fails loud on slug collision (would silently drop an entry). */
  def writeVectors(results: Seq[(String, Json)], baseDir: java.nio.file.Path): Unit = {
    val slugs = results.map(_._1)
    val collisions = slugs.groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("CapturedBlock.writeVectors: slug collision would silently drop entries — " +
        collisions.mkString(", "))

    val liveDir = baseDir.resolve("block-vectors")
    results.foreach { case (slug, json) =>
      java.nio.file.Files.createDirectories(liveDir)
      val path = liveDir.resolve(s"$slug.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
