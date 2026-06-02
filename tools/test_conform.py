"""Self-test for tools/conform.py discovery/selection (plain asserts; run with .venv/bin/python)."""
import sys
from conform import parse_relpath, select, VERSIONS


def chk(name, got, want):
    ok = got == want
    print(f"  [{'OK' if ok else 'XX'}] {name}: got={got!r} want={want!r}")
    return ok


# (relpath, tier, version, provenance, op)
SAMPLE = [
    ("eval/v5/spec/a.json",     "eval", "v5", "spec",     "a"),
    ("eval/v6/spec/b.json",     "eval", "v6", "spec",     "b"),
    ("eval/v5/authored/c.json", "eval", "v5", "authored", "c"),
    ("wire/v5/spec/d.json",     "wire", "v5", "spec",     "d"),
]

cases = [
    ("parse", parse_relpath("eval/v5/spec/plus.json"), ("eval", "v5", "spec", "plus")),
    ("version order", VERSIONS.index("v5") < VERSIONS.index("v6"), True),
    ("select v6 eval", sorted(s[0] for s in select(SAMPLE, "v6", ["eval"])),
     sorted(["eval/v5/spec/a.json", "eval/v5/authored/c.json", "eval/v6/spec/b.json"])),
    ("select v5 eval", sorted(s[0] for s in select(SAMPLE, "v5", ["eval"])),
     sorted(["eval/v5/spec/a.json", "eval/v5/authored/c.json"])),
    ("authored auto-included", any(s[3] == "authored" for s in select(SAMPLE, "v5", ["eval"])), True),
]
ok = all(chk(n, g, w) for n, g, w in cases)
print("=== conform.py discovery: ALL OK ===" if ok else "=== conform.py discovery: FAILURES ===")
sys.exit(0 if ok else 1)
