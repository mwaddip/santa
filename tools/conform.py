#!/usr/bin/env python3
"""SANTA conformance orchestrator. Presence-as-state over runners/*/: each dir with a
valid runner.json + executable santa-run is run against the blessed corpus; one shared
comparator (compare.py) decides nice/coal; a side-by-side table is printed. See
docs/contract/runner-integration.md."""
import json, os, shutil, subprocess, sys, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import categorize

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUNNERS_DIR = os.path.join(ROOT, "runners")
VECTORS = os.path.join(ROOT, "vectors")
CATEGORIES = ["value", "cost", "not-implemented", "unrepresentable", "reject"]
VERSIONS = ["v5", "v6"]  # ordered; cumulative (a runner's version implies all lower)


def parse_relpath(rel):
    """vectors/eval/<tier>/<version>/<provenance>/<op>.json relpath -> (tier, version, provenance, op)."""
    parts = rel.split(os.sep)
    if len(parts) != 4 or not parts[3].endswith(".json"):
        sys.exit(f"vector path is not <tier>/<version>/<provenance>/<op>.json: {rel}")
    tier, version, provenance, leaf = parts
    return tier, version, provenance, leaf[:-len(".json")]


def discover_vectors():
    """One recursive walk of vectors/eval/. Returns sorted [(relpath, tier, version, provenance, op)]."""
    out = []
    for dirpath, _dirs, files in os.walk(VECTORS):
        for fn in files:
            if fn.endswith(".json"):
                rel = os.path.relpath(os.path.join(dirpath, fn), VECTORS)
                out.append((rel,) + parse_relpath(rel))
    return sorted(out)


def select(vectors, version, tiers):
    """Keep vectors where tier in declared tiers AND version <= declared (cumulative).
    Order follows the input — discover_vectors() returns relpath-sorted, so runs are deterministic."""
    vmax = VERSIONS.index(version)
    return [v for v in vectors
            if v[1] in tiers and v[2] in VERSIONS and VERSIONS.index(v[2]) <= vmax]


def discover():
    """presence-as-state: a runner dir is valid iff it has runner.json + an executable santa-run."""
    out = []
    for mpath in sorted(glob.glob(os.path.join(RUNNERS_DIR, "*", "runner.json"))):
        d = os.path.dirname(mpath)
        run = os.path.join(d, "santa-run")
        if not (os.path.isfile(run) and os.access(run, os.X_OK)):
            print(f"  skip {os.path.basename(d)}: no executable santa-run", file=sys.stderr)
            continue
        m = json.load(open(mpath))
        m["_dir"], m["_run"] = d, run
        out.append(m)
    return out


def resolve_impl(impl, cache_dir):
    """impl = "<url>#<ref>" (ref = a branch/tag for latest, a <sha>, or "<branch>@<sha>" to
    pin). Clone/fetch <url> into cache_dir/<repo-name> and check out the ref; return cache_dir
    — the dir Santa passes as <impl-path>, where the runner finds its dependency as
    ./<repo-name> (e.g. ./sigma-rust). impl null → "-" (self-contained: no checkout)."""
    if not impl:
        return "-"
    url, sep, ref = impl.partition("#")
    if not (sep and url and ref):
        sys.exit(f"bad impl '{impl}': expected <url>#<ref>")
    branch, _, sha = ref.partition("@")
    target = sha or branch                       # @sha pins; otherwise the branch/tag/sha
    name = os.path.basename(url.rstrip("/")).removesuffix(".git")
    os.makedirs(cache_dir, exist_ok=True)
    dest = os.path.join(cache_dir, name)
    if not os.path.isdir(os.path.join(dest, ".git")):
        subprocess.run(["git", "clone", "-q", url, dest], check=True)
    subprocess.run(["git", "-C", dest, "fetch", "-q", "--all", "--tags"], check=True)
    subprocess.run(["git", "-C", dest, "checkout", "-q", target], check=True)
    if branch and not sha:                       # bare branch → move to its latest tip
        subprocess.run(["git", "-C", dest, "pull", "-q", "--ff-only"], check=False)
    return cache_dir


def run_one(m):
    """Filter by (version<=, tiers), stage selected vectors flat as symlinks, run, collect actuals
    keyed by source relpath. Everything per-runner lives under .santa/<name>/."""
    ws = os.path.join(ROOT, ".santa", m["name"])
    impl_path = resolve_impl(m.get("impl"), ws)
    selected = select(discover_vectors(), m["version"], m["tiers"])
    indir = os.path.join(ws, "in"); shutil.rmtree(indir, ignore_errors=True); os.makedirs(indir)
    odir  = os.path.join(ws, "out"); shutil.rmtree(odir, ignore_errors=True); os.makedirs(odir)
    staged = {}  # staged filename -> source relpath
    for (rel, _t, _v, _p, _op) in selected:
        name = rel.replace(os.sep, "__")            # flatten; unique across the tree
        os.symlink(os.path.join(VECTORS, rel), os.path.join(indir, name))
        staged[name] = rel
    subprocess.run([m["_run"], impl_path, indir, odir], check=True)
    res = {}
    for name, rel in staged.items():
        ofile = os.path.join(odir, name)
        if os.path.isfile(ofile):
            res[rel] = json.load(open(ofile))
    return res


def tally(actuals):
    """actuals: {relpath: actuals_obj}. Returns {(tier,version,provenance): counts}."""
    slices = {}
    for rel, act in sorted(actuals.items()):
        tier, version, prov, _op = parse_relpath(rel)
        key = (tier, version, prov)
        c = slices.setdefault(key, {"total": 0, "nice": 0, **{k: 0 for k in CATEGORIES}})
        vec = json.load(open(os.path.join(VECTORS, rel)))
        for e in vec["entries"]:
            cat = categorize(act.get(e["name"]), e["expected"])
            c["total"] += 1
            if cat == "nice":
                c["nice"] += 1
            else:
                c[cat] += 1
    return slices


def main(argv):
    if "--clean" in argv:
        shutil.rmtree(os.path.join(ROOT, ".santa"), ignore_errors=True)
    runners = discover()
    if not runners:
        print("no runners under runners/*/ (need runner.json + executable santa-run)")
        return 1
    print(f"\n=== SANTA conformance · {len(runners)} runner(s) ===")
    for m in runners:
        print(f"running {m['name']} …", file=sys.stderr)
        slices = tally(run_one(m))
        agg_red = sum(sum(c[k] for k in CATEGORIES) for c in slices.values())
        mark = "🎁" if agg_red == 0 else "🪨"
        print(f"  {mark} {m['label']}  (version≤{m['version']}, tiers={','.join(m['tiers'])}, cost={m.get('cost', True)})")
        for (tier, version, prov), c in sorted(slices.items()):
            red = sum(c[k] for k in CATEGORIES)
            brk = " ".join(f"{c[k]}{k[0]}" for k in CATEGORIES if c[k])
            line = f"      {tier}/{version}/{prov}: {c['nice']}/{c['total']} nice"
            print(line + (f" · RED {red} ({brk})" if red else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
