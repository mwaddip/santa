package santa

// ─────────────────────────────────────────────────────────────────────────────
// AuthoredChainRetarget — the authored retargeting damping-clamp family
// (contract §6 authored: "retargeting damping clamps (0.5× / 1.5× both hit),
// flat-difficulty controls"):
//
//   vectors/chain/any/authored/Retargeting.damping_clamps.json
//     retargeting-flat-control            perfectly-on-interval epochs at constant
//                                         nBits N → the oracle must emit N back
//     retargeting-fast-chain-clamps-up    epochs compressed 10× → the 1.5× upper
//                                         damping clamp binds
//     retargeting-slow-chain-clamps-down  epochs stretched 10× → the 0.5× lower
//                                         damping clamp binds
//
// WHY THE ENTRIES CARRY THE EIP-37 SETTINGS PAIR (a deviation from the task
// sketch's "classic arm — no eip37 pair", pinned against the JVM source): the
// 0.5×/1.5× damping clamps live ONLY in `eip37Calculate`
// (DifficultyAdjustment.scala:85-96 — `limitedPredictiveDiff` and the avg clamp,
// both against `lastDiff`); the classic `calculate` arm (:106-128) has NO damping
// — linear interpolation plus the <1 → initialDifficulty floor. Under classic
// settings, 10× and 100× compression emit ~10N and ~100N (different), so a
// "classic damping clamp" vector is unreachable; contract §2 places the clamps in
// the EIP-37 arm explicitly. Hence `eip37_activation_height: 1` (the entry
// carries the dispatch gate explicitly, contract §2; T = 393601 ≥ 1 ⇒ the arm
// governs) with `eip37_epoch_length == epoch_length == 128` (one grid for the
// recalculation predicate, the anchor spacing, and the eip37Calculate argument).
// The generator test proves all of this LIVE: oracle(10×) == oracle(100×) under
// the eip37 pair (the clamp), and != under classic settings (no clamp there).
//
// SYNTHETIC ANCHORS: each case clones the REAL captured anchor header
// docs/findings/chain-captures/testnet-retarget/header-392576.json (the captured
// p1 grid's first anchor) 9 times and rewrites ONLY height / timestamp / nBits —
// the computation reads nothing else (spike-pinned, contract §2); every other
// field stays donor-verbatim so the headers remain decode-valid. The grid is the
// captured p1 grid itself: T = 393601, anchors 392576..393600 step 128.
//
// Every `expected.nbits` is ORACLE-EMITTED via ChainEngine.chainEntry — the exact
// runner path. The clamps are pinned by oracle-output EQUALITY (10× vs 100×),
// never a hand-computed 1.5×/0.5× value; the 100× probes live only in the
// generator test (committed vectors = the 10× forms).
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json
import io.circe.parser.parse

object AuthoredChainRetarget {

  /** House oracle identity (CapturedChain's). */
  private val BlessedBy = "jvm:ergo-core-6.0.2.1-chain-model"

  /** Committed output path, vectors/-relative (retargeting is height-gated ⇒ any). */
  val DampingClampsPath = "chain/any/authored/Retargeting.damping_clamps.json"

  /** The captured donor header — the real testnet anchor at 392576 (p1's first). */
  private val DonorPath = "../docs/findings/chain-captures/testnet-retarget/header-392576.json"

  // The captured p1 grid, reused synthetically: T = 393601 ((T−1) % 128 == 0, away
  // from the §2 version-2 carve-out heights), anchors 392576..393600 step 128.
  private val TargetHeight  = 393601
  private val EpochLength   = 128
  private val UseLastEpochs = 8
  private val BaseHeight    = TargetHeight - 1 - UseLastEpochs * EpochLength // 392576

  /** A perfectly-on-interval epoch span: epoch_length × block_interval_ms. */
  private val FlatSpanMs: Long = EpochLength.toLong * 45000L // 5760000

  /** Entry settings WITH the eip37 pair — see the header block for why the pair is
    * load-bearing (the damping clamps exist only in the EIP-37 arm). */
  private val Eip37Settings = Json.obj(
    "epoch_length"            -> Json.fromInt(EpochLength),
    "use_last_epochs"         -> Json.fromInt(UseLastEpochs),
    "block_interval_ms"       -> Json.fromInt(45000),
    "initial_nbits"           -> Json.fromInt(16842752),
    "eip37_activation_height" -> Json.fromInt(1),
    "eip37_epoch_length"      -> Json.fromInt(EpochLength))

  /** Classic-arm settings (no eip37 pair) — PROBES ONLY, proving the classic arm
    * has no damping clamp; never committed. */
  private val ClassicSettings = Json.obj(
    "epoch_length"      -> Json.fromInt(EpochLength),
    "use_last_epochs"   -> Json.fromInt(UseLastEpochs),
    "block_interval_ms" -> Json.fromInt(45000),
    "initial_nbits"     -> Json.fromInt(16842752))

  /** One committed case: the constant per-epoch span its timestamps encode. */
  private final case class RCase(name: String, source: String, spanMs: Long, note: String)

  private val Cases: Seq[RCase] = Seq(
    RCase("retargeting-flat-control", "santa:damping_clamps:flat-control",
      spanMs = FlatSpanMs,
      note = "control: 9 anchors at constant nBits, every epoch exactly on the desired " +
        "interval (128 × 45000 ms) — a flat chain retargets to itself"),
    RCase("retargeting-fast-chain-clamps-up", "santa:damping_clamps:fast-chain-clamps-up",
      spanMs = FlatSpanMs / 10,
      note = "epochs compressed 10× — the EIP-37 1.5× upper damping clamp binds: the " +
        "generator test proves oracle(10×) == oracle(100×) (same nbits for different " +
        "compressions ⇒ the clamp, not the ratio, decides)"),
    RCase("retargeting-slow-chain-clamps-down", "santa:damping_clamps:slow-chain-clamps-down",
      spanMs = FlatSpanMs * 10,
      note = "epochs stretched 10× — the EIP-37 0.5× lower damping clamp binds: the " +
        "generator test proves oracle(10×) == oracle(100×)"))

  // ── donor loading + synthetic anchors ───────────────────────────────────────

  private def donor(): Json = {
    val src = scala.io.Source.fromFile(DonorPath)
    val raw = try src.mkString finally src.close()
    val d = parse(raw).fold(e => sys.error(s"AuthoredChainRetarget: parse donor: $e"), identity)
    // Identity checks: the donor IS the grid's first anchor, so its own height/nBits
    // anchor the synthetic grid to the captured reality.
    val h = d.hcursor.get[Int]("height").fold(e => sys.error(s"donor height: $e"), identity)
    if (h != BaseHeight)
      sys.error(s"AuthoredChainRetarget: donor is block $h, expected $BaseHeight")
    d
  }

  private def setNum(obj: Json, key: String, value: Json): Json =
    obj.hcursor.downField(key).set(value).top
      .getOrElse(sys.error(s"AuthoredChainRetarget: donor has no $key field to rewrite"))

  /** The 9 synthetic anchors for one epoch span: clone the donor, rewrite ONLY
    * height (the grid), timestamp (t0 + k·span), and nBits (the donor's own,
    * constant across the grid) — all other fields donor-verbatim. */
  private def anchors(d: Json, spanMs: Long): Seq[Json] = {
    val t0 = d.hcursor.get[Long]("timestamp").fold(e => sys.error(s"donor timestamp: $e"), identity)
    val n  = d.hcursor.get[Long]("nBits").fold(e => sys.error(s"donor nBits: $e"), identity)
    (0 to UseLastEpochs).map { k =>
      setNum(setNum(setNum(d,
        "height", Json.fromInt(BaseHeight + k * EpochLength)),
        "timestamp", Json.fromLong(t0 + k * spanMs)),
        "nBits", Json.fromLong(n))
    }
  }

  private def baseEntry(name: String, source: String, settings: Json, d: Json,
                        spanMs: Long): Seq[(String, Json)] = Seq(
    "name"     -> Json.fromString(name),
    "source"   -> Json.fromString(source),
    "kind"     -> Json.fromString("retargeting"),
    "settings" -> settings,
    "payload"  -> Json.obj(
      "target_height"  -> Json.fromInt(TargetHeight),
      "anchor_headers" -> Json.arr(anchors(d, spanMs): _*)))

  // ── oracle ──────────────────────────────────────────────────────────────────

  /** Drive ChainEngine.chainEntry — the exact runner path; an authored entry must
    * bless cleanly: any non-null `error` is a recipe bug, fail loud. */
  private def engineNbits(entry: Json, what: String): Long = {
    val (_, actual) = santa.runner.ChainEngine.chainEntry(entry)
    val err = actual.hcursor.downField("error").focus.getOrElse(Json.Null)
    if (!err.isNull)
      sys.error(s"AuthoredChainRetarget[$what]: engine returned error=${err.noSpaces} " +
        s"note=${actual.hcursor.get[String]("note").toOption.getOrElse("<none>")} — " +
        "an authored entry must bless cleanly")
    actual.hcursor.get[Long]("nbits")
      .fold(e => sys.error(s"AuthoredChainRetarget[$what]: engine nbits: $e"), identity)
  }

  private def bless(d: Json, c: RCase): Json = {
    val base = baseEntry(c.name, c.source, Eip37Settings, d, c.spanMs)
    val nbits = engineNbits(Json.obj(base: _*), c.name)
    // diagnostic: the decimal difficulty behind the blessed nbits, derived from the
    // engine's own output via the canonical serializer (CapturedChain's idiom) — never graded.
    val difficulty = org.ergoplatform.mining.difficulty.DifficultySerializer
      .decodeCompactBits(nbits).toString
    Json.obj(base ++ Seq(
      "expected"   -> Json.obj("nbits" -> Json.fromLong(nbits)),
      "diagnostic" -> Json.obj(
        "difficulty" -> Json.fromString(difficulty),
        "note"       -> Json.fromString(c.note))): _*)
  }

  // ── public API ──────────────────────────────────────────────────────────────

  /** Bless the committed family: ONE file, three entries (flat / fast-10× / slow-10×). */
  def blessAll(): Seq[(String, Json)] = {
    val d = donor()
    Seq(DampingClampsPath -> Json.obj(
      "schema"     -> Json.fromString("santa-chain/v1"),
      "blessed_by" -> Json.fromString(BlessedBy),
      "entries"    -> Json.arr(Cases.map(bless(d, _)): _*)))
  }

  /** Generator-test-only oracle probes (never committed):
    *   fast/slow-100x-eip37   — the clamp proof: must EQUAL the committed 10× nbits
    *   fast/slow-10x/100x-classic — the no-clamp evidence: same anchors WITHOUT the
    *     eip37 pair must DIFFER across compressions (the clamps are eip37-only). */
  def probes(): Map[String, Long] = {
    val d = donor()
    def run(tag: String, settings: Json, spanMs: Long): (String, Long) =
      tag -> engineNbits(Json.obj(baseEntry(s"probe-$tag", s"santa:probe:$tag",
        settings, d, spanMs): _*), s"probe-$tag")
    Map(
      run("fast-100x-eip37",   Eip37Settings,   FlatSpanMs / 100),
      run("slow-100x-eip37",   Eip37Settings,   FlatSpanMs * 100),
      run("fast-10x-classic",  ClassicSettings, FlatSpanMs / 10),
      run("fast-100x-classic", ClassicSettings, FlatSpanMs / 100),
      run("slow-10x-classic",  ClassicSettings, FlatSpanMs * 10),
      run("slow-100x-classic", ClassicSettings, FlatSpanMs * 100))
  }

  /** Persist blessed vectors at their COMMITTED paths under vectorsRoot
    * (`../vectors` from the blesser's cwd) — re-blessing regenerates in place.
    * Fails loud on a path collision (would silently drop a file). */
  def writeVectors(results: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit = {
    val collisions = results.map(_._1).groupBy(identity).filter(_._2.size > 1).keys
    if (collisions.nonEmpty)
      sys.error("AuthoredChainRetarget.writeVectors: path collision would silently drop a file — " +
        collisions.mkString(", "))
    results.foreach { case (rel, json) =>
      val path = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, json.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
  }
}
