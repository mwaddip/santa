# Finding — sigma-rust's fixed `[Header; 10]` context window vs the JVM's variable `Coll[Header]`

**Surfaced by:** SANTA `CONTEXT.headers#dummy` (the lone eni red @ `de6331cb`, in
`vectors/eval/v5/authored/Context.properties.json`). **Status:** real, narrow consensus
divergence; routed to sigma-rust (`prompts/sigma-rust-fixed-header-window.md`) +
ergo-node-rust (queued). **Date:** 2026-06-07.

## The divergence

The JVM reference models `ErgoLikeContext.headers` as a variable-length `Coll[Header]`
(`ErgoLikeContext.scala:49`) that explicitly supports the empty case
(`require(headers.isEmpty || ...)`, `:85`) and enforces real parent-chain linkage
(`require(headers(i-1).parentId == headers(i).id, "Incorrect chain")`, `:87`). sigma-rust
models it as a fixed `headers: [Header; 10]` — through both the interpreter `Context`
(`ergotree-ir/src/chain/context.rs:40`) and ergo-lib's `ErgoStateContext`
(`type Headers = [Header; 10]`). A fixed array cannot represent fewer than 10 headers.

SANTA's canonical eval context pins `headers = empty` (the honest value for a contextless
synthetic eval), which the JVM expresses and sigma-rust structurally cannot — the eni
adapter must hand the interpreter 10 headers. That is the SANTA red.

## Why it's an on-chain divergence, not a harness artifact

The JVM gathers the context window via `headerChainBack(10, fullBlock.header, h => h.height == 1)`
(`FullBlockProcessor.scala:71`) — it walks back up to 10 but **stops at height 1**, so at
block height `h ≤ 10` the script sees `h-1` headers, not 10. Empty is just the extreme
(genesis). So the real-chain analogue of SANTA's empty pin is the **first ~10 blocks of any
chain**, where the JVM's `CONTEXT.headers` is genuinely shorter than 10.

ergo-node-rust bridges sigma-rust's fixed type by **padding** — `build_headers_array`
(`validation/src/tx_validation.rs:39-45`, introduced in node commit `3a726ffc`, 2026-04-04)
does `take(10)` newest-first then duplicates the **oldest** header to fill the tail. The
duplicated tail is a window the JVM's own line-87 `require` would reject as an invalid chain
(a duplicate's `parentId != id`). So in the genesis window a script reading the header set
diverges:

- `CONTEXT.headers.size` → JVM `<10`, rust always `10`.
- `CONTEXT.headers(i)` for `i ≥ real count` → JVM out-of-bounds error, rust returns the
  duplicated-oldest header.
- whole-collection use (HOF / equality / serialize) → length-sensitive divergence.

Because the padding is tail-only (`headers[0]` is always the real newest), reads of
`headers(0)` and `CONTEXT.LastBlockUtxoRootHash` (derived from `headers[0].state_root`,
`scontext.rs:91`) do **not** diverge in-window.

## Reachability / impact

- **Trigger is specific:** a block at height 2–10 whose script reads the headers *length*
  or an *out-of-real-range index* (or the collection wholesale). Index-0 / preHeader /
  LastBlockUtxoRootHash reads are unaffected. The header array is otherwise inert —
  `ErgoStateContext::new` validates nothing, the context isn't serialized (only
  `ContextExtension` is `SigmaSerializable`), consensus doesn't commit to it, and the
  interpreter reads it op-only (3 sites). No header-reading script ⇒ identical validation.
- **Practical exposure is testnet.** Mainnet's first 10 blocks (2019) are finalized and the
  rust node fast-syncs from UTXO snapshots rather than re-validating from genesis. Testnet
  *is* periodically reset to genesis and is more likely to carry scripts in its early
  blocks — and the node ships a `revalidate = true` from-genesis path (same commit
  `3a726ffc`). That path is the live exposure.
- It is a **silent value fork** in-window, not a halt (the node pads rather than erroring) —
  the worse failure mode for the affected blocks.

## Provenance note

The node's pad-the-oldest workaround is undocumented as a decision: `git blame` puts it in
the original Phase-4b validation commit `3a726ffc` with a matter-of-fact doc comment
("Pads with the oldest available if fewer than 10 are provided") and no rationale; the node
session's memory carries nothing on the header window. It was written to satisfy
sigma-rust's type, never connected to the genesis-window consensus question — consistent
with it staying latent until this red surfaced it.

## Fix shape (sigma-rust)

Make `headers` variable-length (`BoundedVec<Header, 0, 10>` — sigma-rust already uses
`BoundedVec` for `TxIoVec`). Blast radius is small: the type lives in 2 core sites
(`context.rs:40`, the `Headers` alias); production reads flow through `CONTEXT.headers` →
Coll → `ByIndex` (bounds-checked, JVM-faithful for free), with the only direct index being
`headers[0]` for `LastBlockUtxoRootHash`. **Not serialized ⇒ no wire/storage migration.**

Crucially it's an **API relaxation, not a break**: the wasm surface already takes a
variable-length `BlockHeaders` list and enforces 10 via a runtime `if headers.len() == 10`
check (`bindings/ergo-lib-wasm/src/block_header.rs:127`) — relaxing that to `<= 10` leaves
every existing caller working (they pass 10 fetched off a node) and only *adds* the ability
to pass fewer. No wallet/dApp consumer relies on the fixed-10 behaviorally; the only code
that exercises the `<10` regime is a from-genesis validator (the node).

Full parity for the *truly empty* case (height 1 / the synthetic context) additionally needs
a standalone `last_block_utxo_root` context field (the JVM has one; sigma-rust folded it into
`headers[0]`) — but height 1 is node-guarded (no standard txs) and out of scope for the
real genesis-window fix.

## Status — FIXED on fork-eni (2026-06-07)

Shipped as routed, plus the "out of scope" tail: eni `de6331cb → 7834d2f9` lands
`Context.headers: BoundedVec<Header, 0, 10>` (`7834d2f9`) **and** the standalone
`Context.last_block_utxo_root: AvlTreeData` (`d0497722`) — the latter turned out
load-bearing because the corpus pins `CONTEXT.headers#dummy` (empty) and
`CONTEXT.LastBlockUtxoRootHash#dummy` (dummy tree) against the same canonical
context; only the JVM's two-field shape satisfies the pair. The prior green on the
root vector was accidental (the test template's Arbitrary hardcodes a zero
state-root). SDK `ErgoStateContext.headers` = `BoundedVec<Header, 1, 10>`;
wasm/python/C constructors relax `== 10` to `1..=10`.

SANTA re-grade @ eni `7834d2f9` (santa `4828b37`, blitzen adapter `d5f2a2e`):
`CONTEXT.headers#dummy` + `CONTEXT.LastBlockUtxoRootHash#dummy` both green
(honestly), zero regression across eval/wire/tx. The develop PR opens on this
green per the sigma-rust session's hold. Downstream: the ergo-node-rust
pad-the-oldest workaround (`build_headers_array`, node `3a726ffc`) can now be
dropped — routed separately.
