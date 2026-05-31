# Phase 2 — Stage 2a: Box + Header input vocabulary

Recover **4 of the 8 cases** Stage 1 loudly skipped under "context/box/header input"
— the ones whose input is a `Box` or `Header` (a plain value bindable to context
var 1). Umbrella: [SPEC.md](../../SPEC.md); predecessor:
[phase-2-stage-1.5-unsignedbigint-option.md](phase-2-stage-1.5-unsignedbigint-option.md).
Thin, just-in-time per the working method — written after the Stage-2 scoping
investigation (2026-05-31), which split the 8 context skips into a value-codec half
(this spec) and a real-context half ([Stage 2b](#out-of-scope), gated on a spike).

## Why / what — the split

The extractor's `isContextInput` skip lumps three runtime classes together. The
scoping investigation enumerated the 8 skipped **cases** to **4 ops**, and read each
op's compiled tree + the reference framework's binding path:

| op (property) | input | script | cases | binding |
|---|---|---|---|---|
| `Box properties equivalence (new features)` | `CBox` | `{ (x: Box) => x.getReg[Long](0).get }` | 1 | var 1 |
| `Header new methods` | `CHeader` | `{ (x: Header) => x.checkPow }` | 1 | var 1 |
| `Global.deserializeTo - header` | `CHeader` | `{ (x: Header) => Global.deserializeTo[Header](serialize(x)) == x }` | 2 | var 1 |
| `getVarFromInput` | `CContext` | `{ (x: Context) => x.getVarFromInput[Boolean](0, 11) }` | 4 | **Stage 2b** |

**Key finding (the reason this is 2a, not "Stage 2").** All three Box/Header ops
compile to the *same wrapper SANTA already evaluates* —
`Apply(FuncValue((1,S…),body), OptionGet(GetVar(1,Option[S…])))` — and their bodies
read **only the bound value** (`ExtractRegisterAs(ValUse(1,SBox),R0,…)`,
`MethodCall(ValUse(1,SHeader),checkPow)`, serialize/deserialize/compare). No body
touches a context root (no `Height`/`Inputs`/`Self`/`Outputs`). So a `Box`/`Header`
is a **plain value bound to context var 1**, exactly as the reference framework does
it (`CompilerTestingCommons.createContexts` `case _`:
`.withBindings(1.toByte -> Constant[SType](in, tpeA))` against a dummy box/context —
the same path SANTA's `contextWithVar1` already takes for GroupElement/Coll/Tuple).
A new value type in a proven path. **Context is different** (its value bound to var 1
is an entire context; the reference bypasses the ContextExtension path via
`ctx.copy(vars=…)`) → deferred to 2b behind a mechanism spike. See
[`dont-build-against-the-unbuilt`].

## JSON encoding (the data contract)

Both mirror `GroupElement` — the canonical serialized bytes as hex (confirmed
round-trippable: `ErgoBox`/`ErgoHeader` `bytes` is either the deserializer's exact
`_bytes` or a re-serialization of the same fields, so encode∘decode is identity):

```json
{ "kind": "Box",    "bytes_hex": "<ErgoBox.sigmaSerializer bytes>" }
{ "kind": "Header", "bytes_hex": "<ErgoHeader.sigmaSerializer bytes, incl. PoW>" }
```

SType tags `{"tag":"SBox"}` / `{"tag":"SHeader"}` are **already emitted** by
`stypeToJson` (EvalCore.scala:97-98) — no type-side change. The Header byte form
is the **full** header (with the Autolykos solution): `ErgoHeader.sigmaSerializer`
serializes the PoW (ErgoHeader.scala:157-165), which `checkPow` requires —
`serializeWithoutPoW` would be wrong here.

## Surfaces to touch

**`EvalCore.scala` — value codec (2 surfaces × 2 kinds):**

1. `valueToJson` — add `case b: CBox` → `{kind:"Box", bytes_hex: Base16.encode(b.ebox.bytes)}`
   and `case h: CHeader` → `{kind:"Header", bytes_hex: Base16.encode(h.ergoHeader.bytes)}`.
   Match the **concrete** wrappers (as `CBigInt`/`CUnsignedBigInt`/`CSigmaProp` already
   do); `ebox.bytes` / `ergoHeader.bytes` are `Array[Byte]` (no Coll round-trip).
   Place before the `other` fallback; neither overlaps `Coll[_]`, the `(a,b)` pair, nor `Option[_]`.
2. `decodeInputConstant` — add `case "Box"`: decode hex →
   `ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(bytes))` → `BoxConstant(CBox(ebox))`;
   and `case "Header"`: → `ErgoHeader.sigmaSerializer.parse(SigmaSerializer.startReader(bytes))`
   → `HeaderConstant(new CHeader(ergoHeader))`. (`BoxConstant`/`HeaderConstant` take the
   trait `Box`/`Header`; values.scala:527/567. `startReader` is the same entry-point the
   `GroupElement` arm already uses.)

New imports in `EvalCore.scala`: `sigma.data.{CBox, CHeader}`,
`sigma.ast.{BoxConstant, HeaderConstant}`, `org.ergoplatform.ErgoHeader`
(`ErgoBox`, `SigmaSerializer`, `Header` already imported).

**`V6Extractor.scala` — narrow the skip (1 surface):** `isContextInput` (line 125-126)
currently matches `Context || Box || Header`. Narrow it to **Context only**
(`input.isInstanceOf[Context]`); rename to `isContextOnly` and update the comment +
the `skippedContextReasons` wording. Box/Header inputs then fall through to the normal
capture path. (The Stage-2 diagnostic plumbing — `skippedContextReasons` /
`seenContextTreeClasses` / the `[ctx-tree]` dump — added during scoping stays; it now
reports the 4 remaining Context cases.)

**Dead-code follow-through (Rule 1):** dropping `Box`/`Header` from the predicate
orphans the `sigma.Box` and `sigma.Header` imports (V6Extractor.scala:56, used *only*
at the old line 129). Trim that import to `sigma.{Context, VersionContext}` in the same
edit, and update the `skippedContext` field comment (line 69, "input is
Context/Box/Header") to say Context-only. `scalac` `-Xfatal-warnings` (if on) would
fail on the unused import otherwise — verify the test compiles clean.

**Not needed for 2a (YAGNI — no compound input in scope):** `stypeFromJson` and
`decodeColl` Box/Header arms. No `Coll[Box]`/`Coll[Header]`/tuple-of-box input exists
in the V6 spec (verified by the enumeration sweep), and a top-level Box/Header decode
reads `bytes_hex` directly without `stypeFromJson`. The loud-error default stands; add
an arm only if a future corpus introduces a nested Box/Header (never a silent guess).

## TDD discipline (non-negotiable)

Per kind, an **end-to-end eval-back round-trip** test in `EvalAppliedTest` (the guard
that caught the Stage-1 Tuple→silent-Coll bug). Reuse the existing helpers
(`EvalAppliedTest.scala:28` `idTreeHex(tpe)` = `OptionGet(GetVar(1, tpe))`, and
`assertEvalBack` :40 = decode→bind to var 1→eval→assert
`valueToJson(result).noSpaces == inputJson.noSpaces`). Add `SBox`/`SHeader` to that
file's `sigma.ast.{…}` import (they aren't imported yet).

- **Box** (`assertEvalBack(SBox, boxJson)`): build the input JSON straight from a
  representative `ErgoBox` — `{kind:"Box", bytes_hex: Base16.encode(ergoBox.bytes)}`
  (reuse the dummy-box shape in `EvalCore.dummyContext`: value, a trivial tree, zero
  txId/index/height). This is the **load-bearing test**: it proves a `BoxConstant`
  *survives binding as a ContextExtension var and `toSigmaContext()`* — the one residual
  risk in the source-confirmed mechanism. If it fails, the binding model is wrong for Box
  and 2a reshapes (escalate, don't patch).
- **Header** (`assertEvalBack(SHeader, headerJson)`): the input JSON is
  `{kind:"Header", bytes_hex: <v2 header hex>}`. Use the upstream spec's verified-**v2**
  literal (first byte `02`) — `LanguageSpecificationV6.scala:1550` `headerBytes`, the same
  value `Header new methods` feeds `checkPow` (copy the hex string into the test; it is
  not in SANTA's tree today). The identity tree returns the header unchanged, so PoW
  validity is irrelevant to the round-trip; this guards the codec, while `checkPow`'s
  actual `true` is proven by the extractor re-bless below.
- Keep a malformed-hex negative guard (decode of non-box/non-header bytes errors loudly,
  never a silent wrong value).

## Verification / done criteria

1. `sbt test` green — incl. the two new eval-back tests **and** `EvalConformanceTest`
   (every committed vector, old + 4 new, still re-blesses).
2. Re-run the extractor (`testOnly santa.V6ExtractorTest`): the context skip drops
   **8 → 4** (only `getVarFromInput`'s 4 Context cases remain); `captured` rises **+4**;
   **+3 op files** appear (`Box properties equivalence (new features)`, `Header new
   methods`, `Global.deserializeTo - header`). Each new entry's expected value
   (`Long 10` / `Boolean true` / `Boolean true`) matches via the extractor's own
   VALUE-MATCH guard — the real end-to-end proof that `getReg`/`checkPow`/`deserializeTo`
   eval correctly against the decoded input under SANTA's dummy context. (Trust the
   delta; confirm absolute corpus counts from the run, then correct the umbrella.)
   `skippedUnsupportedKind` stays 0; `propertyFailures` empty.
   *Re-bless safety:* `checkPow` **throws on a version-1 header** (CHeader.scala:73-75 →
   `checkPoWForVersion2`); confirmed the only `checkPow` input is the **v2** literal
   (`headerBytes` first byte `02`), so it returns `true`, not an error. `deserializeTo`'s
   second case feeds a **v1** header (`header2Bytes` first byte `01`) but only
   serialize/deserialize/compares it — never `checkPow` — so v1 is fine there.
3. Commit the 3 recovered `vectors/eval/*.json`; the extractor reproduces them
   byte-identically on a re-run (`diff -rq vectors/eval target/v6-vectors`).
4. Update `SPEC.md` (corpus counts) + this status block; per `update-docs-before-commit`,
   before the commit.

## Out of scope

- **Stage 2b — Context** (`getVarFromInput`, 4 cases): needs a real `CContext`
  materialized from the vector and an eval path that doesn't route through
  `ContextExtension`/`toSigmaContext` (the reference does `ctx.copy(vars=…)`). Blocked on
  a mechanism spike — **do not** design or scaffold it from this spec. Separate
  `phase-2-stage-2b-context.md` after the spike resolves the binding model.
- `skippedError` (error-expected) — Stage-1 policy, unchanged.

## Status

**Delivered 2026-05-31.** Spec written after the Stage-2 scoping investigation (three
converging methods: instrumented extractor, sigma test-framework read, V6-spec
enumeration); built subagent-driven + TDD + two-stage review per the method.

Box + Header value codec landed (`303cb50`; review follow-ups `0253973`), extractor skip
narrowed to Context-only (`976a75c`), 3 recovered vectors committed (`c68e11c`). Final
corpus: **239 `santa-eval/v2` vectors across 32 ops** (+4 cases vs Stage 1.5's 235/29),
cross-check **245/245 re-blessed across 33 files**; extractor reproduces the corpus
byte-identically. The **load-bearing risk is retired**: a `BoxConstant` survives
ContextExtension var-1 binding + `toSigmaContext()` (proven by the Box eval-back test and
by all 3 ops passing the extractor's VALUE-MATCH guard on real spec inputs) — so 2a needed
no eval-path change, only the codec. Box.getReg → `Long 10`; Header.checkPow → `true` (v2
header); deserializeTo-header → `true` for both a v2 and a v1 header.

Context skip now **4** (all `getVarFromInput`) → **Stage 2b** (mechanism spike pending).
