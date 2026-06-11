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

**5 files / 10 entries.** Value-only (no cost dimension at this tier). Two families:

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
| `v6/authored/Voting.threshold_edges.json` | authored | 3 | `half` (64 of 128 votes incl. the seed — strict `>` means NO step) · `half-plus-one` (65 → exactly id 1 steps) · `softfork-below-threshold` (fork votes without an in-progress round count for nothing; blockVersion holds, activated `"0000"`) |
| `v6/authored/Voting.window_clamp.json` | authored | 1 | Chain-start window clamp: boundary 128, window `[1,127]`, stream[0] is not the previous boundary ⇒ EMPTY seed ⇒ unseeded votes drop, identity table |

Gap: no v5 voting family yet (the committed scenarios are v6-era tables; the version label is a selection threshold, see the contract §2).

## Regenerating

The artifact is **deterministic** (sorted keys, no timestamps) and **suite-gated**:
`CoverageManifestTest` rebuilds it from `vectors/eval/` on every `sbt test` run and
fails if the committed file is stale. After any corpus change:

```bash
cd jvm-blesser && SANTA_WRITE_COVERAGE=1 sbt -batch test   # regenerates + gates
```

Generator: `jvm-blesser/src/test/scala/santa/CoverageManifest.scala`.
