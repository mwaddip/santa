# Finding: `maxLevelOf` log2 divergence (sigma-rust vs JVM)

**Tier:** nipopow (`santa-nipopow/v1`)  
**Surfaced:** 2026-08-19, first blitzen-eni conform run  
**Status:** OPEN — sigma-rust diverges from JVM  
**Conformers affected:** any sigma-rust consumer (blitzen-eni, blitzen-develop, donner/enr)  
**Vector:** `NipopowProve.jvm-chain-32.json`, first divergence at height 23  

## The divergence

sigma-rust's `NipopowAlgos::max_level_of` computes a different level than the
JVM's `NipopowAlgos.maxLevelOf` when the `powHit` value is an exact power of 2.

At height 22 of the synthetic chain (where `powHit = q / 32 = q / 2^5`):

| Impl | `log2(powHit)` | `level` | `level.toInt` / `as i32` |
|---|---|---|---|
| JVM | `log(2^251) / log(2)` = **251.00000000000003** | 4.999999999999972 | **4** |
| sigma-rust | `f64::log2(2^251)` = **251.0** (exact) | 5.0 | **5** |

One level off → `update_interlinks` produces a 6-element vector (sigma-rust)
vs 5 (JVM) at the level-4→5 transition → interlinks diverge from height 23
onward → every proof that includes this header diverges.

## Root cause

The JVM's `NipopowAlgos.log2` is:

```scala
private def log2(x: Double): Double = math.log(x) / math.log(2)
```

This computes `ln(x) / ln(2)` using the natural logarithm. IEEE 754 double
arithmetic means `ln(2^N) / ln(2)` is NOT guaranteed to return exactly `N` —
the intermediate `ln(2^N)` result carries rounding error that the division
does not cancel. For `N = 251`, the JVM returns `251.00000000000003`.

sigma-rust uses Rust's dedicated `f64::log2()`, which is implemented as a
single hardware/libm operation and returns exactly `251.0` for `2^251`. More
precise, but not JVM-compatible.

## Why it doesn't fire on mainnet

Real PoW values are hashes — uniformly distributed, never exact powers of 2.
The divergence requires `powHit` to land exactly on a power-of-2 boundary,
which has probability `1/2^52` per header (one ULP out of the mantissa range).
Over 10 million headers that's still ~1 in 450 billion — effectively zero. The
node has worked fine because this case doesn't arise in practice.

The synthetic `DefaultFakePowScheme` triggers it because `d = q / (height + 10)`
produces `d = q / 32` at height 22, and `q` (the group order) is close to
`2^256`, making `d ≈ 2^251` — an exact power of 2 in IEEE 754.

## Fix

sigma-rust should match the JVM's `log2` implementation:

```rust
// Before (Rust-native, more precise but JVM-incompatible):
fn log2(x: f64) -> f64 { x.log2() }

// After (matches JVM's math.log(x) / math.log(2)):
fn log2(x: f64) -> f64 { x.ln() / core::f64::consts::LN_2 }
```

Using `ln() / LN_2` reproduces the JVM's rounding behavior because both sides
compute the same `ln(x) / ln(2)` chain. The Rust constant `LN_2` is the same
IEEE 754 value as Java's `Math.log(2)`.

Verify: after the fix, `max_level_of` for a header with `powHit = q/32` should
return 4, not 5 — matching the JVM.

## Scope

This affects `maxLevelOf` only. The downstream effects (interlinks, proof
selection, serialization) are all correct given the wrong level — the bug is
at the root, not in the propagation.

The `continuous` byte omission in sigma-rust's `NipopowProof` serializer is a
separate finding (the struct has no `continuous` field; the JVM serializer
writes a trailing byte for it). That one affects every proof, not just
power-of-2 edge cases.

## Related

- ergots spec (`2026-08-18-nipopow-prover-design.md`) §Risks: "sub-ulp `log2`
  divergence between engines" — predicted this exact class of issue.
- sigma-rust#866: prior `pack_interlinks` divergence fix (position byte
  truncation).
