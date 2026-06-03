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

## Blitzen 4-way (2026-06-02) — the spec corpus, live

`./conform` now runs `sigma-rust` directly as **Blitzen**: two submodules over the blessed
spec corpus — `develop` (upstream `ergoplatform/sigma-rust`, value-only, `cost:false`) and the
`ergo-node-integration` fork (`--features jit-cost`, value + cost). Headline:

- **v5 — eni fork: 1558/1558 value · 1558/1558 cost · 147/147 reject (perfect).** The fork is
  fully v5-conformant. **Upstream `develop`: 1548/1558 value** — **10 v5 value divergences** the
  fork already fixed (the historical sigma-rust v5 gaps, localized to upstream).
- **v6 — both divergent: 198/206 value · 40 unrepresentable · 23/23 reject**, and eni's cost
  **16/198** (its jit-cost tracks the JVM on v5 but not v6). These are *surfaced, not yet
  verified per-op* — the detailed v6 analysis (which ops; real-vs-harness) is the next focus.

This supersedes the fixture-gen-era findings below (which predate the spec corpus). JVM
`sigma-state` 6.0.3 stays canonical.

## SigmaProp equality cost (2026-06-03) — authored to fill a coverage hole

`SigmaProp == SigmaProp` was **never exercised by the corpus**: `LanguageSpecificationV5`'s NEQ
"predefined types" features exclude SigmaProp, so the spec-extracted corpus carries zero SigmaProp
under EQ/NEQ (its SigmaProp values are all constructors / `&&` / `||` / `propBytes` / `serialize`).
That blind spot let **both** non-JVM conformers ship a flat `EQ_PRIM_COST`-equivalent (3) for
SigmaProp equality where the JVM charges *structurally* — `DataValueComparer.equalSigmaBoolean`
charges `MatchType` (1) per tree node + `EQ_GroupElement` (172) per EcPoint. Surfaced by the
sigma-rust `/code-review` backstop (`SANTA_SIGMAPROP_EQ_COST_VECTOR_NEEDED.md`); **authored** to fill
the gap (you can't extract what the spec never tested).

**Vector:** `vectors/eval/v5/authored/EQ_of_SigmaProp.json` — one tree
`{ getVar[SigmaProp](1).get == getVar[SigmaProp](1).get }` (reads var 1, compares it to itself: a full
structural walk, no short-circuit, nothing to constant-fold; getVar/OptionGet/EQ overhead is identical
across impls, so the only thing that can move the total is the comparer), with three SigmaProp inputs.

| SigmaProp shape | JVM (canonical) | ergots **and** sigma-rust | Δ undercharge |
|---|---|---|---|
| `proveDlog` (1 EcPoint) | **224** | 53 | 171 |
| `proveDHTuple` (4 EcPoints) | **740** | 53 | 687 |
| `CAND` (2 children) | **398** | 53 | 345 |

Both conformers charge a **flat 53** regardless of structure (50 shared tree overhead + their flat 3);
the JVM's variable part is exactly the comparer contribution (174/690/348, matching the prompt's
hand-derivation to the unit). A genuine consensus-cost divergence — a crafted tx could sit under
`MaxBlockCost` on one impl and over it on the JVM.

**String equality — checked, NOT a divergence (unreachable).** `DataValueComparer` has a dedicated
`String` arm too, but the JVM evaluator **rejects** `String == String`:
`getVar[String](1).get == getVar[String](1).get` throws `RuntimeException: Unknown type SString` (no
SType→RType mapping; no op produces a runtime String; the typer only folds `"+"` on two String
constants). String is compile-time-only, so String equality is unreachable in any evaluable ErgoTree —
the flat-3 there is dead/defensive code, not a consensus concern. No vector authored;
`AuthoredSigmaPropEq.stringEqProbe` + `AuthoredSigmaPropEqTest` guard the verdict (they flip loud if a
future sigma-state ever evaluates SString).

**Status: OPEN.** sigma-rust fix forthcoming (a real `SigmaProp` arm in `eq_with_cost` mirroring
`equalSigmaBoolean` — its own `develop fix/` PR + eni cherry-pick). ergots shares the undercharge
(same flat-53; an ergots-side fix lands separately). `./conform` flags blitzen-eni
`eval/v5/authored: cost 0/3` until fixed.

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
- **SigmaProp equality cost** (`EQ of SigmaProp`, authored 2026-06-03): **OPEN** — both ergots and
  sigma-rust undercharge flat-53 vs the JVM's structural 224/740/398; sigma-rust fix forthcoming. See above.
- **Behavioral divergences** (`plus_kind_mismatch_int_long`, `tuple_triple_bool_byte_short`):
  **OPEN** — not yet filed as sigma-rust PRs. Revisit once the eval tier stabilizes further.
