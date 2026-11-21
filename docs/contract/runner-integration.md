# SANTA Runner Integration Contract (operational)

> **What a runner implements to grade an implementation against SANTA, and how SANTA runs it.** A runner selects the
> vectors its scope covers from a SANTA checkout, runs them through the implementation, grades every answer by the
> grading rules, bounds the files that carry a budget, and writes its results. It needs only its own language's
> toolchain, `git`, and the SANTA checkout as data. SANTA's `./conform` runs every board entry's runner, re-grades its
> answers with SANTA's own grader as a canary, and consolidates the board. This document sits on top of the eval
> contract ([`runner-contract.md`](runner-contract.md)), the tier contracts and the grading rules
> ([`grading.md`](grading.md)), which govern the vector and actual shapes and the verdicts.

## 1. A runner is a directory

A runner is a directory `runners/<name>/`. It may be **in-tree** (rudolph and dasher today) or a **git submodule** pulled by
recursive checkout (the SANTA-maintained runners of other implementations) — the orchestrator treats them
identically. **Presence-as-state:** a dir is run iff it contains a valid `runner.json` *and* either an executable
`santa-run` or a `runner` key (below); a dir whose `runner.json` has neither is skipped. There is no central registry
— add a configured dir and it runs; remove it and it's gone.

A runner may also live **in the implementation's own repo**: a directory there (`santa/` by convention) holding
the `santa-run` and a `runner.json` with the runner's scope (§2). It is the implementation's: its pull request check
runs it with no entry in SANTA at all (§6a). SANTA provides only data: the vectors, the grading rules
(`vectors/grading.json`), the oracles (`oracle/`) and the schemas (`schema/`). Every checkout of the implementation
brings its own runner, so an API change and the runner change it needs land in one commit, and a grade at any commit
uses that commit's runner.

SANTA's board grades **implementations**, one entry per line it tracks (an implementation's branch), never a pull
request's. An entry is either a runner SANTA maintains (a dir with its own `santa-run`, in-tree or a submodule) or,
for an implementation that hosts its runner, only `runners/<name>/runner.json` whose `runner` key names that
directory in the implementation's checkout (§2). Such an entry has no `santa-run` of its own (`conform` refuses a
dir with both: two runners would claim it).

Two dirs may wrap the same upstream runner at different checkouts to compare an implementation's branches side by
side.

## 2. The manifest — `runner.json`

```jsonc
{
  "name":    "blitzen-develop",                 // unique; the dir's identity
  "label":   "blitzen (sigma-rust @ develop)",  // shown in the table
  "version": "v6",                               // single max protocol version; implies all lower (cumulative)
  "tiers":   ["eval"],                           // the tiers it implements
  "cost":    false,                              // claims the cost dimension? (eval-only libs: false)
  "impl":    "https://github.com/ergoplatform/sigma-rust.git#develop"  // <url>#<ref>, or null
}
```

A wire runner may also declare (both optional; comet, the Fleet SDK runner, does):

```jsonc
  "kinds":      ["Constant", "Box", "Transaction"],  // the wire kinds it implements; omitted = every kind
  "activation": false                                // applies rules tied to the activated script version? default true
```

- **`version`** — one per runner, the max protocol version it supports; **cumulative** (v6 ⊃ v5 ⊃ v4, soft-fork).
  It selects every vector whose version slot is at or below it (§4.1). The slots: `v4` = every activated version
  below the JIT activation (0 or 1), `v5` = activated 2, `v6` = activated 3 (`conform`'s `VERSIONS`; `validate` holds
  each file's entries to its slot).
- **`tiers`** — the tiers the runner implements, of `eval`, `wire`, `transaction`, `block`, `chain`, `authds`,
  `nipopow`. Tiers are *not* cumulative; declaring `eval` never pulls in `wire`. Uneven maturity across tiers is
  surfaced as naughty (the grid is the readiness map), not hidden by under-declaring.
- **`cost`** (default `true`) — whether the runner claims the **cost dimension**. `false` = value-only (a wallet/eval-only SDK); the
  cost dimension is not graded for it. Declared scope, not abstention.
- **`kinds`** (optional) — the wire kinds the runner implements, from the wire schema's `kind` enum (`Constant`,
  `Box`, `Transaction`, `Header`, `SigmaBoolean`, `ErgoTree`, `BlockTransactions`). A wire file holds one kind
  (`validate` enforces it), and a file of an undeclared kind is not selected for the runner. Omitted = every kind;
  `conform` refuses an unknown kind, an empty list, and `kinds` without the `wire` tier.
- **`activation`** (optional, default `true`) — whether the runner applies consensus rules tied to the activated
  script version. A wire file whose envelope says `activation_rule: true` (runner-contract-wire §1) is not selected
  for a runner that declares `false`.
- **What a scope declaration may say.** `version`, `tiers`, `cost`, `kinds` and `activation` are DECLARED SCOPE,
  not abstention, and they stay coarse: a whole tier, kind or class of rule, never a family, an entry or a failure.
  Each needs a reason the implementation's own API gives (it has no entry point for that kind; it has no notion of
  the activated version), stated in the runner's README. A failure inside an API the implementation does offer
  stays coal: a gap there is a feature to build, not something to declare away. Likewise a kind or rule the
  implementation means to support but has not built yet is no reason: it stays declared, and its
  `not-implemented` is the readiness map (e.g. ergots' missing transaction serializer). Out-of-scope files are not
  selected and not tallied, as a higher version or an undeclared tier; `conform`'s header line, `.santa/results.json`
  and the scoreboard show the declared scope.
- **`impl`** — unchanged (`<url>#<ref>` or `null`).
- `conform` refuses a key outside these and `runner` (a misspelt `kind` / `activations` would silently fall back to
  a default and change the scope) and a `cost` / `activation` / `control` that is not a JSON boolean.
- **`control`** (optional, default `false`) — the oracle's own runner (rudolph). A grid in which a control could not
  build or run is not anchored to the oracle, so `conform` exits non-zero (after writing `.santa/results.json`), and the CI
  run fails before it publishes a scoreboard. The same holds for a control that exceeds or crashes on a bounded
  file (§3a). Any other runner that cannot build or run stays a ⚠️ row in the grid.

### A runner the implementation hosts — `runner`

```jsonc
// SANTA, runners/dasher/runner.json: who the runner is, and where
{
  "name":   "dasher",
  "label":  "dasher (ergots · pure-TS)",
  "impl":   "https://github.com/mwaddip/ergots.git#master",
  "runner": "santa/runner"   // a directory in the impl's checkout: plain names only (no root, `..` or `.`)
}
// the implementation, santa/runner/runner.json: what it implements
{ "version": "v6", "tiers": ["eval", "wire", "transaction", "authds"], "cost": true }
```

- **Each side sets only its own keys.** SANTA's entry holds `name`, `label`, `impl`, `control` and `runner`; the
  implementation's `runner.json` holds the scope keys (`version`, `tiers`, `cost`, `kinds`, `activation`) and
  nothing else. `conform` refuses a scope key beside `runner` (it would grade the implementation at SANTA's claim
  instead of its own), and any other key in the implementation's file (it cannot name itself, point elsewhere, or
  make itself the control). `runner` needs an `impl`.
- **The scope is the checked-out commit's.** `conform` checks the impl out first, then reads the implementation's
  `runner.json`, so a commit that mounts a tier is graded on it. The rules for what a scope declaration may say
  (above) hold unchanged; the reasons go in the runner's README, beside it in the implementation.
- **When it cannot run.** A missing or refused `runner.json`, or no executable `santa-run` beside it, is a ⚠️ row
  ("the implementation's runner: …") whose scope `.santa/results.json` records as `null`: nothing says which slices it
  would have graded, so the scoreboard marks every slice of it UNGRADED. A `santa-run` that fails after the scope
  was read keeps that scope, as any other runner's does.
- **The implementation's file is all its pull request check needs.** SANTA's entry is the board's (a name, a label,
  the line it tracks); the check runs the directory directly (§6a `--hosted`), naming it after the repo.
- **The runner is the implementation's to shape.** It may carry any commands or features of its own (a wrapper, a
  watch mode); only the interface SANTA uses is fixed: `santa-run` (§3), `runner.json` (§2) and the outputs (§4.3).

## 3. The entrypoint — `santa-run`

An executable at `runners/<name>/santa-run`, or, for a runner the implementation hosts, at
`<checkout>/<runner>/santa-run` (§2). The calling convention is the same for both:

```
santa-run <impl-path> <santa-dir> <out-dir>
```

- **`<impl-path>`**: the directory holding the implementation's checkout (below); `-` when `impl` is null.
- **`<santa-dir>`**: a SANTA checkout, read as data only (`vectors/`, `oracle/`, `schema/`); a runner never writes to it.
- **`<out-dir>`**: where it writes its outputs (§4.3).

Resolve the paths to absolute ones if the entrypoint `cd`s before it uses them. In order, `santa-run` MUST:

1. check its grading interpreter against the verdict oracle (§4.2) and its selection against the selection oracle
   (§4.1), both in `<santa-dir>/oracle/`, and refuse to grade — exit non-zero, naming the case — if either is not
   reproduced;
2. select from `<santa-dir>/vectors/` exactly the files its scope selects (§4.1);
3. run every entry of every selected file through the implementation, each bounded file under its budget (§3a);
4. grade every answer (§4.2);
5. write `results.json` and the raw actuals (§4.3).

**Exit 0** = it ran, whatever the reds. **Non-zero** = it could not run: a build failure, an oracle not reproduced, a
`<santa-dir>` it cannot use. That is distinct from a per-entry `errored`, a normal outcome *inside* the actuals (eval
contract §3). The orchestrator catches a non-zero exit: that runner is reported **⚠️ "could not build/run"** —
distinct from a 🪨 divergence, since it was never tested — and the remaining runners still grade, so one broken
conformer can't abort the run. A human summary on stdout (one line per slice, as `./conform` prints) is recommended,
not contracted.

**Where `<santa-dir>` comes from.** `./conform` passes its own checkout. Standalone (a maintainer's machine, a pull
request check), obtaining one is the runner's business; the recommended default is a shallow clone of
`https://github.com/mwaddip/santa.git` at `main`, refreshed on each run unless a ref is pinned. **Given a
`<santa-dir>`, a runner grades exactly that checkout and never clones its own**, so SANTA's canary re-grades against
the same expectations the runner used (§4.4).

**The actuals.** Every tier has its own actual shape, `schema/santa-<tier>.actuals.schema.json`; the eval tier's
([`schema/santa-eval.actuals.schema.json`](../../schema/santa-eval.actuals.schema.json)) is:

```json
{ "<entry-name>": { "value": <SValue|null>, "cost": <number|null>,
                    "error": null | "errored" | "not-implemented" | "panicked",
                    "note": <string, present iff error == "panicked"> }, … }
```

Two forms are **tier-neutral**, admitted by every tier's actuals schema beside the tier's own result, and closed
(nothing else in the object):

- `{"error": "not-implemented"}` — every entry of a vector whose schema the runner was not written for (below);
- `{"error": "exceeded", "limit": "memory" | "time"}` — the entry of a bounded file whose process went over its
  budget (§3a).

**A schema the runner was not written for is `not-implemented`.** A runner dispatches on a vector's `schema` by
exact name and version, and a vector whose schema it does not know (a newer version of one it handles, or a tier it
does not mount) gets `not-implemented` for every entry, as the tier-neutral actual `{"error": "not-implemented"}`
and nothing else: the runner cannot know the actual shape of a schema it was not written for (any other outcome
keeps the tier's own shape). It never reads such a vector as a schema it knows: a new envelope read as an old one
fills in missing fields with defaults and shows as false divergences (it happened with the eval envelopes v3, v4 and
v5). So a schema SANTA adds reaches every implementation as `not-implemented` until its runner learns it, which
matters most for a runner the implementation hosts, whose code SANTA cannot change.

**`<impl-path>`** is the directory Santa checked the runner's `impl` out into; the runner finds its
dependency there **by repo name** — `<impl-path>/sigma-rust`, `<impl-path>/ergots`, etc. (Santa
clones `<url>` into `<impl-path>/<repo-name>`, §2). A runner MUST use `<impl-path>` and MUST NOT
hardcode a shared location — that is what lets two runners pin different refs of the same impl
without colliding. For a `null` impl, `<impl-path>` is `-`. (E.g. a Cargo runner whose path-dep is
`../sigma-rust` can `ln -sfn "$1/sigma-rust" ../sigma-rust` in `santa-run`, then build.)

**A runner the implementation hosts finds its checkout from its own location instead:** the checkout its directory
sits in, resolved by real path (`pwd -P`; for `santa/` at the root, the parent of its directory). `conform` invokes
it inside the very tree it grades, at that tree's real path, whether a cached clone or a working tree graded in place
(§6a). There `<impl-path>/<repo-name>` may be a link to the tree, and a build through the link records the same
sources under two paths (Cargo's target directory did). It still receives `<impl-path>`, and may ignore it. It never
modifies the checkout, which may be a developer's working tree.

### Toolchain (self-provisioned, per-runner)

A runner declares its toolchain in `runners/<name>/mise.toml` ([mise](https://mise.jdx.dev) `[tools]`),
and its `santa-run` provisions it — re-exec once under `mise exec` so the tools are on `PATH` for the
whole build subtree (the build often runs elsewhere — `jvm-blesser/`, the cloned impl — which mise's cwd
resolution alone wouldn't reach):

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
same-language runners can pin different versions, which one flattened `[tools]` could not. **Bootstrap
deps** (assumed, not provisioned): `mise` and `git`. Everything else self-provisions — each runner its own
toolchain (above), and SANTA's own tooling (the `conform` + `validate` + `santa-check` rust workspace under
`tools/`) via the wrappers' `mise exec` against `tools/mise.toml`. CI installs mise via `jdx/mise-action`;
`mise install` may delegate to a native manager (rust → rustup, which mise bootstraps) — still root-less and
version-pinned.

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
- **The runner bounds it, in its own language**, in a **child process it spawns for that one entry, in a process
  group of its own** (`setpgid`; never a new session: conform ends a runner by its session), carrying the budget:
  - **Time:** the parent kills the child's group at `wall_s` seconds → `{"error": "exceeded", "limit": "time"}`.
  - **Memory:** the parent watches the group's resident memory — resident set plus swap (`VmRSS` + `VmSwap` in
    `/proc/<pid>/status`), summed over the group's processes, and each process's peak (`VmHWM`) — with 10 ms between
    polls (the next starts when the last ends, so a slow scan of `/proc` never piles polls up), and kills the group
    when either passes `memory_mb` MiB → `{"error": "exceeded", "limit": "memory"}`. A child that
    finishes reports its own peak resident size (its `VmHWM`, read at its end: after `exec` it counts only the
    child's own memory), and a peak past the budget is exceeded too: a spike between two polls counts, as a cgroup
    bound would have killed it at that moment. The parent never uses the child's `ru_maxrss` (from `wait4`): on Linux
    a forked child's carries the parent's resident memory at the fork, and a `posix_spawn`ed child's the parent's
    lifetime peak. The kill is the parent's own observation, so the
    implementation cannot catch it and no runtime's error needs recognising, and it bounds memory actually used.
    A JVM is told its budget, `-XX:MaxRAM=<memory_mb>m`, so that it sizes its heap to the budget, not to the machine.
  - **The backstop:** the child also runs under the kernel's data-segment limit at `max(4096, 2 × memory_mb)` MiB, set
    before it executes: `setrlimit(RLIMIT_DATA)` in bytes (Rust's `pre_exec`), a shell's `ulimit -d` in KiB, or
    `prlimit --data` in bytes. It bounds a burst between two polls. A child that dies of an allocation the backstop
    refused is `exceeded` memory too, recognised by its runtime's out-of-memory signature: Rust aborts (SIGABRT) with
    `memory allocation of N bytes failed`; Node aborts with `heap out of memory` / `Allocation failed`; a JVM ends with
    `OutOfMemoryError` (exit 3 under `-XX:+ExitOnOutOfMemoryError`) or natively (`os::commit_memory … failed`, or
    `There is insufficient memory for the Java Runtime Environment to continue`; exit 1). An allocation the backstop
    refuses can be caught by the implementation, as an overcommit refusal could be under a cgroup bound; it then
    grades as the implementation's answer.
  - **Any other crash** of the child (a non-zero exit, a signal that is not the budget) is `panicked`, its `note` the
    exit status (`process exit status 101`, `process killed by signal 11`). A runner that starts the child through a
    shell (a JVM or Node runner has no `setpgid`: `bash -c 'set -m; ulimit -d …; "$@" & …'` gives the child its own
    group) sees exit status 128 + N and death by signal N alike, and its note names both (`process exit status 139
    (killed by signal 11?)`); the note is for people, the outcome is `panicked` either way.
  - **An interrupted runner** (SIGINT, SIGTERM, SIGHUP while a bounded child runs) kills the child's group, then dies
    of the same signal (a JVM, from a shutdown hook, exits 128 + the signal). The child sits in a group of its own, so
    a terminal's Ctrl-C never reaches it: left alone, it would run on with no time limit. conform's session kill
    covers a run under conform; this covers a maintainer's own run (§7).
  - **Elsewhere:** a runner that cannot watch a bounded file's memory refuses to run it (exits non-zero, naming the
    file), as SANTA's own runners do off Linux (no `/proc`); a macOS runner may instead poll the child's resident size
    (`proc_pidinfo`, `ps -o rss=`) best-effort and say so in its README (macOS ignores the backstop). Windows is out of
    scope.
- **The control proves every budget, on every run.** If rudolph exceeds or crashes on a bounded file, `./conform`
  fails: the budget or the row is wrong, not the ports.
- **A budget is a threshold, not a benchmark.** An implementation that lands near it can grade differently run to
  run, so a bounded row is one whose blow-up clears the budget by orders of magnitude, where an honest
  implementation stays far below it; the mechanism's own overhead (a JVM's footprint) must not flip a verdict.
  Measured with the watchdog (2026-11-20): every bounded file grades as under the previous cgroup bound, for every
  runner that mounts one; rudolph peaks at 169 MiB resident under its 1024 MiB budget.
- **The whole run has a safety ceiling** that protects the machine and does not grade: `./conform` runs each
  `santa-run` in a session of its own, under the data limit at **8192 MiB** per process (twice the largest budget
  `./validate` allows, so a runner's backstop for a bounded child fits beneath it) and a wall timeout of **1800 s** (a
  cold build took under a minute when measured). It kills every process of that session at the timeout, when conform
  itself is interrupted (Ctrl-C, SIGTERM, SIGHUP; conform then dies of the same signal), and after `santa-run` exits,
  so nothing a runner started outlives it. A runner that hits the timeout loses its column, reported ⚠️ `santa-run
  exceeded the safety ceiling (time 1800 s)`.

## 4. The runner grades; SANTA re-grades

### 4.1 Selection

The runner selects from `<santa-dir>/vectors/`:

- **Discovery:** every `*.json` file under `vectors/`, except the grading rules at its root (`vectors/grading.json`).
  Every discovered file sits at `<tier>/<version>/<provenance>/<op>.json`; a runner refuses (exit non-zero) a file
  that does not. Nothing else under `vectors/` is a vector.
- **Selected:** a file whose tier is in `tiers`, and whose version slot is `any` or at or below `version` in the order
  `v4 < v5 < v6`;
- and, for a wire file, also: its one kind (every entry's `kind`; a wire file holds one) is in `kinds` when `kinds`
  is declared, and, when `activation` is `false`, its envelope does not say `"activation_rule": true` (absent counts
  as `false`).
- `cost` plays no part in selection; it decides whether the cost dimension is graded (§4.2).

**The selection oracle**, [`oracle/selection.json`](../../oracle/selection.json) (`santa-oracle-selection/v1`),
proves it. `files` lists a synthetic `vectors/` tree, each `{"path"}` relative to `vectors/`, a wire file with its
`kind` and, when set, its `activation_rule`; each case gives a `scope` (a `runner.json` scope) and the sorted paths it
`selects`. Before it grades anything, the runner checks that its selection reproduces every case and refuses
otherwise: by feeding its selection the listed facts, or by building the tree — a wire file `{"entries": [{"kind":
<kind>}]}` with `"activation_rule"` when the file gives one, any other `.json` file `{"entries": []}`, `grading.json`
`{}`, a non-JSON file any text. An oracle file of a schema the runner does not know (a `santa-oracle-selection/v2`)
is refused, as an unknown grading schema is. conform's own selection is proven against
it by `tools/conform`'s tests. It guards the paths where no canary runs (a pull request check, a maintainer's
machine) against a selection bug that would grade a smaller corpus and report no change.

### 4.2 Grading

For each entry: run it, take the actual, and grade it against the entry's `expected` by the grading type
`vectors/grading.json` assigns to its file, through the runner's own interpreter of the grading language
([`grading.md`](grading.md)); `cost` (§2) is the verdict's `claims_cost`. Before it grades anything, the runner checks
its interpreter against the verdict oracle — every `<santa-dir>/oracle/*.json` whose schema is
`santa-oracle-grading/v1`, today [`verdicts-grading.json`](../../oracle/verdicts-grading.json), in the format of
[grading.md §6](grading.md#6-conformance) — every case's verdict reproduced, or it refuses (exit non-zero, naming the
case). A grading file of a schema version the interpreter does not know is refused the
same way ([grading.md §5](grading.md#5-strictness)). So the oracle of the very checkout it grades with is the
conformance test of every interpreter, on every run, and a grading type SANTA adds needs no runner change; only a new
building block of the language does.

### 4.3 Outputs

In `<out-dir>`. `run` first removes `<out-dir>/results.json` and `<out-dir>/actuals/`, before anything else, so a run
that fails leaves no earlier run's results behind:

- **`results.json`**, schema [`schema/santa-runner-results.schema.json`](../../schema/santa-runner-results.schema.json)
  (`santa-runner-results/v1`; every object closed):
  - `santa`: the SANTA commit graded against, `git -C <santa-dir> rev-parse HEAD`, when `<santa-dir>` is the top of a
    git checkout (`git -C <santa-dir> rev-parse --show-toplevel` is `<santa-dir>` itself, compared as real paths —
    `realpath`, `pwd -P`; conform passes its root by real path: inside another repository, `rev-parse HEAD` names that
    repository's commit); else `null`. Uncommitted changes are not represented: conform
    and the runner read the same tree;
  - `scope`: the scope it graded at, its `runner.json` keys (`version`, `tiers`, `cost`, and `kinds` / `activation`
    when declared);
  - `slices`, keyed `"<tier>/<version>/<provenance>"`, one per slice with a selected file, each with:
    - `files`: the vector files it graded, by path relative to `vectors/`;
    - `dims`: per graded dimension, `{"graded": n, "nice": n}` (a dimension an entry does not grade is not counted);
    - `not_implemented`, `panicked`, `exceeded`: how many entries had each outcome;
    - `red`: one record per red — a red dimension of an entry, or an entry whose outcome is not-implemented, panicked
      or exceeded: `{"file", "entry", "dim", "expected", "got", "note"?}`, where `dim` is the dimension or the outcome
      (`not-implemented`, `panicked`, `exceeded`), `expected` / `got` are short renderings for people and never
      compared (the board renders its own), and `note` is a panic's message;
  - `totals`: `red` (the red records over all slices) and `not_implemented`.
- **`actuals/<path>`**: the raw actuals of every graded file, at its path relative to `vectors/`, in the tier's
  actuals shape (§3). SANTA's canary reads them (§4.4).

### 4.4 What SANTA does with it

`./conform` is a batcher. For each board entry (§1) it resolves the implementation and the runner's directory, runs
`santa-run <impl-path> <santa-root> <out-dir>` under the safety ceiling (§3a), and then:

0. compares its checkout's working tree before and after the run (`git status`, with a hash of each listed file's
   content, outside the runner's own `runners/<name>/`): a change, by the runner or anyone else during its run, stops
   the board run, naming the paths, since the runners after it would grade against altered expectations;
1. checks `results.json` against its schema, and every actuals file against its tier's actuals schema (an actual that
   fails grades `panicked`, the note naming the schema and why); it refuses a `results.json` whose `santa` is not its
   own checkout's commit, `null` included (the runner graded other expectations, or cannot say which);
2. **completeness:** the files the runner reports graded must equal conform's own selection for its scope, and every
   entry of them must be accounted for; a shortfall is a red finding on the runner;
3. **the canary:** it re-grades every actual with [`santa-check`](../../tools/santa-check), SANTA's own interpreter of
   the grading rules, proven against the same oracle, and compares its verdicts with the runner's per vector file,
   entry and dimension. **The board shows SANTA's re-grade.** Where the runner's own verdicts differ, a note below the board says
   that the runner and SANTA disagree, lists the entries, and says the package maintainer needs to patch their grader
   or runner;
4. it consolidates every runner into `.santa/results.json` (`santa-results/v1`) for the scoreboard: each red record
   carries its vector file (`file`), and each runner that ran its `grader` — `agrees`, the `missing_files` and
   `extra_files` against conform's selection, the `missing_actuals` (files it listed as graded but wrote no actuals
   for), the `disagreement_count` and up to 200 `disagreements` (`file`, `entry`, `dim`, and which side, `runner` or
   `santa`, called it red). The scoreboard renders a disagreeing grader as a note
   below the board.

The orchestrator runs the full grid over `runners/*`: the N-way is whatever is checked in, never a hardcoded count.

**Prose travels with the vector and is never graded.** An entry may carry free prose saying why it exists and
what it pins: `script` in the eval tier, an optional `description` in every other tier. A runner ignores it and
no grader reads it, so editing prose cannot change a verdict. (`conform` copies an eval entry's `script`
into its red-entry detail, for the scoreboard.)

## 5. Deferred (named, not silent)

- **Compiled-artifact cache** — a runner's whole workspace `.santa/<name>/` (the cached impl checkout and its
  outputs) is self-contained and delete-able; the checkout is reused across runs (fetch+checkout), but compiled build
  outputs aren't cached by SANTA, so each `./conform` may rebuild. `./conform --clean` (or `rm -rf .santa`) resets
  everything.

## 6. Worked example — adding a runner

1. Implement `santa-run` (any language) per §3: check both oracles, select (§4.1), run every entry, bound the bounded
   files (§3a), grade (§4.2), write the outputs (§4.3); self-provision its toolchain.
2. Add `runner.json` (§2) and a `mise.toml` (§3 Toolchain) pinning the toolchain.
3. Make `santa-run` executable and place the dir under `runners/` (in-tree or `git submodule add`), or host it in
   the implementation and give SANTA a marker entry (§2).
4. `./conform` discovers and runs it; the per-slice table shows its results.

The JVM reference (rudolph) is canonical (eval contract §6): where a runner diverges from the blessed `expected`, the
runner is wrong — surfaced as RED, routed, never hidden.

## 6a. One runner, a working tree, and a pull request's delta

```
./conform [--clean] [<runner>…] [--impl <url>#<ref> | --impl <path>]
./conform [--clean] --hosted <url>#<ref> | <path> [--runner-dir <dir>] [--label <label>]
./santa-pr [--runner-dir <dir>] [--label <label>] <impl> <base-ref> [<head-ref>]
```

- **Runner names** run only those runners, in discovery order; a control that is not named is not required. No name
  runs every runner, as before. An unknown option is refused.
- **`--impl`**, with exactly one runner named, replaces that runner's `impl` for this run (its `runner.json` is not
  touched). `<url>#<ref>` is checked out as in §2; a **plain path** is graded **in place**, uncommitted edits
  included, and `.santa/results.json` records its sha as `<sha>-dirty` when the tree has uncommitted changes. Either way
  the runner finds the impl at `<impl-path>/<repo-name>` under the CONFIGURED impl's repo name, whatever the
  directory is called; a runner the implementation hosts is invoked at the tree's real path, never through that
  link (through it, a build would record the same crates under two paths in one target dir), and finds the tree
  from its own location (§3). A runner that patches the impl it builds against must not be pointed at a working
  tree.
- The cached clone (`.santa/<name>/<repo-name>`) follows the spec's url, and `<url>#<branch>` is that url's branch
  tip, checked out detached; a pinned commit no branch holds is fetched by sha. So a run against a local checkout
  never leaks into the next run against the upstream.
- **`--hosted`** runs no runner under `runners/`: only the implementation's own, the directory `<dir>` (default
  `santa`) of the checkout `--hosted` names (`<url>#<ref>` or a path in place, as `--impl`), read as §2's hosted
  runner. It is named after the repo and labelled `<label>` (default the same); no control is required. Runner
  names and `--impl` are refused beside it.
- **`./santa-pr`** grades the implementation's own runner (`--hosted`) at `<impl>`'s `<base-ref>`, then at
  `<head-ref>`, or without one at the working tree of a local `<impl>`, each with that ref's copy of the runner, on
  the same checkout's vectors, and writes `santa-delta`'s comparison to `.santa/pr/`: `comment.md` (the counts of
  newly red, now green, red another way and still red, then one row per vector file), `summary.md` (every entry's
  expected / got, cut at 1 MiB) and `delta.json` (every entry, both sides). A base without the runner directory
  could not run, and the comment says so. An implementer runs it before pushing. A commit sha is the unambiguous
  base.
- **In CI**, the composite action [`.github/actions/santa-pr`](../../.github/actions/santa-pr/README.md) runs it in
  the implementation's own workflow (base: the PR's base; head: the PR merged into it; the runner's directory and
  label as inputs) and uploads the delta; a second workflow, on the default branch, posts it as the PR's comment.
  SANTA needs no entry for the implementation, and a board entry it may have plays no part. The split keeps a fork's
  PR safe: its code runs only with a read-only token, and the write token never meets it. The action's README holds
  both workflow files.
- **`./santa-pr`, `santa-delta` and the action retire** once implementations run the pull request check of their
  own: the workflow runs the implementation's runner on the base and on the head against one SANTA checkout, and
  compares the two `results.json` files in the workflow itself, with no SANTA code.

## 7. Grading on your own machine

A runner is the implementation's own grading tool as much as SANTA's: run it against a SANTA checkout and it grades
the implementation with no SANTA tooling at all.

```bash
git clone --depth 1 https://github.com/mwaddip/santa.git ~/.cache/santa
santa/santa-run - ~/.cache/santa ./santa-out     # a runner the implementation hosts, at its checkout's root
```

(or the package's own wrapper, which may fetch the checkout itself, §3). `santa-out/results.json` holds every red
with its expected and actual answer, `santa-out/actuals/` the raw answers. For the same implementation commit and
SANTA commit, the verdicts match the board's whenever the runner's grader agrees with SANTA's canary, which re-grades
with the same rules and oracle; where they disagree, the board says so (§4.4).

**Badges are SANTA-minted, never self-reported.** SANTA's CI runs the canonical grid and publishes per-runner
naughty/nice badges and a dashboard; a runner's README *references* the SANTA-hosted endpoint. A runner's own run is
for its dev loop; the public 🎁/🪨 is SANTA's to award, so it means something.
