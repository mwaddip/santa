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


def grade(actual, expected, claims_cost):
    """Per-entry verdict, mirroring the Dasher e2e precedence. Coverage gaps (runner returned
    not-implemented / unrepresentable) take precedence — the runner didn't engage with the op,
    whether the vector is accept or reject. Otherwise reject vectors (expected errored) get one
    verdict; accept vectors get independent value + cost verdicts (cost only when claimed)."""
    if actual is None:
        return {"kind": "accept", "value": "value", "cost": "n/a"}  # totality breach -> coal
    aerr, eerr = actual.get("error"), expected.get("error")
    if aerr == "not-implemented":
        return {"kind": "coverage", "tag": "not-implemented"}  # coverage > accept/reject classification
    if aerr == "unrepresentable":
        return {"kind": "coverage", "tag": "unrepresentable"}
    if eerr == "errored":  # reject vector — the runner engaged; did it reject identically?
        return {"kind": "reject", "verdict": "nice" if aerr == "errored" else "reject"}
    # accept vector
    if aerr is None and structural_equal(actual.get("value"), expected.get("value")):
        value = "nice"
    else:
        value = "value"
    if not claims_cost or value != "nice":
        cost = "n/a"  # cost not claimed, or value didn't evaluate -> cost moot
    else:
        cost = "nice" if actual.get("cost") == expected.get("cost") else "cost"
    return {"kind": "accept", "value": value, "cost": cost}
