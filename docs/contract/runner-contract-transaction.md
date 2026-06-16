# SANTA Runner Contract — Transaction tier (`santa-transaction/v1`)

> **Status: the committed result-shape contract for the transaction tier (`santa-transaction/v1`).** A
> lean companion to the frozen eval and wire contracts — it specifies only what is transaction-specific
> and inherits totality, never-panic, faithful outcomes, and the comparator topology from
> [`runner-contract.md`](./runner-contract.md).
>
> Machine-checkable schemas:
> [`schema/santa-transaction.vector.schema.json`](../../schema/santa-transaction.vector.schema.json),
> [`schema/santa-transaction.actuals.schema.json`](../../schema/santa-transaction.actuals.schema.json).
> Executable grading oracle: [`oracle/verdicts-transaction.json`](../../oracle/verdicts-transaction.json).

## 1. Tier boundary

A **transaction runner** decides **library-decidable validity**: given one signed transaction,
all boxes it references, and a minimal synthetic context, produce `valid?` (+cost). Everything
needed for the determination is handed in by the vector; no node, no UTXO lookup, no PoW or
block verification is involved. The question is: given these inputs, would `validateStateful`
accept or reject this transaction? That answer is within the reach of any conformer that
exposes stateful tx validation through its public API.

## 2. Vector format (`santa-transaction/v1`)

A transaction vector file is a committed JSON under
[`vectors/transaction/v6/captured/`](../../vectors/transaction/v6/captured/) whose envelope
schema is `santa-transaction/v1` and whose entries each carry the full determination set
(enforced by `schema/santa-transaction.vector.schema.json`):

The tx, boxes, and context are carried as **sigma-serialized BYTES**, not JSON. Bytes are the
consensus-unambiguous form: they preserve context-extension wire ORDER (which a JSON object's
integer keys reorder ascending — a JS `JSON.parse` does this *at parse time*, silently
re-signing a non-ascending extension into a different `bytes_to_sign` → rejecting a chain-valid
tx) and u64 precision (which `JSON.parse` truncates past 2⁵³). Every conformer — JVM, sigma-rust,
ergots — decodes bytes faithfully; no impl can re-order or truncate them.

- **`tx_bytes_hex`** — the signed transaction, canonical sigma bytes (the `parseTransaction`
  input). The runner deserializes it directly; the JVM oracle decodes the same bytes via
  `ErgoTransactionSerializer`, identical by construction.
- **`input_boxes_hex`** — spent input boxes as `ErgoBox` bytes, in tx-input order.
- **`data_input_boxes_hex`** — data-input boxes as `ErgoBox` bytes (may be empty).
- **`headers_hex`** — the last (up to 10) headers, **NEWEST-first** (scorex bytes) — the provided
  block context (`ErgoStateContext.lastHeaders`).
- **`preHeader`** — the validating block's pre-header: `{version, parentId, timestamp` (u64 string)
  `, nBits, height, minerPk, votes}`.
- **`parameters`** — `{maxBlockCost, storageFeeFactor, minValuePerByte, inputCost, dataInputCost,
  outputCost, tokenAccessCost}` (the testnet table at the capture height).
- **`context.height`** — the block height (`== preHeader.height`).
- **`version.activated`** + **`version.ergoTree`** — the protocol versions the scripts compile/verify under.
- **`expected`** — the JVM-blessed `{valid: bool, cost: int|null, reason: null|string}`.

There is no `expected.error` field. The oracle either accepted (with a cost) or rejected (with
a reason); "errored" as a vector state does not exist — the blesser hard-fails on any oracle
failure against a captured seed (`CapturedTxFull` `FAIL-LOUD-on-valid:false`).

A worked example is at §9.

## 3. Actuals shape

The runner emits one result object per entry (keyed by `name`), validated against
`schema/santa-transaction.actuals.schema.json`:

| Outcome | `valid` | `cost` | `error` | `reason` | `note` |
|---|---|---|---|---|---|
| Verdict — accepted | `true` | `int` or `null` | `null` | — | — |
| Verdict — rejected | `false` | `null` | `null` | string (diagnostic) | — |
| No verdict (decode/setup failure) | `null` | `null` | `"errored"` | string | — |
| Not implemented | `null` | `null` | `"not-implemented"` | — | — |
| Panic (caught) | `null` | `null` | `"panicked"` | — | string |

**`valid` is non-null if and only if `error` is null** (enforced by the actuals schema). A
`valid: false` result — a clean rejection — is a **verdict, a normal value**; the impl
reached a decision and the decision was "invalid". `error: "errored"` means the runner
**failed to reach a verdict** (decode failure, context setup failure, or an implementation
error that precluded even posing the question). **Never conflate them.** A reject that
`errored` instead of producing `valid: false` is coal, not a clean rejection (§4 explains
why this differs from the eval reject arm).

`reason` carries the implementation's rejection/error string and is **diagnostic only —
never matched**. It flows through to the run's `results.json` for the write-up, not the
grader.

`note` is present if and only if `error == "panicked"` and carries the panic message/class
(schema-enforced).

`cost` on an accept verdict is `null` when the runner does not claim the cost dimension
(develop branch; `runner.json: cost: false`), non-null when it does (eni branch;
`runner.json: cost: true`). A vector may carry `cost: null` in `expected` (value-only
authoring) — legal; see §4 on cost grading.

## 4. Grading (`grade_transaction`)

Per entry the comparator (`santa-check::grade_transaction`) emits a verdict in the shared
vocabulary. **Precedence:**

1. **`panicked` → coal unconditionally** — before the accept/reject check. A crash is not a
   clean rejection, which is why `panicked` is a distinct tag and not a refinement of
   `errored`.

2. **`not-implemented` → `{"kind": "coverage", "tag": "not-implemented"}`** — coverage
   verdict, not coal. The runner did not engage the tier; per the suite-wide growth-ledger
   stance, coverage verdicts count into the aggregate red figure. An all-not-impl transaction
   slice renders as a blue coverage cell on the scoreboard, not a red one.

3. **Accept vector** (`expected.valid == true`): two independent dimensions.
   - **`valid`**: `"nice"` iff `actual.error == null && actual.valid == true`; else `"value"`
     (coal).
   - **`cost`**: graded only when **both** `expected.cost` is non-null **and**
     `actual.cost` is non-null; `"nice"` or `"cost"` (coal) on equality. In all other
     cases — `expected.cost` null (value-only vector), or `actual.cost` null (runner doesn't
     claim cost), or `valid` not nice — `"cost": "n/a"` (ungraded, not coal). **Cost is an
     accept-arm dimension: it is never graded on reject.**

4. **Reject vector** (`expected.valid == false`): one dimension (`valid`); cost always `n/a`.
   - `"nice"` iff `actual.valid == false && actual.error == null` — a clean rejection.
   - `actual.error == "errored"` → `"value"` (coal).
   - `actual.valid == true` → `"value"` (coal).
   - **This is a deliberate, by-design divergence from the eval reject arm.** In the eval
     tier, an evaluation error IS the clean rejection — `errored` is the expected outcome on a
     reject vector and grades nice. The transaction tier explicitly separates clean-reject
     (`valid: false`, `error: null`) from failed-verdict (`error: "errored"`, `valid: null`);
     `errored` on a reject vector is therefore COAL. Do not "harmonize" these two arms — they
     are different contracts for different tiers.

The verdict object is `{"kind": "transaction", "valid": "nice"|"value", "cost":
"nice"|"cost"|"n/a"}` (or `{"kind": "panicked"}` / `{"kind": "coverage", "tag":
"not-implemented"}`), uniform with the eval and wire shapes so `conform` can tally all tiers
without tier-specific logic.

`oracle/verdicts-transaction.json` (8 cases, schema `santa-oracle-transaction/v1`) is the
executable form of this section; `santa-check`'s `tests/oracle.rs` proves the grader against
all 37 oracle cases across all three tiers.

## 5. The provided-context contract

**This is the seam that makes the blesser and every runner comparable.** The vector carries the
**real** block context, and the blesser and each runner validate under *that* context — there are
no synthetic defaults for an impl to reconstruct identically (the coordination risk the earlier
minimal-context model carried, where each impl had to build the same zeroed `ErgoStateContext`).

| Field | Source |
|---|---|
| last headers (`ErgoStateContext.lastHeaders`) | `headers_hex` — the real last (≤10) headers, **NEWEST-first** (head = tip / pre-header parent) |
| pre-header | `preHeader` — real `{version, parentId, timestamp, nBits, height, minerPk, votes}` |
| parameters | `parameters` — the economic table at the capture height; **honored** (`TxEngine.validateBytes` overrides the launch table's 7 economic params from this field), the seam the authored param-driven reject arm (§6) rides |
| state digest | the JVM reads `chain-testnet.conf`'s `genesisStateDigestHex` (`cb63aa…`); stateful tx validation does not consult the UTXO root |

Because the real headers are carried, a script that reads `CONTEXT.headers` **is** representable
under `santa-transaction/v1` — it was not under the prior synthetic model (an empty/zeroed ring).
The capture provides the exact on-chain headers; the NEWEST-first order matches
`ErgoStateContext.lastHeaders` (`ErgoStateContext.scala:85/113/233`).

**Oracle recipe (compact).** `TxEngine.validateBytes` builds the real context:
```scala
val headers = headers_hex.map(HeaderSerializer.parseBytes)   // newest-first; .head == tip
val preHdr  = CPreHeader(version, headers.head.id, timestamp, nBits, height, votes, minerPk)
val ctx = UpcomingStateContext(headers, None, preHdr,
  chainSettings.genesisStateDigest, params, ErgoValidationSettings.initial, VotingData.empty)
tx.validateStateful(boxesToSpend, dataBoxes, ctx, 0L)
```

**Version boundary for `santa-transaction/v1`.** The context is now carried, so context-reading
scripts no longer force a bump. The next `santa-transaction/v2` trigger is a new *axis* — e.g. a
tx-tier **cost** dimension once `validateStateful` exposes one — exactly the mechanism by which the
eval tier grew `v1` → `v2` → `v3` when new dimensions entered scope.

## 6. Provenance

Two provenances, distinguished by the `source` prefix on each entry:

- **`captured`** — primary corpus. `source: "testnet:<seed-dir>@<height>"`. A captured tx is
  on-chain history; it is therefore inherently `valid: true`. The blesser hard-fails on any
  oracle rejection of a captured seed (`CapturedTx` sys.error + "FAIL LOUD"). The validate
  binary's `tx_path_guard` independently enforces the invariant: a committed vector under
  `vectors/transaction/*/captured/` with `expected.valid: false` is a schema violation.
- **`authored`** — the adversarial reject arm; `source: "santa:<label>"`. An authored vector may
  have `expected.valid: false` (a tx the oracle correctly rejects) and must carry a `reason`; it may
  also test the accept arm for scripts not reachable via testnet captures. **Populated (Track B):**
  two no-re-sign, param-driven boundary pairs — `cost-limit-boundary` + `min-value-dust-boundary`
  (§8) — built over a captured seed by moving exactly one economic parameter. Conservation/body-level
  rejects (Track A, re-signed) are deferred. Design: `docs/specs/transaction-reject-arm.md`.

**Re-blessing.** Producing actuals requires no oracle dependency (§3) — a runner needs only
its own implementation. Re-producing the committed vectors requires the blesser:
`SANTA_TX_BLESSER=1 sbt test` inside `jvm-blesser/` with a `publishLocal`'d ergo-core
6.0.2.1 (locally from an `ergoplatform/ergo@v6.0.2.1` clone; the conform CI workflow
publishes the same artifacts itself, cached by tag). The README carries the full
prerequisite.

**Never-panic invariant.** The runner wraps each entry; a would-be crash is caught and
surfaces as `panicked` + `note`, and the run continues. An implementation crash is faithfully
recorded as coal, not silently swallowed.

## 7. Conformer stances

| Conformer | Stance | Detail |
|---|---|---|
| **rudolph** | control (build-gated) | Declares `transaction`; the tx arm (`santa.runner.TxEngine`, reached by reflection) exists only in builds carrying ergo-core (`SANTA_TX_BLESSER=1` — local maintainer machines and the conform CI, which publishes ergo-core itself). As verification it is oracle-tautological; its value is the HARNESS CONTROL — the same role rudolph plays for eval — so the tx staging/grading pipeline has a row that must be green. A build without ergo-core emits a faithful `not-implemented` per entry (blue coverage cell): a capability fact about that build, not an excuse. |
| **comet** | out-of-scope (grey) | Does not declare `transaction` in `tiers` (`runner.json`). Fleet is a tx-building/serialization SDK with no verifier: it cannot script-verify a signed input (its only adjacent surface is prover-side reduce+sign of *unsigned* txs, delegated to sigmastate-js — sigma's artifact, not Fleet code) and has no stateful aggregate. Not a growth ledger — validation is outside Fleet's design scope, so grey, not not-impl. |
| **blitzen-eni** | full (`cost: true`) | sigma-rust @ ergo-node-integration (`jit-cost` feature). Produces `valid` + `cost`. Costed accept divergences are expected — this is the deliverable. |
| **blitzen-develop** | value-only (`cost: false`) | sigma-rust @ upstream develop. Produces `valid` only; `cost: null` unconditionally. Red on its upstream production-reject bugs (§8) and the cost-ceiling over-accept. |
| **dasher** | full (`cost: true`) | ergots implements stateful tx validation (`@ergots/transaction`); produces `valid` + `cost`, live across the captured corpus. Reject-arm divergences (the cost-ceiling over-accept, §8) are the deliverable. |

## 8. Status

**10 vectors / 12 entries:** 8 captured seeds + 2 authored boundary pairs (the Track-B reject arm).

**Captured (8 seeds, all `valid: true`):** the 4 originals `bigint-downcast-2666`,
`deserialize-context-111927`, `atleast-degenerate-bound-184137`, `powhit-return-type-28474` + the 4
bytes-anchored `getvarfrominput-92847`, `multi-input-3-402800`, `multi-input-10-402900`,
`order-ext-224312`.

**Authored (2 boundary pairs over `multi-input-3-402800` — a plain tx every impl accepts, so the
reject entries test enforcement, not a base bug):** `cost-limit-boundary` (maxBlockCost = the seed's
cost / cost − 1) and `min-value-dust-boundary` (minValuePerByte at the dust flip / + 1), each an
accept control + a one-step reject. Design: `docs/specs/transaction-reject-arm.md`.

**Current 4-way result** (comet grey — wire-only, no `transaction` tier):

| | captured (8) | authored (4) |
|---|---|---|
| **rudolph** (control) | valid 8/8 · cost 8/8 | valid 4/4 · cost 2/2 |
| **blitzen-eni** (cost) | valid 8/8 · cost 8/8 | valid 3/4 · cost 1/1 |
| **blitzen-develop** (value) | valid 4/8 | valid 3/4 |
| **dasher** (ergots) | valid 8/8 | valid 3/4 |

**Captured divergences** — blitzen-develop is red on the 4 originals, each a distinct upstream
production-reject bug eval cannot catch (tree_version is pre-set): `bigint-downcast` v3-gated
`Downcast` illegal (tree_version left V0); `atleast` degenerate-bound (`bound 1 > input size 0`);
`deserialize-context` missing context-extension substitution; `powhit` `SFunc` return-type parse.
eni converged to byte-exact cost across all 8 — the tier's first divergence→fix→convergence loop.

**Authored reject-arm divergences (the Track-B deliverable)** — the JVM (rudolph) accepts a tx
costing exactly `maxBlockCost` and rejects at `−1`; the cost ceiling splits three ways:
- **dasher (ergots) + blitzen-develop OVER-ACCEPT the cost-limit reject** — neither enforces
  `maxBlockCost` at the `validateStateful` seam (`got valid:true` at `maxBlockCost = cost − 1`).
- **blitzen-eni OVER-REJECTS the cost-limit accept** — it enforces the ceiling but on a
  `maxBlockCost × 10` budget (`CostLimitExceeded(184150)` at `maxBlockCost 18415`) while reporting
  JVM-equal cost (18415), so it rejects a tx that fits its own reported cost.
- **The dust floor is green on all four** — a cross-impl regression guard; cost-ceiling enforcement
  is the live divergence. Routed per-impl via `prompts/`.

This block is easy to update as the corpus grows or runners evolve.

## 9. Worked example

```jsonc
// vector entry (vectors/transaction/v6/captured/bigint-downcast-2666.json → entries[0])
{
  "name": "bigint-downcast-2666",
  "source": "testnet:bigint-downcast-2666@2666",
  "tx_bytes_hex": "0296bbdd…",                       // canonical sigma tx bytes
  "input_boxes_hex": [ "c0843d…", … ],               // ErgoBox bytes, in tx-input order
  "data_input_boxes_hex": [],
  "headers_hex": [ "04d43a…", … ],                   // 10 headers, NEWEST-first
  "preHeader": { "version": 4, "parentId": "8b09b7…", "timestamp": "1768…",
                 "nBits": 84141514, "height": 2666, "minerPk": "02339b…", "votes": "000000" },
  "parameters": { "maxBlockCost": 1000000, "storageFeeFactor": 1250000, "minValuePerByte": 360,
                  "inputCost": 2000, "dataInputCost": 100, "outputCost": 100, "tokenAccessCost": 100 },
  "context": { "height": 2666 },
  "version": { "activated": 3, "ergoTree": 3 },
  "expected": { "valid": true, "cost": 14846, "reason": null }
}

// runner's actuals file: { "<name>": { valid, cost, error }, … }

// blitzen-eni (cost-claiming accept; verdict nice, cost coal — cost diverges from oracle):
{ "bigint-downcast-2666": { "valid": true, "cost": 14826, "error": null } }
// → grade: {"kind": "transaction", "valid": "nice", "cost": "cost"}
// (oracle expected cost: 14846; eni: 14826 — demonstrates the dimension split:
//  valid matches, cost diverges; the only eni exact-cost match in the current corpus
//  is atleast-degenerate-bound-184137 at 15487 == 15487)

// blitzen-develop (pre-fix rejection, clean reject):
{ "bigint-downcast-2666": { "valid": false, "cost": null, "error": null,
    "reason": "…Downcast: cannot downcast BigInt(BigInt256(67500000000)) to Long…" } }
// → grade: {"kind": "transaction", "valid": "value", "cost": "n/a"}  (coal)

// dasher (not-implemented):
{ "bigint-downcast-2666": { "valid": null, "cost": null, "error": "not-implemented" } }
// → grade: {"kind": "coverage", "tag": "not-implemented"}

// NOTE (post bytes-anchor migration): the eni/develop verdicts above illustrate the grading
// dimensions but predate the JSON→bytes shape change — both currently grade `errored` until
// their sigma-rust arms switch from the (removed) JSON `tx` field to `parseTransaction(tx_bytes_hex)`.
```
