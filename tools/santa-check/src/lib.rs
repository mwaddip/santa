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
