# SANTA Runner Contract — AuthDS tier (`santa-authds/v1`)

> **Status: the committed result-shape contract for the authds tier (`santa-authds/v1`).**
> A lean companion to the eval, wire, transaction, block, and chain contracts — it
> specifies only what is authds-specific and inherits totality, never-panic, faithful
> outcomes, and the comparator topology from [`runner-contract.md`](./runner-contract.md).
>
> Machine-checkable schemas:
> [`schema/santa-authds.vector.schema.json`](../../schema/santa-authds.vector.schema.json),
> [`schema/santa-authds.actuals.schema.json`](../../schema/santa-authds.actuals.schema.json).
>
> Oracle: scrypto 3.0.0 (`blessed_by: "jvm:scrypto-3.0.0"`), via `jvm-blesser`'s
> `AvlProofGenerator` / `AvlVerifierBlesser` — the authority for this tier is scrypto, not
> sigma-state (contrast the eval tier, whose oracle is `sigma-state`). Design record:
> [`docs/specs/authds-tier.md`](../specs/authds-tier.md) — read for rationale; this
> document is the durable, current source on any divergence between the two.

## 1. Tier boundary

An **authds runner** decides the **authenticated-data-structure surface directly** — the
AVL+ batch **prover** (produce canonical proof bytes) and the batch **verifier** (consume
them) — below the ErgoScript layer every other tier reaches it through. Two kinds in v1:

- **`avl_prove`** — given a tree config and an operation sequence, produce the canonical
  proof bytes and resulting digest at one or more cycle boundaries.
- **`avl_verify`** — given a starting digest, a proof, and an operation sequence, decide
  whether the proof is accepted, what each operation returns, and the resulting digest.

Proof bytes are consensus data: `blake2b256(proofBytes) == header.adProofsRoot` is a
validity rule, and contract-supplied proofs are verified on-chain — a proof the JVM
verifier will not take is stuck funds. `avl_prove` is this tier's first **generative**
kind requiring an independent reimplementation to be interesting (the chain tier already
grades produced values — `retargeting`'s `nbits`, `voting`'s parameter table — so this is
not SANTA's first generative dimension, but it is the first place a *second prover* exists
to disagree with the oracle).

What authds is **not**: `merkle_*` kinds (`transactionsRoot`, `BatchMerkleProof`) are the
same family, same tier, deferred — the `kind` discriminator exists so they land without
schema churn. NiPoPoW interlinks need chain context deeper than this format carries. Sigma
prover (spending proofs — the thing SANTA is named for) does not fit: Fiat-Shamir is
non-deterministic and the runner contract forbids an oracle dependency at conformance time
(§4 below), so there is no committable `expected` without an injected-randomness seam in
every implementation; its deterministic half (reduction to a sigma tree) is already graded
in eval (`{"kind": "SigmaProp", "raw_hex": ...}`). `PersistentBatchAVLProver` /
storage-backed rollback (`restore_root`, `flush`, resolver surface) is an enr/arkadianet
concern, not consensus-visible.

## 2. Vector format (`santa-authds/v1`)

An authds vector file is a committed JSON under `vectors/authds/<version>/<provenance>/`
whose envelope mirrors the other tiers — `{schema: "santa-authds/v1", op, blessed_by,
source?, entries}` — with **kind dispatch per entry** (the chain tier's pattern). Per
entry: `name · source · kind · settings · payload · expected`.

**Key casing rule:** envelope, settings, payload, and expected keys are all **snake_case**
— authds carries no node-API camelCase embeds (unlike chain/block, which embed header
objects verbatim). All hex strings (`*_hex`, `key_hex`, `value_hex`, `proof_hex`,
`starting_digest_hex`, `new_digest_hex`, entries of `proofs`/`digests`) are lower-case
Base16; grading is string-exact, so case is consensus — proof bytes and digests are
consensus data (§1).

### `kind: "avl_prove"`

```
settings : { key_length: int, value_length: int|null }
payload  : { operations: [Op], gen_proof_after: [int] }
expected : { proofs: [hex], digests: [hex] }
```

- **`settings`** is the `BatchAVLProver` constructor's `(keyLength, valueLengthOpt)`.
- **`payload.gen_proof_after`** holds ascending, distinct, 0-based indices into
  `operations` (the blesser's own `require`s enforce ascending/distinct and in-range).
  `proofs[i]` / `digests[i]` are the packed proof and 33-byte tree digest captured
  immediately after `operations[gen_proof_after[i]]` runs and `generateProof()` is called —
  parallel arrays, `len(proofs) == len(digests) == len(gen_proof_after)`.
- **No `initial_entries`.** Seeding a tree is just leading operations with a
  `gen_proof_after` boundary; this subsumes the split the single-shot `/avl-proof` oracle
  endpoint (`AvlProofGenerator.generate`) still uses, and makes the multi-cycle case
  first-class rather than bolted on.

### `kind: "avl_verify"`

```
settings : { key_length: int, value_length: int|null,
             max_num_operations: int|null, max_deletes: int|null }
payload  : { starting_digest_hex: hex, proof_hex: hex, operations: [Op] }
expected : { proof_accepted: bool,
             results: [ {ok: bool, value: hex|null} ],
             new_digest_hex: hex|null }
```

- **`settings`** is the full `BatchAVLVerifier` constructor parameter set.
- **`proof_accepted` has a single observable definition**, so two blessers cannot
  disagree: **`verifier.digest.isDefined` evaluated immediately after construction,
  before any operation is performed.** scrypto's constructor never throws — it leaves the
  tree unbuilt on a bad proof, so `digest()` returning `None` at that point is exactly "the
  proof was not accepted at this starting digest under these settings."
- **When `proof_accepted` is `false`, the blesser does not attempt the operations**:
  `results` is `[]` and `new_digest_hex` is `null`, by convention, not omission. (Running
  the ops against an unbuilt tree would produce a uniform row of `{ok: false}` that carries
  no information and invites a conformer to match the shape without reproducing the
  rejection.)
- Otherwise `results` has one entry per operation: `{ok: true, value: <hex>}` for
  `Success(Some)`, `{ok: true, value: null}` for `Success(None)`, `{ok: false, value:
  null}` for `Failure`. Once one operation fails the verifier is **poisoned** — every later
  operation in the same batch also returns `{ok: false, value: null}` (still one row per
  operation; array length always equals `operations.length`) — and `new_digest_hex` is
  `null`.

### Operation encoding (shared by both kinds)

`{tag, key_hex, value_hex?, delta?}`. Tags: `Insert`, `Update`, `Remove`, `Lookup`,
`InsertOrUpdate`, `RemoveIfExists`, `UpdateLongBy`, `UnknownModification`. `delta` is a
**decimal string** for `UpdateLongBy` (Long range — the eval tier's Long-as-string rule,
`runner-contract.md` §4; a JSON number would lose precision — `update-long-by-i64-max-boundary`
is in the corpus and exercises exactly this). `UnknownModification` is a case *object* in
scrypto with a fixed **zero-length** key — the vector still carries `key_hex` for schema
uniformity (every `op_item` requires it), but no implementation reads it for this tag
(§8/§9's finding).

### Version label: `any`

AVL proofs are not ErgoTree-versioned — the same rationale as chain's `retargeting` and
`header_votes` (`runner-contract-chain.md` §2): an `any` vector is always selected for any
runner declaring the `authds` tier.

## 3. Actuals shape

Per `schema/santa-authds.actuals.schema.json`. The actuals file carries **no kind tag** —
which verdict shape an entry carries is inferred from which fields are non-null, not looked
up from the vector:

```
avl_prove  actuals : { proofs: [hex], digests: [hex], error: null, note? }
avl_verify actuals : { proof_accepted: bool, results: [...], new_digest_hex: hex|null,
                       error: null, note? }
```

| Outcome | verdict fields | `error` | `note` |
|---|---|---|---|
| Verdict | non-null (the kind's own shape) | `null` | forbidden |
| No verdict (decode/setup failure) | all null | `"errored"` | forbidden |
| Not implemented | all null | `"not-implemented"` | forbidden |
| Panic (caught) | all null | `"panicked"` | **required** |

Total per-entry outcomes, no abstention — the shared invariant from `runner-contract.md`
§3. Two things worth calling out because they depart from the pattern elsewhere in the
suite:

- **`note` is stricter here than at the chain tier — the note-iff-panicked rule.** Chain's
  actuals allow `note` to accompany `errored` as an optional, non-graded diagnostic
  (`runner-contract-chain.md` §3). authds does not: the schema's third `allOf` branch makes
  `note` **present iff `error == "panicked"`, forbidden otherwise** — a `note` on an
  `errored` row fails schema validation, full stop.
- **The union shape is legal; the accept-shape is keyed off field presence, not a
  discriminator.** An `error: null` row must carry *either* the `avl_prove` pair
  (`proofs`+`digests`, both arrays) *or* the `avl_verify` triple (`proof_accepted`+
  `results`, plus `new_digest_hex` present as a key — string or `null` are both legitimate
  values there, since `null` means "poisoned verifier," not "field omitted"). A row
  carrying the *other* kind's fields as `null` alongside its own kind's real values is legal
  (the same union tolerance `runner-contract-chain.md` §3 documents for chain), but a row
  carrying **neither** shape complete is a schema failure, not a soft outcome.
- **A non-null `error` nulls every verdict field that is present** — `proofs`, `digests`,
  `proof_accepted`, `results`, `new_digest_hex` all go to `null` together; there is no
  partial-credit shape at this level (contrast `avl_verify`'s own internal partial credit
  via `results`, §2 above — that partiality lives *inside* a clean verdict, not in how
  failure is reported).

## 4. Grading (`grade_authds`)

Per entry the comparator (`santa-check::grade_authds(actual, entry)` — entry-taking, like
`grade_chain`, since it reads `kind` and `expected`) emits a verdict in the shared
vocabulary. **Precedence**, identical in spirit to chain (`runner-contract-chain.md` §4):
`panicked` → coal unconditionally, on either kind; `not-implemented` → blue coverage verdict
(the growth-ledger stance — "a conformer with no prover declares `not-implemented` on
`avl_prove`; that is a growth-ledger cell, not a gap," §6 below); otherwise one or more
graded dimensions, per kind.

**`avl_prove` — `proof` and `digest` are INDEPENDENT dimensions, with NO suppression
chain.** Deliberately breaking with the block tier's `valid → post_digest → cost`
suppression, and with `avl_verify`'s own chain two paragraphs below. Both dimensions share
one `clean` gate (`actual.error` is null or absent) and are then compared independently:

- `proof` is nice iff `clean` and `actual.proofs` structurally equals `expected.proofs`
  (the **whole array**, positionally — same length, same bytes at every index).
- `digest` is nice iff `clean` and `actual.digests` structurally equals `expected.digests`,
  by the same whole-array rule — **evaluated independently of the `proof` verdict.**

Rationale, stated in the grader source itself: *"A correct digest alongside non-canonical
proof bytes is the ADPROOF-FINDING class and is the single most important signal this arm
exists to surface; chaining would hide it behind a green digest."* A conformer whose
prover computes the right tree (right digest) but serializes a non-canonical proof (extra
or reordered nodes, a different flag byte) must show **exactly that** on the board:
`digest: nice`, `proof: coal`. Suppressing either behind the other would erase the one
finding this kind exists to catch.

**`avl_verify` — chained `accepted → results → digest`.** An upstream miss suppresses the
downstream dimensions to `n/a` (skipped, not graded):

1. **`accepted`** — nice iff `clean` and `actual.proof_accepted == expected.proof_accepted`
   (boolean equality).
2. **`results`** — graded only when **both** sides agree the proof is accepted:
   `accepted` is nice **and** `expected.proof_accepted == true`. (Asymmetry worth noting:
   if the runner incorrectly claims `proof_accepted: false` against a `true` expectation,
   `results` is suppressed to `n/a` even though the *expected* answer had operations to
   grade — the upstream miss already coal'd the entry, and grading a dimension the runner
   never attempted would be meaningless.) When graded, nice iff `actual.results` structurally
   equals `expected.results` — the whole array, every `{ok, value}` pair, positionally.
3. **`digest`** — graded only when `results` is **nice** (not merely non-`n/a` — a `results`
   **coal** also suppresses `digest`). This is where the chain diverges from a naive
   "grade what you can" reading: **the digest is downstream of the operations, not
   parallel to them** — scrypto's own `digest()` returns `None` the instant any operation
   poisons the verifier, so a `results` mismatch and a `digest` mismatch are frequently the
   same underlying fact reported twice; suppressing avoids double-counting it as two
   findings. When graded, nice iff `actual.new_digest_hex` structurally equals
   `expected.new_digest_hex`.

**Unknown `kind`** in a graded run is coal on every dimension the entry would otherwise
carry — never a silent pass, the standing rule every tier's grader follows.

The verdict object is `{"kind": "authds_prove", "proof": "nice"|"proof", "digest":
"nice"|"digest"}` or `{"kind": "authds_verify", "accepted": …, "results": …|"n/a",
"digest": …|"n/a"}`. `conform`'s tally pools **all four dimension names into one
slice-level board** — an `authds/<version>/<provenance>` row carries four counters
(`proof`, `digest`, `accepted`, `results`), and **`avl_prove`'s `digest` and `avl_verify`'s
`digest` share that one column.** A slice's `digest N/M` therefore mixes contributions from
both kinds: `M` is the 10 always-graded `avl_prove` digests plus however many `avl_verify`
entries reach the digest dimension (accepted correctly *and* results nice on both sides) —
not a fixed count tied to entry totals. Worth knowing before reading §9's board: it is why
`digest`'s denominator differs between two runners graded on the exact same 60-entry slice.

## 5. Provenance

Single provenance in v1: **`vendored`** — following the wire tier's ergots+Fleet precedent
(`runner-contract-wire.md`). `source: "ergots:packages/avltree/test/fixtures/<set>/<name>"`,
where `<set>` is `prover` (10 fixtures → `avl_prove`) or `avltree` (50 fixtures →
`avl_verify`).

**Vendor the inputs, re-bless every expectation — never vendor an expected value
directly.** The op sequences, tree configs, and `gen_proof_after` trigger points are
ergots' hard-won corpus and carry over verbatim; every `expected.proofs` / `.digests` /
`.results` / `.new_digest_hex` value is **re-derived through `jvm-blesser`**, never copied
from the fixture files. The prover fixtures ship **Rust-blessed** in ergots (their own
header credits `~/projects/ergo_avltree_rust/tests/prover_fixtures.rs`) — importing those
expectations as-is would make the vectors structurally incapable of catching a
Rust-vs-JVM prover divergence, exactly the failure this tier exists to prevent. The 60
re-blesses were the tier's first finding-generator, before a single runner arm mounted
(§9).

**Re-blessing enriches, it doesn't just re-derive.** The 50 `avltree` fixtures collapse
three JVM-distinguishable failure modes into one `expectedNewDigestHex: null` (proof
rejected outright · construction params disagree with the proof · a single operation
legitimately fails against an otherwise-good proof). `santa-authds/v1` keeps the three
levels separate end to end (§2's `proof_accepted` / `results` / `new_digest_hex` triple) —
collapsing them back to one `null` would false-green an implementation that rejects the
whole proof where the JVM accepts the proof and fails only one operation, exactly the
hazard the reject-arm rule warns about elsewhere in the suite (confirm at conform that each
expected diverger is actually red, and for the intended reason).

**Re-blessing gate:** `SANTA_WRITE_AUTHDS=1 sbt test` from `jvm-blesser/` (§7 — no
`ergo-core` / `SANTA_TX_BLESSER` dependency; scrypto is a main-scope `jvm-blesser`
dependency already). `VendoredAuthdsAvlProveTest` / `VendoredAuthdsAvlVerifyTest` fail loud
on any drift between the committed vector and a fresh re-derive from the same fixture
inputs — a hand-edited hex byte in the committed file, or a blesser change that was never
re-emitted, is a red gate, never a silent pass.

## 6. Conformer stances

*(Copied verbatim from `docs/specs/authds-tier.md` "Conformers" — the spec is the design
record for this table; §9 below carries the currently-mounted subset's real numbers.)*

Prover surface verified by reading production code, not manifests:

| Conformer | `avl_verify` | `avl_prove` | Notes |
|---|---|---|---|
| rudolph (JVM) | yes | yes | control; scrypto is the oracle |
| dasher (ergots) | yes | **yes** | the only independent prover reimplementation |
| vixen (arkadianet) | yes | yes | `ergo-state/{digest_store,digest_apply,store/*}.rs`; crates.io `0.1.1` |
| blitzen-eni (sigma-rust) | yes | **not-impl** | `BatchAVLProver` appears only in `#[cfg(test)]` — sigma-rust verifies, never proves |
| donner (enr) | yes | not-impl | prover only in a SANTA-side `dump_avl_tree` bin |
| comet (Fleet) | grey | grey | no AVL surface |

An eni prove arm was considered and rejected: adding a direct
`ergo_avltree_rust` dependency would grade *the fork*, not sigma-rust, and make
the board claim "sigma-rust proves conformantly" about a library that does not
prove. `not-impl` is the honest cell and flips if sigma-rust grows a prover.

## 7. JVM oracle recipe

`jvm-blesser/src/main/scala/santa/{AvlProofGenerator,AvlVerifierBlesser}.scala` wrap
scrypto 3.0.0's own `BatchAVLProver` / `BatchAVLVerifier` directly — **no gate**. Unlike
the tx/block/chain engines (which need a `publishLocal`'d `ergo-core` behind
`SANTA_TX_BLESSER`, README "Re-blessing transaction vectors"), authds needs only scrypto,
already a main-scope `jvm-blesser` dependency. This has a load-bearing consequence for how
to read rudolph's board (§8, §9): rudolph's `authds` arm is **not** a gated reflection seam
that degrades to `not-implemented` on an ungated build (the pattern `Runner.scala` uses for
its tx/block/chain engines) — it is a direct call, always present.

**`BatchAVLProver` multi-cycle (`AvlProofGenerator.generateCycles`).** One prover instance
per entry: construct with `(keyLength, valueLengthOpt)`, then walk `operations` in order.
After the operation at each index named in `gen_proof_after`, call `generateProof()` (which
packs the accumulated modification proof **and resets the prover's modification
tracking**) and read `digest` (33 bytes: 32-byte hash + 1-byte tree height), appending both
to the parallel output lists. The cycle boundary is load-bearing precisely because of that
reset: a proof taken mid-sequence is not recoverable from a one-shot run over the same
operations, which is why the vector format threads `gen_proof_after` through explicitly
(§2) rather than assuming one proof per entry.

**`BatchAVLVerifier` three levels (`AvlVerifierBlesser.verify`).** One verifier instance
per entry, constructed from `(startingDigestHex, proofHex, keyLength, valueLengthOpt,
maxNumOperations, maxDeletes)`:

1. **Level 1 — anchoring.** `verifier.digest.isEmpty` checked *before* any operation:
   `true` ⇒ return `VerifyOutcome(proofAccepted = false, Nil, None)` immediately, matching
   §2's "does not attempt the operations" convention.
2. **Level 2 — per operation.** `verifier.performOneOperation(op)` for every op in order,
   mapped `Success(Some(v)) → {ok: true, value: hex(v)}`, `Success(None) → {ok: true, value:
   null}`, `Failure(_) → {ok: false, value: null}`. One list entry per operation regardless
   of poisoning, so the output array length always equals the input operation count.
3. **Level 3 — final digest.** `verifier.digest.map(hex)` read once, after the operation
   loop — `None` if any operation poisoned the verifier.

**`toOperation` is evaluated outside the `Try`, on purpose, in both blessers.** A decoder
error (unknown tag, non-hex key, `UpdateLongBy` with no `delta`) means the *vector* is
malformed, not that an implementation failed an operation — conflating the two would ship
an authoring bug as a conformance expectation. It propagates loudly (an uncaught exception
at bless time — never shipped as a committed vector) rather than being blessed as
`{ok: false}`.

**One shared decode path, both kinds.** `AvlProofGenerator.deriveFromEntry` /
`AvlVerifierBlesser.deriveFromEntry` decode a `santa-authds/v1` entry's `settings`/
`payload` and drive the prover/verifier — the **same** function the vendored blesser (§5)
and rudolph's control arm (`Runner.authdsEntry`) both call. There is exactly one decode
path, so a control divergence can only mean the vector and the runner disagree about
*decoding* — a build error, never a finding (§8).

**Re-blessing:** `SANTA_WRITE_AUTHDS=1 sbt test` from `jvm-blesser/`, no `ergo-core`
required (contrast the tx-tier's `SANTA_TX_BLESSER=1 sbt -batch "testOnly
santa.CapturedTxTest"`, README "Re-blessing transaction vectors"). Regenerates
`vectors/authds/any/vendored/{AvlProve,AvlVerify}.ergots_corpus.json` from the vendored
fixture inputs and asserts the result equals the committed file — `VendoredAuthdsAvlProveTest`
/ `VendoredAuthdsAvlVerifyTest` fail loud on drift (§5).

## 8. Honest limitations

*(The first three points are the tier's design-time limitations, copied verbatim from
`docs/specs/authds-tier.md`'s "Honest limitations" — the spec is the design record, this
contract is the durable home.)*

- **Thin prover diversity.** Three cells on the prove arm, but rudolph *is* the oracle and
  vixen runs the same Rust crate family as the fork. **dasher is the only genuinely
  independent prover** under test. That is the point — it is the new one — but the board
  will look wider than the evidence is.
- **Sigma-protocol proving does not fit this tier.** Spending proofs are the thing SANTA is
  named for, but Fiat-Shamir is non-deterministic and the runner contract forbids an oracle
  dependency at conformance time, so there is no committable expected without an
  injected-randomness seam in every implementation — a four-codebase coordination ask, not
  a SANTA decision. Its deterministic half, reduction to a sigma tree, is already graded in
  eval (`{"kind": "SigmaProp", "raw_hex": ...}`).
- **The verify arm reaches below consensus.** On-chain `AvlTree` values carry their config
  from the tree constant, so the eval tier only ever exercises the configs its own vectors
  pin. The vendored config-variance fixtures (`keyLength` 1 and 8, fixed `valueLength`,
  `maxNumOperations` bounds) reach library surface that consensus touches only through
  ErgoScript. That is a deliberate widening, accepted when this tier was scoped.

Two further limits, surfaced building the runner arms (Task 9-10) and not yet in the spec:

- **The prove kind has no reject arm.** `avl_verify`'s actuals were deliberately enriched
  into three independently-gradable levels (§2, §4) so a clean rejection at any of them is
  a first-class, gradable outcome. `avl_prove`'s actuals shape — `{proofs, digests, error}`
  — has no equivalent field to carry "operation 3 of 7 was domain-rejected but here is what
  I had": `AvlProofGenerator.generateCycles` calls `.get` on
  `tree.performOneOperation(...)`, which *throws* rather than returning a classifiable
  `Try`, and there is no per-operation classification seam analogous to
  `BatchAVLVerifier.performOneOperation`'s `Try[Option[ADValue]]` (§7). Any domain-rejected
  prove operation therefore surfaces as `panicked`, not `errored` — a real shape limit of
  `santa-authds/v1`, discovered in Task 9, not a defect to route or fix. (It is also why no
  vendored `avl_prove` fixture can ever carry `UnknownModification` — §5/§9's finding: the
  blessing step itself would throw before such a vector could be committed.)
- **rudolph's green proves consistency, not correctness.** §7 already states the mechanism:
  the control arm calls the exact same `deriveFromEntry` functions the vendored vectors
  were blessed with. A red on rudolph's authds slice would mean the committed vector and
  the runner disagree about *decoding* the same JSON — a build error, not a finding — and
  that is also the ceiling on what rudolph's green tells you: it cannot, by construction,
  catch a JVM-vs-scrypto divergence, because scrypto *is* the oracle it is built from.
  Independent evidence about the prover/verifier's correctness comes only from the other
  conformers' cells (§6, §9).

## 9. Status

**Live** as of 2026-08-03. Corpus: 60 entries at `vectors/authds/any/vendored/` — 10
`avl_prove` (`AvlProve.ergots_corpus.json`), 50 `avl_verify`
(`AvlVerify.ergots_corpus.json`) — `blessed_by: "jvm:scrypto-3.0.0"`. Mounted conformers:
**rudolph** (control) and **dasher** (ergots). `SANTA_TX_BLESSER=1 ./conform`, slice
`authds/any/vendored`:

| Runner | proof | digest | accepted | results | red_total |
|---|---|---|---|---|---|
| rudolph (JVM control) | 10/10 | 56/56 | 50/50 | 46/46 | **0** |
| dasher (ergots) | 0/10 | 42/52 | 50/50 | 42/46 | **24** |

(`digest` pools both kinds' contributions, §4: rudolph's 56 = 10 `avl_prove` entries
always graded + 46 `avl_verify` entries that reach the digest dimension; dasher's 52 = the
same 10 `avl_prove` entries + only 42 `avl_verify` entries, since 4 of its 46 non-adverse
entries coal on `results` and suppress `digest` to `n/a`.)

**Confirmed finding — `UnknownModification` key semantics.** scrypto's
`UnknownModification` is a case *object* with a fixed **zero-length** key that sorts below
the tree's −inf sentinel, so the JVM fails the operation and poisons the verifier; ergots
and `ergo_avltree_rust` model it as **keyed** and short-circuit it like `Lookup`. Confirmed
on both sides, ruled out as a SANTA defect at the type level (the non-`$` class has no
constructor). Affects 4 `results` reds: `unknown-mod-3leaves-{absent,present}`,
`batch-16ops-mixed` (from op 14), `batch-stress-mixed-100` (ops 90–99). Full writeup, not
repeated here:
[`docs/findings/authds-unknownmodification-jvm-vs-rust.md`](../findings/authds-unknownmodification-jvm-vs-rust.md).

**Confirmed finding — `ergots@master` ships a broken AVL prover.** All 10 `avl_prove`
entries are red on both `proof` and `digest` — a **separate** finding from the one above
(no prove vector can ever carry `UnknownModification`, §8). Pushed `ergots` master
(`da2a257`) seeds an empty tree as an internal node over two sentinel leaves (height 1)
where both scrypto and `ergo_avltree_rust` seed a single leaf (height 0), so every packed
proof and digest diverges from the first cycle on. The fix (`b533fe5`) exists in the
author's local ergots checkout but is **unpushed**; npm's published `@ergots/avltree@0.3.0`
was cut from the fixed tree and is unaffected — the same version string names two different
implementations. Severity low: the verifier is the consensus surface and it is 46/50 green;
the prover is off-chain tooling. Routed at `prompts/ergots-push-avltree-prover-sentinel-fix.md`
(untracked working scaffolding — cited here for continuity, not as a durable reference).

**A prediction that did not fire.** The four `adverse-*` entries
(`adverse-malicious-extra-nodes`, `adverse-mismatched-config-keylength`,
`adverse-swapped-starting-digest`, `adverse-truncated-proof`) were flagged as a possible
`accepted` red for dasher, since the vector commits to level-1 rejection on evidence the
ergots fixtures could not themselves supply at authoring time. All four came back green —
`accepted 50/50` includes them. ergots independently agrees with the JVM on every one of
the four rejections. Recorded because a prediction that fails to land is a result, not an
omission to quietly drop.

**Not yet mounted:** blitzen-eni, donner, vixen — `avl_verify` only, per §6. Routed at
`prompts/authds-verify-arms.md` (untracked).

## 10. Worked example

```jsonc
// avl_prove entry (vectors/authds/any/vendored/AvlProve.ergots_corpus.json)
{
  "name": "insert-single",
  "source": "ergots:packages/avltree/test/fixtures/prover/insert-single.json",
  "kind": "avl_prove",
  "settings": { "key_length": 32, "value_length": null },
  "payload": {
    "operations": [
      { "tag": "Insert",
        "key_hex": "4242424242424242424242424242424242424242424242424242424242424242",
        "value_hex": "01020304" }
    ],
    "gen_proof_after": [0]
  },
  "expected": {
    "proofs": ["020000000000000000000000000000000000000000000000000000000000000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000000004"],
    "digests": ["12a83992f8707b2d9d135fcc6b62d6707f77ea6193a8f0e0adb1751474f3df8101"]
  }
}

// avl_verify entry — accept (empty tree, one Lookup — never modifies)
{
  "name": "empty-tree-lookup",
  "kind": "avl_verify",
  "settings": { "key_length": 32, "value_length": null,
                "max_num_operations": 1, "max_deletes": 0 },
  "payload": {
    "starting_digest_hex": "4ec61f485b98eb87153f7c57db4f5ecd75556fddbc403b41acf8441fde8e160900",
    "proof_hex": "020000000000000000000000000000000000000000000000000000000000000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000000004",
    "operations": [
      { "tag": "Lookup", "key_hex": "4242424242424242424242424242424242424242424242424242424242424242" }
    ]
  },
  "expected": {
    "proof_accepted": true,
    "results": [ { "ok": true, "value": null } ],
    "new_digest_hex": "4ec61f485b98eb87153f7c57db4f5ecd75556fddbc403b41acf8441fde8e160900"
  }
}

// avl_verify entry — level-1 reject (proof itself rejected; no operations attempted)
{
  "name": "adverse-truncated-proof",
  "kind": "avl_verify",
  "settings": { "key_length": 32, "value_length": null,
                "max_num_operations": 1, "max_deletes": 0 },
  "payload": {
    "starting_digest_hex": "215197cff0244d874639dab08e97913fa0b1979192b264a64f4f24f9ac132e7a02",
    "proof_hex": "04",
    "operations": [
      { "tag": "Lookup", "key_hex": "0202020202020202020202020202020202020202020202020202020202020202" }
    ]
  },
  "expected": { "proof_accepted": false, "results": [], "new_digest_hex": null }
}

// actuals — verdicts:
{ "insert-single": {
    "proofs": ["020000…0004"], "digests": ["12a83992…f3df8101"], "error": null } }
// → {"kind": "authds_prove", "proof": "nice", "digest": "nice"}

{ "empty-tree-lookup": {
    "proof_accepted": true, "results": [{"ok": true, "value": null}],
    "new_digest_hex": "4ec61f48…8e160900", "error": null } }
// → {"kind": "authds_verify", "accepted": "nice", "results": "nice", "digest": "nice"}

{ "adverse-truncated-proof": {
    "proof_accepted": false, "results": [], "new_digest_hex": null, "error": null } }
// → {"kind": "authds_verify", "accepted": "nice", "results": "n/a", "digest": "n/a"}

// the divergence this arm exists to surface: a conformer whose prover computes the
// correct final digest but serializes a differently-shaped proof (extra nodes, a wrong
// flag byte, reordered siblings) reds on "proof" while staying "nice" on "digest" —
// exactly the ADPROOF-FINDING class, and exactly why the two dimensions are never chained.
```
