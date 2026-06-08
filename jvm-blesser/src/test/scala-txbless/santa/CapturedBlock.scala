package santa

// ─────────────────────────────────────────────────────────────────────────────
// CapturedBlock — assemble + bless captured block-tier seeds (docs/findings/) into
// `santa-block/v1` vector envelopes, driving the JVM oracle BlockEngine (which
// wraps ergo-core `ErgoState.execTransactions`).
//
// One vector file per seed → target/block-vectors/<slug>.json (proof-complete),
// or target/block-vectors-PROOFLESS/<slug>.json (adProofs null / empty proofBytes).
// Block 2666 ships today in PROOFLESS quarantine; it moves to the live path only
// once the ADProofs file lands.
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
// ── PROOFLESS quarantine ──────────────────────────────────────────────────────
// If adProofs is null or proofBytes is absent/empty, the vector is written to
// target/block-vectors-PROOFLESS/ and a loud banner is printed. No file ever
// moves from PROOFLESS to block-vectors/ automatically — that requires a manual
// re-bless with the completed proof data.
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
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-execTransactions-model"

  /** docs/findings/ lives at repo root; blesser runs from jvm-blesser/ cwd. */
  private val FindingsDir = "../docs/findings"

  /** One block seed. epochBoundary = the height of the epoch-boundary block
    * whose extension carries the in-force system parameters. */
  private final case class BlockSeed(slug: String, dir: String, height: Int, epochBoundary: Int)

  private val Seeds: Seq[BlockSeed] = Seq(
    BlockSeed("bigint-downcast-2666", "testnet-bigint-downcast-v3", 2666, 2560))

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

  // ── proofless detection ────────────────────────────────────────────────────

  /** Returns true if the block is proof-complete (adProofs non-null with a
    * non-empty proofBytes string). */
  private def isProofComplete(blockJson: Json): Boolean = {
    val adProofs = blockJson.hcursor.downField("adProofs").focus
    adProofs match {
      case None => false
      case Some(j) if j.isNull => false
      case Some(j) =>
        j.hcursor.get[String]("proofBytes").fold(_ => false, s => s.nonEmpty)
    }
  }

  // ── per-seed assembly ──────────────────────────────────────────────────────

  /** Read one seed, bless it, and return (slug, isQuarantined, envelope Json).
    * FAIL LOUD on oracle valid==false (captured ⇒ valid; a reject = recipe bug). */
  private def blessSeed(seed: BlockSeed): (String, Boolean, Json) = {
    val seedDir  = s"$FindingsDir/${seed.dir}"
    val block    = parseFile(s"$seedDir/block-${seed.height}.json")
    val epochBlk = parseFile(s"$seedDir/epoch-block-${seed.epochBoundary}.json")

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
    val verdict = santa.runner.BlockEngine.validate(block, headerWindow, tableScala, boxesBytes)
    if (!verdict.valid)
      sys.error(s"CapturedBlock[${seed.slug}]: oracle REJECTED a captured (on-chain) block — " +
        s"this is a capture/recipe bug, not a vector. reason=${verdict.reason.getOrElse("<none>")}")
    val cost = verdict.cost.getOrElse(
      sys.error(s"CapturedBlock[${seed.slug}]: valid:true but no cost returned"))
    val postDigest = verdict.postDigest.getOrElse(
      sys.error(s"CapturedBlock[${seed.slug}]: valid:true but no post_digest returned"))

    val source = s"testnet:${seed.dir}@${seed.height}"
    val entry  = Json.obj(
      "name"          -> Json.fromString(seed.slug),
      "source"        -> Json.fromString(source),
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

    val quarantined = !isProofComplete(block)
    (seed.slug, quarantined, envelope)
  }

  // ── public API ─────────────────────────────────────────────────────────────

  /** Bless all seeds. Returns Seq of (slug, isQuarantined, envelope). Order follows Seeds. */
  def blessAll(): Seq[(String, Boolean, Json)] =
    Seeds.map(blessSeed)

  /** Persist blessed vectors. Proof-complete → target/block-vectors/<slug>.json.
    * Proofless → target/block-vectors-PROOFLESS/<slug>.json with a loud banner.
    * Fails loud on slug collision (would silently drop an entry). */
  def writeVectors(results: Seq[(String, Boolean, Json)], baseDir: java.nio.file.Path): Unit = {
    val slugs = results.map(_._1)
    val collisions = slugs.groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("CapturedBlock.writeVectors: slug collision would silently drop entries — " +
        collisions.mkString(", "))

    val liveDir  = baseDir.resolve("block-vectors")
    val proofDir = baseDir.resolve("block-vectors-PROOFLESS")

    results.foreach { case (slug, quarantined, json) =>
      if (quarantined) {
        java.nio.file.Files.createDirectories(proofDir)
        val path = proofDir.resolve(s"$slug.json")
        java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        println(s"PROOFS PENDING: $slug staged to quarantine (never moves toward vectors/) → $path")
      } else {
        java.nio.file.Files.createDirectories(liveDir)
        val path = liveDir.resolve(s"$slug.json")
        java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      }
    }
  }
}
