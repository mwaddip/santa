# SANTA Runner Contract — Block tier (`santa-block/v1`)

> **Status: the committed result-shape contract for the block tier (`santa-block/v1`).** A lean
> companion to the eval, wire, and transaction contracts — it specifies only what is
> block-specific and inherits totality, never-panic, faithful outcomes, and the comparator
> topology from [`runner-contract.md`](./runner-contract.md).
>
> Machine-checkable schemas:
> [`schema/santa-block.vector.schema.json`](../../schema/santa-block.vector.schema.json),
> [`schema/santa-block.actuals.schema.json`](../../schema/santa-block.actuals.schema.json).
> Executable grading oracle: [`oracle/verdicts-block.json`](../../oracle/verdicts-block.json).

## 1. Tier boundary

A **block runner** decides **digest-state block validity**: given the parent state digest, a
window of ≤10 preceding headers, the in-force parameters, and a full block *with ADProofs*,
produce `valid?` + the post-state digest (+cost). Ergo's stateless-validation design makes
this library-decidable — the ADProofs are the per-block witness that lets a validator apply
the block against 33 bytes of authenticated pre-state, no UTXO database or sync required.

The tier's surface vs the transaction tier is exactly the node-only residue: ADProof
verification against header roots, section digests, PoW, the version gate, parameter-driven
cost aggregation, and the post-digest computation. Per-tx semantic violations (conservation,
script failure) are the tx tier's job and are not re-probed here.

## 2. Vector format (`santa-block/v1`)

A block vector file is a committed JSON under
[`vectors/block/v6/`](../../vectors/block/v6/) (`captured/` + `authored/`) whose envelope
schema is `santa-block/v1` and whose entries each carry the full determination set
(enforced by `schema/santa-block.vector.schema.json`):

- **`parent_digest`** — 33-byte hex (AVL root hash + height byte), the authenticated
  pre-state. The replay MUST be anchored here — a conformer that instead trusts the root
  embedded in the proof bytes accepts forged pre-states.
- **`headers`** — the ≤10 preceding headers, node-API JSON, **newest-first** (entry 0 is
  height H−1). Genesis-window entries (fewer than 10) are legal.
- **`parameters.table`** — the in-force `parametersTable` (id-string → int) read from the
  governing epoch-boundary block's extension. Carried explicitly so the vector is
  self-contained AND mutable (the cheapest cost mutation shrinks `maxBlockCost` here; a
  version-gate mutation shrinks `123`/blockVersion — meaningful only on an
  epoch-boundary donor, see §5).
- **`block`** — the full node-API block: `header`, `blockTransactions`, `extension`,
  `adProofs` with non-empty `proofBytes` (schema-required — nothing proofless is a vector).
- **`boxes`** — raw serialized bytes (`{boxId, bytes}`, hash-self-verifying) of every
  external input/data-input box. **Engines may ignore this field**: a digest-native
  validator extracts spent-box values from the proof's `Remove` leaves; the field exists
  for execution-model engines (the JVM oracle resolves script evaluation against it).
  In-block creations resolve from block outputs (createdOutputs-first).
- **`version`** — `{activated, ergoTree}`; v6 ⇒ activated 3 (validate-guarded).
- **`expected`** — `{valid, post_digest, cost, reason}`. Accept arm: `post_digest` +
  `cost` non-null, `reason` null. Reject arm: `post_digest` + `cost` null, `reason`
  non-null (schema `oneOf`-enforced).

## 3. Actuals shape

The runner emits one result object per entry (keyed by `name`), validated against
`schema/santa-block.actuals.schema.json`:

| Outcome | `valid` | `post_digest` | `cost` | `error` | `reason` | `note` |
|---|---|---|---|---|---|---|
| Verdict — accepted | `true` | hex or `null` | int or `null` | `null` | — | — |
| Verdict — rejected | `false` | `null` | `null` | `null` | string (diagnostic) | — |
| No verdict (decode/setup failure) | `null` | `null` | `null` | `"errored"` | string | — |
| Not implemented | `null` | `null` | `null` | `"not-implemented"` | — | — |
| Panic (caught) | `null` | `null` | `null` | `"panicked"` | — | string |

The eval/tx invariants carry over: `valid` non-null iff `error` null; a clean rejection is
a **verdict, a normal value**, never conflated with `errored`; `reason` is **diagnostic
only — never matched**; `note` iff `panicked`.

`post_digest` on an accept must be the **computed** post-state digest — the result of
replaying the block's state changes through the proof — not an echo of the vector's own
`header.stateRoot`. (The JVM oracle computes-and-checks: `ADProofs.verify` replays from
`parent_digest` and fails unless the result equals `header.stateRoot`.)

## 4. Grading (`grade_block`)

Per entry the comparator (`santa-check::grade_block`) emits a verdict in the shared
vocabulary. **Precedence:** `panicked` → coal unconditionally; `not-implemented` → blue
coverage verdict (growth-ledger stance); then:

3. **Accept vector** (`expected.valid == true`) — three dimensions, **chained
   valid → post_digest → cost** (a failing upstream dimension suppresses the downstream):
   - **`valid`**: nice iff `actual.error == null && actual.valid == true`.
   - **`post_digest`**: graded only when `valid` is nice; nice on equality of non-null
     digests, else coal.
   - **`cost`**: graded only when `valid` AND `post_digest` are nice and **both** sides
     declare a non-null cost (`runner.json: cost: false` runners emit `null` — ungraded,
     not coal); nice/coal on equality.
4. **Reject vector** (`expected.valid == false`) — one dimension: nice iff
   `actual.valid == false && actual.error == null`. `errored` on a reject vector is
   **coal** (the tx-tier stance, by design — see the tx contract §4 for why this
   deliberately diverges from the eval reject arm). `post_digest` and `cost` are never
   graded on reject.

The verdict object is `{"kind": "block", "valid": …, "post_digest": …, "cost": …}` (or
panicked/coverage), uniform with the other tiers so `conform` tallies without tier-specific
logic. `oracle/verdicts-block.json` is the executable form; `santa-check`'s oracle test
proves the grader against it.

## 5. The validation-context contract

- **Per-entry isolation.** Every entry is validated by a **fresh validator instance**
  seeded at `(parent_digest, height)` — entries are self-contained and unordered; nothing
  may leak between them (enr's `DigestValidator::from_state(parent_digest, height,
  checkpoint = 0)` per entry; the JVM engine is stateless per call).
- **Checkpoint-free rule.** Conformers with checkpoint shortcuts must run checkpoint-free
  (`checkpoint = 0`); a vector below a checkpoint height must still be fully validated.
- **Headers window.** The ≤10 headers are the full chain context handed over. Anything
  needing deeper history is out of scope for `santa-block/v1`.
- **Difficulty-retarget exclusion.** PoW is checked against **the header's own `nBits`**,
  not the retarget schedule — the window is too shallow to recompute difficulty. A block
  with self-consistent PoW but a wrong difficulty would not be caught; that class needs a
  deeper-window format revision (the `v1` → `v2` growth mechanism, as in eval and tx).
- **Stepped-boundary exclusion (same class).** At an epoch boundary the JVM derives the
  new parameters from the pre-state params + the epoch's accumulated votes; a boundary
  where votes actually STEP a parameter would need the epoch's vote history — deeper than
  the ≤10-header window. `santa-block/v1` boundaries are therefore vote-neutral
  (calculated == pre-state == the handed table); stepped boundaries are `v2`-growth
  material alongside the retarget class.
- **PoW seals the header.** Autolykos hashes the header bytes, so *any* header-field
  tamper (stateRoot, transactionsRoot, version, …) invalidates PoW before the named
  subsystem check is reached on a PoW-checking conformer. Mutation authors target the
  unsealed side (sections, the handed parameters, proof bytes) when they want a specific
  downstream gate to fire; header tampers are PoW probes (and post-digest/section probes
  only for conformers without a PoW check). The committed reject arm encodes this.
- **Proofs-section canonicality.** `blake2b256(proofBytes) == header.adProofsRoot` is part
  of validity — a proof that *replays correctly* but isn't byte-identical to the committed
  section is a consensus reject. This class is live in the wild: the rust AVL prover emits
  valid-but-non-canonical proofs for data-input lookups
  ([`ADPROOF-FINDING.md`](../findings/testnet-powhit-return-type/ADPROOF-FINDING.md)) —
  the reason seed 28474 is not yet a vector.
- **The version gate fires only at epoch boundaries.** The JVM compares the parameters'
  declared blockVersion to `header.version` only inside `processExtension`, which runs iff
  `header.votingStarts(votingEpochLength)` (`ErgoStateContext.scala:246`); mid-epoch the
  node never makes the comparison. Conformers must mirror exactly that — an unconditional
  check is stricter than consensus (a donner-surfaced, JVM-verified finding: the oracle's
  own first composition had this bug, and the original mid-epoch version-gate mutation
  blessed a reject the real chain would not produce).
- **JVM oracle recipe (compact).** Gated `BlockEngine` (ergo-core composition,
  equivalence-anchored against the node-module `ErgoState.execTransactions` on block 2666):
  header/section tier (`exBlockVersion` params-vs-header at epoch boundaries only ·
  `hdrPoW` `powScheme.validate` · `bsCorrespondsToHeader` transactionsRoot +
  proofs-section digest) → per-tx `validateStateless`/`validateStateful` with threaded accumulated cost
  (first failure stops, node `cfor` semantics) → `ADProofs.verify` replaying the
  reproduced `ErgoState.stateChanges` (TreeMap-ordered Remove/Insert with in-block
  create-then-spend collapse; dataInput Lookups in tx order; operations =
  toLookup ++ toRemove ++ toAppend) from `parent_digest` to `header.stateRoot`.

## 6. Provenance

Two provenances, distinguished by the `source` prefix (validate-guarded):

- **`captured`** — primary corpus. `source: "testnet:<seed-dir>@<height>"`. Captured
  blocks are on-chain history ⇒ inherently `valid: true`; the blesser (`CapturedBlock`)
  hard-fails on any oracle rejection, and a committed captured vector with
  `expected.valid: false` is a guard violation. Capture inputs (full block with verified
  proofBytes, box bytes, headers window, epoch block) live in `docs/findings/<seed>/`.
- **`authored`** — the reject arm; `source: "santa:mutation:<class>:over:<donor>"`.
  Single-fault mutations over a captured donor, each blessed only after the oracle
  confirms rejection AND the recorded reason carries the intended class signal
  (`AuthoredBlockMutations` — wrong-reason rejection fails the bless). `expected.reason`
  is the oracle's reason **verbatim** (diagnostic-only).

**Re-blessing.** Producing actuals needs no oracle dependency; re-producing committed
vectors needs the gated blesser: `SANTA_TX_BLESSER=1 sbt test` in `jvm-blesser/` with
ergo-core 6.0.2.1 available (the gate name is historical — it admits ergo-core to the
build and gates the tx **and** block engines alike).

## 7. Conformer stances

| Conformer | Stance | Detail |
|---|---|---|
| **rudolph** | control (build-gated) | Declares `block`; `santa.runner.BlockEngine` reached by reflection, exists only in ergo-core-bearing builds (maintainer machines + conform CI). Oracle-tautological as verification; its value is the harness control row. Ungated builds emit faithful `not-implemented`. |
| **donner** | full — the tier's real conformer (`cost: true`) | ergo-node-rust's `validation` crate over its digest-state seam (`DigestValidator::from_state` + `BlockValidator::apply_state`), mounted as the `runners/donner` submodule (santa-donner repo; `impl` clones ergo-node-rust). enr surfaces block cost and enforces the maxBlockCost sum. Build-identity (§3 of the core contract): the runner's Cargo manifest declares the sigma-rust rev + ergo_avltree_rust fork pin stack. |
| **blitzen-eni / develop** | out-of-scope (grey) | sigma-rust verifies transactions given inputs but applies no blocks — no block-application surface to conform. Not a growth ledger: block application is the node's layer, not the library's. |
| **dasher** | out-of-scope (grey) | ergots likewise has no block-application surface today. Becomes a growth ledger only if/when ergots declares the tier. |
| **comet** | out-of-scope (grey) | Fleet is wire-only. |

## 8. Status

**Captured (4):** `bigint-downcast-2666` (cost 39379 — the triple-anchored keystone) ·
`deserialize-context-111927` (170876) · `atleast-degenerate-bound-184137` (40020) ·
`epoch-boundary-2560` (12344 — capture material, not a divergence seed: the only
boundary-height seed, so its accept arm exercises the epochStarts path and it donates
the version-gate mutation), each ADProofs-verified `parent_digest → header.stateRoot`
at bless time.
**Pending:** `powhit-return-type-28474` — blocked on a canonical (JVM-sourced) proof; the
rust-regenerated proof verifies but is non-canonical for the data-input Lookup (the
ADPROOF-FINDING). Joins when a JVM UTXO source regenerates it or the fork fix lands.

**Authored (6):** `params-shrink-maxBlockCost` · `stateroot-flip` · `adproof-tamper` ·
`txs-reorder` · `pow-solution-flip` over the 2666 donor + **`version-gate` over the
epoch-boundary-2560 donor** (its first, mid-epoch authoring was retired by the §5
finding; over a boundary the gate genuinely fires on-chain). The mutation shrinks the
HANDED `parameters.table["123"]` while the block's own extension still packs
blockVersion 4 — a validator must derive the boundary check from the handed pre-state
params, not the block's self-declared extension (the JVM rejects via `exBlockVersion`,
and `exMatchParameters` would fire too: written ≠ calculated).

**Board:** rudolph (control) and donner both `captured: valid 4/4 · digest 4/4 ·
cost 4/4` + `authored: valid 6/6` — donner's initial version-gate accept (it sourced
the boundary check from the block's extension rather than the handed pre-state params)
was routed and fixed same-day (enr `380941a` hands the vector table as
`expected_boundary_params`, the node's own sync wiring; its reject reason is the
`exMatchParameters` twin — verdict identical, reason diagnostic-only). vixen
(arkadianet/ergo) `captured 4/4·4/4·3/4` + `authored 5/6` — the version-gate accept
plus its 111927 cost divergence (169202 vs blessed 170876); theirs to take.

## 9. Worked example

```jsonc
// vector entry (vectors/block/v6/captured/bigint-downcast-2666.json → entries[0], abridged)
{
  "name": "bigint-downcast-2666",
  "source": "testnet:testnet-bigint-downcast-v3@2666",
  "parent_digest": "ac66508bc8d423d8…ce11bd6d0d", // stateRoot of header H-1 (33 bytes hex)
  "headers": [ { "height": 2665, … }, … ],     // newest-first, ≤10
  "parameters": { "table": { "4": 1000000, "123": 4, … } },
  "block": { "header": { … }, "blockTransactions": { … }, "extension": { … },
             "adProofs": { "proofBytes": "03…" } },
  "boxes": [ { "boxId": "b98a06…", "bytes": "80…" }, … ],
  "version": { "activated": 3, "ergoTree": 3 },
  "expected": { "valid": true,
                "post_digest": "40e3b4b002b7abe56c8da96442bcd042c60c031ca8a5abd4cb288f9b97524dfa0d",
                "cost": 39379, "reason": null }
}

// accept, full claim (control row):
{ "bigint-downcast-2666": { "valid": true, "post_digest": "40e3…0d", "cost": 39379, "error": null } }
// → {"kind": "block", "valid": "nice", "post_digest": "nice", "cost": "nice"}

// reject arm (authored mutation), clean reject:
{ "params-shrink-maxBlockCost": { "valid": false, "post_digest": null, "cost": null,
    "error": null,
    "reason": "tx[0]: org.ergoplatform.validation.MalformedModifierError: Accumulated cost of block transactions should not exceed <maxBlockCost>. 23c73fb1…: initial cost" } }
// → {"kind": "block", "valid": "nice", "post_digest": "n/a", "cost": "n/a"}

// a digest-state conformer that ignored parent_digest or skipped the adProofsRoot
// linkage would go red here — that surface is the tier's reason to exist.
```
