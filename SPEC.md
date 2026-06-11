# SANTA — Architecture & Roadmap (umbrella spec)

The **living architectural spec**: what SANTA is *now*, and the phase roadmap. It
complements [README.md](README.md) (public intro) and [BOOTSTRAP.md](BOOTSTRAP.md)
(rationale / decision log — *why* each call was made).

**Working method.** This umbrella is the stable reference. Each phase gets a **thin
subspec** under `docs/specs/`, written just before the work and informed by the
previous phase's delivery. When a delivery conflicts with this umbrella, the
umbrella is corrected immediately — it tracks reality, not intentions.

## The conformance loop

```
        oracle                                   conformer
  chain / JVM reference                  ergots · sigma-rust · nodes
        │                                          │
        ▼                                          ▼
     blesser ───▶ committed vectors ───▶        runner ───▶ result
   (produces the   (the data contract,        (consumes,    {value, cost,
    blessed truth)   versioned, in-repo)        per-impl)     error-class}
                            │                       │
                            └──────── harness ──────┘
                          (runs a vector set through a runner,
                           compares result to blessed expected)
```

- **Oracles** bless canonical expected outputs. The sigma-rust fork is a
  *differential target*, never the oracle (decision 2).
  - block validity → **the chain** (a captured block is valid by definition); its
    post-digest/cost fine-values → the gated ergo-core `BlockEngine` composition;
  - eval fine-values (value, cost, reduced sigma-tree) → the **JVM reference**
    (`sigma-state`), the de-facto spec.
- **Vectors** are the committed, versioned data contract — *raw wire bytes in →
  blessed output out*. The contract lives in the data, not in code (decision 8).
- **Runners** are thin per-conformer consumers of one I/O contract. The *contract*
  is SANTA's; *implementations* live with the conformer's maintainers **when they
  participate** (`ergots`, `ergo-node-rust` — the author's repos; future alt impls —
  theirs). For conformers with no separate team — the **JVM reference** and the
  **`sigma-rust` fork** — **SANTA delivers the runner itself**, and ships per-language
  runner **scaffolding** so either path is cheap. The JVM reference runner (Rudolph)
  is the first such scaffold.
- **Harness** (in SANTA) runs a vector set through a runner command and reports.

## Tiers

| Tier | Vector | Blessed by | Run by |
|---|---|---|---|
| **Wire** | bytes ⇄ structure (round-trip / parse) — constants, boxes, trees, txs, headers, VLQ | JVM serializers · captured chain bytes | *any serializer*: ergots, sigma-rust, **scorex**, **Fleet**, wallets/SDKs |
| **Eval** | ErgoTree bytes (+ ctx) → typed value + JIT cost | JVM interpreter | consensus libraries (ergots, sigma-rust) + JVM |
| **Transaction** | full tx inputs → valid? + declared cost (library-decidable: script-verify + conservation/tokens/min-value/cost) | ergo-core `validateStateful` (JVM, v6) | consensus libraries that implement tx validation |
| **Block** | parent digest + ≤10 headers + parameters + block-with-ADProofs → valid? + post-digest + cost (digest-state, library-decidable) | the chain (captured) · gated ergo-core `BlockEngine` (fine-values + mutations) | digest-capable validators: ergo-node-rust (donner) + JVM control |
| **Chain** | header-chain-decidable consensus functions — difficulty retargeting + parameter-voting math; value-only; versions v5/v6/any | the chain (captured) · the JVM reference (authored edge cases) | any conformer that computes retargeting or param-voting (rudolph control; donner the natural first independent; blitzen nipopow-at-most deferred); contract: [`docs/contract/runner-contract-chain.md`](docs/contract/runner-contract-chain.md) |

The **wire tier** is the broadest — every wallet/SDK serializes while only a couple
of libraries eval — and it's squarely consensus: a `boxId` *is* the hash of a box's
serialized bytes, so a serialization divergence is a different `boxId`. Its cheapest
assertion is a **byte round-trip** (parse → reserialize → identical bytes), needing
no shared structured form; "parse → assert structure" is the richer variant.

## Contracts (sketch — each firms as its phase delivers)

**Eval-tier vector** (`santa-eval/v2`, Phase-2 Stage-1; `santa-eval/v1` for the Phase-1
closed-tree `decode-point` special case):
- `tree_bytes_hex` — serialized **function** ErgoTree `A → B` (v2); or a closed tree (v1);
- `input` — the input SValue JSON, bound to context var 1 at eval time (v2 only; absent in v1);
- **version** it's blessed under — `(activatedVersion, ergoTreeVersion)`;
- expected: typed value `{ kind, … }` + **raw JIT cost** + coarse **error-class**
  (success ⇒ null).
- Source: `sigma-state` `LanguageSpecificationV5` + `V6` (JVM-native `verifyCases`, v2) — the
  oracle's own expected values, not fork-computed. Input-carrying form preserves cost fidelity
  (cost of applying a function to an input, not of a baked closed tree).

**Runner I/O contract** — *frozen for the eval tier*
([`docs/contract/runner-contract.md`](docs/contract/runner-contract.md)): vector in → one
**actuals** record per entry, so the harness can compare any conformer's output to the
blessed expected. The runner is **total** — it emits a faithful outcome for *every* entry
and never drops, hides, or aborts on one: value + JIT cost on success, else a coarse outcome
tag (`errored` / `not-implemented` / `panicked`). A conformer's **scope is selected on
the input side** — it is run against the vector subset it claims (a v5-only library runs
the `v5/` corpus; a wire-only tool like Fleet runs wire vectors, not eval) — never by the
runner suppressing results. The result shape is whatever the op asserts: value + JIT cost
for eval; parsed structure or round-trip-ok for wire.

## Phase roadmap

| Phase | Delivers | |
|---|---|---|
| **0 — spike** | JVM blesser validated on `decode-point` | ✅ done |
| **1 — eval loop closed** | the **basic shape**: vector schema + `decode-point` committed as a canonical vector + harness + a JVM reference runner (runs green); first *independent* runner **Dasher** (ergots, `ts-runner/`) **built** — v5-scoped | ✅ done |
| **2 — eval scaled** | the eval corpus at scale — **1,974 entries / 117 files** from `sigma-state`'s `LanguageSpecificationV5` + `V6`, version-split (`v5/` + `v6/`); Dasher (ergots) gates v5 against the blessed expected | ✅ (incl. Context-input `getVarFromInput`, Stage 2b) |
| **3 — conformers + CI** | runner-agnostic orchestrator `./conform` + [integration contract](docs/contract/runner-integration.md) built (presence-as-state over `runners/*/`, one shared comparator, per-slice table); **rudolph + dasher + Blitzen** (`sigma-rust` develop & eni — two submodules) + **Comet** (Fleet SDK, wire-only) wired into a live **5-way**; ergo-node-rust runner + CI gate next; [self-test kit + scoreboard](docs/specs/phase-3-self-test-kit-and-scoreboard.md) designed | 🟢 underway |
| **4 — block tier** | digest-state reframe: `santa-block/v1` (parent digest + headers + params + block-with-ADProofs) · 4 captured seeds ADProofs-verified (incl. the epoch-boundary donor) + 6 authored mutation classes · gated `BlockEngine` + rudolph control row · **donner (ergo-node-rust) + vixen (arkadianet/ergo) both grading** | ✅ live (7-way) |
| **5 — reject arm** | authored mutation vectors (rejected *for the right reason*) — live for eval + block; tx-tier arm next | 🟢 underway |

Phases 3–5 are roadmap, not spec — each gets its subspec when reached. The **wire
tier** is a *parallel* track, not a sequential phase: it reuses the same `fixture-gen`
assets and serves the broadest conformer set (scorex, Fleet, wallets) — pick it up
alongside the eval tier once Phase 1's loop shape is proven. **Now underway:** the
`santa-wire/v1` round-trip tier is live end-to-end — **vendored** `Box` (11) · `SigmaBoolean`
(7) · `Transaction` (17) · `Constant` (178) = 213 round-trips (`vectors/wire/v5/vendored/`),
JVM-canonicalized from ergots' `fixture-gen` + Fleet's `_test-vectors` seeds, schema-gated, and
graded across the 5-way (rudolph + blitzen 213/213; dasher 196 round-trips Box+SigmaBoolean+Constant,
Transaction 17 not-implemented — ergots has no tx serializer; comet (Fleet SDK) 185 — its honest gaps
are recorded findings: a SigmaProp-constant serialize asymmetry and no unsized-tree parsing).
Header stays capture-only ([`docs/specs/wire-tier.md`](docs/specs/wire-tier.md)).

> **Note on the "reference runner":** the JVM runner (the blesser in consume-mode)
> passes trivially — it proves the *harness mechanics* and defines the runner contract
> by example, but it is **not yet a conformance check**. Real conformance began with the
> first *independent* runner, **Dasher** (ergots), built **2026-05-31** in SANTA's
> `ts-runner/` — it consumes `@ergots/ergoscript` as a library (exactly as Rudolph
> consumes `sigma-state`; the runner is SANTA's, the implementation-under-test is a
> dependency). On first contact it surfaced genuine JVM-vs-ergots divergences (see
> [`docs/findings/eval-jvm-vs-ergots.md`](docs/findings/eval-jvm-vs-ergots.md)). The runner
> lives in SANTA for now; ergots may absorb it later.

## Glossary & roster

A tasteful theme on the *flavorful* surfaces; plain conventional terms for the data
and wiring (decision 8 — the contract stays unambiguous).

- **nice / naughty** — a runner's conformance *verdict* on a vector: its output
  matches the blessed expected (**nice**) or diverges (**naughty**). This is the
  *match-or-not* axis, distinct from a vector's own accept/reject validity — a
  vector that *should* be rejected, correctly rejected, is **nice**.
- **the nice list** — the committed canonical vector corpus; the record of correct
  behaviour every conformer is checked against.
- **lump of coal** — a single divergence (one naughty entry) in a report.
- **runner** — a conformer's consumer of the vectors (the plain contract term).
  Each runner instance also carries a **reindeer codename**: a friendly alias
  *alongside* its canonical impl id, never replacing it.

### Reindeer roster

| Codename | Runner (impl) | Notes |
|---|---|---|
| **Rudolph** | JVM reference (`sigma-state`) | leads — the oracle/reference the others follow |
| **Dasher** | `ergots` | first independent runner (`ts-runner/`, pure-TS) — **v5-green on spec**: gates the `v5/` spec corpus against the JVM-blessed expected, **1,757 / 1,757** (the 52 healed AvlTree entries initially surfaced a SANTA harness gap — the result-encode bridge lacked an AvlTree arm, fixed same-arc); the live manifest declares **version ≤ v6**, so v6 is in scope — its v6 reds are ergots' TDD roadmap ledger, not exclusions. |
| **Blitzen** | `sigma-rust` (×2 submodules) | `develop` (upstream, value-only, `cost:false`) + `ergo-node-integration` fork (`--features jit-cost`, `cost:true`) — eni green except the 10 genuine standing divergences the 2026-06-07 batch surfaced (Box u64 signed-view ×6 · CONTEXT.headers structural · Header stateRoot/powOnetimePk · AvlTree bad-proof), all routed; develop misses 10 v5 values the fork fixed, plus the v6 surface |
| _(unassigned)_ | `ergo-node-rust` · … | assigned on registration |

Nine reindeer = a deliberate soft cap; there won't be dozens of independent Ergo
consensus implementations.

## Status

Phase 1 delivered — the eval loop runs green end-to-end. Phase 2 delivered the **eval
corpus at scale**, since grown by authored gap-fillers: **2,340 entries across 209 files**
(**spec** 2,026/119 blessed by `sigma-state` from its language specification + **authored**
314/90), version-split into **v5** (1,929 — the cumulative v5/mainnet surface) and **v6**
(411 — the v6 new-feature surface); a JSON-Schema gate validates all
209. A standing **coverage manifest** (`docs/coverage/eval-coverage.json`,
`santa-coverage/v1`) maps per-family ops / `(typeId, methodId)` methods / arms / tree
shapes, read off each entry's deserialized tree and suite-gated current — the
registry-diff surface for conformers. The conformer layer is live: `./conform` runs the **full grid** over `runners/*/` — the N-way
is discovered, not declared (currently Rudolph · Dasher · Blitzen develop & eni · Comet). **Dasher (ergots) is fully green across the ENTIRE eval tier — every v5+v6 spec+authored slice, value, cost, and reject (2026-06-10)**; its remaining reds are all roadmap not-impls (tx ×4, wire Transaction ×17 — the growth ledger). (Along the way: the 52 healed AvlTree-typed entries initially surfaced a SANTA harness encode gap — the result-encode bridges lacked an AvlTree arm, fixed same-arc; the last two eval reds fell to SANTA-side adapter work — the contract's `lastBlockUtxoRoot` dummy passed into ergots' `makeContext`, and the oracle-unbound `Box.getReg`-as-MethodCall 99:7 registry miss classified `errored`, not not-implemented, since the JVM itself binds no eval handler for it.) **Blitzen** (`sigma-rust`) via the
eni fork **reached red_total 0 at `a4ee7442` (2026-06-07)** across the then-canonical surface
(eval value *and* cost, wire 213/213, transaction 4/4 valid + 4/4 cost) — the suite's first
fully-green independent conformer. The road there was the loop working as designed, divergences
surfaced → routed → converged in rounds: the Box u64 signed-view ×6 (unbounded `getULong`
mirror), the `Header.stateRoot`/`powOnetimePk` surfaces, `AvlTree.insertOrUpdate#bad-proof`, the
SigmaProp EQ conjecture-throw comparer arm ×2 (@ `de6331cb`), the `CONTEXT.headers` structural
model (fixed `[Header; 10]` → `BoundedVec<Header, 0, 10>` + a standalone `last_block_utxo_root` —
the genesis-window finding, `docs/findings/sigma-rust-fixed-header-window.md`), and the
AvlTree wrong-tree-proof per-method row (contains→false / update,remove→None / the #908
insert version gate — which also exposed remove's op-results-ignored `cfor` semantics). The
**F4 AvlTree degenerate-edge round (2026-06-07)** then grew the corpus by 57 vectors (ergots'
asks + sigma-rust's twins) that surfaced **21 new eni divergences** — 5 classes: bad-proof-bytes /
negative-keyLength-tree / wrong-value-length **panics**, `updateDigest` non-33-byte **over-reject**,
and `TreeLookup` eval **over-accept** (a consensus-split; ergots converges on the last two) —
routed to sigma-rust: the loop surfacing the next round of genuine divergences. The
**fork-wiring convergence (2026-06-08)** closed the crash classes: the `ergo_avltree_rust` fork's
Err-not-panic fix (`5033d0e`) + source-compatible `AVLTree::new` (`a4a2aa7`) + eni's `3e27412b`
updateDigest fix, pulled in by **blitzen-eni's declared `[patch.crates-io]` override** (the
contract-§3 build-identity invariant's first live runner instance — sigma-rust eni's own manifests
stay on crates.io; the runner manifest declares the fork, `Cargo.lock` pins the rev) — eni
21 → 5: four cost-coals on the degenerate paths + the `option-tag-02` over-reject, re-routed —
and closed same-day: the reconstruct-fail costing seam (`treeHeight` 0 when reconstruction can't
start) + the nonzero-Option-tag parser landed (eni `033a0ead`), **eni red 0** — the suite's second
full-green convergence, now under the declared fork override. The **F5 checkType/ingress round
(2026-06-08→10)** repeated the cycle — 7 authored witnesses surfaced 5 eni divergences (checkType
×2, a bidirectional substConstants version-source bug, rule-1012) which sigma-rust closed at
`25fdbcbd` → **eni red 0 again, the third full-green convergence (2026-06-10)**. The **SFunc-arity
witnesses (ergots' Ask 11, 2026-06-10)** then opened the next round: the JVM supports only unary
functions at eval (`FuncValue.eval`/`Apply.eval` reject `args.length ≠ 1` — eagerly, at closure
creation, even when the lambda is never applied), but sigma-rust (both branches) and
arkadianet/ergo all **evaluate non-unary lambdas to completion** (`(x,y)=>x+y` applied → 7 where
the JVM errors) — a fresh over-accept class on all three independent Rust conformers (eni 0 → 3);
the application-arity arm and the lazy-`If`-skip accept guard (the reject is eval-time, not
parse-time) hold green everywhere. sigma-rust closed it same-day (eni `28bc8920`, gate at the
single `Value::Lambda` construction site) — **the fourth full-green convergence, the fastest
loop yet**. The **atLeast children-cap pins (ergots' Ask 15, same day)** then split the board on
a different axis: the JVM checks the 255-children cap BEFORE `AtLeast.reduce`'s degenerates
(`atLeast(0, 256 props)` errors, never TrueProp), and every conformer went red on a *different*
arm — eni over-accepts the ordering pin (its cap sits in the non-degenerate path only), develop
over-rejects the `bound>n`-at-the-boundary FalseProp arm, dasher and vixen lack the cap entirely
(both over-accept the 256-children rejects; ergots' fix lands their current batch). The **GE
canonical-bytes round (Ask 16, same day)** — three families pinning `GroupElementSerializer`
semantics (invalid points fail at deserialize even in dead branches; a `0x00`-lead "garbage
identity" parses with bytes 1..32 discarded and re-serializes canonically) and the **byte-vs-value
identity bases** (box/header twins differing only in a register/minerPk ENCODING: ids differ ⇒
container EQ false, while the decoded points compare equal) — split four conformers along exactly
those bases: sigma-rust (both branches) over-EQs the **byte basis** (box and header twins compare
true — container identity on normalized values, not retained slices), while arkadianet — byte-based
everywhere — passes those but fails every **value-basis** arm (no curve validation, no identity
normalization, byte-equality on GE; 8 cells), and dasher reds the six cells its in-flight batch-4
GE-ingress fix targets (no curve-validate at parse, no normalize-on-serialize, value-basis box EQ).
The batch's tail rounds: the **Coll-HOF per-element cost ladder** (map/filter/exists/forall/fold at
n=2 and n=4 — two points pin each arm's per-element slope, ADD_TO_ENV included) landed **green on
every conformer** — a board-wide agreement pin — while the **Context op-forms** (the bare 0xa6/0xac
wire forms of properties the corpus had only exercised as PropertyCalls — which also COST differently:
op-form 15 vs PropertyCall 20) instantly caught sigma-rust (both branches) unable to parse 0xa6 and
dasher panicking on it, with arkadianet alone handling the full opcode table.
Upstream `develop` still misses the fork's fixes pending PR merges, plus the v6 surface —
the loop surfacing genuine cross-impl divergences, recorded, routed, and converging. The runner contract
holds a faithful per-entry outcome model (no abstention — scope is an input-side selection).
The **wire tier is opened** — `santa-wire/v1` byte-round-trip vectors (`Box` + `SigmaBoolean`,
JVM-canonicalized from ergots' `fixture-gen`) are blessed and schema-gated, and its first finding
(a box `creation_height` overflow the JVM rejects but sigma-rust accepts) is recorded in
[`docs/findings/`](docs/findings/wire-jvm-vs-sigma-rust.md). The **transaction tier is live** — `santa-transaction/v1` schema; **4 captured vectors** (`vectors/transaction/v6/captured/`), JVM-blessed via `ergo-core 6.0.2.1 validateStateful`; grading (`grade_transaction`: valid + declared cost, accept-arm only); contract at [`docs/contract/runner-contract-transaction.md`](docs/contract/runner-contract-transaction.md). Conformer stances: **Rudolph control** (gated `TxEngine` behind a reflection seam; conform CI publishes ergo-core itself, cached by tag — ungated builds degrade to `not-implemented`) · **Blitzen-eni `valid 4/4 · cost 4/4` byte-exact** — the initial bless surfaced 3 genuine cost divergences (avl ops uncosted · deserialize-substitution presence charge · UBI-arith misclassified); decomposed, routed, fixed upstream, re-graded exact at fork-eni `324cc4cd` — the tier's first divergence→fix→convergence loop · **Blitzen-develop `valid 0/4`** (each seed red with its own upstream bug; the bigint-downcast seed exposes the production-path tree-version bug that eval structurally cannot catch) · **Dasher `4 not-implemented`** (growth ledger) · **Comet out-of-scope** (wire-only; Fleet has no verifier — see the tx contract §7). Provenance: `captured` primary; `authored` for the reject arm (not yet built). The **block tier is live** — the **digest-state reframe**: a `santa-block/v1` vector hands over the parent state digest + ≤10 headers + in-force parameters + a full block *with ADProofs*, and the conformer decides `valid` + computed `post_digest` + `cost` — full consensus-grade block validation, library-decidable, no UTXO DB or sync (Ergo's stateless-validation design). **4 captured testnet seeds** (2666 cost 39379 — the triple-anchored keystone · 111927 · 184137 · the epoch-boundary donor 2560), each ADProofs-verified `parent_digest → header.stateRoot` at bless time, **+ 6 authored mutation classes**, each JVM-confirmed to reject for its intended reason (params-shrink · stateroot-flip · adproof-tamper · txs-reorder · pow-solution-flip over 2666; **version-gate over the boundary donor 2560** — its first, mid-epoch authoring was retired by a donner-surfaced, JVM-verified finding: `exBlockVersion` fires only at epoch-boundary blocks, `processExtension` gated on `epochStarts` at `ErgoStateContext.scala:246`, so the engine is boundary-gated, JVM-exact; en route the bundled testnet conf's `votingLength` proved to be the mainnet-default 1024 instead of testnet's 128 — chain-proven by params extensions at every 128-multiple — a latent conf bug only the epochStarts path could expose). The re-authored version-gate immediately caught BOTH independent conformers accepting it (they source the boundary check from the block's self-declared extension instead of the handed pre-state params — the trusting-block-self-declared-data bug class). The 4th seed (powhit-28474) is held: the rust AVL prover regenerates a valid-but-**non-canonical** proof for its data-input Lookup (`blake2b256 != adProofsRoot` — a latent serve-side consensus bug, recorded in [`ADPROOF-FINDING.md`](docs/findings/testnet-powhit-return-type/ADPROOF-FINDING.md)); it joins when a JVM-sourced canonical proof lands. Oracle = the gated `BlockEngine` (ergo-core composition: version/PoW/section-digest tier → `execTransactions`-model cost loop → `ADProofs.verify` replaying the reproduced `stateChanges`); contract at [`docs/contract/runner-contract-block.md`](docs/contract/runner-contract-block.md). Conformers: **rudolph control** `captured 4/4·4/4·4/4 + authored 6/6` · **donner** (ergo-node-rust's digest-state seam, `cost: true`) **LIVE** — building it surfaced + fixed two real enr consensus bugs (v1-only mining transactionsRoot — v2+ commits txIds ++ witness-ids — and a missing maxBlockCost block-sum) before the runner ever mounted; its version-gate accept was routed and fixed same-day (enr `380941a` — the vector's pre-state table becomes `expected_boundary_params`, the node's own sync wiring; donner back to red 0 over the full 4+6 corpus) · **vixen** (arkadianet/ergo) grew a block arm the same day — its debut surfaced a genuine block-aggregation cost divergence (111927: 169202 vs blessed 170876), plus the same version-gate accept (still open) · libraries (blitzen ×2, dasher, comet) out-of-scope grey — block application is the node's layer. **Next:** 28474's canonical proof; the tx-tier authored reject arm.
The **chain tier is live** — `santa-chain/v1`; **5 files / 10 entries** spanning two kinds (retargeting + parameter-voting) across four slices (`any/captured` 2 · `any/authored` 3 · `v6/captured` 1 · `v6/authored` 4), value-only (no cost dimension at this tier). Corpus: `Retargeting.testnet_points` (2 captured recalculation points, targets 393601/393473 — engine FAIL-LOUD-equal to the on-chain headers' nBits) · `Retargeting.damping_clamps` (flat-control + both damping clamps; the 0.5×/1.5× clamps live only in `eip37Calculate`, so these carry the eip37 settings pair — the settings-driven EIP-37 dispatch is exercised; a mainnet EIP-37-era captured window stays a gap) · `Voting.testnet_epoch_2560` (the real boundary, identity epoch — table equality is the pin, engine FAIL-LOUD-equal to `parseExtension`) · `Voting.threshold_edges` (half = no step under strict `>` · half-plus-one steps exactly id 1 · fork votes without an in-progress round count for nothing) · `Voting.window_clamp` (chain-start clamp ⇒ empty seed ⇒ unseeded votes drop). Oracle: gated `ChainEngine` (ergo-core `DifficultyAdjustment` / `Parameters.update`), every captured expected cross-checked against chain history at bless time. Contract: [`docs/contract/runner-contract-chain.md`](docs/contract/runner-contract-chain.md). Conformers: **rudolph control** 10/10 (all 4 chain slices green) · **donner LIVE 10/10** (santa-donner `aaca7cf` / enr `9ccc6e7`) — the routing round converted the heads-up into a fix-before-first-grade: enr's plain-counter epoch tally was a REAL consensus bug on its live boundary path (`count_votes_in_epoch` fed `compute_expected_parameters`), replaced by seeded semantics, plus three further JVM-exactness finds their cross-read surfaced (approval counts = closing-epoch-120 PLUS collected · lifecycle reads an original-table snapshot · approved-vote-for-unknown-id errors like the JVM · id 9 steppable) · blitzen nipopow-at-most deferred · dasher ledger · vixen offered (prompt out).
