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

```bash
cd ts-runner
npm install                 # resolves the @ergots file: deps (see below)
npm test                    # vitest: bridge unit tests + round-trip + schema oracle + e2e
npm run build               # tsup → dist/ (incl. the `runner` bin)
node dist/runner.js ../vectors/eval/decode-point.json   # actuals JSON → stdout; scope notes → stderr
node dist/runner.js <vector.json> <actuals-out.json>    # …or write actuals to a file
```

## Dependency (provisional, one-line swap)

`@ergots/ergoscript` (+ `@ergots/scorex` for the wire I/O primitives) are consumed via **`file:`
paths** to `~/projects/ergots/packages/{ergoscript,scorex}` (their `dist/` is built locally).
Runner code imports **by package name only**; all provisionality lives in `package.json`. When
ergots publishes to npm, each `file:` becomes a pinned version — a one-line change per dep.

## Pipeline (per entry)

`parseTree(tree_bytes_hex)` → decode SANTA input JSON → ergots `SValue` + its `SType` (bind at
context **var 1**; pass `tree.constants` for `ConstPlaceholder`) → `evaluateWith(tree, ctx)` at
`treeVersion = version.ergoTree` (read `ctx.jitCost`; catch `EvalError`) → encode `SValue` →
canonical SANTA JSON → capture `{value, cost, error}`. The **encode/decode bridge** (`src/encode.ts`,
`src/decode.ts`, `src/stype.ts`) is the substance; `ergots` `parseSValue`/`serializeSValue` are
the wire codec for the bytes-kinds (Box/Header/SigmaProp), not the JSON bridge.

## Scope & the five per-entry outcomes

Dasher declares **v5** scope. Each entry yields exactly one of:

| outcome | in actuals? | meaning |
|---|---|---|
| **nice / naughty** | yes | evaluated; structural-match vs blessed `expected` (value AND cost exact) |
| **errored** | yes (`{null,null,errored}`) | ergots implements the op and eval threw |
| **abstain · v6 UnsignedBigInt** | no | out of v5 scope (pre-eval) — clean, neither nice nor naughty |
| **abstain · not-implemented** | no | op not on mainnet ⇒ a v6 feature ergots hasn't built — clean abstain (flips to covered when ergots gains v6) |
| **divergence · repr** | no | a v5 input ergots can't represent (Header ts > 2⁵³) — a real ergots bug; route upstream |

A **cross-impl divergence is a finding, never silenced** — investigate (Dasher bug? real ergots
bug?), then record under `docs/findings/` and route to ergots. Never edit the ergots repo from a
SANTA session.

## Current conformance result (2026-05-31, ergots v5 build)

`npm test` runs the whole committed corpus (33 files / 245 entries):

```
covered 12  = nice 12  (cost bug RESOLVED 2026-06-01; was 6 nice + 6 cost-divergent v2)
abstain 231 = v6 UnsignedBigInt 34 + not-implemented/v6 197
divergences → ergots: 2 repr (Header ts cap)   — the 6 AddToEnvironment cost divergences are FIXED
```

The e2e gate asserts Dasher is correct on every value and **pins** the 2 remaining repr divergences (the 6 AddToEnvironment cost divergences are fixed)
as the two known ergots bugs (see the findings doc) — so a *new* divergence fails the gate.
