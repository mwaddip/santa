#!/usr/bin/env python3
"""Validate SANTA's committed vectors against the eval-tier JSON Schemas.

The schemas (santa-eval.vector.schema.json / santa-eval.actuals.schema.json) are the
machine-checkable half of the runner contract (docs/contract/runner-contract.md). This
script is their regression gate: it confirms both schemas are valid Draft 2020-12 and
that every committed vector under vectors/eval/ validates against the vector schema. It
also spot-checks the actuals schema's load-bearing asymmetries (cost-as-number,
Long-as-string) so a future edit can't silently loosen them.

Run (needs `jsonschema`; use a venv to avoid touching system Python):
    python3 -m venv .venv && .venv/bin/pip install jsonschema
    .venv/bin/python schema/validate.py

Exit code 0 = all good; 1 = at least one failure (suitable as a CI gate).
"""
import json
import glob
import os
import sys

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
VEC_PATH = os.path.join(HERE, "santa-eval.vector.schema.json")
ACT_PATH = os.path.join(HERE, "santa-eval.actuals.schema.json")
VECTORS = os.path.join(ROOT, "vectors", "eval", "**", "*.json")


def main() -> int:
    vec_schema = json.load(open(VEC_PATH))
    act_schema = json.load(open(ACT_PATH))
    errs = 0

    # 1. Both schemas are valid Draft 2020-12.
    for name, sch in [("vector", vec_schema), ("actuals", act_schema)]:
        try:
            Draft202012Validator.check_schema(sch)
            print(f"[meta] {name} schema: VALID Draft 2020-12")
        except Exception as e:  # noqa: BLE001 — surface any metaschema error
            print(f"[meta] {name} schema: INVALID — {e}")
            errs += 1

    # Registry so the actuals schema resolves its cross-$id ref to the vector schema
    # (it reuses the svalue/stype $defs by relative filename).
    reg = Registry().with_resources([
        (vec_schema["$id"], Resource.from_contents(vec_schema)),
        (act_schema["$id"], Resource.from_contents(act_schema)),
        ("santa-eval.vector.schema.json", Resource.from_contents(vec_schema)),
    ])
    vec_validator = Draft202012Validator(vec_schema, registry=reg)
    act_validator = Draft202012Validator(act_schema, registry=reg)

    # 2. Every committed vector validates against the vector schema.
    files = sorted(glob.glob(VECTORS, recursive=True))
    print(f"\n[corpus] validating {len(files)} committed vectors:")
    ok = 0
    for f in files:
        doc = json.load(open(f))
        es = sorted(vec_validator.iter_errors(doc), key=lambda e: list(e.path))
        if es:
            errs += 1
            print(f"  FAIL {os.path.basename(f)}")
            for e in es[:4]:
                loc = "/".join(str(p) for p in e.path) or "<root>"
                print(f"      at [{loc}]: {e.message[:160]}")
        else:
            ok += 1
    print(f"[corpus] {ok}/{len(files)} valid")

    # 2b. Path ⟺ envelope consistency: the self-describing taxonomy path must agree with the in-data
    # catalogue, so the fast path-selector and the self-contained envelope can never silently drift.
    VERSION_ACTIVATED = {"v5": 2, "v6": 3}
    TIER_SCHEMA_PREFIX = {"eval": "santa-eval/"}
    print("\n[catalogue] path ⟺ envelope guard:")
    guard_errs = 0
    for f in files:
        rel = os.path.relpath(f, os.path.join(ROOT, "vectors"))  # <tier>/<version>/<provenance>/<op>.json
        parts = rel.split(os.sep)
        if len(parts) != 4:
            guard_errs += 1; print(f"  [WRONG] {rel}: not <tier>/<version>/<provenance>/<op>.json"); continue
        tier, version, prov, _leaf = parts
        doc = json.load(open(f))
        if not str(doc.get("schema", "")).startswith(TIER_SCHEMA_PREFIX.get(tier, "\0")):
            guard_errs += 1; print(f"  [WRONG] {rel}: schema {doc.get('schema')!r} != tier {tier!r}")
        is_authored = str(doc.get("source", "")).startswith("santa:authored")
        if (prov == "authored") != is_authored:
            guard_errs += 1; print(f"  [WRONG] {rel}: provenance {prov!r} vs source {doc.get('source')!r}")
        want = VERSION_ACTIVATED.get(version)
        off = [e["name"] for e in doc["entries"] if e["version"]["activated"] != want]
        if want is None or off:
            guard_errs += 1; print(f"  [WRONG] {rel}: version {version!r} wants activated={want}, off: {off[:3]}")
    errs += guard_errs
    print(f"  [OK] all {len(files)} paths agree with their envelopes" if guard_errs == 0
          else f"  {guard_errs} path/envelope mismatch(es)")

    # 3. Actuals-schema guards: the contract's load-bearing asymmetries must stay enforced.
    checks = [
        ("success", {"x#0": {"value": {"kind": "Int", "value": 6}, "cost": 36, "error": None}}, True),
        ("errored", {"x#0": {"value": None, "cost": None, "error": "errored"}}, True),
        ("success w/ string cost rejected", {"x#0": {"value": {"kind": "Int", "value": 6}, "cost": "36", "error": None}}, False),
        ("errored w/ non-null cost rejected", {"x#0": {"value": None, "cost": 5, "error": "errored"}}, False),
        ("Long as string", {"x#0": {"value": {"kind": "Long", "value": "9000000000"}, "cost": 1, "error": None}}, True),
        ("success w/ null cost accepted (cost-not-claimed)", {"x#0": {"value": {"kind": "Int", "value": 6}, "cost": None, "error": None}}, True),
        ("Long as number rejected", {"x#0": {"value": {"kind": "Long", "value": 9000000000}, "cost": 1, "error": None}}, False),
        ("Int as string rejected", {"x#0": {"value": {"kind": "Int", "value": "42"}, "cost": 1, "error": None}}, False),
    ]
    print("\n[actuals] asymmetry guards:")
    for label, doc, want_valid in checks:
        got_valid = not list(act_validator.iter_errors(doc))
        good = got_valid == want_valid
        errs += 0 if good else 1
        print(f"  [{'OK' if good else 'WRONG'}] {label}: valid={got_valid} (want {want_valid})")

    print(f"\n=== {'ALL CHECKS PASSED' if errs == 0 else str(errs) + ' FAILURE(S)'} ===")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
