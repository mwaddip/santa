# Wire finding — STypeVar UTF-8 name byte-exactness: the JVM (and sigma-rust eni) collapse a surrogate to 1 U+FFFD; un-fixed lossy decoders produce 3

**Tier:** wire (`santa-wire/v1`, non-identity round-trip, `ErgoTree` kind). **Surfaced:** 2026-06-16
(the wire round-trip arm, off the eval STypeVar UTF-8 leniency vectors). **Status:** sigma-rust **eni
FIXED + pinned** (regression guard, `wire/v6/authored/STypeVar.name_utf8_roundtrip.json`); open on
ergots/dasher + arkadianet/vixen (no structural arm yet); upstream `sigma-rust#develop` not-impl
(lacks the lenient parse hook). The 3rd STypeVar fork, after the charset-acceptance fork
(`STypeVar.name_utf8_leniency`, eval) and ask-23's length bound.

## The divergence

A type-var name carrying the bytes `ed a0 80` — an ill-formed UTF-8 encoding of a UTF-16 surrogate
(U+D800) — is decoded differently by the two ecosystems:

- The **JVM** `TypeSerializer.deserialize` reads the name as `new String(bytes, UTF_8)` — a lossy
  decode that never throws. Java collapses the whole attempted 3-byte sequence to a **single**
  U+FFFD, re-encoding to `ef bf bd` (3 bytes).
- **Rust** `from_utf8_lossy` follows the Unicode "maximal subparts" rule: the surrogate lead `ed`
  plus two stray continuations yield **three** U+FFFD → `ef bf bd ef bf bd ef bf bd` (9 bytes).

An `ErgoTree` carrying such a type-var name therefore **re-serializes to different bytes** on a JVM
node vs. an un-fixed Rust node — a different script template / `propositionBytes` → a different
`boxId` → a consensus fork. It is adversarial-reachable only (honest users never emit a malformed
type-var name), which is precisely the threat model a conformance suite exists to pin: a craftable
input that makes two implementations disagree on an object's identity.

The other four probed sequences converge (JVM ≡ Rust): `ff`→1, `e2 82`→1, `c0 80`→2,
`61 ff 62`→`61·1·62`. Only `ed a0 80` splits (1 vs 3).

## The two serialize paths — echo vs structural (the load-bearing subtlety)

The fork only manifests on a **structural** re-serialize. Both impls have a cached/echo path that
hides it:

| impl | echo path (cached) | structural path (re-encodes) |
|---|---|---|
| JVM | `ErgoTree.bytes` (the lazy/cached form; `== propositionBytes`) — returns the raw input verbatim | `ErgoTreeSerializer.serializeErgoTree(tree)` — re-encodes the decoded name |
| sigma-rust | `ErgoTree::sigma_parse_bytes` (SigmaProp-strict) — keeps unparsed template bytes, echoes | `ErgoTree::sigma_parse_bytes_lenient` (arbitrary-root) — re-encodes the decoded name |

So the wire `ErgoTree` kind **must** re-serialize from structure (the JVM blesser uses
`LenientErgoTree.deserialize` = `checkType=false`; the sigma-rust runner uses
`sigma_parse_bytes_lenient`). An echo path would (a) on the JVM control, emit the raw non-canonical
input and grade differ against its own canonical (red on its own oracle), and (b) on a runner, mask
the fork by round-tripping to identity. This is the `runner-contract-wire.md` §5 "structural, not
cached" clause; it was proven live by the spike (JVM) and the probe (sigma-rust).

## Captured bytes (the vector — input → JVM structural canonical)

| name | `bytes_hex` (input, raw spliced name) | `expected_bytes_hex` (JVM structural canonical) |
|---|---|---|
| `ff` | `1b1501040ad801d701016701ffd901026701ff72027300` | `1b1901040ad801d701016703efbfbdd901026703efbfbd72027300` |
| `e282` | `1b1701040ad801d701016702e282d901026702e28272027300` | `1b1901040ad801d701016703efbfbdd901026703efbfbd72027300` |
| `c080` | `1b1701040ad801d701016702c080d901026702c08072027300` | `1b1f01040ad801d701016706efbfbdefbfbdd901026706efbfbdefbfbd72027300` |
| `eda080` | `1b1901040ad801d701016703eda080d901026703eda08072027300` | `1b1901040ad801d701016703efbfbdd901026703efbfbd72027300` |
| `61ff62` | `1b1901040ad801d70101670361ff62d90102670361ff6272027300` | `1b1d01040ad801d70101670561efbfbd62d90102670561efbfbd6272027300` |

An un-fixed Rust impl emits `…6709 efbfbd efbfbd efbfbd …` for `eda080` (≠ the JVM `…6703 efbfbd …`).

## Resolution

- **sigma-rust eni `10a77c5c`** ("match the JVM's exact U+FFFD substitution for STypeVar names") makes
  the lenient round-trip **byte-exact to the JVM on all 5**, including `eda080` (1 FFFD). Confirmed by
  the blitzen-eni `ErgoTree` wire arm (santa-blitzen `af0840a`): conform red_total 5→0.
- The vector `vectors/wire/v6/authored/STypeVar.name_utf8_roundtrip.json` (5 non-identity entries,
  `kind: ErgoTree`) is a **regression guard**: rudolph (JVM) and blitzen-eni both green; a future
  regression to 3-FFFD on eni would go red on `eda080`.

## Open / routed

- **ergots (dasher)**: no structural `ErgoTree` wire arm yet → honest `not-implemented`. Routed
  (`prompts/`) to add a structural arm (re-encode, not echo) and adopt the JVM-exact U+FFFD
  substitution. Until then the divergence is latent (not-impl), not graded red.
- **arkadianet (vixen)** — **arm LANDED** (`d5e41e4`), grades **5/5 `errored`** (a real, faithful
  divergence, not a coverage gap). The fork is a step *earlier* than the JVM-1-vs-Rust-3 count:
  arkadianet's `ergo-ser` decodes STypeVar names with **strict `String::from_utf8`** (`sigma_type.rs`)
  — it does not lossy-decode at all, so it **rejects** every ill-formed name (`ff`/`e2 82`/`c0 80`/
  `ed a0 80`/`61 ff 62`) where the JVM lossy-accepts + canonicalizes. The node fix (lossy-decode with
  the JVM collapse count) is arkadianet's; vixen flips green with zero runner change when it ships. Per
  [[conformance-divergences-are-the-deliverable]] the runner working is ours (re-pinned `d5e41e4`); the
  red is theirs — surfaced, not tracked. *Adjacent (flagged by the vixen session, not asserted):*
  arkadianet's `unparsed_soft_fork_tree` (`ergo_tree.rs:197`) re-serializes a `Const(true)` placeholder
  + empty constants when wrapping a size-flagged tree whose body fails to parse, where Scala "preserves
  the full declared-size bytes" — consensus-reachability untraced (the boxId still hashes the original
  bytes; the open question is eval/spend of a wrapped box). A possible separate finding, out of scope here.
- **upstream `sigma-rust#develop`** (blitzen-develop): lacks `sigma_parse_bytes_lenient` (a fork-only
  conformance addition, `16878aed`) → cannot host the arm → not-impl, by design.

## Consensus-liveness note

The wire tier pins `serialize(parse(x))` **conformance** unconditionally — the impls demonstrably
disagree on the structural re-serialize, and that is the deliverable. Whether the fork is
consensus-**live** is a separate severity question: the JVM's `boxId`/`propositionBytes` path uses
the cached `ErgoTree.bytes` (it *preserves* `ed a0 80`), so a live fork requires an implementation to
**re-encode on a path the JVM preserves** — its own `boxId` path, an indexer, or a re-serializing
REST boundary (the surface `wire-tier.md` already flags as finding-bearing). A box-level probe (does
`ErgoBox.sigmaSerializer` round-trip preserve or re-encode the embedded tree on each impl?) would
settle liveness if it becomes worth pinning.
