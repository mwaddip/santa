//! `santa-bound <cmd> [args…] <vectors-dir> <out-dir>` (env `SANTA_BOUND_STATUS`): see lib.rs.
use std::env;
use std::path::Path;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    let Some(status) = env::var_os("SANTA_BOUND_STATUS").filter(|_| args.len() >= 3) else {
        eprintln!("usage: SANTA_BOUND_STATUS=<dir> santa-bound <cmd> [args…] <vectors-dir> <out-dir>");
        return ExitCode::from(2);
    };
    let mut vars = Vec::new();
    for (k, v) in env::vars_os() {
        match (k.into_string(), v.into_string()) {
            (Ok(k), Ok(v)) => vars.push((k, v)),
            (k, _) => {
                eprintln!("santa-bound: environment variable {k:?} is not UTF-8 and cannot be passed to a unit");
                return ExitCode::from(1);
            }
        }
    }
    match santa_bound::run(&args, Path::new(&status), &vars) {
        Ok(code) => ExitCode::from(code as u8),
        Err(e) => {
            eprintln!("santa-bound: {e}");
            ExitCode::from(1)
        }
    }
}
