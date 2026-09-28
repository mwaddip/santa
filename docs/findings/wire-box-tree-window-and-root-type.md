# Finding: a box's ErgoTree read window and the unsized-tree root check (sigma-rust diverges on both)

**Tier:** wire (`santa-wire/v1`)  
**Surfaced:** 2026-09-28, two probes requested by the sigma-rust session (hypotheses from source, confirmed on the JVM)  
**Status:** OPEN  
**Conformers affected:** blitzen-eni `862df85f` (ergo-node-rust follows eni), blitzen-develop `1633e018`, dasher at
ergots `master` `f2f4a94c`  
**Vectors:** `vectors/wire/v6/authored/{Box,Transaction}.tree_read_window.json`,
`vectors/wire/v6/authored/{Box,Transaction}.tree_root_type_check.json`

## 1. The read windows (sigmastate 6.0.6)

**Two windows, and each check happens before the read.**
- **Box window.** `parseBodyWithIndexedDigests` sets `positionLimit = position + MaxBoxSize` (4096) at the
  candidate's start and restores the outer limit at its end (`ErgoBoxCandidate.scala:190-192`, `:235`).
- **Tree window.** `deserializeErgoTree` *replaces* that limit with `position + MaxPropositionSize` (4096) at the
  tree's start. Its `finally` puts the box window back (`ErgoTreeSerializer.scala:141-145`, `:210-212`).
- **The check.** Every `get*` checks `position > positionLimit` *before* reading (rule 1014, `CheckPositionLimit`).
  So a bulk read that starts in time crosses the limit, and only the next read fails.

**Inside a tree, a trip is soft.** The rule-1014 `ValidationException` degrades a size-flagged tree to
`UnparsedErgoTree`, whose raw bytes come from its declared size. An unsized tree is rejected instead.

**The peek is unchecked.** `peekByte` skips the position check (`CoreByteReader.scala:41`), and `ValueSerializer`
peeks before every value. A peek past the limit but inside the input succeeds, and the read after it trips rule 1014.
A peek past the *end of the input* throws a raw index exception instead. That isn't a `ValidationException`, so
there is no degrade: the object is rejected.

**The vectors.** Every candidate has value 1000000 (3 VLQ bytes), so its tree window ends at candidate offset
4099, 3 bytes after its box window (4096).

| Entry | Construction | JVM |
|---|---|---|
| degrade-accept | Sized v0 tree declared 5 bytes. The body `BoolToSigmaProp(EQ(Coll[Byte](4090), …))` bulk-reads from offset 10, and the next read trips the tree window. The degrade resumes at offset 10, where the same bytes read as height 1, no tokens and R4 `Coll[Byte](4084)`, a bulk read that crosses the box window. | accept (tree degraded, rule 1014) |
| peek-past-end-reject (Transaction only) | The same tree as the tx's last output: the peek runs off the end of the input. | reject (index exception) |
| unsized-reject | The same kind of body, completed, in an unsized tree. | reject (rule 1014, no size bit) |
| box-read-reject | Sized segregated tree ending at offset 4100. Its last reads start in (4096, 4099], legal under the tree window. The creation-height read at 4100 then fails under the restored box window. | reject (rule 1014, outside the tree) |
| within-accept | The same tree ending at 4094. | accept |

In the Transaction file, the degrade-accept carries a second output after the crafted one, so its peek lands on a
real byte.

## 2. The root type (sigmastate 6.0.6)

`deserializeErgoTree` runs rule 1001 `CheckDeserializedScriptIsSigmaProp` on sized and unsized trees alike
(`ErgoTreeSerializer.scala:173-175`):
- a sized tree with a non-`SigmaProp` root degrades;
- an unsized one is rejected ("Cannot handle ValidationException, ErgoTree serialized without size bit.",
  `:204-207`).

The vectors hold three candidates:
- unsized `00 08 d3` (`SigmaProp(true)` root), which accepts;
- unsized `00 04 02` (`Int` 1 root), which rejects;
- sized `08 02 04 02`, which degrades by rule 1001 and accepts.

## Grades

| Runner | Window: degrade-accept | Window: peek / unsized / box-read rejects | Unsized Int root |
|---|---|---|---|
| rudolph | ✓ | ✓ | ✓ rejects |
| eni `862df85f` | **rejects** (both kinds) | ✓ | **accepts** (both kinds) |
| develop `1633e018` | ✓ (by coincidence, see below) | **all accept** | **accepts** (both kinds) |
| dasher `f2f4a94c` | **rejects** (both kinds) | ✓ | **accepts** (both kinds) |

- **eni's degrade-accept red** is the declared-size bound again (`wire-sized-tree-declared-size.md`). eni parses a
  sized body only within its declared 5 bytes and rejects on the end of input.
- **develop degrades on any error**, so it lands on the JVM's answer for the degrade-accept, but by coincidence. It
  has no windows, so it accepts every window reject.
- **dasher's Box entries** first graded as panicked: a SANTA runner defect, fixed 2026-09-28. ergots answers with a typed
  `ReaderError`, a clean rejection (`wire-tree-degrade-gate.md`).
- **Existing corpus:** unchanged for rudolph, eni and develop. dasher's `master` moved to the merged PR #17
  (`f2f4a94c`); besides these entries, its only reds are the four `sized_tree_declared_size` ones.

## Why it matters

- **Root type.** A transaction creating an output with an unsized non-`SigmaProp` tree is rejected by the JVM at parse
  and accepted by sigma-rust. Output scripts are not evaluated when the output is created, so nothing else catches it.
- **Window.** A body that overruns the tree window must degrade (sized) or reject (unsized), exactly where the JVM
  does. That includes the unchecked peek at the end of the input. Box reads after the tree are checked against the
  restored box window.

## Fix shape (from the JVM)

- Run the root-type check on unsized trees too, as a hard reject.
- Give each tree a 4096-byte window from its first byte, replacing the box window, and restore the box window after
  the tree.
- Check position before every read, but not before a peek.
- Treat a window trip inside a tree as the soft (degradable) error, and treat a peek past the end of the input as a
  hard one.
