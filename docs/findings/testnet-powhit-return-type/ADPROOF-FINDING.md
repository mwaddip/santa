# ADProof finding: rust prover non-canonical for data-input lookups (h=28474)

The four block-tier seed proofs were regenerated locally from the rust node's
UTXO state (genesis replay, capturing the prover's apply-time `generate_proof`),
because no reachable testnet peer keeps ADProofs (all UTXO mode).

Three verified **byte-identical** to consensus — `blake2b256(proofBytes) ==
header.adProofsRoot`: 2666, 111927, 184137. Those shipped as `block-<h>-full.json`.

**28474 did not.** The rust-generated proof *verifies* (replays to the correct
state digest) but is **not byte-identical** to the committed proof:

```
blake2b256(rust proofBytes) = f29871d08dfdfd627420d3b9c879135e45f279849b47a32a4224320307f6e017
header.adProofsRoot         = 4069af94a4813a4b9f1c605b5700954c2345d86ee6da747c9dee58948092931d
```

Deterministic (a flush-suppressed re-dump produced the identical bytes), so not a
flush/timing artifact.

## Root cause (characterized, not yet fixed)

The discriminator is the **data input**:

| h      | dataInputs | proof          |
|--------|-----------:|----------------|
| 2666   | 0          | canonical ✓    |
| 28474  | **1**      | non-canonical ✗|
| 111927 | 0          | canonical ✓    |
| 184137 | 0          | canonical ✓    |

A data input becomes a `Lookup` operation in the batch. The rust AVL prover
(`ergo_avltree_rust` fork) serializes the lookup's authentication path into the
batch proof **differently from the JVM `avldb`** — same operations, same
resulting digest, different proof bytes. Blocks without lookups serialize
identically; the one with a lookup diverges.

The rust **verifier** is unaffected — block 28474 validated normally during sync
against the canonical proof. Only the **prover** (proof generation) diverges.

## Impact

1. The local generate path cannot produce 28474's canonical vector; it needs a
   JVM source (a JVM UTXO node's `generate_proof`), or a fork fix.
2. Latent serve-side consensus bug in the rust node: once it serves ADProofs to
   digest peers (Phase 6), peers would reject lookup-containing blocks on the
   `blake2b256(proof) == adProofsRoot` check. Digest *validation* is fine.

## Artifact

`adproofs-28474-rust-noncanonical.104` — the rust-generated type-104 section
(`[header_id:32][size:VLQ][proof]`), kept for the rust-vs-JVM proof diff. Do NOT
treat it as the canonical block proof.
