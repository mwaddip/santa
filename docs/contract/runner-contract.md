# SANTA Runner Contract (eval tier)

> **Status: frozen for the eval tier (`santa-eval/v1`, `santa-eval/v2`).** This is a
> committed, language-agnostic contract — independent Ergo implementations build their
> runners against *this document + the JSON Schemas in [`/schema`](../../schema/)*, not
> against any one implementation's source. Umbrella: [SPEC.md](../../SPEC.md).
>
> Companion machine-checkable schemas:
> [`schema/santa-eval.vector.schema.json`](../../schema/santa-eval.vector.schema.json),
> [`schema/santa-eval.actuals.schema.json`](../../schema/santa-eval.actuals.schema.json).

## 1. What a runner is

A **runner** is a conformer's consumer of the committed vectors. Abstractly it is a
**pure function from one vector file to one actuals file:**

```
run(vector) → actuals
```

- A **vector** is a committed JSON file under [`/vectors/eval`](../../vectors/eval/): an
  envelope (`schema`, `op`, `blessed_by`, `entries`, and — `santa-eval/v2` only — a
  `source` provenance string; the Phase-1 `santa-eval/v1` `decode-point` vector predates
  `source`) carrying a list of `entries`. Each entry is one (`tree_bytes_hex`, `version`,
  optional `input`) case with the **blessed** `expected` result the JVM reference produced.
- **Actuals** is the runner's output: a JSON object mapping each entry's `name` to the
  runner's *own* `{ value, cost, error }` for that entry, where `error` is the outcome tag
  (§3) — `null` on success, else `errored` / `not-implemented` / `panicked`.

Conformance is then a separate comparison step (§5): an entry is **nice** when the
runner's actual equals the vector's blessed `expected`, **a lump of coal** otherwise.

The JVM reference runner ("Rudolph") is *one* implementation of this function; it is not
a dependency for anyone else (§6).

## 2. Lifecycle

```
committed vector ──▶ run(vector) ──▶ actuals ──▶ compare(actuals, vector.expected) ──▶ verdict
   (blessed truth)    (the runner)   (per entry)    (per entry, structural §5)        nice / coal
```

A runner reads a vector, evaluates each entry under that entry's declared version, and
writes actuals. It never reads `expected` (§3). The comparison is a distinct step that
*may* be the JVM harness, the runner's own checker, or a CI script — all must agree
because "equal" is pinned (§5).

## 3. Preconditions, postconditions, invariants

### Preconditions (a runner may assume)
- The vector validates against `santa-eval.vector.schema.json`.
- Every entry has `tree_bytes_hex` (hex of a serialized ErgoTree) and
  `version { activated, ergoTree }`. A `santa-eval/v2` entry also has an `input` SValue
  (§4); a `santa-eval/v1` entry has none (closed tree).
- **Version is an input.** The runner evaluates each entry under the entry's declared
  `(activated, ergoTree)` versions — it does not choose or assume a version.

### Postconditions (a runner must guarantee)
- The actuals file validates against `santa-eval.actuals.schema.json`.
- **Exactly one** result object per input entry, keyed by that entry's `name`. (Names are
  unique within a vector — the blesser appends `#<index>`.)
- **Success:** `{ "value": <SValue §4>, "cost": <number>, "error": null }`.
- **Failure:** `{ "value": null, "cost": null, "error": "errored" }`.
- **Not-implemented:** `{ "value": null, "cost": null, "error": "not-implemented" }` — the runner has no implementation for this op/method/type.
- **Panicked:** `{ "value": null, "cost": null, "error": "panicked", "note": "<message>" }` — an
  otherwise-uncaught internal error on this entry, caught so the run continues. This is also the
  landing for the implementation's **own** failure to represent or hold a value (e.g. its codec
  rejecting an out-of-range field): the runner records that real failure rather than pre-classifying
  it into a softer outcome on the implementation's behalf. Graded coal **unconditionally** (§5/§6),
  even against a reject-expected vector: a crash is not a clean rejection (hence a distinct tag, not a
  reuse of `errored`). `note` (present iff `error == "panicked"`) carries the message for diagnosis.
- **Value-success with cost not claimed:** `{ "value": <SValue>, "cost": null, "error": null }` — the
  runner evaluated the value but does not claim the **cost dimension** (e.g. an eval-only library).
  This is *scope*, declared in the manifest (`cost: false`), not abstention: the value is still graded.
- The runner **MUST NOT read the vector's `expected`** when producing actuals. Actuals are
  produced blind; otherwise the comparison is meaningless.

### Invariants (hold for any runner, any vector)
- **Determinism.** Same vector → byte-stable actuals on every run. No clocks, no RNG, no
  ambient state.
- **Totality & never-panic.** Every input entry yields exactly one outcome — success,
  `errored`, `not-implemented`, or `panicked`. A runner never silently
  drops or omits an entry, and **no single entry may abort the file.** An entry that fails in
  a *recognized* way (`errored`) is a normal outcome; any other failure the runner does **not**
  classify — a malformed vector, an internal error, or the implementation's own codec/representation
  failure on a value it cannot hold — is **caught and surfaced as `panicked`** (coal, message in
  `note`), never propagated as a process-aborting error. `panicked` is therefore not presumed rare:
  it is the faithful landing for any such throw. Surfacing it as a visible divergence beats both
  silently absorbing it and aborting the whole run.
- **Faithful outcomes — the runner never excuses the implementation.** A runner reports the
  implementation's *actual* behavior; it never authors a classification that softens a divergence on
  the implementation's behalf. An implementation's own failure — including failing to represent or
  hold a value its types nominally cover — is `errored` (a recognized eval failure) or `panicked`
  (any other throw), recorded as it happened. A gap in the **SANTA harness itself** (e.g. a bridge
  that cannot yet encode a value the implementation correctly produced) is `panicked` with a `note`
  naming the TODO — a defect to fix by making the harness total, never a standing "we don't test
  this" outcome. (This is why there is no `unrepresentable` tag: it had drifted into exactly such an
  excuse — the same anti-pattern as the removed `abstain`.)
- **No abstention; scope is an input-side selection.** A runner emits a faithful outcome
  for **every entry of every vector it is given** — including `not-implemented` (an
  op/method/type it doesn't implement) and `panicked` (an uncaught internal error, always coal).
  It never omits an entry or suppresses a would-diverge result to look
  conformant. A conformer expresses its scope by **which vectors it is run against**: the
  corpus is version-split (`vectors/eval/{v5,v6}/`), so a v5-only conformer runs the v5
  subset and never sees a v6 vector. Which entries "count" — and how they slice into
  v5/v6 or pass/gap tables — is a downstream **consumer's** judgment over the raw actuals
  plus each entry's `version`/`op`; it is never a runner behavior. `errored` still means
  *"I implement this op and the evaluation failed"* — never "unsupported"; that distinction
  is carried by `not-implemented`.
  Scope spans three axes: **version** (cumulative — a runner declares one and implies all lower),
  **tiers** (eval/wire/block — the result-shape it implements), and **dimensions** (value always;
  cost only if `cost: true`). A dimension the runner does not claim is not graded — distinct from
  abstaining on a vector it *does* run.
- **No oracle dependency.** Producing actuals requires only the vector plus the runner's
  own implementation — no JVM, no network, no access to the blesser.

## 4. Canonical value encoding (`SValue`)

This is the load-bearing part: because every implementation compares against the *same*
committed `expected`, the encoding must be pinned so two implementations cannot diverge on
representation alone. The encodings below are **normative**; a runner MUST emit exactly
these shapes. The asymmetries are deliberate — mirror them exactly.

| kind | JSON shape | notes |
|---|---|---|
| Boolean | `{"kind":"Boolean","value":<bool>}` | JSON boolean |
| Byte | `{"kind":"Byte","value":<number>}` | **bare JSON number**, −128..127 |
| Short | `{"kind":"Short","value":<number>}` | bare number, −32768..32767 |
| Int | `{"kind":"Int","value":<number>}` | bare number, 32-bit |
| **Long** | `{"kind":"Long","value":"<decimal>"}` | **decimal STRING** — exceeds JS safe-int range |
| BigInt | `{"kind":"BigInt","value":"<decimal>"}` | decimal string (signed, 256-bit) |
| UnsignedBigInt | `{"kind":"UnsignedBigInt","value":"<decimal>"}` | decimal string (unsigned, 256-bit) |
| GroupElement | `{"kind":"GroupElement","bytes_hex":"<hex>"}` | 33-byte SEC1, **lower-case** hex |
| SigmaProp | `{"kind":"SigmaProp","raw_hex":"<hex>"}` | serialized SigmaBoolean, lower-case hex |
| Box | `{"kind":"Box","bytes_hex":"<hex>"}` | `ErgoBox.sigmaSerializer` bytes, lower-case hex |
| Header | `{"kind":"Header","bytes_hex":"<hex>"}` | `ErgoHeader.sigmaSerializer` bytes (incl. PoW), lower-case hex |
| AvlTree | `{"kind":"AvlTree","bytes_hex":"<hex>"}` | `AvlTreeData.serializer` bytes (digest+flags+keyLength+optValueLen), lower-case hex |
| Coll | `{"kind":"Coll","elem":<SType>,"items":[<SValue>,…]}` | `elem` = element SType tag; `items` positional |
| Tuple | `{"kind":"Tuple","items":[<SValue>,…]}` | ≥2 items, positional. Today's corpus is all pairs; the encoding admits higher arity (sigma-state has arity-3+ tuples). |
| Option | `{"kind":"Option","value":<SValue> \| null}` | `null` = `None`; otherwise the inner SValue |

**The Long-vs-cost asymmetry (read this twice).** An SValue **`Long` value** is a decimal
**string**, but the entry's **`cost`** (§5) — also a 64-bit quantity — is a bare JSON
**number**. This is intentional: cost is bounded by the block cost limit (~10⁶), safely
inside IEEE-754 integer range; SValue `Long`/`BigInt` are not. A runner emits **cost as a
number** and **Long values as strings**.

**Hex is lower-case** everywhere (`bytes_hex`, `raw_hex`). **No `Opaque`/`SUnknown` may
appear in a committed vector** — the blesser refuses to bake an un-modeled kind (it
skips-and-reports instead), so a runner never needs to encode one.

### SType tags (the type side, used in `elem`)
`{"tag":"SBoolean"}`, `SByte`, `SShort`, `SInt`, `SLong`, `SBigInt`, `SUnsignedBigInt`,
`SGroupElement`, `SSigmaProp`, `SBox`, `SHeader`, `SPreHeader`, `SAvlTree`, `SUnit`, `SAny`; and the
recursive forms `{"tag":"SColl","elem":<SType>}`, `{"tag":"SOption","elem":<SType>}`,
`{"tag":"STuple","items":[<SType>,…]}`. These appear inside `Coll.elem` and nested type
positions; pinned in the schema.

## 5. The match (conformance comparison)

For each entry, the comparator computes the runner's actual `{value, cost, error}` and the
vector's blessed `expected` (same three-field shape), and decides **nice** iff they are
**deeply structurally equal**:

- **Objects:** key-order-INsensitive; identical key sets; values recursively equal.
- **Arrays:** order-SENSITIVE (Coll items and Tuple items are positional).
- **Numbers:** compared numerically; **strings:** exact (case-sensitive — and all
  contract hex is lower-case, so this is well-defined).
- `null` equals only `null`.

Comparison is **structural, never raw-string/byte** — this removes JSON key-order and
whitespace as drift sources, and is implementable identically in any language.

**Per-field bar:**
- **`value` and `cost` are graded as independent verdicts.** Value is always graded. Cost is graded
  only when the runner claims the cost dimension (`cost: true`); for a `cost: false` runner, cost is
  ignored (not nice, not coal — out of scope). Both remain consensus-critical when graded: no
  tolerance. A `cost: true` runner that emits `cost: null` on a value-success is **cost-not-implemented**
  (coal on the cost verdict), the value verdict unaffected.
- **`error` is compared as an exact string.** A committed `expected`'s `error` is always
  `null` (success) or `"errored"` (failure) — there is **no error taxonomy at the eval
  tier** (§7). An *actual* may also be `not-implemented` or `panicked` (§3); by
  construction those never appear in `expected`, so they match neither and score a lump of
  coal (see below).
- **On an errored entry, `cost` is `null`** (an aborted evaluation has no well-defined
  cost) and `value` is `null`. So a matched failure is `{null, null, "errored"}` on both
  sides; cost/value are not compared further once both errored.

A `not-implemented` actual matches only an `expected` carrying the identical `error` string.
Because the blessing oracle implements the full language and the bless-time
wire-encodability gate drops un-ingestable inputs, a committed `expected` is always
`success` or `errored` — so `not-implemented` never matches and always scores a lump of coal.
That is intentional: it is always a real finding (a coverage gap), surfaced rather than hidden.
`panicked` is coal **unconditionally** — unlike `errored`
it never matches a reject-expected `expected` (a crash is not a clean rejection), which is
why it is a distinct tag rather than a refinement of `errored`.

A vector is **nice** for a runner iff every entry is nice; one lump of coal makes the runner
**naughty** on that vector.

## 6. Comparator topology (no oracle dependency)

The JVM blesses (produces `expected`); those values are committed in the vectors as the
**source of truth**. **Conformance-time comparison requires no JVM.** Each runner compares
its own actuals against the committed `expected` in its own language and CI. The Scala
`Harness` shipped in `jvm-blesser/` is therefore *a reference comparator*, useful for
cross-checking, but **not** a required dependency for any other implementation. Because §5
pins "equal" precisely, a Scala comparator and a TypeScript comparator return the same
verdict on the same bytes — which is the whole point of freezing the encoding and the match
algorithm rather than trusting one implementation.

## 7. Future arms (generalization — NOT specified here)

The `run(vector) → actuals → compare-to-blessed-expected` shape is deliberately
tier-agnostic, but this document specifies **only the eval tier**. The following are named
non-goals, to be specified when each arm is actually built (do not implement against them):

- **Error taxonomy (reject arm, Phase 5).** The eval-tier `error` is intentionally coarse
  (`errored`/null). A field exercise across the JVM, sigma-rust, and ergots found that a
  *finer* taxonomy is not cleanly cross-referenceable without fragile error-message
  matching, so classification is deferred to the reject arm — where rejection *reason* is
  the actual subject. The `error` field is the slot that refinement will fill; adding
  classified reasons there is an additive change, not a breaking one.
  This is distinct from the `not-implemented` outcome tag (§3): that is
  a *coverage* axis — "the runner has no implementation for this op/method/type" — detected by
  each runner's own typed conditions, not by fragile cross-impl error-message matching. The
  deferred taxonomy concerns *why an attempted evaluation failed* (the `errored` case),
  which stays coarse at the eval tier.
- **Wire tier.** Result shape is a serialization round-trip outcome ("parsed structure" /
  "round-trip-ok"), not value+cost. A new `schema` discriminator + a new result-shape
  section; the eval contract is untouched.
- **Block tier.** Result shape is chain-validity of a captured block. Same: new schema, new
  section.

The `schema` field on every vector is the tier discriminator (`santa-eval/v2` today), so a
new arm slots in beside this one without changing it.

## 8. Worked example

A `santa-eval/v2` vector entry and a conforming runner's actual:

```jsonc
// vector entry (in vectors/eval/<op>.json → entries[…])
{
  "name": "5#0",
  "tree_bytes_hex": "…",
  "input":   { "kind": "Int", "value": 5 },
  "version": { "activated": 3, "ergoTree": 3 },
  "expected": { "value": { "kind": "Int", "value": 6 }, "cost": 36, "error": null }
}

// the runner's actuals file: { "<name>": { value, cost, error }, … }
{
  "5#0": { "value": { "kind": "Int", "value": 6 }, "cost": 36, "error": null }
}
```

Here `expected` and the actual for `"5#0"` are structurally equal → **nice**. Had the
runner produced `cost: 37`, or `value.value: 7`, that entry would be a **lump of coal**.
Had the runner produced `{ "value": null, "cost": null, "error": "errored" }` while the
vector expected a value, also a lump of coal (one side succeeded, the other failed).
