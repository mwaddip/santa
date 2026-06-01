#!/usr/bin/env python3
"""SANTA conformance orchestrator. Presence-as-state over runners/*/: each dir with a
valid runner.json + executable santa-run is run against the blessed corpus; one shared
comparator (compare.py) decides nice/coal; a side-by-side table is printed. See
docs/contract/runner-integration.md."""
import json, os, subprocess, sys, tempfile, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import categorize

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUNNERS_DIR = os.path.join(ROOT, "runners")
VECTORS = os.path.join(ROOT, "vectors", "eval")
CATEGORIES = ["value", "cost", "not-implemented", "unrepresentable", "reject"]


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


def run_one(m, outroot):
    """Run a runner over each scoped version dir; return {version: {file: actuals_obj}}."""
    res = {}
    for ver in m["scope"]:
        vdir = os.path.join(VECTORS, ver)
        if not os.path.isdir(vdir):
            continue
        odir = os.path.join(outroot, m["name"], ver)
        os.makedirs(odir, exist_ok=True)
        subprocess.run([m["_run"], "-", vdir, odir], check=True)
        res[ver] = {}
        for fn in sorted(os.listdir(odir)):
            if fn.endswith(".json"):
                res[ver][fn] = json.load(open(os.path.join(odir, fn)))
    return res


def tally(m, actuals):
    """Compare actuals vs blessed expected; return per-version counts + per-op verdicts."""
    counts, ops = {}, {}
    for ver, byfile in actuals.items():
        c = {"total": 0, "nice": 0, **{k: 0 for k in CATEGORIES}}
        ops[ver] = {}
        for fn, act in byfile.items():
            vec = json.load(open(os.path.join(VECTORS, ver, fn)))
            op_nice = True
            for e in vec["entries"]:
                cat = categorize(act.get(e["name"]), e["expected"])
                c["total"] += 1
                if cat == "nice":
                    c["nice"] += 1
                else:
                    c[cat] += 1
                    op_nice = False
            ops[ver][fn] = "nice" if op_nice else "coal"
        counts[ver] = c
    return counts, ops


def main(argv):
    matrix = "--matrix" in argv
    runners = discover()
    if not runners:
        print("no runners under runners/*/ (need runner.json + executable santa-run)")
        return 1
    outroot = tempfile.mkdtemp(prefix="santa-conform-")
    results = {}
    for m in runners:
        print(f"running {m['name']} …", file=sys.stderr)
        results[m["name"]] = (m, tally(m, run_one(m, outroot)))

    print(f"\n=== SANTA conformance · {len(runners)} runner(s) ===")
    for name, (m, (counts, _ops)) in results.items():
        for ver, c in counts.items():
            red = sum(c[k] for k in CATEGORIES)
            mark = "🎁" if red == 0 else "🪨"
            brk = " ".join(f"{c[k]}{k[0]}" for k in CATEGORIES if c[k])
            line = f"  {mark} {m['label']:<40} {ver}: {c['nice']}/{c['total']} nice"
            print(line + (f" · RED {red} ({brk})" if red else ""))

    if matrix:
        cols = [name for name in results if "v5" in results[name][0]["scope"]]
        allops = sorted({fn for c in cols for fn in results[c][1][1].get("v5", {})})
        print("\n  per-op (v5)             " + "  ".join(f"{c[:10]:>10}" for c in cols))
        for fn in allops:
            cells = []
            for c in cols:
                v = results[c][1][1].get("v5", {}).get(fn)
                cells.append("        ✓ " if v == "nice" else ("        ✗ " if v == "coal" else "        – "))
            print(f"  {fn[:22]:<22}" + "".join(cells))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
