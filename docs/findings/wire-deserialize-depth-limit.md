# Finding: sigma-rust has no deserialization depth limit (the JVM caps nesting at 110)

**Tier:** wire (`santa-wire/v1`)  
**Surfaced:** 2026-09-27, first conform run of the vector below  
**Status:** OPEN — sigma-rust over-accepts; routed to the sigma-rust and ergo-node-rust sessions  
**Conformers affected:** blitzen-eni `bf4d6943`, blitzen-develop `1633e018`, and so ergo-node-rust (which pins
eni `bf4d6943`); dasher at ergots `master` (fixed on ergots `extension-bounds-storage-rent` `f2a9c4b`)  
**Vector:** `vectors/wire/v6/authored/Transaction.context_extension_depth_bound.json`, entry
`ext-depth-coll110-reject#1`

## The divergence

A transaction whose input extension holds `Coll^110[Byte]` (each outer collection holding one element, the
innermost `Coll[Byte]` empty) is rejected by the JVM at parse:

```
DeserializeCallDepthExceeded: nested value deserialization call depth(111) exceeds allowed maximum 110
```

sigma-rust parses and re-serializes it. One level shallower, `Coll^109[Byte]` sits exactly at the cap and
both accept it (`ext-depth-coll109-accept#0`).

## The JVM rule (sigmastate v6.0.6)

The reader carries one level counter. Setting it above `maxTreeDepth` throws (`CoreByteReader.scala:127-129`);
the default is `SigmaConstants.MaxTreeDepth` = 110 (`SigmaConstants.scala:28`). Three sites raise the level
on entry and lower it on exit:

- `ValueSerializer.deserialize`: every value and expression node (`ValueSerializer.scala:396-409`)
- `CoreDataSerializer.deserialize`: every data value, so every collection element, tuple item and option
  content (`CoreDataSerializer.scala:94-148`). `DataSerializer.deserialize` does the same around a Box or a
  Header (`DataSerializer.scala:31-49`).
- `SigmaBoolean.serializer.parse` (`SigmaBoolean.scala:72-103`)

Type descriptors are not counted: `TypeSerializer`'s `depth` argument is never checked.

So `Coll^n[Byte]` as an extension value takes `1 + n` levels. The value parse takes one, then each
collection level takes one; the innermost `Coll[Byte]` reads its bytes without a per-element call.

The node parses a whole transaction with one reader (ergo v6.0.6 `ErgoTransactionSerializer.parse`), so the
counter is shared across the transaction. When nothing throws it returns to 0 between values. Whether it
leaks after an exception the parser catches (a size-flagged tree degrading to `UnparsedErgoTree`) is an
open question.

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

Not verified yet:

- Register values and tree bodies go through the same unbounded parser, so they very likely diverge the same
  way. A `Box`-kind vector would pin the register case.
- Whether nesting far deeper than 110, bounded only by the transaction size, exhausts the Rust stack. That
  would abort the process instead of failing the parse.

## Fix

Port the JVM counter: a level in `SigmaByteReader`, raised at the three sites above and failing the parse
when it would exceed 110. This is a parsing fix, so it lands on sigma-rust `develop` first and is
cherry-picked to eni. The accept entry guards against an off-by-one over-reject.
