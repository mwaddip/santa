# Finding: sigma-rust has no deserialization depth limit (the JVM caps nesting at 110)

**Tier:** wire (`santa-wire/v1`)  
**Surfaced:** 2026-09-27, first conform runs of the vectors below  
**Status:** FIXED on sigma-rust eni `862df85f` (pushed 2026-09-28); develop PR #926 (`d0d5e4e3`) open  
**Conformers affected:**
- blitzen-develop `1633e018`: every reject below. blitzen-eni `bf4d6943` had the same gaps; `862df85f` passes every
  pair.
- dasher at ergots `master`: the extension, SigmaBoolean, nested-box and both degrade-leak rejects. ergots PR #17
  (`287a9d6`) passes every pair.

**Vectors:** `vectors/wire/v6/authored/Transaction.*`, one accept/reject pair each (below)

## The divergence

The JVM rejects at parse any transaction that nests past 110 levels on any path its level counter runs
through:

```
DeserializeCallDepthExceeded: nested value deserialization call depth(111) exceeds allowed maximum 110
```

sigma-rust counts nothing, so it parses and re-serializes every one of them.

| Vector | What nests | Levels | Accept / reject |
|---|---|---|---|
| `context_extension_depth_bound` | extension value `Coll^n[Byte]` | 1 + n | 109 / 110 |
| `register_depth_bound` | output R4 `Coll^n[Byte]` | 1 + n | 109 / 110 |
| `tree_body_depth_bound` | size-flagged tree `BoolToSigmaProp(LogicalNot^m(placeholder))` | m + 2 | 108 / 109 |
| `segregated_constant_depth_bound` | unused segregated constant `Coll^n[Byte]` | n | 110 / 111 |
| `sigma_boolean_depth_bound` | extension SigmaProp, `CAND(inner, TrueProp)` k deep | k + 3 | 107 / 108 |
| `nested_box_depth_bound` | extension Box whose size-flagged tree carries `Coll^n[Byte]` | 2 + n | 108 / 109 |
| `degraded_tree_depth_leak` | output 0's tree degrades 10 deep, then output 1 R4 `Coll^n[Byte]` | 10 + 1 + n | 99 / 100 |
| `nested_degrade_depth_leak` | output 0's tree parses, but a Box constant in it degrades 10 deep; then output 1 R4 `Coll^n[Byte]` | 10 + 1 + n | 99 / 100 |

`Coll^n[Byte]` is n nested collections, each outer one holding one element, the innermost empty.

## The JVM rule (sigmastate v6.0.6)

**The counter.** The reader carries one level counter. Setting it above `maxTreeDepth` throws
(`CoreByteReader.scala:127-129`); the default is `SigmaConstants.MaxTreeDepth` = 110 (`SigmaConstants.scala:28`).

**Where it rises.** Three sites raise the level on entry and lower it on normal exit:

- `ValueSerializer.deserialize`: every value and expression node (`ValueSerializer.scala:396-409`). The extension
  value (`ContextExtension.scala:61`) and every register value (`ErgoBoxCandidate.scala:231`) are read with
  `getValue`, which lands here.
- `CoreDataSerializer.deserialize`: every data value, so every collection element, tuple item and option content
  (`CoreDataSerializer.scala:94-148`). `DataSerializer.deserialize` raises it once around a Box or a Header
  (`DataSerializer.scala:31-49`).
- `SigmaBoolean.serializer.parse`: every node (`SigmaBoolean.scala:72-103`).

**What doesn't count.** Type descriptors: `TypeSerializer`'s `depth` argument is never checked. Segregated tree
constants are read with `ConstantSerializer.deserialize` (`ErgoTreeSerializer.scala:256`), so they count data levels
but no value level.

**Scope.** The node parses a whole transaction with one reader (ergo v6.0.6 `ErgoTransactionSerializer.parse`), and
every box's tree is parsed on that reader (`ErgoBoxCandidate.scala:194`). Levels therefore carry into trees and into
boxes nested in values.

**Hard reject, never a degrade.** `DeserializeCallDepthExceeded` is a `SerializerException`. The soft-fork path in
`deserializeErgoTree` catches only `ValidationException`, so a too-deep size-flagged tree rejects the transaction
instead of degrading to `UnparsedErgoTree`.

**The degrade leaks levels.** When a size-flagged tree does degrade (a `ValidationException`, e.g. an unknown
opcode), the frames between the tree root and the failing node never lower the level:

- `ValueSerializer.deserialize` has no `finally`.
- `deserializeErgoTree`'s `finally` restores only the position limit (`ErgoTreeSerializer.scala:209-211`).

A tree degrading k levels deep leaves k levels on the reader for the rest of the transaction. The
`degraded_tree_depth_leak` pair shows it live. The blesser also checks a control: the reject's output 1 behind a
normally parsing output 0 accepts. So the JVM rejects there only because of the leak.

Every enclosing frame lowers the level by one from wherever it is, not back to a saved value. So a degrade nested
inside a tree that parses leaks too: `nested_degrade_depth_leak` puts the degrading tree in a Box constant of
output 0's own size-flagged tree, which parses, and output 1 still starts 10 levels up.

## sigma-rust

`ergotree-ir/src/serialization/sigma_byte_reader.rs` keeps no level. No parse path in
`ergotree-ir/src/serialization` or `sigma-ser` checks nesting depth; the only `depth` hits there are proptest
parameters. Every recursive parse is unbounded.

## Why it matters

ergo-node-rust parses block transactions (`validation/src/sections.rs:117`) and mempool transactions
(`src/main.rs:3725`) with sigma-rust's `Transaction::sigma_parse`. A transaction the JVM rejects at parse is
therefore accepted by the Rust node:

- In the mempool it is relayed on. JVM peers fail to parse it and penalize the sender
  (ergo v6.0.6 `ErgoNodeViewSynchronizer.parseAndProcessTransaction`).
- In a block, JVM nodes reject the block and the Rust node accepts it.

Not verified: whether nesting far deeper than 110, bounded only by the transaction size, exhausts the Rust stack.
That would abort the process instead of failing the parse.

## Fix

Port the JVM counter: a level in `SigmaByteReader`, raised at the three sites above, failing the parse when it
would exceed 110. The pairs pin four details a straight port can miss:

- The extension value is read with `getValue` in the JVM but with `Constant::sigma_parse` in sigma-rust
  (`chain/context_extension.rs:84`). It needs the value level too.
- A size-flagged tree parsed on an inner reader must start from the outer level (`nested_box_depth_bound`).
- A depth error inside a size-flagged tree must reject, not degrade (`tree_body_depth_bound`).
- A degraded tree must leave its levels on the reader (`degraded_tree_depth_leak`), also through an enclosing tree
  that parses (`nested_degrade_depth_leak`).

This is a parsing fix, so it lands on sigma-rust `develop` first and is cherry-picked to eni. Each accept entry
guards against an off-by-one over-reject.
