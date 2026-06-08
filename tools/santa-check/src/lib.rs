//! santa-check — the canonical SANTA comparator (runner-contract §5 structural equality + §6
//! grading). Pure JSON in/out, no sigma-rust dependency, so a Rust runner (blitzen) can depend on
//! it for its own self-test. Mirrors tools/compare.py; proven against oracle/*.json (tests/oracle.rs).
use serde_json::{json, Number, Value};

/// §5 structural equality: objects key-order-insensitive, arrays order-sensitive, numbers numeric,
/// strings exact, null == only null, bool is NOT int.
pub fn structural_equal(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Bool(x), Value::Bool(y)) => x == y,
        (Value::Bool(_), _) | (_, Value::Bool(_)) => false, // bool only equals bool
        (Value::Null, Value::Null) => true,
        (Value::Null, _) | (_, Value::Null) => false, // null only equals null
        (Value::Number(x), Value::Number(y)) => num_eq(x, y),
        (Value::String(x), Value::String(y)) => x == y,
        (Value::Array(x), Value::Array(y)) => {
            x.len() == y.len() && x.iter().zip(y).all(|(p, q)| structural_equal(p, q))
        }
        (Value::Object(x), Value::Object(y)) => {
            x.len() == y.len()
                && x.iter()
                    .all(|(k, v)| y.get(k).is_some_and(|w| structural_equal(v, w)))
        }
        _ => false,
    }
}

/// Numeric equality across int/float reprs (mirrors Python ==), exact for the i64/u64 the corpus uses.
fn num_eq(x: &Number, y: &Number) -> bool {
    if let (Some(a), Some(b)) = (x.as_i64(), y.as_i64()) {
        return a == b;
    }
    if let (Some(a), Some(b)) = (x.as_u64(), y.as_u64()) {
        return a == b;
    }
    matches!((x.as_f64(), y.as_f64()), (Some(a), Some(b)) if a == b)
}

fn err_is(v: &Value, tag: &str) -> bool {
    v.get("error").and_then(Value::as_str) == Some(tag)
}

/// §6 per-entry verdict, as a JSON object. Coverage (not-implemented) takes
/// precedence — the runner didn't engage the op, accept or reject. Otherwise reject vectors
/// (expected errored) get one verdict; accept vectors get independent value + cost verdicts
/// (cost graded only when claimed and value is nice). A null `actual` is a totality breach -> coal.
/// A `panicked` actual (a runner crash) is coal unconditionally, before any expected check.
pub fn grade(actual: &Value, expected: &Value, claims_cost: bool) -> Value {
    if actual.is_null() {
        return json!({"kind": "accept", "value": "value", "cost": "n/a"});
    }
    if err_is(actual, "panicked") {
        // A runner that crashed on this entry. Unconditional coal — even against a
        // reject-expected vector (a crash is not a clean rejection), which is exactly why
        // this is a distinct tag and not a reuse of `errored` (errored grades nice on a
        // reject). The diagnostic `note` lives on the actual and is surfaced downstream
        // (conform -> results.json), so the verdict stays a pure classification.
        return json!({"kind": "panicked"});
    }
    if err_is(actual, "not-implemented") {
        return json!({"kind": "coverage", "tag": "not-implemented"});
    }
    if err_is(expected, "errored") {
        let v = if err_is(actual, "errored") { "nice" } else { "reject" };
        return json!({"kind": "reject", "verdict": v});
    }
    // accept vector — independent value + cost verdicts
    let actual_ok = actual.get("error").map_or(true, Value::is_null);
    let value = if actual_ok
        && structural_equal(
            actual.get("value").unwrap_or(&Value::Null),
            expected.get("value").unwrap_or(&Value::Null),
        ) {
        "nice"
    } else {
        "value"
    };
    let cost = if !claims_cost || value != "nice" {
        "n/a"
    } else if structural_equal(
        actual.get("cost").unwrap_or(&Value::Null),
        expected.get("cost").unwrap_or(&Value::Null),
    ) {
        "nice"
    } else {
        "cost"
    };
    json!({"kind": "accept", "value": value, "cost": cost})
}

/// §6 wire-tier verdict: a single round-trip judgment. The blessed expected IS the entry's own
/// `bytes_hex` (round-trip to self), so grading is `bytes_hex` lower-case exact-equality + `error`
/// null -> a `roundtrip` nice/differ verdict — no value/cost split (the wire tier has no cost
/// dimension). `not-implemented` (no serializer for this kind) and `panicked` (a runner crash) reuse
/// the eval `coverage`/`panicked` verdict shapes, so conform tallies them uniformly across tiers; a
/// non-null `errored` / a byte mismatch / a null actual (totality breach) is differ (coal).
pub fn grade_wire(actual: &Value, expected: &Value) -> Value {
    if actual.is_null() {
        return json!({"kind": "roundtrip", "verdict": "differ"});
    }
    if err_is(actual, "panicked") {
        return json!({"kind": "panicked"});
    }
    if err_is(actual, "not-implemented") {
        return json!({"kind": "coverage", "tag": "not-implemented"});
    }
    let ok = actual.get("error").map_or(true, Value::is_null)
        && hex_eq(actual.get("bytes_hex"), expected.get("bytes_hex"));
    json!({"kind": "roundtrip", "verdict": if ok { "nice" } else { "differ" }})
}

/// Lower-case exact hex equality (the wire grade's byte comparison). Either side absent/non-string
/// (e.g. the null `bytes_hex` carried by an errored actual) -> not equal.
fn hex_eq(a: Option<&Value>, b: Option<&Value>) -> bool {
    matches!((a.and_then(Value::as_str), b.and_then(Value::as_str)),
        (Some(x), Some(y)) if x.eq_ignore_ascii_case(y))
}

/// §6 transaction-tier verdict: two independent dimensions — `valid` (always graded) + `cost`
/// (graded only when BOTH sides declare one). Coverage/panicked precede all grading.
///
/// Precedence:
/// 1. `panicked` → coal unconditionally (before expected check).
/// 2. `not-implemented` → coverage (NOT coal).
/// 3. Accept vector (`expected.valid == true`):
///    - `actual.error == null && actual.valid == true` → valid=nice; else → valid=value (coal).
///    - Cost graded iff `expected.cost != null && actual.cost != null`: nice|cost depending on equality.
/// 4. Reject vector (`expected.valid == false`):
///    - `actual.valid == false && actual.error == null` → valid=nice (clean rejection).
///    - `actual.error == "errored"` → valid=value (coal). Differs from the eval reject arm BY DESIGN:
///      eval rejection manifests as an error (errored IS the clean reject); the tx tier explicitly
///      separates clean-reject (valid:false) from failed-verdict (errored, i.e. valid:null).
///    - `actual.valid == true` → coal.
///    - Cost not graded on reject vectors.
///
/// Returns a verdict object in the same vocabulary as `grade` and `grade_wire` so `conform` can
/// tally all tiers uniformly:
/// - `{"kind": "panicked"}`
/// - `{"kind": "coverage", "tag": "not-implemented"}`
/// - `{"kind": "transaction", "valid": "nice"|"value", "cost": "nice"|"cost"|"n/a"}`
pub fn grade_transaction(actual: &Value, expected: &Value) -> Value {
    if err_is(actual, "panicked") {
        return json!({"kind": "panicked"});
    }
    if err_is(actual, "not-implemented") {
        return json!({"kind": "coverage", "tag": "not-implemented"});
    }

    let expected_valid = expected
        .get("valid")
        .and_then(Value::as_bool)
        .unwrap_or(false);

    if expected_valid {
        // Accept vector: need actual.error == null && actual.valid == true.
        let actual_clean = actual.get("error").map_or(true, Value::is_null);
        let actual_valid = actual.get("valid").and_then(Value::as_bool).unwrap_or(false);
        let valid = if actual_clean && actual_valid { "nice" } else { "value" };

        // Cost graded only when both sides declare it (non-null).
        let expected_cost = expected.get("cost").filter(|v| !v.is_null());
        let actual_cost = actual.get("cost").filter(|v| !v.is_null());
        let cost = match (valid, expected_cost, actual_cost) {
            ("nice", Some(ec), Some(ac)) => {
                if structural_equal(ec, ac) { "nice" } else { "cost" }
            }
            _ => "n/a",
        };

        json!({"kind": "transaction", "valid": valid, "cost": cost})
    } else {
        // Reject vector: only a clean valid:false (error:null) → nice; anything else → coal.
        // Differs from the eval reject arm BY DESIGN — eval rejection manifests as an error
        // (errored IS the clean reject); the tx tier explicitly separates clean-reject
        // (valid:false) from failed-verdict (errored, i.e. valid:null).
        // Cost is not graded on reject vectors.
        let actual_valid_false =
            actual.get("error").map_or(true, Value::is_null)
            && actual.get("valid").and_then(Value::as_bool) == Some(false);
        let valid = if actual_valid_false { "nice" } else { "value" };
        json!({"kind": "transaction", "valid": valid, "cost": "n/a"})
    }
}

/// §6 block-tier verdict: three chained dimensions — `valid` (always graded), `post_digest`
/// (graded only on the accept arm when valid is nice), and `cost` (graded only on the accept arm
/// when valid=nice AND post_digest=nice AND both sides declare one — the tx tier's valid-gate,
/// extended one level). Coverage/panicked precede all grading.
///
/// Precedence:
/// 1. `panicked` → coal unconditionally (before expected check).
/// 2. `not-implemented` → coverage (NOT coal).
/// 3. Accept vector (`expected.valid == true`):
///    - `actual.error == null && actual.valid == true` → valid=nice; else → valid=valid (coal).
///    - post_digest graded iff valid=nice: nice when actual.post_digest == expected.post_digest
///      (both non-null), else coal marker "post_digest"; n/a when valid isn't nice.
///    - Cost graded iff valid=nice AND post_digest=nice AND both sides declare cost (non-null):
///      nice|cost depending on structural equality; n/a otherwise. Chains valid → post_digest → cost.
/// 4. Reject vector (`expected.valid == false`):
///    - `actual.valid == false && actual.error == null` → valid=nice (clean rejection).
///    - `actual.error == "errored"` → valid=valid (coal). BY DESIGN mirrors the tx tier:
///      the block tier explicitly separates clean-reject (valid:false, error:null) from
///      failed-verdict (error:errored, valid:null).
///    - `actual.valid == true` → coal.
///    - post_digest and cost are both n/a on reject vectors.
///
/// Returns a verdict object in the same vocabulary as `grade_transaction` and `grade_wire`:
/// - `{"kind": "panicked"}`
/// - `{"kind": "coverage", "tag": "not-implemented"}`
/// - `{"kind": "block", "valid": "nice"|"valid", "post_digest": "nice"|"post_digest"|"n/a", "cost": "nice"|"cost"|"n/a"}`
pub fn grade_block(actual: &Value, expected: &Value) -> Value {
    if err_is(actual, "panicked") {
        return json!({"kind": "panicked"});
    }
    if err_is(actual, "not-implemented") {
        return json!({"kind": "coverage", "tag": "not-implemented"});
    }

    let expected_valid = expected
        .get("valid")
        .and_then(Value::as_bool)
        .unwrap_or(false);

    if expected_valid {
        // Accept vector: need actual.error == null && actual.valid == true.
        let actual_clean = actual.get("error").map_or(true, Value::is_null);
        let actual_valid = actual.get("valid").and_then(Value::as_bool).unwrap_or(false);
        let valid = if actual_clean && actual_valid { "nice" } else { "valid" };

        // post_digest graded only when valid is nice.
        let digest = if valid != "nice" {
            "n/a"
        } else {
            let exp_digest = expected.get("post_digest").filter(|v| !v.is_null());
            let act_digest = actual.get("post_digest").filter(|v| !v.is_null());
            match (exp_digest, act_digest) {
                (Some(e), Some(a)) if structural_equal(e, a) => "nice",
                _ => "post_digest",
            }
        };

        // Cost graded only when valid=nice, post_digest=nice, and both sides declare it (non-null).
        // Chains valid → post_digest → cost: a failing upstream dimension suppresses the downstream ones.
        let expected_cost = expected.get("cost").filter(|v| !v.is_null());
        let actual_cost = actual.get("cost").filter(|v| !v.is_null());
        let cost = match (valid, digest, expected_cost, actual_cost) {
            ("nice", "nice", Some(ec), Some(ac)) => {
                if structural_equal(ec, ac) { "nice" } else { "cost" }
            }
            _ => "n/a",
        };

        json!({"kind": "block", "valid": valid, "post_digest": digest, "cost": cost})
    } else {
        // Reject vector: only a clean valid:false (error:null) → nice; anything else → coal.
        // Cost and post_digest are not graded on reject vectors.
        let actual_valid_false = actual.get("error").map_or(true, Value::is_null)
            && actual.get("valid").and_then(Value::as_bool) == Some(false);
        let valid = if actual_valid_false { "nice" } else { "valid" };
        json!({"kind": "block", "valid": valid, "post_digest": "n/a", "cost": "n/a"})
    }
}

#[cfg(test)]
mod tx_tests {
    use super::*;
    use serde_json::json;

    fn exp_accept() -> Value {
        json!({"valid": true, "cost": null, "reason": null})
    }
    fn exp_accept_cost(c: i64) -> Value {
        json!({"valid": true, "cost": c, "reason": null})
    }
    fn exp_reject() -> Value {
        json!({"valid": false, "cost": null, "reason": "some reason"})
    }

    // ── accept vectors ────────────────────────────────────────────────────────

    #[test]
    fn grade_transaction_accept_no_cost_nice() {
        // accept + {valid:true, cost:null, error:null} => valid=nice, cost=n/a
        let actual = json!({"valid": true, "cost": null, "error": null});
        let v = grade_transaction(&actual, &exp_accept());
        assert_eq!(v, json!({"kind": "transaction", "valid": "nice", "cost": "n/a"}));
    }

    #[test]
    fn grade_transaction_accept_cost_match_nice() {
        // accept(cost declared) + {valid:true, cost:<match>} => nice on both dims
        let actual = json!({"valid": true, "cost": 1000, "error": null});
        let v = grade_transaction(&actual, &exp_accept_cost(1000));
        assert_eq!(v, json!({"kind": "transaction", "valid": "nice", "cost": "nice"}));
    }

    #[test]
    fn grade_transaction_accept_cost_mismatch() {
        // accept(cost declared) + {valid:true, cost:<mismatch>} => valid=nice, cost=cost (coal)
        let actual = json!({"valid": true, "cost": 999, "error": null});
        let v = grade_transaction(&actual, &exp_accept_cost(1000));
        assert_eq!(v, json!({"kind": "transaction", "valid": "nice", "cost": "cost"}));
    }

    #[test]
    fn grade_transaction_accept_valid_false_is_coal() {
        // accept + {valid:false} => coal (valid mismatch)
        let actual = json!({"valid": false, "cost": null, "error": null});
        let v = grade_transaction(&actual, &exp_accept());
        assert_eq!(v, json!({"kind": "transaction", "valid": "value", "cost": "n/a"}));
    }

    #[test]
    fn grade_transaction_accept_errored_is_coal() {
        // accept + {error:"errored"} => coal
        let actual = json!({"valid": null, "cost": null, "error": "errored"});
        let v = grade_transaction(&actual, &exp_accept());
        assert_eq!(v, json!({"kind": "transaction", "valid": "value", "cost": "n/a"}));
    }

    #[test]
    fn grade_transaction_accept_panicked_is_coal() {
        // accept + {error:"panicked"} => panicked kind (coal unconditional)
        let actual = json!({"valid": null, "cost": null, "error": "panicked", "note": "OOM"});
        let v = grade_transaction(&actual, &exp_accept());
        assert_eq!(v, json!({"kind": "panicked"}));
    }

    #[test]
    fn grade_transaction_accept_not_impl_is_coverage() {
        // accept + {error:"not-implemented"} => coverage (NOT coal)
        let actual = json!({"valid": null, "cost": null, "error": "not-implemented"});
        let v = grade_transaction(&actual, &exp_accept());
        assert_eq!(v, json!({"kind": "coverage", "tag": "not-implemented"}));
    }

    #[test]
    fn grade_transaction_accept_cost_declared_but_actual_null_is_na() {
        // cost declared in expected but actual.cost is null => cost not graded
        let actual = json!({"valid": true, "cost": null, "error": null});
        let v = grade_transaction(&actual, &exp_accept_cost(500));
        assert_eq!(v, json!({"kind": "transaction", "valid": "nice", "cost": "n/a"}));
    }

    // ── reject vectors ────────────────────────────────────────────────────────

    #[test]
    fn grade_transaction_reject_clean_nice() {
        // reject + {valid:false, error:null} (different reason text) => nice (reason not matched)
        let actual = json!({"valid": false, "cost": null, "error": null, "reason": "different reason"});
        let v = grade_transaction(&actual, &exp_reject());
        assert_eq!(v, json!({"kind": "transaction", "valid": "nice", "cost": "n/a"}));
    }

    #[test]
    fn grade_transaction_reject_valid_true_is_coal() {
        // reject + {valid:true} => coal
        let actual = json!({"valid": true, "cost": null, "error": null});
        let v = grade_transaction(&actual, &exp_reject());
        assert_eq!(v, json!({"kind": "transaction", "valid": "value", "cost": "n/a"}));
    }

    #[test]
    fn grade_transaction_reject_panicked_is_coal() {
        // reject + {error:"panicked"} => coal (unconditional, before expected check)
        let actual = json!({"valid": null, "cost": null, "error": "panicked", "note": "crash"});
        let v = grade_transaction(&actual, &exp_reject());
        assert_eq!(v, json!({"kind": "panicked"}));
    }

    #[test]
    fn grade_transaction_reject_errored_is_coal() {
        // reject + {error:"errored"} => coal (valid=value). BY DESIGN: differs from the eval
        // reject arm where errored IS the clean reject; the tx tier separates clean-reject
        // (valid:false) from failed-verdict (errored = runner never reached a verdict).
        let actual = json!({"valid": null, "cost": null, "error": "errored"});
        let v = grade_transaction(&actual, &exp_reject());
        assert_eq!(v, json!({"kind": "transaction", "valid": "value", "cost": "n/a"}));
    }

    #[test]
    fn grade_transaction_reject_not_impl_is_coverage() {
        // reject + {error:"not-implemented"} => coverage (not coal, before expected check)
        let actual = json!({"valid": null, "cost": null, "error": "not-implemented"});
        let v = grade_transaction(&actual, &exp_reject());
        assert_eq!(v, json!({"kind": "coverage", "tag": "not-implemented"}));
    }
}
