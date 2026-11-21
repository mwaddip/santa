"""The PR-check workflow templates' structure: what keeps the check safe on a fork's pull request and the commenter
working (pr-check/README.md). Run by run.sh when PyYAML is there: python3 workflows.py <pr-check dir>."""
import re
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1])
fails = []


def check(cond, what):
    if not cond:
        fails.append(what)


def load(name):
    p = root / name
    check(p.is_file(), f"{name} exists")
    return yaml.safe_load(p.read_text()) if p.is_file() else {}, p.read_text() if p.is_file() else ""


def uses(doc):
    for job in (doc.get("jobs") or {}).values():
        for step in job.get("steps") or []:
            if "uses" in step:
                yield step


check_doc, check_text = load("santa.yml")
on = check_doc.get(True, check_doc.get("on"))  # YAML 1.1 reads a bare `on` as True
check(isinstance(on, dict) and set(on) == {"pull_request"}, "santa.yml runs on pull_request only")
check(check_doc.get("permissions") == {"contents": "read"}, "santa.yml's token is contents: read and nothing else")
steps = [s for j in (check_doc.get("jobs") or {}).values() for s in j.get("steps") or []]
co = [s for s in steps if str(s.get("uses", "")).startswith("actions/checkout@")]
check(len(co) == 1 and co[0].get("with", {}).get("fetch-depth") == 0, "the checkout fetches full history (fetch-depth 0)")
check(len(co) == 1 and co[0].get("with", {}).get("persist-credentials") is False, "the checkout keeps no credentials")
up = [s for s in steps if str(s.get("uses", "")).startswith("actions/upload-artifact@")]
check(len(up) == 1 and up[0].get("with", {}).get("name") == "santa-pr", "the artifact is named santa-pr")
check("$RUNNER_TEMP/santa" in check_text, "SANTA is cloned under $RUNNER_TEMP, outside the repository's tree")
check(".github/santa-pr/delta.jq" in check_text and ".github/santa-pr/comment.jq" in check_text,
      "the check runs the copied delta.jq and comment.jq")
grade = [s.get("run", "") for s in steps if str(s.get("name", "")).startswith("grade the")]
check(len(grade) == 2 and all("santa-runner-results/v1" in g and "jq -e" in g for g in grade),
      "both grade steps check results.json is santa-runner-results/v1 before it is compared")
check(any(re.search(r"worktree add[^\n]*\|\|", g) for g in grade), "a base that cannot be checked out is a status, not a failed job")
job_env = next(iter((check_doc.get("jobs") or {}).values()), {}).get("env") or {}
check(job_env.get("SANTA_REF") == "main" and '"$SANTA_REF"' in check_text, "the SANTA ref is one env value (SANTA_REF, main)")

comment_doc, comment_text = load("santa-comment.yml")
check(comment_doc.get("permissions") == {"actions": "read", "pull-requests": "write"},
      "santa-comment.yml's token is actions: read, pull-requests: write")
cjob = next(iter((comment_doc.get("jobs") or {}).values()), {})
check("conclusion == 'success'" in str(cjob.get("if", "")), "the commenter runs only after a successful check")
check("unzip -p" in comment_text, "the commenter reads the artifact as data (unzip -p), never extracts it")
check("<!-- santa-pr -->" in comment_text, "the commenter's marker is <!-- santa-pr -->")
check("actions/checkout" not in comment_text, "the commenter checks out nothing")

for name, doc in (("santa.yml", check_doc), ("santa-comment.yml", comment_doc)):
    for step in uses(doc):
        check(re.fullmatch(r"[\w.-]+/[\w.-]+@[0-9a-f]{40}", step["uses"]) is not None,
              f"{name}: {step['uses']} is pinned to a commit")

if fails:
    print("FAIL workflows:\n  " + "\n  ".join(fails), file=sys.stderr)
    sys.exit(1)
print("workflows: the templates' structure holds")
