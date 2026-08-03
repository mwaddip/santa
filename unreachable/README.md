# `unreachable/` — vectors retired because no consensus path can reach them

Test material that is **correct but ungradeable**: it exercises code that exists in
a reference implementation yet cannot be constructed from any consensus path. Such
material cannot produce a conformance divergence, because the behaviour it compares
is never observable on-chain.

This directory is **outside `vectors/`**, so neither `conform` (which discovers
`vectors/<tier>/<version>/<provenance>/`) nor `validate` (which walks the same root)
sees it. Nothing here is staged, graded, or schema-checked.

## Why keep it rather than delete it

Dead code can be revived. If a future protocol version wires one of these paths up,
the material — and the JVM blessing behind it — is worth more than the effort of
re-deriving it. Retiring is reversible; deleting is not.

## The bar for putting something here

**Unreachability must be demonstrated, not asserted.** A vector that is merely
inconvenient, or that only one implementation fails, does not belong here — removing
those would be neutralising a divergence, which is precisely what a conformance suite
exists to prevent. Every retirement records the evidence below.

The distinction that matters: a red cell means *implementations disagree about
something observable*. If the behaviour cannot be observed by any consensus path, the
disagreement is about an API artifact, not about consensus.

## Reactivating

The committed vectors are **generated** from these seeds, not hand-maintained. To
bring material back:

1. Move the seed fixtures back under the owning blesser's resource directory.
2. Re-bless (`SANTA_TX_BLESSER=1 sbt test` in `jvm-blesser/`) — the vector file is
   regenerated from the seeds.
3. Restore the corresponding count/name assertions in the blesser's guard test.

Never hand-edit a generated vector back into existence: the drift guards exist to
make exactly that fail loudly.

---

## `authds/` — three AVL operations unreachable from ErgoScript (retired 2026-08-04)

**13 of the 50 vendored `avl_verify` fixtures**, retired because they exercise
`scorex.crypto.authds.avltree.batch` operations that no consensus path constructs.

**Evidence.** Fully-qualified class references to
`scorex/crypto/authds/avltree/batch/<Op>`, counted across the shipped JVM artifacts:

| Operation | sigma-state 6.0.3 | verdict |
|---|---|---|
| `Insert` · `Update` · `Remove` · `Lookup` · `InsertOrUpdate` | 3 · 2 · 2 · 2 · 2 | reachable |
| **`RemoveIfExists`** | **0** | unreachable |
| **`UpdateLongBy`** | **0** | unreachable |
| **`UnknownModification`** | **0** | unreachable |

Corroborating: `ergo-core` shows 0 references to all three, and its own state-change
path (`ergo-core/.../modifiers/state/StateChanges.scala`) imports only
`{Insert, Lookup, Operation, Remove}`. Within `scrypto` itself the sole references to
`UnknownModification` are its own two class files — nothing constructs it, no
deserializer yields it, nothing dispatches on it.

A zero here is trustworthy in a way the nonzero counts are not: prefix matching can
only *inflate* a count (a search for `…/Update` also matches `…/UpdateLongBy`), never
reduce one to zero. An earlier bare-substring grep put `Update` at 24 — that figure
was noise, and is why the check was redone against fully-qualified names.

**What this retires.** 11 single-operation fixtures whose entire content is a dead
operation, plus 2 mixed batches:

- `unknown-mod-3leaves-{absent,present}`
- `remove-if-exists-3leaves-{absent-noop,present}` · `single-leaf-tree-remove-if-exists`
- `update-long-by-{i64-max-boundary,negative-absent-fail,negative-delta-remove,negative-result-fail,positive-no-remove,to-zero-remove}`
- `batch-16ops-mixed` (dead op at index 10 of 16) · `batch-stress-mixed-100` (index 50 of 100)

**Known cost, stated so it is not rediscovered as a gap.** The two mixed batches carry
a reachable *prefix* — 10 and 50 real operations — that goes with them. Their
reachable portions could be recovered as truncated, re-blessed twins; that was
considered and deferred rather than overlooked. The surviving corpus keeps
`batch-256ops-inserts` (256 ops) and the small mixed batches
(`batch-2ops-insert-then-{update,remove,lookup}`, `all-deletes-from-balanced-10`), so
what is lost is large-batch *operation-mixing* coverage specifically, not batch
coverage generally.

**What this resolves.** Every one of the 17 JVM-vs-Rust divergences the blesser's
`KnownDivergences` list pinned had a single root cause: `UnknownModification`'s
zero-length key. With these fixtures retired, the JVM and `ergo_avltree_rust` agree on
the whole reachable corpus. The finding that documented the disagreement is retained
and reframed — see `docs/findings/authds-unknownmodification-jvm-vs-rust.md` — because
"we checked, and it cannot be reached" is a result worth keeping.
