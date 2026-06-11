# SANTA

**S**igma-**A**nchored **N**ode **T**est **A**pparatus — a cross-implementation
conformance test suite for Ergo consensus.

> **Built in the open, slice by slice.** The design takes shape from working
> deliveries rather than a big up-front spec — see **[SPEC.md](SPEC.md)** for the
> current architecture + roadmap, and **[BOOTSTRAP.md](BOOTSTRAP.md)** for the
> rationale behind each call.

## What this is

Language-agnostic conformance **test vectors** + thin per-implementation
**runners** + CI, so multiple independent Ergo implementations can be checked
against the same canonical inputs and expected outputs — the way Ethereum's
[`execution-specs`](https://github.com/ethereum/execution-specs) (the executable
spec + test framework, formerly `execution-spec-tests` / EEST) lets
geth / besu / nethermind / reth prove consensus-equivalence.

The guiding principle is **"the wire is the spec"**: a vector is *raw serialized
bytes in → expected output out*, since every implementation already parses the
wire format. Expected outputs are anchored to **canonical oracles**, never to any
single implementation:

- **block validity → the chain** — a block on mainnet is valid by definition;
- **fine-grained eval outputs (value, cost, reduced sigma-tree) → the JVM reference
  node** (ergo-core / `sigma-state`), the de-facto spec.

Four tiers:

- **Wire tier** — serialization: bytes ⇄ structure (constants, boxes, trees, txs,
  headers). The broadest — every wallet/SDK serializes (ergots, sigma-rust, scorex,
  Fleet, …); and a `boxId` *is* the hash of serialized bytes, so it's squarely consensus.
- **Eval / transition tier** — operation-level (ErgoTree + context → typed value, with
  cost). Run by the consensus *libraries*, no full node required.
- **Transaction tier** — library-decidable tx validity given full inputs (script-verify
  + conservation / tokens / min-value / cost). No full node required; blessed by
  `ergo-core validateStateful`.
- **Block tier** — digest-state block validity: parent state digest + ≤10 headers +
  parameters + a full block *with ADProofs* → `valid?` + computed post-digest + cost.
  Library-decidable (Ergo's stateless-validation design — no UTXO DB, no sync); run by
  digest-capable validators (ergo-node-rust's `validation` seam, the gated JVM engine).

## Status

The **eval tier is closed and scaled**, and the conformance loop is already surfacing
genuine cross-implementation divergences — which is exactly its job. What runs today:

- ✅ **A blessed eval corpus — 2,346 entries across 211 vector files**: 2,026 produced by
  the JVM reference interpreter (`sigma-state`) from its own language specification, plus
  314 authored gap-fillers (oracle-blessed, never spec-copied); version-split into **v5**
  (1,929 entries — the cumulative v5/mainnet method surface)
  and **v6** (411 — the v6 new-feature surface). Each entry is `ErgoTree bytes (+ input)
  → typed value + raw JIT cost`, committed with the `(activated, ergoTree)` version it
  was blessed under.
- ✅ **A runner-agnostic orchestrator — `./conform`** (presence-as-state over `runners/*/`,
  one shared comparator, a per-runner **per-slice** 🎁/🪨 table). Five runners wired today:
  **Rudolph** (the JVM reference — the all-🎁 control that blessed the corpus), **Dasher**
  (the pure-TS `ergots` library, [`ts-runner/`](ts-runner/)), **Blitzen** as two
  submodules pinning `sigma-rust` at upstream `develop` (value-only) and the
  `ergo-node-integration` fork (`--features jit-cost`), and **Comet** (the pure-TS
  **Fleet SDK** — wire tier only). Each is graded against the
  JVM-blessed `expected` — the runner is SANTA's; the implementation under test is a dependency.
- ✅ **Live results — the loop is surfacing real divergences.** Dasher is **fully green
  across the entire eval tier** (every v5+v6 spec+authored slice, value, cost, and reject —
  2026-06-10); its remaining reds are roadmap not-impls (the growth ledger). Blitzen-eni
  (`sigma-rust`'s `ergo-node-integration` fork) has converged to **red 0 four times**
  (eval + wire + transaction, value *and* cost) — each time a new authored round surfaced
  the next genuine divergence class, routed it, and the fork closed it. The two newest
  rounds (both 2026-06-10): the **SFunc-arity witnesses** — the JVM rejects non-unary
  lambdas eagerly at closure creation, but sigma-rust (both branches) *and*
  arkadianet/ergo all **evaluated multi-arg lambdas to completion**; one authored family,
  the same over-accept class in three independent Rust implementations, fixed in eni the
  same day (the fourth convergence) — and the **atLeast children-cap pins**, where every
  conformer went red on a *different* arm (eni: cap checked after the bound≤0 degenerate;
  develop: bound>n over-reject at the cap boundary). Plain upstream `develop` misses the
  values the fork already fixed, plus the v6 surface. A 🪨 is the suite doing its job,
  never silenced.
- ✅ **A frozen runner contract** ([`docs/contract/runner-contract.md`](docs/contract/runner-contract.md))
  + a JVM blesser, the JVM reference runner (*Rudolph*), and a harness. A runner is
  **total**: it emits one faithful outcome for *every* entry — value + cost on success,
  else a coarse tag (`errored` / `not-implemented` / `panicked`) — and
  never drops, hides, or aborts the run on one. A conformer's **scope is chosen on the input side** (it runs the vector
  subset it claims; dasher's manifest declares version ≤ v6). **A divergence is the deliverable** — surfaced
  and routed, never silenced; a red gate means the suite is working.
- ✅ **Machine-checkable gates** — a JSON-Schema validator over the whole corpus (155 eval + 4 wire + 4 tx)
  and an end-to-end conformance gate. The CI seed.
- ✅ **A standing coverage manifest** ([`docs/coverage/eval-coverage.json`](docs/coverage/eval-coverage.json),
  `santa-coverage/v1`) — the per-family op / method / arm map of the eval corpus, read
  off each entry's **deserialized tree** (never script text): every node's op, every
  `MethodCall`'s `(typeId, methodId)`, and the tree shapes
  (version / size-bit / segregation / deserialize × accept / reject). Suite-gated
  current; a conformer diffs its method registry against `method_index` to see what
  the corpus exercises vs. not. See [`docs/coverage/`](docs/coverage/README.md).
- ✅ **Wire tier live** — `santa-wire/v1` byte-round-trip vectors, **213 entries**
  (`Constant` 178 · `Box` 11 · `SigmaBoolean` 7 · `Transaction` 17), JVM-canonicalized
  from ergots' `fixture-gen` + Fleet's `_test-vectors` seeds. Rudolph + Blitzen 213/213;
  Dasher 196 (no tx serializer — growth ledger); Comet 185 (Fleet's honest gaps, recorded
  as findings). Contract:
  [`docs/contract/runner-contract-wire.md`](docs/contract/runner-contract-wire.md).
- ✅ **Transaction tier live** — `santa-transaction/v1` schema; **4 captured vectors**
  (`vectors/transaction/v6/captured/`), each JVM-blessed via `ergo-core 6.0.2.1
  validateStateful`. Conformer stances: **Rudolph control** (gated `TxEngine`; the
  conform CI publishes ergo-core itself, cached by tag) · **Blitzen-eni `valid 4/4 ·
  cost 4/4` byte-exact** (the initial bless surfaced 3 genuine cost divergences —
  decomposed, routed, fixed in the fork, re-graded exact: the tier's first
  divergence→fix→convergence loop) ·
  **Blitzen-develop `valid 0/4`** (upstream bugs; the bigint-downcast
  seed exposes the tree-version bug eval cannot catch) · **Dasher `4 not-implemented`**
  (growth ledger) · **Comet out-of-scope** (wire-only; Fleet has no verifier). Contract:
  [`docs/contract/runner-contract-transaction.md`](docs/contract/runner-contract-transaction.md).
- ✅ **Block tier live** — `santa-block/v1`, the **digest-state** shape: parent digest +
  ≤10 headers + parameters + block-with-ADProofs → `valid` + computed `post_digest` +
  `cost`. **4 captured testnet seeds** (block 2666 cost 39379 — the triple-anchored
  keystone · 111927 · 184137 · the epoch-boundary donor 2560), ADProofs-verified at
  bless time, **+ 6 authored mutation classes**, each JVM-confirmed to reject for its
  intended reason (version-gate rides the boundary donor — a donner-surfaced finding
  established the JVM's version check fires only at epoch boundaries; its re-authored
  form immediately caught both independent conformers trusting the block's
  self-declared extension over the handed pre-state params).
  The 4th seed (28474) awaits a canonical proof — the rust AVL prover emits a
  valid-but-non-canonical proof for data-input lookups, a latent serve-side consensus
  bug recorded in
  [`ADPROOF-FINDING.md`](docs/findings/testnet-powhit-return-type/ADPROOF-FINDING.md).
  Conformers: **Rudolph control** `3/3·3/3·3/3 + 5/5` · **donner** (ergo-node-rust's
  digest-state seam) **live** — building it surfaced + fixed two real enr consensus
  bugs before mounting · **vixen** (arkadianet/ergo) grading too — its debut surfaced
  a real block-cost divergence · other libraries grey (block application is the
  node's layer). Contract:
  [`docs/contract/runner-contract-block.md`](docs/contract/runner-contract-block.md).
- ✅ **Chain tier live** — `santa-chain/v1`; **9 files / 33 entries** across two
  families: **retargeting** (difficulty arithmetic) + **parameter-voting** (v6
  governance math). Value-only (no cost dimension). The voting `v6/authored` slice
  carries a reject arm (`expected.error: "errored"`, contract §2). Provenance split:
  `any/captured` (2 — testnet-anchored retargeting points) · `any/authored` (3 —
  difficulty-damping clamp edges, EIP-37 arm settings-driven) · `v6/captured` (1 —
  testnet epoch-boundary voting point) · `v6/authored` (27 — threshold edges (4) +
  voting-window clamp (1) + soft-fork round lifecycle (7) + activation basis/edge/id-9
  insertion/sigma-rule/cleanup (8) + zombie checkpoint-flips (4) + hostile-reject
  classes (3)).
  Conformers: **Rudolph control** 33/33 (all chain slices green) · **donner LIVE**
  (8/27 chain/v6/authored — 19 new-batch reds are findings) · **vixen LIVE** (21/27 —
  6 new-batch reds are findings) · blitzen nipopow-at-most deferred · dasher ledger.
  Contract:
  [`docs/contract/runner-contract-chain.md`](docs/contract/runner-contract-chain.md).

Still greenfield, and where help is most wanted (see below):

- the tx-tier **authored reject arm**; more captured block seeds (incl. 28474's
  canonical proof);
- more **independent runners** — donner (ergo-node-rust, block tier) is routed and
  pending; alt impls welcome at every tier.

## Layout

```
SPEC.md            umbrella spec — architecture, tiers, contracts, roadmap, glossary
BOOTSTRAP.md       design rationale + decision log (the *why*)
docs/contract/     the frozen runner I/O contracts (eval · wire · transaction)
docs/specs/        per-phase subspecs
docs/findings/     recorded cross-implementation divergences
docs/coverage/     standing corpus coverage manifest (what the vectors exercise)
schema/            JSON Schemas for vectors + actuals, and the validator
vectors/eval/      the canonical eval corpus — the "nice list" (v5/ and v6/)
vectors/wire/      wire-tier round-trip vectors
vectors/transaction/  transaction-tier captured vectors (v6/captured/)
jvm-blesser/       Scala: the blesser, the JVM reference runner (Rudolph), the harness
ts-runner/         Dasher — the ergots runner + the conformance gate
runners/           per-conformer dirs (rudolph · dasher · blitzen-develop · blitzen-eni · comet)
conform            the runner-agnostic orchestrator — runs every runner, prints the table
README.md          this file
```

## Re-blessing transaction vectors (maintainer only)

The transaction *blesser* is env-gated and never runs in CI — committed vectors are the
reproducible artifact. (The same gate also compiles rudolph's transaction **grading** arm:
the conform CI publishes ergo-core itself and sets `SANTA_TX_BLESSER=1`, so the canonical
grid carries the tx control row; a build without ergo-core degrades that arm to
`not-implemented`.) To re-bless (e.g. to add new seeds):

1. Clone [`ergoplatform/ergo`](https://github.com/ergoplatform/ergo) at tag `v6.0.2.1`.
2. From that clone: `sbt "avldb/publishLocal" "ergoWallet/publishLocal" "ergoCore/publishLocal"` — publishes `ergo-core 6.0.2.1` to `~/.ivy2/local`.
3. From `jvm-blesser/`: `SANTA_TX_BLESSER=1 sbt -batch "testOnly santa.CapturedTxTest"` — stages blessed JSON under `jvm-blesser/target/tx-vectors/`.
4. Copy the staged files into `vectors/transaction/v6/captured/` and commit.

The bundled `jvm-blesser/src/test/resources/chain-testnet.conf` is pinned by the
[transaction runner contract](docs/contract/runner-contract-transaction.md) and must
not be changed without re-blessing.

## Contributing

SANTA is meant to be **community-owned conformance ground**: the more independent
Ergo implementations run the same vectors, the more the vectors are worth. Help is
genuinely wanted — especially:

- **Implementations under test** — wire a runner for your Ergo implementation
  (TypeScript, Rust, Scala, or anything that parses the wire format) against the
  [runner I/O contract](docs/contract/runner-contract.md). It's a thin adapter:
  vector in → `{value, cost, error}` per entry.
- **Vectors** — more eval-tier operations; wire-tier serialization vectors;
  captured-block vectors for the block tier; authored mutation / reject vectors.
- **Design** — the vector schema, the runner I/O contract, CI topology.

The eval-tier contract is frozen, but the wider design is still taking shape, so
**a conversation beats a big PR** — read [SPEC.md](SPEC.md) + [BOOTSTRAP.md](BOOTSTRAP.md),
look at `jvm-blesser/` and `ts-runner/`, and open an issue to talk through where you'd
like to plug in.

## Related

- [Ergo](https://ergoplatform.org/) — the protocol under test.
- [`sigma-rust`](https://github.com/ergoplatform/sigma-rust) — Rust consensus
  library; a convenience differential target (never the oracle).
- [`execution-specs`](https://github.com/ethereum/execution-specs) — Ethereum's
  executable spec + test framework (the former `execution-spec-tests` / EEST,
  consolidated in 2025); the project SANTA's structure is modeled on.

## Acknowledgements

- [**Fleet SDK**](https://github.com/fleet-sdk/fleet) — its serializer test vectors
  (`packages/serializer/src/_test-vectors`) are **vendored** into SANTA's **wire tier**:
  harvested and re-anchored through the JVM oracle, so each harvest doubles as a JVM-vs-Fleet
  differential pass (178 constants, 17 signed transactions, 7 boxes).
- [**ergots**](https://github.com/mwaddip/ergots)' `fixture-gen` wire fixtures are vendored too
  (boxes + sigma-booleans). Box round-trips from ergots and Fleet share one slice, each entry
  tagged by its framework.

## License

[MIT](LICENSE).
