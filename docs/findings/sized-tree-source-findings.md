# Finding: sigma-rust's source reading of the sized-tree follow-ups, confirmed on the JVM

**Tiers:** wire (`santa-wire/v1`), eval (`santa-eval/v2`), transaction (`santa-transaction/v1`)  
**Surfaced:** 2026-09-29, a probe from the sigma-rust session. It read sigmastate v6.0.6 (`ab0b15ce`) while fixing
the reds of `wire-sized-tree-requests.md` and `tx-sized-tree-spend-and-output-bytes.md`, and asked for JVM
confirmation.  
**Status:** all seven items hold on the JVM (sigma-state 6.0.6), under the node's v6 parse context (3, 3). Item 6's
list of `getUShort` sites was incomplete; the three missing sites are vectored too.

**Vectors** (appended entries keep every earlier entry byte-identical):
- `vectors/wire/v6/authored/{Box,Transaction}.tree_bool_pair_form.json`: #3–#13 (item 1);
- `vectors/wire/v6/authored/SigmaBoolean.conjecture_bounds.json`: #7–#12, and
  `{Box,Transaction}.tree_sigmaboolean_bounds.json`: #3–#8 (item 2);
- `vectors/wire/v6/authored/{Box,Transaction}.tree_count_bounds.json`: #6–#9 (item 3);
- `vectors/wire/v6/authored/{Box,Transaction}.tree_nested_degrade.json`: #9–#10 (item 4);
- `vectors/eval/v6/authored/substConstants_template_forms.json`: 3 entries, new (item 5);
- `vectors/wire/v6/authored/{Box,Transaction,Constant}.ushort_wrap.json`: 4, 5 and 4 entries, new (item 6);
- `vectors/wire/v6/authored/{Box,Transaction}.tree_parse_acceptance.json`: #20–#22 (item 7);
- `vectors/transaction/v6/authored/sized-tree-spend.json`: #10–#12 (item 2's spend rows).

Blessers: `AuthoredWireSizedTreeRequests`, `AuthoredWireBoxTreeParse`, `AuthoredEvalSizedTreeRequests`,
`AuthoredTxSizedTreeRequests`. Each re-derives every verdict on the JVM and fails loud on a wrong-reason reject, a
wrong degrade rule or different re-serialized bytes.

## 1. The `85` pair form after the other two-argument opcodes

Only the nine relations peek for `85` and read a packed Boolean pair (`Relation2Serializer.scala:40-51`). The other
ten two-argument opcodes go through `TwoArgumentsSerializer`, which reads two values (`TwoArgumentsSerializer.scala:21-25`),
so an `85` after them starts a Boolean collection constant. The shape is `tree_bool_pair_form` #0's:
`BoolToSigmaProp(EQ(Op(C, C), C))`, with `C = Coll[Boolean](true)` (`85 01 01`).

| # | Op | JVM | Why |
|---|---|---|---|
| 3–7 | Multiply `9c`, Division `9d`, Modulo `9e`, Min `a1`, Max `a2` | parse, identity | `arithOp` builds the `ArithOp` with no operand check (`SigmaBuilder.scala:707-712`; `ArithOp`, `trees.scala:704`) |
| 8–10 | BitOr `f2`, BitAnd `f3`, BitXor `f5` | **reject** | `BitOp` requires numeric operands (`trees.scala:913`): an IAE, rethrown as a `SerializerException` (`ErgoTreeSerializer.scala:191-193`) |
| 11–13 | the same three, size-flagged | **reject** | the `SerializerException` is not a `ValidationException`, so the tree does not degrade |

Plus (#0) and Minus (#1) were already in the file.

## 2. Conjecture sizes

- **CAND and COR data** read their child count with `getUShort` into `safeNewArray`, and neither constructor checks
  anything (`SigmaBoolean.scala:80-93`, `:149`, `:185`). So 256 children parse, up to 65535.
- **CTHRESHOLD's `require(0 <= k <= n <= 255)`** runs after the children are read (`:223`). Its k and n are
  `getUShort`s too.
- **The `SigmaAnd`/`SigmaOr` nodes** (`ea`/`eb`) read a `getUIntExact` count into `safeNewArray`
  (`SigmaTransformerSerializer.scala:20-30`). `mkSigmaAnd`/`mkSigmaOr` build them unchecked
  (`SigmaBuilder.scala:515-519`).

`SigmaBoolean.conjecture_bounds`:

| # | Bytes | JVM |
|---|---|---|
| 7 | CAND(256 × TrueProp) `96 80 02 d3…` | parses, identity |
| 8 | COR(256 × TrueProp) `97 80 02 d3…` | parses, identity |
| 9 | CTHRESHOLD(256, [TrueProp]) `98 80 02 01 d3` | **reject**: k > n |
| 10 | CTHRESHOLD with k = 2^32 + 1, `98 81 80 80 80 10 01 d3` | parses as CTHRESHOLD(1, [TrueProp]), back as `98 01 01 d3` |
| 11 | CAND with count 2^32 + 1, `96 81 80 80 80 10 d3` | parses as CAND([TrueProp]), back as `96 01 d3` (added) |
| 12 | CAND with count 2^32 + 2^16, `96 80 80 84 80 10` | **reject**: truncates to 65536, out of range (added) |

`{Box,Transaction}.tree_sigmaboolean_bounds`: all of these parse and round-trip identically.

| # | Tree |
|---|---|
| 3 | the SigmaProp constant CAND(256 × TrueProp), `00 08 96 80 02 d3…` |
| 4 | the same with COR |
| 5 | `SigmaAnd()`, `00 ea 00` |
| 6 | `SigmaOr()`, `00 eb 00` |
| 7 | `SigmaAnd` of 256 × `sigmaProp(true)`, `00 ea 80 02 08 d3…` |
| 8 | `SigmaOr` of 256 × `sigmaProp(true)` |

The spend rows (`sized-tree-spend`):

| # | Spent tree | Proof | JVM | Why |
|---|---|---|---|---|
| 10 | `SigmaAnd()` node | none | **invalid** | `allZK` calls `CAND.normalized` (`CSigmaDslBuilder.scala:134-136`), which requires a non-empty list (`SigmaBoolean.scala:165`): the reduction throws |
| 11 | `SigmaOr()` node | none | **invalid** | `anyZK` → `COR.normalized` (`:140-142`, `:201`), the same |
| 12 | `SigmaAnd` of 256 × `sigmaProp(true)` | none | **valid** | `CAND.normalized` skips every TrueProp and returns TrueProp |

**The empty node and the empty constant differ.** The `CAND()` *constant* (#3) is not normalized and spends with its
24-byte Fiat-Shamir proof. The `SigmaAnd()` *node* is evaluated, and the evaluation throws before any proof is
checked.

## 3. The constants count

`deserializeConstants` allocates the constants with `safeNewArray` (`ErgoTreeSerializer.scala:254`). It refuses
more than 100000 before reading any constant. The JVM has no bound at 4096.

Each tree is size-flagged and segregated (`18`), and its first constant is a `Coll[Byte]`.

| # | Count | Layout | JVM |
|---|---|---|---|
| 6 | 100001 | the bulk read crosses the tree window | **reject**: `safeNewArray` |
| 7 | 100000 | the same | degrades (rule 1014), identity |
| 8 | 4097 | the same | degrades (rule 1014), identity |
| 9 | 4097 | the `Coll[Byte]` is 64 bytes, but the input ends first (Box: 36 bytes left; Transaction: the last output, 3 bytes left) | **reject** |

#9's reject: scorex-util's reader throws an `IllegalArgumentException` ("Not enough bytes in the buffer: 64").
`deserializeErgoTree` rethrows it as a `SerializerException` (`:191-193`, reading "Tree version (0) is above activated
script version (3)"). That is not a `ValidationException`, so the size flag does not help. An impl that degrades a
tree whose count is above 4096 resumes after the declared size (4), reads the box's remaining fields, and accepts.

## 4. A nested unsized tree whose root is not a SigmaProp

This is like `tree_nested_degrade` #0 (rule 1002), with rule 1001 instead. The nested box's tree is `00 04 02`, whose
root is `Int 1`; the outer tree is size-flagged (`18`).

| # | Nested tree | JVM |
|---|---|---|
| 9 | `00 04 02`, unsized | **reject** |
| 10 | `08 02 04 02`, sized (the twin) | the nested tree degrades on its own; the outer parses, identity |

In #9, the nested tree's rule 1001 (`ErgoTreeSerializer.scala:174`) becomes the unsized tree's `SerializerException`
("Cannot handle ValidationException, ErgoTree serialized without size bit."). That rejects even inside the sized outer
tree.

## 5. substConstants templates (`substConstants_template_forms`)

The tree is the spec vector's `substConstants` function, as in `substConstants_declared_size_u32`. Each call replaces
constant 0 with `sigmaProp(false)`.

| # | Template | Result | Why |
|---|---|---|---|
| 0 | `38 05 01 08 d3 73 00` (bit 5) | `38 05 01 08 d2 73 00` | the header byte is written back as read (`ErgoTreeSerializer.scala:367`) |
| 1 | `18 07 ff ff ff ff 0f 08 d3` | `18 03 00 08 d3` | a count that wraps negative means no constants (`:248-261`, through `deserializeHeaderWithTreeBytes`, `:269-273`); position 0 is out of range, so nothing is replaced; count 0, the size recomputed (`:371-374`) |
| 2 | `10 ff ff ff ff 0f 08 d3` (unsized) | `10 00 08 d3` | the same, with no size |

## 6. `getUShort` outside trees (`ushort_wrap`)

scorex-util's `getUShort` is `getULong().toInt`, then the 0..65535 check. So 2^32 + k reads as k, and the JVM writes
the object back with k: a non-identity round-trip. 2^32 + 2^16 truncates to 65536 and fails the check, where a 16-bit
mask would read 0. Each file carries such a reject twin.

sigma-rust named four sites. A grep for `getUShort` in the 6.0.6 source finds three more that apply to wire kinds
(a search for the name; a wrapper under another name would be missed):

| Site | Where | Vectors |
|---|---|---|
| inputs, data-inputs and outputs counts | `ErgoLikeTransaction.scala:148`, `:155`, `:172` | `Transaction.ushort_wrap` #0–#3 |
| a box's index | `ErgoBox.scala:218` | `Box.ushort_wrap` #0–#2 |
| a proof's length (added) | `ProverResult.scala:40` | `Transaction.ushort_wrap` #4 |
| a collection's length in data (added) | `CoreDataSerializer.scala:132` | `Box.ushort_wrap` #3 (an R4 `Coll[Byte]`), `Constant.ushort_wrap` #0, #1, #3 |
| a BigInt's size (added) | `CoreDataSerializer.scala:112` (and `:119` for UnsignedBigInt) | `Constant.ushort_wrap` #2 |

**Ids.** A transaction's id is computed over the re-encoded bytes (`bytesToSign`). A standalone parsed box keeps its
bytes as received, and its id with them (`ErgoBox.scala:222`). The wire kinds grade the re-serialized bytes, not the
ids.

**Blocks, by source (not pinned by a vector).** ergo-core parses a block's transactions with the same serializer
(`ErgoTransaction.scala:497-502` → `ErgoLikeTransactionSerializer.parse`). A transaction's id hashes its re-encoded
bytes (`ErgoLikeTransaction.scala:100`, `bytesToSign` at `:192-198`). So the transactions root a header commits to does
not see the encoding. That suggests a miner can write a count as 2^32 + k in a block the JVM accepts, which an impl that
range-checks the full value would reject. No block-tier vector covers this yet.

## 7. A `ConcreteCollection` item of the wrong type

`ConcreteCollectionSerializer.parse` asserts each item's type against the declared element type
(`ConcreteCollectionSerializer.scala:38`). The build has no elide flag, so the assert throws an `AssertionError`. No
handler in `deserializeErgoTree` catches it, so it rejects whether the tree is sized or not.

| # | Tree | JVM |
|---|---|---|
| 20 | `BoolToSigmaProp(EQ(SizeOf(Coll[Int](Long 1)), Int 1))`, `00 d1 93 b1 83 01 04 05 02 04 02` | **reject**: `AssertionError` |
| 21 | the same, size-flagged | **reject** |
| 22 | the twin with `Coll[Int](Int 1)` | parses, identity |

rudolph grades an `AssertionError` as `errored`: its wire arm wraps the call in `Try`, and `NonFatal` matches an
`AssertionError`.

## Grades

These are the default pins; blitzen-mwaddip is at fork master `e3d77b17`. 77 entries are new.

The existing corpus grades the same on every runner except blitzen-mwaddip. Its new master turns 35 old reds green
and adds no new ones, as sigma-rust's local sweep predicted.

| Runner | Reds on the new entries | Red total |
|---|---|---|
| rudolph | none | 0 |
| blitzen-mwaddip `e3d77b17` | 21 | 113 → 99 |
| blitzen-develop `1633e018` | 59 | 363 → 422 |
| dasher (ergots `3d48cd1a`) | 29 | 53 → 82 |
| vixen (arkadianet `bd9c1172`) | 34 | 104 → 138 |

comet and donner mount none of these files.

**blitzen-mwaddip's 21** are the items its master does not follow yet:
- the CAND/COR and SigmaAnd/SigmaOr sizes (`conjecture_bounds` #7, #8; `tree_sigmaboolean_bounds` #3–#8), all
  errored;
- the three node spends (`sized-tree-spend` #10–#12), errored because the box does not decode;
- the mistyped collection item (`tree_parse_acceptance` #20, #21), accepted.

Every item its master follows grades green, including the added sites (proof length, data lengths, BigInt size) and
the 2^32 + 2^16 reject twins.

**blitzen-develop's 59:**
- the arithmetic ops on collections errored (10);
- the sized BitOps accepted, rewritten by a pair misread (6);
- the constants count 100001 and the input-ends case accepted (4);
- the nested Int root accepted (2);
- the mistyped item accepted (4);
- the CAND/COR and SigmaAnd/SigmaOr sizes errored (14);
- CTHRESHOLD k = 256 accepted as k = 0, and the k and count wraps errored (3);
- every `getUShort` wrap errored (10);
- `substConstants`: header bit 5 dropped, and the count wraps errored (3);
- the node spends errored (3).

**dasher's 29:**
- BitOp on collections accepted (12);
- the mistyped item accepted (4);
- every `getUShort` wrap errored (12);
- **the empty `SigmaAnd()` node spends** (`sized-tree-spend` #10, valid where the JVM's evaluation throws).

**vixen's 34:**
- BitOp on collections accepted (12);
- the constants count 100001 and the input-ends case accepted (4);
- the mistyped item accepted (4);
- the `getUShort` wraps errored (12);
- the `substConstants` count wraps errored (2).

The 2^32 + 2^16 reject twins are green everywhere: no runner masks to 16 bits. They guard the truncating fixes.
