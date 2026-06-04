# Finding — `DeserializeContext` over an absent/wrong-typed var (testnet block 111,927)

> **Status: PARKED — block-tier seed case, saved 2026-06-04.** This directory is the real-block
> (item 1) half: a V3 tree whose canonical spend needs the full tx context, so it needs the
> **block tier** (unbuilt).
>
> **The eval-catchable cases are authored**, at
> `vectors/eval/v6/authored/DeserializeContext_over_absent_wrong_typed_var.json` — dead-branch
> ACCEPT + live-path REJECT. They became eval-catchable once the eval runners were broadened to
> run the substitution pass (`try_eval_with_deserialize`; runner-contract §2). Unlike the
> [bigint-downcast-2666](../testnet-bigint-downcast-v3/) seed, the *bug* is not eval-blind; only
> this *tree* needs the block tier, for its context.
>
> Source: `~/projects/sigma-rust/prompts/santa-deserialize-context-vector.md`.

## The case

Testnet **block 111,927** (`5e9197cf5224b3258b6f9598655af4696ef80a78dab5c15cfe8278ca9fc0614f`),
**transaction index 2** (`2b7848c8f213527032e58ebec49bd2ef1d71fe375685f0a5817d194501f1fe77`),
**input 0**, spending box `f9c2433ebb84a24889b089baea6e51cd9752fd9f9549beeb79ab5c4bfbc3b67a`
(creationHeight 111,875, **V3**). The tree reduces to:

```
if (upcast(HEIGHT) < SELF.R7.get + 50) {
    allOf(sigmaProp(... dataInputs(0).R4.exists(...) ...), deserializeContext(0))  // dead branch
} else {
    sigmaProp(blake2b256(OUTPUTS(0).propBytes) == "8437.." && <R4..R7 preservation>
              && HEIGHT >= R7+50 && SELF.id == INPUTS(0).id)                        // live at h111,927
}
```

At spend height 111,927 the condition is **false** → the `else` branch is live and never references
var 0; input 0's proof + extension are **empty** → it reduces to `sigmaProp(true)` → **ACCEPT** with
an empty proof. The testnet network (JVM 6.0.x) accepted it; the `ergo-node-rust` node (riding the
sigma-rust fork) **rejected** it and wedged at 111,926 — pre-fix, eager substitution hit the *dead*
`if`-branch's `deserializeContext(0)` over the absent var 0 and errored (`ExtensionKeyNotFound`),
sinking the whole reduction. **Expected: ACCEPT.** The 4th wedge in the testnet-sync stretch
(powHit #877 → Upcast→UBI #878 → gen_indexes #847 → this → atLeast).

## Root cause

`DeserializeContext(id)` is resolved by a whole-tree substitution pass before reduction
(`Interpreter.applyDeserializeContext` → `everywherebu(substDeserialize)`; sigma-rust's
`Expr::substitute_deserialize`). The JVM's `substDeserialize` returns **`None`** — leaving the node
in place — for an absent var (`else None`) and for a present non-`SByteArray` value; a leftover node
only fails if the *live* path evaluates it. sigma-rust eagerly **errored** on absent / wrong-typed
`id` even on a dead branch. **Fix:** PR **#879** (`fix/deserialize-context-absent-var` → develop) /
eni cherry-pick **`46df20c0`** (absent var + non-bytearray var). The negative twin is preserved — a
leftover node on the *live* path still errors at eval ("DeserializeContext cannot be evaluated").

## Why item 1 needs the block tier (but the bug itself is eval-catchable)

- **The *bug* is eval-catchable** — the eval runners now run the substitution pass
  (`try_eval_with_deserialize`), so the distilled dead-branch ACCEPT / live-path REJECT cases at
  `vectors/eval/v6/authored/` reduce directly: a buggy impl errors on the dead-branch absent var
  where the JVM accepts. (Confirmed by `./conform`: rudolph + eni green, develop red — lacks #879.)
- **This *tree* still needs the block tier.** Its `if`-condition reads HEIGHT / `SELF.R7` /
  `dataInputs(0).R4` / OUTPUTS / INPUTS; the eval runner's placeholder context can't reproduce the
  JVM-blessed ACCEPT. A faithful accept vector needs the captured tx + input boxes driven through
  full reduce/verify.

## Vectors to author (when the block tier exists)

1. **Real-block ACCEPT.** Testnet block 111,927, tx 2, input 0 → `valid?` must accept (parse +
   reduce + verify == JVM 6.0.x; empty proof valid). Pre-fix, an impl errors at eager substitution
   on the dead branch's `deserializeContext(0)`.

## Data (this directory)

- `block-111927.json` — full block (header + `blockTransactions`). Failing tx = index 2, input 0.
- `box-f9c2433e.json` — the spending box (SELF), V3 ErgoTree, registers R4–R7.

Spending-box ErgoTree (V3; first byte `0x1b`):
```
1b9402090564040004000400020004000e20a6d4fa307b654dcf31ce07e2462c1be5ca7c5dcc35c1363a0eff62d0b3b9ed3704040e208437796cc1821f8e89272a5d24a0055a80a5eef6ba4e172bca5e905e11e1bf72d804d6017ea305d6029ae4c6a707057300d60393c5a7c5b2a4730100d604b2a5730200958f72017202d801d605b2db6501fe730300ea02d1ededededaee4c67205041ad901060e937206cbe5e3000e8301027304938cb2db63087205730500017306720393c2a7c2720493b1a47307d40800d801d605c1a7d1ededed93cbc2720473089683050193c17204720593e4c672040464e4c6a7046493e4c672040504e4c6a7050493e4c672040606e4c6a7060693e4c672040705720592720172027203
```
