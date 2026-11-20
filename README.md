# SANTA

**S**igma-**A**nchored **N**ode **T**est **A**pparatus: a cross-implementation conformance suite for Ergo consensus.

SANTA is a corpus of language-agnostic test vectors, a thin runner per implementation, and an orchestrator that
grades every implementation against the same vectors. Each vector entry is an input the way an implementation
receives it and the output the Ergo reference implementation gives for it. Independent implementations prove,
entry by entry, that they agree with the reference, the way Ethereum's
[`execution-specs`](https://github.com/ethereum/execution-specs) lets geth, besu, nethermind and reth prove they
agree with each other.

**Live scoreboard:** <https://mwaddip.github.io/santa/>

- [How it works](#how-it-works)
- [The implementations on the board](#the-implementations-on-the-board)
- [What it has found](#what-it-has-found)
- [For implementation maintainers](#for-implementation-maintainers)
- [Running SANTA](#running-santa)
- [Maintaining the corpus](#maintaining-the-corpus)
- [Repository layout](#repository-layout)
- [Contributing](#contributing)

## How it works

### The vectors

**The wire is the spec.** An entry's input is what an implementation reads: the bytes of an ErgoTree, a box, a
transaction or a block section, plus the context the operation needs. Every implementation already parses the
wire format, so no language-specific encoding stands between the vector and the code under test.

**One oracle.** Every expected output comes from the JVM reference implementation, `sigma-state` and `ergo-core`
6.0.7 (the reference node's v6.0.7 release), run by SANTA's blesser in `jvm-blesser/`. Where an implementation
differs from the reference, the implementation is wrong. Each entry records the version it was blessed under: the
activated script version and the tree's own version.

Vectors live at `vectors/<tier>/<version>/<provenance>/<op>.json`:

- **`<version>`**: `v4` (activated script version 0 or 1, before the JIT interpreter), `v5` (2), `v6` (3), or `any`
  where the version plays no part. A runner declares the highest version it supports and gets every vector at or
  below it.
- **`<provenance>`**: where the input came from.
  - `spec`: the reference's own language specification.
  - `authored`: written for SANTA, every expectation blessed by the reference.
  - `captured`: real chain data, testnet or mainnet.
  - `vendored`: another project's test fixtures, every expectation re-derived through the reference.

[`docs/corpus.md`](docs/corpus.md) walks through what the corpus pins, family by family.

### Seven tiers

| Tier | One entry | Size | Contract |
|---|---|---|---|
| eval | an ErgoTree and its context → the value and its cost | ~50,000 | [runner-contract](docs/contract/runner-contract.md) |
| wire | the bytes of a constant, box, transaction, tree, header or block section → re-serialized, or refused | ~1,500 | [wire](docs/contract/runner-contract-wire.md) |
| transaction | a transaction, its inputs and the context → valid, and its cost | ~3,400 | [transaction](docs/contract/runner-contract-transaction.md) |
| block | a block with its ADProofs, the parent state digest, headers and parameters → valid, the post-state digest, the cost | 15 | [block](docs/contract/runner-contract-block.md) |
| chain | difficulty retargeting, parameter voting, the soft-fork vote gate, header votes | 73 | [chain](docs/contract/runner-contract-chain.md) |
| authds | the AVL+ prover (its proof bytes) and verifier (accepted, the per-operation results, the digest) | 47 | [authds](docs/contract/runner-contract-authds.md) |
| nipopow | interlinks and NiPoPoW proofs over a chain of real headers | 10 | [nipopow](docs/contract/runner-contract-nipopow.md) |

The transaction and block tiers need no node and no UTXO database: everything an entry needs travels with it.

### Runners and grading

A runner is a thin adapter. Called as `santa-run <impl-path> <vectors-dir> <out-dir>`, it builds against the
implementation, evaluates every entry of every vector it is given, and writes one **actuals** file per vector:
the implementation's own answer per entry. A runner never compares anything.

`./conform` hands each runner the vectors its declared scope selects, runs it, and grades every runner's
actuals with one shared comparator. The grading rules are data: [`vectors/grading.json`](vectors/grading.json), in the
small language [`grading.md`](docs/contract/grading.md) specifies, interpreted by `tools/santa-check` and proven
against the verdict oracle in `oracle/`. It also holds every actual to its tier's actuals schema (`schema/`).

Per entry and per dimension (value, cost, verdict, digest, bytes), an answer is 🎁 when it matches the blessed
expectation and 🪨 when it does not. The outcomes besides an answer:

| Outcome | Meaning | Graded |
|---|---|---|
| `errored` | the implementation refused the input | 🎁 where the reference refuses it too |
| `not-implemented` | not supported yet | red, counted on its own: the readiness map |
| `panicked` | the runner crashed on the entry, or wrote an answer its tier's schema refuses | always red |
| `exceeded` | a bounded file went over its budget (one heavy entry, with a memory and time limit the reference meets easily) | always red |

**A red is the suite doing its job.** Divergences are reported to the implementations, never hidden, excluded or
pinned green.

## The implementations on the board

| Runner | Implementation | Tiers | The runner lives in |
|---|---|---|---|
| **rudolph** | the JVM reference, the control | all seven | SANTA, `jvm-blesser/` |
| **dasher** | [ergots](https://github.com/mwaddip/ergots), pure TypeScript | eval, wire, transaction, authds | SANTA, `ts-runner/` (moving into ergots as `santa/`) |
| **blitzen-develop** | [sigma-rust](https://github.com/ergoplatform/sigma-rust), upstream `develop` | eval, wire, transaction, nipopow | SANTA, a submodule (moving into sigma-rust as `santa/`, [#1022](https://github.com/ergoplatform/sigma-rust/pull/1022)) |
| **blitzen-mwaddip** | [sigma-rust, the mwaddip fork](https://github.com/mwaddip/sigma-rust)'s `master` | eval, wire, transaction, nipopow | the fork, [`santa/`](https://github.com/mwaddip/sigma-rust/tree/master/santa) |
| **comet** | [Fleet SDK](https://github.com/fleet-sdk/fleet), pure TypeScript | wire (constants, boxes, transactions) | SANTA, a submodule |
| **donner** | [ergo-node-rust](https://github.com/mwaddip/ergo-node-rust), digest-state validation | block, chain, nipopow | SANTA, a submodule |
| **vixen** | [arkadianet/ergo](https://github.com/arkadianet/ergo) | eval, wire, block, chain, authds, nipopow | SANTA, a submodule |

The scoreboard has their current grades, slice by slice. Rudolph blessed the corpus, so a red on its row is a
SANTA defect. Every other row grades an implementation, not its runner: a runner SANTA maintains is SANTA's to
keep right, and a runner an implementation hosts is the implementation's.

## What it has found

A few of the divergences the corpus has surfaced:

- **An over-accept in three independent Rust implementations.** The reference refuses a lambda of more than one
  argument when the closure is created. sigma-rust (both branches) and arkadianet/ergo evaluated such lambdas to
  completion. One authored family showed the same class in all three.
- **An AVL+ verifier without its operation bounds.** arkadianet/ergo accepted a proof padded with extra nodes that
  the reference and ergots reject. arkadianet fixed it (`19091d47`).
- **An AVL+ prover that serves non-canonical proofs.** For a data-input lookup, the Rust prover emits a proof that
  verifies but is not the proof the reference builds. A captured block seed waits on it.
- **NiPoPoW levels by floating point.** A header's level computed with a double's `log2` differs from the
  reference's at exact powers of two, in sigma-rust and arkadianet alike.
- **Ports that converge.** Each authored round tends to surface the next divergence class in a port, and the fix
  comes back: the sigma-rust fork and ergots have both closed whole tiers this way, round after round.

## For implementation maintainers

You can run SANTA against your implementation yourself, from your own repository: keep the runner next to your
code, grade your working tree locally against SANTA's latest vectors, and get a comment on every pull request that
shows what it turns red or green. sigma-rust's runner ([`santa/`](https://github.com/mwaddip/sigma-rust/tree/master/santa)
on the mwaddip fork) and ergots' are working examples.

### 1. A runner in your repository

A runner your implementation hosts is a directory, `santa/` by convention:

```
santa/
├── runner.json   what it implements
├── santa-run     executable: builds against this checkout and runs the conformer
└── mise.toml     its toolchain, pinned (SANTA assumes only mise and git on the machine)
```

`runner.json` holds the runner's scope and nothing else:

```json
{ "version": "v6", "tiers": ["eval", "wire", "transaction"], "cost": true }
```

- **`version`**: the highest version slot it supports (`v4`, `v5`, `v6`); every lower one comes with it.
- **`tiers`**: the tiers it implements.
- **`cost`**: whether it claims the cost dimension; `false` grades value only.
- **`kinds`** and **`activation`** (optional, wire tier): the wire kinds it implements, and whether it applies the
  rules tied to the activated script version.

A scope is coarse (a whole tier, kind or class of rule) and needs a reason your API gives, stated in the runner's
README. Something you mean to support but have not built yet stays in scope: its `not-implemented` entries are
your readiness map. See [runner-integration §2](docs/contract/runner-integration.md#2-the-manifest--runnerjson).

`santa-run <impl-path> <vectors-dir> <out-dir>` must:

- build against the checkout it lives in (find it from its own location, by real path), and never modify that
  checkout: it may be a developer's working tree;
- evaluate every `*.json` in `<vectors-dir>` and write `<out-dir>/<same name>`, one answer per entry. A failure on
  one entry is an outcome in the file, never a crash of the run;
- run the conformer on its **last line**, under SANTA's resource bound when one is set, with the vectors dir and the
  out dir as the last two arguments: `${SANTA_BOUND:+"$SANTA_BOUND"} <command> "$vectors" "$out"`;
- dispatch on each vector's `schema` by exact name and version, and answer `{"error": "not-implemented"}` for every
  entry of a schema it was not written for, so a schema SANTA adds reaches you as `not-implemented`, never as false
  divergences;
- exit 0 when it ran. A non-zero exit means the runner itself could not run.

A skeleton:

```bash
#!/usr/bin/env bash
# santa-run <impl-path> <vectors-dir> <out-dir>
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo="$(dirname "$here")"                       # the checkout this runner lives in
vectors="$(realpath "$2")"; out="$(realpath -m "$3")"; mkdir -p "$out"
(cd "$repo" && cargo build --release -p my-santa-runner)
${SANTA_BOUND:+"$SANTA_BOUND"} "$repo/target/release/my-santa-runner" "$vectors" "$out"
```

An eval-tier actuals file, for instance, maps each entry's name to the implementation's answer:

```json
{
  "Coll()#0":     { "value": { "kind": "Boolean", "value": false }, "cost": 95, "error": null },
  "Coll(true)#2": { "value": { "kind": "Boolean", "value": true },  "cost": 95, "error": null }
}
```

Each tier's actuals shape is in its contract and in `schema/santa-<tier>.actuals.schema.json`;
[runner-integration](docs/contract/runner-integration.md) has the whole calling convention.

**The runner is yours.** Once it lives in your repository you own it, and you are free to build on it: your own
commands and scripts, a local grading wrapper, debugging modes, its own tests. The one thing to keep intact is the
interface SANTA runs it through, because that same interface runs your pull request check and your row on the board:

- `runner.json` holds the scope keys and nothing else;
- `santa-run <impl-path> <vectors-dir> <out-dir>` builds and runs, with the conformer on its last line under
  `${SANTA_BOUND:+"$SANTA_BOUND"}`;
- it writes one actuals file per vector, in its tier's actuals shape, and answers `{"error": "not-implemented"}` for a
  schema it was not written for;
- it exits 0 when it ran.

ergots' runner, for instance, comes with `santa/grade`, its own kit that runs SANTA's checks on the working tree.

### 2. Grade locally against SANTA's latest vectors

SANTA needs `mise` and `git`, and runs on **Linux**, where the kernel enforces the data limits it runs a runner
under. SANTA's own tools (Rust) and your runner's toolchain
provision themselves; the first run builds the tools.

```bash
git clone https://github.com/mwaddip/santa.git && cd santa
./conform --hosted ~/src/my-impl          # your working tree, uncommitted edits included
```

- `git pull` in the SANTA checkout brings the newest vectors.
- `--runner-dir <dir>` if your runner is not in `santa/`; `--label <name>` names it in the table.
- `--hosted <url>#<ref>` grades a commit of a remote instead (`#<branch>`, `#<tag>`, or `#<branch>@<sha>`).

`conform` prints a line per slice (tier, version, provenance), e.g.

```
eval/v6/authored: value 15156/15156 · 8 not-impl · cost 15156/15156 · reject 19100/19100
```

and writes `.santa/results.json`, every red entry with its file, name, dimension, expected and actual. Your runner's
raw actuals are in `.santa/<repo-name>/out/`.

Before you push, see the comment your pull request would get:

```bash
./santa-pr ~/src/my-impl origin/main               # that base against your working tree
./santa-pr ~/src/my-impl <base-sha> <head-ref>     # two commits
```

It grades the base and the head, each with its own copy of your runner, on the same SANTA vectors, and writes
`.santa/pr/comment.md` (what the comment says), `summary.md` (every entry, both sides) and `delta.json`.

### 3. The pull request check

Two workflow files in your repository give every pull request, a fork's included, a SANTA comment: entries newly
red, now green, red another way and still red, one row per vector file, from grading the PR's base and the PR
merged into it. The full per-entry list goes to the check's job summary, and every entry, both sides, to its
`santa-pr` artifact.

- **`santa.yml`**, the check, runs on `pull_request` with a read-only token and posts nothing. It calls
  `mwaddip/santa/.github/actions/santa-pr@main`.
- **`santa-comment.yml`**, the commenter, runs on `workflow_run` from your default branch with a write token. It never
  runs anything from the pull request: it reads the check's artifact as data and posts it to the one pull request
  whose head the check graded.

Copy both from the [action's README](.github/actions/santa-pr/README.md), which also covers failing the check on new
red and **reading the comment**: the check runs the pull request's own copy of the workflow and its own runner, so
review a change to either like any other code. The board's grade of your default branch is the one that counts.

### 4. On the board

The board grades implementations, one row per tracked branch, never a pull request. For a runner you host, SANTA's
row is a marker that names it:

```json
{ "name": "my-impl", "label": "my-impl (Rust)", "impl": "https://github.com/owner/my-impl.git#main", "runner": "santa" }
```

in `runners/<name>/runner.json`. Open an issue or a pull request on SANTA to add one; each run of the board then
reads your runner's scope from the branch's current commit.

## Running SANTA

Prerequisites as [above](#2-grade-locally-against-santas-latest-vectors). The commands:

| Command | What it does |
|---|---|
| `./conform` | grades every runner under `runners/` and prints the per-slice table; `.santa/results.json` |
| `./conform <runner>…` | only those runners (a control not named is not required) |
| `./conform <runner> --impl <url>#<ref>` or `--impl <path>` | one runner against another checkout of its implementation (a path in place) |
| `./conform --hosted <url>#<ref>` or `<path>` `[--runner-dir <dir>] [--label <label>]` | an implementation's own runner, with no entry in SANTA |
| `./conform --clean …` | wipes `.santa/` first |
| `./santa-pr [--runner-dir <dir>] [--label <label>] <impl> <base> [<head>]` | the pull request delta, into `.santa/pr/` |
| `./validate` | the schema gate: every vector against its schema, its path against its envelope, the bounded files' limits |
| `./scoreboard [results.json] [out-dir]` | the dashboard and badges, into `site/` |

**Rudolph's full grid** (`SANTA_TX_BLESSER=1 ./conform`, the canonical invocation) needs `ergo-core` 6.0.7 in the
local Ivy repository for its transaction, block, chain and nipopow engines (see
[Maintaining the corpus](#maintaining-the-corpus)); without it those arms answer `not-implemented`.

**CI:** the `tools` workflow runs the tooling's tests on every push. The `conform` workflow (run on demand) validates
the corpus, grades every runner, publishes ergo-core for rudolph itself, and publishes the scoreboard to GitHub
Pages.

## Maintaining the corpus

- **Blessing.** The blessers are Scala, in `jvm-blesser/` (sbt). The transaction, block, chain and nipopow engines
  are compiled only with `SANTA_TX_BLESSER=1`, and need `ergo-core` published locally: clone
  [`ergoplatform/ergo`](https://github.com/ergoplatform/ergo) at `v6.0.7` and run
  `sbt "avldb/publishLocal" "ergoWallet/publishLocal" "ergoCore/publishLocal"`.
- **The oracle pin** is `sigma-state` and `ergo-core` 6.0.7. It moved from 6.0.6 on 2026-11-06, after the whole
  corpus re-derived identically under the new pins: every rudolph actual on all seven tiers, and every blesser that
  regenerates committed files. An entry's `blessed_by` names the oracle that first blessed it.
- **Captured seeds.** The raw capture data of a captured seed is kept outside the repository; the committed vectors
  are the reproducible artifact.
- **Vectors are appended, never renumbered.** Other projects copy vector files verbatim; new entries go at the end
  of a file.
- **Every vector passes `./validate`.** A file may declare `limits` (one entry, a memory and time budget: a bounded
  file, [runner-integration §3a](docs/contract/runner-integration.md#3a-bounded-files)). An entry's prose (`script`,
  `description`) travels with it and is never graded.
- **Only reachable behaviour.** Behaviour no consensus path can reach is deleted, once its unreachability is proven.
- `jvm-blesser/src/test/resources/chain-testnet.conf` is pinned by the
  [transaction contract](docs/contract/runner-contract-transaction.md) and changes only with a re-bless.

## Repository layout

```
vectors/<tier>/<version>/<provenance>/   the corpus
vectors/grading.json    the grading rules, as data (docs/contract/grading.md)
schema/                 JSON Schemas: each tier's vectors and actuals
docs/contract/          the runner contracts, one per tier, runner-integration (how a runner is run), grading
docs/corpus.md          what the corpus pins, family by family
docs/coverage/          the eval corpus's coverage manifest (ops, methods, tree shapes)
oracle/                 the verdict oracle the comparator is proven against
jvm-blesser/            Scala: the blessers and rudolph, the JVM reference runner
ts-runner/              dasher, the ergots runner (moving into ergots)
runners/                one directory per board row (its runner.json; santa-run, or a marker for a hosted runner)
tools/                  Rust: conform, validate, santa-check (the grading interpreter), santa-delta, scoreboard
.github/actions/santa-pr/   the pull request check
conform  validate  scoreboard  santa-pr   the entry points
```

## Contributing

SANTA is meant to be community-owned conformance ground: the more independent implementations run the same
vectors, the more the vectors are worth. Help is wanted with:

- **Implementations under test.** Host a runner in your repository and turn on the pull request check
  ([For implementation maintainers](#for-implementation-maintainers)), then ask for a row on the board.
- **Vectors.** Operations and edge cases the corpus misses, captured chain data, reject arms.
- **Design.** The vector schemas, the runner contracts, the check.

A conversation beats a big pull request: read the [contracts](docs/contract/), and open an issue about where you
would like to plug in.

## Related

- [Ergo](https://ergoplatform.org/), the protocol under test.
- [`execution-specs`](https://github.com/ethereum/execution-specs), Ethereum's executable specification and test
  framework (formerly `execution-spec-tests`), the project SANTA's structure is modelled on.

## Acknowledgements

- [**Fleet SDK**](https://github.com/fleet-sdk/fleet): its serializer test vectors
  (`packages/serializer/src/_test-vectors`) are vendored into the wire tier, re-anchored through the reference, so
  each harvest doubles as a reference-against-Fleet pass.
- [**ergots**](https://github.com/mwaddip/ergots): its `fixture-gen` wire fixtures (boxes, sigma-booleans) and its AVL
  prover corpus are vendored too, every expectation re-derived through the reference.

## License

[MIT](LICENSE).
