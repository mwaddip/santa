# Findings — JVM (sigma-state) vs ergots eval divergences

Discovered **2026-05-31** by the ergots runner (**Dasher**, `ts-runner/`) on its first run of
the committed `santa-eval` corpus (33 files / 269 entries) against the JVM-blessed `expected`.
ergots is the first *independent* conformer, so a Dasher-vs-blessed mismatch is a genuine
**JVM-vs-ergots** divergence (the JVM `sigma-state` 6.0.3 is canonical per BOOTSTRAP decision 1).

ergots is a **v5/mainnet** library; Dasher's gate runs the v5 input bucket. The runner emits
one faithful outcome for every entry (no abstain — scope is which vectors you run, not a runner
behavior). Current gate: **1705 / 1705 — fully green** (was 1632/73, then 1678/27); every value/cost
divergence and the 27 not-implemented methods below are now fixed in ergots. The repr (Header-ts)
findings remain open but live in the v6 bucket (not run by the v5 gate) — documented below.

Reproduce: `cd ts-runner && npm test`, or
`node ts-runner/dist/runner.js vectors/eval/v5/<op>.json`.

## Cost divergences (value agrees; JIT cost differs) — RESOLVED 2026-06-01

> **RESOLVED:** fixed in **sigma-rust** `d59d8d9f` (ergo-node-integration) + **ergots** `6171d32`; after ergots' `dist` rebuild Dasher matches the JVM on all six (12/12 covered nice). The e2e gate flipped 6→0, kept as a regression guard. The record + root-cause analysis below stands.

ergots **undercharges JIT cost by 5 per lambda application** — it omits the `AddToEnvironment`
(FixedCost 5) charge that sigma-state makes on **every** environment insertion, including
lambda-argument binding. (ergots already charges it for `BlockValue` ValDefs; the
`FuncValue`/`Apply` lambda path was missed.) Each evaluable v2 entry is a closed
`Apply(FuncValue([arg], <body>), [OptionGet(GetVar(1))])` that applies the user function once
→ **−5**; the higher-order-lambda applies five lambdas → **−25**. v1 closed trees have no
`Apply`/`FuncValue`, so they match the JVM exactly.

| op / entry | JVM (canonical) | ergots | Δ |
|---|---|---|---|
| `Box.getReg` (Box_properties_equivalence_new_features #0) | **135** | 130 | −5 |
| `Coll.getOrElse` with lazy default (Coll(1) #0) | **101** | 96 | −5 |
| `substConstants` v6.0 ErgoTree v0 (#0) | **351** | 346 | −5 |
| `substConstants` v6.0 ErgoTree v0 (#1) | **351** | 346 | −5 |
| `Option.getOrElse` with lazy default (Some(2) #0) | **90** | 85 | −5 |
| `higher_order_lambdas` (map over Coll(1,2)) | **408** | 383 | −25 (5 lambdas) |

**Root cause** (confirmed on both sides — empirically via cost-traced runs *and* source):
- ergots charges **0** for lambda-arg binding: `~/projects/ergots/packages/ergoscript/src/eval/apply.ts:62` (`bodyEnv.extend(argId, arg)` with no `ctx.addCost`); `func-value.ts` charges only `FUNC_VALUE_COST = 5` (closure creation).
- sigma-state charges **5**: `tmp/sigma-src/sigma/ast/values.scala:1047` (`E.addFixedCost(AddToEnvironmentDesc_CostKind, …)`), with `AddToEnvironmentDesc_CostKind = FixedCost(JitCost(5))` at `values.scala:1064`.
- Every other wrapper node matches 1:1: Apply 30, FuncValue 5, GetVar 10, OptionGet 15, ValUse 5. The `higher_order_lambdas` map per-item envelope also matches; its extra −20 is just four more instances of the same omission (5 lambda bindings × 5).

**Upstream:** sigma-rust carries the same gap, narrowed to one site. It already charges
`ADD_TO_ENV_COST` for `BlockValue` ValDefs (`eval/block.rs:30,45`, commit `50f44685`, with a comment
documenting the Scala parity gap) **and** for coll-op lambdas (`6ded99cb`) — but **not** for the
generic `Apply`→lambda binding (`eval/apply.rs:36`: `env.insert(*idx, arg_v)` with no charge; `Apply`
pays only its own `add_jit_cost(30)` at line 18). ergots has the identical shape: its `coll-map`
per-item charge is correct, so the hole is likewise the generic `Apply` path (`apply.ts`), not
coll-ops. This is the same class as the three cost undercharges in
[`eval-jvm-vs-sigma-rust.md`](eval-jvm-vs-sigma-rust.md) (also first surfaced via ergots), one site further.

**Fix:** charge `5` (AddToEnvironment) per lambda-arg binding in the **generic `Apply`** eval — in
ergots (`apply.ts`) and upstream sigma-rust (`apply.rs:36`, mirroring `block.rs:30/45`); verify no
double-charge against the already-fixed coll-op path. One fix closes all 6. Route the sigma-rust half
to the open JIT-cost-parity PR **#854 `mwaddip:jit-costing`** (the line of work already carrying the
BlockValue/coll-op ADD_TO_ENV fixes).

## Representation divergence (ergots cannot represent a valid-to-the-JVM input) — OPEN

| op / entry | JVM (canonical) | ergots |
|---|---|---|
| `Header.checkPow` (Header_new_methods #0) | Boolean true, cost 774 | cannot parse input |
| `Global.deserializeTo[Header]` (Global.deserializeTo_header #0) | Boolean true, cost 677 | cannot parse input |

Both inputs are `Header`s carrying a synthetic timestamp `4928911477310178288` (≈4.9×10¹⁸).
ergots stores `Header.timestamp` as a JS `number` and rejects any value `> Number.MAX_SAFE_INTEGER`
(2⁵³) with `ReaderError('vlq-overflow')` — a deliberate NIP-08 byte-exact-round-trip decision at
`~/projects/ergots/packages/scorex/src/header.ts:69`. The JVM uses a full `Long`, accepts the
header, and evaluates. Real chain timestamps fit in <2⁴⁵, so this never bites mainnet — only this
synthetic spec value. Dasher records these as **`unrepresentable`** outcomes (value/cost null, `error:
"unrepresentable"`) — present in actuals, never omitted. They live in the **v6** corpus, so
they are *not* exercised by ergots' v5 gate; they remain a real, open repr bug for ergots'
future v6 support.

**Fix:** represent `Header.timestamp` as `bigint` (full u64) to match consensus.

## v5 corpus divergences (2026-06-01 — `LanguageSpecificationV5`) — RESOLVED 2026-06-02

> **RESOLVED:** all 73 closed. ergots landed every value/cost fix and then the 27
> not-implemented methods (`Coll.updated`/`updateMany`/`GroupElement.negate`, `35eac6b`);
> SANTA re-blessed box inputs to ≥protocol-min (`santa-rebless-min-box-value`). **Dasher v5 =
> 1705/1705**, pinned as a full-green regression guard. The record below stands.

Dasher's run of the **v5** spec corpus (`vectors/eval/v5/`, 84 files / 1705 entries, extracted
at ErgoTree v2) against the JVM-blessed `expected`, ergots' declared v5 scope: originally **1632
nice, 73 RED** — 10 value/error, 36 cost, **27 `not-implemented`**, and **0 reject**. The corpus now
includes 147 harvested reject vectors (the spec's Failure-expected cases blessed as coarse
`errored`); ergots rejects every input the JVM rejects, so reject divergences are 0 (135 score
reject-nice). The 27 `not-implemented` are the 3 v5 methods ergots lacks (`Coll.updated`,
`Coll.updateMany`, `GroupElement.negate` — confirmed v5 in sigma-state `methods.scala`
`v5Methods`, NOT v6-gated): their 15 accept cases plus the 12 `updated`/`updateMany` reject
cases. No "abstain" — every entry yields a faithful outcome; gaps are honest RED, routed via
`prompts/ergots-v5-method-gaps.md`, the 46 value/cost divergences via
`prompts/ergots-v5-divergences.md`. All ergots-vs-JVM (JVM canonical).

**Value/error (10):**
- **Negation overflow (4)** — `Numeric_Negation_equivalence` `-{128, 32768, 2147483648, 9223372036854775808}` (`#0/#9/#18/#26`): JVM wraps `-MIN_VALUE` → `MIN_VALUE`; ergots `errored`. (ergots negation appears *checked*; sigma-state wraps.)
- **substConstants (5)** — `substConstants_equivalence` `(Coll[Byte], pos)`: JVM returns the substituted `Coll[Byte]`; ergots `errored`. **Input-specific** — ergots evals the v6 substConstants fine.
- **flatMap empty-Coll type (1)** — `Coll_flatMap_method_equivalence :: Coll()#0`: JVM `Coll[SByte][]`, ergots `Coll[SAny][]`. Empty input → no first-iteration elem refinement (ergots `mir/expr-tpe.ts:147-150`, in-code acknowledged).

**Cost (36, ergots mostly under-charges; distinct from the resolved `AddToEnvironment −5`):**
`Coll.flatMap` (Δ −90…−190) · `Coll.indexOf` (Δ ±3…16) · `NEQ`-nested-colls (Δ −1…−18) ·
`SigmaProp.propBytes` (Δ −18…−210 — ergots flat 111; JVM scales with the sigma-tree's serialized size).
Full per-entry deltas are in the e2e `cost-divergences:` block.

## Status
- **Cost — `AddToEnvironment` lambda undercharge** (6 entries): **RESOLVED 2026-06-01** — fixed in sigma-rust (`d59d8d9f`) + ergots (`6171d32`); ergots `dist` rebuilt; Dasher nice on all 12 covered. The e2e gate flipped 6→0 (kept as a regression guard).
- **Repr — `Header.timestamp` cap** (2 entries): **OPEN** — route to ergots.
- **v5 corpus** (2026-06-01 → **RESOLVED 2026-06-02**): originally **73 RED** — 10 value (negation overflow ×4, substConstants ×5, flatMap empty-type ×1) + 36 cost (flatMap / indexOf / NEQ / propBytes) + 27 not-implemented (Coll.updated / Coll.updateMany / GroupElement.negate — accept + reject cases) + 0 reject. **All fixed in ergots** (value/cost via the routed fixes; the 27 methods via `35eac6b`); SANTA re-blessed box inputs to ≥min. **Dasher v5 = 1705/1705** — the e2e pins are now a full-green guard.
- **Reject arm** (2026-06-01): the corpus now harvests `LanguageSpecificationV5/V6`'s Failure-expected cases as coarse `errored` reject vectors (147 v5 + 24 v6). Dasher's reject bucket found **0 divergences** — ergots rejects every input the JVM rejects. Coarse only; the rejection-*reason* taxonomy stays deferred (runner-contract §7).
- Dasher's e2e gate (`ts-runner/test/e2e.test.ts`) pins each RED count; when ergots fixes any, the count drops and the gate flags it for re-baselining.
