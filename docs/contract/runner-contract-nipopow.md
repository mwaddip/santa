# SANTA Runner Contract — NiPoPoW tier (`santa-nipopow/v1`)

> **Status: the committed result-shape contract for the nipopow tier (`santa-nipopow/v1`).**
> A lean companion to the eval, wire, transaction, block, chain, and authds contracts — it
> specifies only what is nipopow-specific and inherits totality, never-panic, faithful
> outcomes, and the comparator topology from [`runner-contract.md`](./runner-contract.md).
>
> Machine-checkable schemas:
> [`schema/santa-nipopow.vector.schema.json`](../../schema/santa-nipopow.vector.schema.json),
> [`schema/santa-nipopow.actuals.schema.json`](../../schema/santa-nipopow.actuals.schema.json).
>
> Oracle: JVM `ergo-core` 6.0.2.1's `NipopowAlgos` (`blessed_by:
> "jvm:ergo-core-6.0.2.1-NipopowAlgos"`), driven by `jvm-blesser`'s `NipopowVectorGen`
> (bless-time, §5) and `NipopowEngine` (rudolph's live control arm, §6) — both call the
> same underlying `ergo-core` classes but are two independently-written call sites, not one
> shared function (contrast authds, §6 below). **No separate `docs/specs/nipopow-tier.md`
> design record exists for this tier** — unlike authds/block/chain, whose per-tier contracts
> point at a companion spec doc, this document is the sole durable source.

## 1. Tier boundary

A **nipopow runner** decides the **NiPoPoW protocol surface directly** — interlink-vector
bookkeeping and proof construction — the KMZ17 "Non-Interactive Proofs of Proof-of-Work"
machinery that lets a light client verify a header is buried *k* blocks deep in the best
chain using O(log n) headers instead of downloading the whole chain. Two kinds in v1:

- **`nipopow_interlinks`** — given a synthetic header chain, compute every header's
  interlinks vector (`NipopowAlgos.updateInterlinks`).
- **`nipopow_prove`** — given the same chain plus KMZ17 security parameters `(m, k)`, and
  optionally an anchor header id, construct a NiPoPoW proof (`NipopowAlgos.prove`) and
  produce its canonical serialized bytes.

This is consensus-*adjacent*, not consensus-validated by ErgoScript: a header's interlinks
vector is packed into that header's own `extensionRoot` (a real, on-chain-committed digest —
§5), so a wrong interlinks computation produces a header no honest node would have mined, but
neither `updateInterlinks` nor `prove`/proof bytes are themselves interpreted by any
ErgoScript contract the way, say, AVL+ proof bytes are (authds §1's `blake2b256(proofBytes)
== header.adProofsRoot` rule has no nipopow analogue). The tier exists for the same reason
authds does: below-the-script-layer functions that a full implementation needs to get
right, with no cheaper place in the suite to catch a divergence.

What nipopow is **not**: there is no `nipopow_verify` kind in v1 — this tier grades
**production** (computing interlinks, constructing a proof) never **consumption** (a light
client validating a *received* proof — `NipopowProof.isValid`, `hasValidConnections`,
`hasValidHeights`, `hasValidDifficultyHeaders`, none of which are exercised here; §7). The
tier also never uses `continuous: true` proof mode (§5) and never touches real Autolykos
mainnet headers — the entire corpus is a synthetic, JVM-authored chain (§5), unlike
authds's vendored fixtures or the block/chain tiers' mainnet-captured seeds.

## 2. Vector format (`santa-nipopow/v1`)

A nipopow vector file is a committed JSON under `vectors/nipopow/any/authored/` whose
envelope is `{schema: "santa-nipopow/v1", blessed_by, chain, entries}`. **`chain` is new in
this tier** — no earlier tier's envelope carries a file-level payload shared across entries;
every prior tier's entries are self-contained. Here, `chain` is one ordered, synthetic
header sequence and every entry in the file references it (by height, or implicitly, by
grading against it in full) rather than embedding its own header data.

**`chain[i]`**: `{height, headerHex, interlinks}`.
- `height` is 1-based and **must be contiguous from 1** (`chain[i].height == i + 1`) — not
  expressible in JSON Schema, so `validate`'s `nipopow_path_guard` enforces it directly
  (§6 of `tools/validate/src/main.rs`).
- `headerHex` is lower-case hex of the header's canonical `HeaderSerializer` bytes
  (includes the fake-PoW solution, §5).
- `interlinks` is that header's **own blessed interlinks vector** — an array of 64-hex-char
  (32-byte) modifier-id strings. This field does double duty: it is also the
  `nipopow_interlinks` kind's expected value (§3, §4) — there is no separate `expected` on
  that entry carrying its own copy.

**`entries[i]`**: `{name, kind, source, payload?, expected}`, kind-dispatched.

### `kind: "nipopow_interlinks"`

```
payload  : (absent)
expected : {}   — deliberately empty; chain[i].interlinks IS the expected value (§4)
```

Exactly one such entry per file in the current corpus — `name: "interlinks"`,
`source: "santa:authored:jvm-chain"` — grading the **whole chain's** interlinks in one
verdict (§4), not one entry per height.

### `kind: "nipopow_prove"`

```
payload  : { m: int, k: int, headerId: hex64 | null }
expected : { proofHex: hex }
```

- **`m`, `k`** are the KMZ17 security parameters, passed as `PoPowParams(m, k, continuous =
  false)` (§5): `m` the minimal μ-superchain length, `k` the suffix length.
- **`headerId`** is `null` for a **tip** proof (prove over the file's `chain` exactly as
  given) or a 64-hex-char header id for an **anchored** proof (prove over the chain
  *truncated* at that header — the truncation rule, §5).
- **`proofHex`** is lower-case hex of `NipopowProofSerializer.toBytes(proof)` — the same
  wire encoding a real node gossips/persists (`w.putUInt(m)`, `w.putUInt(k)`, length-prefixed
  prefix `PoPowHeader`s, length-prefixed `suffixHead`, length-prefixed `suffixTail` headers,
  a trailing `continuous` byte).
- **`source`** is `"santa:authored:NipopowAlgos.prove"` for tip cases or
  `"santa:authored:truncated-prove"` for the anchored case — both JVM-authored, never
  vendored or captured (§5).

### Version label: `any`

NiPoPoW proofs are not ErgoTree-versioned — the same rationale as chain's
`retargeting`/`header_votes` and authds (`runner-contract-chain.md` §2,
`runner-contract-authds.md` §2): an `any` vector is always selected for any runner
declaring the `nipopow` tier. Provenance is single-valued too: every v1 vector is
`authored` (§5) — there is no `vendored` or `captured` nipopow provenance yet (§7).

## 3. Actuals shape

Per `schema/santa-nipopow.actuals.schema.json`. Like authds, the actuals file carries **no
kind tag** — which shape an entry carries is inferred from which fields are non-null:

```
nipopow_interlinks actuals : { interlinks: [[hex64,…], …], error: null }
nipopow_prove       actuals : { proofHex: hex, error: null }
```

| Outcome | verdict field | `error` | `note` |
|---|---|---|---|
| Verdict | non-null (the kind's own field) | `null` | forbidden |
| No verdict (decode/setup failure) | `null` | `"errored"` | forbidden |
| Not implemented | `null` | `"not-implemented"` | forbidden |
| Panic (caught) | `null` | `"panicked"` | **required** |

**Note-iff-panicked** — the same stricter rule authds carries over chain's looser one
(`runner-contract-authds.md` §3): the schema's `allOf` forbids `note` on both `error: null`
and `error: "errored"` rows, and requires it exactly on `"panicked"`.

`interlinks` is a **jagged array of arrays**: `actual.interlinks[i]` is the runner's
computed interlinks vector for `chain[i]`. Its outer length need not equal `chain.length` to
pass schema validation (the two schemas don't cross-reference each other) — a length
mismatch is a **grading** fact (§4), not a schema failure.

## 4. Grading (`grade_nipopow`)

Signature: `grade_nipopow(actual: &Value, entry: &Value, chain: &Value) -> Value`. **This is
the one structural novelty in the runner contract**: every other tier's grader takes only
`actual` plus the entry (or its `expected`); nipopow's grader also takes the **file-level
`chain`** value, because `nipopow_interlinks`'s expected value lives there, not in the entry
(§2). `conform` threads it through as `grade_nipopow(&actual, e, &vec["chain"])`.

**Precedence**, identical in spirit to every other tier: `panicked` → coal unconditionally,
checked first and unconditionally; `not-implemented` → the blue coverage verdict (the
growth-ledger stance, §7/§8 — a conformer with no nipopow arm declares
`not-implemented`, not a gap); otherwise one kind-specific dimension.

**`nipopow_interlinks` — one whole-chain verdict, early-exit compare.**

```rust
let ok = clean && chain_arr.is_some() && actual_arr.is_some() && {
    let ca = chain_arr.unwrap();
    let aa = actual_arr.unwrap();
    ca.len() == aa.len()
        && ca.iter().zip(aa).all(|(c, a)| structural_equal(&c["interlinks"], a))
};
```

`clean` gates on `actual.error` being null/absent, same as every other tier. The length
check is folded into the same short-circuit `&&` — an outer-length mismatch never reaches
the per-height comparison. `.all(...)` is Rust's standard early-exiting combinator: it stops
comparing at the **first height whose interlinks diverge** — cheap, but this is a
*mechanism*, not a reported dimension. The verdict is still a single coarse
`"nice"`/`"interlinks"` per entry; **`grade_nipopow` never reports *which* height
diverged** — that granularity is not surfaced anywhere in the actuals/verdict shape, only
"at least one height disagreed." A conformer debugging a red interlinks entry must diff
`actual.interlinks` against `chain[].interlinks` itself to find the divergent height.

**`nipopow_prove` — byte-exact, case-insensitive raw hex compare.**

```rust
let ok = clean && expected_hex.eq_ignore_ascii_case(actual_hex) && !expected_hex.is_empty();
```

Unlike most of SANTA's comparator, which is JSON-*structural* (`runner-contract.md` §5:
key-order-insensitive, recursively equal), this is a **raw string compare** of the entire
`proofHex` value — appropriate because proof bytes are an opaque, single deterministic wire
encoding (§2), not a JSON value with multiple equally-valid representations. The
`!expected_hex.is_empty()` guard is a vacuous-match defense: no committed vector's
`proofHex` is ever empty (the vector schema's `hex` $def requires ≥1 byte), so this guard is
currently dead in practice against real vectors, but it means a runner cannot accidentally
score "nice" by returning an empty string against a malformed or missing expectation.

**Unknown `kind`.** A entry whose `kind` matches neither string falls to `_ =>
json!({"kind": "nipopow_prove", "proof": "proof"})` — an unconditional coal, never a silent
pass. Since the vector schema's `kind` enum only admits the two known values, this arm is
unreachable against any schema-valid committed vector; it exists for the same reason
authds's analogous fallback does (`runner-contract-authds.md` §4) — reachable only from a
hand-crafted actual against the grader directly (`nipopow_tests` exercises it in isolation).

**Tally — two independent counters, no chaining.** `nipopow_interlinks` and `nipopow_prove`
land on **different entries** (distinguished by `kind`), never the same entry's two facets,
so there is no suppression chain analogous to authds's `avl_verify` (`accepted → results →
digest`, `runner-contract-authds.md` §4). `conform`'s summary pools them as two independent
slice counters, printed `interlinks N/M · proof N/M`.

## 5. Provenance — the bless-time (`NipopowVectorGen`) recipe

**Fully synthetic, fully `authored`** — no vendored or captured input (§2, §7). Generated by
`jvm-blesser/src/test/scala-txbless/santa/NipopowVectorGen.scala`, under the same gate as
the tx/block/chain blessers (`SANTA_TX_BLESSER=1 sbt --batch 'testOnly
santa.NipopowVectorGenTest'`) — **not** authds's ungated main-scope path
(`runner-contract-authds.md` §7), since `NipopowVectorGen` lives under `scala-txbless/`
alongside the other gated generators.

**Chain construction.** `chain-fakepow.conf` — **not** `chain-testnet.conf` (the conf every
other JVM engine, e.g. `ChainEngine`, uses) — configures `powScheme.powType = "fake"`
(`DefaultFakePowScheme`) and `initialDifficultyHex = "01"` (a minimal target). This is
deliberate: with real Autolykos PoW, naturally-occurring high-level μ-superblocks would
require an astronomically long chain; the fake scheme plus a near-zero initial difficulty
lets a **32- or 64-header** synthetic chain still exercise multiple interlink levels — the
generator's own diagnostic output (`level=$lvl interlinks=${ph.interlinks.size}`) shows
levels climbing past 4-5 within the first ten headers on both corpus files. `epochLength =
1024` is far longer than either chain, so difficulty retargeting never triggers — irrelevant
to this tier.

**Genesis convention — read this before building a second nipopow runner.** Genesis is
mined with an **empty** extension (`ExtensionCandidate(Seq.empty)`, i.e. `emptyExt.digest`
as its real `extensionRoot`) — genesis has nothing preceding it to interlink to, so nothing
is committed on-chain. Both the generator and the runtime engine (§6) then apply the
**off-chain `PoPowHeader` modeling convention**: `genesisInterlinks = Seq(genesis.id)`. This
is the recursive base case `NipopowAlgos.updateInterlinks` needs
(`if prevHeader.isGenesis then Seq(prevHeader.id)`) — a convention for representing genesis
as a `PoPowHeader`, **not** a value ever encoded in genesis's own extension.

**The circularity**: a Merkle batch-proof of `packInterlinks([genesis.id])` checked against
genesis's *real* `header.extensionRoot` would **not** validate — the real root is the
empty-extension digest, a different value entirely. This is an accepted quirk of the
KMZ17/Ergo interlink bootstrap, not a SANTA defect, and it is a non-issue for this tier's
actual grading: `grade_nipopow` (§4) never reconstructs or checks a Merkle root against
`header.extensionRoot` — it only compares computed interlinks arrays and proof bytes against
the JVM-blessed values. It matters anyway, because it is the single most likely first-contact
divergence for anyone implementing `updateInterlinks` from the paper/protocol description
rather than matching this pinned convention: **`interlinks = [genesis.id]` is asserted, not
independently derivable, at height 1.**

For every non-genesis header (`i = 1..length-1`): `interlinks =
updateInterlinks(prev.header, prev.interlinks)`, packed via `NipopowAlgos.packInterlinks`,
and *that* packed form's digest becomes header `i`'s real, mined `extensionRoot` — so for
every header except genesis, the interlinks-vs-`extensionRoot` relationship **is** the real
on-chain-committed one, no circularity.

**Corpus shape.** Two chain lengths, `jvm-chain-32` and `jvm-chain-64`, both through the same
`generateChain` function. Per file: 1 `nipopow_interlinks` entry + 4 `nipopow_prove`
entries — three **tip** cases at `(m, k) ∈ {(2,2), (3,3), (6,5)}`, plus one **anchored**
case at `(m, k) = (2, 2)`. Total corpus: 2 files × 5 entries = **10 entries (2 interlinks +
8 prove)** — matches §9's live board.

**The truncation rule (anchored case), precisely.** Anchor height = `length / 2` (0-based
`midIdx = length/2 - 1`; height 16 for chain-32, height 32 for chain-64 — the
`anchored-h16`/`anchored-h32` entry-name suffixes). Given the anchor's `headerId`, let `idx`
be its 0-based position in `chain`. The chain fed to `prove` is:

```
chain.take(idx + k + 1)     // indices 0..idx+k inclusive — idx, plus exactly k more
```

This is what makes the result an **anchored, k-confirmed membership proof** rather than a
tip proof: `prove`'s own `k`-length suffix (`chain.takeRight(k)`) lands exactly on the
truncation boundary, so the anchor header ends up buried `k` blocks deep in the truncated
chain's own tip — structurally the same question as "is `headerId` buried `k` blocks deep in
the (real) chain," evaluated against a shorter, explicitly-truncated stand-in. Both the
bless-time generator (`NipopowVectorGen`) and the runtime engine (§6) compute this identical
truncation **independently** — different code, same formula, both bottoming out in the same
`NipopowAlgos.prove`/`updateInterlinks` — so they cannot structurally diverge from each other
the way two genuinely different implementations could (§6's honesty note).

**`continuous` is always `false`** (one-shot proof mode) — `PoPowParams`'s own doc comment:
"one-shot use means using the proof to just prove that a best chain contains some header,"
as opposed to continuous mode's extra difficulty-header bookkeeping for extending validation
past the proof. The tier never exercises `continuous: true` or
`NipopowProof.hasValidDifficultyHeaders`'s epoch-recalculation logic (§7).

**Reproducibility caveat**, carried forward from Task 1 of this tier's build: `ergo-core`'s
`DefaultFakePowScheme.prove` seeds its solution from `Random.nextLong()` ("fill solution
with random values," its own doc comment) — re-running the generator produces a
**different-but-equally-valid** chain and proof set every time; chain and proofs stay
mutually consistent within one run (both come from the same in-memory `chain` before
anything serializes), but the **committed JSON, not the generator, is the source of
truth** — nothing downstream should assume today's specific header ids survive a
regeneration. Pre-existing `ergo-core` behavior, not introduced by this tier's generator.

## 6. The JVM runtime recipe — rudolph's control arm (`NipopowEngine`)

`jvm-blesser/src/main/scala-tx/santa/runner/NipopowEngine.scala`, gated identically to the
generator (present only under `SANTA_TX_BLESSER`'s `ergo-core` composition; `Runner.scala`'s
`nipopowEntryFn` reflection seam — `Class.forName("santa.runner.NipopowEngine$")` — degrades
to a clean `not-implemented` on an ungated build, confirmed empirically: an ungated `sbt
clean compile` still succeeds with no static reference).

**Not the same function as the generator — a structural difference from authds.** Authds's
control arm calls the *literal same* `deriveFromEntry` function the vendored vectors were
blessed with (`runner-contract-authds.md` §7's "one shared decode path" — a control
divergence there can only mean the vector and the runner disagree about *decoding*).
Nipopow's rudolph arm does **not** share code with `NipopowVectorGen`: `NipopowEngine`'s
private `buildPoPowChain` independently re-derives the genesis convention, the
`updateInterlinks` walk, and (for `nipopow_prove`) the `headerId`-truncation search —
mirroring the generator's logic by hand, at runtime, rather than calling into it. Both call
sites bottom out in the same `ergo-core` library primitives (`NipopowAlgos.updateInterlinks`,
`.prove`, `NipopowProofSerializer`, `HeaderSerializer`), so this is **not** an independent
*reimplementation* of the protocol either — a bug shared by both hand-written call sites
(e.g. a wrong genesis convention) would show green on both sides. It sits between authds's
two ends: a red on rudolph means the two **independently-written chain-construction code
paths** disagree despite calling the same library, which is a real (if narrow) signal;
green is a slightly stronger claim than authds's pure decode-consistency ceiling, but still
not evidence of an *independent* second implementation (§7, §8).

**Two-arg reflection seam** — unlike every other gated engine (`chainEntryFn`,
`authdsEntryFn`, one arg), `nipopowEntryFn` is typed `(Vector[Json], Json) => (String,
Json)`: the file-level `chain` array has to be threaded in from the enclosing vector
document, since nipopow entries are chain-relative, not standalone (mirrors
`grade_nipopow`'s own three-argument shape, §4). `Runner.scala`'s `nipopowEntry(doc, e)`
extracts `doc.chain` and passes it alongside the entry on every call.

**`chain-fakepow.conf`, not `chain-testnet.conf`** — required because `NipopowAlgos.maxLevelOf`
calls `chainSettings.powScheme.powHit(header)`, and every vector header is fake-PoW-mined; a
real-Autolykos conf would silently compute wrong PoW levels (and thus wrong interlinks) for
these headers.

**Outcome mapping — no `errored`, only `panicked` or `not-implemented`.** The entire
per-entry dispatch (both kinds) runs inside one `try { … } catch { case NonFatal(t) =>
panicked }` block. Every failure mode — a missing `headerHex`, a `headerId` not found in the
chain, `NipopowAlgos.prove` returning a `Failure` (e.g. chain shorter than `m + k`), a
`chain-fakepow.conf` load failure — becomes **`panicked`** with `note` naming the exception
class and message. Unlike eval's `errored`/`panicked` split (`runner-contract.md` §3),
**this engine draws no distinction between "a recognized rejection" and "an internal
error"** — everything that is not a clean success is `panicked`. An unrecognized `kind`
string is the one exception: it returns `not-implemented` directly (a normal return inside
the `try`, never throws), so it is never caught as `panicked`.

## 7. Honest limitations

- **No verify/consumption kind in v1.** This tier grades production only — computing
  interlinks, constructing a proof — never a light client validating a *received* proof
  (`NipopowProof.isValid` and its four sub-checks, §1). A future `nipopow_verify` kind,
  mirroring authds's `avl_verify` (`runner-contract-authds.md` §2), is a natural extension,
  not yet built.
- **Fully synthetic corpus — no mainnet-anchored entries.** Contrast block/chain's
  mainnet-captured seeds. A mainnet-derived corpus would need real headers deep enough to
  have naturally-occurring high-level superblocks, which the synthetic fake-PoW chain
  manufactures cheaply instead (§5). Not attempted; would be a `captured`-provenance
  addition under the universal provenance taxonomy, not a v1 concern.
- **Thin conformer diversity at ship time (§8).** Only rudolph (JVM control) has a nipopow
  arm. **Rudolph's green here proves the two independently-written chain-construction code
  paths (generator and engine, §6) agree with each other — not that `ergo-core`'s
  `NipopowAlgos` is itself correct relative to some independent second implementation.** No
  cross-implementation evidence exists yet for this tier (§8).
- **`prove`'s own `Try` failures collapse to `panicked`, not a distinguished outcome**
  (§6) — the same shape of gap authds's `avl_prove` has (`AvlProofGenerator.generateCycles`
  also `.get`s a `Try` rather than classifying it, `runner-contract-authds.md` §8),
  independently arrived at on two different tiers' bless-time code.
- **The genesis-convention circularity (§5) must be replicated exactly, not re-derived.** A
  naive from-the-paper implementation of `updateInterlinks` has no reason to special-case
  genesis this way without reading this document — flagged prominently because it is the
  single most likely first-contact divergence for a second nipopow runner.
- **No cost dimension.** Unlike eval/transaction/block, nipopow's actuals carry no `cost`
  field at all (§3) — proof construction cost is not a graded consensus quantity here.
- **Proofs are non-continuous only** (§5) — `continuous: true` and its difficulty-header
  bookkeeping are entirely untested by this tier.

## 8. Conformer stances

Verified by reading `runners/*/runner.json` directly, not by manifest inference (matching
authds's convention, `runner-contract-authds.md` §6):

| Conformer | `updateInterlinks` + `prove` | Notes |
|---|---|---|
| rudolph (JVM) | yes | control; `NipopowAlgos` is the oracle (§6) |
| dasher (ergots) | pending | `runners/dasher/runner.json`'s `tiers` list does not include `"nipopow"` (confirmed directly — the file is materialized in this worktree) |
| blitzen-eni (sigma-rust fork) | pending | not yet mounted |
| donner (ergo-node-rust) | pending | not yet mounted |
| vixen (arkadianet/ergo) | pending | not yet mounted |
| blitzen-develop, comet | not applicable | comet is wire-only (no AVL/nipopow surface, same as authds §6); blitzen-develop tracks upstream `sigma-rust`, which has no nipopow surface either |

The last four rows' `runners/<name>/` directories are **git submodules not initialized in
this worktree** (empty on disk here) — their pending status is stated on the task brief's
authority and the project's standing roster knowledge, not independently re-verified from
this vantage point. This is a search-shape caveat worth naming explicitly: absence of a
submodule checkout here is not, by itself, proof of absence of a `nipopow` arm in that
runner's own canonical clone — only dasher's negative was directly confirmed against a
materialized file.

## 9. Status

**Live** as of 2026-08-19, rudolph control only. Corpus: **10 entries** across
`vectors/nipopow/any/authored/{NipopowProve.jvm-chain-32,NipopowProve.jvm-chain-64}.json` —
2 `nipopow_interlinks` (one per file) + 8 `nipopow_prove` (four per file: three tip cases at
`(m,k) ∈ {(2,2),(3,3),(6,5)}` plus one anchored `(2,2)` case) — `blessed_by:
"jvm:ergo-core-6.0.2.1-NipopowAlgos"`. `SANTA_TX_BLESSER=1 ./conform`, slice
`nipopow/any/authored`:

| Runner | interlinks | proof | red_total |
|---|---|---|---|
| rudolph (JVM control) | 2/2 | 8/8 | **0** |

No independent conformer is mounted yet (§8) — the live board is rudolph-only, and per §7's
honest limitation, this proves generator/engine self-consistency, not `NipopowAlgos`
correctness against a second implementation.

## 10. Worked example

```jsonc
// nipopow_interlinks entry (vectors/nipopow/any/authored/NipopowProve.jvm-chain-32.json)
// — the file-level chain (abbreviated: headerHex elided, only heights 1/2/3 shown of 32)
{
  "schema": "santa-nipopow/v1",
  "blessed_by": "jvm:ergo-core-6.0.2.1-NipopowAlgos",
  "chain": [
    { "height": 1, "headerHex": "…", "interlinks": [
        "a3d19b55ba1a730ae9d87a68c4df334864fa2ca614a43ad8aacbcf1a96bcd6fb" ] },
    { "height": 2, "headerHex": "…", "interlinks": [
        "a3d19b55ba1a730ae9d87a68c4df334864fa2ca614a43ad8aacbcf1a96bcd6fb" ] },
    { "height": 3, "headerHex": "…", "interlinks": [
        "a3d19b55ba1a730ae9d87a68c4df334864fa2ca614a43ad8aacbcf1a96bcd6fb",
        "1ffeef12021e64f833cedc4e29d691a23927a1ba96e6f678a8ec1c32786850e3",
        "1ffeef12021e64f833cedc4e29d691a23927a1ba96e6f678a8ec1c32786850e3",
        "1ffeef12021e64f833cedc4e29d691a23927a1ba96e6f678a8ec1c32786850e3" ] }
    /* … 29 more heights … */
  ],
  "entries": [
    { "name": "interlinks", "kind": "nipopow_interlinks",
      "source": "santa:authored:jvm-chain", "expected": {} },

    // tip case — headerId: null means "prove over the whole chain as given"
    { "name": "prove-m2-k2-tip", "kind": "nipopow_prove",
      "source": "santa:authored:NipopowAlgos.prove",
      "payload": { "m": 2, "k": 2, "headerId": null },
      "expected": { "proofHex": "0202098c039b02…" /* 5,705 bytes */ } },

    // anchored case — truncated at height 16, proving that specific header k=2-deep
    { "name": "prove-m2-k2-anchored-h16", "kind": "nipopow_prove",
      "source": "santa:authored:truncated-prove",
      "payload": { "m": 2, "k": 2,
        "headerId": "8155ad85f7da165ca5bf17644c904e00c7fe7deabe1da87501fc7f3238eda875" },
      "expected": { "proofHex": "…" /* 7,004 bytes */ } }
  ]
}

// a conforming runner's actuals for this file:
{
  "interlinks": {
    "interlinks": [
      ["a3d19b55ba1a730ae9d87a68c4df334864fa2ca614a43ad8aacbcf1a96bcd6fb"],
      ["a3d19b55ba1a730ae9d87a68c4df334864fa2ca614a43ad8aacbcf1a96bcd6fb"],
      ["a3d19b55ba1a730ae9d87a68c4df334864fa2ca614a43ad8aacbcf1a96bcd6fb",
       "1ffeef12021e64f833cedc4e29d691a23927a1ba96e6f678a8ec1c32786850e3",
       "1ffeef12021e64f833cedc4e29d691a23927a1ba96e6f678a8ec1c32786850e3",
       "1ffeef12021e64f833cedc4e29d691a23927a1ba96e6f678a8ec1c32786850e3"]
      /* … 29 more heights … */
    ],
    "error": null
  },
  "prove-m2-k2-tip": { "proofHex": "0202098c039b02…", "error": null },
  "prove-m2-k2-anchored-h16": { "proofHex": "…", "error": null }
}
// -> {"kind": "nipopow_interlinks", "interlinks": "nice"}
// -> {"kind": "nipopow_prove", "proof": "nice"}   (both prove entries)

// the divergence this arm exists to surface: a runner whose updateInterlinks disagrees
// with the JVM at even one height (e.g. a different max-level formula, or a genesis
// convention that doesn't match §5's [genesis.id] pin) reds "interlinks" on that whole
// entry, and — since prove() walks the SAME interlinks internally to build the proof
// prefix — very likely reds every "proof" entry in the same file too, since a wrong
// interlinks vector changes the prefix superblock selection and thus the serialized bytes.
```
