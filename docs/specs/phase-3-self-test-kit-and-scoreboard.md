# Phase 3 — Self-test kit & conformance scoreboard (design)

> **Status: design, not built.** What runs today is the in-SANTA `./conform` 4-way (rudolph · dasher ·
> blitzen-develop · blitzen-eni). This subspec captures the optional capability that lets a runner
> self-test in its own CI and turns SANTA into a public conformance scoreboard. Contract touchpoint:
> [`docs/contract/runner-integration.md`](../contract/runner-integration.md) §7.

## Goal

Two related capabilities, both inversions of "conformance only runs inside SANTA":

1. **Runner self-test** — a conformer repo (e.g. `santa-blitzen`) gates *itself* in its own CI against
   a pinned set of SANTA blessings, with no SANTA checkout. (The `execution-spec-tests` model: clients
   pull a fixture release and run it in their own suite.)
2. **Conformance scoreboard** — SANTA publishes per-runner **naughty/nice badges** + a dashboard that
   conformer repos reference in their READMEs: the public, canonical status.

## The model

Keystone decision: **the comparator is a spec, not a shipped tool.**

- **Comparator = spec.** The verdict logic (§5 structural equality + §6 grading — coverage precedence,
  independent value/cost) is a *specification* each maintainer implements in their own language for
  their own CI. SANTA ships no comparator binary into anyone's repo.
- **Verdict-oracle = the spec's conformance test.** Meta-test vectors
  `(actual, expected, claims_cost) → expected verdict`. Any comparator implementation runs the oracle
  and *proves* it agrees — the executable half of the spec, the way the corpus proves a runner. Without
  it, "implement the spec" is where §6 drift creeps back (two readers interpret "coverage beats reject"
  differently); with it, agreement is provable, not hoped.
- **One canonical engine (SANTA's).** SANTA keeps exactly one implementation of the spec — the engine
  that runs the authoritative `./conform` 4-way and mints the badges. The spec needs a reference *and*
  an authority: everyone may implement it, but the scoreboard is computed once, by SANTA. This engine
  becomes the **Rust** `santa-check` (replacing today's Python `tools/compare.py`) — Ergo-native, and
  for Rust runners it doubles as their reference.
- **The kit = data, not code.** A versioned artifact a runner consumes (below).

This resolves an earlier "ship a Rust binary vs ship pure data" fork: shipping a cross-compiled binary
to run ~70 lines of pure logic is disproportionate and puts SANTA in the binary-distribution business.
Native-per-ecosystem + an oracle is cleaner, and is what §6 already implies — SANTA already maintains
three comparators that must agree (Python `compare.py`, the TS one in Dasher, the Scala `Harness`). The
move is to make that trio Ergo-native (swap Python → Rust) and ship the **spec + oracle**, not a binary.

## The comparator spec (what a maintainer implements)

Per entry, given the runner's `actual {value, cost, error}`, the blessed `expected`, and whether the
runner `claims_cost`:

1. **Coverage precedence** — if `actual.error` is `not-implemented` or `unrepresentable`, the verdict
   is that coverage tag (the runner didn't engage the op), regardless of accept/reject.
2. **Reject vectors** (`expected.error == "errored"`) — one verdict: *nice* iff the runner also
   `errored`, else a reject-divergence.
3. **Accept vectors** — independent verdicts:
   - **value**: *nice* iff `actual.error` is null and `actual.value` structurally equals `expected.value`;
   - **cost**: graded only if `claims_cost` *and* value is *nice*; *nice* iff `actual.cost == expected.cost`.

**Structural equality (§5):** objects key-order-insensitive; arrays order-sensitive; numbers numeric;
strings exact; `null` equals only `null`; `bool` is not an `int`. Reference impl today: `tools/compare.py`
`structural_equal`/`grade` — to be ported into the Rust canonical engine.

## The verdict-oracle

A committed set of meta-test vectors (e.g. `oracle/*.json`):

```json
{ "name": "cost-mismatch-when-claimed",
  "actual":   { "value": {"kind":"Int","value":1}, "cost": 99,  "error": null },
  "expected": { "value": {"kind":"Int","value":1}, "cost": 104, "error": null },
  "claims_cost": true,
  "verdict": { "kind": "accept", "value": "nice", "cost": "cost" } }
```

Cases must cover every branch: coverage-precedence (incl. not-impl on a reject vector), reject
nice/divergent, value nice/divergent, cost nice/divergent/n-a (unclaimed), and the structural-equality
edges (key-order, array-order, bool≠int, null). An implementation is conformant iff it reproduces every
`verdict`. The oracle ships in the kit and lives in-tree as the cross-check for SANTA's engine + the
per-ecosystem references (§6 made executable).

## The kit

A **GitHub Release** tied to a SANTA tag (`santa-data-v…`):

```
santa-data-vX.Y.Z/
  vectors/<tier>/<version>/<provenance>/*.json   # blessed corpus (with expected)
  schema/santa-eval.actuals.schema.json          # actuals shape
  comparator-spec.md                             # the §5/§6 algorithm above
  oracle/*.json                                  # verdict meta-vectors
  VERSION                                         # the SANTA commit/tag it was cut from
```

Small (~1974 entries / 117 files). **Pinned, not "latest":** a runner gates against a known blessing
set, so a re-bless on SANTA's side can't silently flip a runner's CI — it bumps its pin when it chooses
(as clients pin an EEST fixture version), and a 🪨 is always traceable to an exact corpus via `VERSION`.

## Self-test flow (runner CI)

1. Fetch + unpack a pinned `santa-data-vX.Y.Z`.
2. (Once) implement the comparator from `comparator-spec.md`; prove it against `oracle/` in CI.
3. Run `santa-run <impl> <selected-vectors> <out>` over the subset the runner's `runner.json` selects
   (`version ≤`, `tiers ∈`) — the same entrypoint `./conform` uses.
4. Grade `out` vs the corpus `expected` with the (oracle-checked) comparator; gate on the RED count.

The verdict equals `./conform`'s by construction (same spec, oracle-proven). For Rust runners, step 2
is "depend on SANTA's `santa-check` crate" — the canonical engine itself.

## Scoreboard & badges

- SANTA CI runs the canonical 4-way → emits a structured **`results.json`** (per runner / slice /
  dimension) → a generator turns it into per-runner **badge JSON** (shields.io endpoint:
  `{label, message, color}`) + a **dashboard** table → published to GitHub Pages.
- A conformer README references the SANTA-hosted endpoint
  (`img.shields.io/endpoint?url=…/blitzen-eni.json`); it refreshes whenever SANTA re-runs (re-bless,
  runner-bump, schedule). **Self-reported badges are not canonical** — the suite awards the 🎁.

## The shared refactor

`results.json` is the linchpin: factor `./conform`'s grading core (`run_one` + `tally`) into a
corpus-parameterized function that emits structured results. **One** change then feeds three consumers:
the terminal table (today), the kit self-test gate, and the badge/dashboard generator. Bundle with
porting the canonical comparator Python → Rust (`santa-check`).

## Reproducibility preconditions (a clean recursive clone must run the 4-way)

The kit and scoreboard both assume a clean clone can run `./conform` — the scoreboard's canonical badge
run *is* a clean-environment CI run. Today it can't, on two counts (found by walking the fresh-checkout
case):

1. **dasher's impl is a local path, not fetched.** `ts-runner/package.json` points `@ergots/*` at
   `file:../../ergots/...` (a `~/projects/ergots` sibling not in the checkout), and
   `runners/dasher/santa-run` runs `npm run build` with no `npm install` — so dasher only runs on the
   author's box. Fix (SANTA-side, no ergots change): give dasher a real `impl: "<ergots-url>#<ref>"` so
   `./conform` clones ergots into `.santa/dasher/`, and have `santa-run` build ergots' `dist/` then
   `npm install && build` the ts-runner against `$1/ergots` — the fetch-the-impl model blitzen already
   follows (the extra steps are just the JS two-repo dist build). rudolph + blitzen are already correct.
2. **Toolchains aren't bootstrapped.** `./conform` assumes `python3`; rudolph a JDK + sbt; blitzen
   cargo; dasher node — with no installer and no CI. **Decision: a per-runner `mise.toml`** of pinned
   toolchains; `mise install` is the recursive, daemon-less, root-less installer (the "text file of
   deps + installer" shape, already built and maintained). It pins *versions*, not a sandbox — fine
   while every runner is first-party/trusted. Per-runner *isolation* is a separate future concern (a
   container/VM, for when untrusted third-party runners exist — chroot / PATH-shims aren't a security
   boundary), not built now.

Do these as **one pass** — dasher's `impl` + a `mise.toml` on every runner + a CI workflow running the
4-way — not piecemeal: the runners are being brought in line anyway, so make the clean clone
reproducible properly, at once. This pass is the floor the kit and scoreboard stand on.

## Build order

0. **Reproducibility pass (precondition)** — dasher's fetched `impl` + a `mise.toml` on every runner +
   a CI workflow running the 4-way (the section above); the floor everything below stands on.

1. Factor the grading core → `results.json` (structured output from `./conform`).
2. Port the canonical comparator to Rust (`santa-check`, replacing `compare.py`); write the
   verdict-oracle; prove `santa-check` + the TS/Scala references against it.
3. Kit packaging + Release CI (cut `santa-data-v…` on tag).
4. Badge/dashboard generator + GitHub Pages publish from `results.json`.

## Open / to refine

- **Badge granularity** — one per runner, or per runner × version (a v5 badge, a v6 badge)?
- **Oracle coverage** — enumerate the exact branch set; it *is* the contract for "agrees."
- **Rust migration** — `compare.py` → `santa-check` is a real port; the schema validator
  (`validate.py`, leaning on Python `jsonschema`) is separable and can stay Python or move later.
- **Pages topology** — branch vs `/docs`, and endpoint-URL stability (READMEs pin to it).
