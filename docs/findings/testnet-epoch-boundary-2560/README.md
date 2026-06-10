# testnet epoch-boundary 2560 — capture material (not a divergence seed)

Full capture of testnet block **2560** (height % 128 == 0, the bigint family's
epoch boundary; previous boundary 2432), produced on request as the
**version-gate re-donor**: an epoch-boundary donor lets the `version-gate`
mutation (`params.table["123"]` shrink) fire the real `exBlockVersion` gate —
which both the SANTA engine and enr enforce at boundaries only, per the
JVM (`processExtension` gated on `epochStarts`, `ErgoStateContext.scala:246`).

- `block-2560-full.json` — node-API block + regenerated canonical proofBytes
  (enr `ENR_DUMP_ADPROOFS_AT` state-aside replay against a store copy;
  verified `blake2b256(proofBytes) == header.adProofsRoot = d8117b7bd4a9a400…`,
  1683 proof bytes). 1 transaction, header.version 4, 26 extension fields
  including the boundary's packed parameters.
- `headers-2550-2559.json` — the ≤10-window, ascending (seed-dir convention).
- `box-66aea4b3146d144a-bytes.json` — the single external input box
  (id-self-verifying raw bytes, indexer-served).
- `epoch-block-2432.json` — the previous boundary block (in-force params
  entering 2560).

Produced by ergo-node-rust main `f2f4f89` machinery, 2026-06-10.
