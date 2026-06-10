# Finding — BigInt→Long downcast under ErgoTree V3 (testnet block 2666)

> **Status: PARKED — block-tier seed case, saved 2026-06-03.** Not yet vectored.
> The eval tier **cannot** catch this (see below); it needs the **block tier**, which is
> unbuilt. This directory preserves the data + analysis so it's the ready-made vector #0
> whenever the block tier is started.
>
> Source: `~/projects/ergo-node-rust/prompts/santa-bigint-downcast-vector.md` (git-excluded
> scratch — copied here so it survives).

## The case

Testnet **block 2666** (`2de342ec25fb6cc16311a9d428a2670dd66e56abdb00ee95412c6a47470e9ad5`),
**transaction index 1, input 0**. A **V3** ErgoTree runs `Downcast(BigInt, SLong)` on the value
**67,500,000,000**. The testnet network (JVM sigma-state 6.0.3) accepted this block — it is
canonical history. The `ergo-node-rust` node (riding the sigma-rust fork) **rejected** it and
wedged at fullHeight 2665:

```
transaction 1 invalid: Verifier error on input 0:
EvalError: Unexpected value: Downcast: cannot downcast BigInt(BigInt256(67500000000)) to Long
```

**Expected: ACCEPT** (→ `67500000000L`). A fork-direction divergence — sigma-rust rejects a block
the network validated.

## Root cause

- 67.5e9 fits in i64 — **not** a bounds problem.
- sigma-rust gates `BigInt→Long` behind `ctx.tree_version() >= V3` (`eval/downcast.rs`,
  `downcast_to_long`). The **threshold is correct** (a 6.0/V3 feature).
- The bug: `ctx.tree_version()` (a `Cell<ErgoTreeVersion>` on the eval `Context`) **defaults to
  V0 in the production verify path** and is only `.set()` in test helpers. Gate sees `V0 < V3`
  → rejects, even though the tree header is V3.
- Both versions are genuinely V3: script header byte `0x1b` → `0x1b & 0x07 = 3`; block
  `blockVersion = 4` → `activated_script_version = 3`. The per-block activated version is fine;
  it's the unpopulated per-script `tree_version()` cell that breaks it.
- **sigma-rust fix:** eni commit `6b3ce5ed` — `ctx.tree_version.set(tree.header()?.version())` at
  the top of `reduce_to_crypto`. Regression rode in on the JIT-costing refactor (node pin bump to
  `46e94c21`, commit `7862f8b`, 2026-04-22); pins ≤ `d8a588a1` (v0.3.2) validated past 2666.
  Sigma pin when found: `c3ee4a6a`.

## Why the eval tier can't catch this (the load-bearing point)

SANTA's eval-tier runners evaluate via `try_eval_out` (sigma-rust) / `evalApplied` (JVM) against a
context whose `tree_version` is **set explicitly from the vector's recorded version**
(`runners/blitzen-eni/src/eval.rs:102`, and the JVM derives it from `tree.version`). That
pre-supplies the exact value the bug fails to derive — so an eval vector for this downcast grades
**nice on both the buggy and the fixed sigma-rust**. The node's prompt confirms it:

> *"a unit test that pre-sets V3 passes while the real path fails… sigma-rust's own `to_long`
> proptest stays green — `try_eval_out_with_version` sets the version the verify path forgets to."*

The bug lives in the **production reduce/verify path** (`reduce_to_crypto` deriving `tree_version`
from the tree header). Catching it requires a runner that drives that real path — i.e. the
**block tier**: a captured tx + its input boxes → `valid?`, validated by a node, *not* the eval
helper.

## Vectors to author (when the block tier exists)

1. **Primary (loud).** Under tree version ≥ V3, `Downcast(BigInt(67500000000), SLong)` must
   evaluate to `67500000000L` (not error). ErgoScript: `bigInt("67500000000").toLong` in a V3
   tree. **Must run through full reduce/verify** (version derived from the header), never a helper
   that hand-sets `ctx.tree_version`.
2. **Sibling (silent twin — same root cause, opposite polarity).** Pre-JIT **leniency** gates
   (`tree_version() < V2`) also misfire: `V0 < V2` is always true, so V3 trees wrongly take the
   *lenient* path — an accept-direction, fork-causing variant that fails silently. A companion
   vector should assert a V3 tree does **not** receive pre-V2 leniency.

## Data (this directory)

- `block-2666.json` — full block (header + `blockTransactions`, `blockVersion = 4`). Failing tx =
  index 1, input 0.
- `box-90a2f395.json` — the spent box `90a2f395…` (SELF for tx1/input0): the V3 AvlTree script +
  registers (R4 holds the AvlTree the script runs `getMany` against), `value = 67500000000`.

Spent-box ErgoTree (V3, constant-segregated; first byte `0x1b`):
```
1b8d030b02000400041005000580897a040004020402040004000400d809d6018301027300d602e5e3001a83010e7201d603e4c6a70464d604dc0c1d720201addc640b7203027202e5e3010e7201d9010432e47204d605e4c6a70705d606e4c6a70606d607ad7204d901073c0e0e86028c7207017d9d9c7e7205067e7cb48c7207027301730206720605d608b072077303d90108414d0e9a8c7208018c8c72080202d609c1a795ed8f720872099199720972087304d801d60ab2a5730500d19683080193c2720ac2a793c1720a9972097208937206e4c6720a0606937205e4c6720a070593e4c6a70504e4c6720a050493db6401e4dc640e7203027202e5e3020e7201db6401e4c6720a0464afdc0c1db4a573069ab172047307017207d9010b3c634d0ed802d60d8c720b01d60e8c720b02ed93cbc2720d8c720e0193c1720d8c720e0293c5a7c5b2a4730800d19683020193c5a7c5b2a4730900afdc0c1db4a5730ab17204017207d9010a3c634d0ed802d60c8c720a01d60d8c720a02ed93cbc2720c8c720d0192c1720c8c720d02
```
- block-tier capture (today set): headers-2656-2665, 3 external boxes, epoch-block-2560 (params in force at 2666). Full block with ADProofs: pending digest side-sync.
- box-<id16>-bytes.json per external box: raw serialized box bytes (id-verifiable; true content creationHeight per the 2026-06-08 amendment).
- block-2666-full.json: real proofBytes, blake2b256==adProofsRoot VERIFIED; regenerated locally from rust UTXO state (genesis replay — no testnet peer keeps ADProofs).
