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

A wire runner may also declare (both optional; comet, the Fleet SDK runner, does):

```jsonc
  "kinds":      ["Constant", "Box", "Transaction"],  // the wire kinds it implements; omitted = every kind
  "activation": false                                // applies rules tied to the activated script version? default true
```

- **`version`** — one per runner, the max protocol version it supports; **cumulative** (v6 ⊃ v5 ⊃ v4, soft-fork). The orchestrator selects every vector whose `version ≤` this. The slots: `v4` = every activated version below the JIT activation (0 or 1), `v5` = activated 2, `v6` = activated 3 (`conform`'s `VERSIONS`; `validate` holds each file's entries to its slot).
- **`tiers`** — the set of result-shape tiers the runner implements (`eval` today; `wire`/`block` later). Tiers are *not* cumulative; declaring `eval` never pulls in `wire`. Uneven maturity across tiers is surfaced as naughty (the grid is the readiness map), not hidden by under-declaring.
- **`cost`** — whether the runner claims the **cost dimension**. `false` = value-only (a wallet/eval-only SDK); the orchestrator grades value and ignores cost for it. Declared scope, not abstention.
- **`kinds`** (optional) — the wire kinds the runner implements, from the wire schema's `kind` enum (`Constant`,
  `Box`, `Transaction`, `Header`, `SigmaBoolean`, `ErgoTree`, `BlockTransactions`). A wire file holds one kind
  (`validate` enforces it), and a file of an undeclared kind is not staged for the runner. Omitted = every kind;
  `conform` refuses an unknown kind, an empty list, and `kinds` without the `wire` tier.
- **`activation`** (optional, default `true`) — whether the runner applies consensus rules tied to the activated
  script version. A wire file whose envelope says `activation_rule: true` (runner-contract-wire §1) is not staged
  for a runner that declares `false`.
- **What a scope declaration may say.** `version`, `tiers`, `cost`, `kinds` and `activation` are DECLARED SCOPE,
  not abstention, and they stay coarse: a whole tier, kind or class of rule, never a family, an entry or a failure.
  Each needs a reason the implementation's own API gives (it has no entry point for that kind; it has no notion of
  the activated version), stated in the runner's README. A failure inside an API the implementation does offer
  stays coal: a gap there is a feature to build, not something to declare away. Likewise a kind or rule the
  implementation means to support but has not built yet is no reason: it stays declared, and its
  `not-implemented` is the readiness map (e.g. ergots' missing transaction serializer). Out-of-scope files are not
  staged and not tallied, as a higher version or an undeclared tier; `conform`'s header line, `results.json` and
  the scoreboard show the declared scope.
- **`impl`** — unchanged (`<url>#<ref>` or `null`).
- `conform` refuses a key outside these (a misspelt `kind` / `activations` would silently fall back to a default
  and change the scope) and a `cost` / `activation` / `control` that is not a JSON boolean.
- **`control`** (optional, default `false`) — the oracle's own runner (rudolph). A grid in which a control could not
  build or run is not anchored to the oracle, so `conform` exits non-zero (after writing `results.json`), and the CI
  run fails before it publishes a scoreboard. The same holds for a control that exceeds or crashes on a bounded
  file (§3a). Any other runner that cannot build or run stays a ⚠️ row in the grid.

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
                    "error": null | "errored" | "not-implemented" | "panicked",
                    "note": <string, present iff error == "panicked"> }, … }
```

The orchestrator performs discovery + filtering and stages the runner's selected vectors **flat** into
`<vectors-dir>` (symlinks). A runner therefore still globs `*.json` one level deep — the nested
`vectors/<tier>/<version>/<provenance>/` layout is invisible to it.

**Exit 0** = it ran (actuals written). **Non-zero** = the runner itself failed to run — distinct
from a per-entry `errored`, which is a normal outcome *inside* the actuals (eval contract §3). The
orchestrator **catches** a non-zero exit (build / run / impl-checkout failure): that runner is
reported **⚠️ "could not build/run"** — distinct from a 🪨 divergence, since it was never tested —
and the remaining runners still grade, so one broken conformer can't abort the run.

Resolve `<vectors-dir>`/`<out-dir>` to absolute paths if the entrypoint `cd`s before running.

### The last line and `santa-bound`

Everything a `santa-run` does before it runs the conformer (provisioning, the build, patching the impl checkout
and the `trap` that restores it) stays as it is. The conformer itself runs on the **last line**, and only there,
through `santa-bound` (§3a):

```bash
${SANTA_BOUND:+"$SANTA_BOUND"} <cmd…> "$VECTORS" "$OUT"
```

`./conform` sets `SANTA_BOUND` (and `SANTA_BOUND_STATUS`); the last two arguments are always the vectors dir and
the out dir. Unset (a runner's own self-test, a manual run), the prefix expands to nothing and the line runs as
before. Nothing that builds or patches may run inside it: a bound covers the conformer's work, and a process the
bound kills must never be the one holding a restore `trap`. A runner whose last line skips the prefix is reported
**⚠️** (its bounded files would have run unbounded).

**`<impl-path>`** is the directory Santa checked the runner's `impl` out into; the runner finds its
dependency there **by repo name** — `<impl-path>/sigma-rust`, `<impl-path>/ergots`, etc. (Santa
clones `<url>` into `<impl-path>/<repo-name>`, §2). A runner MUST use `<impl-path>` and MUST NOT
hardcode a shared location — that is what lets two runners pin different refs of the same impl
without colliding. For a `null` impl, `<impl-path>` is `-`. (E.g. a Cargo runner whose path-dep is
`../sigma-rust` can `ln -sfn "$1/sigma-rust" ../sigma-rust` in `santa-run`, then build.)

### Toolchain (self-provisioned, per-runner)

A runner declares its toolchain in `runners/<name>/mise.toml` ([mise](https://mise.jdx.dev) `[tools]`),
and its `santa-run` provisions it — re-exec once under `mise exec` so the tools are on `PATH` for the
whole build subtree (the build often runs elsewhere — `ts-runner/`, `jvm-blesser/`, the cloned impl —
which mise's cwd resolution alone wouldn't reach):

```bash
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -z "${SANTA_MISE_ACTIVE:-}" ]; then
  mise trust "$here/mise.toml" >/dev/null      # a runner vouches for its own co-located config
  mise --cd "$here" install
  exec env SANTA_MISE_ACTIVE=1 mise --cd "$here" exec -- "$0" "$@"
fi
# … tools now on PATH …
```

The orchestrator stays **toolchain-agnostic** — `./conform` never learns a runner's language, it just
invokes `santa-run`. The pin lives **with the runner** (presence-as-state): drop a dir in, it carries
its toolchain; remove it, nothing dangles. Deliberately **per-runner, not a shared union** — two
same-language runners (`blitzen-develop`/`blitzen-eni`) can pin different versions, which one flattened
`[tools]` could not. **Bootstrap deps** (assumed, not provisioned): `mise`, `git`, and a systemd user manager on
cgroup v2 with the memory controller delegated and swap accounted (§3a; `santa-bound` checks it). Everything else
self-provisions — each runner its own toolchain (above), and SANTA's own tooling (the `conform` +
`validate` + `santa-check` rust workspace under `tools/`) via the wrappers' `mise exec` against
`tools/mise.toml`. CI installs mise via `jdx/mise-action`; `mise install` may delegate to a native
manager (rust → rustup, which mise bootstraps) — still root-less and version-pinned.

## 3a. Bounded files

Some inputs make an implementation do unbounded work where the reference does almost none (a write the JVM charges
as it goes and stops at the cost limit; a tree whose parse grows). The reference is the measure: **what it handles
within reasonable bounds, every implementation is expected to handle.** A vector file may therefore declare a
budget at its top level:

```json
"limits": { "memory_mb": 1024, "wall_s": 10 }
```

- **A bounded file holds exactly one entry**, so a budget is a per-row bound. `memory_mb` is MiB, `wall_s` seconds;
  `./validate` holds them to at most **4096 MiB** and **60 s** (`santa_check::limits`). The blessers' default is
  1024 MiB / 10 s.
- **`santa-bound`** (`tools/santa-bound`) runs the last line once per unit, each a temporary `systemd-run --user`
  service, so systemd enforces the bound from outside the process (`MemoryMax`, `MemorySwapMax=0`, `RuntimeMaxSec`,
  `TimeoutStopSec=5`). The files without `limits` are ONE unit under the **safety ceiling (4096 MiB, 600 s)**; each
  bounded file is its own unit under its budget. A unit gets the caller's whole environment and working directory.
  It never runs anything unbounded: before any unit it runs a probe unit that reads its own `memory.max` and
  `memory.swap.max` back (`systemd-run` accepts `MemoryMax=` even where the user manager cannot enforce it), and if
  the bound is not applied, or a unit cannot be created, the runner is reported ⚠️.
- **A bounded file whose process hits its budget grades `exceeded`** (`{"kind": "exceeded", "limit": "memory" |
  "time"}`), coal unconditionally, on that entry alone; the column survives. conform writes that actual from
  santa-bound's status record (`{"error": "exceeded", "limit": …, "budget": {…}}`), whatever file the process left:
  a runner never emits it, and the actuals schemas do not carry it. A bounded file whose process crashes (non-zero
  exit, a signal) grades `panicked` with `note` `process <exit status>`.
- **The control proves every budget, on every run.** If rudolph exceeds or crashes on a bounded file, `./conform`
  fails: the budget or the row is wrong, not the ports.
- **The safety ceiling protects the machine; it does not grade.** An ordinary run that hits it loses that runner's
  column, reported ⚠️ `santa-run exceeded the safety ceiling (memory 4096 MiB)` (or `time 600 s`).
- **A budget is a threshold, not a benchmark.** An implementation that lands near it can grade differently run to
  run, so a bounded row is one whose blow-up clears the budget by orders of magnitude, where an honest
  implementation stays far below it.

## 4. The runner does not compare — the orchestrator does

Per eval contract §6, conformance comparison needs no oracle and must be **pinned identically
across runners**. So the orchestrator grades with **one shared comparator** — the Rust
[`santa-check`](../../tools/santa-check) lib (§5 equality + §6 grading), linked into `conform`
in-process — over *every* runner's actuals. **A runner MUST NOT self-judge for orchestration**
— it only emits actuals; the orchestrator decides nice/coal and categorizes RED
(value / cost / not-implemented / reject / panicked). A standalone self-compare mode is fine
as a *dev convenience*, but the `santa-run` path emits actuals and leaves the verdict to `conform`.

(`santa-check` is the canonical engine, and the one proven against the [verdict-oracle](../../oracle)
(`oracle/*.json`, by `tools/santa-check/tests/oracle.rs`; the nipopow verdicts by unit tests). The per-ecosystem
references (the TypeScript `structuralEqual` + grading in Dasher's `ts-runner/test/`, and the Scala `Harness`)
implement the same §5/§6 but do not read the oracle today, so their agreement is not machine-checked. The
orchestrator runs the full grid over `runners/*` — the N-way is whatever is checked in, never a hardcoded count.)

**Prose travels with the vector and is never graded.** An entry may carry free prose saying why it exists and
what it pins: `script` in the eval tier, an optional `description` in every other tier. A runner ignores it and
no comparator reads it, so editing prose cannot change a verdict. (`conform` copies an eval entry's `script`
into its red-entry detail, for the scoreboard.)

## 5. Deferred (named, not silent)

- **Rendered report** (HTML/SVG) — `./conform` prints a per-slice terminal table; an exported report is not built.
- **Compiled-artifact cache** — a runner's whole workspace `.santa/<name>/` (the cached impl
  checkout + `out/<ver>/` actuals) is self-contained and delete-able; the checkout is reused across
  runs (fetch+checkout), but compiled build outputs aren't cached, so each `./conform` rebuilds.
  `./conform --clean` (or `rm -rf .santa`) resets everything.

*(Done since v1: the `impl` auto-checkout — Santa clones `<url>#<ref>` into the per-runner workspace
and passes `<impl-path>` — see §2/§3.)*

## 6. Worked example — adding a runner

1. Implement `santa-run` (any language) emitting actuals per §3, self-provisioning its toolchain, its last line
   under the `santa-bound` prefix (§3, "The last line and `santa-bound`").
2. Add `runner.json` (§2) and a `mise.toml` (§3 Toolchain) pinning the toolchain.
3. Make `santa-run` executable and place the dir under `runners/` (in-tree or `git submodule add`).
4. `./conform` discovers and runs it; the per-slice table shows its results.

The JVM reference (Rudolph) is canonical (eval contract §6): where a runner
diverges from the blessed `expected`, the runner is wrong — surfaced as RED, routed, never hidden.

## 7. Self-test kit (optional) — SANTA's published-data interface

Everything above is how SANTA *runs* a runner (`./conform` over `runners/*/`). Optionally, a runner may
also **run itself** against SANTA's blessings in its own CI — the way Ethereum clients consume an
`execution-spec-tests` fixture release. This section describes SANTA's side of that interface; how a
runner wires it into CI is the runner's business. **Optional and additive** — a runner stays a
`runners/*/` dir for the in-SANTA grid whether or not it self-tests.

**SANTA publishes a versioned conformance kit** — a GitHub Release tied to a SANTA tag
(`santa-data-v…`) containing: the **blessed corpus** in its `vectors/<tier>/<version>/<provenance>/`
layout (each entry carrying the JVM-blessed `{value, cost, error}`); the **actuals schema**; the
**comparator spec** (the §5 equality + §6 grading of the eval contract, [`runner-contract.md`](runner-contract.md));
a **verdict-oracle** — meta-test vectors `(actual, expected, claims_cost) → expected verdict` that let
an implementation *prove* it grades identically; and a **version stamp** (the SANTA commit it was cut
from, so a 🪨 traces to an exact corpus).

The kit ships **no comparator binary**: the comparison *is* the §5/§6 spec, which a runner implements
natively and proves against the oracle (§6 made executable). A runner's self-test: fetch a pinned kit →
run `santa-run` over the subset its `runner.json` selects (`version ≤`, `tiers ∈`, and its declared `kinds` /
`activation`) → grade with its own
oracle-checked comparator → gate. By construction the verdict equals `./conform`'s.

**Badges are SANTA-minted, never self-reported.** SANTA's CI runs the canonical grid and publishes
per-runner naughty/nice badges + a dashboard; a runner's README *references* the SANTA-hosted endpoint.
A runner's own gate is for its dev loop; the public 🎁/🪨 is SANTA's to award, so it means something.
