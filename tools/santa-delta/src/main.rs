//! santa-delta <base-results.json> <head-results.json> <out-dir>
//!
//! Compares two conform results files (a pull request's base and head, graded on one SANTA checkout) and writes
//! `<out-dir>/comment.md` (the PR comment), `<out-dir>/summary.md` (the job summary, cut at GitHub's 1 MiB limit)
//! and `<out-dir>/delta.json` (every entry). It always exits 0 on a delta: failing a check on new coal is the
//! caller's choice, made from delta.json. Exit 2 = usage or I/O.
use serde_json::Value;
use std::path::Path;
use std::process::ExitCode;
use std::{env, fs};

/// GitHub's job summary limit is 1 MiB per step; keep a margin for what the caller appends.
const SUMMARY_BUDGET: usize = 1_000_000;

fn read(p: &str) -> Result<Value, String> {
    let text = fs::read_to_string(p).map_err(|e| format!("{p}: {e}"))?;
    serde_json::from_str(&text).map_err(|e| format!("{p}: {e}"))
}

fn run(base: &str, head: &str, out: &str) -> Result<(), String> {
    let ds = santa_delta::delta(&read(base)?, &read(head)?);
    let out = Path::new(out);
    fs::create_dir_all(out).map_err(|e| format!("{}: {e}", out.display()))?;
    let write = |name: &str, body: String| {
        let p = out.join(name);
        fs::write(&p, body).map_err(|e| format!("{}: {e}", p.display()))
    };
    write("comment.md", santa_delta::comment(&ds))?;
    write("summary.md", santa_delta::summary(&ds, SUMMARY_BUDGET))?;
    write("delta.json", serde_json::to_string_pretty(&santa_delta::to_json(&ds)).unwrap())
}

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    let [base, head, out] = args.as_slice() else {
        eprintln!("usage: santa-delta <base-results.json> <head-results.json> <out-dir>");
        return ExitCode::from(2);
    };
    match run(base, head, out) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("santa-delta: {e}");
            ExitCode::from(2)
        }
    }
}
