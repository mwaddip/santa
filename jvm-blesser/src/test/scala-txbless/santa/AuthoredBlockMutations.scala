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

  private val DonorPath = "../vectors/block/v6/captured/bigint-downcast-2666.json"
  private val DonorSlug = "bigint-downcast-2666"
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-execTransactions-model"

  /** One mutation class: vector name + the marker the engine's reject reason must
    * contain (the recipe-intent check) + the entry transform. */
  private final case class Mutation(name: String, reasonMustContain: String,
                                    mutate: Json => Json)

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

  /** Swap the first two transactions of the blockTransactions section. */
  private def swapFirstTwoTxs(entry: Json): Json = {
    val path = Seq("block", "blockTransactions", "transactions")
    val c = cursorAt(entry, path)
    val arr = c.focus.flatMap(_.asArray)
      .getOrElse(sys.error("txs-reorder: transactions not an array"))
    if (arr.size < 2) sys.error(s"txs-reorder: need >=2 txs, donor has ${arr.size}")
    val swapped = arr.updated(0, arr(1)).updated(1, arr(0))
    c.set(Json.arr(swapped: _*)).top.getOrElse(sys.error("txs-reorder: cursor set failed"))
  }

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
  // Class 6 (version-gate) is RETIRED pending a boundary re-donor: exBlockVersion
  // fires only at epoch-boundary blocks (processExtension gated on epochStarts,
  // ErgoStateContext.scala:246 — a donner-surfaced, JVM-verified finding), and 2666
  // is mid-epoch, so the mutation encoded stricter-than-consensus semantics. It
  // returns as a params.table["123"] shrink over an epoch-boundary donor (capture
  // requested from the node session) where the gate genuinely fires on-chain.

  private val Mutations: Seq[Mutation] = Seq(
    // 1. shrink maxBlockCost below the block's real cost (39379) → aggregation reject
    Mutation("params-shrink-maxBlockCost", "cost",
      e => setIn(e, Seq("parameters", "table", "4"), Json.fromInt(10000))),
    // 2. corrupt the declared post-state — header tamper, dies at PoW on the JVM;
    //    probes the post-digest comparison on conformers without a PoW check
    Mutation("stateroot-flip", "hdrPoW",
      e => updateHexIn(e, Seq("block", "header", "stateRoot"), flipLastNibble)),
    // 3. tamper proof bytes → proofs-section digest no longer matches adProofsRoot
    Mutation("adproof-tamper", "bsCorrespondsToHeader: blake2b256",
      e => updateHexIn(e, Seq("block", "adProofs", "proofBytes"), flipMidByte)),
    // 4. reorder the txs section → computed transactionsRoot != sealed commitment
    Mutation("txs-reorder", "transactionsRoot mismatch", swapFirstTwoTxs),
    // 5. corrupt the Autolykos solution nonce → PoW invalid (the intended PoW probe)
    Mutation("pow-solution-flip", "hdrPoW",
      e => updateHexIn(e, Seq("block", "header", "powSolutions", "n"), flipMidByte)))

  // ── donor loading ───────────────────────────────────────────────────────────

  private def donorEntry(): Json = {
    val src = scala.io.Source.fromFile(DonorPath)
    val raw = try src.mkString finally src.close()
    val env = parse(raw).fold(e => sys.error(s"AuthoredBlockMutations: parse donor: $e"), identity)
    env.hcursor.downField("entries").focus.flatMap(_.asArray).flatMap(_.headOption)
      .getOrElse(sys.error("AuthoredBlockMutations: donor has no entries[0]"))
  }

  // ── per-class bless ─────────────────────────────────────────────────────────

  /** Mutate the donor, drive the runner path, confirm the reject + reason class,
    * and return (name, envelope) with the recorded reason as expected.reason. */
  private def blessMutation(donor: Json, m: Mutation): (String, Json) = {
    val mutated = m.mutate(donor)
    val named = setIn(
      setIn(mutated, Seq("name"), Json.fromString(m.name)),
      Seq("source"), Json.fromString(s"santa:mutation:${m.name}:over:$DonorSlug"))

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

  /** Bless all mutation classes over the donor. Order follows Mutations. */
  def blessAll(): Seq[(String, Json)] = {
    val donor = donorEntry()
    Mutations.map(m => blessMutation(donor, m))
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
