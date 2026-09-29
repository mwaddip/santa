# Finding: extension and register values that are EvaluatedValues but not Constants (sigma-rust probe, confirmed on the JVM)

**Tiers:** wire (`santa-wire/v1`), transaction (`santa-transaction/v1`)  
**Surfaced:** 2026-09-29, a probe from the sigma-rust session (`~/projects/sigma-rust/prompts/santa-probe-evaluated-values.md`)
for ergo-node-rust's board item #16 (liveness). A transaction the JVM accepts can stall a Rust node that reads only
Constants there.  
**Status:** every row of both rounds holds on the JVM (sigma-state 6.0.6, ergo-core 6.0.6) as sigma-rust read it,
with these corrections:
- V1, V10 and D4 fail with an `AssertionError`, not a `ClassCastException`;
- the open rows V3 and V9 are invalid;
- D2 fails with a `RuntimeException`: the `DeserializeRegister` node is left in the tree and evaluated;
- C2 surfaces as a `MatchError` from ergo-core's failure log (§4).

The second round (§4) also answers whether `SContext` method 11 parses: it does, and it fails when evaluated. The
third round (§5) pins an output's bytes on the node's path (written at (1, 1), while the tx id is computed at the read
context), and non-pair tuples at `Value.checkType`. It also traces how a node thread can inherit a version context.

**Vectors:**

| File | Entries | Rows |
|---|---|---|
| `vectors/wire/v6/authored/Transaction.extension_evaluated_values.json` | 24 | X1–X15, N1–N6; C1, C2 and C2's twin |
| `vectors/wire/v5/authored/Transaction.extension_evaluated_values.json` | 4 | X15 and U1 below tree v3; C2 and its twin reject |
| `vectors/wire/v6/authored/{Box,Transaction}.register_evaluated_values.json` | 16 each | G1–G6, four more accepts (C1, D1 among them), six rejects |
| `vectors/wire/v6/authored/{Box,Transaction}.tree_parse_acceptance.json` | #23–#24 | `SContext` method 11, unsized and sized |
| `vectors/transaction/v6/authored/evaluated-values-spend.json` | 51 | V1–V10 with twins (#0–#14); T1–T8, C1/C2 and D1–D4 with twins, method 11 (#15–#43); X15 and C2's twin as an output's R4 (#44–#46); X15 at the dust and size boundaries (#47–#50) |
| `vectors/eval/v6/authored/Tuple.non_pair_type_check.json` | 6 | a triple at `EQ`, a `ValDef` and a lambda's argument, with pair twins |

Blessers: `AuthoredWireEvaluatedValues`, `AuthoredTxEvaluatedValues` and `AuthoredEvalNonPairTuple`. Each re-derives every verdict on the JVM and
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

## 4. The second round

### Where a `Tuple` node's value fails (T1–T8)

The node reaches the script as a `Coll` typed as a pair. `GetVar`, `ExtractRegisterAs` and `OptionGet` pass it on
unchecked (`transformers.scala:491-494`, `:579-582`, `:602-605`). The first check is at the node that consumes it,
`Value.checkType` (`values.scala:251-260`).

Every row uses the extension `{0: Tuple(1, 2)}` as a node (T6 and T7 use R4), next to its twin, the constant
`(1, 2)` (`58 02 04`).

| # | Script | Tuple node | Constant twin |
|---|---|---|---|
| T1 | `getVar[(Int, Int)](0).isDefined` | valid | valid |
| T2 | `getVar[(Int, Int)](0).get != (0, 0)` | **invalid**: `NEQ` checks its operands (`trees.scala:1226-1228`) | valid |
| T3 | `getVar[(Int, Int)](0).map({ (p: (Int, Int)) => 1 }).get == 1` | **invalid** (1) | valid |
| T4 | `getVar[(Int, Int)](0) == getVar[(Int, Int)](0)` | valid (2) | valid |
| T5 | `Coll(getVar[(Int, Int)](0).get).size == 1` | **invalid**: the collection checks its item (`values.scala:894`) | valid |
| T6 | `SELF.R4[(Int, Int)].isDefined` | valid | valid |
| T7 | `SELF.R4[(Int, Int)].get != (0, 0)` | **invalid** | valid |
| T8 | `CONTEXT.getVarFromInput[(Int, Int)](0, 0).isDefined`, a v3 tree | valid (3) | valid |

1. The lambda checks its argument (`values.scala:1074`). `Option.map` calls it by reflection, so the error surfaces as
   an `InvocationTargetException`.
2. `EQ` checks both operands, but `isValueOfType` checks only an `Option`'s outer class (`SType.scala:199`), and the
   two options compare equal.
3. `getVarFromInput` returns `Some(v.value)` when the RType matches (`CContext.scala:76-82`).

### Collections of `Tuple` nodes, and a function element type (C1, C2)

**C1.** The extension `{0: a Coll[(Int, Int)] holding the Tuple node (1, 2)}` (`83 01 58 86 02 04 02 04 04`) parses:
the item's type is the element type.
- **Converting it throws `ArrayStoreException`.** `ConcreteCollection.value` copies each item's value into an array of
  the element's class (`values.scala:882-885`), a `Tuple2` array, and the item's value is a `Coll`. So the spend is
  invalid.
- **With a SigmaPropConstant root** nothing converts, and the spend is valid.
- **As R4, with R5 read,** the spend is invalid: the first register read converts R4 too.

**C2.** The extension `{0: an empty Coll of (Int, Int) => Int}` (`83 00 70 02 04 04 04 00`) parses at tree v3 and
round-trips identically. Below v3 it rejects (rule 1008; the v5 file). The spend fails in three steps:
1. **The conversion throws.** `stypeToRType` has no case for a function of two arguments (`Evaluation.scala:51-55`).
2. **The failure log throws.** ergo-core's `verifyInput` then logs the failed input's context as JSON
   (`ErgoTransaction.scala:139-146`). That re-serializes the extension under the ambient context, the default (1, 1) in
   block validation. At (1, 1), `TypeSerializer` cannot write a function type (`TypeSerializer.scala:111`), so a
   `MatchError` escapes `validateStateful`.
3. **Both of the node's paths catch it.** Block validation runs inside `Try.flatMap` (`UtxoState.scala:138-139`), so
   the block fails. The mempool's `withVersions` (`ErgoMemPool.scala:286-296`) covers only a re-parse: its validation
   also runs outside any context, inside `Try.flatMap` (`UtxoStateReader.scala:54-60`), so the transaction is
   invalidated. Either way, the spend is invalid.

The one-argument twin (`Int => Int`) is valid, and so is C2 with a SigmaPropConstant root.

**SANTA's `TxEngine` now mirrors that `Try`:** a throw out of `validateStateful` is an invalid verdict. Before, it
escaped, and rudolph would have panicked on this entry.

### Deserializing the new kinds (D1–D4)

| # | Setup | JVM |
|---|---|---|
| D1 | R4 = `Coll[Byte](1, 1)` as a node, `sigmaProp(DeserializeRegister(R4, Boolean))` | valid (1) |
| D1 twin | R4 = `0e 02 01 01` | valid |
| D2 | R4 = GroupGenerator | **invalid** (2) |
| D3 | ext `{0: Coll[Byte](1, 1)}` as a node, `sigmaProp(executeFromVar[Boolean](0))` | valid (3) |
| D4 | ext `{0: Tuple(1, HEIGHT), 1: 0e 02 08 d3}`, `executeFromVar[SigmaProp](1)` | **invalid** (4) |
| D4 twin | without var 0 | valid |

1. `DeserializeRegister` matches any register value and reads `.value.toArray` (`ErgoLikeInterpreter.scala:17-36`).
2. The substitution fails, the `DeserializeRegister` node stays in the tree, and evaluating it throws: "Should be
   overriden in class sigma.ast.DeserializeRegister", a `RuntimeException`.
3. `DeserializeContext` takes a value of type `Coll[Byte]` and reads `.value` (`Interpreter.scala:110-126`).
4. After the substitution the JVM still evaluates through `CErgoTreeEvaluator.eval` (`Interpreter.scala:171-177`),
   which converts every extension value. Var 0 fails `Tuple.value`'s assert.

### An `Upcast` of a non-constant (U1)

`{0: Tuple(1, Upcast(HEIGHT, Long))}` under (2, 2) is written back as read. The pre-v3 strip only applies to an
`Upcast` of a Constant (`ValueSerializer.scala:362-393`).

### `SContext` method 11 (the question)

`MethodCall(CONTEXT, SContext method 11, [Byte 0])` parses in an unsized and in a size-flagged tree, and round-trips
identically (`tree_parse_acceptance` #23, #24).
- `getVarV5Method` is declared with info but no IR builder or Java method (`methods.scala:1750-1753`), and is listed for
  v5 and v6 (`:1766-1774`). Its type variable stays unbound.
- Evaluating it throws `NoSuchMethodException` for `Context.getVar(byte)` (`evaluated-values-spend` #43).
- sigma-rust also parses it and fails only when evaluating, so there is no L divergence.

## 5. The third round (sigma-rust's regrade follow-ups)

### X15 as an output's R4: the tx id and the output's bytes (item 1)

An output whose R4 is `Tuple(1, Upcast(1, Long))` (`86 02 04 02 7e 04 02 05`), confirmed on the node's path:
- **The transaction is read at (3, 3), as a v4 block's are** (`BlockTransactions.scala:184-202`). The tx id is computed
  at parse (`ErgoTransaction.scala:68`), and its message keeps the `Upcast`.
- **The output is written later, at (1, 1).** `ErgoBox.bytes` is first read in `verifyOutput`'s size checks
  (`ErgoTransaction.scala:163-176`), which run before any input script and outside any version context. So the output
  is written at the default (1, 1), and the `Upcast` of the constant is dropped.
- **Everything downstream of the output uses the stripped bytes.** Its id hashes them, and `stateChanges` stores them
  (`Insert(o.id, o.bytes)`, `ErgoState.scala:184`).

`evaluated-values-spend` #44 and #45 pin both halves:

| # | Script | Proof | JVM |
|---|---|---|---|
| 44 | `proveDlog(pk) && OUTPUTS(0).bytes.slice(3, 15) == 00 08 d3 01 00 01 86 02 04 02 04 02` (stripped) | Schnorr over the message, which keeps the `Upcast` | valid |
| 45 | the same, expecting the kept form | the same | **invalid** |

### C2's twin as an output's register (item 4)

An output whose R4 is an empty `Coll[Int => Int]` (`83 00 70 01 04 04 00`) parses at (3, 3).
- **`verifyOutput` writes the output at (1, 1),** where `TypeSerializer` has no case for a function type
  (`TypeSerializer.scala:111`). The result is a `MatchError`.
- **Both paths reject it.** The block fails (`UtxoState.scala:138-139`), and the mempool invalidates the transaction
  (`UtxoStateReader.scala:54-60`).

Vector: `evaluated-values-spend` #46, invalid.

### `Value.checkType` on a tuple that is not a pair (item 3)

A constant triple's value is a `Coll` (`Evaluation.toDslTuple`, `Evaluation.scala:99-102`), and `isValueOfType`
throws "Unsupported tuple type" for an `STuple` of arity other than 2 (`SType.scala:200-202`).

`eval/v6/authored/Tuple.non_pair_type_check.json` pins three check sites:

| # | Site | Triple | Pair twin |
|---|---|---|---|
| 0, 1 | `EQ`'s operands (`trees.scala:1206-1208`) | errored | true |
| 2, 3 | a `ValDef` (`values.scala:1027`) | errored | true |
| 4, 5 | a lambda's argument (`values.scala:1074`) | errored | true |

### The dust and size boundaries of an X15 output (sigma-rust's PR #52 request)

`verifyOutput`'s dust check (`value >= minValuePerByte × ErgoBox.bytes.length`, `ErgoTransaction.scala:171`,
`BoxUtils.scala:41`) and its size check (`out.bytes.length <= MaxBoxSize`, 4096, `:175`) measure the output as the node
writes it: at (1, 1), where X15's `Upcast` of a constant is dropped. So the output is two bytes shorter than at (3, 3).
Measured on the JVM:

| # | Output 0 | Bytes at (1, 1) / (3, 3) | JVM |
|---|---|---|---|
| 47 | R4 = X15, value 17280 = 48 × 360 | 48 / 50 | valid |
| 48 | the same, value 17279 | 48 / 50 | **invalid** (`txDust`) |
| 49 | R4 = X15, R5 = 4043 zero bytes, value 10⁹ | 4096 / 4098 | valid |
| 50 | the same with R5 = 4044 bytes | 4097 / 4099 | **invalid** (`txBoxSize`) |

An impl that measures at (3, 3) wants 18000 for #47 and sees 4098 bytes in #49, so it rejects both.

### The version context a thread inherits (item 2, by source; not reproduced on a node)

**The mechanism is confirmed on the JVM.** `VersionContext` is a `DynamicVariable`, so an `InheritableThreadLocal`.
A thread constructed inside `withVersions` inherits that version as its own value for life. A spike showed this with a
plain fixed thread pool: its worker was created by a task submitted inside `withVersions(3, 3)`, and it still reads
(3, 3) for tasks submitted later outside any context.

**The chain in ergo 6.0.6, from source:**
1. **The NodeViewHolder's threads are re-created on demand.** The NodeViewHolder runs on `critical-dispatcher`, a
   `thread-pool-executor` with `fixed-pool-size = 2` (`application.conf:597-604`). Akka 2.6.10's defaults for that
   executor are `allow-core-timeout = on` and `keep-alive-time = 60s` (akka-actor `reference.conf:489`, `:533`). So a
   NodeViewHolder thread idle for 60 s dies, and the thread that sends the next message constructs its replacement.
2. **Some senders run inside `withVersions`.** `/transactions/bytes` and `/transactions/checkBytes` wrap parsing and
   `validateTransactionAndProcess` in `withVersions(protocolVersion, protocolVersion)`
   (`TransactionsApiRoute.scala:189-201`, `:210-221`). Inside that scope, `validateTransactionAndProcess` calls
   `verifyTransaction` (`:165-177`), which asks an actor on `api-dispatcher`. That is a fork-join pool whose workers are
   also created by the submitting thread.
3. **The version can therefore propagate.** An API worker created in that scope inherits the version. If it is the
   next thread to message an idle NodeViewHolder, the replacement thread inherits the version too, and block
   validation on it writes outputs at v3+.

**If that happens,** such a node and a default-context node disagree:
- X15-like outputs keep the `Upcast`, so their box ids and stored bytes differ;
- C2-twin-like outputs are valid (no `MatchError`) where the other node fails the block.

That is a chain split between JVM nodes. Whether the timing occurs on a live node depends on traffic, and it has not
been reproduced. It is a question for the ergo developers.

## Grades

These are the default pins; blitzen-mwaddip is at fork master `08105652`. Both rounds together add 108 entries: 65 in
the first and 43 in the second. The existing corpus grades the same on every runner.

| Runner | First round (65) | Second round (43) | Red total |
|---|---|---|---|
| rudolph | 0 | 0 | 0 |
| blitzen-mwaddip `08105652` | 44 | 26 | 63 → 133 |
| blitzen-develop `1633e018` | 44 | 25 | 422 → 491 |
| dasher (ergots `3d48cd1a`) | 42 | 25 | 82 → 149 |
| vixen (arkadianet `bd9c1172`) | 10 | 3 | 138 → 151 |
| comet (Fleet) | 1 | 3: the v5 file, which it has no Transaction kind for | 28 → 32 |

### First round

**blitzen-mwaddip's 44** (blitzen-develop has the same 44):
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

### Second round

**blitzen-mwaddip's 26:**
- **Errors at parse.** Every row with a non-Constant errors at parse:
  - T1–T5 and T8 with the Tuple node (6);
  - C1 and C2, their SigmaPropConstant-root twins and C2's one-argument twin (6);
  - D1–D4 (4);
  - on the wire, C1, C2, C2's twin (3) and U1 (1), plus the register C1 and D1 values (2 per kind).
- **T7 is accepted** where the JVM's evaluation fails: V9's divergence again, through `NEQ`.
- **A cost divergence:** D4's twin (`executeFromVar[SigmaProp](1)` without var 0) is valid on both, but costs 12117
  against the JVM's 12112.
- **Green:** the Constant twins, D1's constant twin, T6, method 11 (both parse it and fail when evaluating it), and
  the v5 C2 rejects.

**blitzen-develop's 25:** the same, minus the cost red (it grades no cost).

**dasher's 25:** the same errors, and T7 accepted.

**vixen's 3:** it accepts C2 and its twin below tree v3 (an over-accept: the function type code is not a type before
v3), and it errors on U1.

### Third round, and the re-grade of fork master `3b23f47e`

**Fork PR #51 (`3b23f47e`, runner santa-blitzen `8428754`):** 133 → 59.
- **The pin change alone moves nothing.** Against `08105652`, the new runner's raw actuals are byte-identical to the
  old runner's (386 files).
- **At `3b23f47e`, 74 reds flip green and none are new.** The raw actuals change in exactly those 74 entries.
- **The 49 wire and transaction reds left** are round C's (empty conjectures and node sizes, the parse-acceptance C, E
  and L rows, the `Apply` root) and R1's (`sized-tree-output-bytes` #0, #1, #3; the storage-rent non-canonical
  script).

**The third round's 9 entries**, graded at `3b23f47e`:

| Runner | Red | Detail |
|---|---|---|
| rudolph | 0 | |
| blitzen-mwaddip | 1 | #46 errors at parse, where the JVM parses the transaction and finds it invalid; both reject it (1) |
| blitzen-develop | 6 | the three triples evaluate to true; #44, #45 error; #46 panics |
| dasher | 4 | the `EQ` triple evaluates to true; #44–#46 error |
| vixen | 0 | vixen mounts no transaction tier |

1. blitzen-mwaddip matches the JVM on #44/#45 (an output written at (1, 1), the message kept) and on every non-pair
   tuple row.

Board: mwaddip 60, develop 497, dasher 153, vixen 119 (arkadianet `3d554fe2`), comet 32, donner 10, rudolph 0.

**The dust and size rows (#47–#50).**
- **At fork master `3b23f47e`** blitzen-mwaddip rejects #47 and #49, because it measures the output at (3, 3). develop
  and dasher error on all four. Board: mwaddip 62, develop 501, dasher 157, the rest unchanged.
- **At PR #52's tip `d7c5b479`,** graded directly with runner `8428754`, exactly five entries change against
  `3b23f47e`, and all now match the JVM:
  - #46, #47 and #49 flip green, #47 and #49 at the JVM's costs;
  - #48 and #50 change only their reason text.

  Nothing else changes: blitzen-mwaddip would go 62 → 59.

**Reproducibility.** The blesser JVM now runs with `-XX:-OmitStackTraceInFastThrow`. HotSpot had dropped the message of
C1's `ArrayStoreException` in one bless, so `evaluated-values-spend` #31 and #33 differed between runs in their
`reason` only. The reason is diagnostic, not graded, and with the flag it is stable.
