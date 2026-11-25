# SANTA Runner Contract (eval tier)

> **Status: frozen for the eval tier (`santa-eval/v1`, `santa-eval/v2`).** This is a
> committed, language-agnostic contract — independent Ergo implementations build their
> runners against *this document + the JSON Schemas in [`/schema`](../../schema/)*, not
> against any one implementation's source.
>
> Per-tier contracts (each a lean companion inheriting totality, never-panic, faithful
> outcomes, and this document's comparator topology): [wire](./runner-contract-wire.md) ·
> [transaction](./runner-contract-transaction.md) · [block](./runner-contract-block.md) ·
> [chain](./runner-contract-chain.md) · [authds](./runner-contract-authds.md) ·
> [nipopow](./runner-contract-nipopow.md).
>
> Companion machine-checkable schemas:
> [`schema/santa-eval.vector.schema.json`](../../schema/santa-eval.vector.schema.json),
> [`schema/santa-eval.actuals.schema.json`](../../schema/santa-eval.actuals.schema.json).

## 1. What a runner is

A **runner** is a conformer's consumer of the committed vectors. Abstractly it is a
**pure function from one vector file to one actuals file:**

```
run(vector) → actuals
```

- A **vector** is a committed JSON file under [`/vectors/eval`](../../vectors/eval/): an
  envelope (`schema`, `op`, `blessed_by`, `entries`, and — `santa-eval/v2` only — a
  `source` provenance string; the Phase-1 `santa-eval/v1` `decode-point` vector predates
  `source`) carrying a list of `entries`. Each entry is one (`tree_bytes_hex`, `version`,
  optional `input`) case with the **blessed** `expected` result the JVM reference produced.
- **Actuals** is the runner's output: a JSON object mapping each entry's `name` to the
  runner's *own* `{ value, cost, error }` for that entry, where `error` is the outcome tag
  (§3) — `null` on success, else `errored` / `not-implemented` / `panicked` (or, for a bounded file, `exceeded`).

Conformance is then a separate comparison step (§5): an entry is **nice** when the
runner's actual equals the vector's blessed `expected`, **a lump of coal** otherwise. The runner makes that comparison
itself, by the grading rules, and SANTA's `./conform` makes it again over the same actuals as a canary
([runner-integration §4](runner-integration.md#4-the-runner-grades-santa-re-grades)).

The JVM reference runner ("Rudolph") is *one* implementation of this function; it is not
a dependency for anyone else (§6).

## 2. Lifecycle

```
committed vector ──▶ run(vector) ──▶ actuals ──▶ compare(actuals, vector.expected) ──▶ verdict
   (blessed truth)    (the runner)   (per entry)    (per entry, structural §5)        nice / coal
```

A runner reads a vector, evaluates each entry under that entry's declared version, and
writes actuals. It never reads `expected` (§3). The comparison is a distinct step that
*may* be the JVM harness, the runner's own checker, or a CI script — all must agree
because "equal" is pinned (§5).

**Evaluation includes deserialize-substitution.** A tree may carry `DeserializeContext(id)`
nodes; the runner resolves them against the entry's context — the eager whole-tree pass,
matching the JVM's `Interpreter.applyDeserializeContext` — *before* reducing. The JVM (and a
conforming runner) leaves the node in place when `id` is absent or not a `Coll[Byte]`, so a
`DeserializeContext` on a never-taken branch is tolerated while one on the live path errors at
eval. A runner that skips this pass cannot grade the `DeserializeContext` vectors faithfully —
e.g. sigma-rust's `try_eval_with_deserialize` runs it, the bare `try_eval_out` does not.

**Deserialize-bearing segregated trees are blessed at PRODUCTION (substituted-constant)
cost.** The JVM's `fullReduction` evaluates ordinary trees lazily (ConstantPlaceholders
resolved against the constants array; CP visit = JitCost 1) but evaluates deserialize-bearing
trees from the constants-SUBSTITUTED proposition (Constant visit = JitCost 5) — sigma-state
`Interpreter.scala:218` short-circuits to the lazy path only when `!ergoTree.hasDeserialize`.
On-chain cost therefore includes the Const-vs-CP delta exactly for this tree class, and the
blessed `cost` mirrors it (the bless seam substitutes iff `isConstantSegregation &&
hasDeserialize`; everywhere else lazy == production, so the rest of the corpus is unaffected).
A runner whose eval harness charges the lazy form on such trees will show a genuine cost
divergence — that is consensus-relevant, not a harness artifact. Decided 2026-06-06
(probe: lazy 12 vs substituted 20 on the two DC dead-branch entries, Δ = 2 ex-placeholders × 4);
consistent with the transaction tier, which blesses this mechanism via `validateStateful`.

**The canonical eval context.** Vectors may read context surfaces (`CONTEXT.*`,
`CONTEXT.preHeader.*`, `SELF`), so the context a runner evaluates under is part of the
contract — every runner constructs the SAME one (it mirrors the blesser's
`EvalCore.dummyContext`). Pinned fields:

| surface | pinned value |
|---|---|
| `SELF` box | `value = 1000000`, `ergoTree` = the entry's own tree, `transactionId` = 32 zero bytes, `index = 0`, `creationHeight = 0`, no tokens; **R4–R9 ABSENT** — a read of one must return `None`, never an arbitrary value — except on `santa-eval/v4`, whose `selfRegisters` replaces them (see the SELF-registers note) |
| `INPUTS` / `boxesToSpend` | exactly `[SELF]` (so `selfBoxIndex = 0`) — and the SAME box VALUE as `SELF`, not merely one of equal shape (see the note) |
| `OUTPUTS` / spending tx | empty (no outputs, no other inputs) |
| `dataInputs` | empty |
| `headers` | empty |
| `preHeader.version` | **`activated + 1`** (block-version convention; see note) |
| `preHeader.parentId` | 32 zero bytes (chain-faithful width — `BlockId` is fixed-size in byte-oriented models) |
| `preHeader.timestamp` | `3` |
| `preHeader.nBits` | `0` |
| `preHeader.height` / `HEIGHT` | `0` |
| `preHeader.minerPk` / `minerPubKey` | the secp256k1 group generator (`0279be66…f81798`) |
| `preHeader.votes` | 3 zero bytes (chain-faithful width — `Votes` is fixed-size in byte-oriented models) |
| `LastBlockUtxoRootHash` | `AvlTreeData.dummy` (33-byte all-zero digest = 32-byte root hash + height byte; flags 0x07 = all operations allowed; keyLength 32; no value-length — serialized `00`×33 `07 20 00`) |
| extension | empty except the entry's input binding (var 1; `santa-eval/v3` per-input extensions; `santa-eval/v5` a full top-level self extension keyed 0..255 — a key ≥ 0x80 is a signed-negative `Byte` that crashes `toSigmaContext` before eval) |
| cost limit | the evaluator's default, `scriptCostLimitInEvaluator` = 1,000,000 block units = **10,000,000 JIT units**, from an initial cost of 0, on every schema arm (`santa-eval/v6-fullctx` too); an evaluation whose cost crosses it is `errored` (see the cost-limit note) |

*SELF-registers note (added 2026-11-07, after a runner broke it).* The table always meant R4–R9
to be absent — "registers beyond the mandatory ones only via `santa-eval/v4`" — but it did not say
so in those words, and it did not say what `INPUTS = [SELF]` costs. Both halves are now explicit,
because a runner satisfied neither and the corpus could not see it:

- **Absent, not arbitrary.** A runner that clones a *generated* context (proptest `Arbitrary`,
  a fixture, a captured box) inherits whatever registers it happens to carry. Nothing fails until
  a vector reads one — and then it grades against a random value. The first vectors to read SELF's
  registers on the `santa-eval/v2` arm (`eval/v6/authored/Value.checkType_lazy_default.json`,
  entries #3/#6/#7) found blitzen doing exactly that: two entries red and one falsely GREEN, all
  for the runner's reason. A falsely-green entry is the dangerous half — it reports agreement that
  was never tested. The two controls in that family (`#6` an honest default, `#7` the bare
  `SELF.R4[Int].isDefined`) exist to catch this, and are what localised it.
- **The same box value, not an equal-looking one.** A box id is a hash over the serialized box,
  registers included, so *changing* SELF's registers yields a different box. If a runner fixes
  SELF's registers but leaves `INPUTS[0]` as it was, `CONTEXT.selfBoxIndex` breaks: the JVM's
  `selfBoxIndex` is a full-value search of `INPUTS` for SELF, and so is sigma-rust's. Whatever a
  runner does to SELF it must do to `INPUTS[0]`. This is the half a `Context.properties` red caught
  during the fix, not reasoning.

*Cost-limit note (added 2026-11-16, after two runners lacked it).* The oracle has always evaluated
every entry under `DefaultEvalSettings.scriptCostLimitInEvaluator` (EvalCore), but the table did not
say so, and no vector came near the limit until `eval/v6/authored/Global.serialize_shared_nested.json`,
whose writes cross it at about 10,030,000. Two runners evaluated with NO limit and answered those
entries as values at 16 to 67 million: blitzen took its context from a generated template, and dasher
left ergots' `jitCostLimit` unset, which ergots reads as "unlimited". A limit is a context premise like
the rest of this table: a runner sets it, it does not inherit it.

`OUTPUTS` is empty here, so it has no registers to pin — but a future vector reading
`OUTPUTS(i).R4` would need the same treatment stated for outputs, and the premise that no vector
reads them is exactly the one that just failed for SELF.

*Version note:* the JVM carries `activatedScriptVersion` as its own context field and
treats `preHeader.version` as data, while sigma-rust DERIVES script activation from the
block version (`pre_header.version`). The pin `preHeader.version = activated + 1`
satisfies both: it is the on-chain convention (script v2 activates at block v3) and the
only choice that lets a block-version-deriving implementation evaluate at the declared
`activated`. Same principle for parentId/votes: zeroed at their chain wire widths
rather than empty, so fixed-size models can represent the pin exactly. `headers` stays
EMPTY — the JVM expresses that; a model that structurally cannot (e.g. sigma-rust's
`[Header; 10]`) will show a STRUCTURAL divergence on `CONTEXT.headers`, which is a real
conformance fact, not a harness artifact. Pinned 2026-06-07 when the first
context-surface vectors exposed that each adapter had improvised this context (the
divergences were harness artifacts, not library findings); blessed values for
`Context.*`/`preHeader.*` families assume this table. Drift gates: the pin is mirrored in EvalCore, each runner adapter, and the test anchors — a mirror that drifts goes red on the `Context.*`/`preHeader.*` families (EvalConformanceTest re-bless on the blesser side, `./conform` on the runner side).

*No tree above its activated version (2026-11-15).* Every eval entry carries `ergoTree ≤ activated`, and
`./validate` enforces it. The node refuses a tree above its activated version before any reduction
(`Interpreter.checkSoftForkCondition`), but implementations apply that refusal in their verifier, outside the
evaluator an eval runner calls (sigma-rust's `check_soft_fork_condition`, arkadianet's `reduce.rs`). So the
transaction tier grades it (`tree-version-block-version-edges`, `tree-version-above-activated`), the one
tier where it is the implementation's answer and not a runner's. Below activated 2 it matters most: there
sigma-state's `VersionContext` accepts the pair, so a bare evaluator would run a v1 tree at activated 0.

**`santa-eval/v6-fullctx` — real reconstructed context (not the pinned dummy).** Entries
in this schema carry a full `context` envelope; the JVM control reconstructs a REAL
`ErgoLikeContext` via `EvalCore.evalFullContext` rather than `EvalCore.dummyContext`. The
reconstruction rules, which every conforming implementation of this schema must mirror:

- **Boxes** (`inputs`, `data_inputs`, `outputs`) are parsed from their canonical
  `ErgoBox` bytes and **held as-is** (retained-bytes identity — box id =
  `Blake2b256(the exact input bytes)`, not a re-serialization). Non-canonical on-chain
  boxes (Option tags >0x01, identity GroupElements, etc.) have ids derived from their
  retained bytes; a round-trip canonicalization produces a divergent id.
- **`preHeader.version`** is read from `pre_header_hex` using the PreHeader sub-encoding
  (a pure unsigned-LEB128 codec, NOT the consensus header serializer — the PreHeader has
  no standalone `sigmaSerialize`). The `activated + 1` pin of the dummy context is
  **not** applied on this path; the real block version from the envelope is used directly.
  The sub-encoding's fields, in order (the LEB128 is plain unsigned, not sigma-state's
  ZigZag `putLong`):

  | field | encoding |
  |---|---|
  | `version` | 1 raw byte |
  | `parentId` | 32 raw bytes |
  | `timestamp` | unsigned LEB128, u64 |
  | `nBits` | unsigned LEB128, u32 |
  | `height` | unsigned LEB128, u32 |
  | `minerPk` | 33 raw bytes (SEC1) |
  | `votes` | 3 raw bytes |

  **The pre-header's `parentId` and `votes` are the collections a node builds** (2026-11-17), and their runtime
  class is visible to a script. ergo-core's `PreHeader.toSigma` writes them with `toColl`, which makes them GENERIC
  collections on the JVM. A specialised collection's `patch` casts the patch's array to its own, so
  `CONTEXT.preHeader.parentId.patch(0, <an empty Coll[Boolean]>, 0)` answers the parent id, and the same call on
  `CONTEXT.headers(0).parentId` throws (`Coll.patch_array_class_context`). The same holds for the dummy context's
  pre-header. A node also holds GENERIC: R1 read as a register (`ByteArrayConstant(propositionBytes)`), the bytes of
  R3 read as a register, and a `Coll[Byte]` or `Coll[Boolean]` collection EXPRESSION (`83…`, `85…`) in a register or a
  context variable, nested or not. It holds SPECIALISED a header's fields, a collection CONSTANT in a register or
  variable, `SELF.propositionBytes`, a token id and `LastBlockUtxoRootHash.digest`. On a generic collection
  `getOrElse` / `ByIndex` hand back a default of another class as it is, and `startsWith` / `endsWith` compare item
  by item; on a specialised one they throw (`Coll.generic_producers`). Registers and variables come from the bytes
  a node's readers take (box bytes, `Value` bindings), so an implementation's readers decide this; a hand-built
  pre-header does not. **R2 read as a register** is built as `Box.tokens` is (`Constant(additionalTokens.map(identity))`):
  a GENERIC collection of pairs (`CollOverArray` of `Tuple2`), where the same data as a constant, or a `zip`, is a pair
  of arrays (`PairOfCols`). `++` with pairs of another class, `updated`, `getOrElse` / `ByIndex` with a default of
  another class and `patch` / `startsWith` / `endsWith` with an `indices` argument show the difference
  (`Coll.generic_producers_pairs`). The rule holds for every box of the transaction, not only SELF: a data input's, an
  output's and a second input's R1, R3 bytes and expression registers, `getVarFromInput`, `creationInfo`'s bytes, and
  expressions two deep, empty, or beside a constant are GENERIC; their constants, token ids, `propositionBytes` and an
  output's `id` are SPECIALISED. A Tuple EXPRESSION in a register or variable is a collection of `Any` at every arity,
  a pair included (see the Tuple note in §4). The read itself never refuses it, but `._1` / `._2` throw on it,
  so every call behind them throws (`Coll.generic_producers_census`).
- **`lastBlockUtxoRoot`** is derived from `headers[0].stateRoot` as
  `AvlTreeData{ digest = headers[0].stateRoot (33 bytes), flags = 0x07 (all ops),
  keyLength = 32, valueLengthOpt = None }` — serialized `<stateRoot 33B> 07 20 00`.
  If the entry carries `context.last_block_utxo_root_hex`, that field overrides the
  derived value. (The derivation is deterministic and matches ergots'
  `avlTreeFromDigest(headers[0].stateRoot)` — no drift by construction.)
- **Per-input ContextExtensions** come from `context.input_extensions[i]` and are
  accessed via `getVarFromInput(i, key)`. The top-level `context.extension` is the
  SELF-input's extension and serves as a legacy single-input fallback; runners
  implementing the full schema use `input_extensions` for all inputs.
- **`headers`** are `CONTEXT.headers` as given: at most **nine**, newest-first, the headers a script in a
  block sees (the node's `lastHeaders.drop(1)`, `ErgoStateContext.sigmaLastHeaders`). A captured entry carries
  the nine below its spending block; an implementation presents them as they are, neither cutting nor padding.
- **Consistency constraint:** `preHeader.parentId == headers[0].id`. Real harvested
  envelopes satisfy this inherently (the spending block's preHeader references its
  parent, which is `headers[0]`); the control surface-verifies it.
- **Authored entries** (2026-11-16: the `SubstConstants.lift_by_class` and `template_tree_version` families) carry
  a synthetic but internally consistent envelope: ONE header (the newest of the transaction tier's storage-rent
  window at block version `activated + 1`), a pre-header whose parent is that header and whose version is
  `activated + 1`, a P2PK `SELF` without registers, no context variable and no outputs (the `source|…` rows, from
  2026-11-17: the same `SELF` with R4 = `Coll[String]("D")`, and context variable 1 = `Coll[String]("C")` as a
  `Value` binding, `0c66010143`). The `Coll.patch_array_class_context` families (2026-11-17) carry the same header
  and pre-header, and sigma-rust's own `SELF` (1,000,000 nanoErg, the generator's P2PK, created at 100, one token
  `22`×32, R4 = `Coll[Byte](1, 2)`, R5 = `Coll(true, false)`, R6 = `Coll(Coll[Byte](1), Coll[Byte](2))`) and context
  variables 1 = `Coll[Byte](1, 2)` and 2 = `Coll(true, false)` as `Value` bindings (`0e020102`, `0d0201`), the
  bytes a node's reader takes, on the rows that read them (`getVar`): the reader decides the collection's class.
  The other rows' context carries no extension. The `Coll.generic_producers` families (2026-11-17) carry the same
  header and pre-header with either that `SELF` or sigma-rust's rv-rc `SELF` (R4..R9 holding collection expressions,
  a nested one, a `Coll[Int]` expression and a `Coll[Byte]` constant, one token) and its five context variables as
  `Value` bindings in their RAW bytes, on the rows that read a variable (re-writing would turn an `83…` of booleans
  into `85…`). The `Coll.generic_producers_pairs` and `_census` families carry the same header and pre-header and
  sigma-rust's whole transaction: `SELF` and a second input (`inputs`), one data input, and two `outputs` whose boxes
  carry the transaction's id. On the rows that read a variable, both inputs' extensions travel as RAW `Value` bindings
  (`input_extensions`, `extension` = SELF's); the other rows' transaction has no extension, and so another id, which
  their outputs carry. The `AvlTree.item_bind_context` families (2026-11-18) carry the same header and pre-header,
  sigma-rust's review `SELF` (1,000,000 nanoErg, the generator's P2PK, created at 100, R4 = `(Coll[Byte](1), 5, 0)`,
  R5 = `Coll((Coll[Byte](3), 5))`, R6 = `Coll[Int](10)`), ONE output (1,000,000 nanoErg to the same P2PK, created at
  100), and its context variables 1 to 7 as RAW `Value` bindings on the rows that read a variable only. The
  `{AND,OR,XorOf}.item_by_item_context` families (2026-11-18) carry the same header, pre-header and one output, and
  sigma-rust's round `SELF` (R4..R9: `(true, 5, true)`, `(false, 5, true)`, `Coll(true, false, true)`, `Coll[Int](5)`,
  `(5, true, false)`, an empty `Coll[Int]`), its context variables 1 to 8 as RAW `Value` bindings on the rows that read a
  variable only. The `AND.item_by_item_context` and `OR.item_by_item_context` entries appended later the same day (the
  round's addendum) carry that context, or the addendum's: its `SELF` (one token, `22`×32 × 1,000; R4 / R5 =
  `(true, 5, true)` / `(false, 5, true)`, R6 / R7 their bytes as a `Coll[Byte]`, R8 a `Coll` of both) and its context
  variables 1, 2 and 10 to 13 (the two tuples, their bytes, a `Coll[Boolean]`'s bytes, a `Coll` of both), again on the
  rows whose tree reads a variable only. Each entry states its own context. They sit at activated 2 (the `v5` slot) as well as 3: the schema's `v6` names its revision, not the protocol version. A runner takes the
  activated version from the entry, or from the pre-header's version less one; the two agree on every entry.

## 3. Preconditions, postconditions, invariants

### Preconditions (a runner may assume)
- The vector validates against `santa-eval.vector.schema.json`.
- Every entry has `tree_bytes_hex` (hex of a serialized ErgoTree) and
  `version { activated, ergoTree }`. A `santa-eval/v2` entry also has an `input` SValue
  (§4); a `santa-eval/v1` entry has none (closed tree). A `santa-eval/v4` entry is v2's
  single-input form plus a per-entry `selfRegisters` object (R4–R9 SValue map, applied to
  the SELF box's additional registers); minted for dynamic-register-read vectors
  (Box.getReg MethodCall). A `santa-eval/v5` entry carries an `extension` object — the SELF
  box's TOP-LEVEL ContextExtension keyed by the unsigned wire byte 0..255 (no `input`) —
  minted for the ContextExtension key-domain seam: a key ≥ 0x80 decodes to a signed-negative
  `Byte` and crashes `toSigmaContext` (`NegativeArraySizeException`) before any bytecode, so the
  spend FAILS (`errored`); implementations that treat keys as unsigned 0..255 ACCEPT — a
  consensus-fork divergence that the extension being attacker-supplied makes mainnet-reachable.
  A `santa-eval/v6-fullctx` entry carries a `context` object —
  `{ self_index, inputs[], data_inputs[], outputs[], headers[], pre_header_hex, height,
  extension, input_extensions[] }` — where boxes and headers are lower-case hex of their
  canonical serializations, and NONE of the top-level `input` / `inputs` / `selfRegisters`
  / `extension` fields used by v1–v5 appear at the entry level (SELF is identified by
  `self_index`; per-input extensions live in `context.input_extensions`). Unlike v1–v5,
  which evaluate under the pinned dummy context, the control reconstructs a REAL
  `ErgoLikeContext` from this envelope via `EvalCore.evalFullContext` (see §2).
- **Version is an input.** The runner evaluates each entry under the entry's declared
  `(activated, ergoTree)` versions — it does not choose or assume a version. The corpus
  directory names the slot: `v4` holds entries at `activated` 0 or 1 (below the JIT activation,
  where sigma-state's only gate, `activated >= 2`, is closed), `v5` at 2, `v6` at 3. No entry
  carries a tree above its activated version (§2).

### Postconditions (a runner must guarantee)
- The actuals file validates against `santa-eval.actuals.schema.json`.
- **Exactly one** result object per input entry, keyed by that entry's `name`. (Names are
  unique within a vector — the blesser appends `#<index>`.)
- **Success:** `{ "value": <SValue §4>, "cost": <number>, "error": null }`.
- **Failure:** `{ "value": null, "cost": null, "error": "errored" }`.
- **Not-implemented:** `{ "value": null, "cost": null, "error": "not-implemented" }` — the runner has no implementation for this op/method/type.
- **Panicked:** `{ "value": null, "cost": null, "error": "panicked", "note": "<message>" }` — an
  otherwise-uncaught internal error on this entry, caught so the run continues. This is also the
  landing for the implementation's **own** failure to represent or hold a value (e.g. its codec
  rejecting an out-of-range field): the runner records that real failure rather than pre-classifying
  it into a softer outcome on the implementation's behalf. Graded coal **unconditionally** (§5/§6),
  even against a reject-expected vector: a crash is not a clean rejection (hence a distinct tag, not a
  reuse of `errored`). `note` (present iff `error == "panicked"`) carries the message for diagnosis.
- **Exceeded:** `{ "error": "exceeded", "limit": "memory"|"time" }`, the tier-neutral form every tier's actuals
  schema admits (closed: nothing else in the object) — a bounded file (one entry with a `limits` budget) whose
  process went over its memory or time budget. The runner bounds the file in a child process and writes it; a
  bounded file whose process crashed for any other reason is `panicked`, `note` its exit status. See
  [runner-integration.md §3a](runner-integration.md#3a-bounded-files).
- **Value-success with cost not claimed:** `{ "value": <SValue>, "cost": null, "error": null }` — the
  runner evaluated the value but does not claim the **cost dimension** (e.g. an eval-only library).
  This is *scope*, declared in the manifest (`cost: false`), not abstention: the value is still graded.
- The runner **MUST NOT read the vector's `expected`** when producing actuals. Actuals are
  produced blind; otherwise the comparison is meaningless. It reads `expected` only afterwards, to grade the actuals
  it already has.

### Invariants (hold for any runner, any vector)
- **Determinism.** Same vector → byte-stable actuals on every run. No clocks, no RNG, no
  ambient state.
- **Totality & never-panic.** Every input entry yields exactly one outcome — success,
  `errored`, `not-implemented`, `panicked`, or (a bounded file) `exceeded`. A runner never silently
  drops or omits an entry, and **no single entry may abort the file.** An entry that fails in
  a *recognized* way (`errored`) is a normal outcome — and that includes the implementation
  **refusing input material the oracle blessed**: a clean parse/`try_from` rejection of an
  input constant (e.g. a box value outside the impl's type bounds) is the implementation's
  own verdict, surfaced as `errored` and graded as the divergence it is, never softened into
  a runner-failure class. Any other failure the runner does **not** classify — a malformed
  vector, an internal error, an uncaught throw — is **caught and surfaced as `panicked`**
  (coal, message in `note`), never propagated as a process-aborting error. `panicked` is
  therefore not presumed rare: it is the faithful landing for any such throw. Surfacing it as
  a visible divergence beats both silently absorbing it and aborting the whole run.
- **Faithful outcomes — the runner never excuses the implementation.** A runner reports the
  implementation's *actual* behavior; it never authors a classification that softens a divergence on
  the implementation's behalf. An implementation's own failure — including failing to represent or
  hold a value its types nominally cover — is `errored` (a recognized eval failure) or `panicked`
  (any other throw), recorded as it happened. A gap in the **SANTA harness itself** (e.g. a bridge
  that cannot yet encode a value the implementation correctly produced) is `panicked` with a `note`
  naming the TODO — a defect to fix by making the harness total, never a standing "we don't test
  this" outcome. (This is why there is no `unrepresentable` tag: it had drifted into exactly such an
  excuse — the same anti-pattern as the removed `abstain`.)
- **No abstention; scope is an input-side selection.** A runner emits a faithful outcome
  for **every entry of every vector it is given** — including `not-implemented` (an
  op/method/type it doesn't implement) and `panicked` (an uncaught internal error, always coal).
  It never omits an entry or suppresses a would-diverge result to look
  conformant. A conformer expresses its scope by **which vectors it is run against**: the
  corpus is version-split (`vectors/eval/{v5,v6}/`), so a v5-only conformer runs the v5
  subset and never sees a v6 vector. Which entries "count" — and how they slice into
  v5/v6 or pass/gap tables — is a downstream **consumer's** judgment over the raw actuals
  plus each entry's `version`/`op`; it is never a runner behavior. `errored` still means
  *"I implement this op and the evaluation failed"* — never "unsupported"; that distinction
  is carried by `not-implemented`.
  Scope spans three axes: **version** (cumulative — a runner declares one and implies all lower),
  **tiers** (eval/wire/block — the result-shape it implements), and **dimensions** (value always;
  cost only if `cost: true`). A dimension the runner does not claim is not graded — distinct from
  abstaining on a vector it *does* run.
- **No oracle dependency.** Producing actuals requires only the vector plus the runner's
  own implementation — no JVM, no network, no access to the blesser.
- **Build identity — declared dependency overrides.** A conformer-under-test is its
  implementation *plus its resolved dependency graph*, not the library in the abstract. Where a
  runner's build redirects a behavior-bearing dependency away from its published/upstream default
  — a fork, a `git`/`path` dep, a `[patch.crates-io]` redirect — that override is part of the
  runner's **declared identity** and MUST be visible, so a nice/coal cell names *what was actually
  built* and stays reproducible. No new mechanism is needed: each runner dir is a self-contained
  build and its manifest (`Cargo.toml`, `package.json`, …) already **is** the declaration — the
  override surface is the manifest itself. Two runners over the same library but different override
  sets are **distinct conformance subjects** and may legitimately grade differently on the same
  vectors; the board surfaces each runner's active overrides (dependency · source · rev) so the
  distinction is on the record. Absent any override, a runner builds against **published/upstream
  defaults** — the bare-library subject, which surfaces the most divergence and is the sensible
  default. An override is pinned and declared by *rev*, because a fork bundles whatever that rev
  carries: *operational* changes (a fork needed to build or run at all, often consensus-inert at a
  given tier — e.g. ergo-node-rust pins `ergo_avltree_rust` to a `git` fork rev for a DB-backed
  AVL store via `VersionedAVLStorage::flush` + a persistence-capable Resolver, leaving in-memory
  proof-verification semantics — and so eval grades — identical to upstream) **and** any
  behavior-bearing changes riding the same rev (that same fork also carries a `contains`-resolution
  fix). A behavior-bearing override is a real conformance fact **about that build**, never a way to
  turn coal green: you declare the patch and let the cell reflect the patched subject — the same
  ethos by which divergences are surfaced, not pinned. The first live runner instance:
  **blitzen-eni** declares `[patch.crates-io] ergo_avltree_rust → the mwaddip fork` at its build
  root (sigma-rust eni's own manifests stay on the crates.io verifier; `Cargo.lock` pins the rev),
  and the override is behavior-bearing at eval — the fork returns `Err` on malformed proofs /
  out-of-range params where crates.io `.unwrap()`-panics — so the cell reflects the patched
  subject: the f4 panic classes graded green, the residual cost divergences stayed coal
  (2026-06-08). This invariant is **cross-tier** (it governs
  every tier's runner); it bites hardest at the **block tier**, where the node conformer *is*
  "sigma-rust + its persistence-fork dependency stack" and the cell must say so. Added 2026-06-07.

## 4. Canonical value encoding (`SValue`)

This is the load-bearing part: because every implementation compares against the *same*
committed `expected`, the encoding must be pinned so two implementations cannot diverge on
representation alone. The encodings below are **normative**; a runner MUST emit exactly
these shapes. The asymmetries are deliberate — mirror them exactly.

| kind | JSON shape | notes |
|---|---|---|
| Boolean | `{"kind":"Boolean","value":<bool>}` | JSON boolean |
| Byte | `{"kind":"Byte","value":<number>}` | **bare JSON number**, −128..127 |
| Short | `{"kind":"Short","value":<number>}` | bare number, −32768..32767 |
| Int | `{"kind":"Int","value":<number>}` | bare number, 32-bit |
| **Long** | `{"kind":"Long","value":"<decimal>"}` | **decimal STRING** — exceeds JS safe-int range |
| BigInt | `{"kind":"BigInt","value":"<decimal>"}` | decimal string (signed, 256-bit) |
| UnsignedBigInt | `{"kind":"UnsignedBigInt","value":"<decimal>"}` | decimal string (unsigned, 256-bit) |
| GroupElement | `{"kind":"GroupElement","bytes_hex":"<hex>"}` | 33-byte SEC1, **lower-case** hex |
| SigmaProp | `{"kind":"SigmaProp","raw_hex":"<hex>"}` | serialized SigmaBoolean, lower-case hex |
| Box | `{"kind":"Box","bytes_hex":"<hex>"}` | `ErgoBox.sigmaSerializer` bytes, lower-case hex |
| Header | `{"kind":"Header","bytes_hex":"<hex>"}` | `ErgoHeader.sigmaSerializer` bytes (incl. PoW), lower-case hex |
| AvlTree | `{"kind":"AvlTree","bytes_hex":"<hex>"}` | `AvlTreeData.serializer` bytes (digest+flags+keyLength+optValueLen), lower-case hex |
| Coll | `{"kind":"Coll","elem":<SType>,"items":[<SValue>,…]}` | `elem` = element SType tag; `items` positional |
| Coll[Byte] | `{"kind":"Coll[Byte]","value_hex":"<hex>"}` | the COMPACT byte-collection form, semantically identical to `Coll`/`SByte` per-item — added for large payloads (per-item JSON is ~35× the bytes; the SBox token-window family carries >4KB box bytes as context input). INPUT-side only: results still encode as `Coll` (the JVM's `valueToJson` is unchanged), and a vector MAY keep using per-item `Coll` for byte collections — both decode identically. |
| Tuple | `{"kind":"Tuple","items":[<SValue>,…]}` | ≥2 items, positional. **The runtime representation is decided by the ARITY, and it decides verdicts** — see the note below. |
| Option | `{"kind":"Option","value":<SValue> \| null}` | `null` = `None`; otherwise the inner SValue |
| **Value** | `{"kind":"Value","bytes_hex":"<hex>"}` | **a binding only**: the bytes a node itself reads for a register or a context-extension value, bound as YOUR implementation's reader builds them — see the note below. Never nested inside another SValue, never an expected value. |

**A tuple's runtime representation, by arity (read this before implementing the Tuple kind).**
A **pair** is held as a language-level PAIR (a Scala `Tuple2` on the JVM); a tuple of **any other
arity** is held as a **generic collection of `Any`** — `CoreDataSerializer` reads a tuple's items
into `Colls.fromArray(arr)(sigma.AnyType)` and hands them to `Evaluation.toDslTuple`, which pairs
up only at arity 2, and `Platform.isCorrectType` enforces it from the other side (a collection
under a tuple type must have item type `Any` and exactly the declared length). This is not an
implementation detail: `STuple extends SCollection[SAny]`, so a non-pair tuple VALUE is accepted
wherever a collection is wanted — `SizeOf`, `ByIndex`, `Exists`, `Fold`, `AND`/`OR`, `Xor` and the
`Coll` methods all answer on one — while an operation that needs the backing ARRAY of a class
(`Append` against a primitive-backed operand, `AtLeast`, the hashes, `ByteArrayToBigInt`, the
`AvlTree` key/proof arguments) throws. A runner that holds every tuple in one dedicated node
cannot reproduce both halves. The value also throws (`Unsupported tuple type`) wherever it meets a
`Value.checkType` at its own tuple type: a `Fold` accumulator, a `ConcreteCollection` item, and a lambda's
argument. So no lambda over a data collection of non-pair tuples can be applied (`ForAll`, `Exists`, `Map`,
`Filter`), while over an empty one they answer. Arithmetic picks its implementation by the LEFT operand's
type, so a tuple item (typed `Any`) on the left throws whatever it holds, and on the right it answers.
Vectors: `eval/v6/authored/Tuple.non_pair_value_{ops,gates}.json`,
`wire/v6/authored/*.non_pair_tuple_operand.json`,
`transaction/v6/authored/non-pair-tuple-spend.json`.

**A pair can be a collection too: a `Tuple` EXPRESSION.** A `Tuple` node's value is a collection of `Any` at
**every** arity (`Tuple.value`, `values.scala:818-822`). So a two-item `Tuple` expression bound in a register or a
variable (the `Value` binding form below) is a `Coll[Any]` under a pair type, while the pair CONSTANT is a pair.

Reading it at its pair type does not refuse it. `CBox.getReg` and `CContext.getVar` check only the type the value
was stored with, and `OptionGet` checks nothing. What happens next depends on the consumer:
- **Answer on the collection:** `isDefined`, the bare `get` (it encodes as `{"kind":"Coll","elem":{"tag":"SAny"},…}`),
  `SizeOf` and `ByIndex`. On the pair constant, `SizeOf` and `ByIndex` throw instead. `Global.serialize`
  writes both forms the same (`0204` for `(1, 2)`), by the static pair type.
- **Refuse it:** `EQ` / `NEQ`, which type-check each operand first, and `SelectField` (`._1` / `._2`), which matches
  a pair only.

Vectors: the P rows of `eval/v6/authored/Tuple.binding_provenance_{register,var}.json`.

**And the decode/encode asymmetry that follows from it.** `{"kind":"Tuple",…}` is an INPUT form at
every arity, but decode-then-encode is the identity only for a **pair**. A non-pair tuple value, if
it is ever an OUTPUT, encodes as `{"kind":"Coll","elem":{"tag":"SAny"},"items":[…]}` with each item
by its own runtime kind — because the encoder dispatches on the runtime representation, and that
representation IS a collection. A runner must not assume the two directions mirror each other.
(A 0- or 1-item tuple TYPE parses but can never be written back — `TypeSerializer` refuses fewer
than 2 items — so it has no input form at all, and no box can carry a script containing one.)

**A binding as the bytes a node reads (`Value`).** Every binding position — `input`,
`selfRegisters`, `inputs[].extension`, `extension`, and a full context's `extension` /
`input_extensions` — takes any SValue above, or `{"kind":"Value","bytes_hex":…}`: the bytes a node
itself reads for that position. Bind what your implementation's **own reader for that position**
builds from them — the whole reader, its validation rules included — under the entry's activated
version, consuming **exactly one value with no bytes left over**. On the JVM both readers (a box
register, `ErgoBoxCandidate.scala:231-232`; an extension value, `ContextExtension.scala:61-62`) are
`getValue`, cast to `EvaluatedValue` — so the bytes may hold a constant or an evaluated expression
such as a `Tuple` of constants — then **rule 1019 `CheckV6Type`**, which refuses a type holding an
Option, a Header or an UnsignedBigInt anywhere, at every version. If your reader refuses them, the
entry is `errored`: a node would reject the transaction. **Never decode them into an SValue and
rebuild the value from that** — the point of the form is that the reader decides the value's runtime
shape. A vector uses it when its verdict depends on how the bound value was built or encoded, or for
content no SValue can say (a register holding a `Tuple` expression; an encoding a reader
normalises). The JSON forms remain for everything else, and they are **not** subject to rule 1019:
they are the harness's way of handing a function its input, and the corpus binds exactly those types
into var 1, where no real register or extension could hold them. SANTA's oracle binds the JSON forms
through its reader's `getValue` as well (`EvalCore.offTheWire`), because its own JSON-built values
were measured to differ from it — a `Coll[Short|Int|Long]`'s class, a collection of non-pair tuples'
item type — and a script can see the difference; a runner whose JSON-built value can differ from its
reader's should do the same.

**The Long-vs-cost asymmetry (read this twice).** An SValue **`Long` value** is a decimal
**string**, but the entry's **`cost`** (§5) — also a 64-bit quantity — is a bare JSON
**number**. This is intentional: cost is bounded by the block cost limit (~10⁶), safely
inside IEEE-754 integer range; SValue `Long`/`BigInt` are not. A runner emits **cost as a
number** and **Long values as strings**.

**Hex is lower-case** everywhere (`bytes_hex`, `raw_hex`). **No `Opaque`/`SUnknown` may
appear in a committed vector** — the blesser refuses to bake an un-modeled kind (it
skips-and-reports instead), so a runner never needs to encode one.

**Canonical vs faithful range.** The numeric bounds above (Byte −128..127, Int 32-bit, …)
describe the **canonical** encoding — what a *correct* value looks like, and what a committed
`expected` always is (blesser-guaranteed). An **actual** may legitimately fall outside the
canonical range when that *is* the divergence: e.g. a runner whose `>>> 0` yields a u32
`2147483649` where the JVM wraps to a negative int32. Per §3 (faithful outcomes), the harness
must be able to **represent** that, so the actuals schema does not bound `Int` to int32 — the
divergence records and grades as a value-coal rather than failing schema validation. Expected
stays strictly canonical.

### SType tags (the type side, used in `elem`)
`{"tag":"SBoolean"}`, `SByte`, `SShort`, `SInt`, `SLong`, `SBigInt`, `SUnsignedBigInt`,
`SGroupElement`, `SSigmaProp`, `SBox`, `SHeader`, `SPreHeader`, `SAvlTree`, `SUnit`, `SAny`; and the
recursive forms `{"tag":"SColl","elem":<SType>}`, `{"tag":"SOption","elem":<SType>}`,
`{"tag":"STuple","items":[<SType>,…]}`. These appear inside `Coll.elem` and nested type
positions; pinned in the schema.

## 5. The match (conformance comparison)

**The match is specified as data:** [`grading.md`](grading.md) defines the grading language, and the eval tier's
rules are the type `eval` in [`vectors/grading.json`](../../vectors/grading.json). In short, for the eval tier:

- **Equality is structural**, never raw-string or byte: objects regardless of key order, arrays in order, numbers
  numerically, strings exactly (all contract hex is lower-case), `null` only `null`.
- **`value` is always graded; `cost` only after a nice value, and only for a runner that claims the cost dimension**
  (`cost: true`). No tolerance on either. A claiming runner's `cost: null` on a value-success is red on cost.
- **An `expected` of `errored` is the reject arm:** nice when the actual is `errored`, red otherwise. A committed
  `expected` is always a success or `errored`; there is no error taxonomy at the eval tier (§7).
- **`not-implemented`, `panicked` and `exceeded` are red whatever was expected** (a crash or a blown budget is not a
  clean rejection); `not-implemented` is counted on its own, as the readiness map.

A vector is **nice** for a runner iff every entry is nice; one lump of coal makes the runner **naughty** on that
vector.

## 6. Comparator topology (no oracle dependency)

The JVM blesses (produces `expected`); those values are committed in the vectors as the **source of truth**.
**Conformance-time comparison requires no JVM.** The comparison rules are data ([`grading.md`](grading.md)): a
comparator interprets that file and proves itself against the verdict oracle
([`oracle/verdicts-grading.json`](../../oracle/verdicts-grading.json)); `santa-check` is SANTA's. Two interpreters that
reproduce the oracle return the same verdict on the same bytes.

## 7. Future arms (generalization — NOT specified here)

The `run(vector) → actuals → compare-to-blessed-expected` shape is deliberately
tier-agnostic, but this document specifies **only the eval tier**. The following are named
non-goals, to be specified when each arm is actually built (do not implement against them):

- **Error taxonomy (reject arm, Phase 5).** The eval-tier `error` is intentionally coarse
  (`errored`/null). A field exercise across the JVM, sigma-rust, and ergots found that a
  *finer* taxonomy is not cleanly cross-referenceable without fragile error-message
  matching, so classification is deferred to the reject arm — where rejection *reason* is
  the actual subject. The `error` field is the slot that refinement will fill; adding
  classified reasons there is an additive change, not a breaking one.
  This is distinct from the `not-implemented` outcome tag (§3): that is
  a *coverage* axis — "the runner has no implementation for this op/method/type" — detected by
  each runner's own typed conditions, not by fragile cross-impl error-message matching. The
  deferred taxonomy concerns *why an attempted evaluation failed* (the `errored` case),
  which stays coarse at the eval tier.
- **Wire tier.** Result shape is a serialization round-trip outcome ("parsed structure" /
  "round-trip-ok"), not value+cost. A new `schema` discriminator + a new result-shape
  section; the eval contract is untouched.
- **Block tier.** Result shape is chain-validity of a captured block. Same: new schema, new
  section.

The `schema` field on every vector is the tier discriminator (`santa-eval/v2` today), so a
new arm slots in beside this one without changing it.

## 8. Worked example

A `santa-eval/v2` vector entry and a conforming runner's actual:

```jsonc
// vector entry (in vectors/eval/<op>.json → entries[…])
{
  "name": "5#0",
  "tree_bytes_hex": "…",
  "input":   { "kind": "Int", "value": 5 },
  "version": { "activated": 3, "ergoTree": 3 },
  "expected": { "value": { "kind": "Int", "value": 6 }, "cost": 36, "error": null }
}

// the runner's actuals file: { "<name>": { value, cost, error }, … }
{
  "5#0": { "value": { "kind": "Int", "value": 6 }, "cost": 36, "error": null }
}
```

Here `expected` and the actual for `"5#0"` are structurally equal → **nice**. Had the
runner produced `cost: 37`, or `value.value: 7`, that entry would be a **lump of coal**.
Had the runner produced `{ "value": null, "cost": null, "error": "errored" }` while the
vector expected a value, also a lump of coal (one side succeeded, the other failed).
