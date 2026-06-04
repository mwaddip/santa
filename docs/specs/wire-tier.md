# SANTA — Wire tier (`santa-wire/v1`, byte round-trip)

> Thin subspec opening the **wire tier** — a *parallel track*, not a numbered phase
> (SPEC.md "Tiers"). The eval contract is untouched; wire slots in beside it as a new
> `schema` discriminator with its own result shape (runner-contract `§7` reserves exactly
> this). Read order: SPEC.md "Tiers" → docs/contract/runner-contract.md `§7` → this.

## What & why

The wire tier asserts **bytes ⇄ structure** round-trip: parse a canonical byte sequence,
reserialize it, and require the result to be **byte-identical**. It is squarely consensus —
a `boxId` *is* `blake2b256` of a box's serialized bytes, so a serialization divergence is a
different `boxId`, a different transaction, a fork. And it is the **broadest** tier: every
wallet/SDK serializes while only a couple of libraries eval, so wire is the entry point for
serializer-only conformers (`scorex`, Fleet, wallets) that can never join the eval tier.

The cheapest assertion — **round-trip to self** — needs no shared structured form: the
blessed expected *is* the input bytes. A richer "parse → assert canonical structure" variant
exists (see Deferred); the round-trip is the spine.

## Approach: canonicalize, don't re-author

The JVM is the oracle (BOOTSTRAP decision 1; sigma-rust is the differential target, never
canonical). The wire blesser is a single uniform step:

```
candidate bytes ──parse via the JVM serializer for `kind`──▶ object ──reserialize──▶ JVM-canonical bytes
```

The committed vector carries the **JVM-canonical** bytes. Each entry records its own
**`source`** (per-entry, so one slice — e.g. `Box` — can hold vectors from several
frameworks), which places it under `vectors/wire/<version>/<provenance>/`:

- **`vendored`** — harvested from another framework's serializer test corpus and re-anchored
  through the JVM. Two sources today: **ergots** `fixture-gen/.../wire/` (`sbox_roundtrip`,
  `sigma_boolean_variants`) and **Fleet** `packages/serializer/src/_test-vectors/`
  (`signedTransactions`, `constantVectors`, `boxVectors`). Those bytes are produced by the
  framework's own serializer (sigma-rust for ergots, Fleet's scorex reimpl), so feeding them
  through the JVM both re-anchors them to the oracle and makes each harvest a
  **JVM-vs-that-framework wire diff**: where the JVM reserializes differently the JVM bytes
  win and the divergence is a finding (`docs/findings/`); where the JVM can't parse a fixture
  it is logged and excluded (a reject seed). Box round-trips from ergots **and** Fleet share
  one `vendored/Box.json` slice, each entry tagged by its `source`.
- **`authored`** — our own manually-curated cases filling spec gaps: real testnet
  `Transaction`/`Header`/box **captures** (the rust-node session gathers these from the REST
  endpoint, routed via `prompts/`; `testnet:` source), plus any hand-built vectors. Captures
  are consensus-canonical, so the JVM canonicalize is normally a confirmation; a mismatch
  (e.g. a node re-serializing at the REST boundary) is itself a finding. Captures are how
  **tx and header** coverage from real chain data enters.
- **`spec`** — extracted from an official serialization specification. None today:
  serialization has no enumerable spec the way eval has `LanguageSpecificationV5/V6`, so
  `wire/*/spec/` stays an empty slot.

The provenances differ only in *where the candidate bytes come from*; the blesser, schema, and
runner contract are identical.

## Format — `santa-wire/v1`

New schemas (siblings of the eval pair, NOT an extension of them):
`schema/santa-wire.vector.schema.json` + `schema/santa-wire.actuals.schema.json`.

```jsonc
// vector: vectors/wire/v5/vendored/Box.json
{
  "schema": "santa-wire/v1",
  "op": "Box",                              // slice label (file-level), like eval's `op`
  "blessed_by": "jvm:sigma-state-6.0.3",    // the JVM canonicalizer, always
  "entries": [{
    "name": "sbox_minimal",
    "kind": "Box",                          // serializer selector (entry-level); see below
    "source": "ergots:fixture-gen/wire",    // candidate origin, PER ENTRY (a Box slice mixes ergots + fleet)
    "description": "value=1_000_000, no tokens, no registers, height=0, index=0",
    "bytes_hex": "80897a1000...",           // the JVM-CANONICAL bytes; expected = these, round-trip-to-self
    "version": { "activated": 2, "ergoTree": 2 }
  }]
}
```

```jsonc
// a runner's actuals (keyed by entry name, like eval):
{
  "sbox_minimal": { "bytes_hex": "80897a1000...", "error": null }   // reserialized; nice iff == vector bytes_hex
}
```

- **`kind`** is the serializer selector the runner dispatches on — initial set
  `{ Constant, Box, Transaction, Header, SigmaBoolean }`, extensible. `Constant` parses via the
  SValue `DataSerializer` (the inner type is recoverable from the bytes' type prefix); `Box` via
  `ErgoBox.sigmaSerializer`; `Transaction` via `ErgoLikeTransaction.serializer`; `Header` via
  `ErgoHeader.sigmaSerializer`; `SigmaBoolean` via the sigma serializer.
- **Expected is implicit — round-trip to self.** There is no `expected` field: the entry's
  `bytes_hex` *is* the blessed expected. The comparator grades **nice** iff the runner's
  `actuals[name].bytes_hex == entry.bytes_hex` and `error == null`. (This intentionally collapses
  the eval contract's "runner never reads `expected`" separation — round-trip's answer *is* its
  input; see Honest limitations.)
- **`version`** reuses the eval shape `{ activated, ergoTree }` — serialization is version-gated
  (e.g. `SHeader` constants and v3 trees require `ergoTree ≥ 3`), so a vector declares the version
  under which its bytes are canonical, and the runner reserializes under it. The taxonomy bucket
  (`v5`/`v6`) is the max version surface the file exercises, mirroring eval.
- **`blessed_by`** is always the JVM canonicalizer for both provenances; **`source`** records the
  candidate origin (`ergots:fixture-gen/wire/<module>` or `testnet:capture@<height-range>`).

## Wire result shape (runner contract)

The eval contract (`runner-contract.md`) stays **frozen**; the wire result shape is its own
companion (`docs/contract/runner-contract-wire.md`). It mirrors the eval
contract's totality model with `value`+`cost` replaced by a single `bytes_hex`:

- **Success:** `{ "bytes_hex": "<reserialization>", "error": null }`.
- **Failure (recognized):** `{ "bytes_hex": null, "error": "errored" }` — parse/reserialize threw.
- **Not-implemented:** `{ "bytes_hex": null, "error": "not-implemented" }` — no serializer for this `kind`.
- **Panicked:** `{ "bytes_hex": null, "error": "panicked", "note": "<message>" }` — any other uncaught
  throw, caught so the run continues (always coal). A serializer's **own** failure on a value it cannot
  hold lands here too — recorded faithfully, never pre-classified into an "excuse" tag.
- **Totality** holds exactly as eval `§3`: one faithful outcome per entry, never dropped; an
  unrecognized failure is caught as `panicked`, never mislabeled.
- **No cost dimension.** Wire grades a single **`roundtrip`** verdict per entry. A serialization
  divergence is `{ bytes_hex: <different>, error: null }` — the analog of an eval value-mismatch:
  the runner produced bytes, they just aren't canonical → **coal** (the deliverable). `not-implemented`
  is coal exactly as eval `§5` (always a real coverage finding).
- A wire-only conformer declares `tiers: [wire]` and is inherently cost-less (`cost` is not a wire
  concept) — scope selection (`runner-integration.md`) is otherwise unchanged.

## Blesser (JVM) — `jvm-blesser/`

- A `WireCanonicalize` core (TEST scope, parallel to `EvalCore`/`AuthoredSerialize`): given
  `(kind, candidateBytesHex, version)` it parses with the `kind`'s JVM serializer under the
  declared `VersionContext`, reserializes, and returns JVM-canonical bytes — or a coarse outcome
  (parse-fail). All serializers it needs already back eval SValue encodings (`ErgoBox`,
  `ErgoHeader`, `DataSerializer` per runner-contract `§4`; `ErgoLikeTransaction.serializer` from
  sigma-state main).
- Per-framework drivers `VendoredWireErgots` + `VendoredWireFleet` read each framework's vendored
  seeds (ergots' `fixture-gen/wire/*` and Fleet's `_test-vectors/*`, vendored under
  `src/test/resources/{ergots,fleet}-wire/`), canonicalize each `bytes_hex`, and stamp a per-entry
  `source`. A `VendoredWire` assembler merges them by op (so `Box` unions both frameworks) and
  `VendoredWireTest` writes `vectors/wire/<v>/vendored/<op>.json`. At bless time each diffs
  JVM-canonical vs the framework candidate and reports any mismatch (a finding) — the bless run is
  itself a JVM-vs-framework pass.
- Captured candidates are canonicalized by the same `WireCanonicalize`; the capture-side gathering
  is the rust-node session's job (separate `prompts/` spec, this schema as its contract).

## Schema gate / conform / santa-check / scoreboard

- **Schema:** two new files validated by `tools/validate` (the recursive walker already covers new
  files). `santa-wire.vector` pins the envelope + entry (`kind` enum, `bytes_hex` hex, `version`);
  `santa-wire.actuals` pins `{ bytes_hex|null, error }` with the eval-style `error`-null ⇔
  bytes-present invariant.
- **santa-check:** a wire branch keyed on the `santa-wire/v1` discriminator — grade = `bytes_hex`
  string-equality (lower-case, exact) + `error` match, a single `roundtrip` verdict (no value/cost
  split). Adds a sibling `oracle/verdicts-wire.json` (`santa-oracle-wire/v1`) of wire meta-vectors
  (round-trip-ok / bytes-differ / not-impl / panicked) — a sibling, not an extension of
  `verdicts.json`, mirroring the wire *schemas* being siblings of the eval pair.
- **conform / results.json:** wire vectors grade alongside eval; `results.json` gains wire slices
  (dimension `roundtrip`). Runners declare `tiers` including `wire`; presence-as-state unchanged.
- **scoreboard:** wire rows render green (round-trip-ok) / red (N bytes-differ) / grey (out of
  scope); the value/cost/amber legend collapses to a single round-trip verdict for wire cells.

## Vectors / taxonomy

`vectors/wire/<version>/<provenance>/<op>.json` — mirrors `vectors/eval/<version>/<provenance>/`.
First files: `wire/v5/vendored/{Box,SigmaBoolean,Transaction,Constant}.json` (ergots + Fleet
harvest; Box unions both frameworks); `wire/{v5,v6}/authored/{Header,…}.json` (rust-node captures,
`testnet:` source).

## Honest limitations (round-trip to self)

Round-trip-to-self is the spine, and it has two blind spots — named here, not hidden:

1. **Misparse-that-round-trips.** A runner could parse a field wrong yet write it back identically;
   the bytes match, the structure is wrong. This only bites where the parsed structure is *consumed*
   (registers/values feeding eval) — which the eval tier already covers.
2. **Echo-cheat.** A runner that returns its input unparsed passes every round-trip vector. The tier
   cannot observe the intermediate structure (that is what makes it structure-free), so it relies on
   the runner genuinely round-tripping. This is acceptable: a conformer's author *wants* to surface
   their own serializer's bugs, and the **canonicalize-bless already catches serialize-side
   divergences** (JVM-vs-sigma-rust) before any runner runs.

Both blind spots are closed by the Deferred arms below — `structural-assert` (catches misparse) and
the wire reject/mutation arm (malformed bytes a real parser must reject — an echo-runner can't).

## Build order

0. Schemas (`santa-wire.{vector,actuals}`) + `tools/validate` coverage.
1. `WireCanonicalize` + the `VendoredWire{Ergots,Fleet}` harvesters + `VendoredWire` assembler;
   harvest ergots + Fleet → `wire/v5/vendored/*`, anchored TDD (the `VendoredWire*Test`s pin each
   canonical `bytes_hex` + any JVM-vs-framework finding).
2. santa-check wire branch + `oracle/verdicts-wire.json` wire meta-vectors; conform/results wire slices.
3. Wire runner shape on the serializers already wired — rudolph (control, trivially green: it *is*
   the canonicalizer), dasher (`@ergots/ergoscript` + `@ergots/scorex`), blitzen-eni/develop
   (`sigma_parse`/`sigma_serialize`). Scoreboard wire cells.
4. Route the capture spec to the rust-node session (`prompts/`); land `wire/*/captured/*` as txs/
   headers arrive.

## Gates / testing (TDD)

- **jvm:** `VendoredWireErgotsTest` / `VendoredWireFleetTest` / `VendoredWireTest` (anchor canonical
  bytes, per-framework + merged counts, divergence findings); `WireCanonicalizeTest` (parse→reserialize per `kind`).
- **schema:** the two new files validate; `tools/validate` count bumps.
- **conform:** wire slices grade across rudolph/dasher/blitzen×2; rudolph all round-trip-ok.
- **santa-check:** `oracle/verdicts-wire.json` reproduced by `tests/oracle.rs` (alongside the eval oracle).

## Out of scope / Deferred

- **`structural-assert` variant** (`santa-wire/v2`): parse → emit a canonical, language-agnostic
  structural form (box/tree/tx/header as JSON) → compare. Catches misparse-that-round-trips; needs a
  new cross-language structural contract. Additive — `santa-wire/v1` round-trip stays.
- **Wire reject/mutation arm:** malformed/non-canonical byte inputs a correct parser must reject
  (disciplines the echo-cheat). The wire analog of the eval reject arm (Phase 5).
- **`scorex` + Fleet runners:** the serializer-only conformers this tier unlocks; wired after the
  round-trip tier is proven on the existing serializers.
- **VLQ-primitive vectors:** sub-constant primitives (VLQ/ZigZag) — covered transitively by
  Constant/Box round-trip for now; promote to their own `kind` only if a divergence demands it.
