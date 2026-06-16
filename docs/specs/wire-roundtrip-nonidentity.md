# SANTA — Wire non-identity round-trip + the `ErgoTree` kind

> Promotes a **deferred wire arm**: a non-identity round-trip (`expected_bytes_hex` ≠ input)
> plus a new `ErgoTree` serializer `kind`. Additive to `santa-wire/v1` — **not** a new tier and
> **not** the reserved `santa-wire/v2` (`structural-assert`). The eval contract is untouched.
>
> Read order: [`wire-tier.md`](./wire-tier.md) → [`docs/contract/runner-contract-wire.md`](../contract/runner-contract-wire.md) → this.
> Motivating divergence + memory: `[[stypevar-utf8-roundtrip-tier]]`.

## What & why

A serialization fork is a consensus fork: a tree's serialized bytes drive its script hash, and
(transitively, via `propositionBytes`) a box's `boxId`. Two nodes that re-serialize the *same*
parsed tree to *different* bytes compute different ids — a network partition primitive.

The motivating case is the **STypeVar UTF-8 surrogate** fork (the 3rd STypeVar fork, after the
charset-acceptance and length-bound forks — see `[[stypevar-utf8-roundtrip-tier]]`,
`[[eq-cost-and-string-eq-reachability]]`). The JVM `TypeSerializer` lossy-decodes a type-var name
(`new String(bytes, UTF_8)` never throws → malformed bytes become U+FFFD); on **re-serialize** the
decoded name is re-encoded to canonical UTF-8. For the byte sequence `ed a0 80` (an ill-formed
UTF-8 encoding of a UTF-16 surrogate) the two ecosystems disagree:

| input | JVM (`new String`/re-encode) | sigma-rust (`from_utf8_lossy`) | |
|---|---|---|---|
| `ff` | `efbfbd` (1) | `efbfbd` (1) | ✓ |
| `e2 82` | `efbfbd` (1) | `efbfbd` (1) | ✓ |
| `c0 80` | `efbfbd efbfbd` (2) | `efbfbd efbfbd` (2) | ✓ |
| **`ed a0 80`** | **`efbfbd` (1)** | **`efbfbd efbfbd efbfbd` (3)** | **✗** |
| `61 ff 62` | `61 efbfbd 62` | `61 efbfbd 62` | ✓ |

Java collapses the whole attempted 3-byte sequence to one replacement char; Rust follows Unicode
"maximal subparts" (surrogate lead `ed` + two stray continuations → three). A tree carrying such a
type-var name re-serializes to different bytes → a script-hash fork between a sigma-rust node and a
JVM node.

**"Adversarial-only" is the threat model, not a mitigation.** Honest users never emit a malformed
type-var name; an attacker *wants* to. A craftable input that makes two implementations compute
different ids is exactly what a conformance suite exists to pin (`[[conformance-divergences-are-the-deliverable]]`).

### Why the existing wire tier can't express it

`santa-wire/v1` grades **round-trip to self**: the blessed expected *is* the entry's own
`bytes_hex` (`runner-contract-wire.md` §1; `santa-check` passes the entry to `grade_wire`, which
reads `expected.bytes_hex`). Byte-exactness here is inherently **non-identity** — the input is a
deliberately non-canonical byte sequence; the canonical output differs from it. Even the JVM does
not round-trip these inputs to themselves on the structural path. So the arm needs a blessed
`expected_bytes_hex` that differs from the input.

## The empirical finding (blesser-first gate, 2026-06-16)

Before designing, a throwaway spike (`jvm-blesser/.../spike/StypeVarUtf8RoundtripSpike.scala`,
untracked) ran each of the 5 trees through the JVM `ErgoTree` deserialize/re-serialize. It
**overturned the initial framing** and settled the design:

**The JVM `ErgoTree` has two serialize paths, and they disagree.**

- **`ErgoTree.bytes`** (the cached/lazy form — and `bytes` *is* `propositionBytes`, so this is the
  boxId path) **echoes the raw input verbatim** → identity. A JVM node relaying a received
  malformed-tree box *preserves* `ed a0 80`; it does not re-encode.
- **`ErgoTreeSerializer.serializeErgoTree(tree)`** (a structural recompute from the parsed tree)
  **re-encodes** the decoded name → `efbfbd`. This is where the 1-vs-3 fork lives.

Worked, for `ed a0 80` (name region `67 <len> <bytes>`; both occur twice — the `ValDef` tpeArg and
the `FuncValue` arg type):

```
input / .bytes (JVM cached):  1b1901040ad801d701016703 eda080 d901026703 eda080 72027300   (identity)
serializeErgoTree (JVM):      1b1901040ad801d701016703 efbfbd d901026703 efbfbd 72027300   (1 FFFD)
serializeErgoTree (sigma-rust, per table): …6709 efbfbd efbfbd efbfbd … (3 FFFD)            (the fork)
```

**Decision (user, 2026-06-16): bless the structural path (`serializeErgoTree`).** It is consistent
with how every other wire kind canonicalizes (`WireCanonicalize` uses the structural serializer
for Box/Constant/Transaction/SigmaBoolean), the bless actually canonicalizes (non-identity is
meaningful, not an echo no-op), and it pins the 1-vs-3 divergence directly. The cached `.bytes`
echo (an identity round-trip that would model the boxId-preserve behavior) was the alternative;
rejected for the structural path. See **Consensus-liveness note** for the boxId nuance this leaves.

## The capability — non-identity `expected_bytes_hex`

Additive, backward-compatible field on a wire entry (schema `santa-wire/v1`, unchanged
discriminator):

- **Vector entry** gains an **optional `expected_bytes_hex`**. Absent ⇒ today's round-trip-to-self
  (every existing vendored entry is untouched — identity is the default). Present ⇒ the runner is
  still fed `bytes_hex` as input, but the comparator grades its output against `expected_bytes_hex`.
- **`santa-check.grade_wire`** prefers `expected_bytes_hex` when present, else falls back to
  `bytes_hex` (the identity default). ~3 lines; the existing `hex_eq(actual.bytes_hex,
  expected.bytes_hex)` comparison is otherwise unchanged. `conform` already passes the whole entry
  as `expected`, so no `conform` change beyond the field read.
- **Runner contract / actuals: unchanged.** The runner still emits `{ bytes_hex, error }` and never
  reads expected — round-trip's *input* is `bytes_hex`; only the *grading reference* moves.

## The `ErgoTree` serializer kind

- **`kind: "ErgoTree"`** added to the wire `kind` enum (`{ Constant, Box, Transaction, Header,
  SigmaBoolean, ErgoTree }`). Selects a structural ErgoTree serialize: parse the bytes to a tree,
  re-serialize **from structure**.
- **Blesser path** (`WireCanonicalize`): `serializeErgoTree(LenientErgoTree.deserialize(bytes))`
  under the entry's `VersionContext`. `LenientErgoTree` (already present, `sigma.santa.*`) reaches
  the `checkType = false` deserialize so an arbitrary-root tree (our witnesses eval to `Int 5`, not
  a `SigmaProp`) parses; `serializeErgoTree` is the structural (non-cached) re-encode the spike
  validated.

### The load-bearing contract clause — structural, not cached

> **An `ErgoTree`-kind round-trip MUST re-serialize the parsed tree *from structure*, not emit a
> cached/preserved copy of the input bytes.** A serializer that returns the original deserialized
> bytes (the JVM's `ErgoTree.bytes`, sigma-rust's template-bytes cache, an echo runner) does not
> exercise the type/name re-encode the kind exists to test.

Without this, the JVM control (rudolph) would emit `.bytes` (the echo) and grade **differ** against
its own structural-canonical `expected_bytes_hex` — red on its own oracle. The clause is what makes
the kind well-defined; it also inherently disciplines the echo-cheat (`wire-tier.md` "Honest
limitations" §2) for this kind, since an echo of the non-canonical input never equals the canonical
expected.

## The first vector — `vectors/wire/v6/authored/STypeVar.name_utf8_roundtrip.json`

The wire complement to the eval accept vector
(`vectors/eval/v6/authored/STypeVar.name_utf8_leniency.json`): **same 5 inputs, different graded
dimension.** Eval grades the *value* (name erased at eval → `Int 5 @ 13`); wire grades the
*reserialized bytes* (name preserved → the fork). This is SANTA's **first authored wire file**
(all wire today is `vendored`); `version { activated: 3, ergoTree: 3 }` (v6 bucket — type-var
serialization needs v3), `source: "santa:authored-stypevar-name-utf8-roundtrip"`.

Five entries, `kind: "ErgoTree"`, each `bytes_hex` = the eval vector's `tree_bytes_hex` (raw spliced
name), each `expected_bytes_hex` = the JVM structural canonical from the spike:

| name | `bytes_hex` (input, raw spliced name) | `expected_bytes_hex` (JVM structural canonical) |
|---|---|---|
| `ff` | `1b1501040ad801d701016701ffd901026701ff72027300` | `1b1901040ad801d701016703efbfbdd901026703efbfbd72027300` |
| `e282` | `1b1701040ad801d701016702e282d901026702e28272027300` | `1b1901040ad801d701016703efbfbdd901026703efbfbd72027300` |
| `c080` | `1b1701040ad801d701016702c080d901026702c08072027300` | `1b1f01040ad801d701016706efbfbdefbfbdd901026706efbfbdefbfbd72027300` |
| `eda080` | `1b1901040ad801d701016703eda080d901026703eda08072027300` | `1b1901040ad801d701016703efbfbdd901026703efbfbd72027300` |
| `61ff62` | `1b1901040ad801d70101670361ff62d90102670361ff6272027300` | `1b1d01040ad801d70101670561efbfbd62d90102670561efbfbd6272027300` |

`ff`, `e2 82`, **and `eda080`** all carry the JVM-canonical single U+FFFD (`…6703efbfbd…`) — identical
`expected_bytes_hex` from distinct inputs (a clean canonicalize test); `c0 80`→2, `61 ff 62`→`61·1·62`.
The `eda080` fork is purely on the *Rust* side (`from_utf8_lossy` → 3), not the JVM expected.

**The vector is a three-way discriminator** — a runner must first *parse* the malformed name, so:
- **strict-reject** (ergots `fatal:true`; upstream sigma-rust pre-lossy = blitzen-develop today):
  parse fails → `errored` on **all 5** (red) until the impl adopts lossy decode;
- **lossy + JVM-matching re-encode** (rudolph): green on all 5;
- **lossy + divergent re-encode** (blitzen-eni, sigma-rust `from_utf8_lossy`): green on the 4
  controls, **red on `eda080`** (`…6709 efbfbd efbfbd efbfbd …` ≠ JVM `…6703 efbfbd …`) — the deliverable.

So at first grade: rudolph 5/5 green · blitzen-eni 4 green + `eda080` red · blitzen-develop & dasher
red on all 5 (strict-reject, until lossy) — or `not-implemented` if they have no `ErgoTree` arm yet.
Once ergots adopts lossy its `eda080` count is a third axis the same vector pins.

## Runner impact

- **rudolph** (JVM control): the `ErgoTree` arm routes through `WireCanonicalize`; green by
  construction (it *is* the blesser). One-file change in the JVM runner dispatch + `WireCanonicalize`.
- **blitzen-eni / blitzen-develop** (sigma-rust): add an `ErgoTree` wire arm — `ErgoTree::sigma_parse_bytes`
  → `sigma_serialize` (structural). **eni** (lossy) is green on the 4 controls, **red on `eda080`** (the
  finding); **develop** strict-rejects the malformed name today, so it errors on all 5 (exactly as it is
  red on the eval accept arm) until upstream adopts lossy, then it tracks eni. Edited in the canonical
  `~/projects/blitzen` clone (`[[pushing-blitzen-submodules]]`), develop→cherry-pick eni.
- **dasher** (ergots): add an `ErgoTree` wire arm **iff** ergots exposes a structural tree serializer
  (the v6 walker parses trees; whether it re-serializes from structure is unverified — confirm before
  routing). Else `not-implemented` (honest coverage). Routed via `prompts/`.
- **vixen** (arkadianet): route an `ErgoTree`-arm request; their finding to grade.
- **comet** (Fleet), **donner**/**vixen** block/chain arms: `not-implemented` for `ErgoTree` (Fleet treats
  trees as opaque hex; donner has no wire tier) — coverage, not a blocker. No silent skip.

## Schema / `tools/validate`

- `schema/santa-wire.vector.schema.json`: add `expected_bytes_hex` (optional, hex string) to the
  entry; add `ErgoTree` to the `kind` enum. `santa-wire.actuals` unchanged.
- `tools/validate`: the recursive walker already covers the file; add a check that
  `expected_bytes_hex`, when present, is lower-case hex; family/entry counts bump.

## Blesser (JVM) — `jvm-blesser/`

- `WireCanonicalize`: add the `case "ErgoTree" =>` structural path above.
- A generator `AuthoredSTypeVarNameUtf8Roundtrip` (+ `…Test`) mirroring `AuthoredSTypeVarNameUtf8`:
  reuse `splicedHex` for the 5 inputs, `WireCanonicalize`-canonicalize each for `expected_bytes_hex`,
  emit the `santa-wire/v1` envelope. The `…Test` anchors all 5 `expected_bytes_hex` (locks the exact
  bytes above) and re-blesses the file.

## Grading / `santa-check`

`grade_wire` prefers `expected_bytes_hex`:

```rust
let expected_hex = expected.get("expected_bytes_hex").or_else(|| expected.get("bytes_hex"));
// then hex_eq(actual.bytes_hex, expected_hex) as today
```

Add `oracle/verdicts-wire.json` meta-vectors for the non-identity case (roundtrip-differ where
`expected_bytes_hex` ≠ input; roundtrip-nice where the runner matches it), reproduced by
`tests/oracle.rs`.

## Build order (blesser-first is already done)

0. **Done:** the spike confirmed the structural path re-encodes and captured the exact bytes.
1. `WireCanonicalize` `ErgoTree` case + `AuthoredSTypeVarNameUtf8Roundtrip{,Test}` → write the vector (TDD: the test anchors the 5 expected).
2. schema (`expected_bytes_hex` + `ErgoTree`) + `tools/validate` bump.
3. `santa-check` `grade_wire` non-identity read + `oracle/verdicts-wire.json` cases.
4. rudolph `ErgoTree` wire arm (green). conform 7-way → rudolph green, others `not-implemented`.
5. blitzen-eni/develop `ErgoTree` arm (`~/projects/blitzen`, push, re-pin) → eni red on `eda080`.
6. Route dasher/vixen/comet (`prompts/`). Re-grade on ping.

## Gates / testing (TDD)

- **jvm:** `AuthoredSTypeVarNameUtf8RoundtripTest` (5 expected anchored), `WireCanonicalizeTest`
  (`ErgoTree` parse→structural-reserialize), `EvalConformanceTest`/`CoverageManifestTest` regen.
- **schema:** the two files validate; `tools/validate` count bumps.
- **santa-check:** `oracle/verdicts-wire.json` reproduced by `tests/oracle.rs`.
- **conform:** rudolph green on all 5; blitzen-eni red on `eda080`, green on 4; develop tracks eni.
- **docs:** `wire-tier.md` "Deferred" promotes this arm; `runner-contract-wire.md` §1/§5 gain the
  `expected_bytes_hex` field + the structural-not-cached clause (`[[update-docs-before-commit]]`).

## Consensus-liveness note (finding annotation, not a blocker)

The wire tier pins `serialize(parse(x))` **conformance** unconditionally — the impls demonstrably
disagree, and that is the deliverable. Whether the fork is consensus-**live** is a separate severity
question: the JVM's boxId path uses the cached `.bytes` (it *preserves* `ed a0 80`), so a live
fork requires an implementation to **re-encode on a path the JVM preserves** — its own boxId /
`propositionBytes` path, an indexer, or a re-serializing REST boundary (a surface `wire-tier.md`
already flags as finding-bearing). The sigma-rust table indicates sigma-rust re-encodes; a
box-level probe (does `ErgoBox.sigmaSerializer` round-trip preserve the embedded tree's bytes on
each impl?) would settle liveness if needed. Record this in `docs/findings/` when the arm lands.

## Out of scope / Deferred

- **The broader non-canonical-input class.** This arm is the first instance of "the JVM leniently
  accepts non-canonical bytes and re-canonicalizes them"; other instances (deserialize windows,
  the `ReplacedRule(0)` statusUpdates landmine) can reuse `expected_bytes_hex` as they arise.
- **`Box`-kind transitive coverage** of the same tree fork (a box carrying the malformed tree) —
  only if the box path re-encodes rather than preserves (the liveness probe above).
- **`santa-wire/v2` `structural-assert`** stays as in `wire-tier.md` (a different idea: parse → emit
  a canonical structural JSON form). This arm is byte-comparison, closer to v1.
