# Chain voting: the zombie round — approval flips between checkpoints, and a missed cleanup is forever

**Found:** 2026-06-11, while spiking the soft-fork-round authored batch
(`SoftForkRoundSpike.scala`, asserts Z1-Z4 + source-read of
`Parameters.updateFork`, ergo-core 6.0.2.1, Parameters.scala:98-155).
**Status:** pinned as-is by `vectors/chain/v6/authored/Voting.softfork_zombie.json`
(4 entries). This is JVM consensus behavior — conformers must reproduce it bit-for-bit;
an implementation that "fixes" any of it silently forks.

## The mechanism

At every epoch boundary the JVM evaluates `votes = votesInPrevEpoch + table(121)`
(Parameters.scala:107-108): the CURRENT closing epoch's seeded 120-tally PLUS the
frozen collected counter (121 stops accumulating after S+4096). The approval predicate
`softForkApproved(votes)` is therefore **not monotone across checkpoints** — each
checkpoint sees a different closing epoch:

- **S+4224** (failed-voting cleanup): fires only if NOT approved *at this boundary*.
- **S+8192** (activation): fires only if approved *at this boundary*.
- **S+8320** (post-activation cleanup): fires only if approved *at this boundary*.

A round with frozen 121 just under the line (e.g. 3680 vs the 3686 testnet threshold)
flips between "approved" and "not approved" purely on how many of the closing epoch's
headers voted 120.

## The three pinned consequences

1. **Survive-then-stall** (`softfork-zombie-survive`, `-no-activation`): closing votes
   at S+4224 lift it over the line (no cleanup), a quiet epoch before S+8192 drops it
   back (no activation, and no cleanup either — that branch only exists at S+4224).
   121/122 persist past activation height.
2. **Cleanup-without-activation** (`softfork-zombie-late-cleanup`): closing votes at
   S+8320 lift it over the line again — the *successful-voting* cleanup fires and
   removes 121/122, but `blockVersion` was never bumped (activation needed approval at
   S+8192 exactly). The round "ends successfully" having activated nothing.
3. **Stuck forever** (`softfork-zombie-stuck`): if S+8320 ALSO fails the approval check,
   no lifecycle branch can ever fire again — every branch requires an exact checkpoint
   offset from the stale 122, and they have all passed; the new-voting branch requires
   122 absent. 121/122 sit in the parameters table permanently and **no soft-fork round
   can ever start again on that chain**. Fork votes are dead from then on.

## Why it matters for the suite

This is exactly the class where an independent implementation diverges by being
*reasonable*: re-reading the mutated table, accumulating during the wait, treating the
cleanup as activation, or garbage-collecting a stale round all look like fixes and all
fork the chain. The four vectors grade the JVM behavior verbatim.

Reachability: real (no hostile input required) — it needs only a round whose frozen
count lands within one epoch's votes of the threshold, with miners still casting 120
votes during the wait phase.
