# Findings — JVM (sigma-state) vs sigma-rust eval divergences

Discovered **2026-05-30** while blessing the fork-authored `fixture-gen` eval fixtures
through the JVM reference (`sigma-state` 6.0.3) under `(activated=3, ergoTree=0)` (v6.0).

**Why these are genuine divergences (not blesser bugs).** The fixtures'
`expected_value_json` / `expected_cost` are *fork-computed* — `fixture-gen`'s generators
run **sigma-rust** as the oracle (e.g. `eval/tuple.rs`: *"sigma-rust IS the cost+value
oracle for this arm"*). So a JVM-vs-expected mismatch is *by construction* a
**JVM-vs-sigma-rust** divergence. Per BOOTSTRAP decision 1 the JVM is canonical, so the
JVM column is the blessed truth and each row is a candidate **sigma-rust PR**.

Reproduce: bless the `tree` bytes with `santa.blesser.Main`, or
`EvalCore.evalEntry(tree, activated = 3)`.

## Cost divergences (value agrees; JIT cost differs) — RESOLVED

| op / entry | tree (hex) | JVM (canonical) | sigma-rust (before fix) | Δ | Resolution |
|---|---|---|---|---|---|
| `and` / `and_empty` | `00960d00` | **20** | 15 | +5 | Fixed in sigma-rust oracle fork 2026-05-31 |
| `collection` / `coll_bool_constants_3` | `00850305` | **35** | 20 | +15 | Fixed in sigma-rust oracle fork 2026-05-31 |
| `calc-blake2b256` / `calc_blake2b256_empty` | `00cb0e00` | **32** | 25 | +7 | Fixed in sigma-rust oracle fork 2026-05-31 |

**RESOLVED.** All three cost undercharges were independently surfaced via an ergots cost
failure and **fixed in the sigma-rust oracle fork (2026-05-31)** — SANTA caught a real
undercharge that is now patched. The fix confirms these were genuine fork bugs (not a
deliberate cost-model lag): aggregate/collection ops were undercounting a per-item or
envelope cost relative to sigma-state 6.0.3. Recorded here as a success story.

## Behavioral divergences (accept vs reject)

**`bin-op-arith` / `plus_kind_mismatch_int_long`** — tree `009a04020504`
- JVM: **accepts** → `Long 3`, cost 35.
- sigma-rust: **rejects** → `bin-op-kind-mismatch`.
- sigma-rust is *stricter*; the JVM silently coerces the Int/Long mismatch. (Decode the
  tree to confirm the exact operands before filing — the `3` result is worth understanding.)

**`tuple` / `tuple_triple_bool_byte_short`** — tree `0086030101020703a413`
(decoded by the JVM as `Tuple(TrueLeaf, ConstantNode(7, SByte), ConstantNode(1234, SShort))`)
- JVM: **rejects** → `InterpreterException: Invalid tuple`.
- sigma-rust: **accepts** → `Tuple[Boolean true, Byte 7, Short 1234]`, cost 30
  (`Tuple = Fixed(15) + 5/item`).
- Structural: sigma-rust supports flat N-ary tuples; sigma-state represents tuples as
  nested pairs and rejects a flat arity-3 `Tuple` node. (Highest-confidence divergence —
  the JVM rejection is explicit.)

## Status
- **Cost divergences** (`and_empty`, `coll_bool_constants_3`, `calc_blake2b256_empty`):
  **RESOLVED** — fixed in sigma-rust oracle fork 2026-05-31. Kept as record.
- **Behavioral divergences** (`plus_kind_mismatch_int_long`, `tuple_triple_bool_byte_short`):
  **OPEN** — not yet filed as sigma-rust PRs. Revisit once the eval tier stabilizes further.
