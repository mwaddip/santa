//! santa-check bin — batch grader. Reads a JSON array of {actual, expected, claims_cost} from
//! stdin, writes the array of verdicts to stdout (same order). The orchestrator (./conform) pipes
//! one batch per run and tallies the verdicts; a Rust runner can shell to it the same way.
use santa_check::grade;
use serde_json::Value;
use std::io::Read;

fn main() {
    let mut input = String::new();
    std::io::stdin()
        .read_to_string(&mut input)
        .expect("read stdin");
    let cases: Value = serde_json::from_str(&input).expect("stdin must be JSON");
    let arr = cases
        .as_array()
        .expect("stdin must be a JSON array of {actual, expected, claims_cost}");
    let verdicts: Vec<Value> = arr
        .iter()
        .map(|c| {
            grade(
                c.get("actual").unwrap_or(&Value::Null),
                c.get("expected").unwrap_or(&Value::Null),
                c.get("claims_cost").and_then(Value::as_bool).unwrap_or(true),
            )
        })
        .collect();
    println!(
        "{}",
        serde_json::to_string(&verdicts).expect("serialize verdicts")
    );
}
