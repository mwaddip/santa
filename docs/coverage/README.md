# Corpus coverage manifest

`eval-coverage.json` (`santa-coverage/v1`) is the standing **per-family op / method /
arm map** of the eval corpus: what the committed vectors actually exercise, machine-read
off each entry's **deserialized ErgoTree** — never off `script` text, which is
type-ambiguous (`.get` could be `Option`/`AvlTree`/`Coll`; the tree carries the exact
nodes).

## What it answers

- *"Does any vector exercise method X?"* — look up `method_index["typeId:methodId"]`.
  The namespace is the serialized `MethodCall` one, shared with conformer method
  registries (e.g. ergots' `mir/method-signatures.ts`, sigma-rust's `SMethodDesc`), so
  a registry diff is a key set-difference. `PropertyCall` materializes as `MethodCall`;
  one index covers both.
- *"Which families hit op Y, and under which opcodes?"* — `op_index[opName]`.
- *"Is there a treeVersion≥3 + hasSize accept for family Z?"* — the family's
  `tree_shapes`: distinct `(tree_version, has_size, constant_segregation,
  has_deserialize)` combos with per-arm counts.
- *"How big is the reject arm?"* — per-family `accepts` / `rejects`
  (`expected.error` null ⇒ accept).

A green conformer grade means *green on what exists*; this manifest is the "what
exists". The gap map — registry entries absent from `method_index` — is where new
vectors (or a conformer's risk acceptance) come from.

## Shape

```jsonc
{
  "schema": "santa-coverage/v1",
  "tier": "eval",
  "totals": { "files", "entries", "accepts", "rejects", "unwalked",
              "distinct_ops", "distinct_methods" },
  "families": {                       // keyed by vectors/eval-relative path
    "v5/spec/substConstants_equivalence.json": {
      "op": "substConstants equivalence",
      "version": "v5", "provenance": "spec",
      "entries": 9, "accepts": 7, "rejects": 2, "unwalked": 0,
      "ops": ["Apply", "SubstConstants", "…"],        // opName per tree node seen
      "methods": ["99:19 Box.getReg", "…"],           // MethodCalls seen
      "tree_shapes": [ { "tree_version": 2, "has_size": true,
                         "constant_segregation": true, "has_deserialize": false,
                         "accepts": 7, "rejects": 2 } ]
    }
  },
  "op_index":     { "<opName>":          { "op_codes": ["0x.."], "families": [..] } },
  "method_index": { "<typeId:methodId>": { "name": "Box.getReg", "families": [..] } }
}
```

`unwalked` counts entries whose committed bytes do not deserialize to a walkable root
— e.g. an adversarial reject whose *parse* is the thing under test (the only current
one: `v6/authored/Box.getReg_adversarial.json`'s v2-header tree). Such entries are
counted in the arms but contribute no ops/shapes.

## Caveats (honest limits)

- Coverage is **structural presence**, not semantic depth: a family whose tree
  *contains* an op exercises that op's node, which says nothing about edge-case
  breadth for it.
- **Embedded script bytes are data**, not walked: a tree passed to `substConstants`
  as a `Coll[Byte]` argument contributes nothing to `ops` — its shape must be read
  from the entry itself (constant bytes), not the manifest.
- Eval tier only. Wire/transaction/chain coverage is small enough to read directly.

## Chain tier coverage

**11 files / 40 entries.** Value-only (no cost dimension at this tier). Two families: retargeting + parameter voting. The voting `v6/authored` slice also carries a **reject arm** (`expected.error: "errored"`, contract §2) covering hostile input classes.

### Retargeting (difficulty arithmetic; dir version `any`)

| File | Provenance | Entries | Notes |
|---|---|---|---|
| `any/captured/Retargeting.testnet_points.json` | captured | 2 | Real testnet recalculation points (targets 393601 / 393473, classic arm) — engine output FAIL-LOUD-equal to the on-chain headers' nBits |
| `any/authored/Retargeting.damping_clamps.json` | authored | 3 | `flat-control` (a flat chain retargets to itself) · `fast-chain-clamps-up` (1.5× cap binds) · `slow-chain-clamps-down` (0.5× floor binds). The clamps live only in `eip37Calculate`, so these entries carry `eip37_activation_height`/`eip37_epoch_length` — the settings-driven EIP-37 dispatch is exercised; classic-arm unclampedness is pinned generator-side (EVIDENCE test, not a vector) |

Gaps: a mainnet EIP-37-era captured window (needs a mainnet header source; authored entries cover the arm's math) · the nipopow kind (probed GO at design time, deferred to its own round).

### Parameter voting (v6 governance math; dir version `v6`)

| File | Provenance | Entries | Notes |
|---|---|---|---|
| `v6/captured/Voting.testnet_epoch_2560.json` | captured | 1 | Real epoch boundary 2560 (identity epoch — table equality is the pin); engine table FAIL-LOUD-equal to `parseExtension` of the on-chain boundary extension |
| `v6/authored/Voting.threshold_edges.json` | authored | 4 | `half` (64 of 128 votes incl. the seed — strict `>` means NO step) · `half-plus-one` (65 → exactly id 1 steps) · `softfork-below-threshold` (fork votes without an in-progress round count for nothing; blockVersion holds, activated `"0000"`) · `id-9-steppable` (id 9 steps when voted, SEEDED tally + "0000" pins the activation snapshot) |
| `v6/authored/Voting.window_clamp.json` | authored | 1 | Chain-start window clamp: boundary 128, window `[1,127]`, stream[0] is not the previous boundary ⇒ EMPTY seed ⇒ unseeded votes drop, identity table |
| `v6/authored/Voting.softfork_round.json` | authored | 7 | Round lifecycle snapshots: `softfork-round-start` (121 inserted, tally zeroed) · `softfork-round-accumulate` (mid-round header cast) · `softfork-round-last-accumulate` (final collecting epoch) · `softfork-round-wait-identity` (identity pass during wait phase S+4096→S+8192) · `softfork-round-failed-cleanup` (S+4224 branch — NOT approved, 121/122 cleared) · `softfork-round-failed-restart` (round resets to new startingHeight after failed cleanup) · `softfork-round-midround-forkvote-noop` (fork-vote mid-round is a no-op) |
| `v6/authored/Voting.softfork_activation.json` | authored | 8 | Activation basis yes/no · id-9 insertion at activation · 409-disable suppression · testnet-proposal suppression · sigma-rule pass-through · cleanup (removes 121/122) · cleanup-restart (new round starts immediately after cleanup) |
| `v6/authored/Voting.softfork_zombie.json` | authored | 4 | Zombie checkpoint-flips: `softfork-zombie-survive` (S+4224 approval saves round, S+8192 fails — 121/122 persist past activation height) · `softfork-zombie-no-activation` (follow-on: no activation fires) · `softfork-zombie-late-cleanup` (S+8320 approval fires successful-voting cleanup, but blockVersion was never bumped) · `softfork-zombie-stuck` (all checkpoints exhausted with no cleanup — 121/122 permanent, no new round ever possible); see [`docs/findings/chain-softfork-zombie-liveness.md`](../findings/chain-softfork-zombie-liveness.md) |
| `v6/authored/Voting.hostile_tables.json` | authored | 3 | Reject classes: `hostile-122-without-121` (122 present but 121 absent → errored) · `hostile-unknown-id-approved` (approved vote for unknown parameter id → errored) · `hostile-mandatory-rule-update` (mandatory-rule update field violates parse invariant → errored) |
| `v6/authored/Voting.lifecycle_leniency.json` | authored | 4 | enr cross-read asks A/B/E: `leniency-122-without-121-nonforce` (the LAZY-votes inverse of the hostile — orphan 122 passes through at a non-force boundary, ordinary step still applies) · `inert-121-without-122` (orphan 121 verbatim pass-through) · `overwrite-121-without-122-forkvote` (restart overwrites the orphan: 122=T, 121=0) · `wrap-int-votes-collected` (Int.MaxValue collected + 1 closing vote WRAPS negative ⇒ fail-cleanup; saturating impls keep the counters) |
| `v6/authored/Voting.tally_order.json` | authored | 3 | enr cross-read ask C — the tally is an ordered Array, duplicates kept; updateParams steps from the post-fork SNAPSHOT: `tally-order-updown`/`-downup` (contradictory ±1 seed slots, on-chain-unreachable/legality-upstream — last-write-wins flips the step direction with slot order) · `tally-dup-120-first-entry` (duplicated 120 seed: votesInPrevEpoch reads the FIRST entry, not the sum — straddled at a checkpoint, the lifecycle outcome discriminates) |

Gap: no v5 voting family yet (the committed scenarios are v6-era tables; the version label is a selection threshold, see the contract §2).

## Regenerating

The artifact is **deterministic** (sorted keys, no timestamps) and **suite-gated**:
`CoverageManifestTest` rebuilds it from `vectors/eval/` on every `sbt test` run and
fails if the committed file is stale. After any corpus change:

```bash
cd jvm-blesser && SANTA_WRITE_COVERAGE=1 sbt -batch test   # regenerates + gates
```

Generator: `jvm-blesser/src/test/scala/santa/CoverageManifest.scala`.
