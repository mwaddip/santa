# Finding: which node constructors check their operands at parse (sigma-rust probe, confirmed on the JVM)

**Tier:** wire (`santa-wire/v1`)  
**Surfaced:** 2026-09-28, a probe from the sigma-rust session, following the degrade gate
(`wire-tree-degrade-gate.md`)  
**Status:** OPEN on sigma-rust eni and develop (found by sigma-rust, not yet fixed)

**Vectors:** `vectors/wire/v6/authored/{Box,Transaction}.tree_parse_acceptance.json`. Entries #20–#22 (a
`ConcreteCollection` item of the wrong type, an `AssertionError`) came later, from sigma-rust's source findings:
`sized-tree-source-findings.md` §7. Entries #23–#24 (`SContext` method 11 parses, sized or not, and fails only when
evaluated) came from the evaluated-values follow-up: `evaluated-values.md` §4.

## The JVM (sigmastate 6.0.6)

Most node constructors take their operands by an erased cast and check nothing. So a node on an operand of the
wrong type parses, and fails only if it is ever evaluated. A few do check, and none of their exceptions is a
`ValidationException`, so a size-flagged tree rejects as well.

Every tree wraps the node under test in `BoolToSigmaProp` (`d1`). The trees are unsized v0 unless the table says
otherwise, and each is a transaction output (Transaction kind) or a bare box (Box kind), under the node's v6 parse
context (3, 3).

| # | Tree | JVM | Why |
|---|---|---|---|
| 0 | `Exists(Int 1, (x: Int) => true)` | parses | no check |
| 1 | `LogicalNot(Int 1)` | parses | no check |
| 2 | `TrueLeaf` as opcode `7f` | parses, comes back as `01 01` | see below |
| 3 | `FalseLeaf` as opcode `80` | parses, comes back as `01 00` | see below |
| 4 | `EQ(PropertyCall(SBox.value) on Int 1, Long 1)` | parses | `specializeFor` keeps the method when unification fails (`SMethod.scala:193-199`) |
| 5 | `Apply(Int 1, [])` | parses | `Apply.tpe` is lazy, `NoType` for a non-function (`values.scala:1247`) |
| 6, 7 | `EQ(SizeOf(Append(Int 1, Int 2)), Int 0)`, unsized and sized | **reject** | `val tpe = input.tpe` is strict: `ClassCastException` (`transformers.scala:62`) |
| 8, 9 | `EQ(SizeOf(Slice(Int 1, 0, 1)), Int 0)`, unsized and sized | **reject** | the same (`transformers.scala:89`) |
| 10 | `OptionIsDefined(Int 1)` | parses | `opType = SFunc(input.tpe, SBoolean)` needs no cast (`transformers.scala:656`) |
| 11 | v3 `EQ(Int 1, Long 1)` (`0b`, sized) | **reject** | no upcast from v3 (`SigmaBuilder.scala:757-758`), so the same-type check throws `ConstraintFailed` (`:691`) |
| 12 | v0 `EQ(Int 1, Long 1)` | parses | below v3 the Int is upcast to Long |
| 13 | v3 `EQ(Int 1, Int 1)` | parses | the same types |
| 14, 15 | `GT(true, true)`, unsized and sized | **reject** | the comparison check requires numeric operands: `ConstraintFailed` (`:699`) |
| 16 | `GT(Int 1, Int 1)` | parses | |
| 17, 18 | `EQ(BitOr(true, true), Int 0)`, unsized and sized | **reject** | `BitOp` requires numeric operands (`trees.scala:913`); the IAE is rethrown as a `SerializerException` |
| 19 | `EQ(BitOr(Int 1, Int 1), Int 0)` | parses | |

The deserializing builder is `DeserializationSigmaBuilder` (`SigmaBuilder.scala:750`), a `TransformingSigmaBuilder`
whose `equalityOp` and `comparisonOp` run the checks (`:686-702`). #17's rethrown message reads "Tree version (0) is
above activated script version (3)", whatever the IAE was.

## TrueLeaf and FalseLeaf are a non-identity round-trip

`7f` and `80` are real opcodes: `CaseObjectSerialization(TrueLeaf)` and `(FalseLeaf)` (`ValueSerializer.scala:79-80`).
But `TrueLeaf` and `FalseLeaf` are Boolean constants (`values.scala:771`, `:782`). The box serializer writes a parsed
tree back from its structure (`ErgoBoxCandidate.scala:142` → `serializeErgoTree`), not from the bytes it read. So
`00 d1 7f` comes back as `00 d1 01 01`, and `00 d1 80` as `00 d1 01 00`. Entries #2 and #3 carry that as
`expected_bytes_hex`.

This matters beyond parsing:
- **Transaction id:** computed over the re-serialized transaction (`ErgoLikeTransaction.bytesToSign`).
- **Output box ids:** each output box is built by `ErgoBoxCandidate.toBox` with no parsed bytes, so its id also
  hashes the re-serialized box (`ErgoBox.bytes` falls back to the serializer, `ErgoBox.scala:87-91`).
- **Consequence:** an impl that parses the opcode but keeps the input bytes computes different transaction and box
  ids.
- **Standalone boxes:** a box parsed on its own keeps its input bytes as `ErgoBox.bytes` (`:214-224`). The Box kind
  grades the serializer's output (`toBytes`).

This also answers ergots' JVM-truth item 10 ("Does `BoolToSigmaProp(0x7F)` parse on the JVM?"): it parses, and
re-serializes as the Boolean constant.

## Grades

Each runner is graded per kind (Box and Transaction alike).

| Runner | Parses (#0–5, #10) | Erased casts (#6–9) | Builder and BitOp checks (#11, #14, #15, #17, #18) | Twins |
|---|---|---|---|---|
| rudolph | all green | all green | all green | all green |
| blitzen-eni `0bd7199f` | **all 7 rejected** | unsized green; **sized accepted** (degraded) | **all 5 accepted** | green |
| blitzen-develop `1633e018` | **all 7 rejected** | unsized green; **sized accepted** | **all 5 accepted** | green |
| dasher (ergots `f2f4a94c`) | green, except **#2 and #3 rejected** (ergots reserves `7f`/`80`) | **all 4 accepted** | **all 5 accepted** | green |

- **Board:** eni 35 → 63, develop 274 → 302, dasher 19 → 41.
- **The existing corpus** is unchanged on every runner.
