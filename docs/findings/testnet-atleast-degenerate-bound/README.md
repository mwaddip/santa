# Finding — `atLeast` with a degenerate bound (testnet block 184,137)

> **Status: PARKED — block-tier seed case, saved 2026-06-04.** This directory is the real-block
> (item 1) half: a V0 self-replicating contract whose canonical spend needs the full tx context,
> so it needs the **block tier** (unbuilt). Data captured from the local testnet node (port 9053)
> via `/utxo/byId` + `/blocks` **while wedged at fullHeight 184,136** — i.e. with the spending box
> still unspent.
>
> **The eval-catchable cases are authored separately**, at
> `vectors/eval/v5/authored/atLeast_with_a_degenerate_bound.json` — the boundary pair + the
> degenerate set. **Unlike** the [bigint-downcast-2666](../testnet-bigint-downcast-v3/) and
> [deserialize-context](../testnet-deserialize-context/) cases, this bug **is** eval-catchable
> (see below); only the real *tree* needs the block tier, because of its context, not the bug.
>
> Source: `~/projects/sigma-rust/prompts/santa-atleast-degenerate-bound-vector.md`.

## The case

Testnet **block 184,137** (`f916afff16db6bf151e99bf71f178b40a55268e8ae531d3c0c30f72f4b11aaa1`),
**transaction index 1, input 0**, spending box
`1bf630b918d57a2e4e75af517a70dc9bb70c36a3022f200f4182fc43d9b575d3` (creationHeight 184,133,
value 10 ERG, registers R4–R7, **V0** — header `0x10`). A self-replicating contract; the relevant
term is `atLeast(1, conditions)` where `conditions` is an **empty** `Coll[SigmaProp]` (size 0),
which the JVM reduces to `FalseProp` and the surrounding logic accommodates → the canonical spend
**ACCEPTs**.

The testnet network (JVM sigma-state 6.0.x) accepted block 184,137. The `ergo-node-rust` node
(riding the sigma-rust fork) **rejected** it and wedged at 184,136:

```
Atleast: bound 1 > input size 0
```

**Expected: ACCEPT.** A fork-direction divergence — sigma-rust rejects a block the network
validated. The 5th wedge in the testnet-sync stretch (powHit #877 → Upcast→UBI #878 →
gen_indexes #847 → DeserializeContext #879 → this).

## Root cause

`AtLeast.reduce` (JVM, `sigma/ast/trees.scala`) reduces a degenerate bound to a trivial prop and
**never errors for a valid tree**: `bound ≤ 0` → `TrueProp`; `bound > nChildren` (incl. `> 255`,
since a valid tree has `nChildren ≤ 255`) → `FalseProp`. sigma-rust's `AtLeast` eval
(`ergotree-interpreter/src/eval/atleast.rs`) had eager error guards **before** reduce — throwing on
`bound > size`, `bound > 255`, and `bound < 0` (i32→u8). Its own `Cthreshold::reduce` already
encoded the correct behavior (`k==0 → TrueProp`, `k > len → FalseProp`); the bug was purely the
pre-reduce guards.

**Fix:** sigma-rust `fix/atleast-degenerate-bound` (→ develop) + eni cherry-pick (tip reported once
pushed; base `46df20c0`). Value-only — `AtLeast.costKind` is charged over `nChildren` regardless of
the bound check, so cost is unaffected.

## Why item 1 needs the block tier (but the bug itself is eval-catchable)

- **The *bug* is eval-catchable** — unlike 2666 (production `tree_version`) or DeserializeContext
  (the eager substitution pass the old eval runner skipped). `AtLeast::eval` is a normal eval node;
  bound + children are read straight from the tree, with no pre-set state to mask it. So the
  distilled cases at `vectors/eval/v5/authored/` reduce directly on the eval runner — a buggy impl
  errors where the JVM yields a SigmaProp. (Confirmed: rudolph green; eni/develop red until they
  carry the fix.)
- **This *tree* still needs the block tier.** The real V0 contract reads INPUTS/OUTPUTS/SELF +
  registers; the eval runner's placeholder context can't reproduce the JVM-blessed ACCEPT. A
  faithful accept vector needs the captured tx + input boxes driven through full reduce/verify.

## Vectors to author (when the block tier exists)

1. **Real-block ACCEPT.** Testnet block 184,137, tx 1, input 0 → `valid?` must accept (parse +
   reduce + verify == JVM 6.0.x). Pre-fix, an impl errors at eval with `Atleast: bound 1 > input
   size 0`.

## Data (this directory)

- `block-184137.json` — full block (header + `blockTransactions`). Failing tx = index 1, input 0.
- `box-1bf630b9.json` — the spending box (SELF), captured unspent from the wedged node (V0
  ErgoTree, 4 registers, 10 ERG, plus a token).

Spending-box ErgoTree (V0; first byte `0x10`):
```
101904000400040204020400040004000100040204040400040004000100040004000400040005c60105c80101000402040004020e2102eab569326ae73e525b96643b2c31300e822007c91faf0c356226c4942ebe9eb2d812d601e4c6a70413d602b17201d603e4c6a70704d604b2a5730000d60593c27204c2a7d606e4c6a70605d60793e4c6720406057206d60893e4c6720407047203d609e4c672040513d60ae4c6a70513d60bdb63087204d60c91b1720b7301d60de4c672040413d60eb1720dd60f91b1a57302d61095720fb2a5730300a7d611db6a01ddd612e5e300077211eb02d1ededededededed8f72027203720572077208937209720a95ed720c91b1db6308a77304938cb2720b730500029a8cb2db6308a7730600027206730793720e9a72027308afdb0c0e7201d901130493b2720d721300b27201721300ea02d1ededededededed927202730972057207720893720e720295ed720c91b1db6308a7730a938cb2720b730b0002998cb2db6308a7730c00027206730d95eded720f91b1db63087210730e91b1db6308a7730fd801d613b2db63087210731000ed938c7213018cb2db6308a773110001928c7213029d9c7206731273137314edededaf720ad9011307947213721293b172099ab1720a731593b472097316b1720a720a93b27209b1720a007212987317ad7201d9011307ea02cd7213ce7211ee731872137212
```
