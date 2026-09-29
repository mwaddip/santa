# Finding: extension and register values that are EvaluatedValues but not Constants (sigma-rust probe, confirmed on the JVM)

**Tiers:** wire (`santa-wire/v1`), transaction (`santa-transaction/v1`)  
**Surfaced:** 2026-09-29, a probe from the sigma-rust session (`~/projects/sigma-rust/prompts/santa-probe-evaluated-values.md`)
for ergo-node-rust's board item #16 (liveness). A transaction the JVM accepts can stall a Rust node that reads only
Constants there.  
**Status:** every row holds on the JVM (sigma-state 6.0.6, ergo-core 6.0.6) as sigma-rust read it, with two
corrections:
- V1 and V10 fail with an `AssertionError`, not a `ClassCastException`;
- the open rows V3 and V9 are invalid.

**Vectors:**

| File | Entries | Rows |
|---|---|---|
| `vectors/wire/v6/authored/Transaction.extension_evaluated_values.json` | 21 | X1–X15, N1–N6 |
| `vectors/wire/v5/authored/Transaction.extension_evaluated_values.json` | 1 | X15 below tree v3 |
| `vectors/wire/v6/authored/{Box,Transaction}.register_evaluated_values.json` | 14 each | G1–G6, two more accepts, six rejects |
| `vectors/transaction/v6/authored/evaluated-values-spend.json` | 15 | V1–V10 with twins |

Blessers: `AuthoredWireEvaluatedValues` and `AuthoredTxEvaluatedValues`. Each re-derives every verdict on the JVM and
fails loud on a wrong-reason reject or different bytes.

## 1. Parse: extension and register values

**Reading.** An extension value is read with `getValue`, cast to `EvaluatedValue` and checked by `CheckV6Type`
(`ContextExtension.scala:61-62`). A register value is read the same way (`ErgoBoxCandidate.scala:231-232`).

**The cast is a real `checkcast`, and `EvaluatedValue` is sealed** (`values.scala:310`). Its classes:
- `Constant`, which includes `TrueLeaf` and `FalseLeaf`: they are `ConstantNode`s (`:771`, `:782`);
- `GroupGenerator` (`:738`);
- `Tuple` (`:807-809`, whose comment reads "required as Tuple can be in a register");
- `ConcreteCollection` (`:856`).

**What is not checked.** Items are not cast at parse. The tuple count is a signed byte, and no one checks the arity
(`TupleSerializer.scala:27-36`).

**Writing back.** The transaction or box is written back with `putValue`:
- A Constant takes the constant path (`ValueSerializer.scala:362-371`).
- A `ConcreteCollection` of Booleans whose items are all Constants takes the packed `85` serializer
  (`values.scala:871-875`).

Extension `{0: v}` in a one-input transaction. Registers behave the same as R4 of a bare box or of a transaction output.

| # | v | JVM (both) | Written back |
|---|---|---|---|
| X1 | `7f` TrueLeaf | parses | `01 01` |
| X2 | `80` FalseLeaf | parses | `01 00` |
| X3 | `82` GroupGenerator | parses | as read |
| X4 | `83 02 04 04 02 04 04` Coll[Int](1, 2) | parses | as read |
| X5 | `83 02 01 01 01 01 00` Coll[Boolean](true, false), constant items | parses | `85 02 01` |
| X6 | `83 02 01 7f 80` the same with TrueLeaf/FalseLeaf items | parses | `85 02 01` |
| X7 | `83 00 01` an empty Coll[Boolean] | parses | `85 00` |
| X8 | `85 02 01` the packed form | parses | as read |
| X9–X12 | `Tuple` nodes of 2, 3, 1 and 0 items | parse | as read |
| X13 | `86 02 04 02 a3` Tuple(1, HEIGHT) | parses | as read |
| X14 | `83 01 04 a3` Coll[Int](HEIGHT) | parses | as read |
| X15 | `86 02 04 02 7e 04 02 05` Tuple(1, Upcast(1, Long)) | parses | as read at tree v3; `86 02 04 02 04 02` below |
| N1 | `73 00` placeholder | **reject**: `ArrayIndexOutOfBoundsException`, no constant store (`ConstantPlaceholderSerializer.scala:20`) | |
| N2 | `a3` HEIGHT | **reject**: `ClassCastException` | |
| N3 | `9a 04 02 04 04` Plus(1, 2) | **reject**: `ClassCastException` | |
| N4 | `86 02 04 02 73 00` a Tuple holding a placeholder | **reject** | |
| N5 | `86 02 04 02 e3 00 04` a Tuple holding GetVar[Int](0) | **reject**: rule 1019. `CheckV6Type` walks a Tuple's item types (`data/.../ValidationRules.scala:190`) | |
| N6 | `86 80` + 128 × `04 02` | **reject**: the count reads −128, `NegativeArraySizeException` | |

The register files carry G1–G6, as rows X3, X4, X1, X5, X13 and X11 above. They add `Tuple(1, 2)`,
`Coll[Int](HEIGHT)` and the six N rows as R4.

## 2. The two questions

**X15: which version applies when the node computes the tx id?** The version of the parse.
- **The id is computed at parse.** `ErgoTransaction.serializedId` is an eager `val` over `messageToSign`
  (`ErgoTransaction.scala:68`), so it is computed inside `ErgoTransactionSerializer.parse`.
- **v4+ blocks** parse each transaction under `(blockVersion - 1, blockVersion - 1)` (`BlockTransactions.scala:184-202`),
  so (3, 3) for a v6 block. From tree v3 the serializer keeps the `Upcast`, and the id hashes the bytes as read.
- **Earlier blocks** parse outside any context, under the default (1, 1) (`VersionContext.scala:58-61`). So does
  anything else that sets no context.
- **Below tree v3** the serializer writes an `Upcast` of a Constant as the Constant (`ValueSerializer.scala:157-169`).
  The strip only takes effect in the constant case, and the id hashes that form.
- **Confirmed on the JVM:** kept under (3, 3); stripped under (3, 2), (2, 2), (1, 1), (0, 0) and (3, 0).
- **The vectors:** the v6 file pins "kept"; the v5 file pins the strip at (2, 2).

**G3/G4: which bytes do the ids hash?** Measured on the JVM, with R4 = `7f` and R4 = the constant Boolean
collection:
- a standalone parsed box keeps its bytes as received, and its id hashes them;
- a transaction output's box is built from its candidate, so its bytes are the written-back form and its id hashes
  that;
- the tx id hashes `messageToSign`, which holds the written-back output and extension.

This is the same rule as the tree bytes in `wire-tree-parse-acceptance.md`.

## 3. Evaluation (`evaluated-values-spend`)

**Extension values.** `toSigmaContext` converts every extension value before the script runs
(`ErgoLikeContext.scala:158-161`), and a `SigmaPropConstant` root skips that (`Interpreter.scala:210-217`).
- `Tuple.value` casts each item to `EvaluatedValue` with an `assert` (`values.scala:819`,
  `CollectionUtil.scala:188-193`). So `Tuple(1, HEIGHT)` throws an `AssertionError`.
- A Tuple node's value is a `Coll[Any]`, but `stypeToRType` of a 2-item `STuple` is a pair type
  (`Evaluation.scala:37-40`). Reading it as a pair fails the evaluator's type check (`values.scala:233-248`).

**Registers** convert all at once, on the box's first register read (`CBox.scala:28`, `:77-94`).

| # | Setup | JVM |
|---|---|---|
| V1 | ext `{0: Tuple(1, HEIGHT)}`, tree `sigmaProp(true)` (evaluated) | **invalid**: `AssertionError` in the conversion |
| V2 | the same ext, tree `00 08 d3` (a SigmaPropConstant root) | valid: nothing converts |
| V3 | ext `{0: Tuple(1, 2)}` node, `getVar[(Int, Int)](0).get._1 == 1` | **invalid**: "Invalid type returned by evaluator" |
| V3 twin | ext `{0: (1, 2)}` as the constant `58 02 04` | valid |
| V4 | ext `{0: GroupGenerator}`, `getVar[GroupElement](0).get == groupGenerator` | valid |
| V5 | ext `{0: Coll[Int](1, 2)}` node, `getVar[Coll[Int]](0).get.size == 2` | valid |
| V6 | ext `{0: 7f}`, `getVar[Boolean](0).get` | valid |
| V7 | ext `{0: a 3-item Tuple}`, tree `sigmaProp(true)` | valid: any arity converts (`Evaluation.scala:41-48`) |
| V8 | rent: an expired `sigmaProp(true)` box, an empty proof, var 127 = `7f` | valid: the `Short` cast throws inside the `Try`, so verification falls back to the script (`ErgoInterpreter.scala:77-84`) |
| V8 | the same with a P2PK box | invalid: the fallback needs a proof |
| V9 | R4 = `Tuple(1, 2)` node, `SELF.R4[(Int, Int)].get._1 == 1` | **invalid**: the same type check as V3 |
| V9 twin | R4 = the constant `(1, 2)` | valid |
| V10 | R4 = `Tuple(1, HEIGHT)`, R5 = Int 1, `SELF.R5[Int].get == 1` | **invalid**: reading R5 converts R4 too, an `AssertionError` |
| V10 twin | R4 = `Tuple(1, 2)` | valid |
| V10 | the V10 box with `sigmaProp(true)`, which reads no register | valid |

**A box whose R4 is `Tuple(1, HEIGHT)` is register-poisoned.** It can be created: the output parses. But any script
that reads any of its registers fails, while a script that reads none spends it.

**V9 is an existing divergence** on sigma-rust's fork master. That master already parses a register tuple of constants
(`ParsedTupleExpr`) and reads it as a real pair.

## Grades

These are the default pins; blitzen-mwaddip is at fork master `08105652`. 65 entries are new: 50 wire and 15
transaction. The existing corpus grades the same on every runner.

| Runner | Reds on the new entries | Red total |
|---|---|---|
| rudolph | none | 0 |
| blitzen-mwaddip `08105652` | 44 | 63 → 107 |
| blitzen-develop `1633e018` | the same 44 | 422 → 466 |
| dasher (ergots `3d48cd1a`) | 42 | 82 → 124 |
| vixen (arkadianet `bd9c1172`) | 10 | 138 → 148 |
| comet (Fleet) | 1: the v5 file, which it has no Transaction kind for | 28 → 29 |

**blitzen-mwaddip's 44:**
- **Accepts that error.** Every extension accept errors: X1–X15 and the v5 X15, 16 in all. The N rejects are green.
  Every register accept but `Tuple(1, 2)` errors: G1–G6 and `Coll[Int](HEIGHT)`, 7 per kind.
- **Over-accept: the register Tuple with count `0x80`** (#13, both kinds). Master reads the count as an unsigned
  byte and parses 128 items, where the JVM reads −128 and rejects. This is sigma-rust's N6 concern, and it is already
  live in registers, because master reads register tuples today.
- **Transaction rows.** Every V row with a non-Constant errors at parse (11). **V9 is accepted** where the JVM's
  evaluation fails: the existing divergence sigma-rust expected. The Constant twins are green.

**dasher's 42:**
- the wire accepts error, as on blitzen (16 + 7 per kind);
- it rejects the `0x80` register tuple, as the JVM does;
- its 12 transaction reds are the same as blitzen's, V9 accepted included.

**vixen's 10.** vixen parses GroupGenerator, collection and tuple nodes and the `7f`/`80` opcodes. Its reds:
- it errors on items that are not constants (X13, X14, G5, `Coll[Int](HEIGHT)`) and on the `Upcast` (X15 in both
  files);
- in a transaction output it writes `7f` and the constant Boolean collection back as received (G3, G4 in the
  Transaction kind), where the JVM re-encodes them. Its Box kind re-encodes them correctly.

vixen mounts no transaction tier.
