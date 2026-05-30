# SANTA — Bootstrap / Brainstorm Seed

Context to start designing SANTA. This is a **starting point, not a spec** — nothing
here is decided. It's the state of thinking as of 2026-05-30, carried over from the
ergo-node-rust conversation where the idea formed.

## Why SANTA exists

The `ergots` mainnet-validation harness (a pure-TS reimplementation of Ergo's
consensus eval) was walked against mainnet from genesis, differentially checked against
the sigma-rust fork (oracle), with the public JVM endpoint as ultimate authority. Over
1M+ blocks it surfaced ~two dozen divergences — each a real difference between the TS
port and canonical behavior:

- `decodePoint` identity leniency (leading `0x00` ⇒ infinity point) — h=1,111,884
- storage-rent (expired-box) spends with empty proofs — h=1,051,232
- `MultiplyGroup` type inference — iter-25
- JIT **cost** drifts (ConstantPlaceholder cost, BigInt floor-mod, Coll-equality) — iters 1/9/20
- `SAny` type leniency in Map/Append — iters 16/19/21/22
- active `maxBlockCost` (inherited, not boundary-only) — h=1,144,466
- … (full log: `~/projects/ergots/tools/mainnet-validate/findings/`)

The realization: those divergences **are** a conformance test corpus, and the
differential walk **is** differential/conformance testing — but today it lives only
inside ergots, checked against a moving fork. SANTA lifts it into a language-agnostic
vector suite every Ergo implementation can run, the way Ethereum's
`execution-specs` (formerly `execution-spec-tests` / EEST) works.

## The shape

Versioned **JSON test vectors** + thin per-implementation **runners** + CI. "The wire
is the spec": a vector is **raw serialized bytes in → expected output**, since every
implementation already parses the wire format.

Two tiers, mirroring EEST's `state_test` vs `blockchain_test`:

- **Eval / transition tier** (≈ `state_test`): operation-level, e.g.
  `decodePoint(bytes) → point`, `reduce_to_crypto(tree, ctx) → {sigma_tree, cost}`.
  Run by the **libraries** (ergots, sigma-rust) with **no full node**. Almost all the
  divergences lived here, so most vectors will too.
- **Block tier** (≈ `blockchain_test`): `block H → valid? / state-root`. Run by the
  **node** (ergo-node-rust). The coarse accept/reject gate.
  *(EEST precedent: `evmone` — a library — consumes `state_test`; full clients consume
  `blockchain_test`. Same library-vs-node split.)*

## Decisions reached so far

1. **Take EEST's design, don't fork it.** EEST is ~80% Ethereum domain model
   (accounts/EVM/gas/hardforks) that doesn't transfer to eUTXO/ErgoScript, and it
   solves *authoring + filling synthetic* tests, whereas our accept-arm corpus is
   *captured real mainnet blocks*. Steal the conventions (versioned fixtures,
   fill-once/consume-many, fixture-shape discipline, consumer pattern,
   fixtures-from-a-reference-impl). Build bespoke, Ergo-native.

2. **The fork is NOT the oracle.** Baking expected outputs against our sigma-rust fork
   = a self-portrait, not conformance (it carries un-merged PRs that may land upstream
   in a different form). Anchor expected values to:
   - **block validity → the chain** — a block on mainnet is valid by definition;
     implementation-independent, fork-free. The gold standard.
   - **fine-grained outputs (cost, sigma-tree) → the JVM** — the de-facto spec; these
     aren't observable on-chain, so only an interpreter can bless them (likely needs a
     Scala/ergo-core harness). Version-stamp anything that stays fork-relative; a
     re-bless that shifts a value flags a real behavioral change.

3. **Bake vs live.** Bake expected outputs into committed versioned vectors for a fast
   CI gate; keep the ergots full mainnet walk as the periodic *live* differential (the
   slow, thorough tier). The vector set grows as the accumulation of divergence blocks.

4. **Accept arm vs reject arm.** Captured mainnet vectors only prove the *accept* path
   (mainnet blocks are all valid). A validator is proven by what it *rejects*, so the
   high-value, harder half is **authored negative/mutation vectors** — deliberately
   broken blocks (bad PoW, forged proof, unbalanced value, over-cost) asserted to be
   *rejected, for the right reason*. Consensus symmetry: a false *reject* forks you off
   the network as surely as a false accept corrupts state → reject the invalid set
   *exactly*, no wider.

5. **Error normalization.** TS/Rust/Scala error taxonomies differ — assert a coarse
   class (ok / errored, maybe a small enum), never exact messages.

6. **Eval-tier first, JVM-anchored — and validated (2026-05-30).** v1 starts with the
   eval-tier (where the ~110 fork-blessed `fixture-gen` vectors and most divergences
   already live), anchored on the JVM per decision 2. A standalone Scala blesser
   (`jvm-blesser/`, pinned to `sigma-state` 6.0.3 — the version `ergo-node-build` ships)
   reproduces the `decode-point` corpus **exactly** against the reference interpreter
   (value + JIT cost + accept/reject). It drives the interpreter in "evaluate a raw
   expression" mode — *below* its proposition/version safety layer — which needs two small
   reaches into `private[sigma]` internals (lenient deserialize for non-SigmaProp roots;
   raw JIT cost before the ÷10 block-cost scaling), done via package shims.

7. **Eval-tier vector format (discovered by blessing, not designed up front).**
   - input: serialized **ErgoTree bytes** ("the wire is the spec"); the root expression
     may be **any type** (GroupElement, Long, …), not only a SigmaProp.
   - output: typed value `{ kind, … }` (mirrors the fork/ergots shape) + **raw JIT cost**
     (block cost is the ÷10 projection, derivable) + coarse **error-class** (success ⇒ null).
   - each vector records the **version it's blessed under**: `(activatedVersion,
     ergoTreeVersion)`. The JVM enforces version consistency; the fork left it implicit
     (`force_any_val`), which doesn't round-trip.

8. **Contracts live in the data, not the code.** SANTA *is* a contract enforcer at the
   vector level — enforce it with a versioned **vector schema** (validated) + a precise
   **runner I/O contract**, plus the differential-vs-oracle as the executable
   postcondition. Skip pervasive in-code Design-by-Contract (the libraries' own `require`s
   + the type system + the differential already cover it); keep only surgical assertions at
   the **blessing boundary**, where a silently-wrong canonical value is the one failure the
   differential can't catch (it *becomes* the reference the differential trusts).

## Open questions

Resolved since the brainstorm (see decisions 6–8):
- ~~Vector JSON schema (eval-tier)~~ — largely settled (decision 7); **composite value
  encodings** (Coll / Box / SigmaProp / Header) still to confirm as more ops are blessed.
- ~~Is the JVM blesser worth it up front?~~ — **yes**, validated (~150 LOC, decision 6).
- ~~v1 scope~~ — eval-tier first (decision 6).
- Error normalization — coarse-class **confirmed live**: TS `decode-point-invalid` vs JVM
  `Not enough bytes` / `Incorrect length` all collapse to "errored".

Still open:
- **Scaling the eval-tier** — wire the other ~110 `fixture-gen` ops through the blesser;
  expect per-op wrinkles (context-reading ops, composite value types).
- **Negative / mutation authoring** — broken inputs + their expected rejection; how much
  derives from valid vectors by mutation.
- **Runner interface per language, repo layout, CI topology** — the cross-language I/O
  contract (decision 8) and where ergots / sigma-rust / ergo-node-rust / JVM plug in.
- **Block tier** — captured-block vectors, chain-blessed; the coarse accept/reject gate.
- **Baking pipeline** — block-tier from the chain is cheap/fork-free; the eval-tier baker
  now exists (decision 6). How vectors get regenerated + re-blessed in CI.

## Reference

- Ethereum execution-specs (executable spec + tests; absorbed the former
  `execution-spec-tests` / EEST in 2025): <https://github.com/ethereum/execution-specs> ·
  docs <https://eest.ethereum.org/>
- Classic common tests: <https://github.com/ethereum/tests>
- Concepts: conformance testing; differential/oracle testing.
- Seed corpus: the ergots divergence findings (`~/projects/ergots/tools/mainnet-validate/findings/`).
