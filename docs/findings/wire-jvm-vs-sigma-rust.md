# Wire finding — JVM caps box `creation_height` at `Int.MaxValue`; sigma-rust accepts `u32`

**Tier:** wire (`santa-wire/v1`, round-trip). **Surfaced:** 2026-06-03, the first `AuthoredWire` bless run.
**Status:** real divergence; excluded from the round-trip MVP corpus; a seed for the future wire reject arm.

## The divergence

sigma-state (the canonical JVM oracle, 6.0.3) reads an `ErgoBox` `creation_height` with `getUIntExact`,
which **throws `ArithmeticException: Int overflow`** on any value above `Int.MaxValue`
(2³¹−1 = 2 147 483 647). sigma-rust stores `creation_height` as a `u32`, so it serializes and parses the
full unsigned-32-bit range (up to 2³²−1 = 4 294 967 295).

The two implementations therefore disagree on the *parseable box space*: a box with
`creation_height ∈ (2³¹−1, 2³²−1]` is well-formed to sigma-rust and **rejected at parse** by the JVM.

## Root cause (source)

`org.ergoplatform.ErgoBoxCandidate.parseBodyWithIndexedDigests` (sigma-state 6.0.3), line ~195:

```scala
val creationHeight = r.getUIntExact
// NO-FORK: ^ in v5.x getUIntExact may throw Int overflow exception
// in v4.x r.getUInt().toInt is used and may return negative Int instead of the overflow
// and ErgoBoxCandidate with negative creation height is created, which is then invalidated
// during transaction validation. See validation rule # 122 in the Ergo node (ValidationRules.scala)
```

`getUIntExact` (`sigma.serialization.CoreByteReader:73`) reads a VLQ unsigned int and calls `toIntExact`,
which overflows for values > `Int.MaxValue`. The comment is explicit that this is the v5.x behaviour
(v4.x silently produced a negative height, later rejected by node validation rule #122).

## Trigger

ergots' `fixture-gen` wire fixture `sbox_boundary` (`sbox-roundtrip.json`) — a synthetic boundary box
with `value = MAX_RAW`, `creation_height = u32::MAX (4 294 967 295)`, `index = u16::MAX`, `txId = 0xff·32`.
sigma-rust produces its bytes; the JVM `ErgoBox.sigmaSerializer.parse` throws.

Bytes (`santa-wire` `kind: Box`):
`ffffffffffffffff7f09020101ffffffff0f0000ffff…ff03` — the `ffffffff0f` segment is the VLQ encoding of
`creation_height = 4 294 967 295`.

## Significance

Real but **synthetic** — `creation_height` is a block height (~1.5M on mainnet today), so no actual chain
box approaches 2³¹. It is not a live consensus risk; it is a domain-edge divergence in what each serializer
will accept. It is a *reject*-shaped case (the JVM rejects bytes sigma-rust accepts), which belongs to the
**wire reject arm** (deferred), not the round-trip MVP.

## Disposition

`AuthoredWire` catches the per-entry parse throw (the `isWireEncodable` analog the eval blesser already
applies), **excludes** `sbox_boundary` from the committed round-trip corpus, and records it here. The Box
round-trip vector ships the 4 JVM-parseable entries; this case is retained as a seed for the future wire
reject/mutation arm, where "the JVM rejects these bytes" is the asserted outcome.
