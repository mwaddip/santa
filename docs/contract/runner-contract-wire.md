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
  `entries`. Each entry is `{ name, kind, source, bytes_hex, version }` — **there is no
  `expected` field.** The blessed expected *is the entry's own `bytes_hex`* (round-trip to
  self): the JVM-canonical bytes for that object. `source` is **per entry** (not the
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
- **Failure (recognized):** `{ "bytes_hex": null, "error": "errored" }` — the serializer's
  parse/reserialize threw (it rejected bytes the JVM blessed — a real divergence).
- **Not-implemented:** `{ "bytes_hex": null, "error": "not-implemented" }` — the runner has
  no serializer reachable for this `kind`.
- **Panicked:** `{ "bytes_hex": null, "error": "panicked", "note": "<message>" }` — any
  other uncaught throw, caught so the run continues; also the landing for the
  implementation's **own** failure to hold/represent a value. Graded coal **unconditionally**.

`error` null ⇔ `bytes_hex` present (the asymmetry the actuals schema pins); `note` present
iff `error == "panicked"`. A wire conformer is inherently **cost-less** — it declares
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

Precedence mirrors the eval grade: **panicked → not-implemented → roundtrip**. The
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
  `{ Constant, Box, Transaction, Header, SigmaBoolean }`, extensible. A `kind` the runner
  does not serialize is `not-implemented` (§2), never a silent skip.
- **Echo-cheat blind spot (named, not hidden).** Round-trip-to-self cannot observe the
  intermediate structure, so a runner that returns its input unparsed passes every vector.
  This is accepted: a conformer's author *wants* to surface their own serializer's bugs, and
  the canonicalize-bless already catches serialize-side divergences (JVM-vs-sigma-rust)
  before any runner runs. The blind spot is closed by the **deferred** arms below.

## 6. Relationship & deferred arms (NOT specified here)

The eval contract is frozen and untouched; this companion adds the wire result shape beside
it (the `schema` discriminator routes between them). Named non-goals, to be specified when
built (do not implement against them) — see `wire-tier.md` "Out of scope / Deferred":

- **Wire reject/mutation arm** — malformed/non-canonical bytes a correct parser must reject;
  disciplines the echo-cheat. The wire analog of the eval reject arm.
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
