//! Prove santa-check's grading interpreter reproduces the SANTA verdict oracle (../../oracle/verdicts-grading.json):
//! the grading rules made executable (docs/contract/grading.md).
use santa_check::grading::{Grading, Input};
use serde_json::{json, Value};
use std::{fs, path::Path};

fn grading() -> Grading {
    let p = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../vectors/grading.json");
    Grading::parse(&serde_json::from_str(&fs::read_to_string(p).unwrap()).unwrap()).unwrap_or_else(|e| panic!("{e}"))
}

/// One `santa-oracle-grading/v1` case through the interpreter: its label, entry, file envelope (default `{}`), actual
/// (`null` = missing) and cost claim (default false) -> the verdict as `Verdict::to_json` writes it.
fn interpret(g: &Grading, c: &Value) -> Value {
    let empty = json!({});
    let input = Input {
        entry: &c["entry"],
        actual: Some(&c["actual"]).filter(|a| !a.is_null()),
        file: c.get("file").unwrap_or(&empty),
        claims_cost: c["claims_cost"].as_bool().unwrap_or(false),
    };
    g.grade(c["label"].as_str().expect("a label"), &input).map_or_else(|e| json!({ "error": e.to_string() }), |v| v.to_json())
}

#[test]
fn reproduces_verdict_oracle() {
    let oracle_dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../oracle");
    let g = grading();
    let mut total = 0usize;
    let mut fails: Vec<String> = Vec::new();
    for entry in fs::read_dir(&oracle_dir).expect("read oracle/ dir") {
        let p = entry.unwrap().path();
        if p.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let doc: Value = serde_json::from_str(&fs::read_to_string(&p).unwrap()).unwrap();
        let schema = doc["schema"].as_str().unwrap_or("");
        for c in doc["cases"].as_array().expect("cases[]") {
            total += 1;
            let got = match schema {
                "santa-oracle-grading/v1" => interpret(&g, c),
                _ => panic!("unknown oracle schema: {schema:?} in {}", p.display()),
            };
            if got != c["verdict"] {
                fails.push(format!("{} [{}]: got {} want {}", c["name"], p.display(), got, c["verdict"]));
            }
        }
    }
    assert!(total > 0, "no oracle cases found under oracle/*.json");
    assert!(
        fails.is_empty(),
        "santa-check disagrees with the oracle:\n{}",
        fails.join("\n")
    );
    eprintln!("santa-check reproduces all {total} oracle verdicts");
}

/// The grading oracle exercises every grading type: each label in `vectors/grading.json` has cases, and each case's
/// label exists.
#[test]
fn the_grading_oracle_covers_every_label() {
    let p = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../oracle/verdicts-grading.json");
    let doc: Value = serde_json::from_str(&fs::read_to_string(&p).expect("oracle/verdicts-grading.json")).unwrap();
    assert_eq!(doc["schema"], "santa-oracle-grading/v1");
    let g = grading();
    let cases = doc["cases"].as_array().unwrap();
    for label in g.types.keys() {
        assert!(cases.iter().any(|c| c["label"] == label.as_str()), "no oracle case for the grading type {label}");
    }
    for c in cases {
        let label = c["label"].as_str().unwrap();
        assert!(g.types.contains_key(label), "{}: unknown label {label}", c["name"]);
    }
}

