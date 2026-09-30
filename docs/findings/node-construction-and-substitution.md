# Node construction at parse, and substitution at spend

**Asked by:** the ergots session, 2026-09-30, for its spec `docs/specs/2026-09-30-jvm-node-construction-design.md`
(branch `jvm-node-construction`, `1c6ddde`), section "SANTA: requests".
**Oracle:** sigma-state and ergo-core 6.0.6. Every `file:line` below is in sigma-state's `v6.0.6` tag.
**Vectors:**
- wire: `vectors/wire/v6/authored/{Box,Transaction}.tree_parse_acceptance.json`, entries #25 to #47, appended;
- transaction: `vectors/transaction/v6/authored/deserialize-substitution-spend.json`, 24 entries.

Every verdict below was first probed on the JVM (throwaway spikes), then blessed. Each blesser fails loud on a wrong
verdict or a wrong reason.

## 1. At parse: construction and serializer checks (wire)

A node is built right after its own bytes are read, and at parse through `DeserializationSigmaBuilder` first. A
failure there is never a `ValidationException`. `deserializeErgoTree` rethrows an `IllegalArgumentException` as a
`SerializerException` (`ErgoTreeSerializer.scala:191-195`), and only a `ValidationException` degrades a size-flagged
tree (`:197`). So each failure below rejects, sized or not.

| # | Tree | JVM | Why |
|---|---|---|---|
| 25 | sized root `Upcast(true, Long)` (`08 04 7e 01 01 05`) | reject | `require(input.tpe.isInstanceOf[SNumericType])` (`trees.scala:398`) |
| 26 | sized root `Upcast(Coll[Int](), Long)` | reject | the same |
| 27 | sized root `Downcast(true, Byte)` | reject | `trees.scala:431` |
| 28 | sized root `Downcast(Coll[Int](), Byte)` | reject | the same |
| 29 | sized root `Upcast(Int 1, Long)` | degrade (1001) | builds; the root is a Long |
| 30 | sized root `Upcast(Int 1, Boolean)` | reject | `asNumType` on the target type (`NumericCastSerializer.scala:22`): `ClassCastException` |
| 31 | v0 `EQ(SizeOf(Coll[Long](Plus(Int 1, Long 2))), Int 1)` | accept | below v3 the builder upcasts mixed numeric operands (`SigmaBuilder.scala:674-683`), so `Plus` is a Long and the item assert passes |
| 32 | the same at v3 | reject | no upcast from v3 (`:757-758`): `Plus` is an Int, and the item assert (`ConcreteCollectionSerializer.scala:38`) throws `AssertionError` |
| 33 | v0 `ByIndex(Coll(true), Long 0)` | reject | below v3 the index is `upcastTo(SInt)` (`ByIndexSerializer.scala:29-33`); its assert fails for a Long (`syntax.scala:168-177`) |
| 34 | the same at v3 | accept | the index is taken as it is |
| 35, 36 | `BlockValue([Int 1], SigmaProp(true))`, unsized and sized | reject | each item is cast to `BlockItem` (`BlockValueSerializer.scala:39`) |
| 37 | `BlockValue([ValDef(1, Int 1)], ...)` | accept | |
| 38, 39 | `SELF.R10[Int]`, and register id `0x80` | reject | `findRegisterByIndex(id).get` (`ExtractRegisterAsSerializer.scala:28`): `NoSuchElementException` |
| 40 | `SELF.R9[Int]` | accept | |
| 41 | root `DeserializeRegister(R10, SigmaProp)` | reject | the same lookup (`DeserializeRegisterSerializer.scala:28`) |
| 42 | root `DeserializeRegister(R9, SigmaProp)` | accept | |
| 43 | v3 `MethodCall(CONTEXT, method 11, [])` | reject | from v3, `assert(args.nonEmpty)` (`MethodCallSerializer.scala:52-55`) |
| 44 | the same at v0 | accept, **non-identity** | written back as a `PropertyCall`: `dc … 00` becomes `db …`, one byte shorter |
| 45 | v3 `MethodCall(CONTEXT, method 11, [Byte 0])` | accept | |
| 46 | sized, declared 12: `If(EQ(Upcast(true, Long), Long 0), Coll[Byte](4083), …)` | reject | the `Upcast` fails at candidate offset 10, before the window |
| 47 | the same with `Upcast(Int 1, Long)` | degrade (1014) | the `Coll[Byte]` crosses the tree window; the next read trips it |

**#44: a pre-v3 `MethodCall` with no arguments.** A `MethodCall`'s companion is `PropertyCall` when `args` is empty
(`values.scala:1350`), so it is serialized with the `PropertyCall` opcode and without the argument count. The
`MethodCall` serializer itself asserts that there are arguments (`MethodCallSerializer.scala:27`). From v3 the parser
makes the same check, citing it (`:52-55`). A transaction or box holding such a tree gets a different id when the JVM
writes it back.

**#46 and #47: the order.** Both trees are laid out like `Box.tree_read_window` #0: a size-flagged v0 tree declared
12 bytes, whose `Coll[Byte]` bulk read runs from candidate offset 17 to 4100. With a numeric input (#47), the read of
`If`'s third child trips rule 1014 at 4100, the tree degrades to its declared 12 bytes, and the box resumes at offset
17 (height 1, no tokens, `R4 = Coll[Byte](4077)`). With `true` (#46), the `Upcast` is built at offset 10 and its
`require` throws first. An impl that makes its type checks after reading the body meets the window first, and
degrades #46 too.

## 2. At spend: substituting Deserialize nodes (transaction)

The mechanism:
- **Reduction.** A tree with a Deserialize node is reduced by `reductionWithDeserialize` (`Interpreter.scala:240-265`).
  From V6 it charges the tree's bytes × 2 (`CostPerTreeByte`, `:88`), then runs `applyDeserializeContextJITC`
  (`:149-157`): `everywherebu(strategy(substDeserialize))`, then `toValidScriptTypeJITC` (`:598-602`).
- **The `ClassCastException` catch.** Kiama's `strategy` catches a `ClassCastException` and returns `None`
  (`core/.../sigma/kiama/rewriting/Rewriter.scala:180-191`). The node stays where it is, and throws ("Should be
  overriden") only if it is evaluated. The catch covers `substDeserialize` alone.
- **Rebuilds.** `everywherebu` rebuilds each ancestor of a replaced node through its constructor, by reflection. A
  constructor that reads the new child's type and throws rejects the spend, wrapped in
  `InvocationTargetException`, even in a branch that is never evaluated.
- **`DeserializeRegister`** (`ErgoLikeInterpreter.scala:17-37`):
  - the register comes through `ErgoBoxCandidate.get`, which synthesizes R0 to R3 (`ErgoBoxCandidate.scala:69-83`);
  - `eba.value.toArray` throws `ClassCastException` for a register that is not a `Coll[Byte]`;
  - a decoded type other than the declared one is a `sys.error`;
  - an absent register gives the default, untyped.
- **`DeserializeContext`** (`Interpreter.scala:110-126`) takes a `Coll[Byte]` variable only. A decoded type other
  than the declared one fails rule 1000.
- **The root.** `toValidScriptTypeJITC` wraps a Boolean root in `BoolToSigmaProp` and throws for anything but Boolean
  or SigmaProp.

Notation:
- `BI = ByIndex(Tuple(0, 0), 0)`, typed SAny, since a tuple is a collection of SAny;
- `F(x) = Filter(x, (i: Int) => true)`;
- `GV = OptionGet(GetVar(1, SAny))`;
- `DR(T, d) = DeserializeRegister(R4, T, d)`;
- `dead(c) = sigmaProp(If(false, c, true))`.

| # | ergots | Tree | R4 / var 1 | JVM | Why |
|---|---|---|---|---|---|
| 0 | S3j | `dead(EQ(DR(SAny), GV))` | R4 = `OptionGet(BI)` | valid | the decode throws in `OptionGet`'s constructor (`transformers.scala:600-601`); swallowed |
| 1 | L1 | `sigmaProp(DR(Int) == 1)` | R4 = `If(true, 1, SizeOf(OptionGet(BI)))` | invalid | the same; the node stays, and is evaluated |
| 2 | | the same | R4 = `If(true, 1, 2)` | valid | decodes as an Int; `1 == 1` |
| 3 | S3 | `dead(EQ(DR(SAny, F(BI)), GV))` | R4 = `F(BI)` | valid | the decode completes; the type read casts SAny (`transformers.scala:121`); swallowed |
| 4 | S15 | `dead(EQ(DR(Int), 1))` | R4 = Int 1 | valid | `value.toArray` throws `ClassCastException`; swallowed |
| 5 | | `sigmaProp(DR(Int) == 1)` | R4 = Int 1 | invalid | the node stays, and is evaluated |
| 6 | S2 | `dead(EQ(DR(SAny, F(BI)), GV))` | none | valid | the default replaces the node; `EQ`, `If` and `BoolToSigmaProp` read no child type when rebuilt |
| 7 | S1 | `dead(GT(SizeOf(DR(SAny, F(BI))), 0))` | none | valid | `SizeOf` and `GT` read none either |
| 8 | S8 | `dead(EQ(If(true, DR(SAny, F(BI)), GV), GV))` | none | invalid | the rebuilt `If` reads all three types (`trees.scala:1313`): `ClassCastException` |
| 9 | S5 | `dead(GT(Negation(DR(Int, Coll[Int]())), 0))` | none | invalid | the rebuilt `Negation` requires a numeric input (`trees.scala:882`) |
| 10 | S6 | `dead(OptionGet(DR(Option[Boolean], Coll[Boolean]())))` | none | invalid | the rebuilt `OptionGet` casts to `SOption` |
| 11 | S9 | `dead(GT(Plus(DR(Int, F(BI)), 0), 0))` | none | invalid | the rebuilt `Plus` reads both types (`trees.scala:704-708`) |
| 12 | S10 | `dead(EQ(DR(SAny), GV))` | R4 = `Apply(Int 0, [Int 0])` | invalid | decoded `NoType` (`values.scala:1247-1251`) is not SAny: `sys.error` |
| 13 | S16 | `dead(EQ(DeserializeContext(SAny, 1), GV))` | var 1 = `F(BI)` | valid | rule 1000's type read casts; swallowed |
| 14 | S16b | the same | var 1 = `OptionGet(BI)` | valid | the decode throws; swallowed |
| 15 | S16c | the same | var 1 = `Apply(Int 0, [Int 0])` | invalid | rule 1000, not soft-forked |
| 16 | R1 | `10 01 04 04` + `sigmaProp(DR(R1, Coll[Int])(0) == placeholder 0)` | | valid | R1 is SELF's proposition bytes (`ErgoBoxCandidate.scala:72`); they decode as `Coll[Int](2)` |
| 17 | R1 | the same with constant 0 = Int 1 | | invalid | `2 == 1` |
| 18 | R1 | `sigmaProp(SizeOf(DR(R1, Coll[Long])) == placeholder 0)` | | invalid | decodes as `Coll[Int]`: `sys.error` |
| 19 | R1 | `dead(EQ(DR(R1, SAny), GV))`, unsized v0 | | invalid | the bytes start with `00`: `InvalidTypePrefix`, not a cast |
| 20 | root | root `DR(SigmaProp, true)` | none | valid | the Boolean root is wrapped: `sigmaProp(true)` |
| 21 | root | root `DR(SigmaProp, false)` | none | invalid | `sigmaProp(false)` |
| 22 | root | root `DR(SigmaProp, Int 1)` | none | invalid | neither Boolean nor SigmaProp |
| 23 | root | root `DR(SigmaProp)`, no default | none | invalid | the node stays, and is evaluated |

**R1 (#16 to #19).** Read as a value, the segregated tree `10 01 04 …` is a `Coll[Int]` constant: type `10`, length
`01`, and the item `04`, which is 2 in zigzag. The decoder ignores the rest of the tree. The unsized tree `00 …`
starts with type code 0, so its decode throws `InvalidTypePrefix`. That is a `SerializerException`, which nothing
catches, so the spend is invalid although the branch is dead.

**What a rebuild throws (#8 to #11).** TxEngine's reason names only the outermost exception,
`InvocationTargetException`. The blesser also reduces each of these spends directly on sigma-state's interpreter and
checks the cause:
- #8 and #11: `SAny$ cannot be cast to … SCollection`, from `Filter`'s `def tpe = input.tpe` (`transformers.scala:121`);
- #9: `IllegalArgumentException: requirement failed: invalid type Coll[SInt$]`;
- #10: `SCollectionType cannot be cast to … SOption`.

## 3. The decode charge

`deserializeMeasured` charges twice the script's length after `ValueSerializer.deserialize` returns
(`Interpreter.scala:99-107`, `CostPerByteDeserialized = 2` at `:81`). A decode that throws is not charged. Two pairs
pin it:
- **#3 and #6** have the same tree. #3 decodes `F(BI)`, 17 bytes, and costs 34 more than #6, which has no R4.
- **#13 and #14** have the same tree. #13 decodes `F(BI)` from variable 1 and costs 34 more than #14, whose decode
  throws.

## 4. A fix found on the way: identity hashes in reasons

TxEngine wrote a failure's reason as the exception's class and message, and some messages print an object's identity
hash (`[B@4b023973`, `SigmaByteReader@3ee4b252`). That changes from run to run, so a re-bless rewrote the vector:
- `evaluated-values-spend` #50, from `3f75e14`;
- this file's #19.

`TxEngine.reasonOf` now writes each identity hash as `@<hash>`. Reasons are not graded.

## 5. Grades (2026-09-30)

| Runner | Implementation | Reds on the new entries |
|---|---|---|
| rudolph | the JVM | 0 |
| dasher | ergots `953e6b3c` | 37: spends #0, #3, #4, #6, #7, #13, #14, #16, #20 (invalid where valid) and #1, #19 (valid where invalid); wire #25 to #28, #30, #32, #33, #35, #36, #38, #39, #43, #46 in both kinds |
| blitzen-mwaddip | sigma-rust fork master `3f8c2633` | 36: spends #0, #4, #13, #14, #20 (invalid where valid), #3, #6, #7 (errored where valid), #8, #11 (errored where invalid); wire #25 to #28, #30, #31, #35, #36, #38, #39, #43, #44, #46 in both kinds |
| blitzen-develop | sigma-rust develop `1633e018` | 36: the same spends; wire the same, with #32 in place of #31 |
| vixen | arkadianet `5d62fd58` | 4: #32 and #44 in both kinds (vixen has no transaction tier) |
| donner, comet | | none graded: donner has no wire or transaction tier, and comet grades wire up to v5 |

dasher reports no cost for these spends, so its entries are graded on validity alone.
