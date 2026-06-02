#!/usr/bin/env python3
"""SANTA conformance orchestrator. Presence-as-state over runners/*/: each dir with a
valid runner.json + executable santa-run is run against the blessed corpus; one shared
comparator (compare.py) decides nice/coal; a side-by-side table is printed. See
docs/contract/runner-integration.md."""
import json, os, shutil, subprocess, sys, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import categorize, grade

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


def tally(actuals, claims_cost):
    """actuals: {relpath: actuals_obj}. Returns {(tier,version,provenance): per-dimension counts}."""
    slices = {}
    for rel, act in sorted(actuals.items()):
        tier, version, prov, _op = parse_relpath(rel)
        c = slices.setdefault((tier, version, prov), {
            "value_total": 0, "value_nice": 0, "value_coal": 0, "not_impl": 0, "unrepr": 0,
            "cost_graded": 0, "cost_nice": 0, "cost_coal": 0,
            "reject_total": 0, "reject_nice": 0, "reject_coal": 0})
        vec = json.load(open(os.path.join(VECTORS, rel)))
        for e in vec["entries"]:
            g = grade(act.get(e["name"]), e["expected"], claims_cost)
            if g["kind"] == "coverage":  # not-implemented / unrepresentable — precedence over value/reject
                c["not_impl" if g["tag"] == "not-implemented" else "unrepr"] += 1
            elif g["kind"] == "reject":
                c["reject_total"] += 1
                c["reject_nice" if g["verdict"] == "nice" else "reject_coal"] += 1
            else:  # accept vector — independent value + cost verdicts
                c["value_total"] += 1
                c["value_nice" if g["value"] == "nice" else "value_coal"] += 1
                if g["cost"] != "n/a":
                    c["cost_graded"] += 1
                    c["cost_nice" if g["cost"] == "nice" else "cost_coal"] += 1
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
        slices = tally(run_one(m), m.get("cost", True))
        def red(c):
            return c["value_coal"] + c["not_impl"] + c["unrepr"] + c["cost_coal"] + c["reject_coal"]
        agg_red = sum(red(c) for c in slices.values())
        mark = "🎁" if agg_red == 0 else "🪨"
        print(f"  {mark} {m['label']}  (version≤{m['version']}, tiers={','.join(m['tiers'])}, cost={m.get('cost', True)})")
        for (tier, version, prov), c in sorted(slices.items()):
            bits = []
            if c["value_total"]:
                bits.append(f"value {c['value_nice']}/{c['value_total']}")
                if c["value_coal"]: bits.append(f"{c['value_coal']} val-coal")
                if c["not_impl"]: bits.append(f"{c['not_impl']} not-impl")
                if c["unrepr"]: bits.append(f"{c['unrepr']} unrepr")
            if c["cost_graded"]:
                bits.append(f"cost {c['cost_nice']}/{c['cost_graded']}")
            if c["reject_total"]:
                bits.append(f"reject {c['reject_nice']}/{c['reject_total']}")
            print(f"      {tier}/{version}/{prov}: " + " · ".join(bits))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
