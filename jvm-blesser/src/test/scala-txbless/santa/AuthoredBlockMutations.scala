package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredBlockMutations — the block tier's reject arm (spec §7, approved ranking):
// single-fault mutations over the committed captured donor (bigint-downcast-2666),
// each emitted ONLY after the JVM BlockEngine confirms rejection AND the recorded
// reason carries the intended class signal — a mutation rejected for the WRONG
// reason is a recipe bug, fail loud.
//
// expected.reason = the engine's recorded reason VERBATIM (diagnostic-only, never
// cross-matched by grading) — derived from the engine's own failure, not hand-typed.
//
// The oracle is driven through BlockEngine.blockEntry — the exact runner path the
// committed vectors will hit — so a recipe that panics the runner (instead of
// rejecting cleanly) surfaces here as error:"panicked" and fails the bless.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import io.circe.parser.parse

object AuthoredBlockMutations {

  private val MidEpochDonor = "bigint-downcast-2666"
  private val BoundaryDonor = "epoch-boundary-2560"
  private val BlessedBy = "jvm:ergo-core-6.0.7-execTransactions-model"

  private def donorPath(slug: String) = s"../vectors/block/v6/captured/$slug.json"

  /** One mutation class: vector name + the marker the engine's reject reason must
    * contain (the recipe-intent check) + the entry transform + the captured donor
    * it mutates (mid-epoch 2666 by default; version-gate needs the boundary donor —
    * the gate only fires at epochStarts). `entryName` is the committed entry's name where it
    * is not the class name: entry names are identities results are keyed by, never renamed.
    * `description` is the entry's prose (it replaces the donor's own). */
  private final case class Mutation(name: String, reasonMustContain: String,
                                    mutate: Json => Json,
                                    donorSlug: String = MidEpochDonor,
                                    entryName: Option[String] = None,
                                    description: String)

  // ── hex tampering helpers (length-preserving, stay lowercase hex) ──────────

  /** Flip the last hex nibble (0↔1 — any change suffices, length preserved). */
  private def flipLastNibble(hex: String): String = {
    val flipped = if (hex.last == '0') '1' else '0'
    hex.init + flipped
  }

  /** Flip one hex char at a byte boundary mid-string. */
  private def flipMidByte(hex: String): String = {
    val mid = (hex.length / 2) & ~1
    val flipped = if (hex.charAt(mid) == '0') '1' else '0'
    hex.updated(mid, flipped)
  }

  // ── JSON surgery ────────────────────────────────────────────────────────────

  private def cursorAt(entry: Json, path: Seq[String]): io.circe.ACursor =
    path.foldLeft(entry.hcursor: io.circe.ACursor)((c, p) => c.downField(p))

  /** `entry` with its `description` set, placed right after `source` (the donor's own is dropped). */
  private def withDescription(entry: Json, d: String): Json = entry.mapObject { o =>
    io.circe.JsonObject.fromIterable(o.toList.filterNot(_._1 == "description").flatMap {
      case ("source", v) => List("source" -> v, "description" -> Json.fromString(d))
      case kv            => List(kv)
    })
  }

  private def setIn(entry: Json, path: Seq[String], value: Json): Json =
    cursorAt(entry, path).set(value).top
      .getOrElse(sys.error(s"mutation path ${path.mkString(".")}: cursor set failed"))

  private def updateHexIn(entry: Json, path: Seq[String], f: String => String): Json = {
    val c = cursorAt(entry, path)
    val cur = c.focus.flatMap(_.asString)
      .getOrElse(sys.error(s"mutation path ${path.mkString(".")}: not a string"))
    c.set(Json.fromString(f(cur))).top
      .getOrElse(sys.error(s"mutation path ${path.mkString(".")}: cursor set failed"))
  }

  /** Swap the first two transactions of the blockTransactions section — in its BYTES: parse the donor's
    * section, swap, frame it again. The donor's section is the writer's own form, so re-framing changes
    * nothing but the order; that is asserted rather than assumed. */
  private def swapFirstTwoTxs(entry: Json): Json = {
    val path = Seq("block", "blockTransactions")
    val c = cursorAt(entry, path)
    val section = c.focus.flatMap(_.asString)
      .getOrElse(sys.error("txs-reorder: blockTransactions is not the section's hex"))
    val bt = BlockSectionBytes.parse(section)
    require(BlockSectionBytes.hexOf(bt.headerId, bt.blockVersion, bt.txs) == section,
      "txs-reorder: the donor's section does not re-frame to itself — a swap would change more than the order")
    if (bt.txs.size < 2) sys.error(s"txs-reorder: need >=2 txs, donor has ${bt.txs.size}")
    val swapped = bt.txs.updated(0, bt.txs(1)).updated(1, bt.txs(0))
    c.set(Json.fromString(BlockSectionBytes.hexOf(bt.headerId, bt.blockVersion, swapped))).top
      .getOrElse(sys.error("txs-reorder: cursor set failed"))
  }

  /** Remove the ADProofs section: its bytes and its digest both empty. Hand-made in July 2026 with a
    * hand-typed reason; generated since 2026-11-11, so its reason is the oracle's. */
  private def dropProofs(entry: Json): Json =
    setIn(setIn(entry, Seq("block", "adProofs", "proofBytes"), Json.fromString("")),
      Seq("block", "adProofs", "digest"), Json.fromString(""))

  // ── the classes (spec §7 priority order) ────────────────────────────────────
  //
  // PoW-sealing note (discovered at first bless): Autolykos hashes the header
  // bytes, so ANY header-field flip (stateRoot, transactionsRoot, version…)
  // invalidates PoW before the named subsystem check is reached. Hence:
  //  - stateroot-flip stays a header flip (spec class 2 names it) and records
  //    hdrPoW on the JVM — the post-digest comparison it exists to probe is what
  //    PoW-LESS digest-native conformers hit instead; either way: must-reject.
  //  - class 4 mutates the SECTION (tx reorder), not the sealed commitment, so
  //    the section-digest check itself fires (PoW intact).
  //
  // Class 6 (version-gate) rides the BOUNDARY donor: exBlockVersion fires only at
  // epoch-boundary blocks (processExtension gated on epochStarts,
  // ErgoStateContext.scala:246 — a donner-surfaced, JVM-verified finding). Its first
  // authoring over mid-epoch 2666 encoded stricter-than-consensus semantics and was
  // retired; over 2560 (2560 % 128 == 0) the gate genuinely fires on-chain.

  private val Mutations: Seq[Mutation] = Seq(
    // 1. shrink maxBlockCost below the block's real cost (39379) → aggregation reject
    Mutation("params-shrink-maxBlockCost", "cost",
      e => setIn(e, Seq("parameters", "table", "4"), Json.fromInt(10000)),
      description = "Block 2666 with the in-force `maxBlockCost` (parameter 4) shrunk to 10000, below the " +
        "block's real cost: the transactions' accumulated cost exceeds the limit and the block is INVALID. " +
        "Every other byte is the captured donor's (bigint-downcast-2666, a mid-epoch block)."),
    // 2. corrupt the declared post-state — header tamper, dies at PoW on the JVM;
    //    probes the post-digest comparison on conformers without a PoW check
    Mutation("stateroot-flip", "hdrPoW",
      e => updateHexIn(e, Seq("block", "header", "stateRoot"), flipLastNibble),
      description = "Block 2666 with the last nibble of the header's declared post-state root (`stateRoot`) " +
        "flipped. Autolykos hashes the header, so the JVM rejects at PoW (`hdrPoW`) before it compares " +
        "digests; an implementation without a PoW check reaches the post-digest comparison instead. Either " +
        "way the block is INVALID."),
    // 3. tamper proof bytes → proofs-section digest no longer matches adProofsRoot
    Mutation("adproof-tamper", "bsCorrespondsToHeader: blake2b256",
      e => updateHexIn(e, Seq("block", "adProofs", "proofBytes"), flipMidByte),
      description = "Block 2666 with one byte flipped in the middle of the ADProofs bytes: the proofs " +
        "section no longer hashes to the header's `adProofsRoot`, so the block is INVALID before any proof " +
        "is applied. The header and its PoW are untouched."),
    // 4. reorder the txs section → computed transactionsRoot != sealed commitment
    Mutation("txs-reorder", "transactionsRoot mismatch", swapFirstTwoTxs,
      description = "Block 2666 with its first two transactions swapped in the transactions section " +
        "(re-framed bytes; nothing but the order changes): the computed `transactionsRoot` no longer matches " +
        "the header's, and the block is INVALID. The header and its PoW are untouched, so the section-digest " +
        "check itself fires."),
    // 5. corrupt the Autolykos solution nonce → PoW invalid (the intended PoW probe)
    Mutation("pow-solution-flip", "hdrPoW",
      e => updateHexIn(e, Seq("block", "header", "powSolutions", "n"), flipMidByte),
      description = "Block 2666 with one byte of the Autolykos solution's nonce (`powSolutions.n`) flipped: " +
        "the header's PoW fails (`hdrPoW`) and the block is INVALID."),
    // 6. shrink the epoch's declared blockVersion below the header's → version gate
    //    (boundary donor — the only place the JVM makes this comparison)
    Mutation("version-gate", "exBlockVersion",
      e => setIn(e, Seq("parameters", "table", "123"), Json.fromInt(3)),
      donorSlug = BoundaryDonor,
      description = "Block 2560, an epoch boundary, with the epoch's declared block version (parameter 123) " +
        "set to 3, below the header's version 4: `exBlockVersion` fails and the block is INVALID. The node " +
        "makes this comparison only at an epoch's first block, so the mutation needs a boundary donor; at a " +
        "mid-epoch block it would encode a rule stricter than consensus."),
    // 7. no ADProofs section at all — the degenerate limit of adproof-tamper (contract §8):
    //    the empty bytes cannot hash to the header's adProofsRoot
    Mutation("proofless", "blake2b256(proofBytes)", dropProofs, entryName = Some("proofless-2666"),
      description = "Block 2666 with no ADProofs section: its bytes and its digest both empty. The empty " +
        "bytes cannot hash to the header's `adProofsRoot`, so the block is INVALID: the degenerate limit of " +
        "adproof-tamper."))

  // ── donor loading ───────────────────────────────────────────────────────────

  private def donorEntry(slug: String): Json = {
    val src = scala.io.Source.fromFile(donorPath(slug))
    val raw = try src.mkString finally src.close()
    val env = parse(raw).fold(e => sys.error(s"AuthoredBlockMutations: parse donor $slug: $e"), identity)
    env.hcursor.downField("entries").focus.flatMap(_.asArray).flatMap(_.headOption)
      .getOrElse(sys.error(s"AuthoredBlockMutations: donor $slug has no entries[0]"))
  }

  // ── per-class bless ─────────────────────────────────────────────────────────

  /** Mutate the class' donor, drive the runner path, confirm the reject + reason
    * class, and return (name, envelope) with the recorded reason as expected.reason. */
  private def blessMutation(donors: Map[String, Json], m: Mutation): (String, Json) = {
    val donor = donors.getOrElse(m.donorSlug,
      sys.error(s"AuthoredBlockMutations[${m.name}]: donor ${m.donorSlug} not loaded"))
    val mutated = m.mutate(donor)
    require(m.description.nonEmpty, s"AuthoredBlockMutations[${m.name}]: every mutation states what it breaks")
    val named = withDescription(setIn(
      setIn(mutated, Seq("name"), Json.fromString(m.entryName.getOrElse(m.name))),
      Seq("source"), Json.fromString(s"santa:mutation:${m.name}:over:${m.donorSlug}")), m.description)

    val (_, actuals) = santa.runner.BlockEngine.blockEntry(named)
    val ac = actuals.hcursor
    val err = ac.downField("error").focus.filterNot(_.isNull)
    if (err.isDefined)
      sys.error(s"AuthoredBlockMutations[${m.name}]: runner path errored instead of " +
        s"rejecting cleanly — recipe bug. actuals=${actuals.noSpaces}")
    val valid = ac.get[Boolean]("valid").getOrElse(
      sys.error(s"AuthoredBlockMutations[${m.name}]: no valid field. actuals=${actuals.noSpaces}"))
    if (valid)
      sys.error(s"AuthoredBlockMutations[${m.name}]: oracle ACCEPTED the mutation — " +
        s"the recipe did not break what it intended to break (or an engine gap).")
    val reason = ac.get[String]("reason").getOrElse(
      sys.error(s"AuthoredBlockMutations[${m.name}]: reject without reason. actuals=${actuals.noSpaces}"))
    if (!reason.toLowerCase.contains(m.reasonMustContain.toLowerCase))
      sys.error(s"AuthoredBlockMutations[${m.name}]: rejected for the WRONG reason — " +
        s"recipe bug. wanted marker '${m.reasonMustContain}', got: $reason")

    val entry = setIn(named, Seq("expected"), Json.obj(
      "valid"       -> Json.fromBoolean(false),
      "post_digest" -> Json.Null,
      "cost"        -> Json.Null,
      "reason"      -> Json.fromString(reason)))

    val envelope = Json.obj(
      "schema"     -> Json.fromString("santa-block/v1"),
      "op"         -> Json.fromString(s"block:mutation:${m.name}"),
      "blessed_by" -> Json.fromString(BlessedBy),
      "entries"    -> Json.arr(entry))

    (m.name, envelope)
  }

  // ── public API ──────────────────────────────────────────────────────────────

  /** Bless all mutation classes, each over its declared donor. Order follows Mutations. */
  def blessAll(): Seq[(String, Json)] = {
    val donors = Mutations.map(_.donorSlug).distinct.map(s => s -> donorEntry(s)).toMap
    Mutations.map(m => blessMutation(donors, m))
  }

  /** Persist blessed mutation vectors → target/block-mutations/<class>.json.
    * Fails loud on name collision (would silently drop an entry). */
  def writeVectors(results: Seq[(String, Json)], baseDir: java.nio.file.Path): Unit = {
    val names = results.map(_._1)
    val collisions = names.groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("AuthoredBlockMutations.writeVectors: name collision — " + collisions.mkString(", "))

    val outDir = baseDir.resolve("block-mutations")
    results.foreach { case (name, json) =>
      java.nio.file.Files.createDirectories(outDir)
      val path = outDir.resolve(s"$name.json")
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
