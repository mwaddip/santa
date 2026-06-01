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

Three tiers:

- **Wire tier** — serialization: bytes ⇄ structure (constants, boxes, trees, txs,
  headers). The broadest — every wallet/SDK serializes (ergots, sigma-rust, scorex,
  Fleet, …); and a `boxId` *is* the hash of serialized bytes, so it's squarely consensus.
- **Eval / transition tier** — operation-level (ErgoTree + context → typed value, with
  cost). Run by the consensus *libraries*, no full node required.
- **Block tier** — `block H → valid? / state-root`. Run by full *nodes*.

## Status

The **eval tier is closed and scaled**, and the conformance loop is already surfacing
genuine cross-implementation divergences — which is exactly its job. What runs today:

- ✅ **A blessed eval corpus — 1,792 entries across 115 vector files**, produced by the
  JVM reference interpreter (`sigma-state`) from its own language specification and
  version-split into **v5** (1,547 entries — the cumulative v5/mainnet method surface)
  and **v6** (245 — the v6 new-feature surface). Each entry is `ErgoTree bytes (+ input)
  → typed value + raw JIT cost`, committed with the `(activated, ergoTree)` version it
  was blessed under.
- ✅ **An independent runner under test — *Dasher*** (the pure-TypeScript `ergots`
  library, in [`ts-runner/`](ts-runner/)). It consumes the committed vectors and is
  checked against the JVM-blessed `expected`. On the v5 surface it scores **1,486 nice ·
  61 divergent** (10 value, 36 cost, 15 not-yet-implemented methods) — the divergences
  are recorded and routed back to `ergots`. (`ergots` consumes `sigma-state` as Rudolph,
  the JVM reference runner, does — the runner is SANTA's; the implementation under test
  is a dependency.)
- ✅ **A frozen runner contract** ([`docs/contract/runner-contract.md`](docs/contract/runner-contract.md))
  + a JVM blesser, the JVM reference runner (*Rudolph*), and a harness. A runner is
  **total**: it emits one faithful outcome for *every* entry — value + cost on success,
  else a coarse tag (`errored` / `not-implemented` / `unrepresentable`) — and never drops
  or hides one. A conformer's **scope is chosen on the input side** (it runs the vector
  subset it claims; ergots runs `v5/`). **A divergence is the deliverable** — surfaced
  and routed, never silenced; a red gate means the suite is working.
- ✅ **Machine-checkable gates** — a JSON-Schema validator over the whole corpus (115/115)
  and an end-to-end conformance gate. The CI seed.

Still greenfield, and where help is most wanted (see below):

- the **wire tier** (serialization round-trips — the broadest surface) and the **block
  tier** (chain-blessed block vectors);
- more **independent runners** — `sigma-rust`, the full nodes;
- the **reject arm** — authored negative / mutation vectors (rejected *for the right
  reason*); and a full CI gate.

## Layout

```
SPEC.md            umbrella spec — architecture, tiers, contracts, roadmap, glossary
BOOTSTRAP.md       design rationale + decision log (the *why*)
docs/contract/     the frozen runner I/O contract
docs/specs/        per-phase subspecs
docs/findings/     recorded cross-implementation divergences
schema/            JSON Schemas for vectors + actuals, and the validator
vectors/eval/      the canonical eval corpus — the "nice list" (v5/ and v6/)
jvm-blesser/       Scala: the blesser, the JVM reference runner (Rudolph), the harness
ts-runner/         Dasher — the ergots runner + the conformance gate
README.md          this file
```

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

## License

[MIT](LICENSE).
