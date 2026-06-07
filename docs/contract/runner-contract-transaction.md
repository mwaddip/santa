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

- **`tx`** — the signed transaction, node-API JSON (`id`, `inputs[].spendingProof`,
  `dataInputs`, `outputs`). Decoded by the runner via its own node-JSON serde; the JVM oracle
  decodes the same bytes via `ApiCodecs` (`Decoder[ErgoTransaction]`), so the vector payload
  and the oracle's input are identical by construction.
- **`inputBoxes`** — full `ErgoBox` JSON per input, ordered to match `tx.inputs`. The runner
  resolves spending proofs against the box at the same index.
- **`dataInputBoxes`** — full `ErgoBox` JSON per data-input (may be empty).
- **`context.height`** — block height for the pre-header and parameters.
- **`version.activated`** + **`version.ergoTree`** — the protocol versions under which the
  scripts were compiled and must be verified.
- **`expected`** — the JVM-blessed `{valid: bool, cost: int|null, reason: null|string}`.

There is no `expected.error` field. The oracle either accepted (with a cost) or rejected (with
a reason); "errored" as a vector state does not exist — the blesser hard-fails on any oracle
failure against a captured seed (CapturedTx `FAIL-LOUD-on-valid:false`).

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

## 5. The minimal-context contract

**This is the seam that makes the blesser and every runner comparable.** For every field of
`ErgoStateContext` the vector does not carry, the blesser and each runner fill in the same
pinned defaults:

| Field | Value |
|---|---|
| pre-header `height` | `context.height` from the vector entry |
| pre-header `version` (blockVersion) | `version.activated + 1` |
| pre-header `timestamp` | `0` |
| pre-header `nBits` | `0` |
| pre-header `votes` | `[0, 0, 0]` |
| pre-header `parent_id` | `Digest32::zero()` / `Header.GenesisParentId` |
| parameters | launch-default (`TestnetLaunchParameters`), `BlockVersion` key updated to `activated + 1` |
| last-headers ring | JVM: `Seq.empty` (ergo-core); sigma-rust: 10 synthetic headers, same zeroed fields |
| state digest | JVM reads `chain-testnet.conf`'s `genesisStateDigestHex` (`cb63aa…`) |

The last-headers equivalence holds because the current captured corpus contains no script that
reads `CONTEXT.headers`: a script that did cannot have been blessed valid against the JVM's
empty `Seq.empty`, so it could never enter the corpus as a capture. The zeroed sigma-rust
ring and the JVM empty seq are functionally indistinguishable for this corpus.

**Oracle recipe (compact).** `TxValidate.scala` constructs:
```scala
val params = new Parameters(height,
  TestnetLaunchParameters.parametersTable.updated(Parameters.BlockVersion, blockVersion.toInt),
  ErgoValidationSettingsUpdate.empty)
val preHeader = CPreHeader(blockVersion, Header.GenesisParentId, ts=0, nBits=0, height,
  votes=Array.fill(3)(0.toByte), minerPk=group.generator)
val ctx = UpcomingStateContext(Seq.empty, None, preHeader,
  chainSettings.genesisStateDigest, params, ErgoValidationSettings.initial, VotingData.empty)
tx.validateStateful(boxesToSpend, dataBoxes, ctx, 0L)
```

**Version boundary for `santa-transaction/v1`.** A vector is valid under this format only if
its verdict is independent of every defaulted field. The first captured script that reads a
field this contract leaves zeroed (e.g. `CONTEXT.headers`, a live `nBits`, a real
`timestamp`) would force that field into the capture payload, requiring a
`santa-transaction/v2` — exactly the mechanism by which the eval tier grew `v1` → `v2` → `v3`
when new context fields entered scope.

## 6. Provenance

Two provenances, distinguished by the `source` prefix on each entry:

- **`captured`** — primary corpus. `source: "testnet:<seed-dir>@<height>"`. A captured tx is
  on-chain history; it is therefore inherently `valid: true`. The blesser hard-fails on any
  oracle rejection of a captured seed (`CapturedTx` sys.error + "FAIL LOUD"). The validate
  binary's `tx_path_guard` independently enforces the invariant: a committed vector under
  `vectors/transaction/*/captured/` with `expected.valid: false` is a schema violation.
- **`authored`** — the adversarial reject arm; `source: "santa:<label>"`. Future. An authored
  vector may have `expected.valid: false` (a tx the oracle correctly rejects) and must carry a
  `reason`; it may also test the accept arm for scripts not reachable via testnet captures.

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
| **blitzen-develop** | value-only (`cost: false`) | sigma-rust @ upstream develop. Produces `valid` only; `cost: null` unconditionally. Each of the 4 current seeds is red with its upstream bug. |
| **dasher** | not-implemented (growth ledger) | ergots does not yet implement stateful tx validation. Rationale: script-verify alone would false-green non-conserving txs; the tier needs the full `validateStateful` surface. dasher's not-impl slice is the roadmap ledger for the tx tier. |

## 8. Status

4 captured seeds: `bigint-downcast-2666`, `deserialize-context-111927`,
`atleast-degenerate-bound-184137`, `powhit-return-type-28474`.

Current 5-way result:
- **blitzen-eni**: **`valid 4/4 · cost 4/4` byte-exact.** The initial bless surfaced 3 genuine
  cost divergences (bigint 14826 vs 14846, deserialize 14816 vs 15374, powhit 16401 vs 16656;
  atleast exact — the structural-accounting control). A decomposition spike named the ops
  (avl get/remove uncosted · deserialize-substitution presence charge · UBI-arith
  misclassified); routed; sigma-rust fixed all three plus the `enrich_err` cost-lattice
  wrinkle; re-graded exact at fork-eni `324cc4cd` with zero eval side-effects — the tier's
  first divergence→fix→convergence loop.
- **blitzen-develop**: 0/4 — each seed red with its upstream bug, each a distinct upstream defect:
  - `bigint-downcast-2666`: production-path tree-version bug — `reduce_to_crypto` leaves
    `tree_version` at V0, so the v3-gated `Downcast` op is illegal and sigma-rust rejects what
    the JVM accepts; the divergence eval structurally cannot catch (tree_version is pre-set, not
    computed by the evaluator).
  - `atleast-degenerate-bound-184137`: degenerate-bound handling in `Atleast` — sigma-rust
    rejects with `bound 1 > input size 0` where the JVM accepts.
  - `deserialize-context-111927`: context-extension substitution missing — sigma-rust cannot
    resolve `DeserializeContext` variable 0.
  - `powhit-return-type-28474`: `powHit` return-type / `SFunc` parse bug — sigma-rust fails to
    parse the ergotree's `SFunc` condition type.
- **dasher**: 4/4 not-implemented (blue coverage cells; growth ledger).
- **rudolph**: `valid 4/4 · cost 4/4` — the control row (ergo-core-gated build; see §7).
- **comet**: not in the tx slice (out-of-scope grey; wire-only — see §7).

This block is easy to update as the corpus grows or runners evolve.

## 9. Worked example

```jsonc
// vector entry (vectors/transaction/v6/captured/bigint-downcast-2666.json → entries[0])
{
  "name": "bigint-downcast-2666",
  "source": "testnet:testnet-bigint-downcast-v3@2666",
  "tx": { "id": "fcb588…", "inputs": [{ "boxId": "90a2f3…", "spendingProof": { … } }, …], … },
  "inputBoxes": [ { "boxId": "90a2f3…", "ergoTree": "1b8d03…", … }, … ],
  "dataInputBoxes": [],
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
```
