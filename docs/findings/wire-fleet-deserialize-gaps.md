# Wire finding — Fleet serializes what it cannot re-read: deserialize-side gaps, and what they imply for tier design

**Tier:** wire (`santa-wire/v1`, round-trip). **Surfaced:** 2026-06-05, the first **Comet**
(`santa-comet`, the Fleet SDK runner) run over `vectors/wire/v5/vendored` @ `a66af91`.
**Status:** real, one-directional capability gaps in Fleet — graded faithfully by comet
(`errored` / `not-implemented`, all coal); nothing excluded from the corpus. Recorded
both as findings and as **test-design input** for the deferred serialize-direction arm.

Comet's first grade, for reference: **185/213 round-trips green, zero byte-mismatches,
zero panicked** — when Fleet parses, it re-serializes byte-identically. The 28 coal:

| slice | coal | mechanism |
|---|---|---|
| Transaction | 17 × `errored` | finding 1 |
| Box | 1 × `errored` (`box_6`) | finding 1 |
| Constant | 3 × `not-implemented` (`sigmaProp_0/1/2`) | finding 2 |
| SigmaBoolean | 7 × `not-implemented` | no bare entry point (known scope, not a finding) |

## Finding 1 — `readErgoTree` parses only three tree shapes

Fleet's box/tx deserializers read an embedded ErgoTree via `readErgoTree`
(`packages/serializer/src/serializers/boxSerializer.ts`), which handles exactly:
the **fee contract** (byte-matched), **P2PK** (`0008cd` + valid EC point), and trees
**with the size flag** (header bit `0x08`, then VLQ size). Anything else throws
`"ErgoTree parsing without the size flag is not supported."` — skipping an unsized
general tree would require the full expression parser Fleet deliberately doesn't ship.

**Trigger:** every one of the 17 `Transaction` vectors carries at least one
general-script output without the size bit, as does `box_6` (verified: same throw).
The tx bytes are **verbatim from Fleet's own `_test-vectors/signedTransactions.json`**
(the JVM canonicalization changed nothing) — Fleet's own specs serialize these vectors
but never deserialize them, so the gap is invisible to Fleet's CI. Fleet can *write*
transactions it cannot *read back*.

## Finding 2 — SigmaProp constants: deserialize→serialize shape asymmetry

`dataSerializer.deserialize` for an `SSigmaProp` constant returns the **raw 33-byte
group element** (after the `0xcd` ProveDlog opcode), but `dataSerializer.serialize`
expects the **nested-`SConstant`** shape the constructor API produces
(`node.type === descriptors.groupElement`) — and throws its gap error
`"Serialization error: SigmaProp operation not implemented."` for anything else.
So `SConstant.from(bytes).serialize()` — the natural public round-trip — throws for
**every** SigmaProp constant, **ProveDlog included**. (First read suggested "non-dlog
props"; the corpus entries are all `08cd…` = ProveDlog. The asymmetry is the defect.)

**Bucket note (error-taxonomy relevant):** comet's classifier routes Fleet's own
"not implemented" message to the `not-implemented` bucket, though mechanically this is
a serialize-side defect on a parse-supported value. Either bucket is coal (NI never
matches a blessed expected), so the verdict is unaffected — but it is a live example
of why the deferred error taxonomy (runner-contract §7) matters: "has no
implementation" vs "implementation is internally inconsistent" are different findings
wearing the same message.

## Implications for tier design (the actual point of this note)

**Round-trip enters through the parse side.** A vector is bytes-in → parse →
re-serialize → bytes-out, so a conformer that fails to *parse* never gets to
demonstrate its *serialize* fidelity on that vector. For Fleet — a builder/wallet SDK
whose primary job is producing transactions — this means the direction it is best at
is exactly the direction the round-trip tier cannot grade on these vectors. Its
serialize side is in fact clean: zero byte-mismatches on all 185 parsed entries, and
the vendor-time JVM diff found no serialize-side divergences either (the tx vectors
*are* Fleet's bytes, JVM-confirmed canonical).

Test-design consequences worth weighing when the next wire arms are specced:

1. **A serialize-direction vector form** — structured input → expected canonical
   bytes — would grade the direction builder SDKs actually exercise. This is
   adjacent to (or part of) the deferred `structural-assert` / `santa-wire/v2` arm
   (wire-tier.md "Out of scope / Deferred"); these findings are its first concrete
   demand signal. The structured-form contract it requires is the same one
   structural-assert needs anyway.
2. **Scoreboard category semantics:** a "build-only SDK" lens (serialize-direction
   slices graded, parse-direction shown as declared scope) would let the grid say
   "Fleet builds correct transactions but cannot re-read general ones" instead of a
   flat 0/17 — more accurate, which is the whole point. (Category tuning is a
   SANTA-side decision; comet just emits faithful actuals either way.)
3. **The reject arm gains a seed class:** "bytes the JVM parses but conformer X
   rejects" (these findings) is the mirror of the recorded sigma-rust
   `creation_height` case ("bytes sigma-rust accepts but the JVM rejects") — the
   reject arm should assert both directions when it lands.

## Disposition

Comet reports all of the above faithfully and runs live in the 5-way grid
(`github.com/mwaddip/santa-comet`, `runners/comet`; the 28 coal on the standing
board are exactly this table). The
Transaction limitation is pinned **as a classification** in comet's own test suite
(flips to a round-trip assertion when Fleet grows unsized-tree parsing). Both findings
are candidates for routing upstream to `fleet-sdk/fleet`; nothing in the corpus or the
blesser changes.
