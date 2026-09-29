# Finding: nested failures, count bounds and wraps, header bits, root forms (ergots' sized-tree requests, confirmed on the JVM)

**Tier:** wire (`santa-wire/v1`)  
**Surfaced:** 2026-09-28, vector requests from the ergots session for its `sized-tree-declared-size` work
(`~/projects/ergots/prompts/santa-sized-tree-requests-2026-09-28.md`)  
**Vectors:** `vectors/wire/v6/authored/{Box,Transaction}.tree_{nested_degrade,count_bounds,count_wrap,header_bits,root_forms,bool_pair_form,sigmaboolean_bounds}.json`
and `SigmaBoolean.conjecture_bounds.json`: 79 entries (20 rejects, 12 non-identity). Blesser
`AuthoredWireSizedTreeRequests`.  
**Later entries:** on 2026-09-29, sigma-rust's source findings appended entries to `nested_degrade`,
`count_bounds`, `bool_pair_form`, `sigmaboolean_bounds` and `conjecture_bounds`, and added `ushort_wrap`. They are
described in `sized-tree-source-findings.md`; the tables below cover the first 79.

Everything below is sigmastate 6.0.6 under the node's v6 parse context (3, 3). A size-flagged tree degrades only on
a `ValidationException` (`wire-tree-degrade-gate.md`).

## 1. Nested failures (`tree_nested_degrade`)

A Box constant carries a whole box, tree and registers included, parsed on the outer tree's reader.
- A `ValidationException` inside it reaches the OUTER tree's handler and degrades a size-flagged outer tree.
- That includes a nested v1 header without the size bit (rule 1012). `CheckHeaderSizeBit`
  (`ErgoTreeSerializer.scala:219`) runs before the nested tree's own handler (`:145`).
- It also includes rule 1019 (`CheckV6Type`, `ErgoBoxCandidate.scala:232`) on a well-formed register.

| # | Nested box | Outer tree | JVM |
|---|---|---|---|
| 0 | unsized tree `00 d1 fd` (rule 1002) | sized v0 | **reject**: unsized, the nested tree turns it into a `SerializerException` |
| 1 | the same, sized (`08 02 d1 fd`) | sized v0 | accept (the nested tree degrades) |
| 2 | tree header `01` (rule 1012) | sized v0 | accept, the outer tree degrades |
| 3 | the same | unsized v0 | **reject** |
| 4 | R4 = `Option[Int]` (rule 1019) | sized v3 | accept, degrades |
| 5 | the same | unsized v0 | **reject** (rule 1009 below v3) |
| 6 | R4 = an SHeader | sized v1 | **reject**: no data serializer below v3 |
| 7 | the same | sized v3 | accept, degrades (rule 1019) |
| 8 | 7 registers, R4 = `Option[Int]` | sized v3 | accept, degrades: R4 fails before the 7th register's id is looked up |

## 2. Count bounds (`tree_count_bounds`)

| # | Count | JVM |
|---|---|---|
| 0 | SigmaAnd, 100001 items | **reject**: `safeNewArray` refuses more than `MaxArrayLength` 100000 (`sigma/util/package.scala:7-12`) |
| 1 | SigmaAnd, 100000 items | accept: reads on, the tree window degrades it (rule 1014) |
| 2 | ConcreteCollection, 65536 items | **reject**: `getUShort` (`ConcreteCollectionSerializer.scala:28`) |
| 3 | ConcreteCollection, 65535 items | accept, degrades |
| 4 | Apply, 70000 arguments | accept, degrades: in range for `safeNewArray` (`SigmaByteReader.scala:53-59`) |
| 5 | Apply, 100001 arguments | **reject** |

**The layout** (as `tree_read_window`'s degrade). The declared size covers the prefix up to a `Coll[Byte]` whose bulk
read crosses the tree window. From the end of the declared size, the same bytes are the box's fields.

So an impl without a bound reads on and degrades, where the JVM rejects. The Transaction entries put a second output
after the candidate, so the parser's unchecked peek lands on a real byte.

## 3. Counts that wrap (`tree_count_wrap`, all non-identity)

- **The constants count** is `getUInt().toInt`, and a negative count means no constants
  (`ErgoTreeSerializer.scala:248-261`). A tree declaring 2^32 − 1 or 2^31 constants parses. It comes back as count 0
  (`18 07 ff ff ff ff 0f 08 d3` → `18 03 00 08 d3`).
- **`getUShort` truncates before it checks.** scorex-util 0.2.0's `VLQReader.getUShort` is `getULong().toInt`, then
  `require(0 <= x <= 65535)` (confirmed in the bytecode). So a count of 2^32 + k reads as k:
  - a Boolean collection `85` with count 2^32 is empty and comes back as `85 00`;
  - count 2^32 + 1 reads one bit and comes back as `85 01 01`;
  - a `ConcreteCollection` of `Int` with count 2^32 comes back as `83 00 04`.

**This corrects ergots' request.** It said "the JVM rejects it (getUShort)" of `00 85 80 80 80 80 10`. The JVM does
reject that exact tree, but only because its root is a `Coll[Boolean]` (rule 1001), not because of the count.

## 4. Header bits 5–7 (`tree_header_bits`)

Headers `28`, `48`, `88` and `e8` (sized) and `e0` (unsized) parse. The JVM reads the version, the size bit and the
segregation bit and ignores the rest. It keeps the header byte as stored.

## 5. Root forms (`tree_root_forms`)

| Tree | JVM |
|---|---|
| `00 da 14 01 d3 01 04 00`: Apply of a `Coll[SigmaProp]` | accept: it types as its element, SigmaProp |
| `08 06 da 04 00 01 04 00`: Apply of an Int | accept, degrades (rule 1001: `NoType`) |
| `00 db 65 01 fe`: `CONTEXT.dataInputs`, a `Coll[Box]` | **reject** (rule 1001, unsized) |
| the same, sized (`08 04 db 65 01 fe`) | accept, degrades |

## 6. The `85` pair form (`tree_bool_pair_form`)

Only the nine relations read an `85` after their opcode as a packed Boolean pair. For `Plus` or `Minus`, `85` starts a
Boolean collection constant, and the builder doesn't check an arithmetic operation's operand types at parse. So
`EQ(Plus(C, C), C)`, with `C = Coll[Boolean](true)`, parses. `EQ(true, true)` in the pair form `93 85 03` parses too.

## 7. SigmaBoolean conjectures (`SigmaBoolean.conjecture_bounds`, `tree_sigmaboolean_bounds`)

- **CTHRESHOLD** requires `0 <= k <= n <= 255`, and checks it after reading the children (`SigmaBoolean.scala:223`):
  - 256 children, or k above n, reject;
  - 255 children and k = 0 parse.
- **CAND and COR:** the parser builds them directly, and their constructors check nothing, so with 0 children they
  parse. Only `CAND.normalized` requires a non-empty list (`SigmaBoolean.scala:165`).
- **In a sized tree,** CTHRESHOLD's `IllegalArgumentException` is rethrown as a `SerializerException` and rejects.

The spend verdicts for CAND()/COR()/CTHRESHOLD(0, …) are transaction-tier work, not in these files.

## Grades

Default pins; each file per kind (Box and Transaction alike). Board: eni 63 → 105, develop 302 → 352, dasher 41 → 84;
the existing corpus is unchanged on every runner.

| File | blitzen-eni `0bd7199f` | blitzen-develop `1633e018` | dasher (ergots `f2f4a94c`) |
|---|---|---|---|
| nested_degrade | **accepts #0** (degrades the outer tree) | **accepts #0, #3, #6** | **accepts #0**; **rejects** the four degrades (#2, #4, #7, #8) |
| count_bounds | **accepts** the two `MaxArrayLength` rejects (#0, #5) | **accepts** all three rejects | **rejects** the three at-bound degrades |
| count_wrap | keeps the constants count as received (#0, #1: identity where the JVM writes count 0); **rejects** #2–#5 | the same | **rejects** all six; #3 and #4 **panic** (`RangeError: Invalid array length`: an array of 2^32) |
| header_bits | **rewrites** every header without bits 5–7 (`28` → `08`, `e0` → `00`) | the same | green |
| root_forms | **rejects #0** (Apply of a `Coll[SigmaProp]`) | **rejects #0**, **accepts #2** | **accepts #2** (`CONTEXT.dataInputs`: residual 1 of ergots' spec) |
| bool_pair_form | **rejects** `Plus`/`Minus` on Boolean collections | the same | **rejects** them (the pair lookahead on arithmetic opcodes) |
| sigmaboolean_bounds | **accepts** CTHRESHOLD(256) sized (degraded); **rejects** `CAND()` | the same | the same |
| conjecture_bounds | **accepts** k > n; **rejects** `CAND()`, `COR()`, `CTHRESHOLD(0, [])` | the same | **accepts** 256 children; **rejects** `CAND()`, `COR()`, both k = 0 |

- **dasher's reds** are ergots' "before" column in its spec. The branch fixes most of them. Its planned `getUShort`
  fix is the exception (§3).
- **The sigma-rust reds** are new here: the dropped header bits, the kept constants count, no `MaxArrayLength` bound,
  and `CAND()`/`COR()` rejected.
