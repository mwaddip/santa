# SANTA's PR check

A pull request to an Ergo implementation gets a comment comparing SANTA's grades at its base and at its head:
entries newly red, now green, red another way, and still red, one row per vector file. Everything runs in the
implementation's own workflows, on its own runners, with its own token, and **no SANTA code runs there**:
- the implementation's own runner grades both sides
  ([runner-integration §3](../docs/contract/runner-integration.md#3-the-entrypoint--santa-run));
- the comparison is two short `jq` programs, copied with the workflows.

## What to copy

| from this directory | to the implementation's repository |
|---|---|
| [`santa.yml`](santa.yml) | `.github/workflows/santa.yml`: the check |
| [`santa-comment.yml`](santa-comment.yml) | `.github/workflows/santa-comment.yml`: the commenter |
| [`delta.jq`](delta.jq) | `.github/santa-pr/delta.jq`: two results into `delta.json` |
| [`comment.jq`](comment.jq) | `.github/santa-pr/comment.jq`: `delta.json` into the comment and the job summary |

In `santa.yml`, set two values in the job's `env`:
- `RUNNER_DIR`: the runner's directory, holding its `runner.json` and `santa-run`; `santa` by default
  ([runner-integration §2](../docs/contract/runner-integration.md#a-runner-the-implementation-hosts--runner));
- `LABEL`: the runner's name in the comment; the repository's name by default.

Nothing else needs editing. SANTA needs no entry for the runner.

**The commenter takes effect from the default branch.** GitHub runs a `workflow_run` workflow only from there, so
merge `santa-comment.yml` first, or together with the check.

## How it works

It takes two workflows so that a pull request from a fork works too. GitHub gives a fork's pull request a read-only
token and runs the pull request's own copy of its workflow files.

**The check** (`santa.yml`, `contents: read`) posts nothing. Its steps:
1. Checks out the pull request merged into its base (`github.sha`), with full history.
2. Clones SANTA's `main` into `$RUNNER_TEMP/santa`, outside the repository's tree, read as data.
3. Grades the head with `$RUNNER_DIR/santa-run - $RUNNER_TEMP/santa <out>`.
4. Grades the base the same way, from a git worktree of the base commit with **its own copy** of the runner. A base
   with no runner, or one that exits non-zero, "could not run", and the comment says so.
5. Runs `delta.jq` and `comment.jq`, then uploads the artifact `santa-pr`: `comment.md`, `delta.json` and `pr.json`.

**The commenter** (`santa-comment.yml`, `actions: read`, `pull-requests: write`) runs the default branch's copy. It
never checks out or runs anything from the pull request. It reads the artifact's two text files out of its zip,
posts only when the pull request's head is the commit the check ran on, and updates its one comment (marked
`<!-- santa-pr -->`) on every push.

## Reading the comment

- **The classes.** An entry (a vector file's entry) is compared by its *set* of red dimensions; the expected and
  actual renderings are for people and play no part.
  - **Newly red:** green at base, red at head.
  - **Now green:** the reverse.
  - **Red another way:** red at both, on different dimensions.
  - **Still red:** red at both, on the same dimensions; only counted.
- **The rows.** One row per vector file, the largest first, at most 50 per section in the comment. The check run's
  job summary has every file, then every entry with its expected and actual answer.
- **Files on one side only.** A file graded on one side only, because the runner's scope moved, is listed apart,
  and so is a moved scope (`version`, `tiers`, `cost`, `kinds`, `activation`).
- **"No delta: …"** says why there is no comparison:
  - a side could not run (no runner at the base, a build failure, an oracle not reproduced);
  - the two sides were graded against different SANTA commits. They never are in this workflow, which grades both
    against one clone.
- **The comment is the pull request's own work.** The check runs the pull request's copy of `santa.yml` and of the
  two `jq` programs, and grades the head with the head's runner, so a pull request that changes any of them can make
  its comment say anything. Review changes there like any other code. The grade that counts is SANTA's board, which
  grades the default branch.
- **The vectors track SANTA's `main`**, so every pull request is graded on SANTA's current vectors. Both sides use
  one clone, so a change on SANTA's side never shows up as the pull request's doing. To hold the vectors still, pin
  the fetch to a SANTA commit.
- **The actions are pinned to commits**, with the release each one is in in a comment beside it.

## Optional steps

- **Fail the check on new red:** add a step after `compare`:
  `jq -e '.newly_red | length == 0' .santa-pr/artifact/delta.json`.
- **Run your runner's own SANTA-reading tests** with the same clone, e.g. `SANTA_DIR=$RUNNER_TEMP/santa npm test`.

## `delta.json`

`schema: "santa-pr-delta/v1"`, with these fields:

| field | what it holds |
|---|---|
| `label`, `base_sha`, `head_sha` | as given |
| `santa` | the SANTA commit graded against |
| `outcome` | `"compared"`, or `"not-compared"` with a `reason` |
| `scope_changed` | `{base, head}` (the scopes as text), or `null` |
| `files` | `{only_base, only_head}` |
| `newly_red`, `now_green`, `changed` | lists of `{slice, file, entry, base, head, note?}`; `base` and `head` hold that side's red dimensions, each `{expected, got}` |
| `still_red` | a count |

SANTA's tests for both programs are in [`test/`](test/) (`bash test/run.sh`).
