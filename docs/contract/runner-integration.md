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
  "name":  "blitzen-develop",                 // unique; the dir's identity
  "label": "blitzen (sigma-rust @ develop)",  // shown in the table
  "scope": ["v5", "v6"],                       // which vectors/eval/<ver>/ dirs it claims
  "impl":  { "path": "../sigma-rust", "ref": "develop" }   // or null
}
```

- **`scope`** is the **input-side selection** (eval contract §3): the version dirs this runner
  claims. A v5-only library declares `["v5"]`; a full implementation `["v5","v6"]`. The orchestrator
  runs the runner once per scoped version and never feeds it a vector outside its scope.
- **`impl`** is the implementation-under-test dependency, or `null` if the runner is self-contained
  (Rudolph builds sigma-state via sbt; Dasher resolves ergots via an in-repo dependency). When
  present it is a **sibling working-copy + ref**: `path` is a checkout (e.g. a Cargo/path or npm
  sibling), `ref` the branch/commit it should be built against. This matches how Rust runners pin
  sigma-rust (a Cargo `path` dependency on a sibling checkout), so develop-vs-eni is *which branch
  that checkout is on* — there is no central lockfile. **Auto-checkout of `ref` is deferred** (see §5).

## 3. The entrypoint — `santa-run`

An executable at `runners/<name>/santa-run`:

```
santa-run <impl-path> <vectors-dir> <out-dir>
```

It MUST: build the runner against `<impl-path>` (the checked-out impl source; `-` when `impl` is
null), evaluate **every `*.json` vector** in `<vectors-dir>`, and write **one actuals file per
vector** to `<out-dir>/<same-filename>`. The actuals file is the frozen actuals schema
([`schema/santa-eval.actuals.schema.json`](../../schema/santa-eval.actuals.schema.json)):

```json
{ "<entry-name>": { "value": <SValue|null>, "cost": <number|null>,
                    "error": null | "errored" | "not-implemented" | "unrepresentable" }, … }
```

**Exit 0** = it ran (actuals written). **Non-zero** = the runner itself failed to run — distinct
from a per-entry `errored`, which is a normal outcome *inside* the actuals (eval contract §3).

Resolve `<vectors-dir>`/`<out-dir>` to absolute paths if the entrypoint `cd`s before running.

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

- **Auto-checkout of `impl.ref`** — when a runner declares `impl: {path, ref}`, the orchestrator
  will (in a later iteration, once a runner needs it) ensure `path` is on `ref` before invoking
  `santa-run`, and pass the resolved path as `<impl-path>`. v1 passes `-` and runners build against
  whatever their sibling checkout is currently on.
- **Per-instance build cache**, so two checkouts of the same impl don't clobber each other's build.
- **Rendered report** (HTML/SVG) — v1 prints a terminal table (`./conform`) and an optional
  `--matrix` op×runner ✓/✗ grid.

## 6. Worked example — adding a runner

1. Implement `santa-run` (any language) emitting actuals per §3.
2. Add `runner.json` (§2) declaring `scope` + `impl`.
3. Make `santa-run` executable and place the dir under `runners/` (in-tree or `git submodule add`).
4. `./conform` discovers and runs it; `./conform --matrix` shows it in the ✓/✗ grid.

The JVM reference (Rudolph) is canonical (eval contract §6 / BOOTSTRAP decision 1): where a runner
diverges from the blessed `expected`, the runner is wrong — surfaced as RED, routed, never hidden.
