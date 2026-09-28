# Finding: a size-flagged tree degrades only on a ValidationException (sigma-rust probe, confirmed on the JVM)

**Tier:** wire (`santa-wire/v1`)  
**Surfaced:** 2026-09-28, a probe from the sigma-rust session (the class `tree_valuse_unbound` belongs to)  
**Status:** OPEN on sigma-rust eni and develop (found by sigma-rust, not yet fixed)

**Vectors:** `vectors/wire/v6/authored/{Box,Transaction}.tree_degrade_gate.json`

## The JVM (sigmastate 6.0.6)

`ErgoTreeSerializer.deserializeErgoTree` (`ErgoTreeSerializer.scala:141-214`) turns a size-flagged tree into
`UnparsedErgoTree` only when its parse throws a `ValidationException` (`:197`). It rethrows an
`IllegalArgumentException` as a `SerializerException` (`:191`). Every other exception propagates. So a size-flagged
tree whose parse fails any other way rejects the box or transaction that holds it. The size flag doesn't help.

All trees are size-flagged; each is a transaction output (Transaction kind) or a bare box (Box kind), under the node's
v6 parse context (3, 3).

| # | Tree | Throws | JVM |
|---|---|---|---|
| 0 | `08 02 73 05`: `ConstantPlaceholder(5)`, no constants | `ArrayIndexOutOfBoundsException` (`ConstantPlaceholderSerializer.scala:20`) | reject |
| 1 | segregated, constant 0 = `SigmaProp(true)`, `ConstantPlaceholder(0)` | — | accept |
| 2 | `08 01 00`: a constant of type code 0 | `InvalidTypePrefix` (`TypeSerializer.scala:134`) | reject |
| 3 | `08 02 08 01`: SigmaBoolean opcode 0x01 | `MatchError`: the opcode match has no default (`SigmaBoolean.scala:75`) | reject |
| 4 | `08 02 08 d3`: `SigmaProp(true)` (control for #2, #3) | — | accept |
| 5 | a BigInt of declared size 33, with 33 value bytes | `SerializerException` (`CoreDataSerializer.scala:113`) | reject |
| 6 | the same value in 32 bytes | rule 1001, the root is not a SigmaProp | accept (degrades) |
| 7 | `BlockValue(ValDef(2^31, …), ValUse(2^31))` | `ArithmeticException`: `getUIntExact` (`ValDefSerializer.scala:31`) | reject |
| 8 | the same with id 2^31 − 1 | — | accept |
| 9 | a `FunDef` whose type-argument count is −1 | `NegativeArraySizeException` (`ValDefSerializer.scala:39`) | reject |
| 10 | a `FunDef` whose type argument is `Int` | `ClassCastException` to `STypeVar` (`:41`) | reject |
| 11 | a `FunDef` whose type argument is the type variable `T` | — | accept |
| 12 | v3: a constant of type `SFunc(Int => Int)` with type parameter `Int` | `IllegalArgumentException` (`TypeSerializer.scala:221`), rethrown as `SerializerException` | reject |
| 13 | the same with type parameter `T` | rule 1009, a function has no data | accept (degrades) |
| 14 | a Box constant created at height 2^31 | `ArithmeticException`: `getUIntExact` (`ErgoBoxCandidate.scala:195`) | reject |
| 15 | the same at height 2^31 − 1 | — | accept |
| 16 | a Box constant whose R4 is `Height`, an expression | `ClassCastException` to `EvaluatedValue` (`:231`) | reject |
| 17 | a Box constant with 7 registers | `ArrayIndexOutOfBoundsException`: 6 register ids (`:230`) | reject |
| 18 | a Box constant with 6 registers | — | accept |

- **#12's message is misleading.** The rethrown `SerializerException` always says "Tree version (3) is above activated
  script version (3)", whatever the `IllegalArgumentException` was.
- **Built to surface the over-accept.** Each reject is built so an impl missing the bound still accepts it, by
  parsing it or degrading it. #5 carries the full 33 value bytes. #7's `ValUse` names the same id, 2^31: sigma-rust's
  original probe used `ValUse(0)`, which would reject as unbound once candidate 4 is fixed, even with the id bound
  still missing.
- **Probe as given.** sigma-rust's original trees for #5 (`08 02 06 21`) and #7 (`… 72 00`) reject on the JVM too, for
  the same reasons.

## Grades

| Runner | Rejects (11 per kind) | Accepts (8 per kind) |
|---|---|---|
| rudolph | all green | all green |
| blitzen-eni `0bd7199f` | **all 22 over-accepted** (degraded) | all green |
| blitzen-develop `1633e018` | **all 22 over-accepted** | all green |
| dasher (ergots `f2f4a94c`) | Transaction kind all green. Box kind: #0, #2, #3, #7, #9, #10, #12 **panicked**, the rest green | **#13 rejected** (both kinds) |

- **dasher's Box panics are a SANTA runner defect.** ergots rejects every one of them with a typed error for the right
  reason (`ExprParseError`, `STypeParseError`, `SigmaBooleanParseError`). The runner's Box arm maps only the SValue
  errors to `errored` and lets the rest reach its panic net.
- **dasher's #13** is ergots not degrading on a function's data (rule 1009), the same gap as
  `{Box,Transaction}.func_type_code`.
- **The existing corpus** is unchanged on every runner.
