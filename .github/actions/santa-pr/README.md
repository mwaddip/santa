# SANTA PR check

A pull request to an Ergo implementation gets a comment comparing SANTA's grades at its base and at its head:
entries newly red, now green, red another way, and still red, one row per vector file
([runner-integration §6a](../../../docs/contract/runner-integration.md#6a-one-runner-a-working-tree-and-a-pull-requests-delta)).
It runs entirely in the implementation's own workflows, on its own runners, with its own token.

It takes two workflow files, so that a pull request from a fork works too. GitHub gives a fork's pull request a
read-only token, and runs the pull request's own copy of its workflow files: the check (this action) runs the
pull request's code with that token and posts nothing; the commenter runs the default branch's copy with a write
token, and never checks out or runs anything from the pull request. It reads the check's artifact as text.

The repository hosts its own SANTA runner: a directory, `santa/` by default, holding its `santa-run` and a
`runner.json` with its scope
([runner-integration §2](../../../docs/contract/runner-integration.md#a-runner-the-implementation-hosts--runner)).
SANTA needs no entry for it: SANTA provides the vectors, the comparator and the delta, and the base and the head
are each graded with their own copy of the runner. Linux runners only (SANTA's resource bound needs a systemd user
manager).

## `.github/workflows/santa.yml`: the check

```yaml
name: SANTA
on:
  pull_request:
permissions:
  contents: read
jobs:
  santa:
    runs-on: ubuntu-latest
    timeout-minutes: 90
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0 # the base and head are graded from this checkout
      - uses: mwaddip/santa/.github/actions/santa-pr@main
        # with:
        #   runner-path: santa      # the runner's directory in this repository (default santa)
        #   label: owner/repo       # its name in the comment (default the repository's)
```

The head graded is the pull request merged into its base (`github.sha`), so a base that moved on since the branch
was cut does not show up as the pull request's doing.

## `.github/workflows/santa-comment.yml`: the commenter

GitHub runs a `workflow_run` workflow only from the **default branch**: it takes effect once it is there.

```yaml
name: SANTA comment
on:
  workflow_run:
    workflows: [SANTA] # the check's name
    types: [completed]
permissions:
  actions: read # download the check's artifact
  pull-requests: write # post or update the comment
jobs:
  comment:
    if: github.event.workflow_run.event == 'pull_request' && github.event.workflow_run.conclusion == 'success'
    runs-on: ubuntu-latest
    steps:
      - name: download the check's artifact
        env:
          GH_TOKEN: ${{ github.token }}
          RUN_ID: ${{ github.event.workflow_run.id }}
        run: gh run download "$RUN_ID" --repo "$GITHUB_REPOSITORY" --name santa-pr --dir santa-pr
      - name: post or update the comment
        env:
          GH_TOKEN: ${{ github.token }}
          HEAD_SHA: ${{ github.event.workflow_run.head_sha }}
        run: |
          set -euo pipefail
          # The artifact comes from the pull request's run, so nothing in it is trusted: the number is used only
          # when that pull request's head is the commit the check ran on. (For a fork's pull request GitHub leaves
          # the event's pull_requests empty, so the number has to come from the artifact.)
          pr=$(jq -r '.number' santa-pr/pr.json)
          [[ "$pr" =~ ^[0-9]+$ ]] || { echo "no pull request number in the artifact"; exit 1; }
          head=$(gh api "repos/$GITHUB_REPOSITORY/pulls/$pr" --jq '.head.sha')
          if [ "$head" != "$HEAD_SHA" ]; then
            echo "pull request #$pr's head is $head, the check ran on $HEAD_SHA: not posting"
            exit 0
          fi
          marker='<!-- santa-pr -->'
          { echo "$marker"; cat santa-pr/comment.md; } > body.md
          id=$(gh api --paginate "repos/$GITHUB_REPOSITORY/issues/$pr/comments" \
                 --jq ".[] | select(.user.login == \"github-actions[bot]\" and (.body | startswith(\"$marker\"))) | .id" \
               | head -n 1)
          if [ -n "$id" ]; then
            gh api -X PATCH "repos/$GITHUB_REPOSITORY/issues/comments/$id" -F body=@body.md > /dev/null
          else
            gh api "repos/$GITHUB_REPOSITORY/issues/$pr/comments" -F body=@body.md > /dev/null
          fi
```

One comment per pull request, updated on every push. The full per-entry list is in the check run's job summary,
and every entry, both sides, in its `santa-pr` artifact (`delta.json`).

Failing the check on new red is the implementation's choice: add a step after the action that reads
`.santa-pr/artifact/delta.json`, e.g. `jq -e '[.runners[].newly_red // [] | length] | add == 0'`.
