#!/usr/bin/env bash
# The pr-check templates' tests: every case under delta/ (two runner results.json in, delta.json out) and comment/
# (delta.json in, markdown out) against its golden output, and the workflow templates' structure (workflows.py, when
# PyYAML is there). Needs jq 1.7. Exits non-zero at the first difference, naming the case.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
templates="$(cd "$here/.." && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }

# `--arg key value` for every key of a case's args.json, read two lines at a time (never word-split)
args_of() {
  local f=$1 k v
  ARGS=()
  [ -f "$f" ] || return 0
  while IFS= read -r k && IFS= read -r v; do ARGS+=(--arg "$k" "$v"); done < <(jq -r 'to_entries[] | .key, (.value | tostring)' "$f")
}

n=0
for c in "$here"/delta/*/; do
  c=${c%/}; name=delta/$(basename "$c")
  args_of "$c/args.json"
  got=$(jq -n -f "$templates/delta.jq" --slurpfile base "$c/base.json" --slurpfile head "$c/head.json" "${ARGS[@]}") \
    || fail "$name: delta.jq failed"
  diff -u <(jq -S . "$c/expected.json") <(jq -S . <<< "$got") || fail "$name"
  n=$((n + 1))
done
echo "delta: $n case(s) pass"

n=0
for c in "$here"/comment/*/; do
  c=${c%/}; name=comment/$(basename "$c")
  if [ -f "$c/delta.json" ]; then delta=$(cat "$c/delta.json"); else delta=$(jq -n -f "$c/make.jq"); fi
  cap=$(jq -c '.cap' "$c/args.json")
  got=$(jq -r -f "$templates/comment.jq" --argjson cap "$cap" <<< "$delta") || fail "$name: comment.jq failed"
  if [ -f "$c/expected.md" ]; then
    diff -u "$c/expected.md" <(printf '%s\n' "$got") || fail "$name"
  else
    while IFS= read -r line; do
      case "$line" in
        "lines: "*) want=${line#lines: }; have=$(grep -c '^| <code>' <<< "$got" || true)
                    [ "$have" = "$want" ] || fail "$name: $have table rows, want $want" ;;
        *) grep -qF -- "$line" <<< "$got" || fail "$name: missing: $line" ;;
      esac
    done < "$c/expected.grep"
  fi
  n=$((n + 1))
done
echo "comment: $n case(s) pass"
