# Phase 2 — Stage 1.5: UnsignedBigInt + Option vocabulary

Recover the **36 cases** Stage 1 loudly skipped because `EvalCore`'s value codec
doesn't model two v6.0 value kinds. Umbrella: [SPEC.md](../../SPEC.md); predecessor:
[phase-2-eval-scale.md](phase-2-eval-scale.md) (Stage 1). Thin, just-in-time per the
working method — written after Stage 1's delivery, informed by its skip inventory.

## Why / what

Stage 1 extracted 192 `santa-eval/v2` vectors and **skipped 36 cases** whose input or
expected-value is a kind `valueToJson` emits as `{kind:"Opaque"}` (the extractor's
recursive `hasOpaque` gate refuses to bake a guessed encoding — skip-and-log instead).
Inventory **verified 2026-05-31** by re-running the extractor at `712c277`:

| Kind | Skips | Ops |
|---|---|---|
| **UnsignedBigInt** (`CUnsignedBigInt`) | 32 | `UnsignedBigInt methods` (22), `GroupElement.expUnsigned` (3), `Global.fromBigEndianBytes` (3), `BigInt.toUnsigned` (2), `Global.powHit` (1), `BigInt.toUnsignedMod` (1) |
| **Option** (`Some`/`None`) | 4 | `Coll.get` (3 — 2×Some, 1×None), `Option.getOrElse` (1×Some) |

UnsignedBigInt also appears **nested in Tuples** (e.g. `(GroupElement, UnsignedBigInt)`
for `expUnsigned`); the existing Coll/Tuple recursion in the codec covers those for free
once the base kind lands.

## JSON encoding (the data contract)

**UnsignedBigInt** — decimal string, exactly mirroring `BigInt`:
```json
{ "kind": "UnsignedBigInt", "value": "<decimal>" }
```
SType tag: `{"tag":"SUnsignedBigInt"}`.

**Option** — **no `elem` field** (decided 2026-05-31). A runtime `None` is the untyped
Scala singleton and carries no element type, so an `elem` would be information that
doesn't exist at the value level (unlike an empty `Coll`, which keeps its element RType).
The element type of an Option is invisible to eval-tier conformance anyway, so we don't
synthesize it:
```json
Some(x) → { "kind": "Option", "value": <SValue of x> }   // type implied by inner value
None    → { "kind": "Option", "value": null }
```
SType tag: `{"tag":"SOption","elem":<SType>}` (already emitted by `stypeToJson`; the
`elem` lives on the *type* tag, where it IS known — not on the *value*).

## Surfaces to touch (all in `EvalCore.scala`)

**UnsignedBigInt — five surfaces** (mechanical; mirror the existing `BigInt` arm):
1. `valueToJson` — `case u: CUnsignedBigInt` → decimal string. (Confirm the accessor —
   `wrappedValue` vs `toBigInteger` — at impl time; `CBigInt` uses `.wrappedValue`.)
2. `stypeToJson` — `case SUnsignedBigInt => tag("SUnsignedBigInt")`.
3. `stypeFromJson` — `case "SUnsignedBigInt" => SUnsignedBigInt`.
4. `decodeInputConstant` — `case "UnsignedBigInt"` → `UnsignedBigIntConstant(<BigInteger>)`.
5. `decodeColl` — `case SUnsignedBigInt` (for `Coll[UnsignedBigInt]`).

**Option — fewer surfaces, deliberately asymmetric:**
1. `valueToJson` — `case opt: Option[_]` → `Some`/`None` per the encoding above. (Place
   before the `other` fallback; Option overlaps neither `Coll[_]` nor the `(a,b)` pair.)
2. `stypeToJson` — **already emits `SOption`** (no change).
3. `stypeFromJson` — `case "SOption"` → `SOption(stypeFromJson(elem))` (for Option nested
   as a Coll/Tuple element type).
4. `decodeInputConstant` — `case "Option"`: **`Some` only** (decode inner, wrap as
   `Constant(Some(<innerRuntimeValue>), SOption(<innerType>))`). A `None`-as-**input**
   errors loudly — it's untyped and unreconstructable without an explicit `elem`, and no
   skipped case needs it (the lone `None` is an *output*). If a future corpus needs
   None-input, add an explicit `elem` to the input JSON then — not pre-emptively.
5. `decodeColl` — add `SOption` only if `Coll[Option[_]]` actually appears; otherwise the
   existing loud-error default stands (never a silent guess).

## TDD discipline (non-negotiable)

Per kind, an **end-to-end eval-back round-trip** test in `EvalAppliedTest` — the guard
that caught the Stage-1 Tuple→silent-Coll bug. Pattern: build the identity tree
`{ getVar[T](1).get }` (`idTreeHex(tpe)` = `OptionGet(GetVar(1, tpe))`), bind the decoded
input to var 1, eval, assert `valueToJson(result).noSpaces == inputJson.noSpaces`.

- UnsignedBigInt: `tpe = SUnsignedBigInt`, a representative decimal (incl. a large value).
- Option: `tpe = SOption(SInt)`, input `Some(5)` — verifies sigma-state accepts a Scala
  `Some` as an `SOption` constant value and that `getVar[Option[Int]](1).get` round-trips.
  This is the genuinely novel reconstruction; UnsignedBigInt is mechanical by comparison.
- Keep the range/overflow-style negative guards where they apply (e.g. malformed decimal).

## Verification / done criteria

1. `sbt test` green — incl. the new eval-back tests **and** the `EvalConformanceTest`
   cross-check (every committed vector still re-blesses).
2. Re-run the extractor (`testOnly santa.V6ExtractorTest`): `skippedUnsupportedKind`
   drops to **0**; `captured` rises by ~36 (192 → ~228) across the ~7 ops above.
   `propertyFailures` stays empty; cost diagnostics unchanged.
3. Commit the recovered `vectors/eval/*.json`; the extractor reproduces them
   byte-identically on a re-run.
4. Update `SPEC.md` (corpus counts) + `phase-2-eval-scale.md` status; per the
   `update-docs-before-commit` rule, before the commit.

## Out of scope

- `skippedError` (25, error-expected) — Stage-1 policy, unchanged.
- `skippedContext` (8, Context/Box/Header input) — **Stage 2**.
- `None`-as-input — deferred until a corpus needs it (see surface 4).

## Status

**Delivered 2026-05-31.** UnsignedBigInt (commit `7eafea2`) + Option (`2574508`) codec
landed; recovered vectors committed (`6489baf`). Final corpus: **235 `santa-eval/v2`
vectors across 29 ops** (+43 vs Stage 1's 192), `skippedUnsupportedKind` **0** (was 36),
cross-check **241/241** re-blessed across 30 files. Both kinds modeled per the contract
above — Option carries no `elem` (decode is Some-only; None-as-input errors loudly).
Built subagent-driven + TDD + two-stage review per the method.
