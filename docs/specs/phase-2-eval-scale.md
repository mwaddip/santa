# Phase 2 — Eval tier scaled

Grow *the nice list* from one op to the whole `fixture-gen` eval corpus: bless the
remaining **99 ops** (100 fixtures; `decode-point` already done) through the JVM
reference, widening the value vocabulary and honoring per-entry context inputs.
Umbrella: [SPEC.md](../../SPEC.md). Builds on
[phase-1-eval-loop.md](phase-1-eval-loop.md) — schema + runner contract, unchanged.

**The blesser stays a pure truth-emitter.** No JVM-vs-fork diff here: the sigma-rust
fork is validated against the JVM *by construction* (the Rust node syncs genesis→tip
in consensus with the JVM), so a diff over these vectors finds nothing by design. The
divergence target that *isn't* already covered is **ergots** (the TS port) — and that
belongs to the runner tier (thread B), not the blesser. The fixtures' own
`expected_value_json` / `expected_cost` are read for `tree_bytes_hex` + `name` only;
their expected fields are ignored — the JVM re-blesses.

## Deliverables

1. **Value vocabulary** — `EvalCore.valueToJson` extended from GroupElement+Opaque to
   the full `SValue` union, reading JVM runtime types. Canonical schema = the TS
   `SValue` union (`ergots/packages/ergoscript/src/mir/types.ts`); the fork's
   `fixture-gen/.../eval/common.rs::value_to_json` mirrors it but **lags** — e.g. it
   punts `Option` to `Opaque`; the TS union is the source of truth.
2. **op-name from filename** — derive `op` from the fixture filename stem
   (`sigma-or.json` → `sigma_or`); 9/100 fixtures lack the `corpus` field the blesser
   keys on today.
3. **Batch blessing** — drive the blesser over the whole fixture dir → one vector per
   op under `vectors/eval/`, so the nice list grows in one pass.
4. **Context from `opts_json`** — build the eval `ErgoLikeContext` from each entry's
   declared inputs (Stage 2; the blesser ignores `opts_json` entirely today).
5. **The grown nice list** — ~99 new committed `vectors/eval/<op>.json`.

The schema envelope is unchanged: still `santa-eval/v1`; Phase 2 only widens the
`expected.value.kind` vocabulary and feeds the context. `decode-point.json` stays valid.

## Two stages

Split by whether a fixture has **any** context-bearing entry (`opts_json`) — Stage 1
fixtures bless correct-by-construction (no context to honor):

- **Stage 1 — 58 fully-context-free fixtures** (every entry `opts_json: {}`;
  `decode-point` among them, done → 57 new): need only the value vocabulary (1–3) +
  the existing dummy context. Breadth-first; exercises every Stage-1 value kind.
- **Stage 2 — 42 fixtures with ≥1 context-bearing entry**: add (4) — context-extension
  vars, registers, headers, constants, `jitCostLimit` → bless the rest, plus the
  cost-limit error arm.

(Many of the 42 are *mostly* context-free with a single `jitCostLimit` entry; Stage 2
honors `opts_json` per entry. A few entries may still surface needing richer context
than the dummy provides — handled as they appear.)

## Value-kind vocabulary

`expected.value` is `{ "kind": <Variant>, … }`; schema = the TS `SValue` union.

**Stage 1** — the kinds context-free ops actually produce (empirically confirmed by
running the blesser's `Opaque` branch over representative fixtures):

- **Primitives** — `Boolean`/`Byte`/`Short`/`Int` → `{ value: <number> }`;
  `Long`/`BigInt` → `{ value: "<decimal-string>" }` (JSON has no exact bigint).
- **GroupElement** → `{ bytes_hex }` — 33-byte SEC1 (done in Phase 1).
- **SigmaProp** → `{ raw_hex }` — bare `SigmaBoolean` wire bytes (no ErgoTree header).
- **Coll** → `{ elem: <SType>, items: [<SValue>…] }`; byte-colls unpack to `Byte` items.
- **Tuple** → `{ items: [<SValue>…] }` (JVM represents pairs as `scala.Tuple2`).

`SType` (for `elem` fields) → `{ "tag": "S…" }`; recursive `elem` for `SColl`/`SOption`,
`items` for `STuple` (per `common.rs::stype_to_json`), derived from the runtime element
type via `sigma.Evaluation.rtypeToSType`.

**Stage 2** — arrive with their (context-bearing) ops:

- **Option** → `{ elem: <SType>, value: <SValue> | null }` (None → `null`).
- **Box / Header / PreHeader** → `{ value: <structured-json> }` per the TS interfaces
  (mirrored by `common.rs::{ergo_box,header,preheader}_to_json`).
- **AvlTree** — no settled encoding (the fork punts to `Opaque`); settle it when
  blessing the `savltree-*` ops.
- **Unit / Context / Global** — sentinels, no payload.

## Error class

Unchanged from `v1`: coarse `null | "errored"`. The fork carries a 41-code error
taxonomy, but the JVM throws JVM exceptions, not those codes — a fork code-string and
a JVM exception class aren't comparable, so the only cross-impl-meaningful axis is
*did it error?*. A finer enum stays a later (v2) refinement.

## Out of scope

- **JVM-vs-fork differential** — see intro; not this phase, and empty by construction.
- The first independent runner (ergots) — thread B, routed to the ergots repo.
- Wire / block tiers; reject arm; CI.

## Status

**Drafted** — Stage 1 next (value vocabulary + op-name + batch). Stage 2 (context
construction) firmed once Stage 1 delivers.
