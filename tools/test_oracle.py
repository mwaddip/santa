"""Prove a comparator reproduces the SANTA verdict-oracle (oracle/*.json) — §6 made executable,
the contract for 'agrees'. Run with .venv/bin/python (or any python3 — pure stdlib).

This proves tools/compare.py (the canonical Python reference, to be ported to the Rust
`santa-check`) reproduces every blessed verdict. The Rust / TS (Dasher) / Scala (Harness)
comparators run the same oracle and must agree — that is what keeps the implementations from
drifting on §5/§6."""
import json, os, sys, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare import grade

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
cases = []
for f in sorted(glob.glob(os.path.join(ROOT, "oracle", "*.json"))):
    cases += json.load(open(f))["cases"]
if not cases:
    sys.exit("no oracle cases found under oracle/*.json")

ok = True
for c in cases:
    got = grade(c["actual"], c["expected"], c["claims_cost"])
    passed = got == c["verdict"]
    ok = ok and passed
    print(f"  [{'OK' if passed else 'XX'}] {c['name']}: got={got} want={c['verdict']}")
print(f"=== oracle vs compare.py: {len(cases)} cases, {'ALL OK' if ok else 'FAILURES'} ===")
sys.exit(0 if ok else 1)
