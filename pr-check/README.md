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

In `santa.yml`, check three values in the job's `env`:
- `RUNNER_DIR`: the runner's directory, holding its `runner.json` and `santa-run`; `santa` by default
  ([runner-integration §2](../docs/contract/runner-integration.md#a-runner-the-implementation-hosts--runner));
- `LABEL`: the runner's name in the comment; the repository's name by default;
- `SANTA_REF`: the SANTA ref whose vectors grade the pull request; `main` by default (see below).

Nothing else needs editing, and SANTA needs no entry for the runner. The check runs the `jq` programs with a pinned
jq (1.8.1, installed through mise), never the image's: a runner image changed it under them once (1.7 to 1.8). SANTA
tests both programs under 1.7 and 1.8.

**The commenter takes effect from the default branch.** GitHub runs a `workflow_run` workflow only from there, so
merge `santa-comment.yml` first, or together with the check.

## How it works

It takes two workflows so that a pull request from a fork works too. GitHub gives a fork's pull request a read-only
token and runs the pull request's own copy of its workflow files.

**The check** (`santa.yml`, `contents: read`) posts nothing. Its steps:
1. Checks out the pull request merged into its base (`github.sha`), with full history.
2. Clones SANTA at `SANTA_REF` into `$RUNNER_TEMP/santa`, outside the repository's tree, read as data.
3. Grades the head with `$RUNNER_DIR/santa-run - $RUNNER_TEMP/santa <out>`.
4. Grades the base the same way, from a git worktree of the base commit with **its own copy** of the runner. A side
   that has no runner, exits non-zero, or leaves no readable `results.json` is reported as "could not run", and the
   comment says so. Whether a `results.json` is readable is `delta.jq`'s to decide; the workflow only checks it is
   JSON. The job still succeeds.
5. Runs `delta.jq` and `comment.jq`, then uploads the artifact `santa-pr`: `comment.md`, `delta.json` and `pr.json`.

**The commenter** (`santa-comment.yml`, `actions: read`, `pull-requests: write`) runs the default branch's copy. It
never checks out or runs anything from the pull request. It reads the artifact's two text files out of its zip, and
posts only to an **open** pull request whose head is the commit the check ran on. It updates its one comment (marked
`<!-- santa-pr -->`) on every push, one run at a time per pull request branch, so two runs cannot both post a first
comment. It finds that comment by the marker and by `POSTS_AS`, the login its token posts as: `github-actions[bot]`
for the workflow's own token. A repository that posts with another token changes `POSTS_AS` with it, or every push
adds a new comment.

## Reading the comment

- **The classes.** An entry (a vector file's entry) is compared by its *set* of red dimensions; the expected and
  actual renderings are for people and play no part.
  - **Newly red:** green at base, red at head.
  - **Now green:** the reverse.
  - **Red another way:** red at both, on different dimensions.
  - **Still red:** red at both, on the same dimensions; only counted.
- **The rows.** One row per vector file, the largest first, at most 50 per section in the comment. The check run's
  job summary has every file, then every entry with its expected and actual answer.
- **Files on one side only.** A file graded on one side only, because the runner's scope moved, is listed apart
  with its red entries there, at most 50 in the comment. A moved scope (`version`, `tiers`, `cost`, `kinds`,
  `activation`) is noted too.
- **"No delta: …"** says why there is no comparison:
  - a side could not run: no runner at the base, a base that cannot be checked out, a build failure, an oracle not
    reproduced, or a `results.json` that is not JSON or not readable `santa-runner-results/v1`;
  - the two sides were graded against different SANTA commits. They never are in this workflow, which grades both
    against one clone.
- **The comment is the pull request's own work.** The check runs the pull request's copy of `santa.yml` and of the
  two `jq` programs, and grades the head with the head's runner, so a pull request that changes any of them can make
  its comment say anything. Review changes there like any other code. The grade that counts is SANTA's board, which
  grades the default branch.
- **The vectors track SANTA's `main`**, so every pull request is graded on SANTA's current vectors. Both sides use
  one clone, so a change on SANTA's side never shows up as the pull request's doing. To hold the vectors still, set
  `SANTA_REF` to a SANTA commit.
- **The cells are inert.** Every name and answer is escaped: no tag, no table split, no markdown, and no
  `@mention`. The text comes from the pull request's own artifact, so it must not notify anyone through the bot.
- **The actions are pinned to commits**, each with its release in a comment beside it.
- **The job's token is read-only, and the runner sees it.** `jdx/mise-action` exports the job's token as
  `MISE_GITHUB_TOKEN` to the later steps, so mise can download toolchains without hitting GitHub's API limits. The
  pull request's own runner, and anything it installs, can read that token. It is `contents: read`, the same access
  the job's code already has.

## Optional steps

- **Fail the check on new red:** add a step after `upload the delta`:
  `jq -e '.newly_red | length == 0' .santa-pr/artifact/delta.json`. It must come after the upload, or the artifact is
  never uploaded. Know the cost: the commenter posts only after a successful check, so a pull request whose check
  fails this way keeps the comment of its last successful run. This run's delta is still in its job summary and its
  artifact.
- **Run your runner's own SANTA-reading tests** with the same clone, e.g. `SANTA_DIR=$RUNNER_TEMP/santa npm test`.

## `delta.json`

`schema: "santa-pr-delta/v1"`, with these fields:

| field | what it holds |
|---|---|
| `label`, `base_sha`, `head_sha` | as given |
| `santa` | the SANTA commit graded against |
| `outcome` | `"compared"`, or `"not-compared"` with a `reason` |
| `scope_changed` | `{base, head}` (the scopes as text), or `null` |
| `files` | `{only_base, only_head}`: lists of `{file, red_entries}`, the files graded on that side only |
| `newly_red`, `now_green`, `changed` | lists of `{slice, file, entry, base, head, note?}`; `base` and `head` hold that side's red dimensions, each `{expected, got}` |
| `still_red` | a count |

SANTA's tests for both programs are in [`test/`](test/) (`bash test/run.sh`).
