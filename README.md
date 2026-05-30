# SANTA

**S**igma-**A**nchored **N**ode **T**est **A**pparatus — a cross-implementation
conformance test suite for Ergo consensus.

> **Early, in-the-open development.** The design takes shape from working slices
> rather than a big up-front spec — see **[SPEC.md](SPEC.md)** for the current
> architecture + roadmap, and **[BOOTSTRAP.md](BOOTSTRAP.md)** for the rationale.

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
- **fine-grained eval outputs (cost, reduced sigma-tree) → the JVM reference node**
  (ergo-core / `sigma-state`), the de-facto spec.

Three tiers:

- **Wire tier** — serialization: bytes ⇄ structure (constants, boxes, trees, txs,
  headers). The broadest — every wallet/SDK serializes (ergots, sigma-rust, scorex,
  Fleet, …); and a `boxId` *is* the hash of serialized bytes, so it's squarely consensus.
- **Eval / transition tier** — operation-level (e.g. `decodePoint(bytes) → point`,
  with cost). Run by the consensus *libraries*, no full node required.
- **Block tier** — `block H → valid? / state-root`. Run by full *nodes*.

## Status — early / scaffolding

This is **greenfield**, and things will move and reshape. What works today — **Phase 1**,
the minimal loop closed end-to-end on `decode-point`:

- ✅ **Blesser** — links the canonical `sigma-state` interpreter and emits a committed
  `santa-eval/v1` vector (`vectors/eval/decode-point.json`): typed value, raw JIT cost,
  a recorded `(activatedVersion, ergoTreeVersion)`, and a coarse error-class.
- ✅ **Runner I/O contract + harness + JVM reference runner (Rudolph)** — the runner
  consumes a vector and emits actuals; the harness diffs them against the blessed
  expected and prints a **nice/naughty** verdict (`nice ✓ 6/6` on `decode-point`).
- The loop currently has the JVM consuming its *own* blessing, so it proves the
  mechanics + the contract — *real* conformance begins with the first independent runner.

Not built yet (and where help is most wanted — see below):

- the full eval-tier corpus (only `decode-point` so far) and the **wire tier**;
- **independent runners** — ergots, sigma-rust, the nodes;
- the **block tier**; authored **negative / mutation** vectors (the reject arm); CI.

## Layout

```
SPEC.md         umbrella spec — architecture, tiers, contracts, roadmap, glossary
BOOTSTRAP.md    design rationale + decision log (the *why*)
docs/specs/     per-phase subspecs
vectors/        the canonical vectors — the "nice list" (e.g. vectors/eval/decode-point.json)
jvm-blesser/    Scala: the blesser, the JVM reference runner (Rudolph), and the harness
README.md       this file
```

## Contributing

SANTA is meant to be **community-owned conformance ground**: the more independent
Ergo implementations run the same vectors, the more the vectors are worth. Help is
genuinely wanted — especially:

- **Implementations under test** — wire a runner for your Ergo implementation
  (TypeScript, Rust, Scala, or anything that parses the wire format) against the
  runner I/O contract.
- **Vectors** — more eval-tier operations; wire-tier serialization vectors;
  captured-block vectors for the block tier; authored mutation / reject vectors.
- **Design** — the vector schema, the runner I/O contract, CI topology.

At this stage the contracts aren't frozen, so **a conversation beats a big PR** —
read [SPEC.md](SPEC.md) + [BOOTSTRAP.md](BOOTSTRAP.md), look at `jvm-blesser/`, and
open an issue to talk through where you'd like to plug in.

## Related

- [Ergo](https://ergoplatform.org/) — the protocol under test.
- [`sigma-rust`](https://github.com/ergoplatform/sigma-rust) — Rust consensus
  library; a convenience differential target.
- [`execution-specs`](https://github.com/ethereum/execution-specs) — Ethereum's
  executable spec + test framework (the former `execution-spec-tests` / EEST,
  consolidated here in 2025); the project SANTA's structure is modeled on.

## License

[MIT](LICENSE).
