# AuthDS finding — `UnknownModification` is a keyless singleton in scrypto, a keyed lookup everywhere else

**Tier:** authds (`santa-authds/v1`, `avl_verify`). **Surfaced:** 2026-08-03, the 50-fixture verify re-bless (Task 8).
**Status:** real, confirmed on both sides. Recorded, not reconciled — the committed vector carries the JVM answer.
**Blast radius:** 4 of the 50 vendored verifier fixtures; 17 pinned divergence keys, all one root cause.

## The divergence

`UnknownModification` is the eighth tag in the `santa-authds/v1` operation vocabulary. The two
implementation families do not agree on what it *is*:

| | scrypto 3.0.0 (JVM, canonical) | `ergo_avltree_rust` / ergots |
|---|---|---|
| shape | case **object** — a singleton, no fields | keyed variant: `{tag, key}` |
| key | fixed **zero-length** byte array; the caller's key never reaches it | the **caller's** key |
| applied to a tree | rejected: `IllegalArgumentException: requirement failed: Key  is less than -inf` | short-circuits exactly like `Lookup` |
| effect on the tree | none — the operation never runs | none — non-modifying by construction |
| result | `Failure` → `{ok: false, value: null}`, and the verifier is **poisoned** | old value if the key is present, `null` if absent; digest unchanged |

Both agree the operation must not modify the tree. They disagree about *which key it addresses* — and
therefore about whether it can run at all. scrypto's `updateFn` is in fact `oldValue => Success(oldValue)`,
semantically identical to `Lookup`; it is the fixed zero-length `key()` that makes the operation
unreachable, because a zero-length key sorts **below** the tree's negative-infinity sentinel.

## Root cause (source)

scrypto — `scorex.crypto.authds.avltree.batch.UnknownModification`, verified against
`scrypto_2.12-3.0.0.jar`:

```
public final class scorex.crypto.authds.avltree.batch.UnknownModification$
    implements Modification, Product, Serializable {
  public static UnknownModification$ MODULE$;
  public byte[] key();                                             // zero-length
  public Function1<Option<byte[]>, Try<Option<byte[]>>> updateFn(); // oldValue => Success(oldValue)
}
```

`MODULE$` is the giveaway: there is one instance for the whole JVM, so there is nowhere to put a key.
`AuthenticatedTreeOps`'s `require(key > NegativeInfinityKey)` then rejects it on any tree, empty or
seeded. Pinned in `AvlProofGeneratorTest` ("UnknownModification has a FIXED zero-length key…") and
`VendoredAuthdsAvlVerifyTest` ("…is a captured Failure on the verify side, not an escape").

ergots — `packages/avltree/src/operation.ts:13`:

```ts
| { tag: 'UnknownModification'; key: Uint8Array }
```

with `modify.ts:189` / `:272` short-circuiting `Lookup` **and** `UnknownModification` together, citing the
Rust reference lines it was ported from. The Rust-blessed fixture expectations agree, so this is
`ergo_avltree_rust`'s model, not an ergots reading of it.

## Where the throw lands — prove vs verify

The same rejection surfaces differently on the two arms, which is why the tag is blessable here and not
on the prove side:

- **prove** — `BatchAVLProver.performOneOperation(...).get` in `generateCycles`; the exception propagates
  and kills the run. No vendored prover fixture uses the tag, and none can.
- **verify** — `BatchAVLVerifier.performOneOperation` returns `Try`, so the rejection is **captured**.
  The entry records `{ok: false, value: null}` and the verifier is poisoned for the rest of the batch.
  Confirmed empirically, not inferred: the test constructs the operation (a case object — construction
  cannot throw), checks the verifier anchored first, and asserts `attempt.isFailure` with the
  `is less than -inf` message.

## Blast radius in the vendored corpus

Four fixtures, 17 pinned keys, one cause:

| Fixture | UnknownModification at | Divergent |
|---|---|---|
| `unknown-mod-3leaves-absent` | op 0 | digest (rust: tree unchanged; jvm: poisoned) |
| `unknown-mod-3leaves-present` | op 0 | digest + `results[0]` (rust returned the leaf value `0202…`) |
| `batch-16ops-mixed` | op 14 | digest + `results[14]` + `results[15]` — 15 is a `Lookup` that fails only because 14 poisoned the verifier |
| `batch-stress-mixed-100` | ops 90–99 | digest + `results[90..99]` |

The other 46 fixtures agree with the JVM (38 exact; 8 consistent-with-enrichment, where the fixture's
single coarse `null` is compatible with the finer three-level answer).

## Disposition

- The committed vector `vectors/authds/any/vendored/AvlVerify.ergots_corpus.json` carries the **JVM**
  outcome for all four, per "vendor the inputs, re-bless every expectation". No fixture was excluded,
  reshaped, or held.
- `VendoredAuthdsAvlVerifyTest.KnownDivergences` pins the 17 keys as an **exact set**: a new divergence
  fails, and a pinned one that stops reproducing fails too. A second guard asserts every pinned fixture
  actually contains an `UnknownModification` operation, so the pin cannot quietly absorb an unrelated
  finding.
- **Expect a real red on dasher (ergots) at Task 10** on these four entries. It is this divergence, not
  an adapter bug — do not "fix" the adapter to make it green. The question it raises is a spec question:
  which model of `UnknownModification` is canonical for consensus. scrypto is the oracle, so the vector
  says scrypto; whether `ergo_avltree_rust` should change is an upstream conversation, and the tag is not
  reachable from ErgoScript's `AvlTree` surface either way.
