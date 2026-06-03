# SANTA — Stage 2b: `getVarFromInput` (Context-input eval vectors)

> Thin subspec (Phase 2, eval tier). Closes the **one open eval-tier coverage gap**
> SPEC.md flags. Written after the Stage-2b spike (2026-06-03) confirmed feasibility.

## What & why

`getVarFromInput` is a v6.0 `Context` method: `getVarFromInput[T](inputIndex, id): Option[T]`
reads context-variable `id` from the **ContextExtension of a specific spending-transaction
input** (`spendingTransaction.inputs(inputIndex).extension.get(id)`, type-checked against `T` —
`tmp/sigma-src/sigmastate/eval/CContext.scala:76`). It is the only `Context`-input feature the
v5/v6 corpus harvest excluded, because the existing vector format can't carry a `Context`.

**Enumeration (V6ExtractorTest, 2026-06-03):** exactly **4 cases**, one op, one script —
`CONTEXT.getVarFromInput[Boolean](0, 11)`. The compiled AST is **pure** getVarFromInput
(`MethodCall(ValUse(Context), getVarFromInput, [Const(0), Const(11)])`), with **no** `INPUTS` /
`SELF` / register / header reads. The four cases differ only in what input 0's extension holds at
id 11 (present-Boolean / absent / wrong-type), yielding `Some(true)` / `Some(false)` / `None`.

## Why a new slice, not a v2 capture

- `santa-eval/v2` binds a **single** input SValue at context var 1 (`func(getVar[A](1).get)` —
  `EvalCore.scala:497,525`). `getVarFromInput` needs **per-input** extensions, which v2 can't express.
- The spec compiles these cases as `func(getVar[SContext](1).get)` — it binds the whole **Context**
  at var 1. A `Context` has no value serializer (`SContext`), so this exact tree can't be carried as
  a vector. We therefore **do not reuse the spec's tree**.

## Format — `santa-eval/v3`

Additive successor to v2 (as v2 was to v1; v2 stays frozen/untouched). A v3 entry drops the single
`input` and adds **per-input extensions**:

```jsonc
{
  "schema": "santa-eval/v3",
  "op": "Context.getVarFromInput",
  "blessed_by": "jvm:sigma-state-6.0.3",
  "source": "sigma-state:LanguageSpecificationV6",
  "entries": [{
    "name": "...",
    "script": "{ CONTEXT.getVarFromInput[Boolean](0, 11) }",
    "tree_bytes_hex": "...",
    "inputs": [                                  // one entry per spending-tx input
      { "extension": { "11": { "kind": "Boolean", "value": true } } }
    ],
    "version": { "activated": 3, "ergoTree": 3 },
    "expected": {
      "value": { "kind": "Option", "elem": { "tag": "SBoolean" },
                 "value": { "kind": "Boolean", "value": true } },   // Some(true); None ⇒ "value": null
      "cost": 12,                                                    // illustrative: raw JIT eval cost, never 0
      "error": null
    }
  }]
}
```

- Each extension value is an ordinary **wire-encodable SValue** — the existing encode/decode
  machinery handles it. **No `Context` is ever serialized.**
- **Minimal scope (YAGNI):** per-input extensions only. No `INPUTS`/`dataInputs`/`SELF`/registers/
  headers and no top-level `input`/var-1 binding (the 4 cases need none). A future Context feature
  that reaches a context root would need a `v4` (or a v3 extension), decided then — not now.

## Blesser (JVM) changes — `jvm-blesser/`

- **`EvalCore`**: generalize `contextWithVar1` → a context builder that sets the spending
  transaction's inputs, each carrying a `ContextExtension` from the v3 `inputs[]`. New
  `evalWithInputExtensions(treeHex, inputs, activated)` parallel to `evalApplied`.
- **Tree construction**: build the standalone `CONTEXT.getVarFromInput[Boolean](0, 11)` tree
  (compile the script, or construct the `MethodCall` AST directly) — **not** the spec's var-1-Context
  wrapper. (Build-time spike if the compiler path is fiddly.)
- **Extractor (`V6Extractor.Tap`)**: the `isContextOnly(input)` branch (today: skip + record reason)
  becomes a **capture** branch for the per-input-extension subset — read the case's `CContext`
  `spendingTransaction.inputs[i].extension`, re-encode into v3, take the spec's expected
  `Option[Boolean]` and the JVM re-eval cost. **Keep the value cross-check** (spec expected ==
  eval value; a silent wrong value never ships — the cardinal rule).
- **Scope guard (no silent drop):** capture *only* AST-pure getVarFromInput cases; any Context case
  that reaches a context root stays skipped **and is loudly reported as out-of-v3-scope** (the
  enumeration shows none today, but the guard keeps it honest if the spec grows).

## Runner changes

Every runner stays **total** — a v3 entry it can't fully build/eval yields a faithful outcome
(`not-implemented` / `unrepresentable` / `errored`), never a crash (the AvlTree-totality lesson:
a decoder must not throw on an unknown shape — here, on the v3 `inputs[]` shape).

- **Rudolph (JVM consume-mode)** — `Runner` builds the v3 context via the shared `EvalCore` path;
  blesses green (control).
- **Blitzen-eni (sigma-rust)** — **READY** (spike-confirmed): `getVarFromInput` is fully implemented
  (`ergotree-interpreter/.../scontext.rs:117`, JIT cost 10) via a `ContextExtensionProvider`.
  `build_context` (`runners/blitzen-eni/src/eval.rs:91`) currently builds one extension; populate the
  `DummyContextExtensionProvider` `Vec` with one entry per v3 input. → **live value+cost target** (the
  deliverable).
- **Blitzen-develop (upstream, value-only)** — same `build_context` change if develop carries
  `getVarFromInput`; otherwise a faithful `not-implemented`.
- **Dasher (ergots)** — **MISSING** (spike-confirmed): wire layer knows the method (`101:12`) but the
  eval dispatcher has no handler → `method-not-implemented`; the Context model has no per-input
  extension storage. The runner parses v3 (builds whatever context it can) and eval yields a faithful
  `not-implemented`. An ergots-side fix (add the op + per-input extension storage) is a **separate
  routed follow-up** (`prompts/`), not required for this slice.

## Schema gate / conform / santa-check

- Add the **`santa-eval/v3`** JSON-Schema; `./validate` covers the new file.
- **conform / santa-check**: grading is per-op/per-entry outcome — **no comparator change** for a new
  op slice; v3 entries grade like any other (value+cost vs expected, or a coverage gap). Confirm
  santa-check's structural-equal handles an `Option[Boolean]` expected (it already grades `Option`
  results elsewhere in v6, e.g. `Coll.get`).

## Vectors / taxonomy

- `vectors/eval/v6/spec/Context_getVarFromInput.json` — provenance `spec`, 4 entries.

## Gates / testing (TDD)

- **jvm**: extend `V6ExtractorTest` (or a new `Stage2bExtractorTest`) to assert the 4 entries captured,
  the v3 envelope shape, and value/cost anchored; determinism (seeded) holds.
- **schema**: v3 schema validates the new file; `./validate` count bumps.
- **conform 4-way**: rudolph green; **blitzen-eni real value+cost** (the deliverable); dasher
  `not-implemented`; develop per capability.
- Per-runner change built TDD; each runner re-verified total on the v3 file.

## Risks / unknowns

De-risked by the spike: feasibility **confirmed** (sigma-rust ready; format minimal; 4 pure cases).
The one residual build risk is constructing the standalone `getVarFromInput` tree on the JVM
(compile vs hand-built AST) — spike it first if the compiler path resists.

## Out of scope

General `Context` access (`INPUTS`/`dataInputs`/`SELF` registers/headers), reject-reason taxonomy,
and the **cross-version activation** pass — the latter is the *other* eval-finishing sub-project and
gets its own thin subspec.
