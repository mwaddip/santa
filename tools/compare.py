"""Shared comparator for the SANTA conformance orchestrator.

structural_equal implements runner-contract §5 (objects key-order-INsensitive,
arrays order-SENSITIVE, numbers numeric, strings exact, null==only-null, bool is
not int). categorize mirrors the Dasher e2e: nice / value / cost / not-implemented
/ unrepresentable / reject. Pure — no I/O."""


def structural_equal(a, b):
    if type(a) is bool or type(b) is bool:
        return type(a) is bool and type(b) is bool and a == b
    if a is None or b is None:
        return a is None and b is None
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return a == b
    if isinstance(a, str) and isinstance(b, str):
        return a == b
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(structural_equal(x, y) for x, y in zip(a, b))
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(structural_equal(a[k], b[k]) for k in a)
    return False


def categorize(actual, expected):
    """One of: nice, value, cost, not-implemented, unrepresentable, reject."""
    if actual is None:
        return "value"  # totality says this can't happen; treat a missing actual as coal
    if structural_equal(actual, expected):
        return "nice"
    aerr, eerr = actual.get("error"), expected.get("error")
    if aerr == "not-implemented":
        return "not-implemented"
    if aerr == "unrepresentable":
        return "unrepresentable"
    if eerr == "errored":
        return "reject"  # JVM rejects this input; runner did not reject identically
    if (aerr is None and eerr is None
            and structural_equal(actual.get("value"), expected.get("value"))
            and actual.get("cost") != expected.get("cost")):
        return "cost"
    return "value"
