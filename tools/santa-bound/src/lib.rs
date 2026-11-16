//! santa-bound — runs a runner's last line under resource bounds enforced from outside the process
//! (runner-integration.md §3a, docs/specs/resource-bounds.md).
//!
//! `santa-bound <cmd> [args…] <vectors-dir> <out-dir>`, with `SANTA_BOUND_STATUS` naming a status dir. The files in
//! `<vectors-dir>` without `limits` run as ONE unit under the safety ceiling (`Limits::ORDINARY`); each bounded file
//! runs as its own unit under its `limits`. A unit is a temporary `systemd-run --user` service, so systemd, outside
//! the process, enforces the cgroup's memory and the wall clock. Every unit runs `<cmd> [args…] <unit-in> <out-dir>`.
//! Status dir: `plan.json` (written before any unit runs), `ordinary.json`, `bounded/<file>`, `units/<n>/`.
pub use santa_check::limits::Limits;
use santa_check::limits::check_bounded;
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::fs;
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// A unit name must never be one systemd already holds: a refused `systemd-run` followed by `systemctl show` would
/// read ANOTHER unit's result. So it carries the pid, the run's start time (a reused pid) and a per-process sequence
/// (concurrent runs in one process).
static UNIT_SEQ: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Limit {
    Memory,
    Time,
}

impl Limit {
    pub fn as_str(&self) -> &'static str {
        match self {
            Limit::Memory => "memory",
            Limit::Time => "time",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    Success,
    Exceeded(Limit),
    Crashed(String),
}

#[derive(Debug, PartialEq, Eq)]
pub struct Plan {
    pub ordinary: Vec<String>,
    pub bounded: Vec<(String, Limits)>,
}

/// Splits `<vectors-dir>`'s `*.json` into the ordinary files and the bounded ones (names sorted).
pub fn plan(vectors_dir: &Path) -> Result<Plan, String> {
    let rd = fs::read_dir(vectors_dir).map_err(|e| format!("read {}: {e}", vectors_dir.display()))?;
    let mut names: Vec<String> = rd
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".json"))
        .collect();
    names.sort();
    let mut p = Plan { ordinary: Vec::new(), bounded: Vec::new() };
    for n in names {
        let path = vectors_dir.join(&n);
        let text = fs::read_to_string(&path).map_err(|e| format!("read {}: {e}", path.display()))?;
        let doc: Value = serde_json::from_str(&text).map_err(|e| format!("{n}: not JSON: {e}"))?;
        match check_bounded(&doc).map_err(|e| format!("{n}: {e}"))? {
            None => p.ordinary.push(n),
            Some(l) => p.bounded.push((n, l)),
        }
    }
    Ok(p)
}

/// Reads `systemctl show -p Result -p ExecMainCode -p ExecMainStatus -p LoadState` output. Err: no such unit.
pub fn outcome_from_show(show: &str) -> Result<Outcome, String> {
    let kv: BTreeMap<&str, &str> = show.lines().filter_map(|l| l.split_once('=')).collect();
    if kv.get("LoadState") == Some(&"not-found") {
        return Err("no such unit".into());
    }
    let status = kv.get("ExecMainStatus").copied().unwrap_or("?");
    Ok(match kv.get("Result").copied().unwrap_or("") {
        "success" => Outcome::Success,
        "oom-kill" => Outcome::Exceeded(Limit::Memory),
        "timeout" => Outcome::Exceeded(Limit::Time),
        "exit-code" => Outcome::Crashed(format!("exit status {status}")),
        "signal" | "core-dump" => Outcome::Crashed(format!("signal {status}")),
        other => Outcome::Crashed(format!("systemd result {other}")),
    })
}

/// systemd takes only `[A-Za-z_][A-Za-z0-9_]*` names; any other variable is refused, by name, never dropped.
pub fn check_env_names<'a>(names: impl IntoIterator<Item = &'a str>) -> Result<(), String> {
    for n in names {
        let mut cs = n.chars();
        let ok = cs.next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
            && cs.all(|c| c.is_ascii_alphanumeric() || c == '_');
        if !ok {
            return Err(format!(
                "environment variable {n:?} cannot be passed to a systemd unit; unset it (unset -f for an exported bash function)"
            ));
        }
    }
    Ok(())
}

/// A temporary service searches systemd's default PATH, not the caller's, so the command is made absolute here.
pub fn resolve_command(cmd0: &str, path: &str) -> Result<PathBuf, String> {
    let executable = |p: &Path| p.metadata().is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0);
    if cmd0.contains('/') {
        let p = PathBuf::from(cmd0);
        let p = if p.is_absolute() { p } else { std::env::current_dir().map_err(|e| e.to_string())?.join(p) };
        return if executable(&p) { Ok(p) } else { Err(format!("{} is not an executable file", p.display())) };
    }
    path.split(':')
        .filter(|d| !d.is_empty())
        .map(|d| Path::new(d).join(cmd0))
        .find(|p| executable(p))
        .ok_or_else(|| format!("{cmd0:?} is not on PATH"))
}

/// Runs `cmd <unit_in> <out>` as the temporary user service `name` under `limits`, with exactly `env`, in this
/// process's working directory. Err: the unit could not be run at all (never a reason to run it unbounded).
pub fn run_unit(
    name: &str,
    limits: &Limits,
    cmd: &[String],
    unit_in: &Path,
    out: &Path,
    env: &[(String, String)],
) -> Result<Outcome, String> {
    check_env_names(env.iter().map(|(k, _)| k.as_str()))?;
    let path = env.iter().find(|(k, _)| k == "PATH").map_or("", |(_, v)| v.as_str());
    let exe = resolve_command(cmd.first().ok_or("empty command")?, path)?;
    let mut c = Command::new("systemd-run");
    c.args(["--user", "--wait", "--pipe", "--quiet", "-u", name, "--same-dir"])
        .args(["-p", &format!("MemoryMax={}M", limits.memory_mb), "-p", "MemorySwapMax=0"])
        .args(["-p", &format!("RuntimeMaxSec={}", limits.wall_s), "-p", "TimeoutStopSec=5"]);
    for (k, v) in env {
        c.arg("-E").arg(format!("{k}={v}"));
    }
    let st = c.arg("--").arg(&exe).args(&cmd[1..]).arg(unit_in).arg(out).status()
        .map_err(|e| format!("cannot start systemd-run: {e}"))?;
    let show = Command::new("systemctl")
        .args(["--user", "show", name, "-p", "Result", "-p", "ExecMainCode", "-p", "ExecMainStatus", "-p", "LoadState"])
        .output()
        .map_err(|e| format!("cannot run systemctl: {e}"))?;
    let outcome = match outcome_from_show(&String::from_utf8_lossy(&show.stdout)) {
        Ok(o) => o,
        // A unit that succeeded is unloaded at once; one that never existed is a failure to run.
        Err(_) if st.success() => Outcome::Success,
        Err(_) => return Err(format!("systemd-run exited {st} and no unit {name} exists")),
    };
    let _ = Command::new("systemctl").args(["--user", "reset-failed", name]).output();
    Ok(outcome)
}

/// The probe's reading of its own cgroup: `memory.max` (bytes) then `memory.swap.max`. Ok iff systemd applied a
/// `MemoryMax=<memory_mb>M` and `MemorySwapMax=0` to it.
pub fn check_probe(out: &str, memory_mb: u64) -> Result<(), String> {
    let mut l = out.lines().map(str::trim);
    let (mem, swap) = (l.next().unwrap_or(""), l.next().unwrap_or(""));
    let want = (memory_mb << 20).to_string();
    if mem == want && swap == "0" {
        return Ok(());
    }
    Err(format!(
        "systemd did not apply the memory bound to a user unit (memory.max {mem:?}, memory.swap.max {swap:?}; want \
         {want:?}, \"0\"): the user manager needs cgroup v2 with the memory controller delegated and swap accounted. \
         santa-bound never runs a file unbounded"
    ))
}

/// `systemd-run --user` accepts `MemoryMax=` even where the user manager has no memory controller, and the unit then
/// runs unbounded. So before any unit runs, a probe unit reads its own cgroup's limits back.
pub fn check_enforced() -> Result<(), String> {
    let out = Command::new("systemd-run")
        .args(["--user", "--wait", "--pipe", "--quiet", "--collect"])
        .args(["-p", "MemoryMax=64M", "-p", "MemorySwapMax=0", "-p", "RuntimeMaxSec=30", "--"])
        .args(["/bin/sh", "-c", "cg=$(cut -d: -f3 /proc/self/cgroup); cat /sys/fs/cgroup$cg/memory.max /sys/fs/cgroup$cg/memory.swap.max"])
        .output()
        .map_err(|e| format!("cannot start systemd-run: {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "cannot run a systemd user unit ({}): {}",
            out.status,
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    check_probe(&String::from_utf8_lossy(&out.stdout), 64)
}

/// One unit's status record.
pub fn record(file: Option<&str>, limits: &Limits, o: &Outcome) -> Value {
    let mut r = Map::new();
    r.insert("file".into(), json!(file));
    r.insert("limits".into(), limits.to_json());
    match o {
        Outcome::Success => {
            r.insert("result".into(), json!("success"));
        }
        Outcome::Exceeded(l) => {
            r.insert("result".into(), json!("exceeded"));
            r.insert("limit".into(), json!(l.as_str()));
        }
        Outcome::Crashed(d) => {
            r.insert("result".into(), json!("crashed"));
            r.insert("detail".into(), json!(d));
        }
    }
    Value::Object(r)
}

fn write_json(p: &Path, v: &Value) -> Result<(), String> {
    fs::write(p, serde_json::to_string_pretty(v).unwrap()).map_err(|e| format!("write {}: {e}", p.display()))
}

/// The whole run (`args` = `<cmd> [args…] <vectors-dir> <out-dir>`, at least three). Ok(0): the ordinary unit
/// succeeded (or there was none) and every bounded unit has a record; Ok(3): the ordinary unit did not succeed.
pub fn run(args: &[String], status: &Path, env: &[(String, String)]) -> Result<i32, String> {
    check_enforced()?;
    let (cmd, dirs) = args.split_at(args.len() - 2);
    let (vectors, out) = (Path::new(&dirs[0]), Path::new(&dirs[1]));
    let vectors = fs::canonicalize(vectors).map_err(|e| format!("{}: {e}", vectors.display()))?;
    for stale in ["plan.json", "ordinary.json", "bounded", "units"] {
        let p = status.join(stale);
        let _ = if p.is_dir() { fs::remove_dir_all(&p) } else { fs::remove_file(&p) };
    }
    fs::create_dir_all(status.join("bounded")).map_err(|e| format!("create {}: {e}", status.display()))?;
    let p = plan(&vectors)?;
    let bounded: Map<String, Value> = p.bounded.iter().map(|(n, l)| (n.clone(), l.to_json())).collect();
    write_json(&status.join("plan.json"), &json!({"ordinary": p.ordinary, "bounded": bounded}))?;

    let mut units: Vec<(Option<&str>, Vec<&str>, Limits)> = Vec::new();
    if !p.ordinary.is_empty() {
        units.push((None, p.ordinary.iter().map(String::as_str).collect(), Limits::ORDINARY));
    }
    for (n, l) in &p.bounded {
        units.push((Some(n.as_str()), vec![n.as_str()], *l));
    }
    let started = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis());
    let mut code = 0;
    for (i, (file, files, limits)) in units.iter().enumerate() {
        let unit_in = status.join("units").join(i.to_string());
        fs::create_dir_all(&unit_in).map_err(|e| format!("create {}: {e}", unit_in.display()))?;
        let unit_in = fs::canonicalize(&unit_in).map_err(|e| e.to_string())?;
        for f in files {
            symlink(vectors.join(f), unit_in.join(f)).map_err(|e| format!("link {f}: {e}"))?;
        }
        let name = format!("santa-bound-{}-{started}-{}", std::process::id(), UNIT_SEQ.fetch_add(1, Ordering::Relaxed));
        let o = run_unit(&name, limits, cmd, &unit_in, out, env)?;
        match file {
            None => {
                if o != Outcome::Success {
                    code = 3;
                }
                write_json(&status.join("ordinary.json"), &record(None, limits, &o))?;
            }
            Some(n) => write_json(&status.join("bounded").join(n), &record(Some(n), limits, &o))?,
        }
    }
    Ok(code)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::os::unix::fs::PermissionsExt;

    const L: Limits = Limits { memory_mb: 1024, wall_s: 10 };

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("santa-bound-test-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn write(dir: &Path, name: &str, v: &Value) {
        fs::write(dir.join(name), serde_json::to_string(v).unwrap()).unwrap();
    }

    fn env() -> Vec<(String, String)> {
        std::env::vars().collect()
    }

    fn bash(script: &str) -> Vec<String> {
        vec!["bash".into(), "-c".into(), script.into(), "_".into()]
    }

    /// One live unit: `cmd <in> <out>` under `limits`, in fresh temp dirs.
    fn live(tag: &str, limits: Limits, cmd: &[String], env: &[(String, String)]) -> (Result<Outcome, String>, PathBuf) {
        let d = tmp(tag);
        let (i, o) = (d.join("in"), d.join("out"));
        fs::create_dir_all(&i).unwrap();
        fs::create_dir_all(&o).unwrap();
        let name = format!("santa-bound-test-{}-{tag}", std::process::id());
        (run_unit(&name, &limits, cmd, &i, &o, env), o)
    }

    // ---- pure ----

    #[test]
    fn plan_splits_ordinary_and_bounded() {
        let d = tmp("plan");
        write(&d, "a.json", &json!({"entries": [{}, {}]}));
        write(&d, "b.json", &json!({"limits": {"memory_mb": 1024, "wall_s": 10}, "entries": [{}]}));
        assert_eq!(plan(&d), Ok(Plan { ordinary: vec!["a.json".into()], bounded: vec![("b.json".into(), L)] }));
    }

    #[test]
    fn plan_refuses_a_malformed_limit() {
        let d = tmp("plan-bad");
        write(&d, "c.json", &json!({"limits": {"memory_mb": 5000, "wall_s": 10}, "entries": [{}]}));
        let p = plan(&d);
        assert!(p.as_ref().is_err_and(|e| e.contains("c.json")), "{p:?}");
    }

    #[test]
    fn show_maps_every_result() {
        let s = |r: &str, status: &str| format!("Result={r}\nExecMainCode=1\nExecMainStatus={status}\nLoadState=loaded\n");
        assert_eq!(outcome_from_show(&s("success", "0")), Ok(Outcome::Success));
        assert_eq!(outcome_from_show(&s("oom-kill", "9")), Ok(Outcome::Exceeded(Limit::Memory)));
        assert_eq!(outcome_from_show(&s("timeout", "15")), Ok(Outcome::Exceeded(Limit::Time)));
        assert_eq!(outcome_from_show(&s("exit-code", "3")), Ok(Outcome::Crashed("exit status 3".into())));
        assert_eq!(outcome_from_show(&s("signal", "6")), Ok(Outcome::Crashed("signal 6".into())));
        assert_eq!(outcome_from_show(&s("core-dump", "11")), Ok(Outcome::Crashed("signal 11".into())));
        assert_eq!(outcome_from_show(&s("resources", "0")), Ok(Outcome::Crashed("systemd result resources".into())));
        assert!(outcome_from_show("Result=success\nLoadState=not-found\n").is_err());
    }

    #[test]
    fn env_names() {
        assert_eq!(check_env_names(["PATH", "HOME", "_X1"]), Ok(()));
        let e = check_env_names(["PATH", "BASH_FUNC_f%%"]);
        assert!(e.as_ref().is_err_and(|e| e.contains("BASH_FUNC_f%%")), "{e:?}");
    }

    #[test]
    fn resolve_command_uses_the_given_path() {
        let d = tmp("resolve");
        let exe = d.join("santa-probe-cmd");
        fs::write(&exe, "#!/bin/bash\n").unwrap();
        fs::set_permissions(&exe, fs::Permissions::from_mode(0o755)).unwrap();
        let path = format!("/nonexistent:{}", d.display());
        assert_eq!(resolve_command("santa-probe-cmd", &path), Ok(exe.clone()));
        assert_eq!(resolve_command(exe.to_str().unwrap(), "/nonexistent"), Ok(exe));
        assert!(resolve_command("santa-no-such-cmd", &path).is_err());
    }

    #[test]
    fn record_shapes() {
        let l = json!({"memory_mb": 1024, "wall_s": 10});
        assert_eq!(
            record(Some("b.json"), &L, &Outcome::Exceeded(Limit::Memory)),
            json!({"file": "b.json", "limits": l, "result": "exceeded", "limit": "memory"})
        );
        assert_eq!(
            record(None, &L, &Outcome::Crashed("exit status 3".into())),
            json!({"file": null, "limits": l, "result": "crashed", "detail": "exit status 3"})
        );
        assert_eq!(record(Some("c.json"), &L, &Outcome::Success), json!({"file": "c.json", "limits": l, "result": "success"}));
    }

    #[test]
    fn probe_reading() {
        assert_eq!(check_probe("67108864\n0\n", 64), Ok(()));
        let e = check_probe("max\nmax\n", 64);
        assert!(e.as_ref().is_err_and(|e| e.contains("memory controller")), "{e:?}");
        assert!(check_probe("67108864\n", 64).is_err(), "swap not accounted");
        assert!(check_probe("", 64).is_err());
    }

    // ---- live: systemd-run --user (present locally and on ubuntu-latest, tmp/resource-bounds/README.md) ----

    #[test]
    fn bounds_are_enforced_here() {
        assert_eq!(check_enforced(), Ok(()));
    }

    #[test]
    fn unit_success() {
        assert_eq!(live("ok", L, &bash("true"), &env()).0, Ok(Outcome::Success));
    }

    #[test]
    fn unit_oom() {
        let small = Limits { memory_mb: 128, wall_s: 10 };
        assert_eq!(live("oom", small, &bash(r#"python3 -c "bytearray(1<<30)""#), &env()).0, Ok(Outcome::Exceeded(Limit::Memory)));
    }

    #[test]
    fn unit_oom_in_a_child() {
        let small = Limits { memory_mb: 128, wall_s: 30 };
        let t = std::time::Instant::now();
        let r = live("oom-child", small, &bash(r#"python3 -c "bytearray(1<<30)"; sleep 30"#), &env()).0;
        assert_eq!(r, Ok(Outcome::Exceeded(Limit::Memory)));
        assert!(t.elapsed().as_secs() < 10, "took {:?}", t.elapsed());
    }

    #[test]
    fn unit_timeout() {
        let quick = Limits { memory_mb: 128, wall_s: 2 };
        assert_eq!(live("slow", quick, &bash("sleep 30"), &env()).0, Ok(Outcome::Exceeded(Limit::Time)));
    }

    #[test]
    fn unit_ignoring_term_is_killed() {
        let quick = Limits { memory_mb: 128, wall_s: 2 };
        let t = std::time::Instant::now();
        let r = live("noterm", quick, &bash(r#"trap "" TERM; sleep 30"#), &env()).0;
        assert_eq!(r, Ok(Outcome::Exceeded(Limit::Time)));
        assert!(t.elapsed().as_secs() < 15, "took {:?}", t.elapsed());
    }

    #[test]
    fn unit_crash() {
        assert_eq!(live("crash", L, &bash("exit 3"), &env()).0, Ok(Outcome::Crashed("exit status 3".into())));
    }

    #[test]
    fn unit_sees_env_and_cwd() {
        let mut e = env();
        e.push(("SANTA_BOUND_PROBE".into(), "xyz".into()));
        let (r, out) = live("env", L, &bash(r#"printf '%s %s' "$SANTA_BOUND_PROBE" "$PWD" > "$2/probe""#), &e);
        assert_eq!(r, Ok(Outcome::Success));
        let cwd = std::env::current_dir().unwrap();
        assert_eq!(fs::read_to_string(out.join("probe")).unwrap(), format!("xyz {}", cwd.display()));
    }

    #[test]
    fn unit_bare_command_from_path() {
        let bin = tmp("bin");
        let exe = bin.join("santa-probe-cmd");
        fs::write(&exe, "#!/bin/bash\ntouch \"$2/ran\"\n").unwrap();
        fs::set_permissions(&exe, fs::Permissions::from_mode(0o755)).unwrap();
        let mut e: Vec<(String, String)> = env().into_iter().filter(|(k, _)| k != "PATH").collect();
        e.push(("PATH".into(), format!("{}:{}", bin.display(), std::env::var("PATH").unwrap())));
        let (r, out) = live("bare", L, &["santa-probe-cmd".to_string()], &e);
        assert_eq!(r, Ok(Outcome::Success));
        assert!(out.join("ran").exists());
    }

    #[test]
    fn run_end_to_end() {
        let d = tmp("e2e");
        let (v, out, status) = (d.join("v"), d.join("out"), d.join("status"));
        fs::create_dir_all(&v).unwrap();
        fs::create_dir_all(&out).unwrap();
        let small = json!({"memory_mb": 128, "wall_s": 5});
        write(&v, "a.json", &json!({"entries": [{}, {}]}));
        write(&v, "b.json", &json!({"limits": small, "entries": [{}]}));
        write(&v, "c.json", &json!({"limits": small, "entries": [{}]}));
        let script = r#"for f in "$1"/*.json; do n=$(basename "$f"); if [ "$n" = b.json ]; then python3 -c "bytearray(1<<30)"; else : > "$2/$n"; fi; done"#;
        let mut args = bash(script);
        args.push(v.display().to_string());
        args.push(out.display().to_string());
        assert_eq!(run(&args, &status, &env()), Ok(0));
        let read = |p: &str| -> Value { serde_json::from_str(&fs::read_to_string(status.join(p)).unwrap()).unwrap() };
        assert_eq!(read("plan.json"), json!({"ordinary": ["a.json"], "bounded": {"b.json": small, "c.json": small}}));
        assert_eq!(read("ordinary.json")["result"], "success");
        assert_eq!(read("bounded/b.json")["result"], "exceeded");
        assert_eq!(read("bounded/b.json")["limit"], "memory");
        assert_eq!(read("bounded/c.json")["result"], "success");
        assert!(out.join("a.json").exists() && out.join("c.json").exists());
    }

    #[test]
    fn run_ordinary_failure_exits_3() {
        let d = tmp("e2e-fail");
        let (v, out, status) = (d.join("v"), d.join("out"), d.join("status"));
        fs::create_dir_all(&v).unwrap();
        fs::create_dir_all(&out).unwrap();
        write(&v, "a.json", &json!({"entries": [{}]}));
        let mut args = bash("exit 3");
        args.push(v.display().to_string());
        args.push(out.display().to_string());
        assert_eq!(run(&args, &status, &env()), Ok(3));
        let o: Value = serde_json::from_str(&fs::read_to_string(status.join("ordinary.json")).unwrap()).unwrap();
        assert_eq!(o["result"], "crashed");
        assert_eq!(o["detail"], "exit status 3");
    }
}
