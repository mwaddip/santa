# SANTA Runner Integration Contract (operational)

> **What a runner repo implements to be run by the SANTA orchestrator (`./conform`).**
> This is the *operational* layer — discovery + invocation. It sits on top of the frozen
> *eval* contract ([`runner-contract.md`](runner-contract.md)), which governs vector/actuals
> shape, the §5 equality, and §6 (conformance needs no oracle). A runner author needs both:
> this doc for *how it's run*, that doc for *what it emits*.

## 1. A runner is a directory

A runner is a directory `runners/<name>/`. It may be **in-tree** (Rudolph, Dasher today) or a
**git submodule** pulled by recursive checkout (Blitzen, and external runners) — the orchestrator
treats them identically. **Presence-as-state:** a dir is run iff it contains a valid `runner.json`
*and* an executable `santa-run`; anything else is skipped. There is no central registry — add a
configured dir and it runs; remove it and it's gone.

Two dirs may wrap the same upstream runner at different checkouts — e.g. `runners/blitzen-develop/`
and `runners/blitzen-eni/` — to compare an implementation's branches side by side.

## 2. The manifest — `runner.json`

```jsonc
{
  "name":    "blitzen-develop",                 // unique; the dir's identity
  "label":   "blitzen (sigma-rust @ develop)",  // shown in the table
  "version": "v6",                               // single max protocol version; implies all lower (cumulative)
  "tiers":   ["eval"],                           // result-shape tiers it implements (eval/wire/block)
  "cost":    false,                              // claims the cost dimension? (eval-only libs: false)
  "impl":    "https://github.com/ergoplatform/sigma-rust.git#develop"  // <url>#<ref>, or null
}
```

- **`version`** — one per runner, the max protocol version it supports; **cumulative** (v6 ⊃ v5, soft-fork). The orchestrator selects every vector whose `version ≤` this.
- **`tiers`** — the set of result-shape tiers the runner implements (`eval` today; `wire`/`block` later). Tiers are *not* cumulative; declaring `eval` never pulls in `wire`. Uneven maturity across tiers is surfaced as naughty (the grid is the readiness map), not hidden by under-declaring.
- **`cost`** — whether the runner claims the **cost dimension**. `false` = value-only (a wallet/eval-only SDK); the orchestrator grades value and ignores cost for it. Declared scope, not abstention.
- **`impl`** — unchanged (`<url>#<ref>` or `null`).

## 3. The entrypoint — `santa-run`

An executable at `runners/<name>/santa-run`:

```
santa-run <impl-path> <vectors-dir> <out-dir>
```

It MUST: build the runner against the impl Santa checked out under `<impl-path>` (see below;
`<impl-path>` is `-` when `impl` is null), evaluate **every `*.json` vector** in `<vectors-dir>`, and
write **one actuals file per
vector** to `<out-dir>/<same-filename>`. The actuals file is the frozen actuals schema
([`schema/santa-eval.actuals.schema.json`](../../schema/santa-eval.actuals.schema.json)):

```json
{ "<entry-name>": { "value": <SValue|null>, "cost": <number|null>,
                    "error": null | "errored" | "not-implemented" | "unrepresentable" }, … }
```

The orchestrator performs discovery + filtering and stages the runner's selected vectors **flat** into
`<vectors-dir>` (symlinks). A runner therefore still globs `*.json` one level deep — the nested
`vectors/<tier>/<version>/<provenance>/` layout is invisible to it.

**Exit 0** = it ran (actuals written). **Non-zero** = the runner itself failed to run — distinct
from a per-entry `errored`, which is a normal outcome *inside* the actuals (eval contract §3).

Resolve `<vectors-dir>`/`<out-dir>` to absolute paths if the entrypoint `cd`s before running.

**`<impl-path>`** is the directory Santa checked the runner's `impl` out into; the runner finds its
dependency there **by repo name** — `<impl-path>/sigma-rust`, `<impl-path>/ergots`, etc. (Santa
clones `<url>` into `<impl-path>/<repo-name>`, §2). A runner MUST use `<impl-path>` and MUST NOT
hardcode a shared location — that is what lets two runners pin different refs of the same impl
without colliding. For a `null` impl, `<impl-path>` is `-`. (E.g. a Cargo runner whose path-dep is
`../sigma-rust` can `ln -sfn "$1/sigma-rust" ../sigma-rust` in `santa-run`, then build.)

## 4. The runner does not compare — the orchestrator does

Per eval contract §6, conformance comparison needs no oracle and must be **pinned identically
across runners**. So the orchestrator runs **one shared comparator** ([`tools/compare.py`](../../tools/compare.py),
the §5 algorithm) over *every* runner's actuals. **A runner MUST NOT self-judge for orchestration**
— it only emits actuals; the orchestrator decides nice/coal and categorizes RED
(value / cost / not-implemented / unrepresentable / reject). A standalone self-compare mode is fine
as a *dev convenience*, but the `santa-run` path emits actuals and leaves the verdict to `conform`.

(`tools/compare.py` is a third comparator alongside the TypeScript one (Dasher) and the Scala
`Harness`; §5/§6 require all three to return the same verdict on the same bytes, so it doubles as a
cross-implementation check on "equal". The first orchestrator run demonstrated it: Dasher scored
1632/1705 · RED 73 under `compare.py`, matching its TS-comparator e2e pins exactly.)

## 5. Deferred (named, not silent)

- **Rendered report** (HTML/SVG) — `./conform` prints a terminal table and an optional `--matrix`
  op×runner ✓/✗ grid; an exported report is not built.
- **Compiled-artifact cache** — a runner's whole workspace `.santa/<name>/` (the cached impl
  checkout + `out/<ver>/` actuals) is self-contained and delete-able; the checkout is reused across
  runs (fetch+checkout), but compiled build outputs aren't cached, so each `./conform` rebuilds.
  `./conform --clean` (or `rm -rf .santa`) resets everything.

*(Done since v1: the `impl` auto-checkout — Santa clones `<url>#<ref>` into the per-runner workspace
and passes `<impl-path>` — see §2/§3.)*

## 6. Worked example — adding a runner

1. Implement `santa-run` (any language) emitting actuals per §3.
2. Add `runner.json` (§2) declaring `version`, `tiers`, `cost`, and `impl`.
3. Make `santa-run` executable and place the dir under `runners/` (in-tree or `git submodule add`).
4. `./conform` discovers and runs it; `./conform --matrix` shows it in the ✓/✗ grid.

The JVM reference (Rudolph) is canonical (eval contract §6 / BOOTSTRAP decision 1): where a runner
diverges from the blessed `expected`, the runner is wrong — surfaced as RED, routed, never hidden.
