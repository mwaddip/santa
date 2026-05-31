# Phase 2 — Eval tier scaled (JVM-native vectors)

Scale *the nice list* from one op to a broad eval corpus drawn from the **canonical JVM
reference's own test suite** — `sigma-state`'s `LanguageSpecificationV6` — rather than
re-blessing fork-authored fixtures. Umbrella: [SPEC.md](../../SPEC.md). This supersedes
the subspec's first draft (which sourced `fixture-gen` fixtures and used the fork's
expecteds as a test oracle — see *Why the pivot*).

## Why the pivot

The first draft blessed the fork-authored `fixture-gen` fixtures and asserted JVM output
`== fixture.expected_*`. But those expecteds are **sigma-rust-computed** (`fixture-gen`
runs the fork as its generation oracle), and per BOOTSTRAP decision 2 the fork is a
*differential target, never the oracle*. That assertion inverted the oracle relationship
and surfaced 5 genuine JVM-vs-fork divergences
([findings](../findings/eval-jvm-vs-sigma-rust.md)) as test failures. The fix: source
vectors from the JVM's *own* spec, where the expecteds are canonical by construction.

## The canonical source

`sigma/LanguageSpecificationV6.scala` (sigma-state's executable v6.0 language spec):
**279 `verifyCases`**, version-aware. Each is a `Feature[A, B]` — a *function* `A => B`
(`newF.compiledTree` is the function ErgoTree) — exercised over deterministic
`cases: Seq[(input: A, Expected[B])]`, where `Expected` carries the output value and its
eval `CostDetails`. Reachable via the published **tests-classifier jar**
(`sigma-state…-tests.jar`, on Maven Central, HTTP 200), so extraction runs entirely in
SANTA — no cross-repo edits.

## The eval-tier vector — `santa-eval/v2` (input-carrying)

The reference models eval as **a function applied to an input**, so the vector does too —
**cost fidelity is the reason** (baking inputs into closed trees would drift the JIT cost
away from the canonical function-apply cost):

```json
{
  "schema": "santa-eval/v2",
  "op": "<feature>",
  "blessed_by": "jvm:sigma-state-6.0.3",
  "source": "sigma-state:LanguageSpecificationV6",
  "entries": [
    {
      "name": "<input.toString>#<index>",
      "script": "<Feature.script — the ErgoScript source string for this entry>",
      "tree_bytes_hex": "<serialized function ErgoTree (A => B)>",
      "input": { "kind": "…", "…": "…" },
      "version": { "activated": 3, "ergoTree": 3 },
      "expected": { "value": { "kind": "…" }, "cost": 0, "error": null }
    }
  ]
}
```

Entry fields:

- `name` — `"<input.toString>#<index>"` where `index` is the 0-based position within this op's emitted entries. The `#index` suffix guarantees uniqueness: multi-feature properties fan one case-set across several features, so the raw `input.toString` repeats; a consumer keying by `name` within an op's entry list must not collide.
- `script` — the `Feature.script` string from `LanguageSpecificationV6`: the ErgoScript source expression for this feature variant (provenance). Additive — carries no semantic weight for conformance checking, but lets a reader trace each entry back to the V6 spec line that sourced it.
- `tree_bytes_hex` — the serialized function `ErgoTree (A => B)`, hex-encoded. One distinct value per distinct `Feature` (different `script` → different tree).
- `input` — SValue JSON; same encoding as `expected.value`.
- `version` — `{"activated": 3, "ergoTree": 3}` for all Stage-1 v2 vectors (pinned to V6 soft-fork).
- `expected.cost` — raw JIT eval cost of applying the function to the input (`CErgoTreeEvaluator`).
- `expected.error` — `null` for success cases (Stage-1 skips error-expected cases).

- `input` uses the **same SValue encoding** as `expected.value` — the value vocabulary
  serves both. `input: null` is the closed-tree case (Phase-1 `v1` is this special form;
  `decode-point` re-emits trivially under v2).
- `expected.cost` is the **raw JIT eval cost** of applying the function to the input.
  SANTA re-blesses via its own eval, reproducing V6's `CostDetails` — a built-in
  cross-check, since SANTA *is* sigma-state 6.0.3.
- `expected.error` — coarse `null | "errored"` (unchanged).

## Deliverables

1. **Tests-jar dependency** — `sigma-state % Test classifier "tests"` (+ transitive test
   deps); confirm it bundles the `sc`-module specs incl. `LanguageSpecificationV6`.
2. **V6 extractor** — a SANTA test-scope harness subclassing `LanguageSpecificationV6`,
   tapping `verifyCases` to capture, per deterministic case: the serialized function
   tree, the input (as SValue), and the expected value + JIT cost. Skips the
   property-generated samples (committed vectors must be deterministic). Emits `v2`.
3. **SValue codec** — `valueToJson` (encode — carried over) **plus a decoder** (SValue
   JSON → JVM value) so the runner can reconstruct inputs.
4. **Apply-eval** — `EvalCore` gains "apply a function tree to an input value → (value,
   JIT cost)" alongside the current closed-tree eval.
5. **Runner (v2)** — consumes a v2 vector: reconstruct input, apply, normalize result.
6. **The committed corpus** — the extracted canonical `vectors/eval/*.json` (v2).

## What carries over / what changes

- **Carries over:** the value/`SType` encoders in `EvalCore.valueToJson` / `stypeToJson`
  (primitives, BigInt, SigmaProp, Coll, Tuple) — now also encode *inputs*; the version
  handling + `CostAccumulator` JIT-cost mechanics.
- **Changes:** source (V6, not fork fixtures); schema (`v2`, input-carrying); the test
  oracle (V6's own expected, canonical — no fork); eval mechanics (apply, not just closed
  eval). The fork-oracle harness from the first draft is dropped.

## Two stages

`SigmaDslTesting` special-cases `input: CContext`, which splits the features cleanly:

- **Stage 1 — value-input features**: inputs are plain SValues (Boolean / numeric / Coll
  / Tuple / …). The bulk of arithmetic, logic, collection, crypto ops. Needs
  deliverables 1–6 with a plain-value input codec.
- **Stage 2 — context-input features**: inputs that *are* a `Context` / `Box` / `Header`.
  The Stage-2 scoping investigation (2026-05-31) split this in two: **2a** (Box/Header —
  plain values bound to context var 1, same path as Stage 1; no reconstruction) and **2b**
  (Context — the lone genuine "context reconstruction" case, `getVarFromInput`). See
  [phase-2-stage-2a-box-header.md](phase-2-stage-2a-box-header.md).

## Out of scope

- The first independent runner (ergots) — thread B, routed to the ergots repo.
- Wire / block tiers; reject arm; CI.
- Filing the recorded JVM-vs-fork [findings](../findings/eval-jvm-vs-sigma-rust.md) as
  sigma-rust PRs — revisit once the eval tier stabilizes.

## Status

**Stage 1 delivered — 192 `santa-eval/v2` vectors across 22 ops, cross-check green.**
**Stage 1.5 delivered** (see [phase-2-stage-1.5-unsignedbigint-option.md](phase-2-stage-1.5-unsignedbigint-option.md)) —
UnsignedBigInt + Option codec added; corpus grew to 235 vectors across 29 ops, skip count 0.
**Stage 2a delivered** (see [phase-2-stage-2a-box-header.md](phase-2-stage-2a-box-header.md)) —
Box + Header value codec added; corpus **239 vectors across 32 ops**, cross-check **245/245
across 33 files**. The context-input skip dropped 8 → 4 (the remaining 4 are all
`getVarFromInput`, a `Context` input → Stage 2b).
`SPEC.md`'s eval-tier contract reflects v2 reality. Stage 2b (Context-input features) and
the first independent runner (ergots) remain open.
