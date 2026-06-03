# SANTA — Architecture & Roadmap (umbrella spec)

The **living architectural spec**: what SANTA is *now*, and the phase roadmap. It
complements [README.md](README.md) (public intro) and [BOOTSTRAP.md](BOOTSTRAP.md)
(rationale / decision log — *why* each call was made).

**Working method.** This umbrella is the stable reference. Each phase gets a **thin
subspec** under `docs/specs/`, written just before the work and informed by the
previous phase's delivery. When a delivery conflicts with this umbrella, the
umbrella is corrected immediately — it tracks reality, not intentions.

## The conformance loop

```
        oracle                                   conformer
  chain / JVM reference                  ergots · sigma-rust · nodes
        │                                          │
        ▼                                          ▼
     blesser ───▶ committed vectors ───▶        runner ───▶ result
   (produces the   (the data contract,        (consumes,    {value, cost,
    blessed truth)   versioned, in-repo)        per-impl)     error-class}
                            │                       │
                            └──────── harness ──────┘
                          (runs a vector set through a runner,
                           compares result to blessed expected)
```

- **Oracles** bless canonical expected outputs. The sigma-rust fork is a
  *differential target*, never the oracle (decision 2).
  - block validity → **the chain** (a mainnet block is valid by definition);
  - eval fine-values (value, cost, reduced sigma-tree) → the **JVM reference**
    (`sigma-state`), the de-facto spec.
- **Vectors** are the committed, versioned data contract — *raw wire bytes in →
  blessed output out*. The contract lives in the data, not in code (decision 8).
- **Runners** are thin per-conformer consumers of one I/O contract. The *contract*
  is SANTA's; *implementations* live with the conformer's maintainers **when they
  participate** (`ergots`, `ergo-node-rust` — the author's repos; future alt impls —
  theirs). For conformers with no separate team — the **JVM reference** and the
  **`sigma-rust` fork** — **SANTA delivers the runner itself**, and ships per-language
  runner **scaffolding** so either path is cheap. The JVM reference runner (Rudolph)
  is the first such scaffold.
- **Harness** (in SANTA) runs a vector set through a runner command and reports.

## Tiers

| Tier | Vector | Blessed by | Run by |
|---|---|---|---|
| **Wire** | bytes ⇄ structure (round-trip / parse) — constants, boxes, trees, txs, headers, VLQ | JVM serializers · captured chain bytes | *any serializer*: ergots, sigma-rust, **scorex**, **Fleet**, wallets/SDKs |
| **Eval** | ErgoTree bytes (+ ctx) → typed value + JIT cost | JVM interpreter | consensus libraries (ergots, sigma-rust) + JVM |
| **Block** | block H → valid? / state-root | the chain | full nodes (ergo-node-rust, JVM) |

The **wire tier** is the broadest — every wallet/SDK serializes while only a couple
of libraries eval — and it's squarely consensus: a `boxId` *is* the hash of a box's
serialized bytes, so a serialization divergence is a different `boxId`. Its cheapest
assertion is a **byte round-trip** (parse → reserialize → identical bytes), needing
no shared structured form; "parse → assert structure" is the richer variant.

## Contracts (sketch — each firms as its phase delivers)

**Eval-tier vector** (`santa-eval/v2`, Phase-2 Stage-1; `santa-eval/v1` for the Phase-1
closed-tree `decode-point` special case):
- `tree_bytes_hex` — serialized **function** ErgoTree `A → B` (v2); or a closed tree (v1);
- `input` — the input SValue JSON, bound to context var 1 at eval time (v2 only; absent in v1);
- **version** it's blessed under — `(activatedVersion, ergoTreeVersion)`;
- expected: typed value `{ kind, … }` + **raw JIT cost** + coarse **error-class**
  (success ⇒ null).
- Source: `sigma-state` `LanguageSpecificationV5` + `V6` (JVM-native `verifyCases`, v2) — the
  oracle's own expected values, not fork-computed. Input-carrying form preserves cost fidelity
  (cost of applying a function to an input, not of a baked closed tree).

**Runner I/O contract** — *frozen for the eval tier*
([`docs/contract/runner-contract.md`](docs/contract/runner-contract.md)): vector in → one
**actuals** record per entry, so the harness can compare any conformer's output to the
blessed expected. The runner is **total** — it emits a faithful outcome for *every* entry
and never drops or hides one: value + JIT cost on success, else a coarse outcome tag
(`errored` / `not-implemented` / `unrepresentable`). A conformer's **scope is selected on
the input side** — it is run against the vector subset it claims (a v5-only library runs
the `v5/` corpus; a wire-only tool like Fleet runs wire vectors, not eval) — never by the
runner suppressing results. The result shape is whatever the op asserts: value + JIT cost
for eval; parsed structure or round-trip-ok for wire.

## Phase roadmap

| Phase | Delivers | |
|---|---|---|
| **0 — spike** | JVM blesser validated on `decode-point` | ✅ done |
| **1 — eval loop closed** | the **basic shape**: vector schema + `decode-point` committed as a canonical vector + harness + a JVM reference runner (runs green); first *independent* runner **Dasher** (ergots, `ts-runner/`) **built** — v5-scoped | ✅ done |
| **2 — eval scaled** | the eval corpus at scale — **1,974 entries / 117 files** from `sigma-state`'s `LanguageSpecificationV5` + `V6`, version-split (`v5/` + `v6/`); Dasher (ergots) gates v5 against the blessed expected | ✅ (incl. Context-input `getVarFromInput`, Stage 2b) |
| **3 — conformers + CI** | runner-agnostic orchestrator `./conform` + [integration contract](docs/contract/runner-integration.md) built (presence-as-state over `runners/*/`, one shared comparator, per-slice table); **rudolph + dasher + Blitzen** (`sigma-rust` develop & eni — two submodules) wired into a live **4-way**; ergo-node-rust runner + CI gate next; [self-test kit + scoreboard](docs/specs/phase-3-self-test-kit-and-scoreboard.md) designed | 🟢 underway |
| **4 — block tier** | captured-block vectors, chain-blessed, node runner | |
| **5 — reject arm** | authored mutation vectors (rejected *for the right reason*) | |

Phases 3–5 are roadmap, not spec — each gets its subspec when reached. The **wire
tier** is a *parallel* track, not a sequential phase: it reuses the same `fixture-gen`
assets and serves the broadest conformer set (scorex, Fleet, wallets) — pick it up
alongside the eval tier once Phase 1's loop shape is proven. **Now underway:** the
`santa-wire/v1` round-trip blesser is live — authored `Box` + `SigmaBoolean` vectors
(`vectors/wire/v5/authored/`), JVM-canonicalized from ergots' `fixture-gen` seeds and
schema-gated; runners + grading next ([`docs/specs/wire-tier.md`](docs/specs/wire-tier.md)).

> **Note on the "reference runner":** the JVM runner (the blesser in consume-mode)
> passes trivially — it proves the *harness mechanics* and defines the runner contract
> by example, but it is **not yet a conformance check**. Real conformance began with the
> first *independent* runner, **Dasher** (ergots), built **2026-05-31** in SANTA's
> `ts-runner/` — it consumes `@ergots/ergoscript` as a library (exactly as Rudolph
> consumes `sigma-state`; the runner is SANTA's, the implementation-under-test is a
> dependency). On first contact it surfaced genuine JVM-vs-ergots divergences (see
> [`docs/findings/eval-jvm-vs-ergots.md`](docs/findings/eval-jvm-vs-ergots.md)). The runner
> lives in SANTA for now; ergots may absorb it later.

## Glossary & roster

A tasteful theme on the *flavorful* surfaces; plain conventional terms for the data
and wiring (decision 8 — the contract stays unambiguous).

- **nice / naughty** — a runner's conformance *verdict* on a vector: its output
  matches the blessed expected (**nice**) or diverges (**naughty**). This is the
  *match-or-not* axis, distinct from a vector's own accept/reject validity — a
  vector that *should* be rejected, correctly rejected, is **nice**.
- **the nice list** — the committed canonical vector corpus; the record of correct
  behaviour every conformer is checked against.
- **lump of coal** — a single divergence (one naughty entry) in a report.
- **runner** — a conformer's consumer of the vectors (the plain contract term).
  Each runner instance also carries a **reindeer codename**: a friendly alias
  *alongside* its canonical impl id, never replacing it.

### Reindeer roster

| Codename | Runner (impl) | Notes |
|---|---|---|
| **Rudolph** | JVM reference (`sigma-state`) | leads — the oracle/reference the others follow |
| **Dasher** | `ergots` | first independent runner (`ts-runner/`, pure-TS) — **v5-scoped**: gates the `v5/` corpus against the JVM-blessed expected, **1705 / 1705 — fully green** (every routed value/cost divergence + the 27 not-implemented methods fixed in ergots `35eac6b`); v6 is out of its declared scope. |
| **Blitzen** | `sigma-rust` (×2 submodules) | `develop` (upstream, value-only, `cost:false`) + `ergo-node-integration` fork (`--features jit-cost`, `cost:true`) — eni v5-perfect, both v6-divergent; develop also misses 10 v5 values the fork fixed |
| _(unassigned)_ | `ergo-node-rust` · … | assigned on registration |

Nine reindeer = a deliberate soft cap; there won't be dozens of independent Ergo
consensus implementations.

## Status

Phase 1 delivered — the eval loop runs green end-to-end. Phase 2 delivered the **eval
corpus at scale**: **1,974 entries across 117 files**, blessed by `sigma-state` from its
language specification and version-split into **v5** (1,705 — the cumulative v5/mainnet
surface) and **v6** (269 — the v6 new-feature surface); a JSON-Schema gate validates all
117. The conformer layer is live: `./conform` runs a **4-way** (Rudolph · Dasher · Blitzen
develop & eni) over `runners/*/`. **Dasher (ergots) is fully green on v5 — 1,705 / 1,705**
(every routed divergence fixed). **Blitzen** (`sigma-rust`) is perfect on v5 via the eni
fork but divergent on v6, and upstream `develop` misses 10 v5 values the fork fixed — the
loop surfacing genuine cross-impl divergences, recorded and routed. The runner contract
holds a faithful per-entry outcome model (no abstention — scope is an input-side selection).
The **wire tier is opened** — `santa-wire/v1` byte-round-trip vectors (`Box` + `SigmaBoolean`,
JVM-canonicalized from ergots' `fixture-gen`) are blessed and schema-gated, and its first finding
(a box `creation_height` overflow the JVM rejects but sigma-rust accepts) is recorded in
[`docs/findings/`](docs/findings/wire-jvm-vs-sigma-rust.md). **Next:** wire runners + grading (the
wire result shape across the conformers), the node runners (`ergo-node-rust`, block tier), the
authored **reject arm** (mutation vectors), and a full CI gate.
