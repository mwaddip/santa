# Spec — never-panic runner invariant + the `panicked` outcome

**Status: AGREED, NOT BUILT (2026-06-03). For a fresh context to implement** (the user asked to do
the work in a clean context). Design locked with the user; this is the requirements + scope. The
implementing context should plan it (`superpowers:writing-plans`, for its review gate) then build.

## Goal

A runner must **never panic / abort the run**. Today one bad entry can crash a whole runner — e.g.
`ts-runner`'s `encodeSValue` threw a plain `Error` on an `UnsignedBigInt` *result*, `runEntry`
re-threw it, and the **entire dasher run died** (⚠️, every slice ungraded). New invariant: a runner
emits a faithful outcome for **every** entry; any would-be-panic is caught, the entry is marked
**`panicked`** (graded **coal**, with the panic message in a **`note`**), and the run **continues**.

## Why

- **Robustness** — one unhandled case can't abort a runner's whole grading pass (the other hundreds
  of entries still grade).
- **Visibility** — a panic becomes a *visible divergence* (coal, with the message) instead of a silent
  crash. Surfacing failures is exactly a conformance suite's job.
- Surfaced by the dasher crash on `eval/v6/spec/BigInt_6.0_features` during the 2026-06-03 SigmaProp-EQ
  sidestep's `./conform` run (`ts-runner/src/encode.ts` had no `UnsignedBigInt` case → plain `Error` →
  `runner.ts` `[2]` `throw err` → process exit). See HANDOFF + `docs/findings/eval-jvm-vs-sigma-rust.md`.

## The `panicked` outcome

A 5th runner outcome, alongside `null` / `errored` / `not-implemented` / `unrepresentable`:

```
{ "value": null, "cost": null, "error": "panicked", "note": "<panic message / class>" }
```

- **`note`** — NEW field: the panic message, for diagnosis. Present **iff** `error == "panicked"`.
- **Grades coal UNCONDITIONALLY.** Unlike the other tags, `panicked` is coal even on a
  reject-expected vector: a runner that *crashes* instead of cleanly rejecting is defective, not nice.
  This is precisely **why we do NOT reuse `errored`** — `errored` grades *nice* against a
  reject-expected entry, which would hide a crash.

## Scope — the contract change (all five)

1. **Actuals schema** (`schema/santa-eval.actuals.schema.json`; wire actuals too if it gains the
   notion): add `panicked` to the `error` enum + the `note` field (string; present iff
   `error=="panicked"`, forbidden otherwise — keep the value/cost-null asymmetry strict). Add `validate`
   asymmetry guards for the new tag (mirrors the existing `errored`/`unrepresentable` guards).
2. **santa-check** (Rust, `tools/`): §5/§6 grade `panicked` → **coal unconditionally**; surface `note`
   in the per-slice red detail in `.santa/results.json`. Add `panicked` metas to `oracle/verdicts.json`
   and prove the engine reproduces them in `tests/oracle.rs`.
3. **Contract doc** (`docs/contract/runner-contract.md`): document the never-panic invariant in §3
   (totality) + the `panicked` outcome + its unconditional-coal grading in §5/§6.
4. **ts-runner** (dasher — the immediate need): wrap `runEntry`'s body so ANY otherwise-uncaught throw →
   `{ value:null, cost:null, error:'panicked', note:<msg> }`; `runVector` already iterates per-entry, so
   it continues. **Keep deliberate outcomes** — in particular **`UnsignedBigInt` → `not-implemented`** in
   `encode.ts` (mirroring `decode.ts`, which already declares it so): decode-consistent, and the
   panic-net then catches only the *genuinely-unexpected*, never a declared not-impl. Add a test that
   injects a panic → `panicked`+note and asserts the run continues.
5. **All-runners clause + fast-follow:** rudolph (Scala `Runner`) and blitzen (Rust `santa-run`) adopt
   the same wrapper (catch per-entry → `panicked`+note, never abort). **ts-runner first**; rudolph +
   blitzen as a fast-follow (separate languages; blitzen lives in the santa-blitzen submodules — land per
   [[pushing-blitzen-submodules]]: mirror to both, SSH push, `$?` per step).

## Decisions locked (do not re-litigate)

- **New `panicked` + `note`**, NOT reuse `errored` — must be unconditional-coal + carry the message.
- **UnsignedBigInt stays deliberate `not-implemented`** in ts-runner encode (decode-consistent); real
  UnsignedBigInt encode/decode bridging is DEFERRED until ergots' v6 job lands + stabilizes (the user:
  "no need to check yet because the ergots v6 job is in flight").
- **Full contract change** (schema + santa-check + oracle + contract + ts-runner) now; rudolph/blitzen
  fast-follow.
- **No SHA-pinning the conformer `impl`s** — branch-latest is intentional (it tracks each conformer's
  conformance progress; a pin would freeze exactly what SANTA exists to watch).

## Verification (forced — run all)

- ts-runner: `npx tsc --noEmit` + `npm test` (incl. the injected-panic → `panicked`+note test);
  rebuild `dist`.
- `./validate` — schema accepts `panicked`+`note`; all asymmetry guards green.
- `cargo test -p santa-check` (oracle: `panicked` → coal) + `./conform` — **dasher COMPLETES (no ⚠️)**,
  grades every slice incl. `eval/v5/authored/EQ_of_SigmaProp`, and `BigInt_6.0_features`'s
  UnsignedBigInt entries surface as `not-implemented`.
- `sbt test` if rudolph's `Runner` is touched.

## Out of scope / deferred

- Full UnsignedBigInt bridging in ts-runner (when ergots v6 lands).
- The **wire-tier runners** — the original NEXT-SESSION track; resume after this lands.

## Context already on `main`

- SigmaProp-EQ cost vector LANDED (`6f862a6`): `vectors/eval/v5/authored/EQ_of_SigmaProp.json`. That
  work is DONE — this spec is the follow-up triggered by its conform run. Don't redo it.
- See [[conformance-divergences-are-the-deliverable]], [[runner-contract-and-ergots-scope]],
  [[runner-orchestrator]], [[all-rust-tooling-and-mise]].
