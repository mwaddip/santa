# Finding: spending degraded and empty-conjecture boxes, an output's bytes, substConstants' size (ergots' sized-tree requests)

**Tiers:** transaction (`santa-transaction/v1`), eval (`santa-eval/v2`)  
**Surfaced:** 2026-09-28, the transaction and eval part of ergots' sized-tree requests. The wire part is
`wire-sized-tree-requests.md`.  
**Vectors:**
- `vectors/transaction/v6/authored/sized-tree-spend.json` (10 entries);
- `vectors/transaction/v6/authored/sized-tree-output-bytes.json` (5 entries);
- `vectors/eval/v6/authored/substConstants_declared_size_u32.json` (3 entries).

Blessers: `AuthoredTxSizedTreeRequests`, which reuses the storage-rent vectors' synthetic context (H = 1051200, the
launch parameters), and `AuthoredEvalSizedTreeRequests`. JVM: ergo-core / sigmastate 6.0.6.

## 1. Spend verdicts (`sized-tree-spend`)

A spend evaluates the box's tree as it was parsed at ingest.

**The `If` rows.**
- A size-flagged tree that degraded because its root is not a SigmaProp (rule 1001, not a soft fork) throws during
  interpretation.
- The JVM builds `If` without comparing its branch types, and types it as its true branch.

**The conjecture rows.**
- A leafless `CAND()` or `CTHRESHOLD(0, [])` is spendable with a 24-byte proof: its Fiat-Shamir challenge over the
  transaction's message, which needs no secret. The JVM's own prover makes it.
- With an empty proof, none of the conjectures spends. A trivial child does not make a conjecture constant trivial.

| # | Spent tree | Proof | JVM |
|---|---|---|---|
| 0 | sized `If(false, Int 1, sigmaProp(true))` (rule 1001 degrades it) | none | **invalid** |
| 1 | sized `If(true, sigmaProp(true), Int 1)` | none | valid |
| 2 | sized `If(false, sigmaProp(true), Int 1)`: parses, evaluates to an Int | none | **invalid** |
| 3 | `CAND()` | its 24-byte challenge | **valid** |
| 4 | `CAND()` | none | invalid |
| 5 | `CTHRESHOLD(0, [])` | its 24-byte challenge | **valid** |
| 6 | `CTHRESHOLD(0, [])` | none | invalid |
| 7 | `COR()` | none | invalid |
| 8 | `CAND([TrueProp])` | none | invalid |
| 9 | `CTHRESHOLD(0, [TrueProp])` | none | invalid |

- **How it discriminates.**
  - A lenient parser evaluates #0 to `sigmaProp(true)` and accepts it.
  - An impl that normalizes `CAND()` to `TrueProp` accepts #4 and may reject #3.
- **`COR()`.** The JVM's prover cannot prove it ("Tree root should be real"). Whether some proof verifies it is not
  pinned here.

## 2. An output's bytes (`sized-tree-output-bytes`)

The transaction creates output 0 with a tree whose declared size is wrong: 3 or 1 for the 2-byte body `08 d3`. Inside
that transaction:
- `OUTPUTS(0).propositionBytes` is the tree **as received**: `ErgoTree.bytes`, the parser's span.
- `OUTPUTS(0).bytes` is **re-encoded** with the true size. The output box is built from its candidate
  (`ErgoBoxCandidate.toBox`) and has no parsed bytes, so `ErgoBox.bytes` falls back to the serializer. `OUTPUTS(0).id`
  hashes those bytes.
- The transaction's signing message (`bytesToSign`) is re-encoded too.

The spent box's script is:

```
proveDlog(pk) && OUTPUTS(0).propositionBytes == P && OUTPUTS(0).bytes.slice(3, 7) == S
              && OUTPUTS(0).id == blake2b256(OUTPUTS(0).bytes)
```

The input carries a Schnorr proof for `pk` over the JVM's message. It is built deterministically (fixed secret and
nonce, Fiat-Shamir as the JVM prover does it), so a re-bless is byte-identical. That proof pins the signing message.

| # | Output 0's tree | P | S | JVM |
|---|---|---|---|---|
| 0 | `08 03 08 d3` (over) | `08 03 08 d3` | `08 02 08 d3` | valid |
| 1 | `08 01 08 d3` (under) | `08 01 08 d3` | `08 02 08 d3` | valid |
| 2 | `08 02 08 d3` (true) | `08 02 08 d3` | `08 02 08 d3` | valid |
| 3 | `08 03 08 d3` | `08 02 08 d3` (expects re-encoded) | `08 02 08 d3` | **invalid** |
| 4 | `08 03 08 d3` | `08 03 08 d3` | `08 03 08 d3` (expects as received) | **invalid** |

- **The reject twins** carry the same valid signature. An impl with the other basis reduces the script to `pk` and
  accepts.
- **The tx id is not compared in-script.** The output's bytes contain the tx id, which in turn depends on the spent box
  and so on the script: a circle. The signature covers the tx id's preimage instead.

## 3. substConstants' declared size (`substConstants_declared_size_u32`)

`substConstants` reads the template's header and size through `deserializeHeaderAndSize`. The size is a `getUInt`, so
the u32 bound applies, and otherwise it is ignored. The result is written with the true size.

The tree is the spec vector's `substConstants` function (`Fix_substConstants_in_v6.0_for_ErgoTree_version_0`). Each
template is size-flagged and segregated, with `SigmaProp(true)` as constant 0, and is replaced by `SigmaProp(false)`:

| # | Declared size | JVM |
|---|---|---|
| 0 | 2^32 (`80 80 80 80 10`) | **error**: above u32 |
| 1 | 2^32 − 1 (`ff ff ff ff 0f`) | `18 05 01 08 d2 73 00`, the size recomputed |
| 2 | 5, the true size (control) | `18 05 01 08 d2 73 00` |

## Grades

Default pins. Board: eni 105 → 113, develop 352 → 363, dasher 84 → 96. The existing corpus is unchanged on every
runner (only the new files hold new reds).

| File | blitzen-eni `0bd7199f` | blitzen-develop `1633e018` | dasher (ergots `f2f4a94c`) |
|---|---|---|---|
| sized-tree-spend | **errors on #3–#7**: the `CAND()`/`COR()`/`CTHRESHOLD(0, [])` boxes don't parse | **errors on #3–#7**; **panics on #2** (the mismatched `If`) | **accepts #0** (the degraded `If`); **errors on #3–#7 and #9** |
| sized-tree-output-bytes | **rejects #0, #1** and **accepts #3**: its `propositionBytes` is re-encoded | **errors on #0, #3, #4**; **rejects #1** | **errors on #0, #1, #3, #4** |
| substConstants_declared_size_u32 | green | **rejects #1** (declared 2^32 − 1) | **accepts #0** (declared 2^32) |
