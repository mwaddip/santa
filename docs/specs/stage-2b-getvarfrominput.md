# SANTA — Stage 2b: `getVarFromInput` (Context-input eval vectors)

> Thin subspec (Phase 2, eval tier). Closes the **one open eval-tier coverage gap**
> SPEC.md flags. The 2026-06-03 spike **resolved the keystone** — the mechanism below is
> build-ready (treeHex + values + cost all confirmed by a throwaway jvm run).

## What & why

`getVarFromInput` is a v6.0 `Context` method: `getVarFromInput[T](inputIndex, id): Option[T]`
reads context-variable `id` from the **ContextExtension of a specific spending-transaction
input** (`spendingTransaction.inputs(inputIndex).extension.get(id)`, type-checked against `T` —
`tmp/sigma-src/sigmastate/eval/CContext.scala:76`). It is the only `Context`-input feature the
v5/v6 corpus harvest excluded, because the existing vector format can't carry a `Context`.

The `LanguageSpecificationV6` `getVarFromInput` property has **4 cases**, all one script —
`x.getVarFromInput[Boolean](0, 11)` — differing only in input 0's extension at id 11 (present /
absent / wrong-type), yielding `Some(true)` / `Some(false)` / `None`. We cover the *same behaviors*.

## Why a new slice, and why **authored** (not extracted)

- `santa-eval/v2` binds a **single** input SValue at context var 1 (`func(getVar[A](1).get)` —
  `EvalCore.scala:497,525`). `getVarFromInput` needs **per-input** extensions, which v2 can't express.
- The spec compiles its cases as `func(getVar[SContext](1).get)` — binding the whole **Context** at
  var 1. `SContext` has no value serializer, so that exact tree can't be carried as a vector.
- **Spike finding:** `getVarFromInput` is a *predef global* (`SigmaPredef.scala:144`) → a **standalone**
  tree `MethodCall(Context, getVarFromInputMethod, [Short, Byte], Map(T → resultType))` reads the
  implicit context directly. Since SANTA *constructs* this tree (the spec's tree is uncarryable),
  these are honestly **authored** — exactly the `AuthoredSerialize` pattern: SANTA builds the tree +
  representative inputs, the JVM eval (sigma-state 6.0.3, the oracle) blesses value+cost. Provenance
  **`v6/authored`**, source `santa:authored-getvarfrominput`.

## Format — `santa-eval/v3`

Additive successor to v2 (as v2 was to v1; v2 stays frozen). A v3 entry drops the single `input` and
adds **per-input extensions**:

```jsonc
{
  "schema": "santa-eval/v3",
  "op": "Context.getVarFromInput",
  "blessed_by": "jvm:sigma-state-6.0.3",
  "source": "santa:authored-getvarfrominput",
  "entries": [{
    "name": "present-true#0",
    "script": "{ getVarFromInput[Boolean](0, 11) }",
    "tree_bytes_hex": "1b0f020300020bdc650cfe027300730101",
    "inputs": [                                  // one object per spending-tx input, in order
      { "extension": { "11": { "kind": "Boolean", "value": true } } }
    ],
    "version": { "activated": 3, "ergoTree": 3 },
    "expected": {
      "value": { "kind": "Option", "value": { "kind": "Boolean", "value": true } },  // Some(true); None ⇒ "value": null
      "cost": 17,
      "error": null
    }
  }]
}
```

- The canonical `Option` SValue is `{ "kind": "Option", "value": <svalue|null> }` — **no `elem`**
  (matches `schema/santa-eval.vector.schema.json` `$defs/svalue`). Every extension value is an
  ordinary wire-encodable SValue; **no `Context` is ever serialized**.
- **Minimal scope (YAGNI):** per-input extensions only. No `INPUTS`/`dataInputs`/`SELF`/registers/
  headers, and no top-level `input`/var-1 binding (the cases need none). A future Context feature that
  reaches a context root needs a `v4` (or a v3 extension), decided then.

## Blesser (JVM) — the resolved mechanism (`jvm-blesser/`)

- **`EvalCore.evalWithInputExtensions(treeHex, inputExtensions, activated)`** — the productionized
  spike, parallel to `evalApplied`: builds an `ErgoLikeContext` whose `spendingTransaction =
  ErgoLikeTransaction(inputExtensions.map(ext => Input(boxId, new ProverResult(Array.empty,
  ContextExtension(ext)))), …)`, evals the tree through `CErgoTreeEvaluator`, returns `(valueJson,
  cost)` or a coarse error. (Top-level `extension` is empty — getVar isn't used here.)
- **`AuthoredGetVarFromInput`** (mirrors `AuthoredSerialize`): builds the standalone tree once,
  authors the scenarios, blesses each via `evalWithInputExtensions`. The tree —
  `MethodCall(Context, SContextMethods.getVarFromInputMethod, IndexedSeq(ShortConstant(0),
  ByteConstant(11)), Map(SType.tT → SBoolean))`, serialized via `LenientErgoTree` at v6 — is the
  **confirmed** `tree_bytes_hex = 1b0f020300020bdc650cfe027300730101`.
- **Scenarios (spike-harvested, value+cost confirmed):** input 0 extension `{11 → …}` —
  present `Boolean(true)` ⇒ `Some(true)`; present `Boolean(false)` ⇒ `Some(false)`; absent ⇒ `None`;
  wrong-type `Int(5)` ⇒ `None`; (optionally) bad input index ⇒ `None`. **All cost 17** (fixed op cost).
- The extractor's `isContextOnly` branch **stays a skip** (Context cases aren't extracted — SANTA
  authors getVarFromInput separately). Its loud out-of-scope report stays, so a *future* Context
  feature can't slip through silently.

## Runner changes (Phase 2 — separate plan, after the blesser lands)

Every runner stays **total** — a v3 entry it can't fully build/eval yields a faithful outcome, never a
crash (the AvlTree-totality lesson: a decoder must not throw on the v3 `inputs[]` shape).

- **Rudolph (JVM consume-mode)** — `Runner` builds the v3 context via the shared `EvalCore` path;
  blesses green (control).
- **Blitzen-eni (sigma-rust)** — **READY**: `getVarFromInput` fully implemented
  (`ergotree-interpreter/.../scontext.rs:117`, JIT cost 10) via a `ContextExtensionProvider`.
  `build_context` (`runners/blitzen-eni/src/eval.rs:91`) populates the `DummyContextExtensionProvider`
  `Vec` with one entry per v3 input. → **live value+cost target** (the deliverable).
- **Blitzen-develop (value-only)** — same change if develop carries `getVarFromInput`; else faithful
  `not-implemented`.
- **Dasher (ergots)** — **MISSING**: wire layer knows the method (`101:12`) but no eval handler →
  `method-not-implemented`; Context model has no per-input extension storage. Parses v3, eval yields a
  faithful `not-implemented`. ergots-side fix routed via `prompts/`, not required for this slice.

## Schema gate / conform / santa-check

- Extend **`schema/santa-eval.vector.schema.json`**: add `"santa-eval/v3"` to the `schema` enum; add an
  `inputs` array to `$defs/entry` (`items`: `{ extension: { <varId-string>: svalue } }`); add a v3
  branch to the top-level `allOf` (v3 ⇒ require `source`; entries require `inputs`, forbid `input`;
  v1/v2 entries forbid `inputs`). `tools/validate` then covers the new file.
- **conform / santa-check**: grading is per-op/per-entry outcome — **no comparator change**; v3 entries
  grade like any other (value+cost vs expected, or a coverage gap). santa-check already grades `Option`
  results in v6 (e.g. `Coll.get`).

## Vectors / taxonomy

- `vectors/eval/v6/authored/Context.getVarFromInput.json` — provenance `authored`, 4–5 entries.

## Gates / testing (TDD)

- **jvm**: `AuthoredGetVarFromInputTest` (mirrors `AuthoredSerializeTest`) — anchors the standalone
  `tree_bytes_hex` and every scenario's value + cost; `EvalCoreTest` covers `evalWithInputExtensions`.
- **schema**: v3 validates the new file; `tools/validate` count bumps.
- **conform 4-way** (Phase 2): rudolph green · blitzen-eni real value+cost · dasher `not-implemented`.

## Risks — resolved

The keystone (standalone tree construction + per-input-extension eval) is **resolved by the spike**:
treeHex serialized + round-tripped, and eval produced the correct `Option[Boolean]` for all four
scenarios at cost 17. No residual unknowns — the spike code *is* the blesser code.

## Out of scope

General `Context` access (`INPUTS`/`dataInputs`/`SELF` registers/headers), reject-reason taxonomy, and
the **cross-version activation** pass — the latter is the *other* eval-finishing sub-project, its own
thin subspec.
