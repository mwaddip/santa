# Finding: the JVM ignores a size-flagged tree's declared size when its body parses; sigma-rust and ergots honour it

**Tier:** wire (`santa-wire/v1`)  
**Surfaced:** 2026-09-27, a probe requested by the sigma-rust session (hypothesis from source, confirmed on the JVM)  
**Status:** OPEN  
**Conformers affected:** blitzen-eni `bf4d6943` (ergo-node-rust pins it), blitzen-develop `1633e018`, dasher at ergots
`master`  
**Vectors:** `vectors/wire/v6/authored/{Box,Transaction}.sized_tree_declared_size.json`

## The JVM rule (sigmastate v6.0.6)

- **The size is read but never checked.** `deserializeHeaderAndSize` reads the declared size of a size-flagged
  tree (`ErgoTreeSerializer.scala:217-237`); its own comment says the size is not used on a normal pass.
- **Only a degrade uses it.** `deserializeErgoTree` needs the size only when the body throws a
  `ValidationException` and the tree degrades to `UnparsedErgoTree` (`:197-208`).
- **A successful parse ignores it.** When the body parses, the tree is exactly the bytes the parse consumed
  (`:176-179`), and the box parse continues right after them.
- **Re-serialization corrects it.** `serializeErgoTree` re-encodes a parsed tree and writes the recomputed size
  (`:114-122`). Only a degraded tree echoes its raw bytes.

So a box whose tree declares the wrong size parses as if the size were right, and re-serializes with the right
size. Probed on 6.0.6 with declared sizes 0, 1, 3 and 12 around a 2-byte body, on both kinds: every one accepted,
every one re-serialized to the canonical bytes.

## The vectors

Each file holds three entries built around the v1 size-flagged tree `09 02 08 d3` (`sigmaProp(true)`):

- **control:** identity round-trip
- **over:** the same object with the declared size spliced to 3
- **under:** the same object with the declared size spliced to 1

Both mismatches are non-identity round-trips whose expected bytes are the control's. The Box entry is a bare box;
the Transaction entry has the tree on its only output.

## Grades

| Runner | control | over (declared 3) | under (declared 1) |
|---|---|---|---|
| rudolph | ✓ | ✓ | ✓ |
| blitzen-eni `bf4d6943` | ✓ | rejects | rejects |
| blitzen-develop `1633e018` | ✓ | rejects | keeps the declared size 1 (see below) |
| dasher (ergots `master`) | ✓ | rejects | Box: panics (`ReaderError: readU8: EOF`); Transaction: rejects |

- **develop on "under".** It degrades the tree to `Unparsed` (bytes `09 01 08`) and reads the leftover `d3 01` as
  creation height 211. It happens to re-serialize the input unchanged, but it holds a different box than the JVM:
  an unparsed script at height 211, where the JVM has `sigmaProp(true)` at height 1.
- **Existing corpus.** Unchanged for every runner.

## Why it matters

- **eni over-rejects.** It rejects boxes and transactions the JVM accepts, so a transaction or block the JVM takes
  is refused by ergo-node-rust.
- **develop misreads.** On the "under" case its box differs from the JVM's.

Related JVM facts, read from source but not graded by these vectors:
- **A transaction's id hashes the corrected size.** It comes from its re-serialization (`bytesToSign`,
  `ErgoLikeTransaction.scala:192-198`).
- **A standalone box's id hashes the declared size as received.** The box parser keeps the raw bytes, and `bytes`
  returns them (`ErgoBox.scala:87-91`, `:214-224`).

Not probed: whether the JVM relays or stores the raw bytes it received, which decides whether a Rust node would
ever see a non-canonical size from a JVM peer.

## Fix (sigma-rust)

Match the JVM:
- read the declared size;
- parse the body on the outer reader;
- continue from wherever the body parse ended;
- use the declared size only to recover the raw bytes when the body fails with a soft-fork (`ValidationException`
  class) error.

The control keeps the canonical path green, and the two mismatches pin both directions.
