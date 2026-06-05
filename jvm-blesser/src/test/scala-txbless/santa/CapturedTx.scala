package santa

// ─────────────────────────────────────────────────────────────────────────────
// CapturedTx — assemble + bless the 4 captured tx-tier seeds (docs/findings/) into
// `santa-transaction/v1` vector envelopes, driving the JVM oracle TxValidate (which
// wraps ergo-core `ErgoTransaction.validateStateful`).
//
// One vector file per seed → target/tx-vectors/<slug>.json (Task 2.4 copies these into
// vectors/transaction/v6/captured/). The tx / inputBoxes / dataInputBoxes JSON are the
// node-API JSON objects read verbatim from the captured files — TxValidate decodes the
// SAME JSON via ApiCodecs (Decoder[ErgoTransaction] / Decoder[ErgoBox]), so the vector
// payload and the oracle's input are identical by construction.
//
// ── Robust box resolution ──────────────────────────────────────────────────────
// For each seed we read block-<h>.json, locate the TARGET tx (the one whose inputs[]
// boxId set includes the seed's known divergence box → txIndex), then resolve every
// input boxId — IN tx.inputs ORDER — to its ErgoBox JSON by:
//   (1) indexing every box-*.json in the seed dir by the box's OWN `boxId` field
//       (not the filename prefix), then
//   (2) falling back to the SAME block's transactions' outputs[] for a box whose boxId
//       matches (the in-block case, e.g. powhit's input[1] 1d746ebe — an earlier tx's
//       output in block-28474). dataInputs resolve identically.
// A boxId that resolves to NEITHER a file NOR an in-block output is a real capture gap:
// we FAIL LOUD (sys.error) rather than fabricate a box.
//
// Block shape varies across the captured seeds: blocks 2666 / 184137 nest the txs under
// `blockTransactions.transactions`; blocks 28474 / 111927 are bare `{headerId,transactions}`.
// `blockTxs` handles both.
//
// ── FAIL-LOUD-on-valid:false ────────────────────────────────────────────────────
// Captured history is by definition valid (these txs are on-chain). If TxValidate returns
// valid==false for a captured seed, that is a capture/recipe bug (wrong box, wrong order,
// wrong height/version) — never a legitimate vector. We sys.error, we do not emit it.
// The tx_path_guard (tools/validate) independently enforces captured ⇒ valid:true.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import io.circe.parser.parse

object CapturedTx {

  /** Full v6: activated=3, ergoTree=3 (all 4 seeds are blockVersion-4 blocks → v3 scripts). */
  private val Activated = 3
  private val ErgoTreeV = 3

  /** Provenance stamp for the envelope `blessed_by` (mirrors AuthoredSerialize's house style:
    * the oracle identity). The per-entry `source` is `testnet:<seed>@<height>` (captured). */
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-validateStateful"

  /** docs/findings/ lives at the REPO ROOT; the blesser runs from the jvm-blesser/ cwd
    * (forked test JVM, baseDirectory = jvm-blesser/), so findings are one level up. If
    * Task 2.4's runtime cwd differs, adjust this single constant. */
  private val FindingsDir = "../docs/findings"

  /** One seed: its findings sub-dir, block height, and the known divergence box id used to
    * pin the target tx within the block. */
  private final case class Seed(slug: String, dir: String, height: Int, divergenceBox: String)

  private val Seeds: Seq[Seed] = Seq(
    Seed("bigint-downcast-2666",          "testnet-bigint-downcast-v3",       2666,
      "b98a06c14edc67f3fb11e7a8903fcdf83bdcb37e52f8173c872bd2327bfb895a"),
    Seed("deserialize-context-111927",    "testnet-deserialize-context",      111927,
      "dc577b24180a3c70edfd4606e09c1a40bebd6d5925bc2e238e54c7840c094edc"),
    Seed("atleast-degenerate-bound-184137","testnet-atleast-degenerate-bound", 184137,
      "f4ddf9282c016a56f52e2ed87a8702b623802563ef6c4d5006b7992073ceaf53"),
    Seed("powhit-return-type-28474",      "testnet-powhit-return-type",       28474,
      "105d395e17b67494aaa92a62d3724f5861c606b376df7ab55277b5b794b7fb60"))

  // ── file / JSON helpers ────────────────────────────────────────────────────────

  private def readFile(p: String): String = {
    val src = scala.io.Source.fromFile(p)
    try src.mkString finally src.close()
  }

  private def parseFile(p: String): Json =
    parse(readFile(p)).fold(e => sys.error(s"CapturedTx: parse $p: $e"), identity)

  /** The transactions array of a block, handling both shapes:
    *   nested:  { blockTransactions: { transactions: [...] } }
    *   bare:    { headerId, transactions: [...] }  */
  private def blockTxs(block: Json): Vector[Json] = {
    val nested = block.hcursor.downField("blockTransactions").downField("transactions").focus
    val bare   = block.hcursor.downField("transactions").focus
    nested.orElse(bare)
      .flatMap(_.asArray)
      .getOrElse(sys.error("CapturedTx: block has neither blockTransactions.transactions nor transactions[]"))
  }

  private def boxIdOf(boxJson: Json): String =
    boxJson.hcursor.downField("boxId").as[String]
      .getOrElse(sys.error(s"CapturedTx: box JSON has no boxId: ${boxJson.noSpaces}"))

  /** boxId list (in order) for a tx's `field` (inputs or dataInputs). */
  private def boxIds(tx: Json, field: String): Vector[String] =
    tx.hcursor.downField(field).focus.flatMap(_.asArray).getOrElse(Vector.empty)
      .map(e => e.hcursor.downField("boxId").as[String]
        .getOrElse(sys.error(s"CapturedTx: $field entry has no boxId: ${e.noSpaces}")))

  // ── per-seed assembly ──────────────────────────────────────────────────────────

  /** Read one seed → ((slug, op), envelope Json). FAIL LOUD on a missing box or a
    * valid:false oracle verdict. */
  private def blessSeed(seed: Seed): ((String, String), Json) = {
    val seedDir = s"$FindingsDir/${seed.dir}"
    val block   = parseFile(s"$seedDir/block-${seed.height}.json")
    val txs     = blockTxs(block)

    // (1) index every box-*.json by the box's OWN boxId field.
    val dir = new java.io.File(seedDir)
    val boxFiles = Option(dir.listFiles).getOrElse(Array.empty[java.io.File])
      .filter(f => f.getName.startsWith("box-") && f.getName.endsWith(".json"))
    val byFile: Map[String, Json] = boxFiles.map { f =>
      val j = parseFile(f.getPath); boxIdOf(j) -> j
    }.toMap

    // (2) index every in-block output by boxId (the in-block fallback, e.g. powhit 1d746ebe).
    val byInBlock: Map[String, Json] = txs.flatMap { t =>
      t.hcursor.downField("outputs").focus.flatMap(_.asArray).getOrElse(Vector.empty)
        .map(o => boxIdOf(o) -> o)
    }.toMap

    // locate the target tx: the one whose inputs include the divergence box.
    val txIndex = txs.indexWhere(t => boxIds(t, "inputs").contains(seed.divergenceBox))
    if (txIndex < 0)
      sys.error(s"CapturedTx[${seed.slug}]: divergence box ${seed.divergenceBox} not an input " +
        s"of any tx in block-${seed.height} — capture/recipe mismatch")
    val tx = txs(txIndex)

    // resolve a single boxId to its ErgoBox JSON: file first, then in-block; else BLOCKED.
    def resolve(kind: String, boxId: String): Json =
      byFile.get(boxId).orElse(byInBlock.get(boxId)).getOrElse(
        sys.error(s"CapturedTx[${seed.slug}]: BLOCKED — $kind box $boxId resolves to neither a " +
          s"box-*.json file nor an in-block output of block-${seed.height} (capture gap; do not fabricate)"))

    val inputBoxes     = boxIds(tx, "inputs").map(id => resolve("input", id))
    val dataInputBoxes = boxIds(tx, "dataInputs").map(id => resolve("dataInput", id))

    // ── drive the oracle ─────────────────────────────────────────────────────────
    val verdict = TxValidate.validate(tx, inputBoxes, dataInputBoxes, seed.height, Activated)
    if (!verdict.valid)
      sys.error(s"CapturedTx[${seed.slug}]: oracle REJECTED a captured (on-chain) tx — this is a " +
        s"capture/recipe bug, not a vector. reason=${verdict.reason.getOrElse("<none>")}")
    val cost = verdict.cost.getOrElse(
      sys.error(s"CapturedTx[${seed.slug}]: valid:true but no cost from validateStateful"))

    val op     = s"tx:captured:${seed.slug}"
    val source = s"testnet:${seed.dir}@${seed.height}"
    val entry = Json.obj(
      "name"           -> Json.fromString(seed.slug),
      "source"         -> Json.fromString(source),
      "tx"             -> tx,
      "inputBoxes"     -> Json.arr(inputBoxes: _*),
      "dataInputBoxes" -> Json.arr(dataInputBoxes: _*),
      "context"        -> Json.obj("height" -> Json.fromInt(seed.height)),
      "version"        -> Json.obj("activated" -> Json.fromInt(Activated),
                                   "ergoTree"  -> Json.fromInt(ErgoTreeV)),
      "expected"       -> Json.obj("valid"  -> Json.fromBoolean(true),
                                   "cost"   -> Json.fromLong(cost),
                                   "reason" -> Json.Null))
    val envelope = Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString(op),
      "blessed_by" -> Json.fromString(BlessedBy),
      "entries"    -> Json.arr(entry))
    (seed.slug -> op) -> envelope
  }

  /** Bless all 4 seeds → Seq of (slug, envelope Json). Order follows `Seeds`. */
  def blessAll(): Seq[(String, Json)] =
    Seeds.map { s => val ((slug, _), env) = blessSeed(s); slug -> env }

  /** Persist the blessed vectors to a staging dir (build artifact — Task 2.4 copies them
    * into vectors/transaction/v6/captured/ once inspected). One file per seed, named by
    * slug. Fails loud on a slug collision (would silently drop a seed's entry). */
  def writeVectors(vectors: Seq[(String, Json)], outDir: java.nio.file.Path): Unit = {
    java.nio.file.Files.createDirectories(outDir)
    val collisions = vectors.map(_._1).groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("CapturedTx.writeVectors: slug collision would silently drop entries — " +
        collisions.mkString(", "))
    vectors.foreach { case (slug, json) =>
      val path = outDir.resolve(s"$slug.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
