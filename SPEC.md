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

**Eval-tier vector** (from the Phase-0 spike; Phase 1 finalizes the on-disk schema):
- `tree_bytes_hex` — serialized ErgoTree; root may be **any type**, not only SigmaProp;
- **version** it's blessed under — `(activatedVersion, ergoTreeVersion)`;
- expected: typed value `{ kind, … }` + **raw JIT cost** + coarse **error-class**
  (success ⇒ null).

**Runner I/O contract** — *pinned in Phase 1*: vector in → normalized result out, so
the harness can compare any conformer's output to the blessed expected. A runner
**declares which ops/tiers it supports** and *abstains* on the rest — Fleet runs wire
ops and abstains on eval (neither nice nor naughty on what it doesn't implement). The
result shape is whatever the op asserts: value + JIT cost for eval; parsed structure
or round-trip-ok for wire.

## Phase roadmap

| Phase | Delivers | |
|---|---|---|
| **0 — spike** | JVM blesser validated on `decode-point` | ✅ done |
| **1 — eval loop closed** | the **basic shape**: vector schema + `decode-point` committed as a canonical vector + harness + a JVM reference runner (runs green); first *independent* runner (ergots) routed next | ✅ done |
| **2 — eval scaled** | bless all ~110 `fixture-gen` ops through the JVM; composite value encodings | |
| **3 — conformers + CI** | sigma-rust / ergo-node-rust runners; CI gate on committed vectors | |
| **4 — block tier** | captured-block vectors, chain-blessed, node runner | |
| **5 — reject arm** | authored mutation vectors (rejected *for the right reason*) | |

Phases 2–5 are roadmap, not spec — each gets its subspec when reached. The **wire
tier** is a *parallel* track, not a sequential phase: it reuses the same `fixture-gen`
assets and serves the broadest conformer set (scorex, Fleet, wallets) — pick it up
alongside the eval tier once Phase 1's loop shape is proven.

> **Note on Phase 1's "reference runner":** the first runner is the JVM itself
> (the blesser in consume-mode), so it passes trivially — it proves the *harness
> mechanics* and defines the runner contract by example, but it is **not yet a
> conformance check**. Real conformance begins with the first *independent* runner
> (ergots), whose implementation lives in the ergots repo and is routed there.

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
| Dasher | `ergots` | first independent runner (TS) — *tentative* |
| _(unassigned)_ | `sigma-rust` fork · `ergo-node-rust` · … | assigned on registration |

Nine reindeer = a deliberate soft cap; there won't be dozens of independent Ergo
consensus implementations.

## Status

Phase 1 delivered — the eval loop runs green end-to-end on `decode-point`
(`nice ✓ 6/6`): blesser → committed vector → runner (Rudolph) → harness.
**Next: the first independent runner (ergots), and/or scaling the eval tier (Phase 2).**
