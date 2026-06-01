"""Self-test for conform.resolve_impl (plain asserts; run with .venv/bin/python).

Builds a throwaway local git "remote" and checks that resolve_impl clones it into
<impl-path>/<repo-name>, honors a bare branch (latest) vs a @<sha> pin, and maps
null impl to "-"."""
import os, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from conform import resolve_impl


def git(*a):
    subprocess.run(["git", *a], check=True, capture_output=True)


def chk(name, cond):
    print(f"  [{'OK' if cond else 'XX'}] {name}")
    return cond


# A local "remote" repo: commit v1, then v2, on branch main.
remote = tempfile.mkdtemp(prefix="santa-remote-")
git("init", "-q", "-b", "main", remote)
git("-C", remote, "config", "user.email", "t@santa")
git("-C", remote, "config", "user.name", "santa")
with open(os.path.join(remote, "VERSION"), "w") as f:
    f.write("v1\n")
git("-C", remote, "add", ".")
git("-C", remote, "commit", "-qm", "v1")
sha1 = subprocess.run(["git", "-C", remote, "rev-parse", "HEAD"],
                      capture_output=True, text=True, check=True).stdout.strip()
with open(os.path.join(remote, "VERSION"), "w") as f:
    f.write("v2\n")
git("-C", remote, "add", ".")
git("-C", remote, "commit", "-qm", "v2")

name = os.path.basename(remote)

# 1) bare branch → latest tip (v2)
cache = tempfile.mkdtemp(prefix="santa-cache-")
impl_path = resolve_impl(f"{remote}#main", cache)
checkout = os.path.join(impl_path, name)
ok = chk("impl_path is the passed cache dir", impl_path == cache)
ok &= chk("checkout lands at <impl-path>/<repo-name>", os.path.isdir(checkout))
ok &= chk("bare branch → latest (v2)",
          open(os.path.join(checkout, "VERSION")).read().strip() == "v2")

# 2) @<sha> pin → that commit (v1), not the tip
cache2 = tempfile.mkdtemp(prefix="santa-cache2-")
resolve_impl(f"{remote}#main@{sha1}", cache2)
ok &= chk("@sha pin → pinned commit (v1)",
          open(os.path.join(cache2, name, "VERSION")).read().strip() == "v1")

# 3) null impl → "-"
ok &= chk("null impl → '-'", resolve_impl(None, cache) == "-")

print("=== checkout: ALL OK ===" if ok else "=== checkout: FAILURES ===")
sys.exit(0 if ok else 1)
