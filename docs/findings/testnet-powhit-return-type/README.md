# Finding — `Global.powHit` return type under ErgoTree V3 (testnet block 28,474)

> **Status: PARKED — block-tier seed case, saved 2026-06-04.** This directory is the
> real-block (item 1) half: a context-dependent guard script that needs the **block tier**
> (unbuilt) to bless faithfully — the eval tier can't reproduce its real context.
>
> **The eval-catchable half (item 2) is authored separately**, at
> `vectors/eval/v6/authored/` — a distilled standalone `map(powHit).{exists,filter,forall}`
> tree. Unlike the [bigint-downcast-2666 seed](../testnet-bigint-downcast-v3/), this bug is
> **not** eval-blind (see "Why item 1 needs the block tier" below); only this *particular,
> context-reading* tree does.
>
> Source: `~/projects/sigma-rust/prompts/santa-powhit-unsignedbigint-hof-vector.md`
> (git-excluded scratch — copied here so it survives). Supersedes an earlier guess
> (`ergo-node-rust/prompts/santa-unsignedbigint-hof-vector.md`) that blamed `SBigInt` /
> a register read — the confirmed mechanism is the `powHit` return type.

## The case

Testnet **block 28,474** (`77f0f301facf826b4c7b8e84a7666a397cfd376d1214427e1fdde1a4441657d0`),
**transaction index 4** (`432b15c086bc2fb5044d3dfa36df3c41cda4c68a38e1369e4f470bf9759c8571`),
**input 1**, spending box `1d746ebe5da0a0df46de9c34c60c5ed642b07fbff7c4cbf46dc93b1cd4a95166`
(created by an earlier tx in the **same** block — self-contained in the block JSON, no
registers). The **V3** ErgoTree maps `Global.powHit` over a collection and runs a HOF on the
result:

```
coll.map { x => Global.powHit(k, msg, x, h, N) }      // JVM: Coll[UnsignedBigInt]
    .exists { (u: UnsignedBigInt) => u > hit / diff }  // predicate domain SUnsignedBigInt
```

The testnet network (JVM sigma-state 6.0.3) accepted this block — canonical history.
The `ergo-node-rust` node (riding the sigma-rust fork) **rejected** it and forked off testnet
at block 28,474 with `Invalid condition tpe`.

**Expected: ACCEPT** (parse + reduce/verify == JVM 6.0.3). A fork-direction divergence —
sigma-rust rejects a block the network validated.

## Root cause

- `Global.powHit` (the Autolykos-2 PoW *hit value*) was mis-declared in sigma-rust with
  **`t_range: SBoolean`**. The Scala oracle (`sigma/ast/methods.scala`, `powHitMethod`,
  methodId 8, v6.0/V3) declares **`SUnsignedBigInt`**; `powHit_eval` yields an
  `UnsignedBigInt`. Signature:
  `(SGlobal, SInt, SByteArray, SByteArray, SByteArray, SInt) => SUnsignedBigInt`.
- With `powHit: SBoolean`, sigma-rust resolved `coll.map { x => powHit(..) }` to
  `Coll[Boolean]`, and `Exists::new` (exact `t_dom[0] == elem_type` check) rejected the
  `UnsignedBigInt => Boolean` predicate: `Invalid condition tpe`. The predicate side was 100%
  correct (it contains working UBI ops: `BigInt.toUnsigned`, `Upcast(Int)→UBI`, UBI `Divide`).
  It is purely the `powHit` return type.
- **sigma-rust fix:** PR **#877** (`fix/powhit-return-type`, develop) / eni cherry-pick tip
  **`96367193`**.

## Why item 1 needs the block tier (and how it differs from the 2666 seed)

This finding's **eval-catchability** is the opposite of the [bigint-downcast-2666
seed](../testnet-bigint-downcast-v3/), and the distinction is worth keeping straight:

- **The *bug* is not eval-blind.** It's a method-table return type, consulted at
  **parse / proposition** — `Exists::new`'s `t_dom[0] == elem_type` check fires while
  deserializing the HOF node. The SANTA eval runner exercises exactly that path
  (`ErgoTree::sigma_parse` → `root_expr`/`proposition` in `runners/blitzen-eni/src/eval.rs`),
  and **nothing in `build_context` overrides a method's return type** — there is no field to
  pre-set, so no masking. Contrast 2666, whose bug is `ctx.tree_version()` defaulting to V0;
  the eval runner *sets* `tree_version` from the vector (`eval.rs:106`), pre-supplying the
  exact value the bug fails to derive. That is what makes 2666 eval-blind; powHit is not.
- **This *tree* still needs the block tier.** The real block-28,474 script is a context guard
  (reads INPUTS/OUTPUTS/SELF, registers, amounts, script bytes). The eval runner evaluates
  against an *arbitrary placeholder* context — only the var-1 input + versions are real
  (`eval.rs` arbitrary-context template) — so it cannot reproduce the JVM-blessed **ACCEPT
  value**. A faithful accept vector for *this* tree needs a runner that drives the real
  reduce/verify against the captured tx + its input boxes — i.e. the block tier.

So the bug is covered **now** at the eval tier by the distilled item-2 vector; this directory
preserves the real-block end-to-end case for when the block tier exists.

## Vectors to author (when the block tier exists)

1. **Real-block ACCEPT.** Testnet block 28,474, tx index 4, input 1 → `valid?` must accept
   (parse + reduce + verify == JVM 6.0.3). Pre-fix, an impl typing `powHit` as `SBoolean`
   errors at `proposition()` with `Invalid condition tpe`. Run through the full
   reduce/verify path, never an eval helper.

## Data (this directory)

- `block-28474.json` — full block (header + `blockTransactions`). Failing tx = index 4,
  input 1; its spending box is created by an earlier tx in the same block (self-contained,
  no registers).

Failing-input ErgoTree (V3, constant-segregated; first byte `0x1b`):
```
1bb8042204000200040004100400041004000402040404060408040a040c040e041004120400040804b803040204400442040804c003040004080440040804ee020400040806207fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed04a09c010402d807d601b2a4730000d602e4c672010464d6038301027301d604e5e3000e7203d605e4dc640a7202027204e5e3010e7203d606d901060e7cb4720673027303d607b2a5730400d1edaeaddad901080ed801d60ab472087305b172088cb0830a047306730773087309730a730b730c730d730e730f860283000e7310d9010b4c4c1ad804d60d8c720b01d60e8c720d02d60f9a9a720e73117312d6109a9a9a720f731373149c7eb2720a720f000473158602b38c720d0183010eb4720a720e7210721001017205d901080ed801d60adad9010a0ed801d60cdad9010c0edc6a04dd01b4720c731673176801720a8602db680d720cb47a7edb6809720c0573187319017208dc6a08dd05731adad9010b0ecbb4720b731b731c0172088c720a018c720a02dad9010b0edc6a05dd01b4720b731d731e04017208d90108099172089ddad9010a0edad9010c099ddb060e731f720c01db060e7eda720601720a060172057e7320099683060193c17207c1720193db6401e4dc640e72020283010e7204e5e3020e7203db6401e4c67207046493e4c67207050499e4c672010504732193e4c67207060699e4c6720106067eda72060172050693e4c672070705e4c67201070593e4c672070805e4c672010805
```
- block-tier capture (today set): headers-28464-28473, 8 external boxes, epoch-block-28416 (params in force at 28474). Full block with ADProofs: pending digest side-sync.
- box-<id16>-bytes.json per external box: raw serialized box bytes (id-verifiable; true content creationHeight per the 2026-06-08 amendment).
- ADProofs: see ADPROOF-FINDING.md. Rust prover emits a non-canonical (but valid) proof for this block's data-input lookup; canonical proof needs a JVM source.
