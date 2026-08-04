# Eval finding — REFUTED: the unbounded AVL verifier on the ErgoScript path is consensus, not a defect

**Tier:** eval (`AvlTree` script ops). **Raised:** 2026-08-04, inherited from the enr session's reply.
**Status: REFUTED 2026-08-04.** The claim was checked against the JVM and does not hold. No vector was
authored, no divergence exists, and the item is closed.

**Read this before re-opening "the Rust AVL verifier has no operation bound".** The observation that
prompted it is *true* and will be re-observed by anyone reading `savltree.rs`; the inference from it is
wrong. That asymmetry is exactly what makes a refutation worth committing.

## The claim as inherited

`ergotree-interpreter/src/eval/savltree.rs` constructs `BatchAVLVerifier` passing `None, None` for
`max_num_operations` / `max_deletes` — no bound on how much work an attacker-supplied proof can force —
and that path is reachable from a plain transaction. SANTA's four `adverse-*` fixtures only ever exercised
the block-proof surface, so this looked like an untested, unbounded, transaction-reachable surface: the one
open lead that could *find* a consensus bug rather than prevent one.

## Why it fails: the JVM is unbounded on the same path

`sigmastate/eval/CAvlTreeVerifier.scala:22` — the verifier every ErgoScript AVL op runs through — calls the
**four-argument** constructor:

```scala
extends BatchAVLVerifier[Digest32, Blake2b256.type](
  startingDigest, proof, keyLength, valueLengthOpt)
```

`maxNumOperations` and `maxDeletes` are constructor params **5 and 6**, and they are defaulted. Decompiled
from the shipped scrypto 3.0.0 artifact, both default bodies are a single `getstatic scala/None$.MODULE$`:

```
$lessinit$greater$default$5()  ->  scala.None$
$lessinit$greater$default$6()  ->  scala.None$
```

So the JVM passes `None, None` too. In `reconstructedTree` the entire bound-derivation block — the loop that
computes a maximum proof size from `maxNumOperations`, `maxDeletes` and `rootNodeHeight` — sits behind
`maxNumOperations.isDefined` (`ifeq`, jumping past all of it). On the script path it is dead code in the
reference implementation.

**sigma-rust's `None, None` is faithful. There is nothing to diverge from.** An implementation that *did*
impose a bound here would be the divergence — a wrong-reject, the same shape as the fork's 255-depth cap that
was rejected upstream.

## The second lever, also closed

If the verifier is unbounded, what actually bounds a malicious proof is the **cost model**, so the next
candidate was a disagreement over the cost inputs. Both are attacker-influenced:

| input | JVM (`CErgoTreeEvaluator`) | live eni (`savltree.rs`) |
|---|---|---|
| verifier construction | `CreateAvlVerifier_Info` × `proof.length` | `add_per_item_jit_cost(110, 20, 64, proof.len())` |
| each lookup / insert / update | `LookupAvlTree_Info` × `bv.treeHeight` | `add_per_item_jit_cost(40, 10, 1, tree_height(…))` |

`bv.treeHeight` looked like the lever: the JVM reads it from the verifier (reconstructed from the proof),
while eni derives it from the tree's digest byte. Those are **the same value**. In the scrypto bytecode,
`rootNodeHeight` is assigned `startingDigest.last & 0xff` *before* the proof is parsed — it is the digest
byte, by definition. eni also reproduces the failure ordering: the `require` on digest length runs first, so
a wrong-length digest leaves `rootNodeHeight` at its default `0`, which is precisely what eni's guard
(`digest.len() != 33 || key_length <= 0 => 0`) returns.

The eni implementation is a deliberate, line-cited port of the JVM cost model, down to charge-then-throw on
construction failure. It is not a surface with slack in it.

## What this does *not* close

- **`maxNumOperations` is live scrypto code for other callers.** It is dead on the *ErgoScript* path only.
  If any caller passes a bound it would be node-side block ADProofs verification — a different tier and a
  separate question, not a restatement of this one.
- **Whether an unbounded verifier is a good protocol design.** A digest can declare height `255` with a tiny
  proof, and both implementations then charge 255 items. That is a protocol question about Ergo, not a
  conformance question about implementations, and SANTA cannot answer it.
- **`vixen`'s `adverse-malicious-extra-nodes` over-accept.** Genuinely open, arkadianet's to fix, and
  unrelated to operation bounds.

## Turned up in passing — a real coverage gap

`sigmastate/interpreter/CErgoTreeEvaluator.scala:150` forks on tree version inside `insert_eval`: a failed
insert **throws** below ErgoTree v3 and **returns `None`** at v3 and later
([sigmastate-interpreter#908](https://github.com/ScorexFoundation/sigmastate-interpreter/issues/908)).
`vectors/eval/v6/authored/AvlTree.per_op_failure_v6.json` has eight entries and every one is
`ergoTree: 3` — only the returns-`None` arm. The throwing arm may be covered by a v5 file; if it is not,
that is a version-gated behavioural fork with no vector, and it is cheap to author.

## Reproducing

```bash
# JVM: the four-arg call
unzip -p tmp/sigma-state-6.0.3-sources.jar sigmastate/eval/CAvlTreeVerifier.scala

# scrypto: the defaults, and the isDefined gate around the bound derivation
javap -p -c -classpath ~/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scorexfoundation/\
scrypto_2.13/3.0.0/scrypto_2.13-3.0.0.jar scorex.crypto.authds.avltree.batch.BatchAVLVerifier
```

## A source-provenance warning

The inherited lead cited **seven** `BatchAVLVerifier::new` sites. That count is from `tmp/sigma-rust`
(`d59d8d9f`), which is **stale**: it predates the AVL costing work entirely and charges *nothing* for the
seven proof-consuming ops. The tree that is actually graded is the conform-staged
`.santa/blitzen-eni/sigma-rust` (`f76db922`), where those seven sites are refactored into **one**
`create_verifier` helper that charges the proof-length cost. Reading the stale tree yields a
spectacular-looking cost divergence that does not exist — eni is red 0 on `AvlTree.get`, which pins
`cost: 257`.

**For any costing question, read the conform-staged checkout, not `tmp/sigma-rust`.**
