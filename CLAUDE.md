# SANTA

**S**igma-**A**nchored **N**ode **T**est **A**pparatus — a cross-implementation
conformance test suite for Ergo consensus.

> Naming note (true, not fake): it started as a *node* test apparatus (block-level
> vectors), then grew an eval/transition tier for the consensus *libraries*. The
> "Node" in the acronym is a fossil of v1's scope; the name stuck. As acronyms go,
> that's the honest kind.

## OVERRIDES (LOAD FIRST)

**Read and internalize `~/projects/OVERRIDES.md` before anything else.** It contains
mechanical overrides for code quality, edit safety, and context management that apply
across all projects.

## What this is

A set of **language-agnostic conformance test vectors** + thin per-implementation
**runners** + CI, so multiple independent Ergo implementations can be checked against
the same canonical inputs and expected outputs — the way Ethereum's
`execution-specs` (formerly `execution-spec-tests` / EEST) lets geth / besu /
nethermind / reth prove consensus-equivalence.

Implementations under test ("conformers"):

| Conformer | Lang | Kind | Role |
|---|---|---|---|
| `ergots` | TypeScript | library (eval/validation reimpl) | the pure-TS port; the thing most under test |
| `sigma-rust` (fork) | Rust | library (eval/validation) | convenience oracle — **not** canonical (carries un-merged PRs) |
| `ergo-node-rust` | Rust | full node | block-tier conformer |
| JVM reference node | Scala | full node | the canonical oracle / ultimate authority |

## Status

**Greenfield — brainstorming stage. No design committed.** The starting context, the
decisions reached so far, and the open questions live in **`BOOTSTRAP.md` — read it
first.** Per `~/.claude/CLAUDE.md`: discuss before formalizing; don't enter plan mode
without asking; brainstorm in open prose, not multiple-choice.

## Related projects (local)

- `~/projects/ergots` — the mainnet-validation harness that motivated this; its
  ~two-dozen divergence findings (`tools/mainnet-validate/findings/`) are the seed corpus.
- `~/projects/ergo-node-rust` — the Rust node + `addons/indexer`; rides the sigma-rust fork.
- `~/projects/ergo-node-build` — the JVM reference node (v6.0.3), the canonical oracle.
