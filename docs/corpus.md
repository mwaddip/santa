# What the corpus pins

A walk through SANTA's vectors, tier by tier and family by family: what each pins about the reference's behaviour.
The vector files are the authority: every entry carries its own prose (`script` on the eval tier, `description`
elsewhere), and each tier's contract in [`docs/contract/`](contract/) defines its shapes. Where a family's size is
given, it is the size when this page was written; `./validate` prints the current ones.

- [Eval](#eval)
- [Wire](#wire)
- [Transaction](#transaction)
- [Block](#block)
- [Chain](#chain)
- [AuthDS](#authds)
- [NiPoPoW](#nipopow)

## Eval

About 50,000 entries in 486 files, each `ErgoTree bytes (+ its inputs) → typed value + raw JIT cost`, committed with
the `(activated, ergoTree)` version it was blessed under.

- **By provenance:** 2,026 produced by the reference interpreter (`sigma-state`) from its own language
  specification; 47,930 authored (blessed by the reference, never copied from a specification); 62 captured from
  testnet spends.
- **By version:**
  - **v4**, 18 entries, below the JIT activation (activated 0 or 1). These pin the one 4.x behaviour mainnet history
    needs: `BoolToSigmaProp` passing a `SigmaProp` through.
  - **v5**, about 15,000 entries: the cumulative v5 (mainnet) method surface.
  - **v6**, about 35,000 entries: the v6 surface.
- **Envelopes:** `santa-eval/v1` (a closed tree), `v2` (an input binding), `v3` (per-input extensions), `v4` (SELF's
  registers), `v5` (SELF's extension, keys 0 to 255), and `v6-fullctx`, a real spending context: boxes, headers, the
  pre-header and the extensions. 9,440 entries use `v6-fullctx`:
  - the 62 captured;
  - 2,359 authored `substConstants` rows that read `CONTEXT.headers`, the pre-header and `SELF`;
  - 1,047 `patch` rows over the collections a context brings;
  - 1,956 over the collections a node holds generic;
  - 1,875 over R2 read as a register, and every box of a transaction;
  - 907 AvlTree calls given a box, a header, the context or a register's value as an item;
  - 1,234 AND / OR / XorOf calls over a register's, a variable's, the context's or a producer's collection.
- **Bounded files:** 17 files hold one heavy entry each, with a `limits` budget (1 GiB, 10 s). They are rows where an
  implementation can do unbounded work the reference never does (`Global.serialize` of a value shared 2^12 times and
  more). Each runner runs each bounded file in its own process under that budget; going over grades `exceeded` on
  that entry alone.
- **SELF's form** (`Self.node_form_tree_v1` to `_v5`, `_span`): SELF holds a size-flagged tree whose root is not a
  SigmaProp as its bytes, as a node does, and a tree a node cannot read as its parse. The same reads of SELF (its
  value, id, bytes, script, registers, creation info, `INPUTS(0) == SELF`, `selfBoxIndex`) run in every envelope, on
  a tree the writer refuses and on its writable twin, and SELF's bytes follow the size field when it lies or bytes
  follow the tree.
- **Coverage:** [`docs/coverage/`](coverage/README.md) maps every op, `MethodCall` and tree shape the eval corpus
  exercises, read off each entry's deserialized tree.

## Wire

1,508 entries, each `bytes → the reference's re-serialization`, or a refusal. By kind:

| Kind | Entries |
|---|---|
| Transaction | 525 |
| Box | 417 |
| ErgoTree | 347 |
| Constant | 187 |
| SigmaBoolean | 20 |
| BlockTransactions (a block's transactions section) | 12 |

- **The vendored round-trips** (`wire/v5/vendored/`) come from ergots' `fixture-gen` and Fleet's serializer test
  vectors, re-anchored through the reference. Each entry is tagged with its source project.
- **A round-trip need not be the identity.** An `ErgoTree` or `Constant` the reference reads leniently comes back in
  its canonical form (`expected_bytes_hex`).

### The reject arm

The reject arm (`wire/v6/authored/`) pins bytes the reference refuses at parse, each with an accept twin on the
other side of the bound:

- soft-fork SHeader constants;
- **ContextExtension bounds:** a count of 128 or more, and a variable id of `0x80` or more (since sigma-state 6.0.5);
- **ContextExtension values:** a v6-only type (`CheckV6Type`: Option, Header, UnsignedBigInt), and a value nested past
  the reader's 110-level cap;
- **parse depth:** the same cap on registers, tree bodies, segregated constants, SigmaBooleans and a Box nested in an
  extension. It also covers the levels a soft-fork-degraded tree leaves on the reader, including when the degrade is
  nested inside a tree that parses;
- **creation-height bound:** a box, output or Box constant created above `Int.MaxValue`;
- **tree read window:** a box's tree is read within 4096 bytes of its own start, and then the box's window is back.
  An overrun degrades a sized tree and rejects an unsized one;
- **root type:** an unsized tree whose root is not a SigmaProp;
- **function type:** a register or extension value typed `0x70`. A sized tree's `0x70` constant degrades instead;
- **unbound ValUse:** a tree using a variable it never defined, sized or not. This is not a soft-fork degrade;
- **degrade gate:** a size-flagged tree degrades only on a `ValidationException`, so any other parse failure inside it
  rejects. That covers:
  - an out-of-store placeholder;
  - type code 0;
  - an unknown SigmaBoolean opcode;
  - a BigInt over 32 bytes;
  - a `ValDef` id past `Int.MaxValue`;
  - a malformed `FunDef` or function type parameter;
  - a Box constant with a bad height or registers;
- **parse acceptance:** which node constructors check their operands at parse. Most check nothing, so `Exists` or
  `LogicalNot` on an `Int` parses. The ones that do:
  - `Append` / `Slice` on a non-collection throw a `ClassCastException`;
  - `EQ(Int, Long)` from tree v3, and `GT` on Booleans, fail the builder's constraints;
  - `BitOr` needs numeric operands;
  - a `ConcreteCollection` item of the wrong type fails an `assert` (an `AssertionError`);
  - `Upcast` / `Downcast` need a numeric input and target type;
  - below tree v3 the builder upcasts mixed numeric operands (`Coll[Long](Plus(Int, Long))` parses there, not at
    v3), and a `ByIndex` index to `Int` (a `Long` index rejects there);
  - a `BlockValue` item must be a `ValDef`;
  - a register id must be 0 to 9;
  - from v3 a `MethodCall` needs arguments; below v3, one without comes back as a `PropertyCall`.

  Two quirks come back changed: the `TrueLeaf` / `FalseLeaf` opcodes `7f` / `80` parse and return as the Boolean
  constants `01 01` / `01 00`, a non-identity round-trip; and `SContext` method 11 parses and fails only when
  evaluated.

  Order matters too. A node is built as soon as its bytes are read, so its failure rejects even where a later read
  would have degraded the tree. A soft failure read first (a method not found, a type missing below v3, a type
  without methods) degrades the tree before a later construction failure;
- **nested failures and counts:**
  - a `ValidationException` inside a Box constant degrades the outer size-flagged tree, while a nested unsized tree's
    failure rejects;
  - a nested box's registers are read under the enclosing tree's version. In a v0 tree, a v3 box's SHeader register
    has no serializer and rejects. In a v3 tree, a v0 box's UnsignedBigInt register over 32 bytes fails its size
    bound and rejects;
  - SigmaAnd, Apply and constants counts above 100,000, and collection counts above `0xFFFF`, reject. Below that the
    parser reads on, and an input that ends first rejects even a sized tree;
  - a constants count that wraps negative means none, and `getUShort` truncates a count to 32 bits before its range
    check (both non-identity);
  - header bits 5 to 7 are kept;
  - only a relation reads `85` as a Boolean pair;
  - CTHRESHOLD needs `k <= n <= 255`, while CAND / COR data and SigmaAnd / SigmaOr nodes take 0, or more than 255,
    children;
- **`getUShort` outside trees:** a transaction's input, data-input and output counts, a proof's length, a box's index,
  and a collection's length or a BigInt's size in data all read 2^32 + k as k (non-identity). 2^32 + 2^16 rejects;
- **evaluated values:** an extension or register value may be any `EvaluatedValue`: a Tuple or collection node (items
  unchecked at parse), `GroupGenerator`, or the `7f` / `80` opcodes (written back as constants). `HEIGHT`, a
  placeholder or an operation rejects. The transaction tier pins what happens at evaluation;
- **reader scope:** in the `BlockTransactions` kind, each transaction parses on a fresh reader, so no leaked levels or
  `ValDef` types reach the next one. One transaction's outputs share theirs;
- **tree version:** a tree whose header version is above the activated script version rejects, even when
  size-flagged:
  - the check comes before its constants are read and after the size-bit rule;
  - it also applies when the tree is nested in a Box constant, a register or an extension. A nested header without
    the size bit degrades the outer tree instead;
  - the activated version is the context's: under (2, 2) a v3 tree rejects, and a block section of version 4 parses
    its transactions under (3, 3);
  - a block below version 4 parses them outside any version context, where a tree of any version parses.

Two more non-identity round-trips are pinned:

- repeated extension ids show the reference's collapse (the last value, at the first position);
- a size-flagged tree whose declared size is wrong is re-serialized with the real size. The reference ignores the
  declared size when the body parses.

## Transaction

About 3,460 entries in 79 files, each a signed transaction as bytes, with its input and data-input boxes, the nine
headers a script sees, the pre-header and the parameters → `valid`, and the cost of an accepted spend.

- **Captured** (`transaction/*/captured/`): real spends from testnet and mainnet. They include:
  - `bigint-downcast-2666`: a spend whose tree version only a transaction can carry, out of the eval tier's reach;
  - `order-ext-224312`: a non-ascending extension order the reference preserves;
  - multi-input spends;
  - a stratified mainnet sample of storage-rent spends (`mainnet-rent-s1` to `s8`, v5 and v6 eras);
  - `selfboxindex-342964` (`transaction/v4/`, mainnet block version 1): the one spend below the JIT activation whose
    verdict depends on `CONTEXT.selfBoxIndex` answering −1 there (the v4.x behaviour). Its input carries an empty
    proof and an output's R4 is −1. Its authored twin at block version 3 (`transaction/v5/authored/`) is invalid.
- **Authored**, by family:
  - **Storage rent** (`storage-rent-*`), over a synthetic context at the `StoragePeriod` height. Every arm of the
    reference's rent spend:
    - the gate;
    - the fallbacks to the script;
    - the final recreation verdict, including register equality as the reference compares it (stored nodes and
      retained script bytes, not values);
    - the 32-bit-wrapping storage fee;
    - the 50-unit `StorageContractCost`.
  - **Sized-tree spends** (`sized-tree-spend`, `sized-tree-output-bytes`, `conjecture-child-count-wrap`):
    - a box whose tree degraded under rule 1001 does not spend;
    - a leafless `CAND()` or `CTHRESHOLD(0, [])` spends with its secret-free 24-byte Fiat-Shamir proof, and does not
      spend without a proof;
    - `COR()` never spends: with any proof it throws on its empty last child, caught as false;
    - a 2-of-3 `CTHRESHOLD` over `ProveDlog` children pins the threshold crypto cost;
    - a threshold proof that ends before or inside its single coefficient still verifies;
    - a `CAND` of 40,000 children (carried by a context variable, since an ErgoTree caps at 4096 bytes) spends with
      the Fiat-Shamir challenge over its wrapped child count;
    - inside the creating transaction, an output that declares its tree size wrongly keeps its tree as received in
      `propositionBytes`, while its `bytes`, its id and the signing message are re-encoded.
  - **Deserialize substitution** (`deserialize-substitution-spend`): how a spend replaces `DeserializeRegister` and
    `DeserializeContext` nodes.
    - A `ClassCastException` while decoding or typing the script, or from a register that is not a `Coll[Byte]`, is
      swallowed: the node stays, and throws only if evaluated.
    - An absent register's default replaces the node untyped, and the node's ancestors are rebuilt through their
      constructors. So an `If`, `Negation`, `Plus` or `OptionGet` that reads the default's type rejects, even in a
      branch never evaluated.
    - A decode is charged twice its length once it completes.
  - **The group nodes' static type** (`group-node-static-type`): `Exponentiate` and `MultiplyGroup` are typed
    `GroupElement` whatever their operands, and the substitution checks each deserialized script's type against the
    declared one before the script runs.
  - **The `substConstants` lift** (`substconstants-lift`): `substConstants` turns each of its new values into a
    constant by the item's class and the tree version, before it reads the template.
    - A Header item has no lift below tree version 3.
    - A collection of pre-headers, `CONTEXT`, `Global` or functions lifts whole.
    - A String item has no lift at tree version 3.
  - **A specialised collection's `patch`** (`patch-array-class`): on the reference, a `Coll[Byte]`, `Coll[Boolean]` or
    `Coll[Int]` held over a primitive array casts the patch's array to its own before anything is stored. A patch
    held over another array throws even when empty.
  - **The collections a node holds generic** (`generic-producers`, `generic-producers-pairs`,
    `generic-producers-census`): the pre-header's `parentId` and `votes`, R1 to R3 read as registers, and collection
    expressions in a register or context variable are generic collections. On them, `patch`, `startsWith`, `endsWith`
    and `ByIndex` with a default of another class answer; on a constant, a header's field or a token id they throw.
    R2 is a generic collection of pairs. The rule holds for every box of a transaction.
  - **An AvlTree method given an item of another kind** (`avltree-item-bind`, `avltree-item-bind-review`): the parser
    does not check a method call's argument against its signature. The reference meets the item where it first uses
    it, so per method, the call throws, or answers where the item is never read.
  - **`AND` and `OR` read item by item** (`and-or-item-by-item` and its `-context` and `-producers` families,
    `segregated-tuple`):
    - each stops at the deciding item, and charges for the items it read (AND 5 per 32, OR 5 per 64);
    - a collection expression evaluates every item first;
    - a segregated tuple of three items never reaches the node.
  - **A collection holds at most 100,000 items** (`collection-length-limit`): the six producers that can return more
    than they were given, at the last accepted and the first refused count. `append` checks the sum before it copies;
    `flatMap` refuses part by part.
  - **A tree above the activated version** (`tree-version-above-activated`, `tree-version-above-activated-eval`):
    - a node reads a box outside any version context, so only its script spend fails;
    - a data input with such a tree is valid;
    - below block version 4 an output may carry a tree of any version;
    - a tree deserialized while a script runs is compared with the activated version, and a template header is not.
  - **The block-version edges** (`tree-version-block-version-edges`):
    - no floor at activated 2;
    - above the highest script version the reference supports (3), a script is accepted unverified, at the
      initial cost alone;
    - but a v4 header over a root that degrades when the box is read is not: the degraded tree carries version 0,
      and its spend throws.
  - **A tree no box can carry** (`unwritable-tree-spend`): a tree with a SigmaProp root and a constant of a 0- or
    1-item tuple type has no transaction id, so such a script reaches a node only through a context variable. There,
    `Global.serialize` of a one-item tuple works, and `_1` on it, `() == ()` and `(1) == (1)` throw.
  - **A tree that is not a script** (`non-sigmaprop-root-output`): a tree whose root is not a SigmaProp is kept as
    its bytes, so an output may carry one that holds a 0- or 1-item tuple type, and the transaction is valid.
  - **Which block version activates scripts** (`block-version-source`): the voted parameters' block version, not the
    header's. The header's version only feeds PoW, serialization and `CONTEXT.preHeader`.

## Block

15 entries in 12 files, in the digest-state shape: the parent state digest, up to ten headers, the parameters, and
the block with its ADProofs → `valid`, the computed post-state digest, and the cost. A block's transactions travel as
the section's bytes, because a JSON transaction re-writes its trees.

- **Captured:** four testnet blocks, ADProofs-verified at bless time: 2666 (cost 39,379), 111927, 184137, and the
  epoch-boundary block 2560.
- **Authored mutations**, each confirmed to reject for its intended reason:
  - `adproof-tamper`;
  - `params-shrink-maxBlockCost`;
  - `pow-solution-flip`;
  - `proofless`;
  - `stateroot-flip`;
  - `txs-reorder`;
  - `version-gate` (the reference checks the block version only at epoch boundaries).
- **`kept-tree-bytes-in-block`:** a box created and spent in the same block keeps the tree encoding it arrived in.
- **A captured seed at 28474 waits:** the Rust AVL+ prover emits a valid but non-canonical proof for its data-input
  lookups.

## Chain

73 entries in 16 files, value only (no cost dimension), by kind:

- **retargeting** (5): the difficulty arithmetic, both the classic and the EIP-37 arm, chosen by the entry's settings.
- **voting** (44): the v6 parameter-voting math, covering:
  - threshold edges and the voting-window clamp;
  - the soft-fork round lifecycle;
  - activation;
  - checkpoint flips;
  - tally order;
  - hostile tables, the reject arm (`expected.error: "errored"`, where the reference throws).
- **fork_vote_gate** (12): the v6 soft-fork activation guard, its window edges and preconditions.
- **header_votes** (12): the reference's `validateVotes` on a header's votes.

## AuthDS

47 entries, vendored from ergots' AVL prover corpus with every expectation re-derived through the reference
(`scrypto` 3.0.0). They grade the AVL+ batch **prover** (10 entries: canonical proof bytes) and **verifier**
(37 entries) directly, below the ErgoScript layer every other tier reaches them through. The verifier is graded at
three levels: whether the proof is accepted, the per-operation results, and the final digest.

The operations no consensus path can construct (`UnknownModification`, `UpdateLongBy`, `RemoveIfExists`: zero
references in sigma-state or ergo-core) were removed with their fixtures. The four adverse fixtures (a malformed,
mismatched, truncated or swapped-digest proof) and one padded with extra nodes stay.

## NiPoPoW

10 entries in 2 files, each over a chain of real headers from the reference node (32 and 64 headers):

- **interlinks:** the interlink vector reconstructed for every header;
- **prove:** a NiPoPoW proof for given `m` and `k`, at the tip or at a given header.

Contract: [`runner-contract-nipopow.md`](contract/runner-contract-nipopow.md).
