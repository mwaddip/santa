# Finding: the function type code 0x70, and a ValUse with no ValDef (sigma-rust candidates, confirmed on the JVM)

**Tier:** wire (`santa-wire/v1`)  
**Surfaced:** 2026-09-28, vector candidates from the sigma-rust session  
**Status:**
- the function type: FIXED on sigma-rust eni `0bd7199f` and #927 `09a61b05`;
- the unbound ValUse: OPEN on both eni and #927 (found by sigma-rust, not yet fixed).

**Vectors:** `vectors/wire/v6/authored/{Box,Transaction}.func_type_code.json`,
`vectors/wire/v6/authored/{Box,Transaction}.tree_valuse_unbound.json`

## 1. The function type code 0x70 (sigmastate 6.0.6)

`TypeSerializer` parses 0x70 as `SFunc` only from tree version 3 (`case SFunc.FuncTypeCode if
isV3OrLaterErgoTreeVersion`). Below that it falls to `CheckTypeCodeV6`: rule 1018, "Cannot deserialize the
non-primitive type with code 112". A function has no data encoding at any version: rule 1009, "Data value of the
type with the code 112 cannot be deserialized".

| Entry | JVM |
|---|---|
| a register (Box) or extension value (Transaction) of type `70 01 04 04 00` = `SFunc(Int => Int)`, under the node's (3, 3) context | reject (rule 1009) |
| the same position holding Int 1 (control) | accept |
| a size-flagged, segregated v2 tree with a constant of that type | accept (the tree degrades, rule 1018) |
| the same tree at v3 | accept (the tree degrades, rule 1009) |

## 2. A ValUse with no ValDef (sigmastate 6.0.6)

`ValUse(1)` reads its type from the reader's ValDef type store. With no `ValDef(1)` in scope, the store throws
`NoSuchElementException`. That is not a `ValidationException`, so even a size-flagged tree does not degrade. The
object is rejected.

| Entry | JVM |
|---|---|
| size-flagged tree `08 02 72 01` | reject |
| size-flagged `BlockValue(ValDef(1, SigmaProp(true)), ValUse(1))` = `08 08 d8 01 d6 01 08 d3 72 01` (the bound twin) | accept |
| unsized `00 72 01` | reject |

## Grades

| Runner | Function type code | Unbound ValUse |
|---|---|---|
| rudolph | all green | all green |
| blitzen-eni `0bd7199f` | all green | **accepts the sized unbound tree** (both kinds) |
| blitzen-develop `1633e018` | **panics** on the v2 degrade (both kinds) and on the extension-value reject | **accepts the sized unbound tree** (both kinds) |
| blitzen-develop at #927 `09a61b05` | all green | **accepts the sized unbound tree** (both kinds) |
| dasher (ergots `f2f4a94c`) | **rejects** the v2 and v3 degrades (both kinds) | all green (both kinds) |

- **The develop panics** are the `unreachable!()` that sigma-rust's `09a61b05` turns into `InvalidTypeCode`.
- **dasher's Box ValUse rejects** first graded as panicked: a SANTA runner defect, fixed 2026-09-28. ergots rejects both with a
  typed `ExprParseError` (`wire-tree-degrade-gate.md`).
- **The ValUse red** is sigma-rust degrading any failure inside a size-flagged body to `Unparsed`. The JVM degrades
  only on a `ValidationException`.
- **The existing corpus** is unchanged on every runner.
