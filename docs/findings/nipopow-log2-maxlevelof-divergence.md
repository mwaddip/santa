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

The level is `trunc(log2(T) − log2(hit))`. The two forms disagree whenever the *computed*
difference lands on opposite sides of an integer, which happens for doubles within a few hundred
ulps of `T_f/2^k`, for any target `T`. So the condition is "hit near `T/2^k`", not "hit is an exact
power of two". The `q/32 ≈ 2^251` case in the vector is one instance of that: it fires because the
target there, `double(q) = 2^256`, is itself a power of two, so `2^251` sits exactly on a level
boundary. With a realistic target a power-of-two hit is neither necessary nor sufficient.

Measured (2026-09-18) for four real `nBits` values (two mainnet, one testnet, one from a node test
fixture), k = 1..40 and ±2048 ulps around each boundary (655,520 doubles): `f64::log2` and `ln/LN_2`
gave different levels on 12,998 doubles; none of those is a power of two. Differences fall between
−27 and +300 ulps of the boundary; none occur mid-level (0 of 655,520 control rows).

The per-header probability is about 2e-15 to 1.7e-14, not `1/2^52` (2.2e-16): computed exactly as
Σ(preimage width of each differing double)/T for each target. That is roughly 9–75× the earlier
figure and changes nothing in practice: under 5e-9 expected events per year at Ergo's block rate.
(Separately, `2^52 / 10^7` is about 450 million, not 450 billion.) The value depends on the target;
an independent re-run on three other `nBits` values gave 2.0e-15 to 8.0e-15. The node has worked
fine because this case doesn't arise in practice.

The synthetic `DefaultFakePowScheme` triggers it because `d = q / (height + 10)`
produces `d = q / 32` at height 22, and `q` (the group order) is close to
`2^256`, making `d ≈ 2^251` — an exact power of 2 in IEEE 754 — while `double(q)` is `2^256`.

## Which JVM

"Reproduces the JVM's rounding behavior" (below) depends on the JDK. `Math.log` is specified to
within 1 ulp, not bit-exactly. On the same 655,520 doubles:

- JDK 11, 17 and 21 agree with each other on every double, in default, `-Xint` and `-Xcomp` modes.
- JDK 8 differs from them on 4 doubles (raw `Math.log` differs by 1 ulp on 169 inputs; 4 reach the
  integer). Example, target from mainnet `nBits 121307964`: `T_f = 0x4c915a2ada883936`,
  `h = 0x4be15a2ada883924` → JDK 8 level **11**, JDK 11/17/21 and Rust `ln/LN_2` level **10**.
- Rust `ln()/LN_2` (glibc 2.39) removes almost all of the divergence — from ~13,000 doubles to a
  handful — but is not bit-identical to either JDK class: it agreed with JDK 11/17/21 on all 655,520
  doubles above, and an independent re-run found one double where it sides with JDK 8 instead
  (`T_f = 0x4cac2000ba5404d2`, `h = 0x4abc2000ba540594`: JDK 8 **30**, JDK 11/17/21 **31**, Rust
  `ln/LN_2` **30**). `LN_2` equals `Math.log(2)` bit-for-bit; the 1-ulp differences are in `ln(h)`.

The per-header probability of that split is of order 1e-18 (estimate), so this is a note about what
the vectors mean, not a live risk. It matters here because the ergo node's CI runs JDK 8 while its
Docker image ships JRE 11, and blessed vectors currently record
`blessed_by: jvm:ergo-core-6.0.2.1-NipopowAlgos` with no JDK. A comment and the `--add-opens` options
in `jvm-blesser/build.sbt` indicate JDK 17 (not pinned), which is on the same side as the shipped
JRE. The fix below remains the right one: it is the closest available form, and the existing
vector (`2^256`, `2^251`) gives 4 on every JDK tested. Suggestion: record the blesser's
`java.version` alongside `blessed_by`, so a future vector that happens to sit on one of these
doubles is reproducible.

Measured on x86_64 Linux (glibc 2.39, rustc 1.98.1, OpenJDK 8u502 / 11.0.32 / 17.0.20 / 21.0.12).
Not tested: aarch64, macOS, musl, Windows; no chain scan was run; no claim is made that any mainnet
header is affected. Prepared with Claude (Anthropic), model Claude Fable 5.1.

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
