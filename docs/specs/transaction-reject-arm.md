# SANTA — Transaction reject arm (Track B: param-driven boundaries)

> Populates the transaction tier's reserved **`authored`** provenance
> ([`runner-contract-transaction.md`](../contract/runner-contract-transaction.md) §6) with its first
> reject vectors: **no-re-sign, param-driven boundary pairs** that pin two node-economic consensus
> rules — the block **cost ceiling** and the **dust floor** — cross-impl. Additive to
> `santa-transaction/v1`: no schema change, no new tier.
>
> Read order: [`runner-contract-transaction.md`](../contract/runner-contract-transaction.md) →
> [`runner-contract.md`](../contract/runner-contract.md) §4 (grading) → this.
> Memories: `[[transaction-tier]]`, `[[reject-arm-must-surface-over-accept]]`,
> `[[divergences-are-the-deliverable]]`.

## What & why

The tx tier today is accept-only (8 captured seeds, inherently `valid:true`). The reject arm is the
adversarial complement: transactions the JVM oracle (`validateStateful`) correctly **rejects**, to
pin that every conformer rejects them too — and to surface any impl that **over-accepts** (the
deliverable, `[[divergences-are-the-deliverable]]`).

Grading is already specified (`runner-contract-transaction.md` §4.4): on a reject vector
(`expected.valid:false`), a clean `valid:false, error:null` is **nice**; **both** `errored` and
`valid:true` are **coal**. So the arm naturally catches an over-accept (`valid:true`) — but only if
the vector is wrong in **exactly one** way. A tx invalid in several respects greens on any impl that
catches *any* of them, even one missing the specific rule under test
(`[[reject-arm-must-surface-over-accept]]`); and because `reason` is diagnostic-only (never matched),
the grader cannot tell *why* an impl rejected. Surgical, single-rule isolation is the whole game.

### The signing constraint → Track B

A captured tx is **signed**: each input proof is over `messageToSign = bytesToSign(tx)`
(`ErgoLikeTransaction.scala:192`), which covers input boxIds, dataInputs, and output candidates — but
**not** the context (headers/preHeader/parameters) and **not** the input box *contents* (only their
ids). Therefore:

- Mutating the tx **body** (any output/token/input-id) breaks every signature → a universal
  bad-signature reject that isolates nothing and false-greens a buggy impl. A dead end without re-signing.
- Mutating only the **provided context** leaves the signatures valid → a single isolated rule fires.

**Track B** is the no-re-sign half: perturb the context/parameters of a real captured seed so the tx
rejects for exactly one reason. **Track A** (re-signed synthetic txs for body-level rules — ERG/token
conservation) is deferred; see *Out of scope*.

## The empirical finding (spike, 2026-06-16)

A throwaway spike (`jvm-blesser/src/test/scala-txbless/santa/spike/TxRejectSpike.scala`, untracked)
drove `validateStateful` over the `bigint-downcast-2666` seed (cost 14846) under each candidate
mutation. It confirmed three in-tier levers and settled which are **clean**:

| lever | mutation (no re-sign) | accept → reject boundary | reject rule (reason) |
|---|---|---|---|
| **cost** | `maxBlockCost` ↓ | **14846 accept / 14845 reject** | `CostLimitException 403 > 402` (`txScriptValidation`) |
| **dust** | `minValuePerByte` ↑ | **6896 accept / 6897 reject** (output 1 000 000 nanoErg / 145 B) | `txDust` (`value >= 145·mvpb`) |
| preservation | deflate `input[0].value` | rejects (all 3 seeds tried) | `txErgPreservation` (strict `==`) |

Two structural facts from `ergo-core`'s `ErgoTransaction.validateStateful`:

1. **boxId-binding is *not* enforced at this seam** — `if (!box.id.sameElements(input.boxId))
   log.error(...)` (line 125) only **logs**; it never rejects ("should always be true if client
   implementation is correct"). So a mismatched input box is out of tier — the UTXO/block layer binds
   boxId, not the library `validateStateful`. That lever is dropped.
2. **ERG preservation is strict equality** — `inputSumTry == outputsSumTry` (line 430). The only
   no-re-sign way to break it is to deflate an input box's value, which **also changes the box's id**.
   The JVM ignores the id-mismatch and catches preservation, but an impl stricter on id-binding and
   laxer on preservation would reject on the id and **false-green** the preservation skip
   (`[[reject-arm-must-surface-over-accept]]`). A clean conservation reject needs the box id to match
   the signed reference → re-signing → **Track A**.

**cost and dust are pristine** (only a *parameter* changes; the real tx and boxes are untouched, so
the single violation is the threshold). **preservation is muddy** in Track B → deferred to A.

## The two vectors

Both under `vectors/transaction/v6/authored/`, schema `santa-transaction/v1`, built over the
`bigint-downcast-2666` captured tx / boxes / headers / preHeader. Each is a **boundary pair**: an
accept control (proves the tx is valid in every *other* respect) plus the one-step reject. Per-entry
`parameters` carries the launch table with exactly one field moved.

### `cost-limit-boundary.json` — block cost ceiling
- **accept:** `maxBlockCost = 14846` → `valid:true`, `cost 14846`.
- **reject:** `maxBlockCost = 14845` → `valid:false`, reason `Scripts of all transaction inputs should
  pass verification … CostLimitException: Estimated execution cost 403 exceeds the limit 402`.
- `source: "santa:authored-tx-cost-limit-boundary"`.

### `min-value-dust-boundary.json` — dust floor
- **accept:** `minValuePerByte = 6896` → `valid:true`, `cost 14846`.
- **reject:** `minValuePerByte = 6897` → `valid:false`, reason `Every output … should contain at least
  <minValuePerByte * outputSize> nanoErgs … 1000000 >= 1000065`.
- `source: "santa:authored-tx-min-value-dust-boundary"`.

The exact boundaries (cost−1; the `size·mvpb` crossing) intentionally double as a **cost-model /
box-size cross-check** at the reject edge: an impl whose cost or serialized box size differs flips at
a different threshold. The blesser asserts **both** verdicts (fail-loud), so an off-by-one boundary
fails the bless.

## The one production change — `TxEngine.validateBytes` honors provided params

Today `validateBytes` discards the vector's `parameters` (`val _ = parameters`, `TxEngine.scala:80`)
and hardcodes `TestnetLaunchParameters.parametersTable`. The reject arm needs the oracle — and
rudolph's runner arm, which *is* `validateBytes` — to **honor** the provided params, overriding the
launch table's 7 economic entries from the vector:

```scala
TestnetLaunchParameters.parametersTable
  .updated(Parameters.BlockVersion,             blockVersion.toInt)
  .updated(Parameters.MaxBlockCostIncrease,     p.maxBlockCost)
  .updated(Parameters.MinValuePerByteIncrease,  p.minValuePerByte)
  .updated(Parameters.StorageFeeFactorIncrease, p.storageFeeFactor)
  .updated(Parameters.InputCostIncrease,        p.inputCost)
  .updated(Parameters.DataInputCostIncrease,    p.dataInputCost)
  .updated(Parameters.OutputCostIncrease,       p.outputCost)
  .updated(Parameters.TokenAccessCostIncrease,  p.tokenAccessCost)
```

This is a correctness fix independent of the reject arm (§5 already lists `parameters` as provided
context). It is a **no-op for the 8 captured seeds** — their `parameters` already equal the launch
values, so costs are unchanged, and `CapturedTxFullTest` re-blesses them as the regression guard. The
sigma-rust arms **already** build their `Parameters` from the vector
(`runners/blitzen-{eni,develop}/src/transaction.rs:163-180`), so there is **no runner-arm change** —
only the JVM oracle was discarding params.

## The blesser — `AuthoredTxReject`

A test-scope generator (`jvm-blesser/src/test/scala-txbless/santa/AuthoredTxReject{,Test}.scala`), the
reject-arm sibling of `CapturedTxFull`. Unlike `CapturedTxFull` (FAIL-LOUD on any `valid:false`), it
asserts the **expected** verdict per entry: accept entries must be `valid:true` (records `cost`),
reject entries must be `valid:false` (records `reason`). A wrong verdict fails the bless. It reuses
the base seed's bytes/boxes/context from
`vectors/transaction/v6/captured/bigint-downcast-2666.json`, and emits each entry's `parameters` as
the launch values ± the one moved field. `AuthoredTxRejectTest` anchors the exact boundary params, the
two reject reasons, and the verdicts, and re-blesses both files.

## Grading prediction (settled at conform)

Per impl, on the two reject entries (`runner-contract-transaction.md` §4.4):

- **rudolph** (control): honors params after the engine change → rejects → **green** (accept controls green too).
- **blitzen-eni / -develop**: thread params already; whether `validate` *enforces* the ceiling / dust
  floor is unverified. Enforce → green (regression guard + cost/size pin). Thread-but-skip → accept →
  **coal** (the over-accept caught). **dust is the likelier catch** — a script-focused library often
  omits the node-economic min-value rule.
- **dasher** (ergots): plumbs params; enforcement unknown — same split.
- **comet**: out of scope (no `transaction` tier).

Honest: these may land as regression guards (green everywhere) **or** catch an over-accept — unknown
until graded. The spike (each rejects in-tier and isolates exactly one rule) is the guard against the
"green for an incidental reason" weakness (`[[reject-arm-must-surface-over-accept]]`).

## Outcome (landed 2026-06-17)

Re-based onto `multi-input-3-402800` after the first grade (over `bigint-downcast-2666`): that seed's
exotic-cost / develop-already-rejects profile made develop uninformative (its tree-version bug masked
the param) and added an eni knife-edge. The clean-seed board (conform; comet/donner/vixen not on the
tx tier):

| | rudolph | blitzen-eni | blitzen-develop | dasher |
|---|---|---|---|---|
| authored (4) | valid 4/4 · cost 2/2 | valid 3/4 · cost 1/1 | valid 3/4 | valid 3/4 |

**The prediction was instructively wrong: dust is *not* the catch — the cost ceiling is.** All four
enforce the dust floor (green everywhere — a regression guard). Cost-ceiling enforcement splits three ways:

- **dasher (ergots) + blitzen-develop OVER-ACCEPT** the cost-limit reject — neither enforces
  `maxBlockCost` at `validateStateful` (`got valid:true` at `maxBlockCost = cost − 1`). The deliverable,
  on two independent impls.
- **blitzen-eni OVER-REJECTS** the cost-limit accept — it enforces the ceiling but against a
  `maxBlockCost × 10` budget (`CostLimitExceeded(184150)` at `maxBlockCost 18415`) while reporting
  JVM-equal cost (18415): a scale bug in the limit check, not a magnitude divergence. Surfaced
  *because* the accept control sits at the exact cost — the tight-boundary coupling doing work.

The re-base also validated the seed-choice clause: develop went from noise (2/4, all from its bigint
tree-version bug) to signal (3/4, the real over-accept). This supersedes *Grading prediction* above
where they differ. The boundaries are computed per seed in the blesser (cost ceiling = the seed's own
cost; dust floor = a searched flip), so re-basing was a one-line `BaseSeed` change. Routed per-impl
via `prompts/`.

## Schema / validate

- **No schema change.** `schema/santa-transaction.vector.schema.json` already permits
  `expected.valid:false`; the validate `tx_path_guard` forbids `valid:false` only under `captured/`
  (`authored/` is allowed). **Verify** the validate tx walker discovers
  `vectors/transaction/v6/authored/` and the guard lets authored reject vectors through.
- Authored reject vectors MUST carry `expected.reason` (§6) — the blesser bakes the oracle's string.

## Gates / testing (TDD, blesser-first)

0. **Done:** the spike confirmed cost/dust reject in-tier with exact boundaries + reasons.
1. `TxEngine.validateBytes` param-honor + `CapturedTxFullTest` re-bless (8 seeds, **costs unchanged** = the regression gate).
2. `AuthoredTxReject{,Test}` → write the 2 vectors (TDD: the test anchors boundary params, reasons, verdicts).
3. `validate` covers `authored/` (path guard allows `valid:false` there); coverage regen if tx is tracked.
4. contract docs: §2/§5 (params now honored), §6 (authored populated), §8 (status: +2 authored vectors + board). `[[update-docs-before-commit]]`.
5. conform 7-way → rudolph green; eni/develop/dasher per the prediction (the re-grade *is* the finding).

## Out of scope / Deferred

- **Conservation / Track A** — ERG & token preservation, negative/overflow value, too-many-tokens: the
  body-level rules that need a re-signing harness (a prover in the blesser minting input boxes under a
  held key). The spike confirmed `txErgPreservation` is in-tier; only the clean (id-matching)
  construction is deferred. This doc extends to Track A when it is built.
- **Context-reading-script rejects** (perturb `HEIGHT`/headers a script gates on) — no current seed
  reads perturbable unsigned context without re-signing; revisit if a context-gated seed is captured.
- **More boundaries** (storage-rent, token-access cost, multi-output dust) — the same param-override
  machinery extends to them as the corpus grows.
