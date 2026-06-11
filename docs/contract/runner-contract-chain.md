# SANTA Runner Contract — Chain tier (`santa-chain/v1`)

> **Status: the committed result-shape contract for the chain tier (`santa-chain/v1`).** A lean
> companion to the eval, wire, transaction, and block contracts — it specifies only what is
> chain-specific and inherits totality, never-panic, faithful outcomes, and the comparator
> topology from [`runner-contract.md`](./runner-contract.md).
>
> Machine-checkable schemas:
> [`schema/santa-chain.vector.schema.json`](../../schema/santa-chain.vector.schema.json),
> [`schema/santa-chain.actuals.schema.json`](../../schema/santa-chain.actuals.schema.json).
> Executable grading oracle: `oracle/verdicts-chain.json` (lands with the grader).
> The schemas derive from this contract — on any divergence, this document wins.
>
> JVM file:line references are into ergo-node-build @ `v6.0.2.1` (the publishLocal'd
> ergo-core the blesser gate links), spike-verified 2026-06-11.

## 1. Tier boundary

A **chain runner** decides **header-chain-decidable consensus functions**: pure functions
over a header sequence (or a structure derived from one) — no UTXO state, no transactions,
no ADProofs. Two kinds in v1:

- **`retargeting`** — the required difficulty (`nBits`) for a target height, computed from
  epoch-spaced anchor headers. Fork-critical: a node computing wrong required difficulty
  rejects valid blocks or accepts invalid ones.
- **`voting`** — the parameter-voting math at an epoch boundary: from the epoch's vote
  stream, the in-force parameters, and the proposed validation-settings update, produce the
  full next-`Parameters` table plus the activated update. Pedigree: a known real divergence
  (ergots' first failure walking the chain was voting).

The tier series is organized by **input shape**, and this is the missing rung: eval takes
an ErgoTree + context, wire takes bytes, transaction takes a tx + all its input boxes,
block takes one block + a handed digest pre-state — **chain takes a header sequence**.
`santa-block/v1` headers already carry `votes` and `nBits` per header, but a block vector
holds ≤10 headers around one block. Retargeting needs `use_last_epochs + 1` = **9 anchor
headers spaced an epoch apart** (`previousHeightsRequiredForRecalculation`,
`DifficultyAdjustment.scala:38-46`); a voting epoch needs the **full epoch's vote stream**
(128 testnet / 1024 mainnet headers). Neither fits — the block contract's §5
difficulty-retarget and stepped-boundary exclusions name exactly this gap, and block-tier
boundary seeds (epoch-boundary-2560) exercise these paths only incidentally. Chain vectors
are the dedicated complement.

What chain is **not**: mining is not a tier (its consensus-facing parts are validation
duties already in the block tier; candidate assembly is policy with no blessable expected
output; the solve loop is nondeterministic — retargeting is the one mining-adjacent pure
function, and it lands here). p2p message formats are a future wire-shaped tier of their
own. UTXO snapshots are state-derived, not chain. **nipopow** (proof verify /
`isBetterThan`) was probed GO — capture is cheap (one curl, ~249 KB) and ergo-core has a
pure verification seam — but is **not a v1 kind**; when it ships it is blitzen's kind, not
donner's (§7).

## 2. Vector format (`santa-chain/v1`)

A chain vector file is a committed JSON under `vectors/chain/<version>/<provenance>/`
whose envelope mirrors the other tiers — `{schema: "santa-chain/v1", blessed_by,
entries}` — with **kind dispatch per entry** (wire's dispatch idea at entry level, block's
verdict variety). Per entry: `name · source · kind · settings · payload · expected`
(+ optional `diagnostic`).

**Key casing rule:** envelope, payload, settings, and actuals keys are **snake_case**;
*embedded node-API objects* keep their node-API shape exactly as `santa-block/v1` embeds
them — header objects stay camelCase (`parentId`, `nBits`, `powSolutions`, …) and
parameter-table keys are stringified ints. So a vector says `expected.nbits` (envelope
key) while the headers inside `anchor_headers` say `nBits` (node-API embed). All hex
strings in chain vectors and actuals are lower-case Base16 (`votes`, `boundary_votes`,
`proposed_update`, `activated_update`); grading is string-exact, so case is consensus.

**Version labels:**

- **voting → `v5` / `v6`** — voting rules are version-era-coupled (soft-fork activation is
  literally what voting decides; e.g. `Parameters.update`'s v6 arm inserts
  `SubblocksPerBlockIncrease` (id 9 → 30) only when `table(123) == 4`, id 9 is absent,
  and rule 409 is not disabled, `Parameters.scala:90-94`).
- **retargeting → `any`** — the algorithm switch is *height*-gated via settings the vector
  carries (`eip37_activation_height`), not blockVersion-gated.
- **`any` semantics:** an `any` vector is **always selected for runners mounting the
  tier** — conform's `version≤` selection treats it as match-all, and the validate guard
  admits the label. Only *vectors* get the label; a runner manifest declaring
  `version: "any"` stays an error. Kind↔version sanity is guard-enforced: retargeting ⇒
  `any`; voting ⇒ `v5`|`v6`.

The version label is a selection threshold (the minimum protocol version a runner must
implement to be graded on the vector), not scenario dating — a v6-labelled voting vector
may model an earlier-era table.

**`diagnostic` is never graded.** It is an optional per-entry object for humans and
debugging — graders never read it. Retargeting convention: `{difficulty: <decimal
string>}`, the decoded difficulty behind the expected `nbits`. Voting entries may carry
notes (e.g. `epoch_note`).

The v1 corpus is **accept-shaped**: `expected` has no reject arm and no `error` field —
every vector expects a computed value. (Reject-shaped chain vectors would be a format
revision, the `v1` → `v2` growth mechanism as in the other tiers.)

### `kind: "retargeting"`

- **`payload.target_height`** — the height T whose required difficulty is asked.
  Recalculation fires exactly when `(T − 1) % epoch_length == 0`, i.e. `T = k·epoch_length
  + 1` (classic arm; see the EIP-37 note below — `HeadersProcessor.requiredDifficultyAfter`,
  :335-381). v1 vectors MUST target recalculation points — `(T − 1) % L == 0` for the
  governing epoch length L (validate's guard enforces this); the mid-epoch parent-echo arm
  is outside the graded surface.
- **`payload.anchor_headers`** — the anchor headers, **same node-API header JSON encoding
  as `santa-block/v1`** (reuse, don't re-invent), **ascending height**, adjacent anchors
  stepping exactly `epoch_length` (classic arm; see the EIP-37 note below — the JVM
  `require`s the step, `DifficultyAdjustment.scala:116`). Normally `use_last_epochs + 1`
  = 9 headers — the heights `previousHeightsRequiredForRecalculation(T, epoch_length)`
  yields; fewer near chain start: with a single anchor the JVM echoes its difficulty
  (`calculate`'s `lengthCompare(1)` arm, `DifficultyAdjustment.scala:110-111`), and with
  2–8 anchors it interpolates over the available epoch-spaced pairs (the sliding-pairs
  arm, :113-121). The computation reads
  only `nBits` (as `requiredDifficulty`), `timestamp`, and `height` from each header —
  but full headers are embedded, matching the block tier.
- **`settings`** — `{epoch_length, use_last_epochs, block_interval_ms, initial_nbits}` +
  optional `{eip37_activation_height, eip37_epoch_length}` (mainnet: 844673 / 128). The
  classic-vs-EIP-37 dispatch is **driven by the entry's settings**: the EIP-37 arm
  (predictive + classic with 0.5×–1.5× damping, `DifficultyAdjustment.scala:76-103`) is
  taken iff the eip37 settings are present and `T ≥ eip37_activation_height`; otherwise
  classic `calculate`. (The node hardcodes the activation height and gates the arm
  mainnet-only, `HeadersProcessor.scala:338-341` — the vector carries the gate explicitly
  instead of assuming a network.)
- **When the EIP-37 arm governs, `eip37_epoch_length` replaces `epoch_length`
  throughout:** the recalculation predicate is `(T − 1) % eip37_epoch_length == 0`,
  anchor heights/spacing come from `previousHeightsRequiredForRecalculation(T,
  eip37_epoch_length)`, and it is the epoch-length argument to `eip37Calculate`
  (`HeadersProcessor.scala:341-350`: the node uses a hardcoded local `epochLength = 128`
  for the predicate and anchor heights, :341-343, and `chainSettings.eip37EpochLength.get`
  as the `eip37Calculate` argument, :350 — the two coincide on mainnet, and the entry's
  `eip37_epoch_length` carries all three roles).
- **`expected.nbits`** — integer (the 4-byte compact-bits encoding exactly as the header
  carries it; fits u32). **Graded, exact match.** Both JVM arms normalize the computed
  difficulty through a compact-bits serialization cycle
  (`DifficultyAdjustment.scala:100-102/:125-127`); `nbits =
  DifficultySerializer.encodeCompactBits(difficulty)`.
- **Version-2 carve-out:** `requiredDifficultyAfter` has a difficulty-reset arm — at
  `T ∈ {version2ActivationHeight, version2ActivationHeight + 1}` (417792 / 417793 under
  the node's defaults; testnet inherits the same value) difficulty is force-reset to
  `initialDifficultyVersion2` (`HeadersProcessor.scala:355-358`). The v1 retargeting
  payload does not model this arm — captured and authored targets MUST avoid such special
  heights unless a future revision deliberately pins them; the v1 corpus avoids them.

### `kind: "voting"`

- **`payload.boundary_height`** — the epoch-boundary height T; the JVM boundary predicate
  is `T % voting_length == 0 && T > 0` (`Header.votingStarts`, Header.scala:116).
- **`payload.current_parameters`** — `{table}`: the in-force parameters across the closing
  epoch (the previous boundary's table), in the `parameters.table` shape `santa-block/v1`
  already uses — stringified-int keys → int values. The table is the whole input:
  `Parameters.update` consults nothing else from the receiver (height stamp and carried
  update are ignored, `Parameters.scala:82-96`).
- **`payload.vote_stream`** — ordered `[{height, votes}]`, ascending, covering **exactly
  the window `[T − voting_length, T − 1]`** (`votes` = 6-hex-char string, 3 bytes — the
  header's raw votes field). Window semantics, mirrored from `ErgoStateContext.process`
  (:232-264):
  - The window's **first header is the previous boundary**, and its votes **seed the
    tally** (`VotingData(seedVotes.map(_ -> 1))`, ErgoStateContext.scala:250-251).
    Operationally: the seed is stream[0]'s votes iff stream[0] is the previous boundary
    (`stream[0].height == T − voting_length`, equivalently `stream[0].height %
    voting_length == 0`); otherwise (chain start, clamped window) the seed is empty.
  - Mid-epoch headers fold in via `VotingData.update`, which **increments only ids already
    present** (VotingData.scala:9-13) — only params the seed header voted for can
    accumulate counts at all; mid-epoch votes for any other id are silently dropped. An
    engine that naively counts every vote in the window diverges from the node.
  - The boundary header T's own votes are **NOT in the closing tally** — they derive
    `forkVote` and seed the *next* epoch.
  - **Chain-start clamp:** at the first boundary (T = voting_length) the window clamps to
    `[1, T − 1]`, no previous boundary exists, and the tally seed is empty
    (`VotingData.empty`) — so the closing tally is always empty there.
- **`payload.boundary_votes`** — **required**: 6-hex-char string, the boundary header T's
  own votes. It exists for the `forkVote` derivation: votes filtered of `NoParameter`
  (0x00) contain `SoftFork` (id 120) ⇒ `forkVote = true`
  (ErgoStateContext.scala:236-240, Parameters.scala:252).
- **`payload.proposed_update`** — hex of the serialized `ErgoValidationSettingsUpdate`
  that the boundary block's own extension carries under key `[0x00, 124]`
  (Parameters.scala:256-257, parsed via `ErgoValidationSettingsUpdateSerializer`).
- **Raw vote stream, NOT pre-counted tallies (pinned by design).** The JVM's pure seam takes
  tallies, but implementations tally by walking the window — window-walk off-by-ones are
  exactly where divergence lives (ergots' failure class). Handing the raw stream grades
  tally + update as **one composition**. Size is fine (~10 KB at mainnet 1024).
- **`settings`** — `{voting_length, soft_fork_epochs, activation_epochs}` + optional
  `{version2_activation_height}` where the era needs it (read inside `updateFork`'s forced
  v1→v2 bump, Parameters.scala:151; a 0 default is safe for v6-era tables since the bump
  requires `table(123) == 1`). Threshold semantics the oracle implements
  (VotingSettings.scala:9-11): ordinary change approved iff `count > voting_length / 2`
  (strict); soft-fork approved iff `count > voting_length × soft_fork_epochs × 9 / 10`,
  then `activation_epochs` activation epochs.
- **`expected`** — `{parameters: {table}, activated_update}`, **both graded**: the JVM
  boundary function returns the pair (`Parameters.update(height, forkVote, epochVotes,
  proposedUpdate, votingSettings): (Parameters, ErgoValidationSettingsUpdate)`,
  Parameters.scala:82-86), so the pair is the verdict — the **full** post-epoch table
  (pinned by design: full verdict, not just moved params), not a delta.

**`activated_update` / `proposed_update` encoding (pinned):** the value is the **canonical
lower-case serializer hex** of the `ErgoValidationSettingsUpdate` —
`ErgoValidationSettingsUpdateSerializer.toBytes`, Base16 (the serializer at
`ErgoValidationSettingsUpdate.scala:23`, used exactly so at Parameters.scala:225). The
empty update is therefore **`"0000"` — never `""`** (zero bytes is not a valid encoding;
`parseBytesTry("")` fails). Exactness over prettiness; graded by string equality.

## 3. Actuals shape

The runner emits one result object per entry (keyed by `name`), validated against
`schema/santa-chain.actuals.schema.json`. The verdict shape is per-kind — retargeting
`{nbits, error, note?}`, voting `{parameters: {table}, activated_update, error, note?}` —
and the union shape (the other kind's value keys present-and-null) is legal: graders read
only the entry's kind's own fields.

| Outcome | kind's value fields | `error` | `note` |
|---|---|---|---|
| Verdict | non-null | `null` | — |
| No verdict (decode/setup failure) | `null` | `"errored"` | optional |
| Not implemented | `null` | `"not-implemented"` | — |
| Panic (caught) | `null` | `"panicked"` | string |

The shared invariants carry over: **total per-entry outcomes, no abstention** — every
entry handed to a runner produces exactly one of the four rows; the kind's value fields
are non-null iff `error` is null; `note` with `panicked` is the caught-panic message,
mirroring the other tiers' panic envelope (`note` MAY accompany `errored` as a non-graded
diagnostic; v1's corpus is accept-shaped so errored rows are expected to be rare). With
an accept-shaped v1 corpus `errored` always grades coal — it exists so a runner never has
to lie or die, not as a gradable verdict.

`activated_update` in actuals follows the same pinned encoding as §2: canonical serializer
hex, `"0000"` for the empty update, never `""` — a conformer that emits `""` for
"no activation" is red by construction, and that is deliberate.

## 4. Grading (`grade_chain`)

**Value-only tier — cost is not-applicable.** Chain kinds have no script-cost semantics:
no chain actual carries a cost, the grader never reads one, and the runner manifest's
`cost` flag is irrelevant to chain slices (a runner declares the tier by listing `chain`
in `tiers`; the `cost` declaration keeps meaning only for the tiers that grade it).

Per entry the comparator (`santa-check::grade_chain(actual, entry)` — entry-taking, like
`grade_wire`, since it reads `kind` and `expected`) emits a verdict in the shared
vocabulary. **Precedence:** `panicked` → coal unconditionally; `not-implemented` → blue
coverage verdict (the growth-ledger stance); then one graded dimension, **`value`**:

- **retargeting:** nice iff `actual.error == null && actual.nbits == expected.nbits`
  (exact integer equality).
- **voting:** nice iff `actual.error == null` AND `actual.parameters` deep-equals
  `expected.parameters` (table compared key-by-key; key order irrelevant) AND
  `actual.activated_update` string-equals `expected.activated_update` (the canonical hex —
  `"0000"` ≠ `""` by construction).
- `errored` where a value is expected is coal (the whole v1 corpus expects values).
- An unknown `kind` in a graded run is coal — never a silent pass.

`diagnostic` is never graded — the grader does not read it. The verdict object is
`{"kind": "chain", "value": "nice" | "value"}` (coal carries the dimension tag, like every
tier) or the panicked/coverage envelopes, uniform with the other tiers so `conform`
tallies without tier-specific logic; chain slices appear on the board as
`chain/<version>/<provenance>` rows like every tier. `oracle/verdicts-chain.json` is the
executable form of this section; `santa-check`'s oracle test proves the grader against it
(the file lands with the grader).

## 5. Settings self-containment

**Non-negotiable: vectors are self-contained, including their settings subset.** Every
entry embeds the `ChainSettings` subset it depends on; no vector may silently assume a
network. Two cited justifications, one per side of the fence:

- **The donner votingLength conf bug** — donner's bundled conf carried mainnet
  `votingLength = 1024` against testnet's 128; a vector that assumed "the runner knows the
  network" would have blessed the bug invisible. This is the standing justification.
- **SANTA's own local instance (spike finding, 2026-06-11):** the blesser's bundled
  `jvm-blesser/src/test/resources/chain-testnet.conf` carries mainnet drift —
  `epochLength = 1024`, `blockInterval = 2m` (only the voting block was
  testnet-corrected). Harmless to date **only because no engine read those fields**
  (Tx/BlockEngine read `genesisStateDigest`, `voting.votingLength`, `powScheme` and
  nothing else); chain is the first tier that would. The bug class sits on our side of
  the fence too.

Hence the rule: **every value the computation reads comes from the entry; conf use is
template-only, for fields the computation never reads.** The gated engine materializes
its `ChainSettings` by `.copy`ing every read field from entry settings over the conf
template. The per-kind read-sets (spike-pinned):

- **retargeting** reads `blockInterval` (the desired interval in the per-epoch formula),
  `useLastEpochs`, `initialDifficulty` (the minimum-difficulty fallback; fed from
  `initial_nbits` via `decodeCompactBits`), and the epoch length (always the method
  argument) — all four from the entry — plus `eip37_activation_height` /
  `eip37_epoch_length` (entry-sourced, optional — absent ⇒ classic arm everywhere).
- **voting** reads `votingLength`, `softForkEpochs`, `activationEpochs`,
  `version2ActivationHeight` (optional in the entry; absent ⇒ 0, §2) — all from the entry
  (`VotingSettings.version2ActivationDifficultyHex` is never read by `update`; any
  template value is safe).

**Per-entry isolation** carries over from the other tiers: every entry is computed by
pure-function calls over the entry alone — entries are self-contained and unordered, and
nothing leaks between them.

## 6. Provenance

The universal provenance set, sparse per tier — chain plans three:

- **`captured`** — primary corpus: real retarget points and real voting epochs off chain
  history (testnet first, from the local node). `source` follows the block tier's
  convention (`testnet:<seed-dir>@<height>`), pointing at raw capture provenance under
  `docs/findings/chain-captures/`. Captured ⇒ the blessed output **is** chain history, and
  the blesser is FAIL-LOUD on it: a retargeting bless must equal the real target header's
  `nBits`; a voting bless must equal `Parameters.parseExtension` of the real boundary's
  extension — a mismatch is a blesser bug, never a vector. Target selection respects the
  §2 version-2 carve-out (no `T ∈ {version2ActivationHeight, version2ActivationHeight+1}`
  targets in v1). The mainnet EIP-37 transition window is wanted but **parked** — captures
  come off the testnet node and the EIP-37 arm needs mainnet anchors (no mainnet header
  source yet); v1 ships without it.
- **`authored`** — `source: "santa:<family>:<case>"` — the edge families captured history
  rarely shows: voting threshold edges
  (exactly-half vs half-plus-one; the 90% soft-fork boundary), the chain-start window
  clamp, param step limits, the forced-v2 override window, retargeting damping clamps
  (0.5× / 1.5× both hit), flat-difficulty controls.
  Synthetic deterministic inputs, but **every expected value is oracle-emitted** via the
  gated engine — hand-computed expectations are forbidden; generators assert oracle-output
  *properties* instead.
- **`spec`** — vendor `DifficultyAdjustmentSpecification` / `VotingSpecification` cases if
  extraction proves byte-stable (the same bar as the eval spec heal; else skip — a spec
  corpus inherits the spec's coverage gaps). Spec provenance is deferred in v1 — no
  source convention pinned yet.

**Re-blessing.** Producing actuals needs no oracle dependency; re-producing committed
vectors needs the gated blesser: `SANTA_TX_BLESSER=1 sbt test` in `jvm-blesser/` with
ergo-core 6.0.2.1 available (the gate name is historical — it admits ergo-core to the
build and gates the tx, block, **and chain** engines alike).

## 7. Conformer stances

| Conformer | Stance | Detail |
|---|---|---|
| **rudolph** | control (build-gated), all kinds | Declares `chain`; `santa.runner.ChainEngine` reached by reflection, exists only in ergo-core-bearing builds (the same `SANTA_TX_BLESSER` gate + seam as Tx/BlockEngine). Oracle-tautological as verification; its value is the harness control row. Ungated builds emit faithful `not-implemented`. |
| **donner** | voting + retargeting — the tier's real conformer, **LIVE** | **enr's own code**: `chain/src/difficulty.rs` (pure cores `calculate`/`eip37_calculate`/`interpolate`/`normalize_to_n_bits` over `&[&Header]` + config) and `chain/src/voting.rs` (`tally_votes_seeded`, `compute_boundary_parameters(...) → (Parameters, activated_update)` — the pure seams enr exposed for the chain arm; the earlier `tally_votes` plain counter and chain-side `apply_soft_fork_lifecycle` are RETIRED — the routing round surfaced the plain counter as a live consensus bug on enr's boundary path, fixed at enr `9ccc6e7` together with three further JVM-exactness finds: approval counts = closing-epoch-120 PLUS collected, lifecycle reads an original-table snapshot, approved-vote-for-unknown-id errors like the JVM, id 9 steppable). Its santa-run arm calls the seams with settings **from the entry**, never from `ChainConfig::testnet()`. Mounted at santa-donner `aaca7cf` (`tiers: ["block", "chain"]`). nipopow is NOT donner's: enr's nipopow is a thin wrapper over sigma-rust's `ergo-nipopow` crate — the kind belongs to the library that owns the code. |
| **blitzen-eni / develop** | nipopow at most — out of v1 | No node layer ⇒ voting/retargeting out-of-scope (grey, not a growth ledger: those are the node's functions, not the library's). If/when the nipopow kind ships (probed GO, future plan), it mounts here. |
| **dasher** | growth ledger | ergots' end goal is a node built on top of it — chain is roadmap; declares the tier with `not-implemented` entries when they choose (the not-impl ledger stance, as in eval/tx). |
| **vixen** | offered all kinds | Independent top-to-bottom impl — we test all their tiers; their call (ping with this contract). |
| **comet** | out-of-scope (grey) | Fleet is wire-only. |

## 8. Status

At this contract's commit the corpus is empty — the contract precedes the schemas,
blesser, and vectors in the build order (contract → schemas/guards → `any` selection →
`grade_chain` → engine → corpus → rudolph mount). The capture material is already in hand
and spike-verified against chain history (2026-06-11, testnet node at fullHeight 393702):

- **retargeting (→ `vectors/chain/any/captured/`):** two real testnet recalculation
  points — **T = 393601** (anchors 392576..393600, step 128) where classic `calculate`
  gives difficulty 17324703744 → `encodeCompactBits` = **84150434** == the real header's
  `nBits` (MATCH), and **T = 393473** (anchors 392448..393472) → **84128203** == real
  (MATCH).
- **voting (→ `vectors/chain/v6/captured/`):** the testnet epoch closing at boundary
  **2560** — window 2432..2559, a static epoch (all 128 votes `"000000"`), table identical
  across both boundaries, proposedUpdate `02d701990300` (= disable rules [215, 409], the
  standing testnet 6.0 soft-fork proposal re-emitted every boundary), activated update
  empty ⇒ `"0000"`. Spike output matched `Parameters.parseExtension(2560, extension)`
  (MATCH).

Authored families (threshold edges, window clamp, damping clamps) and the rudolph control
row landed with the same build series; donner's chain arm shipped same-day off the routing
prompt (santa-donner `aaca7cf` / enr `9ccc6e7` — see §7 for what the round surfaced and
fixed). nipopow: GO decision recorded, deferred to a follow-up plan. Mainnet EIP-37
window: parked (§6).

## 9. Worked example

```jsonc
// retargeting entry (lands in vectors/chain/any/captured/Retargeting.testnet_points.json, abridged)
{
  "name": "retargeting-testnet-393601",
  "source": "testnet:testnet-retarget@393601",
  "kind": "retargeting",
  "settings": {
    "epoch_length": 128, "use_last_epochs": 8,
    "block_interval_ms": 45000,          // testnet blockInterval = 45s — from the ENTRY, never a conf
    "initial_nbits": 16842752            // decodeCompactBits → difficulty 1 (testnet initialDifficultyHex "01")
  },
  "payload": {
    "target_height": 393601,             // (T-1) % 128 == 0 — a recalculation point
    "anchor_headers": [                  // 9 headers, ASCENDING, step 128: 392576..393600
      { "height": 392576, "nBits": 83934920, "timestamp": 1781063957902, "parentId": "…", … },
      …                                  // node-API camelCase embeds (nBits!), as in santa-block/v1
    ]
  },
  "expected": { "nbits": 84150434 },     // the REAL header 393601's nBits — graded exact
  "diagnostic": { "difficulty": "17324703744" }  // never graded
}

// voting entry (lands in vectors/chain/v6/captured/Voting.testnet_epoch_2560.json, abridged)
{
  "name": "voting-testnet-epoch-2560",
  "source": "testnet:testnet-voting-2560@2560",
  "kind": "voting",
  "settings": { "voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32,
                "version2_activation_height": 417792 },  // optional here: table 123 = 4, the v1→v2 bump can't fire
  "payload": {
    "boundary_height": 2560,             // 2560 % 128 == 0
    "current_parameters": { "table": { "1": 1250000, "2": 360, "3": 524288, "4": 1000000,
      "5": 100, "6": 2000, "7": 100, "8": 100, "9": 30, "123": 4 } },
    "vote_stream": [                     // exactly [2432, 2559], ascending — 2432 IS the previous
      { "height": 2432, "votes": "000000" },  // boundary; its votes SEED the tally
      …,                                 // a static epoch: all 128 entries "000000"
      { "height": 2559, "votes": "000000" }
    ],
    "boundary_votes": "000000",          // header 2560's own votes — forkVote derivation, NOT tallied
    "proposed_update": "02d701990300"    // boundary extension key [0x00,124]: disable rules [215, 409]
  },
  "expected": {
    "parameters": { "table": { "1": 1250000, "2": 360, "3": 524288, "4": 1000000,
      "5": 100, "6": 2000, "7": 100, "8": 100, "9": 30, "123": 4 } },  // FULL table, identity epoch
    "activated_update": "0000"           // the EMPTY update's canonical serializer hex — never ""
  }
}

// actuals — verdicts:
{ "retargeting-testnet-393601": { "nbits": 84150434, "error": null } }
// → {"kind": "chain", "value": "nice"}
{ "voting-testnet-epoch-2560": { "parameters": { "table": { "1": 1250000, …, "123": 4 } },
    "activated_update": "0000", "error": null } }
// → {"kind": "chain", "value": "nice"}

// the divergence surfaces this tier exists for: a conformer that tallies unseeded ids,
// counts boundary header 2560's own votes into the closing epoch, walks [T-L+1, T] instead
// of [T-L, T-1], emits "" instead of "0000" for the empty update, or reads epoch_length
// from its bundled network conf — red on exactly these vectors.
```
