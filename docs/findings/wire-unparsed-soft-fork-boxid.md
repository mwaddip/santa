# Wire finding — unparsed soft-fork ErgoTree: the box round-trip preserves the boxId on every impl (no consensus fork); only the bare-tree wire surface diverges

**Tier:** wire (`santa-wire/v1`, identity round-trip; `Box` + `ErgoTree` kinds). **Surfaced:** 2026-06-16
(off the vixen `unparsed_soft_fork_tree` flag, `ergo_tree.rs:197`). **Status:** the **boxId
consensus-fork hypothesis is REFUTED by conform** — all five impls preserve the box round-trip
(`Box.unparsed_soft_fork_boxid` green everywhere). The residual divergence is the **bare `ErgoTree`
kind** (`ErgoTree.unparsed_soft_fork_roundtrip`): vixen `errored`, dasher `panicked`, develop
`not-impl`; rudolph + eni preserve. A negative result on the scary path, plus a modest parse-surface
divergence.

## Two surfaces, two outcomes

A **size-flagged `ErgoTree` with an unknown-opcode body** is the soft-fork forward-compat case (old node
can't read the body; the declared size lets it skip). The JVM catches the body-parse `ValidationException`
(sigma-state rule 1002, *"…the opcode is supported by registered serializer or is added via soft-fork"*),
stores the **raw bytes** as `UnparsedErgoTree`, and re-serializes them **verbatim** — identity. vixen
reported arkadianet (`ergo_tree.rs:197`) instead builds a `Const(true)` placeholder + empty constants.
The question was whether that placeholder reaches the boxId. The board answers it on two surfaces:

| surface (kind) | what it exercises | board (rudolph · eni · vixen · dasher · develop) |
|---|---|---|
| **`Box`** | full `ErgoBox.sigmaSerializer` round-trip — the box→tree path, **size flag intact** (the boxId path) | **green · green · green · green · green** |
| **`ErgoTree`** | bare-tree re-serialize; a runner arm may strip the size flag | green · green · **errored · panicked · not-impl** |

**The `Box` round-trip is identity on every impl** — including arkadianet. So the placeholder is a
*parsed-representation* detail that does **not** leak to the box bytes: on re-serialize each impl echoes
the cached raw `propositionBytes`. boxId is preserved universally → **no UTXO-digest / consensus fork via
serialization round-trip.**

## Why the bare-`ErgoTree` surface still diverges (and why it's lower-severity)

The `ErgoTree` kind feeds a naked tree to each runner's structural re-serialize. It diverges, but not
about box identity:

- **eni** — preserves (green). sigma-rust eni round-trips the unparsed tree byte-identical, like the JVM.
- **vixen (arkadianet)** — `errored`. Its wire arm **strips the size flag** to parse the body (a
  deliberate choice to dodge the STypeVar echo trap; from its own reply). Stripping the flag prevents the
  soft-fork wrap, so it hits unknown opcode `0xfd` and fails to parse — never reaching `ergo_tree.rs:197`.
  An *artifact of the arm's construction* meeting arkadianet's strict body parse, not a box-identity bug.
- **dasher (ergots)** — `panicked`: ergots throws an *untyped* error on the unparseable body (the same
  classification gap as the eval/STypeVar arms — [[new-schema-adapter-arm-or-false-divergence]]).
- **develop** — `not-impl`: no lenient bare-tree wire arm (coverage gap).

So the bare-tree reds are about parsing a naked unparsed tree (arm design + strict parse + error typing),
a surface that does not compute box identity.

## The JVM facts (blesser-first, settled live)

sigma-state 6.0.3 is jar-only; spikes are the gate, not recollection:

- **Accept + preserve:** `0b01fd` (header `0b` = v3 + size bit; body = unknown opcode `0xfd`) deserializes
  to `UnparsedErgoTree` (root = `Left`) and re-serializes byte-identical on **both** `.bytes` (cached) and
  `serializeErgoTree` (structural). Trigger is an unknown **opcode** + size — a future-**version** header
  (`0c…`) is hard-rejected at deserialize (*"Tree version (4) is above activated script version (3)"*),
  never reaching the unparsed path. (`0xfe` is the `Context` opcode and parses; `0xfd` is the clean unknown.)
- **boxId anchored to raw bytes:** a JVM `ErgoBox` built from the in-memory unparsed tree serializes to
  `c0843d` · **`0b01fd`** · … — the `propositionBytes` are the raw tree bytes, so boxId hashes them. (This
  is *why* preservation matters: every impl matching it on round-trip is what keeps boxId consistent.)

## The vectors

`vectors/wire/v6/authored/` — identity round-trips (no `expected_bytes_hex` ⇒ round-trip-to-self) plus one
REJECT arm (`error: "errored"` ⇒ the JVM rejects at deserialize, a conformant runner must error):

- **`Box.unparsed_soft_fork_boxid.json`** (`kind: Box`, 2 entries) — a green-**everywhere** invariant
  guard. It locks the consensus-safety property this investigation set out to test: *boxId is preserved
  for an unparsed soft-fork tree across all impls.* If any impl ever regressed to re-encoding the box from
  the placeholder (leaking `Const(true)` into `propositionBytes`), this goes red. Entries:
  `c0843d0b01fd0000…` (zeros txId, index 0) and the 3-byte-body variant.
- **`ErgoTree.unparsed_soft_fork_roundtrip.json`** (`kind: ErgoTree`, 2 entries: `0b01fd`, `0b03fd0102`) —
  pins the JVM/eni preserve and surfaces the bare-tree parse divergences (vixen/dasher/develop). The
  3-byte body proves the full declared region is preserved, not just the opcode.
- **`ErgoTree.unparsed_soft_fork_option_constant.json`** (`kind: ErgoTree`, 1 entry: `1a060128010a7300`) —
  a SECOND degrade trigger: not an unknown opcode (rule 1002) but a **non-serializable type code in a
  segregated constant** (a `SOption[SInt]` constant, typeCode `0x28`, which trips
  `CheckSerializableTypeCode`, rule 1009). The size flag (header `0x1a` = v2 + size + seg) wraps it as
  `UnparsedErgoTree` → identity round-trip (blessed live: rudolph + eni green; dasher panicked, vixen
  errored, develop not-impl — same bare-tree spread). This is the **DEGRADE** side of the rule-1009
  boundary; the **REJECT** twin — an `SHeader` constant (typeCode `0x68`, which rule 1009 does NOT
  special-case, so a direct `SerializerException` escapes the soft-fork fallback and the JVM rejects) —
  lands as a wire **reject arm** — now shipped (next bullet).
- **`ErgoTree.unparsed_soft_fork_header_constant.json`** (`kind: ErgoTree`, 1 entry, **REJECT**:
  `error: "errored"`, no `expected_bytes_hex`) — the REJECT side of the rule-1009 boundary. A v2 + size + seg
  tree with one segregated `SHeader` constant (typeCode `0x68` = 104): SHeader is neither `== OptionTypeCode`
  nor `> LastDataType` (111), so rule 1009 does NOT fire — a direct `SerializerException` ("Not defined
  DataSerializer for type SHeader") ESCAPES the soft-fork fallback and the JVM **rejects**, even size-flagged
  (blessed live: deserialize THROWS). Graded by `grade_wire`'s reject arm (`errored` actual = nice; producing
  bytes = over-accept coal). **Board (converged): rudolph + vixen reject (nice); blitzen-eni OVER-ACCEPTED**
  (degraded the SHeader tree where the JVM rejects) → **FIXED** (sigma-rust eni `044be6c3` gates the lenient
  degrade on rule-1009's `NonSerializableTypeCode`; eni now rejects, green); dasher errored (ergots throws a
  typed `SValueParseError`, mapped → errored by the ts-runner `isWireCodecError`); develop **not-impl** — its
  wire ErgoTree arm needs the lenient parse it lacks, so this ErgoTree vector does NOT grade develop's
  over-accept (the Box twin below does). Bytes from ergots' `sheader-constants-v2-header-literal` fixture.
- **`Box.softfork_header_constant_reject.json`** (`kind: Box`, 1 entry, **REJECT**) — the SAME SHeader reject on
  the **box→tree** path: a real `ErgoBox` frame whose propositionBytes ARE the SHeader tree. The box parse runs
  the impl's STRICT `sigma_parse` (not the lenient bare-tree arm), so it GRADES the strict-path over-accept the
  ErgoTree vector misses. **Board: rudolph + eni reject (nice); blitzen-develop, dasher, AND vixen OVER-ACCEPT**
  — all three degrade the SHeader tree inside a box → accept a box the JVM rejects (a crafted-bytes
  boxId/consensus over-accept). develop's is the sigma-rust strict `sigma_parse` (fix `a9ba5bac` pending); ergots
  is a NEW inconsistency (rejects the BARE SHeader tree but degrades it inside a box); arkadianet's is NEW.
  Minted by frame-swap (a real box frame around a 0xfd tree, propBytes swapped) so the strict parse reaches the
  SHeader DataSerializer; the parse dies at the tree before the tail.

All five are guarded by `AuthoredWireBoxUnparsedSoftForkTest` / `AuthoredWireUnparsedSoftForkTest` /
`AuthoredWireUnparsedSoftForkOptionConstantTest` / `AuthoredWireUnparsedSoftForkHeaderConstantTest` /
`AuthoredWireBoxSoftForkHeaderRejectTest`, which re-derive the blessing each run (genuinely-unparsed + JVM
identity, or — for the reject arms — that `LenientErgoTree.deserialize` / `ErgoBox.sigmaSerializer.parse`
THROWS a `SerializerException` naming SHeader) so a sigma-state change fails loud.

The `(a)` degrade + `(b)`/Box reject set pins the exact `CheckSerializableTypeCode` (rule 1009) boundary:
SOption is the rule's special case (soft-fork degrade → round-trip), SHeader is not (direct reject). An impl
that treats both alike — degrading SHeader like SOption — diverges from JVM consensus on which size-flagged
trees survive deserialization: caught on the LENIENT path on eni (`(b)`, now fixed) and on the STRICT
box→tree path on develop, ergots, and arkadianet (the Box twin).

## What would be needed to actually fork the boxId (not demonstrated reachable)

The round-trip path (parse box → re-serialize) is safe because every impl caches and echoes the original
`propositionBytes`. A leak would require an impl to serialize a box from a *parsed-placeholder* tree that
has **discarded** the cached raw bytes — a non-round-trip construction path. This probe did not find one:
in the realistic flows (relay a received tx's outputs; a genuine soft-fork where new nodes parse the
opcode fully and old nodes hold the raw bytes), the raw bytes are always present and echoed. Recorded as a
ruled-out fork class, not an open severity.

## Relationship to the STypeVar byte-exactness finding

`wire-stypevar-utf8-byte-exactness.md` left open: *"a box-level probe (does `ErgoBox.sigmaSerializer`
round-trip preserve or re-encode the embedded tree?) would settle liveness."* This finding **runs that
probe** (the `Box` kind) and settles it: the box round-trip **preserves** on every impl. For STypeVar the
analogous box-liveness question (does an impl re-encode a lossy-decoded *name* on its boxId path) remains
the live-fork condition there; here the parallel placeholder does **not** survive the round-trip on any
impl, so there is no fork to pin — only the bare-tree surface differs.
