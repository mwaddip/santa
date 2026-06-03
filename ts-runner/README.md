# Dasher — the ergots eval-tier runner

**Dasher** is SANTA's runner for the **ergots** TypeScript implementation: it consumes the
committed `santa-eval` vectors through `@ergots/ergoscript` and emits actuals
(`{value, cost, error}` per entry) in the frozen canonical encoding, to be compared against the
JVM-blessed `expected`. It is the **first independent conformer** (the JVM runner *Rudolph*
judges itself; Dasher is a genuine cross-implementation check) and doubles as the **differential
instrument for ergots' v5 audit** — run the corpus before/after each ergots change; any moved
value or cost is caught.

Contract: [`docs/contract/runner-contract.md`](../docs/contract/runner-contract.md) +
[`schema/`](../schema/). Findings:
[`docs/findings/eval-jvm-vs-ergots.md`](../docs/findings/eval-jvm-vs-ergots.md).

## Build / run / test

**Canonical entry (repo root):** `./conform` fetches the pinned ergots `impl`, builds it, wires
`ts-runner/ergots-impl`, builds Dasher, and grades the 4-way (see *Dependency*). Direct ts-runner
use needs `ergots-impl` present first — `./conform` creates it, or point it at a local ergots tree.

```bash
cd ts-runner
npm install                 # resolves the @ergots file: deps (see below)
npm test                    # vitest: bridge unit tests + round-trip + schema oracle + e2e
npm run build               # tsup → dist/ (incl. the `runner` bin)
node dist/runner.js ../vectors/eval/v5/spec/Long.toBigInt_method.json   # actuals → stdout; scope notes → stderr
node dist/runner.js <vector.json> <actuals-out.json>    # …or write actuals to a file
```

## Dependency (SANTA-fetched impl)

`@ergots/ergoscript` (+ `@ergots/scorex` for the wire I/O primitives) are the
implementation-under-test. SANTA owns the checkout: `runners/dasher/runner.json` pins
`impl: "https://github.com/mwaddip/ergots.git#ergoscript-v6"`, and `./conform` clones it into
`.santa/dasher/ergots`. `runners/dasher/santa-run` builds ergots' `dist/` (npm workspaces) and
symlinks that checkout to `ts-runner/ergots-impl` (gitignored), which the `@ergots/*` `file:`
deps resolve against — so a clean recursive clone runs Dasher with no `~/projects/ergots`
sibling. Runner code imports **by package name only**. (To develop against a local ergots,
point `ergots-impl` at your working tree, or temporarily repoint the `file:` deps.)

## Pipeline (per entry)

`parseTree(tree_bytes_hex)` → decode SANTA input JSON → ergots `SValue` + its `SType` (bind at
context **var 1**; pass `tree.constants` for `ConstPlaceholder`) → `evaluateWith(tree, ctx)` at
`treeVersion = version.ergoTree` (read `ctx.jitCost`; catch `EvalError`) → encode `SValue` →
canonical SANTA JSON → capture `{value, cost, error}`. The **encode/decode bridge** (`src/encode.ts`,
`src/decode.ts`, `src/stype.ts`) is the substance; `ergots` `parseSValue`/`serializeSValue` are
the wire codec for the bytes-kinds (Box/Header/SigmaProp), not the JSON bridge.

## Scope & the four per-entry outcomes

ergots is a **v5/mainnet** library, so Dasher's gate runs the **v5 input bucket**
(`vectors/eval/v5/`); v6 vectors are the JVM's column and ergots' future. The runner is a
total function — it emits exactly one outcome for **every** entry, never omitting (there is
no "abstain"; scope is the input subset you run):

| outcome (`error`) | meaning |
|---|---|
| **success** (`null`) | evaluated; `value`+`cost` present; structural-match vs blessed `expected` decides nice/naughty |
| **errored** (`"errored"`) | ergots implements the op and eval threw (coarse — no reason taxonomy) |
| **not-implemented** (`"not-implemented"`) | ergots has no impl for this op/method/type — a coverage gap; route to ergots |
| **unrepresentable** (`"unrepresentable"`) | ergots has the type but can't hold this value (e.g. Header ts > 2⁵³) — a repr bug; route to ergots |
| **panicked** (`"panicked"`) | an otherwise-uncaught throw on this entry, caught so the run continues — always coal, message in `note`; a runner must never abort the file |

A **cross-impl divergence is a finding, never silenced** — every RED is the runner doing its
job. Record under `docs/findings/` and route to ergots. Never edit the ergots repo from a
SANTA session.

## Current conformance result (v5 gate)

`npm test` runs the whole committed **v5** corpus (`vectors/eval/v5/`) and compares each
entry's actual against the blessed `expected`:

```
nice 1705 · RED 0 — every entry evaluated; no abstain; fully green on v5
(was 1632/73, then 1678/27) — ergots fixed every routed value/cost divergence AND the 27
not-implemented methods (Coll.updated / Coll.updateMany / GroupElement.negate, 35eac6b);
SANTA also re-blessed box inputs to >= protocol-min. The pins are now a full-green guard.
```

The gate pins each known-RED count, so a *new* divergence (count ↑) or an ergots regression
(count ↑ from 0) fails the gate and must be investigated — RED is recorded and tracked, never
hidden. (Update these numbers if the corpus or ergots changes them.)
