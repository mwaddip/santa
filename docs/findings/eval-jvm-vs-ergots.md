# Findings — JVM (sigma-state) vs ergots eval divergences

Discovered **2026-05-31** by the ergots runner (**Dasher**, `ts-runner/`) on its first run of
the committed `santa-eval` corpus (33 files / 245 entries) against the JVM-blessed `expected`.
ergots is the first *independent* conformer, so a Dasher-vs-blessed mismatch is a genuine
**JVM-vs-ergots** divergence (the JVM `sigma-state` 6.0.3 is canonical per BOOTSTRAP decision 1).

ergots is **v5-scoped**. Of 245 entries it **abstains on 231** (out of v5 scope — 34
UnsignedBigInt + 197 not-yet-implemented v6 methods; these flip to *covered* when ergots gains
v6), **evaluates 12** (6 exactly nice, 6 cost-divergent), and **diverges on the 8 below**.

Reproduce: `cd ts-runner && npx vitest run test/e2e.test.ts` (the gate pins these 8), or
`node ts-runner/dist/runner.js vectors/eval/<op>.json`.

## Cost divergences (value agrees; JIT cost differs) — OPEN

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

**Upstream:** sigma-rust carries the identical gap — it *fixed* `AddToEnvironment` for `BlockValue`
(`~/projects/sigma-rust/.../eval/block.rs:30`, with a comment acknowledging the Scala parity gap)
but never for lambdas (`eval/apply.rs:36`, `func_value.rs:12`). ergots mirrors sigma-rust and
inherited exactly that gap. This is the same class as the three cost undercharges in
[`eval-jvm-vs-sigma-rust.md`](eval-jvm-vs-sigma-rust.md) (also first surfaced via ergots), one level down.

**Fix:** charge `5` (AddToEnvironment) when `evalApply` binds each lambda argument, in ergots
(and upstream sigma-rust) — verify it isn't then double-charged anywhere. One fix closes all 6.

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
synthetic spec value. Dasher records these as **representation divergences** (omitted from
actuals; scored as a divergence, **not** `errored`).

**Fix:** represent `Header.timestamp` as `bigint` (full u64) to match consensus.

## Status
- **Cost — `AddToEnvironment` lambda undercharge** (6 entries): **OPEN** — route to ergots (+ upstream sigma-rust). Single fix.
- **Repr — `Header.timestamp` cap** (2 entries): **OPEN** — route to ergots.
- Dasher's e2e gate (`ts-runner/test/e2e.test.ts`) pins both sets at their current counts (6 / 2); when ergots fixes either, the count drops and the gate flags it for un-quarantine.
