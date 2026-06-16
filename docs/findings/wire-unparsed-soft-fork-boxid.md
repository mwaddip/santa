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

`vectors/wire/v6/authored/`, identity round-trip (no `expected_bytes_hex`; absent ⇒ round-trip-to-self):

- **`Box.unparsed_soft_fork_boxid.json`** (`kind: Box`, 2 entries) — a green-**everywhere** invariant
  guard. It locks the consensus-safety property this investigation set out to test: *boxId is preserved
  for an unparsed soft-fork tree across all impls.* If any impl ever regressed to re-encoding the box from
  the placeholder (leaking `Const(true)` into `propositionBytes`), this goes red. Entries:
  `c0843d0b01fd0000…` (zeros txId, index 0) and the 3-byte-body variant.
- **`ErgoTree.unparsed_soft_fork_roundtrip.json`** (`kind: ErgoTree`, 2 entries: `0b01fd`, `0b03fd0102`) —
  pins the JVM/eni preserve and surfaces the bare-tree parse divergences (vixen/dasher/develop). The
  3-byte body proves the full declared region is preserved, not just the opcode.

Both are guarded by `AuthoredWireBoxUnparsedSoftForkTest` / `AuthoredWireUnparsedSoftForkTest`, which
re-derive the blessing each run (genuinely-unparsed + JVM identity) so a sigma-state change fails loud.

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
