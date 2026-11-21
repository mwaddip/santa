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

A runner runs an implementation over the vectors and grades its answers. Called as `santa-run <impl-path>
<santa-dir> <out-dir>`, it selects the vector files its declared scope covers from a SANTA checkout, runs every entry
through the implementation, grades each answer, and writes its results and the raw answers (**actuals**). The grading
rules are data: [`vectors/grading.json`](vectors/grading.json), in the small language
[`grading.md`](docs/contract/grading.md) specifies. Every runner interprets that file, in its own language, and proves
its interpreter against the verdict oracle in `oracle/` before it grades anything.

`./conform` runs every runner on the board and re-grades its raw answers with SANTA's own interpreter
(`tools/santa-check`) as a **canary**: the board shows SANTA's re-grade, and where a runner's own grading disagrees, a
note below the board says so. conform also holds every answer to its tier's actuals schema (`schema/`) and grades
every file the runner's scope selects, so a file the runner skipped shows red.

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
| **dasher** | [ergots](https://github.com/mwaddip/ergots), pure TypeScript | eval, wire, transaction, authds, nipopow | ergots, [`santa/`](https://github.com/mwaddip/ergots/tree/master/santa) |
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

`santa-run <impl-path> <santa-dir> <out-dir>` reads `<santa-dir>`, a SANTA checkout, as data only, and in order:

1. **checks itself**: its grading interpreter reproduces every case of the verdict oracle
   (`oracle/verdicts-grading.json`) and its selection every case of the selection oracle (`oracle/selection.json`);
   otherwise it exits non-zero, naming the case;
2. **selects** the vector files its scope covers ([runner-integration §4.1](docs/contract/runner-integration.md#41-selection));
3. **runs** every entry through the implementation, built from the checkout the runner lives in (found from its own
   location, by real path, and never modified: it may be a developer's working tree). A failure on one entry is an
   outcome, never a crash of the run. A vector schema it was not written for answers `{"error": "not-implemented"}` for
   every entry, so a schema SANTA adds reaches you as `not-implemented`, never as false divergences;
4. **bounds** each bounded file (one heavy entry with a `limits` budget) in a child process of its own, killed at its
   wall time or when its resident memory passes the budget: `{"error": "exceeded", "limit": "time" | "memory"}`
   ([runner-integration §3a](docs/contract/runner-integration.md#3a-bounded-files));
5. **grades** every answer by `vectors/grading.json`;
6. **writes** `<out-dir>/results.json` (the SANTA commit graded against, the scope, per slice the files graded, the
   counts and every red; `schema/santa-runner-results.schema.json`) and `<out-dir>/actuals/<path>`, the raw answers
   of each vector file in its tier's actuals shape.

It exits 0 when it ran, whatever the reds; non-zero means the runner itself could not run. Given a `<santa-dir>`, it
grades exactly that checkout.

A skeleton, with the work in a program of your own language:

```bash
#!/usr/bin/env bash
# santa-run <impl-path> <santa-dir> <out-dir>
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo="$(dirname "$here")"                       # the checkout this runner lives in
santa="$(realpath "$2")"; out="$(realpath -m "$3")"; mkdir -p "$out"
(cd "$repo" && cargo build --release -p my-santa-runner)
exec "$repo/target/release/my-santa-runner" "$santa" "$out"   # checks, selects, runs, bounds, grades, writes
```

The interpreter, the selection and the bound are a few hundred lines, written once; a new grading type SANTA adds
needs no change to them, only a new building block of the grading language would. SANTA's own interpreter,
`tools/santa-check`, is one to read. An eval-tier actuals file, for instance, maps each entry's name to the
implementation's answer:

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
- `santa-run <impl-path> <santa-dir> <out-dir>` grades the SANTA checkout it is given;
- it writes `results.json` and `actuals/` as above, and answers `{"error": "not-implemented"}` for a schema it was not
  written for;
- it exits 0 when it ran.

### 2. Grade locally against SANTA's latest vectors

Your runner needs nothing from SANTA but a checkout of it, as data:

```bash
git clone --depth 1 https://github.com/mwaddip/santa.git ~/.cache/santa    # git -C ~/.cache/santa pull: the newest vectors
santa/santa-run - ~/.cache/santa ./santa-out
```

`santa-out/results.json` lists every red with its expected and actual answer; `santa-out/actuals/` holds the raw
answers.

To see what the board makes of it, SANTA's canary included, run SANTA's `conform` on your working tree. SANTA needs
`mise` and `git`, and runs on **Linux**, where the kernel enforces the data limits it runs a runner under; its own
tools (Rust) and your runner's toolchain provision themselves, and the first run builds the tools.

```bash
cd ~/.cache/santa
./conform --hosted ~/src/my-impl          # your working tree, uncommitted edits included
```

- `--runner-dir <dir>` if your runner is not in `santa/`; `--label <name>` names it in the table.
- `--hosted <url>#<ref>` grades a commit of a remote instead (`#<branch>`, `#<tag>`, or `#<branch>@<sha>`).

`conform` prints a line per slice (tier, version, provenance), then whether your runner's own grading agrees with
SANTA's, e.g.

```
eval/v6/authored: value 15156/15156 · 8 not-impl · cost 15156/15156 · reject 19100/19100
grader: agrees
```

and writes `.santa/results.json`, every red entry with its file, name, dimension, expected and actual. Your runner's
outputs are in `.santa/<repo-name>/out/`.

Before you push, see the comment your pull request would get:

```bash
./santa-pr ~/src/my-impl origin/main               # that base against your working tree
./santa-pr ~/src/my-impl <base-sha> <head-ref>     # two commits
```

It grades the base and the head, each with its own copy of your runner, on the same SANTA vectors, and writes
`.santa/pr/comment.md` (what the comment says), `summary.md` (every entry, both sides) and `delta.json`.

### 3. The pull request check

Every pull request, a fork's included, gets a SANTA comment: entries newly red, now green, red another way and still
red, one row per vector file. It comes from grading the PR's base and the PR merged into it, each with its own copy of
your runner. The full per-entry list goes to the check's job summary, and every entry, both sides, to its `santa-pr`
artifact. It runs in your repository's own workflows, and no SANTA code runs there. Copy four files from
[`pr-check/`](pr-check/README.md):

- **`santa.yml`**, the check, runs on `pull_request` with a read-only token and posts nothing. It clones SANTA's
  vectors, grades the base and the head, and compares them with two short `jq` programs, `delta.jq` and
  `comment.jq`, copied beside it.
- **`santa-comment.yml`**, the commenter, runs on `workflow_run` from your default branch with a write token. It never
  runs anything from the pull request: it reads the check's artifact as data and posts it to the one pull request
  whose head the check graded.

[The guide](pr-check/README.md) covers the two values to set and failing the check on new red. It also covers
**reading the comment**: the check runs the pull request's own copy of the workflow, the `jq` and the runner, so
review a change to any of them like any other code. The board's grade of your default branch is the one that
counts.

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
| `./santa-pr [--runner-dir <dir>] [--label <label>] <impl> <base> [<head>]` | the pull request delta, into `.santa/pr/` (retiring: the check is [`pr-check/`](pr-check/README.md)) |
| `./validate` | the schema gate: every vector against its schema, its path against its envelope, the bounded files' limits, unique entry names |
| `./scoreboard [results.json] [out-dir]` | the dashboard and badges, into `site/` |

**Rudolph's full grid** (`SANTA_TX_BLESSER=1 ./conform`, the canonical invocation) needs `ergo-core` 6.0.7 in the
local Ivy repository for its transaction, block, chain and nipopow engines (see
[Maintaining the corpus](#maintaining-the-corpus)); without it those arms answer `not-implemented`.

**CI:** the `tools` workflow runs the tooling's tests on every push, and the `pr-check` templates' `jq` tests. The `conform` workflow (run on demand) validates
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
  file, [runner-integration §3a](docs/contract/runner-integration.md#3a-bounded-files)). Entry names are unique within
  a file: actuals are keyed by name. An entry's prose (`script`, `description`) travels with it and is never graded.
- **Only reachable behaviour.** Behaviour no consensus path can reach is deleted, once its unreachability is proven.
- `jvm-blesser/src/test/resources/chain-testnet.conf` is pinned by the
  [transaction contract](docs/contract/runner-contract-transaction.md) and changes only with a re-bless.

## Repository layout

```
vectors/<tier>/<version>/<provenance>/   the corpus
vectors/grading.json    the grading rules, as data (docs/contract/grading.md)
schema/                 JSON Schemas: each tier's vectors and actuals, a runner's results, the grading rules
docs/contract/          the runner contracts, one per tier, runner-integration (how a runner is run), grading
docs/corpus.md          what the corpus pins, family by family
docs/coverage/          the eval corpus's coverage manifest (ops, methods, tree shapes)
oracle/                 the verdict and selection oracles every runner reproduces before it grades
jvm-blesser/            Scala: the blessers and rudolph, the JVM reference runner
runners/                one directory per board row (its runner.json; santa-run, or a marker for a hosted runner)
tools/                  Rust: conform, validate, santa-check (the grading interpreter), santa-delta, scoreboard
pr-check/               the pull request check: the workflow templates and their jq, copied into an implementation
.github/actions/santa-pr/   the former pull request check (retiring)
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
