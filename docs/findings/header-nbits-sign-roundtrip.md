# Finding: `Header.nBits` sign round-trip divergence (V5 extraction)

**Status:** open divergence — single excluded case at V5 capture (wiring lands in a
later task). The loud value-mismatch guard in `SpecExtract.toEntry` is **intentionally
preserved** — this finding does NOT silence it.

## The case

- **Property:** `Header properties equivalence`
- **Script:** `{ (x: Header) => x.nBits }`
- **Spec (in-memory) expected value:** `{"kind":"Long","value":"-1"}`
- **Eval (re-blessed) value:** `{"kind":"Long","value":"4294967295"}`

Surfaced by the V5 encode probe (`V5SpikeTest`) as the **only** remaining HARD FAIL
after Task 3's decoder coverage closed the other 85. It manifests as a cardinal
`VALUE MISMATCH` in `SpecExtract.toEntry` (which is the correct, loud behavior):

```
SpecExtract: VALUE MISMATCH for op 'Header properties equivalence'
({ (x: Header) => x.nBits }): spec={"kind":"Long","value":"-1"} vs eval={"kind":"Long","value":"4294967295"}
```

Note `4294967295 == 0xFFFFFFFF == 2^32 - 1` (unsigned 32-bit max), and `-1L` has the
low 32 bits `0xFFFFFFFF`. The two values share the same 4 low bytes; they differ only
in whether those bytes are interpreted as signed (`-1`) or unsigned (`4294967295`).

## Hypothesis (as originally stated)

> `nBits` is serialized as 4 bytes and re-read **unsigned**, dropping the sign of `-1L`
> on the Header bytes round-trip.

This is a HYPOTHESIS about the cause of the spec-vs-eval gap.

## Source check (Rule 17: live evidence) — hypothesis CONFIRMED

`Header.nBits` is typed `Long` but, per sigma-state's own comment, "actually it is
unsigned int" (`org/ergoplatform/ErgoHeader.scala:122`). The byte codec is
`DifficultySerializer` (used by `HeaderWithoutPow` serialize/parse,
`org/ergoplatform/HeaderWithoutPow.scala:55,75`):

```scala
object DifficultySerializer extends SigmaSerializer[Long, Long] {
  /** Parse 4 bytes ... as unsigned 32-bit integer in big endian format. */
  def readUint32BE(bytes: Array[Byte]): Long =
    ((bytes(0) & 0xffL) << 24) | ((bytes(1) & 0xffL) << 16) | ((bytes(2) & 0xffL) << 8) | (bytes(3) & 0xffL)

  def uint32ToByteArrayBE(value: Long): Array[Byte] =
    Array(0xFF & (value >> 24), 0xFF & (value >> 16), 0xFF & (value >> 8), 0xFF & value).map(_.toByte)

  override def parse(r: SigmaByteReader): Long = readUint32BE(r.getBytes(4))
}
```

`readUint32BE` masks every byte with `& 0xffL`, so the result is always in
`0 .. 4294967295` — it can never be negative. Mechanism of the divergence:

1. The V5 test constructs a `Header` literal whose `nBits` field is the in-memory
   `Long` value `-1L` (the spec compares against this raw field).
2. SANTA's re-bless decodes the **Header bytes** (`{kind:"Header",bytes_hex:...}`)
   via `ErgoHeader.sigmaSerializer.parse`, which reads `nBits` through
   `DifficultySerializer.readUint32BE`. The 4 nBits bytes are `0xFF 0xFF 0xFF 0xFF`,
   which `readUint32BE` returns as `4294967295`, not `-1`.

So the bytes-on-the-wire round-trip is **lossless for the 32-bit value**, but the
in-memory `Long` representation `-1L` is not recoverable from those bytes — the
serializer's contract is unsigned. The eval value `4294967295` is the canonical,
serializer-faithful reading of the Header bytes; the spec's `-1` is an artifact of
the literal's in-memory signed `Long`. This is a representation mismatch between
"the raw in-memory field the V5 property reads" and "the value any consumer that
goes through the Header byte codec will observe".

The eval side is internally self-consistent (decode bytes → read nBits → re-encode
the same `4294967295`); the divergence is purely against the spec's pre-serialization
in-memory literal.

## Disposition

- **The V5 extractor will EXCLUDE this single case at capture** (the wiring lands in a
  later task — this finding documents the why; it is not yet wired). The exclusion is
  scoped to exactly this `(x: Header) => x.nBits` case, not a blanket value-mismatch
  skip.
- **The cardinal rule stays intact.** `SpecExtract.toEntry` still `sys.error`s on ANY
  value mismatch. We do NOT add a blanket value-mismatch skip, and we do NOT relax the
  guard for Header. "No silent wrong value can ever ship" is preserved; this one case is
  removed at the *source* (capture-side exclusion), where it is visible and auditable,
  rather than swallowed by the comparator.

## Notes / open questions for the later wiring task

- The exclusion should key on the property name + script (`Header properties
  equivalence` / `{ (x: Header) => x.nBits }`) so it is precise and self-documenting.
- Worth confirming whether the conformers under test (ergots, sigma-rust) read
  `Header.nBits` as signed or unsigned — if any reads it signed, that is itself a
  cross-implementation divergence worth its own vector once a representation is agreed.
  (Out of scope for Task 3; noted so it is not lost.)
