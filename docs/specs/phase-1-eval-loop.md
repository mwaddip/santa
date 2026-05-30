# Phase 1 — Eval-tier loop closed

The minimal working suite: prove the whole shape end-to-end on one op
(`decode-point`), pinning the eval-tier **vector schema** and the **runner I/O
contract**. Umbrella: [SPEC.md](../../SPEC.md).

## Deliverables

1. **Vector schema** (eval-tier `v1`) — the on-disk canonical format.
2. **A committed canonical vector** — `vectors/eval/decode-point.json`, JVM-blessed
   (the first entry on *the nice list*).
3. **Runner I/O contract** — how a conformer consumes a vector + emits results.
4. **Harness** — runs a vector set through a runner command, compares, prints a
   naughty/nice report.
5. **JVM reference runner** (Rudolph) — the blesser in consume-mode. Proves the
   mechanics + defines the contract by example; not yet a conformance check.

Out of scope: the first *independent* runner (ergots) — routed to the ergots repo
once the contract is pinned here.

## Vector schema (eval-tier `v1`)

`vectors/eval/<op>.json`:

```json
{
  "schema": "santa-eval/v1",
  "op": "decode_point",
  "blessed_by": "jvm:sigma-state-6.0.3",
  "entries": [
    {
      "name": "dp_generator",
      "tree_bytes_hex": "00ee0e21...",
      "version": { "activated": 3, "ergoTree": 0 },
      "expected": {
        "value": { "kind": "GroupElement", "bytes_hex": "0279be66..." },
        "cost": 305,
        "error": null
      }
    },
    {
      "name": "dp_wrong_length_32",
      "tree_bytes_hex": "00ee0e20...",
      "version": { "activated": 3, "ergoTree": 0 },
      "expected": { "value": null, "cost": null, "error": "errored" }
    }
  ]
}
```

- `tree_bytes_hex` — serialized ErgoTree; root may be **any type**.
- `version` — `(activated, ergoTree)` the entry is evaluated under.
- `expected.value` — typed `{ kind, … }` (e.g. GroupElement → `bytes_hex`), or `null` on error.
- `expected.cost` — **raw JIT cost** (block cost = ÷10), or `null` on error.
- `expected.error` — coarse class: `null` on success, `"errored"` otherwise.
  *(A finer error enum is a later refinement; `v1` is the ok/errored binary.)*

## Runner I/O contract

A runner is a command that reads a vector file and emits, per entry, its **actual**
result in the same `{ value, cost, error }` shape — JSON to stdout, keyed by entry
`name`:

```
runner <vector.json>
  → stdout: { "dp_generator": { "value": {...}, "cost": 305, "error": null }, ... }
```

Each runner evaluates an entry under the entry's recorded `version`, reports the
**raw JIT cost**, and normalizes its implementation-specific errors to the coarse
class (`null` / `"errored"`).

## Harness

`harness <vector-dir> <runner-cmd>`: for each vector file, run the runner, compare
actual-vs-expected per entry, print a per-runner verdict line:

```
Rudolph (jvm)   nice ✓   6/6   decode_point
```

A divergence is a *lump of coal*; any lump ⇒ the runner is **naughty** on that vector.

## Status

**Delivered** — the loop runs green on `decode-point` (`Rudolph(jvm) nice ✓ 6/6`):
blesser (`Main` + `EvalCore`), runner (`santa.runner.Runner`), harness
(`santa.harness.Harness`), and the committed vector all in place.
