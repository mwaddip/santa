# SANTA

**S**igma-**A**nchored **N**ode **T**est **A**pparatus — a cross-implementation
conformance test suite for Ergo consensus.

> **Early, in-the-open development.** The design is taking shape from a working
> first slice rather than a big up-front spec — see **[BOOTSTRAP.md](BOOTSTRAP.md)**
> for the rationale and the decisions so far.

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

Two tiers, mirroring EEST's `state_test` vs `blockchain_test`:

- **Eval / transition tier** — operation-level (e.g. `decodePoint(bytes) → point`,
  with cost). Run by the consensus *libraries*, no full node required.
- **Block tier** — `block H → valid? / state-root`. Run by full *nodes*.

## Status — early / scaffolding

This is **greenfield**, and things will move and reshape. What works today:

- ✅ A standalone **JVM blesser** (`jvm-blesser/`) — a Scala harness linking the
  canonical `sigma-state` interpreter that produces blessed `(value, cost)` for
  eval-tier vectors. It reproduces the `decode-point` eval corpus **exactly**
  (value + JIT cost + accept/reject) against the reference interpreter, validating
  the "anchor fine values to the JVM" approach end-to-end.
- ✅ The **eval-tier vector format** is emerging from contact with the real
  interpreter: typed values, raw JIT cost, a recorded `(activatedVersion,
  ergoTreeVersion)`, and a coarse error-class.

Not built yet (and where help is most wanted — see below):

- the full eval-tier corpus (only `decode-point` is wired end-to-end so far);
- the per-language **runner contract** and runners for each conformer;
- the **block tier**;
- authored **negative / mutation** vectors (the reject arm);
- CI, repo-layout conventions, a published vector schema.

## Layout

```
jvm-blesser/    standalone sbt project — the JVM eval-tier blesser (the first working slice)
BOOTSTRAP.md    design rationale, decisions reached, open questions
README.md       this file
```

## Contributing

SANTA is meant to be **community-owned conformance ground**: the more independent
Ergo implementations run the same vectors, the more the vectors are worth. Help is
genuinely wanted — especially:

- **Implementations under test** — wire a runner for your Ergo implementation
  (TypeScript, Rust, Scala, or anything that parses the wire format).
- **Vectors** — more eval-tier operations; captured-block vectors for the block
  tier; authored mutation / reject vectors.
- **Design** — the vector schema, the runner I/O contract, CI topology.

At this stage the contracts aren't frozen, so **a conversation beats a big PR** —
read [BOOTSTRAP.md](BOOTSTRAP.md), look at `jvm-blesser/`, and open an issue to talk
through where you'd like to plug in.

## Related

- [Ergo](https://ergoplatform.org/) — the protocol under test.
- [`sigma-rust`](https://github.com/ergoplatform/sigma-rust) — Rust consensus
  library; a convenience differential target.
- [`execution-specs`](https://github.com/ethereum/execution-specs) — Ethereum's
  executable spec + test framework (the former `execution-spec-tests` / EEST,
  consolidated here in 2025); the project SANTA's structure is modeled on.

## License

[MIT](LICENSE).
