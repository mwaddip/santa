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
    let actual_ok = actual.get("error").is_none_or(Value::is_null);
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

/// §6 wire-tier verdict. TWO arms: round-trip (accept) and reject.
/// REJECT arm — when the vector entry carries `error: "errored"` (the JVM rejects these bytes at
/// deserialize, e.g. an SHeader-typed constant whose SerializerException escapes the soft-fork
/// fallback): a clean `errored` actual is the correct rejection (`reject` nice); anything that
/// PRODUCES bytes (round-trips, error null) is the over-accept (`reject` coal). Mirrors the eval
/// reject arm so conform tallies it uniformly.
/// ROUND-TRIP arm (no `error` marker) — the blessed expected is `expected_bytes_hex` when present
/// (a non-identity round-trip), else the entry's own `bytes_hex` (round-trip to self), so grading is
/// `bytes_hex` lower-case exact-equality + `error` null -> a `roundtrip` nice/differ verdict — no
/// value/cost split (the wire tier has no cost dimension). For a round-trip vector a non-null
/// `errored` / a byte mismatch / a null actual (totality breach) is differ (coal).
/// `not-implemented` (no serializer for this kind) and `panicked` (a runner crash) reuse the eval
/// `coverage`/`panicked` verdict shapes, so conform tallies them uniformly across tiers.
pub fn grade_wire(actual: &Value, expected: &Value) -> Value {
    if actual.is_null() {
        // A reject-expected vector reads a null actual as a totality breach (coal), same as round-trip:
        // a runner that emits nothing did not cleanly reject.
        return json!({"kind": if err_is(expected, "errored") { "reject" } else { "roundtrip" },
            "verdict": "differ"});
    }
    if err_is(actual, "panicked") {
        return json!({"kind": "panicked"});
    }
    if err_is(actual, "not-implemented") {
        return json!({"kind": "coverage", "tag": "not-implemented"});
    }
    // Reject arm: `expected.error == "errored"` marks bytes the JVM REJECTS at deserialize. A clean
    // `errored` actual is the correct rejection (nice); anything that PRODUCES bytes (round-trips,
    // error null) is the over-accept (reject/coal). panicked/not-impl handled above — a crash is not
    // a clean reject. Mirrors the eval reject arm (grade()), so the conform tally's `reject` arm counts it.
    if err_is(expected, "errored") {
        let v = if err_is(actual, "errored") { "nice" } else { "reject" };
        return json!({"kind": "reject", "verdict": v});
    }
    // Non-identity round-trip: a blessed `expected_bytes_hex` (JVM-canonical output that differs
    // from the non-canonical input) overrides the identity default (`bytes_hex`). Absent =>
    // round-trip-to-self, every existing vendored vector unchanged.
    // See docs/specs/wire-roundtrip-nonidentity.md.
    let expected_hex = expected
        .get("expected_bytes_hex")
        .or_else(|| expected.get("bytes_hex"));
    let ok = actual.get("error").is_none_or(Value::is_null)
        && hex_eq(actual.get("bytes_hex"), expected_hex);
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
        let actual_clean = actual.get("error").is_none_or(Value::is_null);
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
            actual.get("error").is_none_or(Value::is_null)
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
        let actual_clean = actual.get("error").is_none_or(Value::is_null);
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
        let actual_valid_false = actual.get("error").is_none_or(Value::is_null)
            && actual.get("valid").and_then(Value::as_bool) == Some(false);
        let valid = if actual_valid_false { "nice" } else { "valid" };
        json!({"kind": "block", "valid": valid, "post_digest": "n/a", "cost": "n/a"})
    }
}

/// Chain tier (value-only): one graded dimension per entry — `nbits` exact for
/// retargeting; `parameters` deep-equal AND `activated_update` exact for voting;
/// `fork_vote_gate`: boolean verdict equality (valid:false = a first-class clean prohibition);
/// its reject arm mirrors voting's (errored is the graded nice outcome on reject vectors).
/// `header_votes`: boolean verdict equality, two-outcome only (no reject arm — errored is
/// always coal). `diagnostic` (vector-side) is never read here. Outcome envelope mirrors the
/// other tiers: coverage (not-implemented) / panicked / {"kind":"chain","value":...}.
///
/// Precedence (contract §4): panicked → coal unconditionally; not-implemented → coverage;
/// then value grading — retargeting: exact nbits match; voting: parameters deep-equal AND
/// activated_update string-equal; fork_vote_gate: boolean verdict equality (reject arm mirrors
/// voting); header_votes: boolean verdict equality, no reject arm (errored always coal).
/// errored where a value is expected is coal. On voting and fork_vote_gate reject
/// vectors (`expected.error == "errored"`) errored is the graded nice outcome.
/// Unknown kind is coal — never a silent pass.
pub fn grade_chain(actual: &Value, entry: &Value) -> Value {
    if err_is(actual, "panicked") {
        return json!({"kind": "panicked"});
    }
    if err_is(actual, "not-implemented") {
        return json!({"kind": "coverage", "tag": "not-implemented"});
    }
    let expected = &entry["expected"];
    let nice = match entry["kind"].as_str() {
        Some("retargeting") => {
            actual["error"].is_null() && actual["nbits"] == expected["nbits"]
        }
        Some("voting") => {
            // Reject vector (contract §4): expected.error == "errored" → nice iff the
            // runner's consensus seam rejected the inputs (errored envelope). A produced
            // value where the JVM throws is coal; panicked never reaches here (precedence).
            if expected["error"] == "errored" {
                actual["error"] == "errored"
            } else {
                actual["error"].is_null()
                    && structural_equal(&actual["parameters"], &expected["parameters"])
                    && actual["activated_update"] == expected["activated_update"]
            }
        }
        Some("fork_vote_gate") => {
            // Reject arm exactly as voting's (contract §4); else boolean verdict equality —
            // valid:false expectations grade a clean prohibition as nice.
            if expected["error"] == "errored" {
                actual["error"] == "errored"
            } else {
                actual["error"].is_null() && actual["valid"] == expected["valid"]
            }
        }
        Some("header_votes") => {
            // Two-outcome only (contract §4): no reject arm exists for this kind.
            // nice iff actual.error == null AND actual.valid == expected.valid.
            // An errored actual on any header_votes entry is coal — falls out naturally
            // since actual["error"] would be non-null.
            actual["error"].is_null() && actual["valid"] == expected["valid"]
        }
        _ => false, // unknown kind in a graded run = red, never a silent pass
    };
    json!({"kind": "chain", "value": if nice { "nice" } else { "value" }})
}

#[cfg(test)]
mod chain_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn grade_chain_retargeting_nice() {
        let e = json!({"kind": "retargeting", "expected": {"nbits": 83972072}});
        let a = json!({"nbits": 83972072, "error": null});
        let g = grade_chain(&a, &e);
        assert_eq!(g["kind"], "chain");
        assert_eq!(g["value"], "nice");
    }

    #[test]
    fn grade_chain_retargeting_mismatch_is_value_coal() {
        let e = json!({"kind": "retargeting", "expected": {"nbits": 83972072}});
        let a = json!({"nbits": 83972073, "error": null});
        assert_eq!(grade_chain(&a, &e)["value"], "value");
    }

    #[test]
    fn grade_chain_voting_compares_table_and_update() {
        // "0000" = the canonical serialized EMPTY ErgoValidationSettingsUpdate (contract §2 pin).
        let e = json!({"kind": "voting",
            "expected": {"parameters": {"table": {"1": 1250, "123": 4}}, "activated_update": "0000"}});
        let nice = json!({"parameters": {"table": {"1": 1250, "123": 4}},
            "activated_update": "0000", "error": null});
        let coal = json!({"parameters": {"table": {"1": 1251, "123": 4}},
            "activated_update": "0000", "error": null});
        assert_eq!(grade_chain(&nice, &e)["value"], "nice");
        assert_eq!(grade_chain(&coal, &e)["value"], "value");
    }

    #[test]
    fn grade_chain_voting_update_mismatch_is_value_coal() {
        // activated_update differs, but table matches -> must be "value"
        let e = json!({"kind": "voting",
            "expected": {"parameters": {"table": {"1": 1250}}, "activated_update": "0000"}});
        let a = json!({"parameters": {"table": {"1": 1250}},
            "activated_update": "02d701990300", "error": null});
        assert_eq!(grade_chain(&a, &e)["value"], "value");
    }

    #[test]
    fn grade_chain_not_implemented_and_panicked() {
        let e = json!({"kind": "retargeting", "expected": {"nbits": 1}});
        let ni = json!({"nbits": null, "error": "not-implemented"});
        let pk = json!({"nbits": null, "error": "panicked", "note": "boom"});
        let er = json!({"nbits": null, "error": "errored"});
        assert_eq!(grade_chain(&ni, &e)["kind"], "coverage");
        assert_eq!(grade_chain(&pk, &e)["kind"], "panicked");
        assert_eq!(grade_chain(&er, &e)["value"], "value"); // errored where success expected = red
    }

    #[test]
    fn grade_chain_retargeting_missing_nbits_with_null_error_is_value_coal() {
        // null nbits with error:null (errored path but error field is null) → value coal, not panic
        let e = json!({"kind": "retargeting", "expected": {"nbits": 83972072}});
        let a = json!({"nbits": null, "error": null});
        assert_eq!(grade_chain(&a, &e)["value"], "value");
    }

    #[test]
    fn grade_chain_unknown_kind_is_value_coal() {
        // Unknown kind in a graded run is coal — never a silent pass (contract §4)
        let e = json!({"kind": "unknown-future-kind", "expected": {"nbits": 1}});
        let a = json!({"nbits": 1, "error": null});
        assert_eq!(grade_chain(&a, &e)["value"], "value");
    }

    #[test]
    fn grade_chain_voting_reject_errored_is_nice() {
        let e = json!({"kind": "voting", "expected": {"error": "errored"}});
        let a = json!({"parameters": null, "activated_update": null,
            "error": "errored", "note": "java.util.NoSuchElementException: key not found: 121"});
        assert_eq!(grade_chain(&a, &e)["value"], "nice");
    }

    #[test]
    fn grade_chain_voting_reject_value_is_coal() {
        let e = json!({"kind": "voting", "expected": {"error": "errored"}});
        let a = json!({"parameters": {"table": {"1": 1}}, "activated_update": "0000", "error": null});
        assert_eq!(grade_chain(&a, &e)["value"], "value");
    }

    #[test]
    fn grade_chain_voting_reject_panicked_stays_panicked_coal() {
        // precedence: panicked → coal before the reject check ever runs (contract §3:
        // throw parity = catching + classifying, never crashing).
        let e = json!({"kind": "voting", "expected": {"error": "errored"}});
        let a = json!({"parameters": null, "activated_update": null, "error": "panicked", "note": "boom"});
        assert_eq!(grade_chain(&a, &e)["kind"], "panicked");
    }

    #[test]
    fn grade_chain_gate_pass_and_prohibition_are_both_nice() {
        let e_pass = json!({"kind": "fork_vote_gate", "expected": {"valid": true}});
        let a_pass = json!({"valid": true, "error": null});
        assert_eq!(grade_chain(&a_pass, &e_pass)["value"], "nice");
        let e_proh = json!({"kind": "fork_vote_gate", "expected": {"valid": false}});
        let a_proh = json!({"valid": false, "error": null});
        assert_eq!(grade_chain(&a_proh, &e_proh)["value"], "nice");
    }

    #[test]
    fn grade_chain_gate_valid_mismatch_is_coal() {
        let e = json!({"kind": "fork_vote_gate", "expected": {"valid": false}});
        let a = json!({"valid": true, "error": null});
        assert_eq!(grade_chain(&a, &e)["value"], "value");
    }

    #[test]
    fn grade_chain_gate_reject_arm_mirrors_voting() {
        let e = json!({"kind": "fork_vote_gate", "expected": {"error": "errored"}});
        let nice = json!({"valid": null, "error": "errored", "note": "java.util.NoSuchElementException: None.get"});
        assert_eq!(grade_chain(&nice, &e)["value"], "nice");
        let coal = json!({"valid": false, "error": null}); // consensus-equivalent in-band, red by design
        assert_eq!(grade_chain(&coal, &e)["value"], "value");
    }

    // ── header_votes ──────────────────────────────────────────────────────────

    #[test]
    fn grade_chain_header_votes_valid_true_nice() {
        // expected valid:true + matching actual → nice
        let e = json!({"kind": "header_votes", "expected": {"valid": true}});
        let a = json!({"valid": true, "error": null});
        assert_eq!(grade_chain(&a, &e)["value"], "nice");
    }

    #[test]
    fn grade_chain_header_votes_valid_false_nice() {
        // expected valid:false + matching actual → nice (a reject is a normal graded value)
        let e = json!({"kind": "header_votes", "expected": {"valid": false}});
        let a = json!({"valid": false, "error": null});
        assert_eq!(grade_chain(&a, &e)["value"], "nice");
    }

    #[test]
    fn grade_chain_header_votes_errored_actual_is_coal() {
        // {error:"errored"} actual where {valid:...} is expected → coal (no errored arm for this kind)
        let e = json!({"kind": "header_votes", "expected": {"valid": true}});
        let a = json!({"valid": null, "error": "errored"});
        assert_eq!(grade_chain(&a, &e)["value"], "value");
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
