# SANTA

**S**igma-**A**nchored **N**ode **T**est **A**pparatus — a cross-implementation
conformance test suite for Ergo consensus.

> **Built in the open, slice by slice.** The design takes shape from working
> deliveries rather than a big up-front spec. Its current state is written down in
> the [runner contracts](docs/contract/).

## What this is

Language-agnostic conformance **test vectors** + thin per-implementation
**runners** + CI, so multiple independent Ergo implementations can be checked
against the same canonical inputs and expected outputs — the way Ethereum's
[`execution-specs`](https://github.com/ethereum/execution-specs) (the executable
spec + test framework, formerly `execution-spec-tests` / EEST) lets
geth / besu / nethermind / reth prove consensus-equivalence.

The guiding principle is **"the wire is the spec"**: a vector is *raw serialized
bytes in → expected output out*, since every implementation already parses the
wire format. Expected outputs are anchored to **canonical oracles**, never to any
single implementation:

- **block validity → the chain** — a block on mainnet is valid by definition;
- **fine-grained eval outputs (value, cost, reduced sigma-tree) → the JVM reference
  node** (ergo-core / `sigma-state`), the de-facto spec.

Four tiers:

- **Wire tier** — serialization: bytes ⇄ structure (constants, boxes, trees, txs,
  headers). The broadest — every wallet/SDK serializes (ergots, sigma-rust, scorex,
  Fleet, …); and a `boxId` *is* the hash of serialized bytes, so it's squarely consensus.
- **Eval / transition tier** — operation-level (ErgoTree + context → typed value, with
  cost). Run by the consensus *libraries*, no full node required.
- **Transaction tier** — library-decidable tx validity given full inputs (script-verify
  + conservation / tokens / min-value / cost). No full node required; blessed by
  `ergo-core validateStateful`.
- **Block tier** — digest-state block validity: parent state digest + ≤10 headers +
  parameters + a full block *with ADProofs* → `valid?` + computed post-digest + cost.
  Library-decidable (Ergo's stateless-validation design — no UTXO DB, no sync); run by
  digest-capable validators (ergo-node-rust's `validation` seam, the gated JVM engine).

## Status

The **eval tier is closed and scaled**, and the conformance loop is already surfacing
genuine cross-implementation divergences — which is exactly its job. What runs today:

- ✅ **A blessed eval corpus — 49,898 entries across 480 vector files**: 2,026 produced by
  the JVM reference interpreter (`sigma-state`) from its own language specification, 47,810
  authored gap-fillers (oracle-blessed, never spec-copied) and 62 captured from testnet
  spends; 9,440 of them evaluate under a full context (`santa-eval/v6-fullctx`: the 62 captured, 2,359 authored
  `substConstants` rows that read `CONTEXT.headers`, the pre-header and `SELF`, 1,047 authored `patch` rows over
  the collections a context brings, 1,956 over the ones a node holds generic, 1,875 over R2 read as a register
  and every box of a transaction, 907 AvlTree calls given a box, a header, the context or a register's value as
  an item, and 1,234 AND / OR / XorOf calls over a register's, a variable's, the context's or a producer's collection);
  version-split
  into **v4** (18
  entries — below the JIT activation,
  activated 0 or 1: the one 4.x behaviour mainnet history needs, `BoolToSigmaProp` passing a
  `SigmaProp` through), **v5** (15,285 — the cumulative
  v5/mainnet method surface) and **v6** (34,595 — the v6 new-feature surface). Each entry is
  `ErgoTree bytes (+ input) → typed value + raw JIT cost`, committed with the
  `(activated, ergoTree)` version it was blessed under. 17 of them are **bounded files** (one entry and a
  `limits` budget, 1 GiB / 10 s): rows where an implementation can do unbounded work the reference never does
  (`Global.serialize` of a value shared 2^12 and more times); each runner runs each in its own process under
  that budget, and a hit grades `exceeded` on that entry alone
  ([runner-integration §3a](docs/contract/runner-integration.md#3a-bounded-files)).
- ✅ **A runner-agnostic orchestrator — `./conform`** (presence-as-state over `runners/*/`,
  one shared comparator, a per-runner **per-slice** 🎁/🪨 table). Seven runners wired today:
  - **Rudolph**: the JVM reference, the all-🎁 control that blessed the corpus.
  - **Dasher**: the pure-TS `ergots` library ([`ts-runner/`](ts-runner/)).
  - **Blitzen**, for `sigma-rust`:
    - `blitzen-develop`: a submodule, upstream `develop` (value-only), until upstream hosts the runner (in review
      as `santa/`, with the PR check's workflows); its entry then names that directory instead;
    - `blitzen-mwaddip`: a submodule, the `master` branch of the mwaddip fork (`--features jit-cost`). It took over from
      the fork's `eni` branch, whose runner was retired on 2026-09-29 after master reproduced it exactly.
  - **Comet**: the pure-TS **Fleet SDK**, wire tier only, graded on Fleet's public API: the `Constant`, `Box` and
    `Transaction` kinds, without the rules tied to the activated script version (its declared scope,
    [runner-integration §2](docs/contract/runner-integration.md#2-the-manifest--runnerjson)).
  - **Donner**: ergo-node-rust.
  - **Vixen**: arkadianet/ergo.

  Each is graded against the JVM-blessed `expected`. The runner is SANTA's; the implementation under test is a
  dependency. A runner may instead live in the implementation's own repo; the board then grades the implementation
  through an entry naming that directory
  ([runner-integration §1](docs/contract/runner-integration.md#1-a-runner-is-a-directory)).
- ✅ **A pull request's delta — `./santa-pr`**: an implementation's own runner (in its repo; SANTA needs no entry
  for it) graded at its base and head (or its working tree), compared entry by entry into a PR comment (newly red,
  now green, red another way, one row per vector file) and a per-entry summary, run by the implementation's own
  workflow through the [`santa-pr` action](.github/actions/santa-pr/README.md)
  ([runner-integration §6a](docs/contract/runner-integration.md#6a-one-runner-a-working-tree-and-a-pull-requests-delta)).
- ✅ **Live results — the loop is surfacing real divergences.** Dasher is **fully green
  across the entire eval tier** (every v5+v6 spec+authored slice, value, cost, and reject —
  2026-06-10); its remaining reds are roadmap not-impls (the growth ledger). Blitzen-eni
  (`sigma-rust`'s `ergo-node-integration` fork) has converged to **red 0 four times**
  (eval + wire + transaction, value *and* cost) — each time a new authored round surfaced
  the next genuine divergence class, routed it, and the fork closed it. The two newest
  rounds (both 2026-06-10): the **SFunc-arity witnesses** — the JVM rejects non-unary
  lambdas eagerly at closure creation, but sigma-rust (both branches) *and*
  arkadianet/ergo all **evaluated multi-arg lambdas to completion**; one authored family,
  the same over-accept class in three independent Rust implementations, fixed in eni the
  same day (the fourth convergence) — and the **atLeast children-cap pins**, where every
  conformer went red on a *different* arm (eni: cap checked after the bound≤0 degenerate;
  develop: bound>n over-reject at the cap boundary). Plain upstream `develop` misses the
  values the fork already fixed, plus the v6 surface. A 🪨 is the suite doing its job,
  never silenced.
- ✅ **A frozen runner contract** ([`docs/contract/runner-contract.md`](docs/contract/runner-contract.md))
  + a JVM blesser, the JVM reference runner (*Rudolph*), and a harness. A runner is
  **total**: it emits one faithful outcome for *every* entry — value + cost on success,
  else a coarse tag (`errored` / `not-implemented` / `panicked`) — and
  never drops, hides, or aborts the run on one. A conformer's **scope is chosen on the input side** (it runs the vector
  subset it claims; dasher's manifest declares version ≤ v6). **A divergence is the deliverable** — surfaced
  and routed, never silenced; a red gate means the suite is working.
- ✅ **Machine-checkable gates** — a JSON-Schema validator over the whole corpus (155 eval + 4 wire + 4 tx)
  and an end-to-end conformance gate. The CI seed: the tooling's unit tests run on every push (the
  `tools` workflow), the conformance grid on demand (`conform`).
- ✅ **A standing coverage manifest** ([`docs/coverage/eval-coverage.json`](docs/coverage/eval-coverage.json),
  `santa-coverage/v1`) — the per-family op / method / arm map of the eval corpus, read
  off each entry's **deserialized tree** (never script text): every node's op, every
  `MethodCall`'s `(typeId, methodId)`, and the tree shapes
  (version / size-bit / segregation / deserialize × accept / reject). Suite-gated
  current; a conformer diffs its method registry against `method_index` to see what
  the corpus exercises vs. not. See [`docs/coverage/`](docs/coverage/README.md).
- ✅ **Wire tier live** — `santa-wire/v1` byte-round-trip vectors, **213 entries**
  (`Constant` 178 · `Box` 11 · `SigmaBoolean` 7 · `Transaction` 17), JVM-canonicalized
  from ergots' `fixture-gen` + Fleet's `_test-vectors` seeds. Rudolph + Blitzen 213/213;
  Dasher 196 (no tx serializer — growth ledger); Comet 185 of the 206 in its declared scope
  (Fleet's honest gaps, recorded as findings; its 7 `SigmaBoolean` are declared out). An authored **reject arm** (`wire/v6/authored/`) pins bytes the JVM refuses at
  parse, each with an accept twin on the other side of the bound:
  - soft-fork SHeader constants
  - **ContextExtension bounds:** count ≥ 128, and variable id ≥ 0x80 (since sigma-state 6.0.5)
  - **ContextExtension values:** a v6-only type (`CheckV6Type`: Option, Header, UnsignedBigInt),
    and a value nested past the reader's 110-level cap
  - **parse depth:** the same cap on registers, tree bodies, segregated constants, SigmaBooleans and
    a Box nested in an extension, plus the levels a soft-fork-degraded tree leaves on the reader
    (also when the degrade is nested inside a tree that parses)
  - **creation-height bound:** a box, output or Box constant created above `Int.MaxValue`
  - **tree read window:** a box's tree reads within 4096 bytes of its own start (then the box
    window is back); an overrun degrades a sized tree and rejects an unsized one
  - **root type:** an unsized tree whose root is not a SigmaProp
  - **function type:** a register or extension value typed 0x70 (a sized tree's 0x70 constant degrades)
  - **unbound ValUse:** a tree using a variable it never defined, sized or not (not a soft-fork degrade)
  - **degrade gate:** a size-flagged tree degrades only on a `ValidationException`, so any other parse
    failure inside it rejects: an out-of-store placeholder, type code 0, an unknown SigmaBoolean opcode,
    a BigInt over 32 bytes, a `ValDef` id past `Int.MaxValue`, a malformed `FunDef` or function type
    parameter, a Box constant with a bad height or registers
  - **parse acceptance:** which node constructors check their operands at parse. Most check nothing,
    so `Exists` or `LogicalNot` on an `Int` parses. `Append`/`Slice` on a non-collection throw a
    `ClassCastException`; `EQ(Int, Long)` from tree v3 and `GT` on Booleans fail the builder's
    constraints; `BitOr` needs numeric operands; a `ConcreteCollection` item of the wrong type fails an
    `assert` (an `AssertionError`). The `TrueLeaf`/`FalseLeaf` opcodes `7f`/`80` parse and come back as the
    Boolean constants `01 01`/`01 00`, a non-identity round-trip; `SContext` method 11 parses and fails only
    when evaluated. `Upcast`/`Downcast` need a numeric input and target type; below tree v3 the builder
    upcasts mixed numeric operands (`Coll[Long](Plus(Int, Long))` parses there, not at v3) and a `ByIndex`
    index to `Int` (a `Long` index rejects there); a `BlockValue` item must be a `ValDef`; a register id
    must be 0 to 9; from v3 a `MethodCall` needs arguments (below v3 one without comes back as a
    `PropertyCall`). A node is built as soon as its bytes are read, so its failure rejects even where a
    later read would have degraded the tree; and a soft failure read first (a method not found, a type
    missing below v3, a type without methods) degrades the tree before a later construction failure
  - **nested failures and counts:** a `ValidationException` inside a Box constant degrades the outer
    size-flagged tree, while a nested unsized tree's failure rejects; a nested box's registers are read
    under the enclosing tree's version (in a v0 tree, a v3 box's SHeader register has no serializer and
    rejects; in a v3 tree, a v0 box's UnsignedBigInt register over 32 bytes fails its size bound and
    rejects); SigmaAnd, Apply and constants
    counts above 100000 and collection counts above `0xFFFF` reject (below that the parser reads on, and
    an input that ends first rejects even a sized tree); a constants count that wraps negative means
    none, and `getUShort` truncates a count to 32 bits before its range check (both non-identity);
    header bits 5–7 are kept; only a relation reads `85` as a Boolean pair; CTHRESHOLD needs
    `k <= n <= 255`, while CAND/COR data and SigmaAnd/SigmaOr nodes take 0 or more than 255 children
  - **`getUShort` outside trees:** a transaction's input, data-input and output counts, a proof's
    length, a box's index, and a collection's length or a BigInt's size in data read 2^32 + k as k
    (non-identity); 2^32 + 2^16 rejects
  - **evaluated values:** an extension or register value may be any `EvaluatedValue` — a Tuple or
    collection node (items unchecked at parse), `GroupGenerator`, or the `7f`/`80` opcodes (written back
    as constants) — while `HEIGHT`, a placeholder or an operation rejects; the transaction tier pins what
    happens at evaluation (a Tuple node is a `Coll`, not a pair; `Tuple(1, HEIGHT)` fails when converted; an
    output's `bytesWithoutRef` is written at the tree version of the first script that reads it)
  - **reader scope:** in the `BlockTransactions` kind (a block's transactions section), each
    transaction parses on a fresh reader, so no leaked levels or `ValDef` types reach the next one;
    one transaction's outputs share theirs
  - **tree version:** a tree whose header version is above the activated script version rejects, even
    when size-flagged: before its constants are read and after the size-bit rule, and also when it is nested
    in a Box constant, a register or an extension (a nested header without the size bit degrades the outer
    tree instead). The activated version is the context's: under (2, 2) a v3 tree rejects, and a block
    section of version 4 parses its transactions under (3, 3). A block below version 4 parses them outside
    any version context, where a tree of any version parses

  Repeated extension ids pin the JVM's collapse (last value, first position) as a non-identity
  round-trip, and so does a size-flagged tree whose declared size is wrong: the JVM ignores it when
  the body parses and re-serializes with the real size.

  Contract: [`docs/contract/runner-contract-wire.md`](docs/contract/runner-contract-wire.md).
- ✅ **Transaction tier live** — `santa-transaction/v1` schema; **4 captured vectors**
  (`vectors/transaction/v6/captured/`), each JVM-blessed via `ergo-core 6.0.2.1
  validateStateful`. Conformer stances: **Rudolph control** (gated `TxEngine`; the
  conform CI publishes ergo-core itself, cached by tag) · **Blitzen-eni `valid 4/4 ·
  cost 4/4` byte-exact** (the initial bless surfaced 3 genuine cost divergences —
  decomposed, routed, fixed in the fork, re-graded exact: the tier's first
  divergence→fix→convergence loop) ·
  **Blitzen-develop `valid 0/4`** (upstream bugs; the bigint-downcast
  seed exposes the tree-version bug eval cannot catch) · **Dasher `4 not-implemented`**
  (growth ledger) · **Comet out-of-scope** (wire-only; Fleet has no verifier).
  **Storage-rent vectors** (`storage-rent-*`, 25 entries over a synthetic context at the
  `StoragePeriod` height) pin every arm of the JVM's rent spend:
  - the gate
  - the fallbacks to the script
  - the final recreation verdict, including register equality as the JVM compares it (stored
    nodes and retained script bytes, not values)
  - the 32-bit-wrapping storage fee
  - the 50-unit `StorageContractCost`

  A stratified **mainnet sample** (122 real rent spends, v5 and v6 eras) gates rent ports on real
  history.

  **Sized-tree spends** (`sized-tree-spend`, `sized-tree-output-bytes`, `conjecture-child-count-wrap`, on the same
  synthetic context):
  - a box whose tree degraded under rule 1001 does not spend;
  - a leafless `CAND()` or `CTHRESHOLD(0, [])` spends with its secret-free 24-byte Fiat-Shamir proof, and with no
    proof does not; `COR()` never spends — with any proof it throws on its empty last child, caught as false;
  - a real 2-of-3 `CTHRESHOLD` over `ProveDlog` children pins the threshold crypto cost (11993 JitCost, the node's 15
    included); a threshold proof that ends before or inside its single coefficient still verifies, because the
    coefficients are read leniently and a leafless child carries no challenge;
  - a `CAND` of 40000 children — past the signed-Short range, carried by a context variable because an ErgoTree caps
    at 4096 bytes — spends with the Fiat-Shamir challenge over its wrapped child count;
  - inside the creating transaction, an output that declares its tree size wrongly keeps its tree as received in
    `propositionBytes`, while its `bytes`, its id and the signing message are re-encoded. A deterministic Schnorr
    proof pins the signing message.

  **Deserialize substitution** (`deserialize-substitution-spend`, 24 entries): how a spend replaces
  `DeserializeRegister` and `DeserializeContext` nodes.
  - A `ClassCastException` while decoding or typing the script, or from a register that is not a `Coll[Byte]`, is
    swallowed: the node stays, and throws only if it is evaluated.
  - An absent register's default replaces the node untyped. The node's ancestors are rebuilt through their
    constructors, so an `If`, `Negation`, `Plus` or `OptionGet` that reads the default's type rejects, even in a
    branch never evaluated.
  - A decoded `NoType` against a declared `SAny` rejects. `R1` is the box's own proposition bytes. A Boolean root is
    wrapped in `sigmaProp`.
  - A decode is charged twice its length once it completes.

  **The group nodes' static type** (`group-node-static-type`, 48 entries): `Exponentiate` and `MultiplyGroup` are
  typed `GroupElement` whatever their operands.
  - A box whose tree compares such a node with a group element spends, where the node's left operand is an Any-typed
    group element, or an Int behind `true ||`.
  - The substitution checks each `DeserializeContext` / `DeserializeRegister` script's type against the declared one
    before the script runs, taken or not: the node over the Int 1 against `Int` behind `true ||` does not spend, and
    the node over an Any-typed group element against `GroupElement` does.

  **The `substConstants` lift** (`substconstants-lift`, 80 entries at block version 4 and 60 at block version 3):
  `substConstants` turns each item of its new values into a constant by the item's class and the version of the tree
  being evaluated, before it reads the template.
  - A Header item has no lift below tree version 3, so the call throws whatever its position. Under a node's nine
    headers, `CONTEXT.headers` given as the new values with one position fails the JVM's length requirement; with
    nine positions past the template's constants, the box spends at tree version 3 and does not spend below it.
  - A collection holding a pre-header, `CONTEXT`, `Global` or a function lifts whole, so the box spends at every tree
    version when nothing is written.
  - A String item has no lift at tree version 3, wherever it comes from: a `Coll[String]` read from a context
    variable or from a register spends below tree version 3 and does not spend at 3.

  **A specialised collection's `patch`** (`patch-array-class`, 248 entries at block version 4 and 120 at block
  version 3): on the JVM, a `Coll[Byte]`, `Coll[Boolean]` or `Coll[Int]` held over a primitive array is a specialised
  collection, and its `patch` casts the patch's array to its own before anything is stored. Per receiver (37 a script
  builds, 27 the context brings) and tree version, one such spend and one control.
  - A patch held over another array throws although it is empty and nothing is replaced, so the box does not spend.
  - A node builds the pre-header's `parentId` and `votes` as generic collections, which take an empty patch of any
    class: those boxes spend.

  **The collections a node holds generic** (`generic-producers`, 129 entries at block version 4 and 58 at block
  version 3): besides the pre-header's two fields, R1 and R3 read as registers, and a `Coll[Byte]` or `Coll[Boolean]`
  collection expression in a register or a context variable (also nested, sliced or read by `getReg`).
  - On them `patch`, `startsWith` and `endsWith` with a collection of another array answer, so the box spends; on a
    header's field, a constant register or variable, `SELF.propositionBytes` or a token id they throw.
  - A `ByIndex` node with a default of another class, the index in range, hands back the generic receiver's item at
    tree version 0, so its box spends too.
  - The spent box's registers and the input's extension travel as the bytes their writer chose (an `83…` of booleans
    is not re-encoded as `85…`).

  **R2 and every box of a transaction** (`generic-producers-pairs`, 25 entries at block version 4 and 9 at block
  version 3; `generic-producers-census`, 231 and 87), spent in a transaction with a second input, a data input and two
  outputs:
  - R2 read as a register is built as `Box.tokens` is, a generic collection of pairs, so `++`, `ByIndex` with a default
    of another class, and `patch` / `startsWith` with an `indices` argument answer over it and the box spends.
  - The rule holds for a data input's, an output's and a second input's registers and for `getVarFromInput`: expression
    registers and R1 / R3 read as registers spend, constants, token ids, `propositionBytes` and an output's `id` do not.
  - A Tuple expression in a register or variable, read at its tuple type, throws whatever the call.

  **An AvlTree data method given an item of another kind** (`avltree-item-bind`, 221 entries at block version 4 and
  98 at block version 3): the parser does not check a method call's argument against the signature, so a keys or
  entries argument can be a 3-tuple constant or a collection of another item type. Per method, tree, carrier and
  outcome, one spend.
  - The JVM meets the item where it first uses it: `getMany` and the entry methods bind it before the operation's
    cost (no collection, no pair), every method casts a key or value to `byte[]` after it, `remove` reads each key
    inside its cost block, and `contains` / `get` refuse a key of another class at the reflective call. The box does
    not spend.
  - Where the item is never read, the call answers and the box spends: the tree's flag is off, or the proof does not
    belong to the tree and the item comes after the first entry (`update`, `insertOrUpdate`; `insert` from tree
    version 3).
  - `avltree-item-bind-review` (227 / 119) adds sigma-rust's whole trees at every tree version and, per class, the
    item from a register or a context variable (the spend carries sigma-rust's box and extension), a box, header or
    function as the item, an empty key (a collection, so the call answers), and entries whose key and value are both
    of another kind.

  **`AND` and `OR` read item by item** (`and-or-item-by-item`, 330 entries at block version 4 and 165 at block
  version 3): over its collection, each stops at the deciding item and charges for the items it read (AND 5 per 32,
  OR 5 per 64).
  - Over a long `Coll[Boolean]` decided early, the cost is the chunks read, not the collection's length.
  - A tuple constant holding a value of another kind after the deciding item answers; read before it, the value
    throws. An empty collection of another item type answers; a pair throws.
  - A collection expression evaluates every item first, so `anyOf(Coll(true, 1 / 0 == 1))` throws and the box does
    not spend, where `true || (1 / 0 == 1)` spends.
  - `and-or-item-by-item-context` (52 / 26): sigma-rust's whole trees over a register's, a variable's or a constant's
    collection, spent in its box with its extension. The eval tier adds every producer of the collection, every
    carrier of a tuple, and the neighbour nodes (`XorOf`, `Exists`, `ForAll`, `Filter`, `||` / `&&`, `SigmaAnd` /
    `SigmaOr`), each with its own reading.
  - `segregated-tuple` (120 / 90): a SEGREGATED tuple of three never reaches the node. The constant placeholder
    checks the constant against its type, a tuple of other than two items is no value of its type there, and the
    reduction throws, so the box does not spend; the same tuple inline, or inside a segregated collection or pair
    constant, reduces. A plain box: nothing in the context but the tree.
  - `and-or-item-by-item-producers` (240 / 171): sigma-rust's whole trees over more producers of the collection
    (`slice`, `append`, `updated`, `zip`, `reverse`, `ByIndex` with a default, an `Option`'s `get` / `getOrElse`,
    `Global.deserializeTo`, `executeFromVar`, `DeserializeRegister`), spent in its box with its extension. The eval
    tier adds the chunk boundaries over ten producers, 65,536 and 65,537 items, nested AND / OR, the kinds with no
    data form as an item, and the other callers of the counting `addSeqCost` (`indexOf`, `flatMap`, collection
    equality, `substConstants`).

  **A collection holds at most 100,000 items** (`collection-length-limit`, 44 entries at block version 4 and 20 at
  block version 3): sigma-rust's whole trees `sigmaProp(<producer>.size > 0)` for the six producers that can return
  more than they were given (`Append`, `Coll.flatMap`, `Coll.patch`, `Global.serialize`, `SigmaPropBytes`,
  `SubstConstants`), at the last accepted and the first refused count, constants inline and segregated, in a plain box.
  - The collection class's constructor refuses more than 100,000 items (`MaxArrayLength`), and `append` checks the
    sum before it copies, so the refused tree throws in the reduction and the box does not spend. The accepted
    `append` tree costs 805: the refused one is in reach of any block.
  - The eval tier adds every class of collection at the boundary (100,000 items accepted, 100,001 refused), the
    length-keeping operations on exactly 100,000 items, the method forms (`NoSuchMethodException` at any length), two
    refusals at once, and the declared counts at parse. A row whose root is a collection is authored under `SizeOf`:
    the collection is built first, so a refusal is the row's own, and the value is its length.
  - `flatMap` builds its result part by part: an item the result's array cannot hold is refused at its part, before
    the next lambda runs; a pair component of another class after the last lambda (the left before the right); the
    length last. One shared collection returned by every call makes a result of any length at a price that does not
    grow with it (the fan-out, at 131,072 and 4,194,304 items). A specialised part of another class (a `Coll[Byte]`
    or `Coll[Boolean]` constant, pairs by `zip`) is refused at its part too, its items read; an empty one is taken.

  **A tree above the activated version** (`tree-version-above-activated`, 12 entries at block versions 4 and 3):
  - a node reads a box from the UTXO set outside any version context, so a box whose tree is above the activated
    version is read, and only its script spend fails;
  - a data input with such a tree is valid, and the storage-rent path collects one without comparing versions;
  - below block version 4 a transaction may create an output with a tree of any version.

  **A tree deserialized while the script runs** (`tree-version-above-activated-eval`, 11 entries, block version 4):
  a Box constant in a script a Deserialize node decodes, `Global.deserializeTo[Box]`, or a Box among a `SubstConstants`
  template's constants — each re-parses the Box's tree through `deserializeErgoTree`, which compares it with the
  activated version, so a nested v4 tree rejects mid-reduction where the v3 twin spends. A v4 *template header* (read
  without `deserializeErgoTree`) is not compared — the asymmetry a parse-time check would miss.

  **The block-version edges** (`tree-version-block-version-edges`, 11 entries at `transaction/any/`, block versions
  0/2/5/128/200): the spend check has no floor at activated 2 (a v2 tree at block version 2 rejects); above the max
  supported version 3 a tree is accepted *unverified* at the initial cost alone, its proposition not reduced (the
  `SigmaProp(false)` twin spends); and the activated version is a signed byte, so block version 0 (−1) and 129–255
  (−128 to −2) reject every script spend.

  **Which block version activates scripts** (`block-version-source`, 8 entries at `transaction/any/`): the voted
  **parameters'** block version, not the header's. The JVM judges both the activated script version and the monotonic
  creation-height rule by `currentParameters.blockVersion`; the header's version only feeds PoW, serialization and
  `CONTEXT.preHeader`. These set the two apart (`parameters.blockVersion` ≠ `preHeader.version`) — an impl that reads
  the header's version diverges, and a script `CONTEXT.preHeader.version == 3` still sees the header's.

  Contract: [`docs/contract/runner-contract-transaction.md`](docs/contract/runner-contract-transaction.md).
- ✅ **Block tier live** — `santa-block/v1`, the **digest-state** shape: parent digest +
  ≤10 headers + parameters + block-with-ADProofs → `valid` + computed `post_digest` +
  `cost`. **4 captured testnet seeds** (block 2666 cost 39379 — the triple-anchored
  keystone · 111927 · 184137 · the epoch-boundary donor 2560), ADProofs-verified at
  bless time, **+ 7 authored mutation classes**, each JVM-confirmed to reject for its
  intended reason (version-gate rides the boundary donor — a donner-surfaced finding
  established the JVM's version check fires only at epoch boundaries; its re-authored
  form immediately caught both independent conformers trusting the block's
  self-declared extension over the handed pre-state params), **+ one synthetic block**
  (`kept-tree-bytes-in-block`): a box created and spent in the same block keeps the tree
  encoding it arrived in. That is why a block vector carries its transactions as the
  section's **bytes**, not node-API JSON — a JSON transaction re-writes its trees.
  The 4th seed (28474) awaits a canonical proof — the rust AVL prover emits a
  valid-but-non-canonical proof for data-input lookups, a latent serve-side consensus
  bug.
  Conformers: **Rudolph control** `3/3·3/3·3/3 + 5/5` · **donner** (ergo-node-rust's
  digest-state seam) **live** — building it surfaced + fixed two real enr consensus
  bugs before mounting · **vixen** (arkadianet/ergo) grading too — its debut surfaced
  a real block-cost divergence · other libraries grey (block application is the
  node's layer). Contract:
  [`docs/contract/runner-contract-block.md`](docs/contract/runner-contract-block.md).
- ✅ **Chain tier live** — `santa-chain/v1`; **14 files / 61 entries** across three
  families: **retargeting** (difficulty arithmetic) + **parameter-voting** (v6
  governance math) + **fork-vote gate** (v6 soft-fork activation guard). Value-only
  (no cost dimension). The voting and fork-vote-gate `v6/authored` slices carry a
  reject arm (`expected.error: "errored"`, contract §2). Provenance split:
  `any/captured` (2 — testnet-anchored retargeting points) · `any/authored` (3 —
  difficulty-damping clamp edges, EIP-37 arm settings-driven) · `v6/captured` (1 —
  testnet epoch-boundary voting point) · `v6/authored` (46 — threshold edges (4) +
  voting-window clamp (1) + soft-fork round lifecycle (7) + activation basis/edge/id-9
  insertion/sigma-rule/cleanup (8) + zombie checkpoint-flips (4) + hostile-reject
  classes (3) + lifecycle leniency (4) + tally order (3) + fork-vote gate window
  edges (8) + fork-vote gate preconditions (4)).
  Conformers: **Rudolph control** 52/52 (all chain slices green) · **donner LIVE**
  52/52 green — including the new fork-vote gate family 12/12 · **vixen LIVE** (27/34
  chain/v6/authored — 7 pre-existing Voting reds are findings, fork_vote_gate 12
  entries not yet mounted = their ledger) · blitzen nipopow-at-most deferred · dasher ledger.
  Contract:
  [`docs/contract/runner-contract-chain.md`](docs/contract/runner-contract-chain.md).
- ✅ **AuthDS tier live** — `santa-authds/v1`; grades the AVL+ batch **prover** (canonical
  proof bytes) and **verifier** (three independently-gradable levels: proof acceptance ·
  per-operation results · final digest) directly, below the ErgoScript layer every other
  tier reaches it through. **47 vendored entries**
  (`vectors/authds/any/vendored/` — 10 `avl_prove` + 37 `avl_verify`, ergots' own AVL-prover
  fixture corpus, every expectation re-derived through `jvm-blesser` rather than trusted
  from its Rust-blessed origin — `blessed_by: "jvm:scrypto-3.0.0"`). Conformers: **rudolph
  control** and **dasher (ergots)** both `proof 10/10 · digest 43/43 · accepted 37/37 ·
  results 33/33`, **red 0** — ergots matches the JVM on every graded entry · **vixen
  (arkadianet)** `accepted 30/30 · results 26/26 · digest 26/26`, red 0 + 17 not-implemented.
  Three confirmed findings, **all now closed**:
  pushed ergots `master` seeded an empty AVL tree as two sentinel leaves instead of one (all
  10 `avl_prove` entries, red 24 at ship; ergots pushed the fix `b533fe5` and all 20
  prove-side reds cleared on the 2026-08-03 re-grade, confirmed by conform CI
  `30836488512`). A separate release hazard survived it and was **re-probed 2026-08-04** —
  confirmed, but its direction was backwards and it is not recurring. npm records a `gitHead`
  and ships `src/` in the tarball, so `npm view <pkg>@<v> gitHead` + a diff of `npm pack`'s
  `package/src` settles any version locally (retiring the earlier "network-verified-at-the-time"
  hedge). `0.3.0` was published **faithfully** from `6ad72d3` — a commit carrying the sentinel
  fix that had **not been merged to `master`**, where the broken `da2a257` still sat. npm was
  not the odd artifact; **git `master` was stale**. Worse and previously unseen: `0.3.0`–`0.3.3`
  all declare that same `gitHead` while `0.3.3`'s content is demonstrably not that commit, so
  for those releases no commit reproduces the artifact. **`0.4.0` is clean** — `gitHead` =
  `master`, byte-identical, and graded green.
  The second finding — scrypto's `UnknownModification` as a fixed zero-length-key singleton
  poisoning the JVM verifier where ergots/`ergo_avltree_rust` treat it as a keyed,
  non-modifying lookup — turned out **not to be a consensus divergence at all**:
  `UnknownModification`, `UpdateLongBy` and `RemoveIfExists` have **zero** fully-qualified
  references in sigma-state 6.0.3 or ergo-core, so no ErgoScript or node path can construct
  them. The 13 fixtures exercising them were deleted (behaviour no consensus path reaches is
  irrelevant to SANTA), and with them gone the JVM and `ergo_avltree_rust` agree on **37/37** of the
  reachable corpus. The finding is kept and reframed — plus one prediction
  that did *not* land: the four adverse (malformed/mismatched/truncated/swapped-digest
  proof) fixtures were flagged as a possible reject-detection miss and came back fully
  green, ergots independently agreeing with the JVM. The third finding was vixen's:
  arkadianet accepted a self-consistent proof padded with extra nodes
  (`adverse-malicious-extra-nodes`) that the JVM and ergots reject, because its wrapper
  built the verifier without the operation bounds. arkadianet reproduced the red from the
  board and fixed it (`19091d47`). blitzen-mwaddip / donner not yet mounted
  (`avl_verify` only). Contract:
  [`docs/contract/runner-contract-authds.md`](docs/contract/runner-contract-authds.md).

Still greenfield, and where help is most wanted (see below):

- the tx-tier **authored reject arm**; more captured block seeds (incl. 28474's
  canonical proof);
- more **independent runners** — donner (ergo-node-rust, block tier) is routed and
  pending; alt impls welcome at every tier.

## Layout

```
docs/contract/     the frozen runner I/O contracts (eval · wire · transaction · block · chain · authds)
docs/coverage/     standing corpus coverage manifest (what the vectors exercise)
schema/            JSON Schemas for vectors + actuals, and the validator
vectors/eval/      the canonical eval corpus — the "nice list" (v4/, v5/ and v6/)
vectors/wire/      wire-tier round-trip vectors
vectors/transaction/  transaction-tier captured vectors (v6/captured/)
vectors/block/     block-tier digest-state vectors (captured + authored mutations)
vectors/chain/     chain-tier retargeting / voting / fork_vote_gate / header_votes vectors
vectors/authds/    authds-tier AVL+ prover/verifier vectors (any/vendored/)
jvm-blesser/       Scala: the blesser, the JVM reference runner (Rudolph), the harness
ts-runner/         Dasher — the ergots runner + the conformance gate
runners/           per-conformer dirs (rudolph · dasher · blitzen-develop · blitzen-mwaddip · comet · donner · vixen)
conform            the runner-agnostic orchestrator — runs every runner, prints the table
README.md          this file
```

## Re-blessing transaction vectors (maintainer only)

The transaction *blesser* is env-gated and never runs in CI — committed vectors are the
reproducible artifact. (The same gate also compiles rudolph's transaction **grading** arm:
the conform CI publishes ergo-core itself and sets `SANTA_TX_BLESSER=1`, so the canonical
grid carries the tx control row; a build without ergo-core degrades that arm to
`not-implemented`.) To re-bless (e.g. to add new seeds):

1. Clone [`ergoplatform/ergo`](https://github.com/ergoplatform/ergo) at tag `v6.0.7`.
2. From that clone: `sbt "avldb/publishLocal" "ergoWallet/publishLocal" "ergoCore/publishLocal"` — publishes `ergo-core 6.0.7` to `~/.ivy2/local`.
3. From `jvm-blesser/`: `SANTA_TX_BLESSER=1 sbt -batch "testOnly santa.CapturedTxFullTest"` — fetches each seed's header context from a testnet node API at `127.0.0.1:9053` and stages blessed JSON under `jvm-blesser/target/tx-vectors-full/`.
4. Copy the staged files into `vectors/transaction/v6/captured/` and commit.

**Oracle version.** The oracle pins `sigma-state` 6.0.7 and `ergo-core` 6.0.7 — the reference
node's v6.0.7 release. It moved from 6.0.6 on 2026-11-06, after the whole corpus re-derived
identically under the new pins: every rudolph actual (value, cost, verdict, reason) on all
seven tiers, and every blesser that regenerates committed files. Each vector's `blessed_by`
still names the oracle that first blessed it.

The bundled `jvm-blesser/src/test/resources/chain-testnet.conf` is pinned by the
[transaction runner contract](docs/contract/runner-contract-transaction.md) and must
not be changed without re-blessing.

## Contributing

SANTA is meant to be **community-owned conformance ground**: the more independent
Ergo implementations run the same vectors, the more the vectors are worth. Help is
genuinely wanted — especially:

- **Implementations under test** — wire a runner for your Ergo implementation
  (TypeScript, Rust, Scala, or anything that parses the wire format) against the
  [runner I/O contract](docs/contract/runner-contract.md). It's a thin adapter:
  vector in → `{value, cost, error}` per entry.
- **Vectors** — more eval-tier operations; wire-tier serialization vectors;
  captured-block vectors for the block tier; authored mutation / reject vectors.
- **Design** — the vector schema, the runner I/O contract, CI topology.

The eval-tier contract is frozen, but the wider design is still taking shape, so
**a conversation beats a big PR** — read the [runner contracts](docs/contract/), look at
`jvm-blesser/` and `ts-runner/`, and open an issue to talk through where you'd like to
plug in.

## Related

- [Ergo](https://ergoplatform.org/) — the protocol under test.
- [`sigma-rust`](https://github.com/ergoplatform/sigma-rust) — Rust consensus
  library; a convenience differential target (never the oracle).
- [`execution-specs`](https://github.com/ethereum/execution-specs) — Ethereum's
  executable spec + test framework (the former `execution-spec-tests` / EEST,
  consolidated in 2025); the project SANTA's structure is modeled on.

## Acknowledgements

- [**Fleet SDK**](https://github.com/fleet-sdk/fleet) — its serializer test vectors
  (`packages/serializer/src/_test-vectors`) are **vendored** into SANTA's **wire tier**:
  harvested and re-anchored through the JVM oracle, so each harvest doubles as a JVM-vs-Fleet
  differential pass (178 constants, 17 signed transactions, 7 boxes).
- [**ergots**](https://github.com/mwaddip/ergots)' `fixture-gen` wire fixtures are vendored too
  (boxes + sigma-booleans). Box round-trips from ergots and Fleet share one slice, each entry
  tagged by its framework.

## License

[MIT](LICENSE).
