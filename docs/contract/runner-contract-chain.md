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
no ADProofs. Four kinds in v1:

- **`retargeting`** — the required difficulty (`nBits`) for a target height, computed from
  epoch-spaced anchor headers. Fork-critical: a node computing wrong required difficulty
  rejects valid blocks or accepts invalid ones.
- **`voting`** — the parameter-voting math at an epoch boundary: from the epoch's vote
  stream, the in-force parameters, and the proposed validation-settings update, produce the
  full next-`Parameters` table plus the activated update. Pedigree: a known real divergence
  (ergots' first failure walking the chain was voting).
- **`fork_vote_gate`** — the per-header fork-vote prohibition gate
  (`ErgoStateContext.checkForkVote`, rule 407, ErgoStateContext.scala:156-168): pins whether
  a header is forbidden from carrying a fork-vote based on its height relative to an
  in-progress round's finishing window.
- **`header_votes`** — per-header validation of the 3-byte `header.votes` field
  (`ErgoStateContext.validateVotes`, rules 212–215, ErgoStateContext.scala:329-346): checks
  that the vote bytes are structurally legal — no excess non-soft-fork votes, no duplicates,
  no contradictory pairs. Version-independent (`any`), like retargeting.

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
  `any`; voting ⇒ `v5`|`v6`; fork_vote_gate ⇒ `v6`; header_votes ⇒ `any`.

The version label is a selection threshold (the minimum protocol version a runner must
implement to be graded on the vector), not scenario dating — a v6-labelled voting vector
may model an earlier-era table.

**`diagnostic` is never graded.** It is an optional per-entry object for humans and
debugging — graders never read it. Retargeting convention: `{difficulty: <decimal
string>}`, the decoded difficulty behind the expected `nbits`. Voting entries may carry
notes (e.g. `epoch_note`).

The v1 corpus is **accept-shaped** for retargeting and the bulk of voting vectors: `expected`
carries computed values. Voting and fork_vote_gate add reject arms in v1 (`expected.error: "errored"` — see
below); retargeting and header_votes have no reject arm (retargeting: no known JVM-throw
class at the pure-retarget seam over schema-valid anchors; header_votes: pure byte checks
that cannot throw). Broader reject-shaped expansion across kinds would be
a format revision, the `v1` → `v2` growth mechanism as in the other tiers.

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

**The reject form (v1 — voting, and fork_vote_gate per its own subsection below):** `expected` MAY instead be `{"error": "errored"}` —
no value keys. A reject vector pins an input the JVM itself THROWS on (throw parity:
"if the JVM is hostile, so are we" — a divergence here is a block one node accepts and
another rejects). The three authored classes in v1: a table carrying 122 without 121
(`votes` forces `parametersTable(121)`, Parameters.scala:108 — `NoSuchElementException`),
approved votes for a table-absent id (`updateParams` reads `parametersTable(paramIdAbs)`,
Parameters.scala:167), and a proposed update disabling a MANDATORY rule
(`ErgoValidationSettingsUpdate`'s require, ErgoValidationSettingsUpdate.scala:48 — fires
at extension parse). The reject reason is diagnostic-only (`diagnostic.oracle_note`
carries the JVM throw text; never graded, never cross-matched). Retargeting stays
accept-only in v1 — no known JVM-throw class at the pure-retarget seam over
schema-valid anchors.

Two layering notes (enr's lifecycle cross-read, 2026-06-12). (1) The mandatory-rule
reject pins the DESERIALIZER layer — correct for this tier (bytes that cannot
deserialize cannot exist as `Parameters.update` input) — but IN-BAND the JVM does not
reject such a block: `Parameters.parseExtension` swallows a failed 124-field parse to
the empty update (`.toOption.getOrElse(empty)`, Parameters.scala:382-386). A live
wrapper may strict-parse-then-swallow for two-layer parity; only the pure seam rejects.
Pinning the in-band inertness would be a block-tier vector class, not this kind's.
(2) The 122-without-121 throw is LAZY (`lazy val votes`) — it fires only at boundaries
that FORCE `votes` (the accumulate window and the three checkpoint heights, after their
height conjuncts match); at any other boundary the orphan table passes through
un-thrown. Both arms are pinned: `hostile-122-without-121` (a force site) and
`leniency-122-without-121-nonforce` (the pass-through with an ordinary step proving the
pipeline ran).

**Authored `proposed_update` payloads are rules-only for now** (statusUpdates count 0):
sigma's `RuleStatusSerializer` has no Rust port yet, so conformers strict-parse the
rules section and the statusUpdates COUNT but pass `count > 0` entries unvalidated —
malformed-statusUpdates reject vectors would red against them by design until the port
lands (sigma-rust scope). Also recorded: in-table UNKNOWN ids are legal JVM chain state
(`Map[Byte, Int]`; steppable via the `getOrElse` defaults, Parameters.scala:168-170)
but unrepresentable in closed-enum conformer seams — authoring such vectors requires a
coordinated heads-up first (enr would need a seam representation change).

**`activated_update` / `proposed_update` encoding (pinned):** the value is the **canonical
lower-case serializer hex** of the `ErgoValidationSettingsUpdate` —
`ErgoValidationSettingsUpdateSerializer.toBytes`, Base16 (the serializer at
`ErgoValidationSettingsUpdate.scala:23`, used exactly so at Parameters.scala:225). The
empty update is therefore **`"0000"` — never `""`** (zero bytes is not a valid encoding;
`parseBytesTry("")` fails). Exactness over prettiness; graded by string equality.

### `kind: "fork_vote_gate"` (the third kind, v1.2)

**`fork_vote_gate` entries** pin `ErgoStateContext.checkForkVote` (rule 407,
ErgoStateContext.scala:156-168) — the per-header gate that prohibits fork-voting in defined
windows around an in-progress round's finish. **Settings:** the FULL voting block
(`voting_length`, `soft_fork_epochs`, `activation_epochs`, `version2_activation_height`) —
the last is present but UNREAD (uniformity with the voting kind; conformer settings decoders
stay shared). **Payload:** `{height, header_votes, current_parameters: {table}}` — `height`
is the HEADER's height, any value ≥ 1 (mid-epoch heights are the point: this is
header-acceptance, not boundary computation); `header_votes` the 3-byte hex; the gate
applies iff 120 ∈ votes (the JVM call site's `if (forkVote)`, folded into the seam) — and
this precondition PRECEDES the table read, so non-120 votes pass even over a hostile table.
The gate's operand is `table[121]` ONLY (`softForkVotesCollected.get` — an EAGER read, the
deliberate contrast to the voting kind's lazy `votes`: the same orphan-122 table is
lazy-lenient there and eager-fatal here), and the windows are `[finishing, finishing+L)` when
NOT approved / `[finishing, finishing+L·(ae+1))` when approved, `finishing = S + L·ve`.

`expected` is `{"valid": true|false}` — BOTH values are clean verdicts (`true` = pass;
`false` = the rule-407 prohibition) — or the §2 reject form `{"error": "errored"}` for the
eager-`.get` class (122-without-121 with 120 in votes). **Deliberate-design note (enr's
flag, accepted):** `valid: false` and `errored` are JVM-INDISTINGUISHABLE at the
block-acceptance observable — both throws land inside `validateNoThrow` and surface
identically as rule-407 invalid. The split pins implementation mechanics FINER than the JVM
observable (clean prohibition vs exception-on-hostile-state), the same stance as the
panicked/errored distinction: a conformer folding the missing-121 throw into `valid: false`
is consensus-equivalent in-band but red by design here.

### `kind: "header_votes"` (the fourth kind, v1.3)

**`header_votes` entries** pin `ErgoStateContext.validateVotes` (rules 212–215,
ErgoStateContext.scala:329-346) — the per-header structural check of the 3-byte
`header.votes` field. **Settings:** the FULL voting block (`voting_length`,
`soft_fork_epochs`, `activation_epochs`, `version2_activation_height`) — present but UNREAD
by the seam (uniformity with fork_vote_gate; conformer settings decoders stay shared). The
seam takes only the raw vote bytes. **Payload:** `{"votes": "<6 hex chars>"}` — the 3 vote
bytes only. This is simpler than fork_vote_gate (which also carries `height` and
`current_parameters`): `validateVotes` needs nothing but the bytes.

The method computes `votes = header.votes.filter(_ != Parameters.NoParameter)` (the
0-filtered slice; only this slice is checked) and then enforces three rules — a count guard (212) and two per-element
iteration checks (213, 214):

- **212 `hdrVotesNumber`** (`mayBeDisabled = true`): `votes.count(_ != Parameters.SoftFork)
  <= Parameters.ParamVotesCount` — equivalently, at most **2 non-120 entries** in the
  0-filtered slice. `SoftFork` (120) and `NoParameter` (0, already filtered) do NOT count
  against the limit; a header carrying `[120,120,0]` satisfies 212. (Rule is `mayBeDisabled
  = true` at ValidationRules.scala:141, unlike 213 and 214 which are `mayBeDisabled =
  false`. This distinction is documentation-only for v1 vectors — the seam runs all three
  unconditionally; the disable path is not exercised.)
- **213 `hdrVotesDuplicates`** (`mayBeDisabled = false`): each entry in the 0-filtered
  `votes` appears exactly once. **Asymmetry with rule 212:** 120 (`SoftFork`) is free for
  the *count* but is NOT exempt here — a doubled `[120,120,0]` has `votes = [120,120]`
  after filtering and trips 213 and rejects. Only `NoParameter` (0) is removed before these
  checks; nothing else is.
- **214 `hdrVotesContradictory`** (`mayBeDisabled = false`): for each `v` in the
  0-filtered `votes`, `(-v).toByte` must not also appear. The `reverseVotes` array is
  computed over the SAME 0-filtered slice as the duplicate check. **Edge: `0x80` (−128 as
  i8) is its own negation** — `(-0x80).toByte == 0x80` in Scala's byte arithmetic — so a
  lone `[0x80,0,0]` self-contradicts and rejects on rule 214.
- **215 `hdrVotesUnknown`** — **DEFERRED / out of scope.** The rule fires only when
  `epochStarts` (height-dependent, ErgoStateContext.scala:331: `header.votingStarts(
  votingSettings.votingLength)`), making it stateful/height-dependent, and it is
  `mayBeDisabled = true` (ValidationRules.scala:150). donner defers it pending dynamic
  rule-status tracking. **Do not author a 215 arm in v1.** If it is ever authored, it must
  be flagged first — the defer is a deliberate design decision, not an oversight.

**Version dir: `any/`.** `validateVotes` is version-independent: the three active rules
have no era-gate, and the `any` label carries the same semantics as for retargeting —
always selected for runners mounting the tier.

**`expected` is two-outcome only: `{"valid": true}` or `{"valid": false}`.** There is NO
errored arm for this kind. Pure byte checks over a 3-byte array cannot throw — the three
active rules are all range/membership checks with no eager table reads and no
`require`/`get` calls that could generate exceptions. **Contrast with fork_vote_gate**,
which has an errored arm precisely because its seam performs an eager `table[121].get` that
throws on a 122-without-121 table; `header_votes` has no such access. A conformer that
emits `errored` on a `header_votes` entry is graded coal on an accept vector and coal on a
reject vector (there is no `expected.error == "errored"` class for this kind).

## 3. Actuals shape

The runner emits one result object per entry (keyed by `name`), validated against
`schema/santa-chain.actuals.schema.json`. The verdict shape is per-kind — retargeting
`{nbits, error, note?}`, voting `{parameters: {table}, activated_update, error, note?}`,
fork_vote_gate `{valid, error, note?}`, header_votes `{valid, error, note?}` — and the
union shape (other kinds' value keys present-and-null) is legal: graders read only the
entry's kind's own fields.

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
diagnostic). On accept vectors `errored` grades coal; on reject vectors
(`expected.error == "errored"`, voting and fork_vote_gate) `errored` is the graded NICE
outcome — the runner's consensus seam rejected the inputs exactly as the JVM does. `panicked` is
NEVER the expected outcome, reject vectors included: throw parity means CATCHING and
classifying the rejection, not crashing (a conformer whose chain arm dies on hostile
input is red by design).

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
- **voting reject vectors** (`expected.error == "errored"`): nice iff
  `actual.error == "errored"`. A produced value where the JVM throws is coal; `panicked`
  is coal by the standing precedence (it never reaches the value dimension).
  `not-implemented` stays the blue coverage verdict per the precedence above.
  (This deliberately INVERTS the block tier's reject arm, where `errored` is coal —
  block models rejection as a first-class `valid:false` verdict, so `errored` there is
  a failed verdict; chain has no clean-reject verdict slot — the JVM THROWS on these
  inputs, and faithful parity surfaces the throw as `errored`.)
- **fork_vote_gate:** nice iff `actual.error == null` AND `actual.valid ==
  expected.valid` (boolean equality — `valid: false` expectations grade a clean
  prohibition as nice). Reject vectors (`expected.error == "errored"`) grade exactly as
  the voting reject arm: nice iff `actual.error == "errored"`; `panicked` stays coal by
  precedence.
- **header_votes:** nice iff `actual.error == null` AND `actual.valid == expected.valid`
  (boolean equality — identical to fork_vote_gate's accept-vector rule). **No reject
  vectors exist for this kind** (§2 header_votes): there is no `expected.error ==
  "errored"` class; `errored` from a conformer on any header_votes entry is coal.
- `errored` where a value is expected is coal (accept vectors are the corpus bulk; the
  reject arm above is the one place `errored` grades nice).
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
- **fork_vote_gate** reads `votingLength`, `softForkEpochs`, `activationEpochs` from the
  entry; `version2ActivationHeight` is present-but-unread (uniformity with voting, §2).
- **header_votes** reads **nothing** from entry settings — the seam operates on the 3 vote
  bytes only; the settings block is present-but-unread in full (§2 uniformity). Any
  template value is safe for all four fields.

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
  (exactly-half vs half-plus-one; the 90% soft-fork boundary; id-9 steppability), the
  chain-start window clamp, the soft-fork-round lifecycle (handed-121/122 state,
  spike-proven ≡ chain-accumulated: round start / accumulation / wait identity /
  failed-cleanup + restart / activation incl. the v3→v4 id-9 insertion and its rule-409
  suppression / post-activation cleanup + same-boundary restart — every restart pins the
  snapshot semantics: guards read the ORIGINAL table, fresh 121 is always 0), the zombie
  class (approval flipping between checkpoints: survive→fail-activation→late-cleanup
  without a version bump; the stuck terminal state where no round can ever start again),
  hostile tables (the §2 reject classes), param step limits, the forced-v2 override
  window, retargeting damping clamps (0.5× / 1.5× both hit), flat-difficulty controls,
  the fork-vote gate (window edges across both approval arms — the 3686/3687
  collected-only threshold flips the verdict across [finishing+L, afterActivation); the
  during-voting leniency at finishing−1; the 120-precondition and no-round pass-throughs;
  the eager-.get reject).
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
| **donner** | voting + retargeting + fork_vote_gate + header_votes — the tier's real conformer, **LIVE** | **enr's own code**: `chain/src/difficulty.rs` (pure cores `calculate`/`eip37_calculate`/`interpolate`/`normalize_to_n_bits` over `&[&Header]` + config) and `chain/src/voting.rs` (`tally_votes_seeded` — returning the **ordered `Vec<(i8, u32)>`** since enr `2c24e08`: seed-slot order, duplicates kept, find-first 120; it replaced a `HashMap` whose per-instance iteration order made order-sensitive grades a per-run coin flip, exactly what the `Voting.tally_order` vectors pin — and `compute_boundary_parameters(...) → (Parameters, activated_update)` (since enr `611219f`/donner `5712d1b` the activated update returns as the parsed VALUE — statuses retained — and the arm encodes it via enr's fallible `encode_validation_settings_update`, the canonical serializer port that Errs exactly where the JVM `putUShort(ruleId − 1000)` require-fails ⇒ errored envelope; it replaced an input-byte ECHO that the `Voting.status_updates` canonicalization arms caught — invisible on canonical input, where echo ≡ re-serialize) — the pure seams enr exposed for the chain arm; the earlier `tally_votes` plain counter and chain-side `apply_soft_fork_lifecycle` are RETIRED — the routing round surfaced the plain counter as a live consensus bug on enr's boundary path, fixed at enr `9ccc6e7` together with three further JVM-exactness finds: approval counts = closing-epoch-120 PLUS collected, lifecycle reads an original-table snapshot, approved-vote-for-unknown-id errors like the JVM, id 9 steppable). Its santa-run arm calls the seams with settings **from the entry**, never from `ChainConfig::testnet()`. Mounted at santa-donner `aaca7cf` (`tiers: ["block", "chain"]`). nipopow is NOT donner's: enr's nipopow is a thin wrapper over sigma-rust's `ergo-nipopow` crate — the kind belongs to the library that owns the code. fork_vote_gate arm MOUNTED at santa-donner 03e5443 against enr's `voting::check_fork_vote` tri-state seam (enr 2c24e08 — the gate is also live in their node header paths: `validate_child` / `no_pow` / `reorg`) — mounted ahead of the families, graded the moment they land. header_votes arm MOUNTED ahead of vectors against enr's `voting::check_header_votes([u8;3]) → Result<(), ChainError>` seam (enr `3378fa2`) — graded the moment vectors land. |
| **blitzen-eni / develop** | nipopow at most — out of v1 | No node layer ⇒ voting/retargeting/header_votes out-of-scope (grey, not a growth ledger: those are the node's functions, not the library's). If/when the nipopow kind ships (probed GO, future plan), it mounts here — and at vixen, whose NiPoPoW is an own implementation (`ergo-validation/src/popow/`: prove, verifier, `best_arg`, interlinks, batch-merkle — no sigma-rust wrapper); weigh both in the kind's design. |
| **dasher** | growth ledger | ergots' end goal is a node built on top of it — chain is roadmap; declares the tier with `not-implemented` entries when they choose (the not-impl ledger stance, as in eval/tx). |
| **vixen** | retargeting + voting — **LIVE** | Independent top-to-bottom impl, mounted same-day off the routing prompt (santa-vixen `80d4473` / arkadianet `fa97cfc`): retargeting via `ergo_crypto::difficulty::next_n_bits` with per-entry `DifficultyParams` (eip37 pair as `Option`s from settings); voting via `compute_epoch_votes` (natively seeded-tally semantics) + `compute_next_params` returning the JVM pair. §5 honored with one recorded impl caveat: arkadianet hardcodes `use_last_epochs = 8` as a consensus constant (not threaded from the entry) — every v1 vector carries 8; a future ≠8 vector would faithfully grade arkadianet's 8. fork_vote_gate: not yet probed — their equivalent surface unknown; the kind grades not-implemented/panicked on their runner until they mount (routing probe queued). header_votes: not yet probed — no arm; grades not-implemented until mounted. |
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
