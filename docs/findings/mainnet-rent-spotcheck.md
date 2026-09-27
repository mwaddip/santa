# Mainnet storage-rent spot-check (2026-09-27)

A real-history gate for the storage-rent ports. It runs chain-valid mainnet rent spends, blessed by
the 6.0.6 JVM oracle, against the conformers. What it guards against is a port that **rejects** a
spend the chain accepted, since that would stall a node.

## Method

1. **Scan.** Every mainnet block from 1,051,200 (`StoragePeriod`) to 1,882,402 (831,203 blocks)
   was read through a mainnet node's REST API (`/blocks/at/{h}`, `/blocks/{id}/transactions`). An
   input is *rent-shaped* when its spending proof is empty and its extension holds variable 127.
   - **135,756** txs carry at least one rent-shaped input.
   - **978,026** rent-shaped inputs in all, every one a distinct box.
2. **Resolve.** Each spent box was rebuilt to exact bytes from its creating output, found through
   the indexer's box → (block, tx, index) map. Each rebuild was accepted only if
   `blake2b256(bytes) == boxId`.
   - All 978,026 resolved, with 0 failures.
3. **Classify.** Each input was classified with the JVM's semantics (ergo v6.0.6
   `ErgoInterpreter.verify` / `checkExpiredBox`):
   - the age check, in signed `Int`
   - the empty proof
   - var 127 being a `Short` that indexes an output
   - the `Int * Int` fee, which wraps
   - dust at `value − fee <= 0`
   - full recreation: height, value, R1, R2 and R4..R9
4. **Sample and capture.** A stratified sample of whole transactions was captured with the real
   context at each height:
   - the 10 parent headers, newest first
   - the block's preHeader
   - the voting-epoch parameters, read from the epoch-start block's extension

   Every byte string is checked against its id:
   - tx signing message → tx id
   - box bytes → box id
   - header bytes → header id
5. **Bless.** `CapturedTxMainnetRent` (`jvm-blesser/src/test/scala-txbless/`, input
   `src/test/resources/mainnet-rent/captures.json`) blesses the sample on 6.0.6.
   - It fails loud if the oracle rejects any capture: every one is chain history.
   - It also re-derives from the bytes which inputs take the rent path, and requires that to
     match the classification.

**Anomalies: 0.** Every one of the 978,026 rent-path inputs passes its recreation check under JVM
semantics, as chain validity requires. So the classifier and the chain agree everywhere.

## Population and sample

Heights 1,051,200 → 1,628,159 are block version 3 (`v5`, `activated` 2). Heights from 1,628,160,
mainnet's 6.0 activation, are block version 4 (`v6`, `activated` 3).

| Stratum | Definition | Population (v5 / v6) | In the sample |
|---|---|---|---|
| S1 | dust: rent path, `value − fee <= 0` | 191,132 inputs (39,183 / 151,949) | 103 txs / 198 inputs |
| S2 | recreation of a plain P2PK box: no tokens, no R4–R9 | 776,466 inputs (113,555 / 662,911) | 19 txs / 331 inputs |
| S3 | recreation with tokens (R2) | 5,681 inputs (1,399 / 4,282) | 14 txs / 78 inputs |
| S4 | recreation with any R4–R9 | 1,149 inputs (923 / 226) | 12 txs / 104 inputs |
| S5 | rent box ≥ 1718 bytes (fee-wrap territory) | **66 inputs in 45 txs** (all v6) | **all 45 txs** |
| S6 | tx with ≥ 2 rent-path inputs | 65,926 txs (15,174 / 50,752) | 39 txs |
| S7 | tx with rent-path input(s) + an ordinary (script-path) input | 25,287 txs (2,234 / 23,053) | 59 txs |
| S8 | empty proof + var 127, but the script path | **48 inputs in 48 txs** (22 / 26) | **all 48 txs** |
| (other) | recreation of a plain non-P2PK box | 4,341 inputs (2,089 / 2,252) | 1 tx |

Notes on the table:
- **S5** consists of 65 dust boxes and **1 recreation**. The recreation is a 3,447-byte box at
  height 1,764,577. Under the true `u64` fee it would be dust. Under the JVM's wrapped fee (13.78M
  nanoERG) it must be, and is, recreated.
- **S8** is all one case: every one is a young contract box whose age check fails, so the script
  path decides.
- **Totals:** 122 txs in 15 files (`transaction/{v5,v6}/captured/mainnet-rent-<stratum>.json`, one
  file per primary stratum). 37 are from the v5 era and 85 from v6, spanning 7 distinct parameter
  tables. They contain 702 inputs, 643 of them on the rent path, at heights 1,051,312 – 1,829,331.
- **Sample height cap.** The sample stops at height 1,799,077, the indexer's tip. Every rent box is
  older than that, but a tx's ordinary inputs may not be.

## Grades (2026-09-27)

| Runner @ SHA | Result on the 122 |
|---|---|
| rudolph | 122/122 (valid + cost) |
| **blitzen-eni `bf4d6943`** (the fix) | **122/122 valid + cost — gate passed** |
| blitzen-eni `b438d520` (before the fix) | 122/122 valid; every entry's cost exactly 50 × its rent-path inputs short (643 inputs) |
| blitzen-develop `1633e018` | 122/122 valid (no cost dimension) |
| dasher (ergots `f2a9c4b`) | 122/122 valid (reports no tx cost) |
