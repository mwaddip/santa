"""Self-test for tools/compare.py (plain asserts; run with .venv/bin/python)."""
import sys
from compare import structural_equal, categorize, grade


def chk(name, got, want):
    ok = got == want
    print(f"  [{'OK' if ok else 'XX'}] {name}: got={got!r} want={want!r}")
    return ok


V_INT6 = {"value": {"kind": "Int", "value": 6}, "cost": 36, "error": None}
ERRORED = {"value": None, "cost": None, "error": "errored"}

cases = [
    # structural_equal: §5 — objects key-order-INsensitive, arrays order-SENSITIVE, null==only-null
    ("eq objects key-order", structural_equal({"a": 1, "b": 2}, {"b": 2, "a": 1}), True),
    ("eq arrays order-sensitive", structural_equal([1, 2], [2, 1]), False),
    ("eq null only null", structural_equal(None, 0), False),
    ("eq Long-as-string", structural_equal("3", "3"), True),
    ("eq str!=num kinds", structural_equal("3", 3), False),
    ("eq bool not int", structural_equal(True, 1), False),
    # categorize
    ("nice", categorize(V_INT6, V_INT6), "nice"),
    ("value mismatch", categorize({"value": {"kind": "Int", "value": 7}, "cost": 36, "error": None}, V_INT6), "value"),
    ("cost mismatch", categorize({"value": {"kind": "Int", "value": 6}, "cost": 37, "error": None}, V_INT6), "cost"),
    ("not-implemented", categorize({"value": None, "cost": None, "error": "not-implemented"}, V_INT6), "not-implemented"),
    ("unrepresentable", categorize({"value": None, "cost": None, "error": "unrepresentable"}, V_INT6), "unrepresentable"),
    ("reject-nice", categorize(ERRORED, ERRORED), "nice"),
    ("reject divergence (accepted)", categorize(V_INT6, ERRORED), "reject"),
    # grade(): independent value + cost verdicts; cost gated on claims_cost
    ("grade nice/nice", grade(V_INT6, V_INT6, True), {"kind": "accept", "value": "nice", "cost": "nice"}),
    ("grade nice/cost-coal", grade({"value": {"kind": "Int", "value": 6}, "cost": 37, "error": None}, V_INT6, True),
     {"kind": "accept", "value": "nice", "cost": "cost"}),
    ("grade cost not claimed -> n/a", grade({"value": {"kind": "Int", "value": 6}, "cost": 37, "error": None}, V_INT6, False),
     {"kind": "accept", "value": "nice", "cost": "n/a"}),
    ("grade cost:null while claimed -> coal", grade({"value": {"kind": "Int", "value": 6}, "cost": None, "error": None}, V_INT6, True),
     {"kind": "accept", "value": "nice", "cost": "cost"}),
    ("grade value coal", grade({"value": {"kind": "Int", "value": 7}, "cost": 36, "error": None}, V_INT6, True),
     {"kind": "accept", "value": "value", "cost": "n/a"}),
    ("grade reject-nice", grade(ERRORED, ERRORED, True), {"kind": "reject", "verdict": "nice"}),
    ("grade reject-divergence", grade(V_INT6, ERRORED, True), {"kind": "reject", "verdict": "reject"}),
]
ok = all(chk(n, g, w) for n, g, w in cases)
print("=== compare.py: ALL OK ===" if ok else "=== compare.py: FAILURES ===")
sys.exit(0 if ok else 1)
