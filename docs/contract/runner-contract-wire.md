# SANTA Runner Contract — Wire tier (`santa-wire/v1`, byte round-trip)

> **Status: the committed result-shape contract for the wire tier (`santa-wire/v1`).** A
> lean companion to the frozen eval contract — it specifies *only* what is wire-specific and
> inherits the rest (totality, never-panic, faithful outcomes, comparator topology) from
> [`runner-contract.md`](./runner-contract.md). The wire tier is a *parallel track*, not a
> successor; the eval contract is untouched and its §7 reserves exactly this companion.
>
> Design rationale (the *why* and the deferred arms): [`docs/specs/wire-tier.md`](../specs/wire-tier.md).
> Machine-checkable schemas:
> [`schema/santa-wire.vector.schema.json`](../../schema/santa-wire.vector.schema.json),
> [`schema/santa-wire.actuals.schema.json`](../../schema/santa-wire.actuals.schema.json).
> Executable grading oracle: [`oracle/verdicts-wire.json`](../../oracle/verdicts-wire.json).

## 1. What a wire runner is

Same `run(vector) → actuals` shape as eval (`runner-contract.md` §1), with the result a
**serialization round-trip** instead of value+cost.

- A **wire vector** is a committed JSON file under [`/vectors/wire`](../../vectors/wire/):
  an envelope (`schema: "santa-wire/v1"`, `op`, `blessed_by`, `entries`) carrying a list of
  `entries`. Each entry is `{ name, kind, source, bytes_hex, version }` with an **optional
  `expected_bytes_hex`**. When absent, the blessed expected *is the entry's own `bytes_hex`*
  (round-trip to self): the JVM-canonical bytes for that object. When present (a **non-identity
  round-trip**), the runner is still fed `bytes_hex` as input, but the grade compares its output
  against `expected_bytes_hex` — the JVM-canonical bytes, which differ from the (non-canonical) input. `source` is **per entry** (not the
  envelope), so one slice — e.g. `Box` — can hold vectors vendored from several frameworks
  (ergots + Fleet), each tagged with its origin; the provenance dir follows (framework source
  ⇒ `vendored`, `testnet:`/`santa:` ⇒ `authored`).
- **Actuals** is the runner's output: a JSON object mapping each entry's `name` to the
  runner's **`{ bytes_hex, error }`** — its reserialization of the input bytes.

**Round-trip to self.** The runner parses `bytes_hex` with the serializer for `kind`,
reserializes, and emits the result. It is **nice** iff `actuals[name].bytes_hex` equals the
entry's `bytes_hex` (lower-case exact) and `error` is null. This intentionally collapses the
eval contract's "runner never reads `expected`" rule — round-trip's answer *is* its input
(see §5). **Version is an input** as in eval: the runner reserializes under the entry's
declared `(activated, ergoTree)`.

## 2. Result shape (actuals)

Mirrors the eval totality model (`runner-contract.md` §3) with `value`+`cost` replaced by a
single `bytes_hex` — **the wire tier has no cost dimension.**

- **Success:** `{ "bytes_hex": "<reserialization>", "error": null }`.
- **Failure (recognized):** `{ "bytes_hex": null, "error": "errored", "reason": "<message>" }` —
  the serializer's parse/reserialize threw (it rejected bytes the JVM blessed — a real
  divergence). `reason` carries the codec's own message (diagnostic, never graded; optional).
- **Not-implemented:** `{ "bytes_hex": null, "error": "not-implemented" }` — the runner has
  no serializer reachable for this `kind`.
- **Panicked:** `{ "bytes_hex": null, "error": "panicked", "note": "<message>" }` — any
  other uncaught throw, caught so the run continues; also the landing for the
  implementation's **own** failure to hold/represent a value. Graded coal **unconditionally**.

`error` null ⇔ `bytes_hex` present (the asymmetry the actuals schema pins); `note` present
iff `error == "panicked"`; `reason` is an optional diagnostic on any non-success outcome
(typically `errored`), never graded. A wire conformer is inherently **cost-less** — it declares
`tiers: ["wire"]` and `cost` is not a wire concept.

## 3. Grading — the single `roundtrip` verdict

Per entry the comparator emits **one** verdict (no value/cost split, no amber):

- **`roundtrip` nice** iff `bytes_hex` lower-case exact-equality **and** `error` null.
- **`roundtrip` differ** otherwise — the runner produced bytes, they just aren't canonical
  (the analog of an eval value-mismatch) → **coal, the deliverable.** A recognized
  `errored` and a byte mismatch both land here.
- **`not-implemented` → coverage**, coal — always a real coverage finding (exactly as eval
  §5: it never matches, so it is surfaced, not hidden).
- **`panicked` → coal unconditionally** — a crash is not a clean rejection.
- **Reject entries** (the vector entry carries `"error": "errored"`: the JVM rejects `bytes_hex` at
  parse, so there is no canonical output): **`reject` nice** iff the actual is `errored`. Producing
  bytes is the **over-accept** — coal, whatever the bytes.

Precedence mirrors the eval grade: **panicked → not-implemented → roundtrip / reject**. The
`not-implemented` and `panicked` verdicts are the *same shapes* the eval grade emits, so a
consumer tallies coverage/panicked uniformly across tiers. `oracle/verdicts-wire.json` is
the executable form of this section (reproduced by `santa-check`'s `tests/oracle.rs`).

## 4. Totality, never-panic, faithful outcomes (inherited)

Unchanged from `runner-contract.md` §3:

- **Totality & never-panic.** Every entry yields exactly one outcome; no entry is dropped,
  and no single entry aborts the file. A would-be crash is caught and surfaced as
  `panicked` (coal, message in `note`), never propagated.
- **Faithful outcomes — the runner never excuses the implementation.** A serializer's own
  failure on bytes it cannot hold is `errored`/`panicked`, recorded as it happened, never
  softened into an "excuse" tag. A gap in the **SANTA harness** (a serializer the conformer
  cannot reach through its public API) is the runner's `not-implemented` at that surface,
  with the cause routed/documented — a defect to close, not a standing outcome. (This is
  why, as in eval, there is no `unrepresentable` tag.)
- **No oracle dependency.** Producing actuals needs only the vector bytes plus the runner's
  own serializer — no JVM, no network.

## 5. Kind dispatch & the honest limitation

- **`kind`** selects the serializer the runner dispatches on — the initial set is
  `{ Constant, Box, Transaction, Header, SigmaBoolean, ErgoTree }`, extensible. A `kind` the runner
  does not serialize is `not-implemented` (§2), never a silent skip. An **`ErgoTree`-kind round-trip
  MUST re-serialize the parsed tree from structure**, not emit a cached/preserved copy of the input
  bytes (the JVM's `ErgoTree.bytes` echo, sigma-rust's template-bytes cache) — else it does not
  exercise the type/name re-encode the kind exists to test (e.g. the STypeVar UTF-8 surrogate fork).
- **Echo-cheat blind spot (named, not hidden).** Round-trip-to-self cannot observe the
  intermediate structure, so a runner that returns its input unparsed passes every vector.
  This is accepted: a conformer's author *wants* to surface their own serializer's bugs, and
  the canonicalize-bless already catches serialize-side divergences (JVM-vs-sigma-rust)
  before any runner runs. The blind spot is closed by the **deferred** arms below.

## 6. Relationship & further arms

The eval contract is frozen and untouched; this companion adds the wire result shape beside
it (the `schema` discriminator routes between them). The reject arm below is live; the other
arms are named non-goals, to be specified when built (do not implement against them) — see
`wire-tier.md` "Out of scope / Deferred":

- **Wire reject/mutation arm — LIVE** (§3; `error` in `schema/santa-wire.vector.schema.json`):
  bytes the JVM rejects at parse, each built so a lenient parser round-trips them cleanly and the
  over-accept surfaces as bytes rather than an incidental EOF. Families in `wire/v6/authored/`: the
  soft-fork SHeader-constant rejects; the ContextExtension bounds (count ≥ 128, id ≥ 0x80 —
  `Transaction.context_extension_{count,id}_bound`); the ContextExtension value rules (a v6-only
  type — Option, Header or UnsignedBigInt, also inside a collection or tuple — rejected by
  `CheckV6Type`, rule 1019; a value nested past the reader's 110-level cap —
  `Transaction.context_extension_{v6_type,depth_bound}`); the same 110-level cap on every other
  path the JVM's per-transaction level counter runs through (a register value, a tree body, a
  segregated tree constant, a SigmaBoolean, a Box nested in an extension, and the levels a
  soft-fork-degraded tree leaves on the reader — `Transaction.{register,tree_body,
  segregated_constant,sigma_boolean,nested_box}_depth_bound`,
  `Transaction.{degraded_tree,nested_degrade}_depth_leak`); a box's tree read window (4096 bytes
  from the tree's start, replacing the box window; checked before each read but not before a peek —
  `{Box,Transaction}.tree_read_window`); the root-type check on unsized trees
  (`{Box,Transaction}.tree_root_type_check`); and the creation-height parse bound (a box, output, or Box constant created above `Int.MaxValue` —
  `*.creation_height_int_bound`). Each bound ships with its accept twin on the other side. Beside them,
  `Transaction.context_extension_duplicate_ids` is a non-identity round-trip (§1): the JVM collapses a
  repeated extension id to its last value at its first position, so a runner must re-serialize the
  parsed transaction, not echo its input. `{Box,Transaction}.sized_tree_declared_size` are
  non-identity round-trips too: the JVM ignores a size-flagged tree's declared size when its body
  parses, continues after the bytes the body consumed, and writes the recomputed size.
- **`structural-assert` variant (`santa-wire/v2`)** — parse → emit a canonical structural
  form → compare; catches misparse-that-round-trips. Additive; `santa-wire/v1` stays.
- **Captured + serializer-only conformers** — real testnet `Transaction`/`Header`/box
  captures (the divergence-rich source), and `scorex` / Fleet / wallet runners this tier
  unlocks.

## 7. Worked example

```jsonc
// vector entry (in vectors/wire/v5/vendored/Box.json → entries[…]) — no `expected`
{
  "name": "sbox_minimal",
  "kind": "Box",
  "source": "ergots:fixture-gen/wire",
  "bytes_hex": "c0843d09020101000000000000000000000000000000000000000000000000000000000000000000000000",
  "version": { "activated": 2, "ergoTree": 2 }
}

// the runner's actuals file: { "<name>": { bytes_hex, error }, … }
{
  "sbox_minimal": { "bytes_hex": "c0843d09020101000000000000000000000000000000000000000000000000000000000000000000000000", "error": null }
}
```

The actual's `bytes_hex` equals the entry's `bytes_hex` and `error` is null → **roundtrip
nice**. Had the runner emitted different bytes → **differ** (coal); had its serializer
thrown → `{ bytes_hex: null, error: "errored" }` (coal); had it no `Box` serializer →
`not-implemented` (coal).
