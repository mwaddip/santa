# Finding: where the parser's state lives — a fresh reader per transaction, one reader per transaction's outputs (sigma-rust candidates, confirmed on the JVM)

**Tier:** wire (`santa-wire/v1`, new kind `BlockTransactions`)  
**Surfaced:** 2026-09-28, vector candidates 1 and 2 from the sigma-rust session, plus a within-transaction twin  
**Status:**
- across transactions: FIXED on sigma-rust eni `0bd7199f` (`9163d178`) and #927 `09a61b05` (`70158365`); develop
  `1633e018` still carries `ValDef` types from one transaction to the next;
- within one transaction: sigma-rust matches the JVM; ergots (`f2f4a94c`) scopes the `ValDef` store to one tree
  and over-rejects.

**Vectors:** `vectors/wire/v6/authored/BlockTransactions.reader_scope.json`,
`vectors/wire/v6/authored/Transaction.valdef_scope.json`

## The JVM (ergo-core / sigmastate 6.0.6)

A `SigmaByteReader` carries the parse state: the nesting level (capped at `MaxTreeDepth` 110), the constant
store and the `ValDef` type store. `ValUse(id)` has no type on the wire, so it reads the type the store recorded
for `ValDef(id)`, and an unknown id throws `NoSuchElementException`.

- **Across transactions: fresh.** `BlockTransactionsSerializer.parse` reads each transaction through
  `ErgoTransactionSerializer.parse`, which wraps the section's reader in a new `SigmaByteReader`
  (`ErgoTransaction.scala:497-503`, `BlockTransactions.scala:187-200`). For block version ≥ 4 each parse also runs
  under `VersionContext.withVersions(3, 3)`. No level, constant or `ValDef` state reaches the next transaction.
- **Within one transaction: shared.** Every output's tree parses on the transaction's reader, so a `ValDef` in
  output 0's tree is still in the store when output 1's tree parses.

## The kind

`BlockTransactions` is a block's transactions section as the JVM frames it: the 32-byte header id,
`VLQ(10,000,000 + block version)` (`84 ad e2 04` for version 4), the VLQ tx count, then the transactions. rudolph
grades it through ergo-core's `BlockTransactionsSerializer` (the `SANTA_TX_BLESSER` build). The blitzen arms read
every transaction from ONE sigma-rust reader, the way ergo-node-rust's `parse_block_transactions` does, so
sigma-rust itself decides what state each transaction starts with. Contract: `runner-contract-wire.md` §5.

## Entries

| Entry | JVM | One shared reader would |
|---|---|---|
| block: tx A's size-flagged v3 tree `BoolToSigmaProp(LogicalNot^107(0xfd))` degrades 109 levels deep; tx B has an ordinary output | accept | reject: tx B needs levels 110 and 111 |
| block: the same transactions, the ordinary one first (control) | accept | accept |
| block: tx A's tree `BlockValue(ValDef(1, SigmaProp(true)), ValUse(1))`, tx B's the bare `00 72 01` | reject | accept: tx A's `ValDef(1)` resolves tx B's `ValUse(1)` |
| block: tx B defines its own `ValDef(1)` (twin) | accept | accept |
| tx: output 0 defines `ValDef(1)`, output 1 is `00 72 01` | accept | — (a per-tree store rejects) |
| tx: the same outputs, reversed | reject | — |

The blesser proves the "shared reader" column on the JVM itself, by parsing the block's transactions back to back
on one `SigmaByteReader`, so each block entry distinguishes the two behaviours.

## Grades

| Runner | Block entries | Transaction entries |
|---|---|---|
| rudolph | all green | all green |
| blitzen-eni `0bd7199f` | all green | all green |
| blitzen-develop `1633e018` | **accepts #2**, the cross-transaction `ValUse`; the depth entries are green because `1633e018` keeps no level | all green |
| blitzen-develop at #927 `09a61b05` | all green | all green |
| dasher (ergots `f2f4a94c`) | not-implemented (the kind is new) | **rejects #0**: `ergo-tree.ts:271` gives every tree a fresh `ValDef` map |

- **The existing corpus is unchanged** on every runner. Per slice, only `wire/v6/authored` moved, by exactly the six
  new entries.
