# Finding: `Header.nBits` sign round-trip divergence (V5 extraction)

**Status:** at V5, MOOT — superseded by the general wire-encodability gate. `Header`
(`SHeader`) is a v6-only wire type (`DataSerializer.scala:19` gates the `SHeader` arm on
`ergoTreeVersion >= 3`), so no `Header` SValue is a valid v5 input at all; the
wire-encodability gate in `SpecExtract.toEntry` drops every `(x: Header) => …` case at
v5 generically, `nBits` included. The old `nBits`-specific capture exclusion has been
removed (it was a special-case of what the gate now handles).

The sign round-trip below remains a **v6-relevant** observation: at v6, where `Header`
inputs ARE wire-encodable, `nBits` still round-trips through the unsigned
`DifficultySerializer`, so an in-memory `-1L` literal would still re-read as
`4294967295`. If/when v6 Header-accessor extraction reaches `nBits`, this is the case to
watch. The loud value-mismatch guard in `SpecExtract.toEntry` is **intentionally
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

- **At V5: dropped by the general wire-encodability gate, not a special case.**
  `SpecExtract.toEntry` calls `EvalCore.isWireEncodable(decoded, activated)` before
  emitting an entry; it serializes the decoded input through sigma-state's own
  `DataSerializer` at the target ErgoTree version and skips (skip-and-report) anything
  that throws. At v5 (ergoTree=2) the `SHeader` arm is gated off, so EVERY `Header` input
  — `nBits` and all the other `(x: Header) => …` accessors — is dropped generically. The
  old `ExcludedScripts`/`excludedKnownDivergence` capture-side hack in `V5Extractor` has
  been removed; the gate subsumes it. (V5 captured count: ~1611 → ~1558; the 53 dropped
  are the Header-input cases.)
- **The cardinal rule stays intact.** `SpecExtract.toEntry` still `sys.error`s on ANY
  value mismatch. The wire gate is a skip on *wire-encodability*, NOT a value-mismatch
  skip — it fires before the eval/compare. "No silent wrong value can ever ship" is
  preserved; the Header cases are removed at the *source* (they are not valid v5 wire
  encodings any conformer could deserialize), where it is visible and auditable.

## Notes / open questions

- The gate is generic (sigma-state's `DataSerializer` is the single source of truth for
  "valid wire constant at version N"), so it self-documents and needs no per-script list.
- The sign round-trip becomes live again at **v6**, where `Header` inputs ARE
  wire-encodable. Worth confirming whether the conformers under test (ergots, sigma-rust)
  read `Header.nBits` as signed or unsigned — if any reads it signed, that is itself a
  cross-implementation divergence worth its own vector once a representation is agreed.
