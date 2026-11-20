//! The proof of the switch (plan 1 of the self-contained runners, task 4): the grading interpreter over
//! `vectors/grading.json` grades every entry of the corpus as the hard-coded santa-check functions do, for a battery
//! of synthetic actuals. It goes with the hard-coded functions (task 8).
use santa_check::grading::{Grade, Grading, Input, Verdict};
use santa_check::{grade, grade_authds, grade_block, grade_chain, grade_nipopow, grade_transaction, grade_wire};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

/// Every vector file, with its tier (the first path segment under `vectors/`); the grading file is not a vector.
fn vector_files() -> Vec<(String, PathBuf)> {
    fn walk(d: &Path, out: &mut Vec<PathBuf>) {
        for e in fs::read_dir(d).unwrap().flatten() {
            let p = e.path();
            if p.is_dir() {
                walk(&p, out);
            } else if p.extension().and_then(|x| x.to_str()) == Some("json") {
                out.push(p);
            }
        }
    }
    let vectors = root().join("vectors");
    let mut files = Vec::new();
    walk(&vectors, &mut files);
    files.sort();
    files
        .into_iter()
        .filter(|p| p.file_name().and_then(|n| n.to_str()) != Some("grading.json"))
        .map(|p| {
            let tier = p.strip_prefix(&vectors).unwrap().iter().next().unwrap().to_string_lossy().into_owned();
            (tier, p)
        })
        .collect()
}

/// Today's grading, called exactly as conform calls it (a missing actual is `null`).
fn legacy(tier: &str, actual: Option<&Value>, entry: &Value, file: &Value, claims_cost: bool) -> Value {
    let a = actual.cloned().unwrap_or(Value::Null);
    match tier {
        "eval" => grade(&a, &entry["expected"], claims_cost),
        "wire" => grade_wire(&a, entry),
        "transaction" => grade_transaction(&a, &entry["expected"], claims_cost),
        "block" => grade_block(&a, &entry["expected"], claims_cost),
        "chain" => grade_chain(&a, entry),
        "authds" => grade_authds(&a, entry),
        "nipopow" => grade_nipopow(&a, entry, &file["chain"]),
        t => panic!("unknown tier {t}"),
    }
}

/// A verdict as dimension -> grade, n/a dims dropped (not graded = not counted), or the global outcome.
#[derive(Debug, PartialEq)]
enum Norm {
    Exceeded(Value),
    Panicked,
    NotImplemented,
    Graded(BTreeMap<String, Grade>),
}

fn grade_of(s: &str) -> Grade {
    match s {
        "nice" => Grade::Nice,
        "n/a" => Grade::NotApplicable,
        _ => Grade::Red,
    }
}

fn graded(dims: impl IntoIterator<Item = (String, Grade)>) -> Norm {
    Norm::Graded(dims.into_iter().filter(|(_, g)| *g != Grade::NotApplicable).collect())
}

fn legacy_to_generic(v: &Value) -> Norm {
    let kind = v["kind"].as_str().expect("a legacy verdict has a kind");
    match kind {
        "exceeded" => Norm::Exceeded(v["limit"].clone()),
        "panicked" => Norm::Panicked,
        "coverage" => Norm::NotImplemented,
        _ => graded(v.as_object().unwrap().iter().filter(|(k, _)| k.as_str() != "kind").map(|(k, g)| {
            // `reject` and `roundtrip` carry their one grade as `verdict`; every other kind names its dimensions.
            let name = if k == "verdict" { kind.to_string() } else { k.clone() };
            (name, grade_of(g.as_str().expect("a grade string")))
        })),
    }
}

fn interpreter_to_norm(v: &Verdict) -> Norm {
    match v {
        Verdict::Exceeded { limit } => Norm::Exceeded(limit.clone()),
        Verdict::Panicked => Norm::Panicked,
        Verdict::NotImplemented => Norm::NotImplemented,
        Verdict::Graded(dims) => graded(dims.iter().cloned()),
    }
}

/// The entry's expected outcome written as an actual.
fn as_actual(tier: &str, entry: &Value, file: &Value) -> Value {
    let expected = &entry["expected"];
    let errored = |e: &Value| e["error"] == "errored";
    match tier {
        "eval" if errored(expected) => json!({ "value": null, "cost": null, "error": "errored" }),
        "eval" => json!({ "value": expected["value"], "cost": expected["cost"], "error": null }),
        "wire" if entry["error"] == "errored" => json!({ "bytes_hex": null, "error": "errored" }),
        "wire" => {
            let bytes = entry.get("expected_bytes_hex").filter(|v| !v.is_null()).unwrap_or(&entry["bytes_hex"]);
            json!({ "bytes_hex": bytes, "error": null })
        }
        "nipopow" if entry["kind"] == "nipopow_interlinks" => {
            let chain: Vec<Value> =
                file["chain"].as_array().into_iter().flatten().map(|h| h["interlinks"].clone()).collect();
            json!({ "interlinks": chain, "error": null })
        }
        "nipopow" => json!({ "proofHex": expected["proofHex"], "error": null }),
        _ => {
            let mut a = expected.clone();
            if !a.is_object() {
                a = json!({});
            }
            if !errored(expected) {
                a["error"] = Value::Null;
            }
            a
        }
    }
}

fn is_hex(s: &str) -> bool {
    !s.is_empty() && s.len() % 2 == 0 && s.bytes().all(|b| b.is_ascii_hexdigit()) && s.bytes().any(|b| b.is_ascii_alphabetic())
}

/// The synthetic actuals for one entry; `None` is a missing actual.
fn battery(tier: &str, entry: &Value, file: &Value) -> Vec<Option<Value>> {
    let base = as_actual(tier, entry, file);
    let mut out = vec![Some(base.clone())];
    for (k, v) in base.as_object().unwrap() {
        if k == "error" {
            continue;
        }
        let mut m = base.clone();
        m[k] = json!("mutated");
        out.push(Some(m));
        if let Some(s) = v.as_str().filter(|s| is_hex(s)) {
            let mut u = base.clone();
            u[k] = json!(s.to_ascii_uppercase());
            out.push(Some(u));
        }
    }
    let mut dirty = base.clone();
    dirty["error"] = json!("errored");
    out.push(Some(dirty));
    for a in [
        json!({ "error": "errored" }),
        json!({ "error": "not-implemented" }),
        json!({ "error": "panicked", "note": "x" }),
        json!({ "error": "exceeded", "limit": "time" }),
    ] {
        out.push(Some(a));
    }
    out.push(None);
    out
}

#[test]
fn the_interpreter_reproduces_santa_check_over_every_vector_entry() {
    let doc: Value = serde_json::from_str(&fs::read_to_string(root().join("vectors/grading.json")).unwrap()).unwrap();
    let grading = Grading::parse(&doc).unwrap_or_else(|e| panic!("{e}"));
    let (mut checks, mut allowed) = (0usize, 0usize);
    let mut fails: Vec<String> = Vec::new();
    for (tier, path) in vector_files() {
        let file: Value = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
        let label = grading.label_for(&tier, &file).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
        for entry in file["entries"].as_array().unwrap() {
            for actual in battery(&tier, entry, &file) {
                for claims_cost in [true, false] {
                    checks += 1;
                    let input = Input { entry, actual: actual.as_ref(), file: &file, claims_cost };
                    let new = interpreter_to_norm(&grading.grade(label, &input).unwrap_or_else(|e| {
                        panic!("{} {}: {e}", path.display(), entry["name"])
                    }));
                    let old = legacy_to_generic(&legacy(&tier, actual.as_ref(), entry, &file, claims_cost));
                    // The one deliberate difference: an eval entry expecting `errored` with no actual is red on
                    // `reject` (the legacy grade put it on `value`, before looking at what was expected).
                    if tier == "eval" && actual.is_none() && entry["expected"]["error"] == "errored" {
                        assert_eq!(new, graded([("reject".to_string(), Grade::Red)]), "{}", entry["name"]);
                        assert_eq!(old, graded([("value".to_string(), Grade::Red)]), "{}", entry["name"]);
                        allowed += 1;
                        continue;
                    }
                    if new != old && fails.len() < 40 {
                        fails.push(format!(
                            "{} {} claims={claims_cost} actual={}\n    interpreter {new:?}\n    legacy      {old:?}",
                            path.strip_prefix(root()).unwrap().display(),
                            entry["name"],
                            actual.as_ref().map_or("(missing)".to_string(), |a| a.to_string().chars().take(160).collect()),
                        ));
                    }
                }
            }
        }
    }
    assert!(checks > 900_000, "the battery covers the corpus (~55,000 entries, >= 9 actuals, 2 claims): {checks} checks");
    assert!(allowed > 0, "the allowed difference occurs in the corpus");
    assert!(fails.is_empty(), "{} of {checks} checks differ (first {}):\n{}", fails.len(), fails.len(), fails.join("\n"));
}
