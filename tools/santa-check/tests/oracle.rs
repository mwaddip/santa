//! Prove santa-check reproduces the SANTA verdict-oracle (../../oracle/*.json) — §6 made executable,
//! the same contract tools/test_oracle.py holds compare.py to. Both must agree on every verdict.
use santa_check::grade;
use serde_json::Value;
use std::{fs, path::Path};

#[test]
fn reproduces_verdict_oracle() {
    let oracle_dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../oracle");
    let mut total = 0usize;
    let mut fails: Vec<String> = Vec::new();
    for entry in fs::read_dir(&oracle_dir).expect("read oracle/ dir") {
        let p = entry.unwrap().path();
        if p.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let doc: Value = serde_json::from_str(&fs::read_to_string(&p).unwrap()).unwrap();
        for c in doc["cases"].as_array().expect("cases[]") {
            total += 1;
            let got = grade(&c["actual"], &c["expected"], c["claims_cost"].as_bool().unwrap());
            if got != c["verdict"] {
                fails.push(format!("{}: got {} want {}", c["name"], got, c["verdict"]));
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
