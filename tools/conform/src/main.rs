//! SANTA conformance orchestrator (Rust port of the former tools/conform.py). Presence-as-state
//! over runners/*/: each dir with a valid runner.json + an executable santa-run (or a `runner` key naming the
//! runner's dir in its implementation's checkout) is run against the blessed corpus; the santa-check lib decides
//! nice/coal in-process; a side-by-side table is printed and the structured result written to .santa/results.json.
//! See docs/contract/runner-integration.md.
// Counts::to_json() is one flat json!{} object whose key count grows with every tier; each
// additional tier's fields push serde_json's json! macro further into its recursive expansion.
// 45 fields (post-nipopow) exceeds the default 128 limit — bump it with headroom for future tiers
// rather than fragmenting to_json() into merged sub-objects for a compiler budget, not a design issue.
#![recursion_limit = "256"]
use jsonschema::error::ValidationErrorKind;
use jsonschema::{Registry, Resource, ValidationError, Validator};
use santa_check::grading::{Grade, Grading, Input, Verdict};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, ExitCode, ExitStatus};
use std::time::{Duration, Instant};
use std::{env, fs};

/// Protocol slots, ordered; cumulative (a runner's version implies all lower). `v4` is everything below the JIT
/// activation (an entry's `version.activated` 0 or 1), `v5` is activated 2, `v6` activated 3.
const VERSIONS: [&str; 3] = ["v4", "v5", "v6"];

/// The wire kinds, as the wire vector schema's `kind` enum lists them (a test holds the two equal). A runner's
/// manifest may declare the ones it implements (`kinds`); a wire file holds one kind (./validate).
const WIRE_KINDS: [&str; 7] = ["Constant", "Box", "Transaction", "Header", "SigmaBoolean", "ErgoTree", "BlockTransactions"];

/// SANTA's tiers, as a scope may name them (runner-integration §2).
const TIERS: [&str; 7] = ["eval", "wire", "transaction", "block", "chain", "authds", "nipopow"];

/// The keys SANTA's runner.json sets for every runner (runner-integration §2).
const ENTRY_KEYS: [&str; 5] = ["name", "label", "impl", "control", "runner"];
/// The scope keys, the runner's own claim: in SANTA's runner.json for a runner SANTA hosts, in the implementation's
/// for one the implementation hosts (`runner`), and then only there.
const SCOPE_KEYS: [&str; 5] = ["version", "tiers", "cost", "kinds", "activation"];

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..") // tools/conform -> repo root
}

fn die(msg: String) -> ! {
    eprintln!("{msg}");
    std::process::exit(1);
}

/// <tier>/<version>/<provenance>/<op>.json relpath (relative to vectors/) -> (tier, version, prov, op).
fn parse_relpath(rel: &str) -> (String, String, String, String) {
    let p: Vec<&str> = rel.split('/').collect();
    if p.len() != 4 || !p[3].ends_with(".json") {
        die(format!("vector path is not <tier>/<version>/<provenance>/<op>.json: {rel}"));
    }
    (p[0].into(), p[1].into(), p[2].into(), p[3].trim_end_matches(".json").into())
}

type Vec5 = (String, String, String, String, String); // (rel, tier, version, prov, op)

/// The grading rules, at the vectors root (santa-grading/v1).
const GRADING_FILE: &str = "grading.json";

/// One recursive walk of vectors/, sorted by relpath.
fn discover_vectors(vectors: &Path) -> Vec<Vec5> {
    fn walk(d: &Path, base: &Path, out: &mut Vec<String>) {
        if let Ok(rd) = fs::read_dir(d) {
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() {
                    walk(&p, base, out);
                } else if p.extension().and_then(|x| x.to_str()) == Some("json") {
                    out.push(p.strip_prefix(base).unwrap().to_string_lossy().replace('\\', "/"));
                }
            }
        }
    }
    let mut rels = Vec::new();
    walk(vectors, vectors, &mut rels);
    // The grading rules sit at the vectors root; they are not a vector.
    rels.retain(|r| r != GRADING_FILE);
    rels.sort();
    rels.into_iter()
        .map(|rel| {
            let (t, v, p, o) = parse_relpath(&rel);
            (rel, t, v, p, o)
        })
        .collect()
}

fn version_index(v: &str) -> Option<usize> {
    VERSIONS.iter().position(|x| *x == v)
}

/// Keep vectors where tier in declared tiers AND (version == "any" OR version <= declared cumulative).
/// The "any" label is a vector-only concept meaning "applicable regardless of protocol version;
/// always selected for runners mounting the tier". A runner manifest declaring version: "any" stays
/// an error — only vectors carry the label.
fn select<'a>(vectors: &'a [Vec5], version: &str, tiers: &[String]) -> Vec<&'a Vec5> {
    let vmax = version_index(version)
        .unwrap_or_else(|| die(format!("unknown manifest version {version:?}; known: {VERSIONS:?}")));
    vectors
        .iter()
        .filter(|v| tiers.iter().any(|t| t == &v.1)
            && (v.2 == "any" || version_index(&v.2).is_some_and(|i| i <= vmax)))
        .collect()
}

/// What a wire file says about itself, read once: its one kind and whether its verdicts turn on the activated
/// script version (the envelope's `activation_rule`, runner-contract-wire).
struct WireFacts {
    kind: String,
    activation_rule: bool,
}

/// The facts of every wire file in `vectors`, keyed by relpath. A file without exactly one kind is an error naming it
/// (./validate refuses it too; conform must not guess a kind).
fn wire_facts(vectors_dir: &Path, vectors: &[Vec5]) -> Result<BTreeMap<String, WireFacts>, String> {
    let mut out = BTreeMap::new();
    for v in vectors.iter().filter(|v| v.1 == "wire") {
        let path = vectors_dir.join(&v.0);
        let doc: Value = serde_json::from_str(&fs::read_to_string(&path).map_err(|e| format!("{}: {e}", v.0))?)
            .map_err(|e| format!("{}: {e}", v.0))?;
        let kind = santa_check::wire::one_kind(&doc).map_err(|e| format!("{}: {e}", v.0))?;
        let activation_rule = doc.get("activation_rule").and_then(Value::as_bool).unwrap_or(false);
        out.insert(v.0.clone(), WireFacts { kind, activation_rule });
    }
    Ok(out)
}

/// A runner's declared scope (runner-integration §2): a wire file of a kind it does not declare, or one decided by
/// an activated-version rule when it declares none, is not staged for it. A file without facts (every file of
/// another tier) is in scope.
fn in_scope(rel: &str, facts: &BTreeMap<String, WireFacts>, kinds: &Option<Vec<String>>, activation: bool) -> bool {
    match facts.get(rel) {
        None => true,
        Some(f) => kinds.as_ref().map_or(true, |ks| ks.iter().any(|k| k == &f.kind)) && (activation || !f.activation_rule),
    }
}

/// The files a scope selects (runner-integration §4.1): its tiers at or below its version (`any` always), then its
/// declared wire kinds and activation. Proven against oracle/selection.json.
fn selection<'a>(vectors: &'a [Vec5], facts: &BTreeMap<String, WireFacts>, scope: &Scope) -> Vec<&'a Vec5> {
    select(vectors, &scope.version, &scope.tiers)
        .into_iter()
        .filter(|v| in_scope(&v.0, facts, &scope.kinds, scope.activation))
        .collect()
}

/// The declared scope on a runner's header line: `, kinds=…` when declared, `, activation=False` when not claimed.
fn scope_suffix(sc: &Scope) -> String {
    let mut s = String::new();
    if let Some(ks) = &sc.kinds {
        s.push_str(&format!(", kinds={}", ks.join(",")));
    }
    if !sc.activation {
        s.push_str(", activation=False");
    }
    s
}

/// What a runner declares it implements (runner-integration §2): it decides which vectors are staged for it.
#[derive(Clone, Debug)]
struct Scope {
    version: String,
    tiers: Vec<String>,
    cost: bool,
    /// The wire kinds the runner implements (`kinds`); None = every kind.
    kinds: Option<Vec<String>>,
    /// Whether it applies consensus rules tied to the activated script version (`activation`, default true).
    activation: bool,
}

/// Where a runner's santa-run and its scope live.
enum Host {
    /// In SANTA: `runners/<name>/santa-run`, the scope in the same runner.json.
    Santa { scope: Scope, run: PathBuf },
    /// In the implementation's repo: `<path>` in its checkout holds santa-run and a runner.json with the scope.
    Impl(String),
}

struct Runner {
    name: String,
    label: String,
    impl_: Option<String>,
    /// The repo name the runner finds its impl under (`<impl-path>/<name>`): the CONFIGURED impl's, kept when an
    /// override points at a dir called something else.
    impl_name: Option<String>,
    /// The manifest's `control`: the oracle's own runner, whose not running leaves the grid unanchored.
    control: bool,
    host: Host,
}

impl Runner {
    /// The scope as far as SANTA's manifest knows it, before any checkout.
    fn declared(&self) -> Option<&Scope> {
        match &self.host {
            Host::Santa { scope, .. } => Some(scope),
            Host::Impl(_) => None,
        }
    }
}

/// A manifest's boolean `k`: absent -> `default`; anything but a JSON boolean is refused.
fn flag(j: &Value, k: &str, default: bool) -> Result<bool, String> {
    match j.get(k) {
        None => Ok(default),
        Some(Value::Bool(b)) => Ok(*b),
        Some(v) => Err(format!("runner.json: {k:?} is {v}, not a boolean")),
    }
}

/// A runner.json, read. A misspelt key ("kind", "activations") would silently fall back to a default and widen or
/// narrow the scope, so an unknown key is refused.
fn parse_manifest(j: &Value, dir: &Path) -> Result<Runner, String> {
    let obj = j.as_object().ok_or("runner.json: not an object")?;
    if let Some(k) = obj.keys().find(|k| !ENTRY_KEYS.contains(&k.as_str()) && !SCOPE_KEYS.contains(&k.as_str())) {
        return Err(format!("runner.json: unknown key {k:?}; known: {ENTRY_KEYS:?} and {SCOPE_KEYS:?}"));
    }
    let s = |k: &str| j[k].as_str().map(String::from).ok_or_else(|| format!("runner.json: no {k:?}"));
    let impl_ = j.get("impl").and_then(Value::as_str).map(String::from);
    let host = match j.get("runner") {
        None => Host::Santa { scope: parse_scope(j)?, run: dir.join("santa-run") },
        Some(v) => {
            let path = runner_path(v)?;
            if let Some(k) = obj.keys().find(|k| SCOPE_KEYS.contains(&k.as_str())) {
                return Err(format!("runner.json: {k:?} is the implementation's to declare, in its {path}/runner.json"));
            }
            if impl_.as_deref().is_none_or(str::is_empty) {
                return Err("runner.json: \"runner\" needs an \"impl\" whose checkout holds it".into());
            }
            Host::Impl(path)
        }
    };
    let impl_name = impl_.as_deref().map(repo_name);
    Ok(Runner { name: s("name")?, label: s("label")?, impl_, impl_name, control: flag(j, "control", false)?, host })
}

/// `runner`: a path inside the implementation's checkout, of plain names only (no root, `..` or `.`).
fn runner_path(v: &Value) -> Result<String, String> {
    let p = v.as_str().ok_or("runner.json: \"runner\" is not a string")?;
    if p.is_empty() || !Path::new(p).components().all(|c| matches!(c, Component::Normal(_))) {
        return Err(format!("runner.json: \"runner\" {p:?} is not a relative path inside the checkout"));
    }
    Ok(p.to_string())
}

/// A manifest's scope keys. A declared scope is coarse and must name what it means: an unknown kind, an empty
/// `kinds` (a wire tier that selects nothing would grade nothing, silently) and `kinds` without the wire tier are
/// errors.
fn parse_scope(j: &Value) -> Result<Scope, String> {
    let tiers: Vec<String> = j["tiers"]
        .as_array()
        .ok_or("runner.json: no \"tiers\"")?
        .iter()
        .map(|t| t.as_str().map(String::from).ok_or("runner.json: a tier is not a string"))
        .collect::<Result<_, _>>()?;
    if tiers.is_empty() {
        return Err("runner.json: \"tiers\" is empty".into());
    }
    for (i, t) in tiers.iter().enumerate() {
        if !TIERS.contains(&t.as_str()) {
            return Err(format!("runner.json: unknown tier {t:?}; known: {TIERS:?}"));
        }
        if tiers[..i].contains(t) {
            return Err(format!("runner.json: tier {t:?} listed twice"));
        }
    }
    let kinds = match j.get("kinds") {
        None | Some(Value::Null) => None,
        Some(v) => {
            let ks: Vec<String> = v
                .as_array()
                .ok_or("runner.json: \"kinds\" is not an array")?
                .iter()
                .map(|k| k.as_str().map(String::from).ok_or("runner.json: a kind is not a string"))
                .collect::<Result<_, _>>()?;
            if ks.is_empty() {
                return Err("runner.json: \"kinds\" is empty (omit it to mean every kind)".into());
            }
            if let Some(bad) = ks.iter().find(|k| !WIRE_KINDS.contains(&k.as_str())) {
                return Err(format!("runner.json: unknown wire kind {bad:?}; known: {WIRE_KINDS:?}"));
            }
            if let Some((_, k)) = ks.iter().enumerate().find(|(i, k)| ks[..*i].contains(k)) {
                return Err(format!("runner.json: wire kind {k:?} listed twice"));
            }
            if !tiers.iter().any(|t| t == "wire") {
                return Err("runner.json: \"kinds\" declared without the wire tier".into());
            }
            Some(ks)
        }
    };
    Ok(Scope {
        version: j["version"].as_str().map(String::from).ok_or("runner.json: no \"version\"")?,
        tiers,
        cost: flag(j, "cost", true)?,
        kinds,
        activation: flag(j, "activation", true)?,
    })
}

/// The runner.json of a runner the implementation hosts: the scope keys and nothing SANTA sets.
fn parse_hosted_manifest(j: &Value) -> Result<Scope, String> {
    let obj = j.as_object().ok_or("runner.json: not an object")?;
    if let Some(k) = obj.keys().find(|k| !SCOPE_KEYS.contains(&k.as_str())) {
        return Err(format!("runner.json: {k:?} is not the implementation's to set; it holds only {SCOPE_KEYS:?}"));
    }
    parse_scope(j)
}

/// A hosted runner in its checkout: the scope from `<path>/runner.json`, and the santa-run beside it.
fn hosted_runner(checkout: &Path, path: &str) -> Result<(Scope, PathBuf), String> {
    let dir = checkout.join(path);
    let text = fs::read_to_string(dir.join("runner.json")).map_err(|e| format!("{path}/runner.json: {e}"))?;
    let j: Value = serde_json::from_str(&text).map_err(|e| format!("{path}/runner.json: {e}"))?;
    let scope = parse_hosted_manifest(&j).map_err(|e| format!("{path}/{e}"))?;
    let run = dir.join("santa-run");
    if !is_executable(&run) {
        return Err(format!("{path}/santa-run: no executable file"));
    }
    Ok((scope, run))
}

/// presence-as-state: a runner dir is valid iff it has a runner.json conform accepts and either an executable santa-run
/// or a `runner` key (its santa-run is in the implementation's checkout).
fn discover(runners_dir: &Path) -> Vec<Runner> {
    let mut dirs: Vec<PathBuf> = fs::read_dir(runners_dir)
        .map(|rd| {
            rd.flatten()
                .map(|e| e.path())
                .filter(|p| p.is_dir() && p.join("runner.json").is_file())
                .collect()
        })
        .unwrap_or_default();
    dirs.sort();
    let mut out = Vec::new();
    for d in dirs {
        match discover_dir(&d) {
            Ok(Some(r)) => out.push(r),
            Ok(None) => eprintln!("  skip {}: no executable santa-run", d.file_name().unwrap().to_string_lossy()),
            Err(e) => die(format!("{}: {e}", d.display())),
        }
    }
    out
}

fn is_executable(p: &Path) -> bool {
    p.is_file() && fs::metadata(p).map(|m| m.permissions().mode() & 0o111 != 0).unwrap_or(false)
}

/// One `runners/<name>/` dir: Ok(None) = skipped (a SANTA-hosted runner without an executable santa-run); Err = a
/// manifest conform refuses, or a santa-run beside a `runner` (two runners would claim the entry).
fn discover_dir(d: &Path) -> Result<Option<Runner>, String> {
    let j: Value = serde_json::from_str(&fs::read_to_string(d.join("runner.json")).map_err(|e| e.to_string())?)
        .map_err(|e| format!("bad runner.json: {e}"))?;
    let r = parse_manifest(&j, d)?;
    match (&r.host, is_executable(&d.join("santa-run"))) {
        (Host::Santa { .. }, false) => Ok(None),
        (Host::Impl(p), true) => Err(format!("a santa-run here and \"runner\": {p:?} both claim this runner")),
        _ => Ok(Some(r)),
    }
}

fn git(args: &[&str]) -> Result<(), i32> {
    match Command::new("git").args(args).status() {
        Ok(s) if s.success() => Ok(()),
        Ok(s) => Err(s.code().unwrap_or(1)),
        Err(_) => Err(1),
    }
}

fn git_out(args: &[&str]) -> Option<String> {
    Command::new("git").args(args).output().ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
}

/// An impl, checked out: santa-run's `<impl-path>` argument (`-` for none), the checkout itself, its HEAD sha.
struct Resolved {
    arg: String,
    checkout: Option<PathBuf>,
    sha: Option<String>,
}

/// impl = "<url>#<ref>" (ref = branch/tag for latest, a <sha>, or "<branch>@<sha>"): clone/fetch into
/// cache_dir/<name> and check out the ref, detached. `arg` is cache_dir (passed to santa-run as <impl-path>, where the
/// runner finds its dep as ./<name>). A plain path (no `#`) is graded in place (`in_place`). impl null -> ("-", no
/// checkout, no sha). `name` is the configured impl's repo name, so an override pointing at a dir called something
/// else is still found where the runner looks.
fn resolve_impl(impl_: Option<&str>, name: &str, cache_dir: &Path) -> Result<Resolved, i32> {
    let spec = match impl_ {
        Some(s) if !s.is_empty() => s,
        _ => return Ok(Resolved { arg: "-".to_string(), checkout: None, sha: None }),
    };
    let Some((url, refspec)) = spec.split_once('#') else {
        return Ok(in_place(Path::new(spec), name, cache_dir));
    };
    if url.is_empty() || refspec.is_empty() {
        die(format!("bad impl '{spec}': expected <url>#<ref>, or a path to grade in place"));
    }
    let (branch, sha) = match refspec.split_once('@') {
        Some((b, s)) => (b, Some(s)),
        None => (refspec, None),
    };
    fs::create_dir_all(cache_dir).ok();
    let dest = cache_dir.join(name);
    let dest_s = dest.to_str().unwrap();
    if !dest.join(".git").is_dir() {
        git(&["clone", "-q", url, dest_s])?;
    }
    // `<url>#<ref>` is THAT url's ref, whichever url the cached clone came from (an override may have pointed it at a
    // local checkout): origin follows the spec, and a branch is checked out as origin's tip, detached, never as a
    // local branch that an earlier origin (or a force-push upstream) left elsewhere; --force lets a tag the earlier
    // origin held on another commit follow too.
    git(&["-C", dest_s, "remote", "set-url", "origin", url])?;
    git(&["-C", dest_s, "fetch", "-q", "--all", "--tags", "--prune", "--force"])?;
    let resolves = |r: &str| git_out(&["-C", dest_s, "rev-parse", "-q", "--verify", r]).is_some();
    let target = match sha {
        Some(s) => s.to_string(), // @sha pins
        None if resolves(&format!("refs/remotes/origin/{branch}")) => format!("origin/{branch}"),
        None => branch.to_string(), // a tag or a sha
    };
    // A commit no branch holds (a PR's base under the checkout's origin/main only) is not fetched by --all: by sha.
    if !resolves(&format!("{target}^{{commit}}")) {
        let _ = git(&["-C", dest_s, "fetch", "-q", "origin", &target]);
    }
    git(&["-C", dest_s, "checkout", "-q", "--detach", &target])?;
    let resolved_sha = git_out(&["-C", dest_s, "rev-parse", "HEAD"]);
    Ok(Resolved { arg: cache_dir.to_string_lossy().into_owned(), checkout: Some(dest), sha: resolved_sha })
}

/// A plain path, graded in place, uncommitted edits included: `<cache_dir>/local/<name>` links to it (the clone cache
/// is left alone), and the sha carries `-dirty` when the tree has uncommitted changes. The checkout handed to a hosted
/// runner is the tree's REAL path, not the link: run through the link, its build would record the same crates under
/// two paths in one target dir, and the developer's next build of it there would fail.
fn in_place(dir: &Path, name: &str, cache_dir: &Path) -> Resolved {
    let dir = fs::canonicalize(dir).unwrap_or_else(|e| die(format!("impl {}: {e}", dir.display())));
    let here = cache_dir.join("local");
    fs::create_dir_all(&here).ok();
    let link = here.join(name);
    let _ = fs::remove_file(&link);
    symlink(&dir, &link).unwrap_or_else(|e| die(format!("{}: {e}", link.display())));
    let d = dir.to_str().unwrap();
    let sha = git_out(&["-C", d, "rev-parse", "HEAD"]).map(|head| {
        match git_out(&["-C", d, "status", "--porcelain"]) {
            Some(_) => format!("{head}-dirty"), // git_out maps an empty (clean) status to None
            None => head,
        }
    });
    Resolved { arg: here.to_string_lossy().into_owned(), checkout: Some(dir), sha }
}

/// Why a runner has no grades this run (a ⚠️ row, not a 🪨).
#[derive(Debug)]
enum RunFail {
    /// santa-run exited non-zero: build, impl checkout, or a crash over the ordinary files.
    Exit(i32),
    /// The run hit conform's safety ceiling ("time 1800 s"): it protects the machine and never grades.
    Ceiling(String),
    /// santa-run ran, but its results.json cannot be used (absent, not JSON, failing its schema, another commit).
    Results(String),
    /// The implementation's checkout holds no runner where SANTA's `runner` points, or one conform refuses.
    Hosted(String),
}

fn fail_message(f: &RunFail) -> String {
    match f {
        RunFail::Exit(c) => format!("santa-run exited {c}: could not build/run"),
        RunFail::Ceiling(l) => format!("santa-run exceeded the safety ceiling ({l})"),
        RunFail::Results(m) => format!("santa-run's outputs: {m}"),
        RunFail::Hosted(m) => format!("the implementation's runner: {m}"),
    }
}

/// The whole-run ceiling (runner-integration §3a): it protects the machine and never grades.
struct Ceiling {
    data_mb: u64,
    wall_s: u64,
}

/// RLIMIT_DATA at twice the largest budget ./validate allows (4096 MiB), so a runner's backstop for a bounded child
/// (`max(4096, 2 × memory_mb)`) fits beneath it; a wall timeout long enough for a cold build (plan 3 measured < 1 min).
const CEILING: Ceiling = Ceiling { data_mb: 8192, wall_s: 1800 };

/// Runs `cmd` in a process group of its own under the ceiling: RLIMIT_DATA per process (soft = hard), and the whole
/// group killed at the wall timeout. A group member left behind by a run that exited is killed too.
fn run_under_ceiling(cmd: &mut Command, c: &Ceiling) -> Result<ExitStatus, RunFail> {
    use std::os::unix::process::CommandExt;
    let bytes = (c.data_mb * 1024 * 1024) as libc::rlim_t;
    // SAFETY: setsid and setrlimit are async-signal-safe; the closure allocates nothing.
    unsafe {
        cmd.pre_exec(move || {
            if libc::setsid() == -1 {
                return Err(std::io::Error::last_os_error());
            }
            let lim = libc::rlimit { rlim_cur: bytes, rlim_max: bytes };
            if libc::setrlimit(libc::RLIMIT_DATA, &lim) != 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let mut child = cmd.spawn().map_err(|_| RunFail::Exit(1))?;
    let group = child.id() as libc::pid_t;
    // SAFETY: killpg on the group this function created.
    let kill_group = || unsafe {
        libc::killpg(group, libc::SIGKILL);
    };
    let deadline = Instant::now() + Duration::from_secs(c.wall_s);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                kill_group();
                return Ok(status);
            }
            Ok(None) if Instant::now() >= deadline => {
                kill_group();
                let _ = child.wait();
                return Err(RunFail::Ceiling(format!("time {} s", c.wall_s)));
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(100)),
            Err(_) => {
                kill_group();
                return Err(RunFail::Exit(1));
            }
        }
    }
}

/// The actuals schemas, one validator per tier (`schema/santa-<tier>.actuals.schema.json`), built once per run. The
/// eval tier's cross-references the eval vector schema's SValue, registered under its `$id`.
struct ActualsSchemas(BTreeMap<String, Validator>);

impl ActualsSchemas {
    fn load(root: &Path) -> ActualsSchemas {
        let dir = root.join("schema");
        let read = |name: &str| -> Value {
            let p = dir.join(name);
            serde_json::from_str(&fs::read_to_string(&p).unwrap_or_else(|e| die(format!("{}: {e}", p.display()))))
                .unwrap_or_else(|e| die(format!("{}: {e}", p.display())))
        };
        let vector = read("santa-eval.vector.schema.json");
        let id = vector["$id"].as_str().unwrap_or_else(|| die("santa-eval.vector.schema.json has no $id".into())).to_string();
        let registry = Registry::new()
            .add(id, Resource::from_contents(vector))
            .and_then(|r| r.prepare())
            .unwrap_or_else(|e| die(format!("the eval vector schema: {e}")));
        let tiers = ["eval", "wire", "transaction", "block", "chain", "authds", "nipopow"].map(|t| {
            let name = format!("santa-{t}.actuals.schema.json");
            let v = jsonschema::options()
                .with_registry(&registry)
                .build(&read(&name))
                .unwrap_or_else(|e| die(format!("{name}: {e}")));
            (t.to_string(), v)
        });
        ActualsSchemas(tiers.into_iter().collect())
    }

    /// Why one entry's actual fails its tier's actuals schema, or None when it holds. Where the entry may take the
    /// tier's own result or the tier-neutral not-implemented (an `anyOf`), the reason given is the tier result's.
    fn fault(&self, tier: &str, name: &str, actual: &Value) -> Option<String> {
        let v = self.0.get(tier).unwrap_or_else(|| die(format!("no actuals schema for the tier {tier}")));
        let doc = Value::Object([(name.to_string(), actual.clone())].into_iter().collect());
        let err = v.validate(&doc).err()?;
        let errs: Vec<&ValidationError> = match err.kind() {
            ValidationErrorKind::AnyOf { context } => context.first().map(|b| b.iter().collect()).unwrap_or_default(),
            _ => vec![&err],
        };
        let errs = if errs.is_empty() { vec![&err] } else { errs };
        // An error's instance path is /<entry>/<field>…; the entry is the red row's own, so the field is enough.
        let said: Vec<String> = errs
            .iter()
            .take(3)
            .map(|e| {
                let path = e.instance_path().to_string();
                match path.splitn(3, '/').nth(2) {
                    Some(field) if !field.is_empty() => format!("{field}: {}", brief(&Value::String(e.to_string()), 160)),
                    _ => brief(&Value::String(e.to_string()), 160),
                }
            })
            .collect();
        Some(format!("the actual fails santa-{tier}.actuals.schema.json: {}", said.join("; ")))
    }
}

/// The same actual for every entry of `vector`.
fn each_entry(vector: &Value, a: Value) -> Value {
    let names = vector["entries"].as_array().into_iter().flatten().filter_map(|e| e["name"].as_str());
    Value::Object(names.map(|n| (n.to_string(), a.clone())).collect())
}

/// One file's actuals as the runner wrote them, held to its tier's actuals schema (runner-integration §3): an entry
/// whose actual fails it is `panicked`, the note saying why, and a file that is not JSON or not a JSON object fails
/// every entry so; an absent file is {}.
fn actuals_for(ofile: &Path, vector: &Value, schemas: &ActualsSchemas, tier: &str) -> Value {
    // Totality: an absent file becomes {} so every entry grades (act None -> coal), surfacing the breach rather than
    // silently dropping the vector.
    if !ofile.is_file() {
        return json!({});
    }
    let panicked = |note: String| json!({"error": "panicked", "note": note});
    match serde_json::from_str::<Value>(&fs::read_to_string(ofile).unwrap()) {
        Err(e) => each_entry(vector, panicked(format!("the actuals file is not JSON: {e}"))),
        Ok(Value::Object(m)) => Value::Object(
            m.into_iter()
                .map(|(n, a)| match schemas.fault(tier, &n, &a) {
                    Some(note) => (n, panicked(note)),
                    None => (n, a),
                })
                .collect(),
        ),
        Ok(_) => each_entry(vector, panicked("the actuals file is not a JSON object".into())),
    }
}

/// The commit of conform's own checkout, which a runner's results must name (runner-integration §4.4).
fn head(root: &Path) -> Option<String> {
    git_out(&["-C", root.to_str()?, "rev-parse", "HEAD"])
}

/// The schema of a runner's results.json (santa-runner-results/v1).
fn load_results_schema(root: &Path) -> Validator {
    let p = root.join("schema").join("santa-runner-results.schema.json");
    let doc: Value = serde_json::from_str(&fs::read_to_string(&p).unwrap_or_else(|e| die(format!("{}: {e}", p.display()))))
        .unwrap_or_else(|e| die(format!("{}: {e}", p.display())));
    jsonschema::validator_for(&doc).unwrap_or_else(|e| die(format!("{}: {e}", p.display())))
}

/// A runner's `<out-dir>/results.json`, if conform can use it: present, JSON, valid against its schema, and graded
/// against this checkout's commit. Otherwise why not.
fn read_results(out: &Path, v: &Validator, head: &str) -> Result<Value, String> {
    let text = fs::read_to_string(out.join("results.json")).map_err(|_| "no results.json".to_string())?;
    let doc: Value = serde_json::from_str(&text).map_err(|e| format!("results.json is not JSON: {e}"))?;
    if let Err(e) = v.validate(&doc) {
        let at = e.instance_path().to_string();
        return Err(format!("results.json fails santa-runner-results.schema.json: {}{}",
            brief(&Value::String(e.to_string()), 200), if at.is_empty() { String::new() } else { format!(" (at {at})") }));
    }
    match doc["santa"].as_str() {
        Some(s) if s == head => Ok(doc),
        s => Err(format!("results.json graded SANTA {}, not this checkout's {head}", s.unwrap_or("null"))),
    }
}

/// The runner's actuals for every file of conform's selection, from `<out-dir>/actuals/<relpath>` (actuals_for: held
/// to the tier's schema entry by entry; an absent file is {}).
fn read_actuals(out: &Path, selected: &[&Vec5], root: &Path, schemas: &ActualsSchemas) -> BTreeMap<String, Value> {
    selected
        .iter()
        .map(|v| {
            let vector: Value =
                serde_json::from_str(&fs::read_to_string(root.join("vectors").join(&v.0)).unwrap()).unwrap();
            (v.0.clone(), actuals_for(&out.join("actuals").join(&v.0), &vector, schemas, &v.1))
        })
        .collect()
}

/// What every runner is run against: the checkout (`<santa-dir>`), its vectors and their wire facts, the schemas a
/// runner's outputs are held to, and the commit its results must name.
struct Corpus<'a> {
    root: &'a Path,
    vectors: &'a [Vec5],
    facts: &'a BTreeMap<String, WireFacts>,
    schemas: &'a ActualsSchemas,
    results_schema: &'a Validator,
    head: &'a str,
}

/// What a runner's run yields: its actuals over conform's selection by relpath, the bounded files that exceeded or
/// crashed, and its own results.json.
type RunOutputs = (BTreeMap<String, Value>, Vec<String>, Value);

/// A runner that ran: the scope it was graded at, its actuals over conform's selection by relpath, the impl sha, the
/// bounded files that exceeded or crashed, and its own results.json.
struct Ran {
    scope: Scope,
    actuals: BTreeMap<String, Value>,
    sha: Option<String>,
    failures: Vec<String>,
    results: Value,
}

/// Runs one runner. A failure carries the scope when conform got far enough to know it (a hosted runner's is read
/// from its checkout), so the grid can still say which tiers went ungraded.
fn run_one(m: &Runner, corpus: &Corpus) -> Result<Ran, (Option<Scope>, RunFail)> {
    let ws = corpus.root.join(".santa").join(&m.name);
    let imp = resolve_impl(m.impl_.as_deref(), m.impl_name.as_deref().unwrap_or(""), &ws)
        .map_err(|c| (m.declared().cloned(), RunFail::Exit(c)))?;
    let (scope, run) = match &m.host {
        Host::Santa { scope, run } => (scope.clone(), run.clone()),
        Host::Impl(path) => {
            let checkout = imp.checkout.as_deref().expect("a runner entry has an impl (parse_manifest)");
            hosted_runner(checkout, path).map_err(|e| (None, RunFail::Hosted(e)))?
        }
    };
    match run_runner(&run, &imp.arg, &scope, &ws, corpus) {
        Ok((actuals, failures, results)) => Ok(Ran { scope, actuals, sha: imp.sha, failures, results }),
        Err(f) => Err((Some(scope), f)),
    }
}

/// Runs a self-contained runner (runner-integration §3): `santa-run <impl-path> <santa-dir> <out-dir>` under the
/// whole-run ceiling, `<out-dir>` = `<ws>/out`, emptied first. Then its results.json (schema, commit) and its actuals
/// over conform's own selection: (actuals by relpath, bounded failures, results.json).
fn run_runner(
    run: &Path,
    impl_path: &str,
    scope: &Scope,
    ws: &Path,
    corpus: &Corpus,
) -> Result<RunOutputs, RunFail> {
    let out = ws.join("out");
    let _ = fs::remove_dir_all(&out);
    fs::create_dir_all(&out).unwrap();
    let status = run_under_ceiling(Command::new(run).arg(impl_path).arg(corpus.root).arg(&out), &CEILING)?;
    if !status.success() {
        return Err(RunFail::Exit(status.code().unwrap_or(1)));
    }
    let results = read_results(&out, corpus.results_schema, corpus.head).map_err(RunFail::Results)?;
    let selected = selection(corpus.vectors, corpus.facts, scope);
    let actuals = read_actuals(&out, &selected, corpus.root, corpus.schemas);
    let failures = bounded_failures(&actuals, corpus.root);
    Ok((actuals, failures, results))
}

/// The bounded files (a vector with `limits`) on which the runner reported `exceeded` or `panicked`: for a control,
/// a budget or a row is wrong (runner-integration §3a).
fn bounded_failures(actuals: &BTreeMap<String, Value>, root: &Path) -> Vec<String> {
    actuals
        .iter()
        .filter(|(rel, act)| {
            let failed = act.as_object().is_some_and(|m| {
                m.values().any(|a| matches!(a["error"].as_str(), Some("exceeded" | "panicked")))
            });
            failed && fs::read_to_string(root.join("vectors").join(rel))
                .ok()
                .and_then(|t| serde_json::from_str::<Value>(&t).ok())
                .is_some_and(|v| v.get("limits").is_some())
        })
        .map(|(rel, _)| rel.clone())
        .collect()
}

#[derive(Default)]
struct Counts {
    value_total: u64,
    value_nice: u64,
    value_coal: u64,
    not_impl: u64,
    cost_graded: u64,
    cost_nice: u64,
    cost_coal: u64,
    reject_total: u64,
    reject_nice: u64,
    reject_coal: u64,
    panicked: u64,
    // a bounded file over its budget: the runner's tier-neutral `exceeded` (runner-integration §3a)
    exceeded: u64,
    roundtrip_total: u64,
    roundtrip_nice: u64,
    roundtrip_coal: u64,
    // transaction tier: dedicated valid dim (mirrors wire's dedicated roundtrip fields);
    // cost dim reuses cost_graded/cost_nice/cost_coal (shared concept across eval + tx).
    tx_valid_total: u64,
    tx_valid_nice: u64,
    tx_valid_coal: u64,
    // block tier: dedicated valid + post_digest dims;
    // cost dim reuses cost_graded/cost_nice/cost_coal (shared concept across tiers).
    block_valid_total: u64,
    block_valid_nice: u64,
    block_valid_coal: u64,
    post_digest_total: u64,
    post_digest_nice: u64,
    post_digest_coal: u64,
    // chain tier: single value dimension (nbits for retargeting; parameters+activated_update for voting).
    // cost is not-applicable for chain — cost flag and shared cost counters are irrelevant here.
    chain_value_total: u64,
    chain_value_nice: u64,
    chain_value_coal: u64,
    // authds tier: prove has two INDEPENDENT dims (proof bytes, digests) with no
    // suppression; verify chains accepted -> results -> digest. digest counters are
    // shared by both kinds (a prove digest and a verify digest are the same concept).
    authds_proof_total: u64,
    authds_proof_nice: u64,
    authds_proof_coal: u64,
    authds_digest_total: u64,
    authds_digest_nice: u64,
    authds_digest_coal: u64,
    authds_accepted_total: u64,
    authds_accepted_nice: u64,
    authds_accepted_coal: u64,
    authds_results_total: u64,
    authds_results_nice: u64,
    authds_results_coal: u64,
    // nipopow tier: two independent dims — interlinks and proof.
    nipopow_interlinks_total: u64,
    nipopow_interlinks_nice: u64,
    nipopow_interlinks_coal: u64,
    nipopow_proof_total: u64,
    nipopow_proof_nice: u64,
    nipopow_proof_coal: u64,
    red: Vec<Value>,
}

impl Counts {
    fn red_total(&self) -> u64 {
        self.value_coal + self.not_impl + self.cost_coal + self.reject_coal + self.panicked + self.exceeded + self.roundtrip_coal + self.tx_valid_coal + self.block_valid_coal + self.post_digest_coal + self.chain_value_coal
        + self.authds_proof_coal + self.authds_digest_coal
        + self.authds_accepted_coal + self.authds_results_coal
        + self.nipopow_interlinks_coal + self.nipopow_proof_coal
    }
    fn to_json(&self) -> Value {
        json!({
            "value_total": self.value_total, "value_nice": self.value_nice, "value_coal": self.value_coal,
            "not_impl": self.not_impl,
            "cost_graded": self.cost_graded, "cost_nice": self.cost_nice, "cost_coal": self.cost_coal,
            "reject_total": self.reject_total, "reject_nice": self.reject_nice, "reject_coal": self.reject_coal,
            "panicked": self.panicked, "exceeded": self.exceeded,
            "roundtrip_total": self.roundtrip_total, "roundtrip_nice": self.roundtrip_nice, "roundtrip_coal": self.roundtrip_coal,
            "tx_valid_total": self.tx_valid_total, "tx_valid_nice": self.tx_valid_nice, "tx_valid_coal": self.tx_valid_coal,
            "block_valid_total": self.block_valid_total, "block_valid_nice": self.block_valid_nice, "block_valid_coal": self.block_valid_coal,
            "post_digest_total": self.post_digest_total, "post_digest_nice": self.post_digest_nice, "post_digest_coal": self.post_digest_coal,
            "chain_value_total": self.chain_value_total, "chain_value_nice": self.chain_value_nice, "chain_value_coal": self.chain_value_coal,
            "authds_proof_total": self.authds_proof_total, "authds_proof_nice": self.authds_proof_nice, "authds_proof_coal": self.authds_proof_coal,
            "authds_digest_total": self.authds_digest_total, "authds_digest_nice": self.authds_digest_nice, "authds_digest_coal": self.authds_digest_coal,
            "authds_accepted_total": self.authds_accepted_total, "authds_accepted_nice": self.authds_accepted_nice, "authds_accepted_coal": self.authds_accepted_coal,
            "authds_results_total": self.authds_results_total, "authds_results_nice": self.authds_results_nice, "authds_results_coal": self.authds_results_coal,
            "nipopow_interlinks_total": self.nipopow_interlinks_total, "nipopow_interlinks_nice": self.nipopow_interlinks_nice, "nipopow_interlinks_coal": self.nipopow_interlinks_coal,
            "nipopow_proof_total": self.nipopow_proof_total, "nipopow_proof_nice": self.nipopow_proof_nice, "nipopow_proof_coal": self.nipopow_proof_coal,
            "red": self.red,
        })
    }
    /// The human summary line printed after "tier/version/prov: " in the run loop.
    fn summary(&self) -> String {
        let mut bits: Vec<String> = Vec::new();
        // wire slices carry only the round-trip dimension (+ any coverage/panicked gaps below).
        if self.roundtrip_total > 0 {
            bits.push(format!("roundtrip {}/{}", self.roundtrip_nice, self.roundtrip_total));
        }
        if self.value_total > 0 {
            bits.push(format!("value {}/{}", self.value_nice, self.value_total));
            if self.value_coal > 0 {
                bits.push(format!("{} val-coal", self.value_coal));
            }
        }
        // not-impl renders independent of value coverage: a slice can be entirely coverage-gaps
        // (value_total 0, e.g. dasher's eval/v6/authored) and must still show it.
        if self.not_impl > 0 {
            bits.push(format!("{} not-impl", self.not_impl));
        }
        if self.panicked > 0 {
            bits.push(format!("{} panicked", self.panicked));
        }
        if self.exceeded > 0 {
            bits.push(format!("{} exceeded", self.exceeded));
        }
        // transaction slices: valid dim first, then cost (cost reuses the shared cost counters).
        if self.tx_valid_total > 0 {
            bits.push(format!("valid {}/{}", self.tx_valid_nice, self.tx_valid_total));
        }
        // block slices: valid · digest · cost (cost reuses shared cost counters).
        if self.block_valid_total > 0 {
            bits.push(format!("valid {}/{}", self.block_valid_nice, self.block_valid_total));
            if self.block_valid_coal > 0 {
                bits.push(format!("{} val-coal", self.block_valid_coal));
            }
            if self.post_digest_total > 0 {
                bits.push(format!("digest {}/{}", self.post_digest_nice, self.post_digest_total));
            }
        }
        // chain slices: a single value dimension (nbits / parameters+update).
        if self.chain_value_total > 0 {
            bits.push(format!("value {}/{}", self.chain_value_nice, self.chain_value_total));
            if self.chain_value_coal > 0 {
                bits.push(format!("{} val-coal", self.chain_value_coal));
            }
        }
        // authds slices: prove's independent proof+digest dims, then verify's chained
        // accepted/results dims. digest is shared by both kinds (one shown total, not two) —
        // no single dim plays "valid"/"value"'s gate role here, so none gets an extra coal bit;
        // nice/total says everything (a slice can be pure-prove, pure-verify, or a mix of both).
        if self.authds_proof_total > 0 {
            bits.push(format!("proof {}/{}", self.authds_proof_nice, self.authds_proof_total));
        }
        if self.authds_digest_total > 0 {
            bits.push(format!("digest {}/{}", self.authds_digest_nice, self.authds_digest_total));
        }
        if self.authds_accepted_total > 0 {
            bits.push(format!("accepted {}/{}", self.authds_accepted_nice, self.authds_accepted_total));
        }
        if self.authds_results_total > 0 {
            bits.push(format!("results {}/{}", self.authds_results_nice, self.authds_results_total));
        }
        // nipopow slices: two independent dims — interlinks and proof.
        if self.nipopow_interlinks_total > 0 {
            bits.push(format!("interlinks {}/{}", self.nipopow_interlinks_nice, self.nipopow_interlinks_total));
        }
        if self.nipopow_proof_total > 0 {
            bits.push(format!("proof {}/{}", self.nipopow_proof_nice, self.nipopow_proof_total));
        }
        if self.cost_graded > 0 {
            bits.push(format!("cost {}/{}", self.cost_nice, self.cost_graded));
        }
        if self.reject_total > 0 {
            bits.push(format!("reject {}/{}", self.reject_nice, self.reject_total));
        }
        bits.join(" · ")
    }
}

/// Compact one-line rendering of a JSON value for red-entry detail cells, truncated
/// (char-safe) with an ellipsis — value JSONs can be huge (Coll items, headers).
fn brief(v: &Value, max: usize) -> String {
    let s = match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    };
    if s.chars().count() > max {
        let mut out: String = s.chars().take(max).collect();
        out.push('\u{2026}');
        out
    } else {
        s
    }
}

/// The expected side of one red entry, by tier: eval "value @ cost" (or the expected
/// error tag), wire "roundtrip <bytes…>" (the bytes the wire grading type compares against:
/// `expected_bytes_hex` when present, else the input) or a reject entry's error tag,
/// tx "valid <v> @ cost <c>", chain per-kind phrase (nbits / parameters+update).
fn expected_brief(e: &Value, is_wire: bool, is_tx: bool, is_chain: bool) -> String {
    let exp = &e["expected"];
    if is_wire {
        match e["error"].as_str() {
            Some(err) => err.to_string(),
            None => format!("roundtrip {}", brief(e.get("expected_bytes_hex").unwrap_or(&e["bytes_hex"]), 48)),
        }
    } else if is_tx {
        format!("valid {} @ cost {}", exp["valid"], exp["cost"])
    } else if is_chain {
        match e["kind"].as_str() {
            Some("retargeting") => format!("nbits {}", exp["nbits"]),
            Some("voting") => format!("parameters {} @ update {}",
                brief(&exp["parameters"]["table"], 96), exp["activated_update"]),
            _ => format!("chain {}", brief(exp, 96)),
        }
    } else if let Some(err) = exp["error"].as_str() {
        err.to_string()
    } else {
        format!("{} @ cost {}", brief(&exp["value"], 96), exp["cost"])
    }
}

/// The actual side, by tier; an error outcome renders as its tag (the note travels
/// separately), a missing actual loudly as such.
fn got_brief(actual: &Value, is_wire: bool, is_tx: bool) -> String {
    if actual.is_null() {
        return "(no actual emitted)".to_string();
    }
    if let Some(err) = actual["error"].as_str() {
        return err.to_string();
    }
    if is_wire {
        brief(&actual["bytes_hex"], 48)
    } else if is_tx {
        format!("valid {} @ cost {}", actual["valid"], actual["cost"])
    } else {
        format!("{} @ cost {}", brief(&actual["value"], 96), actual["cost"])
    }
}

/// One red-entry detail: op/entry/dim + the expected/got briefs (the scoreboard's
/// coal-table columns) + optional note and the vector entry's script (description).
fn red_detail(op: &str, name: &str, dim: &str, expected: String, got: String,
              note: Option<Value>, script: Option<&str>) -> Value {
    let mut d = json!({"op": op, "entry": name, "dim": dim,
                       "expected": expected, "got": got});
    if let Some(n) = note {
        d["note"] = n;
    }
    if let Some(s) = script {
        d["script"] = json!(s);
    }
    d
}

/// A red record with the vector file it belongs to (`file`, its relpath): the key the canary compares by.
fn with_file(mut r: Value, rel: &str) -> Value {
    r["file"] = json!(rel);
    r
}

/// The most disagreements a runner's row lists (`grader.disagreement_count` keeps the true count).
const MAX_DISAGREEMENTS: usize = 200;

/// The canary (runner-integration §4.4): where a runner's own verdicts — its red records and the files it says it
/// graded — differ from SANTA's re-grade of its actuals over conform's own selection, keyed by (file, entry, dim).
fn grader(runner: &Value, canary: &BTreeMap<(String, String, String), Counts>, selected: &BTreeSet<String>) -> Value {
    type Key = (String, String, String);
    let key = |r: &Value| -> Option<Key> {
        Some((r["file"].as_str()?.to_string(), r["entry"].as_str()?.to_string(), r["dim"].as_str()?.to_string()))
    };
    let slices: Vec<&Value> = runner["slices"].as_object().into_iter().flat_map(|m| m.values()).collect();
    let runner_red: BTreeSet<Key> =
        slices.iter().flat_map(|s| s["red"].as_array().into_iter().flatten()).filter_map(key).collect();
    let runner_files: BTreeSet<String> = slices
        .iter()
        .flat_map(|s| s["files"].as_array().into_iter().flatten())
        .filter_map(|f| f.as_str().map(String::from))
        .collect();
    let santa_red: BTreeSet<Key> = canary.values().flat_map(|c| c.red.iter()).filter_map(key).collect();
    let mut disagreements: Vec<Value> = runner_red
        .symmetric_difference(&santa_red)
        .map(|k| {
            let (runner, santa) = if runner_red.contains(k) { ("red", "nice") } else { ("nice", "red") };
            json!({"file": k.0, "entry": k.1, "dim": k.2, "runner": runner, "santa": santa})
        })
        .collect();
    let count = disagreements.len();
    disagreements.truncate(MAX_DISAGREEMENTS);
    let missing: Vec<&String> = selected.difference(&runner_files).collect();
    let extra: Vec<&String> = runner_files.difference(selected).collect();
    json!({
        "agrees": count == 0 && missing.is_empty() && extra.is_empty(),
        "missing_files": missing, "extra_files": extra,
        "disagreement_count": count, "disagreements": disagreements,
    })
}

/// The grading rules, `vectors/grading.json`, parsed once per run; a file santa-check cannot parse is a SANTA defect.
fn load_grading(root: &Path) -> Grading {
    let p = root.join("vectors").join(GRADING_FILE);
    let doc: Value = serde_json::from_str(&fs::read_to_string(&p).unwrap_or_else(|e| die(format!("{}: {e}", p.display()))))
        .unwrap_or_else(|e| die(format!("{}: {e}", p.display())));
    Grading::parse(&doc).unwrap_or_else(|e| die(format!("{}: {e}", p.display())))
}

/// The counters a (tier, dimension) feeds: (total, nice, coal), or None for a dimension the board has no counter for
/// (`check_grading` refuses such a grading file before any runner runs).
fn counters<'c>(c: &'c mut Counts, tier: &str, dim: &str) -> Option<(&'c mut u64, &'c mut u64, &'c mut u64)> {
    Some(match (tier, dim) {
        (_, "reject") => (&mut c.reject_total, &mut c.reject_nice, &mut c.reject_coal),
        (_, "cost") => (&mut c.cost_graded, &mut c.cost_nice, &mut c.cost_coal),
        ("eval", "value") => (&mut c.value_total, &mut c.value_nice, &mut c.value_coal),
        ("wire", "roundtrip") => (&mut c.roundtrip_total, &mut c.roundtrip_nice, &mut c.roundtrip_coal),
        ("transaction", "valid") => (&mut c.tx_valid_total, &mut c.tx_valid_nice, &mut c.tx_valid_coal),
        ("block", "valid") => (&mut c.block_valid_total, &mut c.block_valid_nice, &mut c.block_valid_coal),
        ("block", "post_digest") => (&mut c.post_digest_total, &mut c.post_digest_nice, &mut c.post_digest_coal),
        ("chain", "value") => (&mut c.chain_value_total, &mut c.chain_value_nice, &mut c.chain_value_coal),
        ("authds", "proof") => (&mut c.authds_proof_total, &mut c.authds_proof_nice, &mut c.authds_proof_coal),
        ("authds", "digest") => (&mut c.authds_digest_total, &mut c.authds_digest_nice, &mut c.authds_digest_coal),
        ("authds", "accepted") => (&mut c.authds_accepted_total, &mut c.authds_accepted_nice, &mut c.authds_accepted_coal),
        ("authds", "results") => (&mut c.authds_results_total, &mut c.authds_results_nice, &mut c.authds_results_coal),
        ("nipopow", "interlinks") => {
            (&mut c.nipopow_interlinks_total, &mut c.nipopow_interlinks_nice, &mut c.nipopow_interlinks_coal)
        }
        ("nipopow", "proof") => (&mut c.nipopow_proof_total, &mut c.nipopow_proof_nice, &mut c.nipopow_proof_coal),
        _ => return None,
    })
}

/// Before any runner runs: every vector file's grading label resolves, and every dimension of every type a file is
/// graded by (its branches' and its `unmatched`) has a board counter, so a grading mistake stops conform at once.
fn check_grading(grading: &Grading, vectors: &[Vec5], root: &Path) -> Result<(), String> {
    let mut reached: std::collections::BTreeSet<(String, String)> = std::collections::BTreeSet::new();
    for (rel, tier, ..) in vectors {
        let p = root.join("vectors").join(rel);
        let file: Value = serde_json::from_str(&fs::read_to_string(&p).map_err(|e| format!("{rel}: {e}"))?)
            .map_err(|e| format!("{rel}: {e}"))?;
        let label = grading.label_for(tier, &file).map_err(|e| format!("{rel}: {e}"))?;
        reached.insert((tier.clone(), label.to_string()));
    }
    for (tier, label) in &reached {
        let t = &grading.types[label];
        let dims = t.branches.iter().flat_map(|b| b.dims.iter().map(|d| &d.name)).chain(t.unmatched.iter());
        for dim in dims {
            if counters(&mut Counts::default(), tier, dim).is_none() {
                return Err(format!(
                    "vectors/grading.json: the type {label:?} grades the {tier} dimension {dim:?}, which the board has no counter for"
                ));
            }
        }
    }
    Ok(())
}

/// One red (tier, dimension)'s detail, rendered for the scoreboard: the expected and the actual side, and a note.
fn red_for(tier: &str, dim: &str, op: &str, name: &str, e: &Value, actual: &Value, script: Option<&str>) -> Value {
    let exp = &e["expected"];
    let (expected, got, note) = match (tier, dim) {
        (_, "reject") => ("errored".to_string(), got_brief(actual, tier == "wire", tier == "transaction"), None),
        (_, "cost") => (format!("cost {}", exp["cost"]), format!("cost {}", actual["cost"]), None),
        ("eval", "value") => (expected_brief(e, false, false, false), got_brief(actual, false, false), None),
        ("wire", "roundtrip") => {
            (expected_brief(e, true, false, false), got_brief(actual, true, false), actual.get("reason").cloned())
        }
        ("transaction", "valid") => (
            format!("valid {}", exp["valid"]),
            got_brief(actual, false, true),
            actual.get("reason").filter(|v| !v.is_null()).cloned(),
        ),
        ("block", "valid") => {
            // Block actuals carry {valid, cost} (like tx): that shape, not the eval `value @ cost`.
            let got = match actual["error"].as_str() {
                Some(err) => err.to_string(),
                None => format!("valid {} @ cost {}", actual["valid"], actual["cost"]),
            };
            (format!("valid {}", exp["valid"]), got, actual.get("reason").filter(|v| !v.is_null()).cloned())
        }
        ("block", "post_digest") => (
            format!("post_digest {}", brief(&exp["post_digest"], 64)),
            format!("post_digest {}", brief(&actual["post_digest"], 64)),
            None,
        ),
        ("chain", "value") => {
            let got = match actual["error"].as_str() {
                Some(err) => err.to_string(),
                None => match e["kind"].as_str() {
                    Some("retargeting") => format!("nbits {}", actual["nbits"]),
                    Some("voting") => format!(
                        "parameters {} @ update {}",
                        brief(&actual["parameters"]["table"], 96),
                        actual["activated_update"]
                    ),
                    _ => brief(actual, 96),
                },
            };
            (expected_brief(e, false, false, true), got, actual.get("note").filter(|v| !v.is_null()).cloned())
        }
        ("authds", "proof") => (format!("proofs {}", exp["proofs"]), format!("proofs {}", actual["proofs"]), None),
        ("authds", "digest") if e["kind"] == "avl_verify" => (
            format!("new_digest_hex {}", exp["new_digest_hex"]),
            format!("new_digest_hex {}", actual["new_digest_hex"]),
            None,
        ),
        ("authds", "digest") => (format!("digests {}", exp["digests"]), format!("digests {}", actual["digests"]), None),
        ("authds", "accepted") => (
            format!("proof_accepted {}", exp["proof_accepted"]),
            format!("proof_accepted {}", actual["proof_accepted"]),
            None,
        ),
        ("authds", "results") => (format!("results {}", exp["results"]), format!("results {}", actual["results"]), None),
        ("nipopow", "interlinks") => ("interlinks match".to_string(), "interlinks diverge".to_string(), None),
        ("nipopow", "proof") => (
            format!("proofHex {}", brief(&exp["proofHex"], 48)),
            format!("proofHex {}", brief(actual.get("proofHex").unwrap_or(&Value::Null), 48)),
            None,
        ),
        _ => die(format!("vectors/grading.json: the board renders no {tier} dimension {dim:?}")),
    };
    red_detail(op, name, dim, expected, got, note, script)
}

/// actuals: {relpath: actuals_obj}, graded through `vectors/grading.json`. Returns {(tier,version,provenance): counts
/// + red}, slice keys ordered (BTreeMap), entries in vector order, an entry's dimensions in its branch's order.
fn tally(
    actuals: &BTreeMap<String, Value>,
    claims_cost: bool,
    root: &Path,
    grading: &Grading,
) -> BTreeMap<(String, String, String), Counts> {
    let mut slices: BTreeMap<(String, String, String), Counts> = BTreeMap::new();
    for (rel, act) in actuals {
        let (tier, version, prov, op) = parse_relpath(rel);
        let vec: Value =
            serde_json::from_str(&fs::read_to_string(root.join("vectors").join(rel)).unwrap()).unwrap();
        let label = grading.label_for(&tier, &vec).unwrap_or_else(|e| die(format!("{rel}: {e}")));
        let (is_wire, is_tx, is_chain) = (tier == "wire", tier == "transaction", tier == "chain");
        let c = slices.entry((tier.clone(), version, prov)).or_default();
        for e in vec["entries"].as_array().unwrap() {
            let name = e["name"].as_str().unwrap();
            let present = act.get(name);
            let actual = present.cloned().unwrap_or(Value::Null);
            let script = e["script"].as_str();
            let input = Input { entry: e, actual: present, file: &vec, claims_cost };
            match grading.grade(label, &input).unwrap_or_else(|err| die(format!("{rel} {name}: {err}"))) {
                Verdict::Exceeded { limit } => {
                    c.exceeded += 1;
                    let b = &vec["limits"]; // the file's budget: the tier-neutral form carries only the limit hit
                    c.red.push(with_file(red_detail(&op, name, "exceeded",
                        expected_brief(e, is_wire, is_tx, is_chain),
                        format!("exceeded {} ({} MiB, {} s)", limit.as_str().unwrap_or("?"), b["memory_mb"], b["wall_s"]),
                        None, script), rel));
                }
                Verdict::Panicked => {
                    c.panicked += 1;
                    c.red.push(with_file(red_detail(&op, name, "panicked",
                        expected_brief(e, is_wire, is_tx, is_chain), "panicked".to_string(),
                        Some(actual.get("note").cloned().unwrap_or(Value::Null)), script), rel));
                }
                Verdict::NotImplemented => {
                    c.not_impl += 1;
                    c.red.push(with_file(red_detail(&op, name, "not-implemented",
                        expected_brief(e, is_wire, is_tx, is_chain), "not-implemented".to_string(), None, script), rel));
                }
                Verdict::Graded(dims) => {
                    for (dim, g) in dims {
                        if g == Grade::NotApplicable {
                            continue;
                        }
                        let (total, nice, coal) = counters(c, &tier, &dim).unwrap_or_else(|| {
                            die(format!("vectors/grading.json: the board has no counter for the {tier} dimension {dim:?}"))
                        });
                        *total += 1;
                        if g == Grade::Nice {
                            *nice += 1;
                        } else {
                            *coal += 1;
                            c.red.push(with_file(red_for(&tier, &dim, &op, name, e, &actual, script), rel));
                        }
                    }
                }
            }
        }
    }
    slices
}

fn write_results(root: &Path, results: &[Value]) {
    let out = root.join(".santa");
    fs::create_dir_all(&out).ok();
    let doc = json!({"schema": "santa-results/v1", "runners": results});
    fs::write(out.join("results.json"), serde_json::to_string_pretty(&doc).unwrap()).unwrap();
}

/// A runner's row in results.json: who it is, the scope it was graded at (null where unknown), then `rest`.
fn result_row(m: &Runner, scope: Option<&Scope>, rest: Value) -> Value {
    let mut row = json!({
        "name": m.name, "label": m.label,
        "version": scope.map(|s| &s.version), "tiers": scope.map(|s| &s.tiers), "cost": scope.map(|s| s.cost),
        "kinds": scope.and_then(|s| s.kinds.as_ref()), "activation": scope.map(|s| s.activation),
        "impl": &m.impl_,
    });
    for (k, v) in rest.as_object().unwrap() {
        row[k] = v.clone();
    }
    row
}

/// The repo name an impl spec checks out under: the last component of its url / path (before `#`), less `.git`.
fn repo_name(spec: &str) -> String {
    let url = spec.split_once('#').map_or(spec, |(u, _)| u);
    url.trim_end_matches('/').rsplit('/').next().unwrap_or("").trim_end_matches(".git").to_string()
}

const USAGE: &str = "usage: ./conform [--clean] [<runner>…] [--impl <url>#<ref> | --impl <path>]
       ./conform [--clean] --hosted <url>#<ref> | <path> [--runner-dir <dir>] [--label <label>]
  no runner named: every runner under runners/; --impl (one runner named) replaces its impl for this run,
  a path without #<ref> being graded in place, uncommitted edits included.
  --hosted: no runner under runners/, but the implementation's own, in <dir> of its checkout (default santa),
  the one a pull request check runs; named after the repo, labelled <label> (default the same)";

/// conform's command line: `--clean`, the runners to run (none = all) and one runner's impl override, or instead
/// an implementation's own runner (`--hosted`), which SANTA has no entry for.
struct Args {
    clean: bool,
    impl_: Option<String>,
    names: Vec<String>,
    hosted: Option<String>,
    runner_dir: Option<String>,
    label: Option<String>,
}

fn parse_args(args: &[String]) -> Result<Args, String> {
    let mut a = Args { clean: false, impl_: None, names: vec![], hosted: None, runner_dir: None, label: None };
    let mut it = args.iter();
    while let Some(x) = it.next() {
        let mut value = || it.next().cloned().ok_or(format!("{x} needs a value"));
        match x.as_str() {
            "--clean" => a.clean = true,
            "--impl" => a.impl_ = Some(value()?),
            "--hosted" => a.hosted = Some(value()?),
            "--runner-dir" => a.runner_dir = Some(value()?),
            "--label" => a.label = Some(value()?),
            o if o.starts_with('-') => return Err(format!("unknown option {o:?}")),
            n => a.names.push(n.to_string()),
        }
    }
    if a.hosted.is_some() && (!a.names.is_empty() || a.impl_.is_some()) {
        return Err("--hosted runs the implementation's own runner, not one under runners/: no runner names, no --impl".into());
    }
    if a.hosted.is_none() && (a.runner_dir.is_some() || a.label.is_some()) {
        return Err("--runner-dir and --label describe a --hosted runner".into());
    }
    if a.impl_.is_some() && a.names.len() != 1 {
        return Err("--impl replaces one runner's impl: name exactly one runner".into());
    }
    Ok(a)
}

/// The named runners, in discovery order (none named = all); a name with no runner is refused.
fn select_runners(all: Vec<Runner>, names: &[String]) -> Result<Vec<Runner>, String> {
    if let Some(n) = names.iter().find(|n| !all.iter().any(|r| &r.name == *n)) {
        let present: Vec<&str> = all.iter().map(|r| r.name.as_str()).collect();
        return Err(format!("no runner {n:?}; present: {present:?}"));
    }
    Ok(all.into_iter().filter(|r| names.is_empty() || names.contains(&r.name)).collect())
}

/// An impl given on the command line: `<url>#<ref>` as it is; a plain path must be an existing directory, made
/// absolute (it is graded in place, through a link).
fn impl_spec(option: &str, spec: &str) -> Result<String, String> {
    if spec.contains('#') {
        return Ok(spec.to_string());
    }
    let dir = fs::canonicalize(spec).map_err(|e| format!("{option} {spec}: {e}"))?;
    if !dir.is_dir() {
        return Err(format!("{option} {spec}: not a directory"));
    }
    Ok(dir.to_string_lossy().into_owned())
}

/// `--impl`: this run grades `spec` instead of the configured impl, under the configured repo name.
fn override_impl(m: &mut Runner, spec: &str) -> Result<(), String> {
    if m.impl_name.is_none() {
        return Err(format!("{} has no impl to override", m.name));
    }
    m.impl_ = Some(impl_spec("--impl", spec)?);
    Ok(())
}

/// `--hosted`: the implementation's own runner, in `dir` of its checkout (default `santa`), which SANTA has no entry
/// for (runner-integration §6a: the pull request check). It is named after the repo, under which its runner finds the
/// checkout, and labelled `label` (default the same); its scope is read from the checkout, like any hosted runner's.
fn hosted_entry(spec: &str, dir: Option<&str>, label: Option<&str>) -> Result<Runner, String> {
    let path = runner_path(&json!(dir.unwrap_or("santa"))).map_err(|_| format!("--runner-dir {dir:?}: not a relative path inside the checkout"))?;
    let impl_ = impl_spec("--hosted", spec)?;
    let name = repo_name(&impl_);
    if name.is_empty() {
        return Err(format!("--hosted {spec}: no repo name in it"));
    }
    Ok(Runner {
        label: label.map_or_else(|| name.clone(), String::from),
        impl_name: Some(name.clone()),
        name,
        impl_: Some(impl_),
        control: false,
        host: Host::Impl(path),
    })
}

/// The control runners (`"control": true`) that did not run: no result, or one marked `errored` (santa-run could not
/// build or run). A grid without its control says nothing about the oracle, so this fails the run; any other runner
/// that could not run stays a warning in the grid.
fn unrun_controls<'a>(runners: &'a [Runner], results: &[Value]) -> Vec<&'a str> {
    runners
        .iter()
        .filter(|m| m.control)
        .filter(|m| {
            !results.iter().any(|r| r["name"].as_str() == Some(m.name.as_str()) && r["mark"].as_str() != Some("errored"))
        })
        .map(|m| m.name.as_str())
        .collect()
}

/// The control runners that exceeded or crashed on a bounded file. The reference proves every budget on every run:
/// if it cannot keep within one, the budget (or the row) is wrong, not the ports.
fn over_budget_controls<'a>(runners: &'a [Runner], results: &[Value]) -> Vec<&'a str> {
    runners
        .iter()
        .filter(|m| m.control)
        .filter(|m| {
            results.iter().any(|r| {
                r["name"].as_str() == Some(m.name.as_str())
                    && r["bounded_failures"].as_array().is_some_and(|a| !a.is_empty())
            })
        })
        .map(|m| m.name.as_str())
        .collect()
}

fn main() -> ExitCode {
    let root = repo_root();
    let args = parse_args(&env::args().skip(1).collect::<Vec<_>>()).unwrap_or_else(|e| die(format!("{e}\n{USAGE}")));
    if args.clean {
        let _ = fs::remove_dir_all(root.join(".santa"));
    }
    let runners = match &args.hosted {
        Some(spec) => vec![hosted_entry(spec, args.runner_dir.as_deref(), args.label.as_deref()).unwrap_or_else(|e| die(e))],
        None => {
            let mut rs = select_runners(discover(&root.join("runners")), &args.names).unwrap_or_else(|e| die(e));
            if let Some(spec) = &args.impl_ {
                override_impl(&mut rs[0], spec).unwrap_or_else(|e| die(e));
            }
            rs
        }
    };
    if runners.is_empty() {
        println!("no runners under runners/*/ (need runner.json + an executable santa-run or a \"runner\" key)");
        return ExitCode::FAILURE;
    }
    println!("\n=== SANTA conformance · {} runner(s) ===", runners.len());
    let vectors = discover_vectors(&root.join("vectors"));
    let facts = wire_facts(&root.join("vectors"), &vectors).unwrap_or_else(|e| die(e));
    let schemas = ActualsSchemas::load(&root);
    let grading = load_grading(&root);
    check_grading(&grading, &vectors, &root).unwrap_or_else(|e| die(e));
    let results_schema = load_results_schema(&root);
    let head = head(&root).unwrap_or_else(|| die("conform's checkout has no git HEAD: a runner's results name it".into()));
    let corpus = Corpus { root: &root, vectors: &vectors, facts: &facts, schemas: &schemas, results_schema: &results_schema, head: &head };
    let mut results: Vec<Value> = Vec::new();
    for m in &runners {
        eprintln!("running {} …", m.name);
        match run_one(m, &corpus) {
            Err((scope, f)) => {
                let msg = fail_message(&f);
                // only a santa-run that ran has printed anything above
                let above = if matches!(f, RunFail::Exit(_) | RunFail::Ceiling(_)) { " (see error above)" } else { "" };
                println!("  ⚠️  {}  — {msg}{above}", m.label);
                results.push(result_row(m, scope.as_ref(), json!({
                    "sha": Value::Null,
                    "mark": "errored", "red_total": Value::Null, "slices": {}, "bounded_failures": [],
                    "error": msg,
                })));
            }
            Ok(ran) => {
                let sc = &ran.scope;
                let slices = tally(&ran.actuals, sc.cost, &root, &grading);
                let agg_red: u64 = slices.values().map(Counts::red_total).sum();
                let mark = if agg_red == 0 { "🎁" } else { "🪨" };
                println!(
                    "  {mark} {}  (version≤{}, tiers={}, cost={}{})",
                    m.label,
                    sc.version,
                    sc.tiers.join(","),
                    if sc.cost { "True" } else { "False" },
                    scope_suffix(sc)
                );
                for ((tier, version, prov), c) in &slices {
                    println!("      {tier}/{version}/{prov}: {}", c.summary());
                }
                let selected: BTreeSet<String> = ran.actuals.keys().cloned().collect();
                let grader = grader(&ran.results, &slices, &selected);
                if grader["agrees"] == true {
                    println!("      grader: agrees");
                } else {
                    println!(
                        "      grader: {} disagreements, {} missing files, {} extra files",
                        grader["disagreement_count"],
                        grader["missing_files"].as_array().map_or(0, Vec::len),
                        grader["extra_files"].as_array().map_or(0, Vec::len)
                    );
                }
                let slices_json: serde_json::Map<String, Value> = slices
                    .iter()
                    .map(|((t, v, p), c)| (format!("{t}/{v}/{p}"), c.to_json()))
                    .collect();
                results.push(result_row(m, Some(sc), json!({
                    "sha": ran.sha,
                    "mark": if agg_red == 0 { "nice" } else { "coal" }, "red_total": agg_red, "slices": slices_json,
                    "bounded_failures": ran.failures, "grader": grader,
                })));
            }
        }
    }
    write_results(&root, &results);
    eprintln!("\nresults → .santa/results.json");
    let unrun = unrun_controls(&runners, &results);
    if !unrun.is_empty() {
        eprintln!("\nFAILED: the control runner(s) {unrun:?} did not run, so this grid is not anchored to the oracle");
        return ExitCode::FAILURE;
    }
    let over = over_budget_controls(&runners, &results);
    if !over.is_empty() {
        eprintln!("\nFAILED: the control runner(s) {over:?} exceeded or crashed on a bounded file: the budget is wrong, not the ports");
        return ExitCode::FAILURE;
    }
    ExitCode::SUCCESS
}

#[cfg(test)]
mod tests {
    use super::{discover_dir, discover_vectors, hosted_entry, hosted_runner, in_scope, override_impl, parse_args, parse_hosted_manifest, parse_manifest, repo_name, select_runners, Args, parse_relpath, repo_root, resolve_impl, scope_suffix, select, unrun_controls, wire_facts, Host, Runner, Scope, Vec5, WireFacts, WIRE_KINDS};
    use std::collections::BTreeMap;
    use serde_json::json;
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    fn scope() -> Scope {
        Scope { version: "v6".into(), tiers: vec!["eval".into()], cost: true, kinds: None, activation: true }
    }

    fn runner(name: &str, control: bool) -> Runner {
        Runner {
            name: name.into(), label: name.into(), impl_: None, impl_name: None, control,
            host: Host::Santa { scope: scope(), run: PathBuf::from("/dev/null") },
        }
    }

    /// A control that could not build or run fails the run; any other runner that could not stays a warning, and a
    /// control that ran is fine whatever its red count (grading is the grid's job, not the exit code's).
    #[test]
    fn a_control_that_did_not_run_is_named() {
        let rs = vec![runner("rudolph", true), runner("dasher", false)];
        let ran = |n: &str, mark: &str| json!({"name": n, "mark": mark});
        assert!(unrun_controls(&rs, &[ran("rudolph", "nice"), ran("dasher", "errored")]).is_empty());
        assert!(unrun_controls(&rs, &[ran("rudolph", "coal"), ran("dasher", "coal")]).is_empty());
        assert_eq!(unrun_controls(&rs, &[ran("rudolph", "errored"), ran("dasher", "nice")]), vec!["rudolph"]);
        // a control with no result at all did not run either
        assert_eq!(unrun_controls(&rs, &[ran("dasher", "nice")]), vec!["rudolph"]);
    }

    fn sample() -> Vec<Vec5> {
        ["eval/v5/spec/a.json", "eval/v6/spec/b.json", "eval/v5/authored/c.json", "wire/v5/spec/d.json",
         "eval/v4/authored/e.json"]
            .iter()
            .map(|r| {
                let (t, v, p, o) = parse_relpath(r);
                (r.to_string(), t, v, p, o)
            })
            .collect()
    }

    fn selected_rels(version: &str, tiers: &[&str]) -> Vec<String> {
        let s = sample();
        let tiers: Vec<String> = tiers.iter().map(|t| t.to_string()).collect();
        let mut rels: Vec<String> = select(&s, version, &tiers).iter().map(|v| v.0.clone()).collect();
        rels.sort();
        rels
    }

    #[test]
    fn parse_relpath_splits_taxonomy() {
        assert_eq!(
            parse_relpath("eval/v5/spec/plus.json"),
            ("eval".into(), "v5".into(), "spec".into(), "plus".into())
        );
    }

    #[test]
    fn select_v6_eval_is_cumulative() {
        assert_eq!(
            selected_rels("v6", &["eval"]),
            ["eval/v4/authored/e.json", "eval/v5/authored/c.json", "eval/v5/spec/a.json", "eval/v6/spec/b.json"]
        );
    }

    #[test]
    fn select_v5_eval_excludes_v6_and_other_tiers() {
        assert_eq!(
            selected_rels("v5", &["eval"]),
            ["eval/v4/authored/e.json", "eval/v5/authored/c.json", "eval/v5/spec/a.json"]
        );
    }

    #[test]
    fn select_v4_eval_is_below_the_jit_activation_only() {
        assert_eq!(selected_rels("v4", &["eval"]), ["eval/v4/authored/e.json"]);
    }

    #[test]
    fn select_auto_includes_authored_provenance() {
        assert!(selected_rels("v5", &["eval"]).iter().any(|r| r.contains("/authored/")));
    }

    // ── declared scope: a runner's wire kinds and whether it applies activated-version rules ──────────

    fn manifest(extra: serde_json::Value) -> serde_json::Value {
        let mut m = json!({"name": "r", "label": "r", "version": "v5", "tiers": ["wire"]});
        for (k, v) in extra.as_object().unwrap() {
            m[k] = v.clone();
        }
        m
    }

    #[test]
    fn manifest_rejects_unknown_kind() {
        let e = parse_manifest(&manifest(json!({"kinds": ["box"]})), Path::new("/dev")).err().unwrap();
        assert!(e.contains("box"), "{e}");
    }

    #[test]
    fn manifest_rejects_empty_kinds() {
        assert!(parse_manifest(&manifest(json!({"kinds": []})), Path::new("/dev")).is_err());
    }

    #[test]
    fn manifest_rejects_kinds_without_wire() {
        let m = manifest(json!({"kinds": ["Box"], "tiers": ["eval"]}));
        assert!(parse_manifest(&m, Path::new("/dev")).is_err());
    }

    #[test]
    fn manifest_activation_without_wire_is_fine() {
        let m = manifest(json!({"activation": false, "tiers": ["eval"]}));
        assert!(!parse_manifest(&m, Path::new("/dev")).unwrap().declared().unwrap().activation);
    }

    #[test]
    fn manifest_defaults() {
        let r = parse_manifest(&manifest(json!({})), Path::new("/dev")).unwrap();
        assert!(!r.control);
        let s = r.declared().unwrap();
        assert_eq!(s.kinds, None);
        assert!(s.activation);
        assert!(s.cost);
    }

    #[test]
    fn wire_kinds_match_the_schema() {
        let schema: serde_json::Value = serde_json::from_str(
            &fs::read_to_string(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../schema/santa-wire.vector.schema.json"))
                .unwrap(),
        )
        .unwrap();
        let kinds: Vec<&str> = schema["$defs"]["entry"]["properties"]["kind"]["enum"]
            .as_array()
            .unwrap()
            .iter()
            .map(|k| k.as_str().unwrap())
            .collect();
        assert_eq!(kinds, WIRE_KINDS);
    }

    fn facts() -> std::collections::BTreeMap<String, WireFacts> {
        [("wire/v5/authored/Box.a.json", "Box", false), ("wire/v5/authored/ErgoTree.b.json", "ErgoTree", false),
         ("wire/v5/authored/Box.tree_version_above_activated.json", "Box", true)]
            .iter()
            .map(|(r, k, a)| (r.to_string(), WireFacts { kind: k.to_string(), activation_rule: *a }))
            .collect()
    }
    fn fleet() -> Option<Vec<String>> {
        Some(vec!["Constant".into(), "Box".into(), "Transaction".into()])
    }

    #[test]
    fn in_scope_defaults_keep_everything() {
        for rel in facts().keys() {
            assert!(in_scope(rel, &facts(), &None, true), "{rel}");
        }
    }

    #[test]
    fn in_scope_kinds_cut_other_kinds() {
        assert!(in_scope("wire/v5/authored/Box.a.json", &facts(), &fleet(), true));
        assert!(!in_scope("wire/v5/authored/ErgoTree.b.json", &facts(), &fleet(), true));
    }

    #[test]
    fn in_scope_activation_false_cuts_marked_files_only() {
        assert!(in_scope("wire/v5/authored/Box.a.json", &facts(), &None, false));
        assert!(!in_scope("wire/v5/authored/Box.tree_version_above_activated.json", &facts(), &None, false));
    }

    #[test]
    fn in_scope_non_wire_always_in() {
        assert!(in_scope("eval/v5/authored/c.json", &facts(), &fleet(), false));
    }

    /// A temp dir removed on drop, so a failing assertion leaves nothing behind.
    struct TempDir(PathBuf);
    impl TempDir {
        fn new(tag: &str) -> Self {
            let d = std::env::temp_dir().join(format!("santa-conform-{tag}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&d);
            fs::create_dir_all(&d).unwrap();
            TempDir(d)
        }
    }
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn vec5s(rels: &[&str]) -> Vec<Vec5> {
        rels.iter()
            .map(|r| {
                let (t, v, p, o) = parse_relpath(r);
                (r.to_string(), t, v, p, o)
            })
            .collect()
    }

    #[test]
    fn wire_facts_read_kind_and_marker_and_refuse_ambiguous_files() {
        let root = TempDir::new("wire-facts");
        let dir = root.0.join("wire/v5/authored");
        fs::create_dir_all(&dir).unwrap();
        let file = |name: &str, kinds: &[&str], marker: Option<bool>| {
            let entries: Vec<_> = kinds.iter().map(|k| json!({"kind": k})).collect();
            let mut doc = json!({"schema": "santa-wire/v1", "entries": entries});
            if let Some(m) = marker {
                doc["activation_rule"] = json!(m);
            }
            fs::write(dir.join(name), doc.to_string()).unwrap();
        };
        file("Box.one.json", &["Box", "Box"], None);
        file("Box.marked.json", &["Box"], Some(true));
        file("Box.two.json", &["Box", "Transaction"], None);
        file("Box.empty.json", &[], None);
        let ok = wire_facts(&root.0, &vec5s(&["wire/v5/authored/Box.one.json", "wire/v5/authored/Box.marked.json"])).unwrap();
        assert_eq!(ok["wire/v5/authored/Box.one.json"].kind, "Box");
        assert!(!ok["wire/v5/authored/Box.one.json"].activation_rule);
        assert!(ok["wire/v5/authored/Box.marked.json"].activation_rule);
        for bad in ["wire/v5/authored/Box.two.json", "wire/v5/authored/Box.empty.json"] {
            let e = wire_facts(&root.0, &vec5s(&[bad])).err().unwrap();
            assert!(e.contains(bad.rsplit('/').next().unwrap()), "{e}");
        }
    }

    #[test]
    fn scope_suffix_names_what_is_declared() {
        let mut s = scope();
        assert_eq!(scope_suffix(&s), "");
        s.kinds = Some(vec!["Constant".into(), "Box".into()]);
        s.activation = false;
        assert_eq!(scope_suffix(&s), ", kinds=Constant,Box, activation=False");
    }

    #[test]
    fn manifest_rejects_unknown_keys_and_non_bool_flags() {
        for extra in [json!({"kind": ["Box"]}), json!({"activations": false}), json!({"activation": "false"}),
                      json!({"cost": "false"}), json!({"control": 1})] {
            let e = parse_manifest(&manifest(extra.clone()), Path::new("/dev"));
            assert!(e.is_err(), "{extra}");
        }
    }

    // ── a runner hosted in the implementation's repo: SANTA names it, the implementation declares its scope ──────

    fn entry(extra: serde_json::Value) -> serde_json::Value {
        let mut m = json!({"name": "dasher", "label": "dasher", "impl": "https://example.org/ergots.git#master",
                           "runner": "santa/runner"});
        for (k, v) in extra.as_object().unwrap() {
            m[k] = v.clone();
        }
        m
    }

    #[test]
    fn a_runner_entry_leaves_the_scope_to_the_implementation() {
        let r = parse_manifest(&entry(json!({})), Path::new("/dev")).unwrap();
        assert!(matches!(&r.host, Host::Impl(p) if p == "santa/runner"));
        assert!(r.declared().is_none());
    }

    /// SANTA's copy of a scope key would grade the implementation at SANTA's claim instead of its own.
    #[test]
    fn a_runner_entry_refuses_the_scope_keys() {
        assert!(parse_manifest(&entry(json!({})), Path::new("/dev")).is_ok(), "the bare entry parses");
        for extra in [json!({"version": "v6"}), json!({"tiers": ["eval"]}), json!({"cost": true}),
                      json!({"kinds": ["Box"]}), json!({"activation": true})] {
            let key = extra.as_object().unwrap().keys().next().unwrap().clone();
            let e = parse_manifest(&entry(extra.clone()), Path::new("/dev")).err().unwrap_or_else(|| panic!("{extra}"));
            assert!(e.contains(&key), "{e}");
        }
    }

    #[test]
    fn a_runner_entry_needs_an_impl() {
        assert!(parse_manifest(&entry(json!({})), Path::new("/dev")).is_ok(), "the bare entry parses");
        let mut absent = entry(json!({}));
        absent.as_object_mut().unwrap().remove("impl");
        assert!(parse_manifest(&absent, Path::new("/dev")).is_err());
        assert!(parse_manifest(&entry(json!({"impl": null})), Path::new("/dev")).is_err());
    }

    #[test]
    fn a_runner_path_stays_inside_the_checkout() {
        assert!(parse_manifest(&entry(json!({})), Path::new("/dev")).is_ok(), "the bare entry parses");
        for bad in [json!(""), json!("/opt/runner"), json!("../runner"), json!("santa/../../runner"), json!(42)] {
            assert!(parse_manifest(&entry(json!({"runner": bad})), Path::new("/dev")).is_err(), "{bad}");
        }
    }

    /// The implementation's runner.json holds its scope and nothing SANTA sets: it cannot name itself, point
    /// elsewhere, or make itself the control.
    #[test]
    fn an_implementation_manifest_holds_only_the_scope() {
        let base = json!({"version": "v6", "tiers": ["eval", "wire"], "cost": false});
        let s = parse_hosted_manifest(&base).unwrap();
        assert_eq!((s.version.as_str(), s.tiers.len(), s.cost, s.activation), ("v6", 2, false, true));
        for extra in [json!({"control": true}), json!({"name": "x"}), json!({"label": "x"}),
                      json!({"impl": "x#main"}), json!({"runner": "x"}), json!({"kinds": ["Box"], "tiers": ["eval"]})] {
            let mut m = base.clone();
            for (k, v) in extra.as_object().unwrap() {
                m[k] = v.clone();
            }
            assert!(parse_hosted_manifest(&m).is_err(), "{extra}");
        }
    }

    fn scratch(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("santa-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn write_exec(p: &Path) {
        use std::os::unix::fs::PermissionsExt;
        fs::write(p, "#!/bin/sh\n").unwrap();
        fs::set_permissions(p, fs::Permissions::from_mode(0o755)).unwrap();
    }

    #[test]
    fn discover_takes_a_runner_entry_without_a_santa_run_and_refuses_one_with() {
        let d = scratch("discover-entry");
        fs::write(d.join("runner.json"), entry(json!({})).to_string()).unwrap();
        let r = discover_dir(&d).unwrap().expect("an entry with no santa-run of its own is a runner");
        assert!(matches!(r.host, Host::Impl(_)));
        write_exec(&d.join("santa-run"));
        assert!(discover_dir(&d).is_err(), "two santa-runs claim the entry");
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn hosted_runner_reads_the_implementations_dir() {
        let checkout = scratch("hosted-runner");
        let dir = checkout.join("santa/runner");
        fs::create_dir_all(&dir).unwrap();
        assert!(hosted_runner(&checkout, "santa/runner").unwrap_err().contains("runner.json"));
        fs::write(dir.join("runner.json"), json!({"version": "v5", "tiers": ["wire"]}).to_string()).unwrap();
        assert!(hosted_runner(&checkout, "santa/runner").unwrap_err().contains("santa-run"));
        fs::write(dir.join("santa-run"), "#!/bin/sh\n").unwrap(); // present, not executable
        assert!(hosted_runner(&checkout, "santa/runner").unwrap_err().contains("santa-run"));
        write_exec(&dir.join("santa-run"));
        let (s, run) = hosted_runner(&checkout, "santa/runner").unwrap();
        assert_eq!((s.version.as_str(), s.tiers.as_slice()), ("v5", ["wire".to_string()].as_slice()));
        assert_eq!(run, dir.join("santa-run"));
        fs::write(dir.join("runner.json"), json!({"version": "v5", "tiers": ["wire"], "control": true}).to_string())
            .unwrap();
        assert!(hosted_runner(&checkout, "santa/runner").is_err());
        let _ = fs::remove_dir_all(&checkout);
    }

    /// Every runner.json in the tree parses (tools CI has no submodules: it reads the in-tree ones; a local run all).
    #[test]
    fn every_present_runner_manifest_parses() {
        let runners = repo_root().join("runners");
        let mut n = 0;
        for d in fs::read_dir(&runners).unwrap().flatten() {
            let m = d.path().join("runner.json");
            if m.is_file() {
                let j: serde_json::Value = serde_json::from_str(&fs::read_to_string(&m).unwrap()).unwrap();
                parse_manifest(&j, Path::new("/dev")).unwrap_or_else(|e| panic!("{}: {e}", m.display()));
                n += 1;
            }
        }
        assert!(n >= 2, "rudolph and dasher are in-tree");
    }

    /// Comet's declared scope (Constant, Box, Transaction; no activation rules) over the REAL corpus at v5: what it
    /// keeps and what it does not (2026-11-17: 9 wire files in; out 5 ErgoTree, 1 SigmaBoolean, 1 BlockTransactions,
    /// 2 marked). A corpus change that moves these counts should be looked at, not just re-pinned.
    #[test]
    fn comet_scope_over_the_real_corpus() {
        let vdir = repo_root().join("vectors");
        let vectors = discover_vectors(&vdir);
        let facts = wire_facts(&vdir, &vectors).unwrap();
        let kinds = Some(vec!["Constant".to_string(), "Box".to_string(), "Transaction".to_string()]);
        let wire: Vec<&Vec5> = select(&vectors, "v5", &["wire".to_string()]);
        let (kept, out): (Vec<&Vec5>, Vec<&Vec5>) = wire.iter().partition(|v| in_scope(&v.0, &facts, &kinds, false));
        for v in &kept {
            let f = &facts[&v.0];
            assert!(["Constant", "Box", "Transaction"].contains(&f.kind.as_str()) && !f.activation_rule, "{}", v.0);
        }
        let mut why: BTreeMap<String, usize> = BTreeMap::new();
        for v in &out {
            let f = &facts[&v.0];
            // by kind first: the v5 BlockTransactions file is both an undeclared kind and marked
            let in_kinds = ["Constant", "Box", "Transaction"].contains(&f.kind.as_str());
            *why.entry(if in_kinds { "marked".into() } else { f.kind.clone() }).or_default() += 1;
        }
        assert_eq!(kept.len(), 9);
        let want: BTreeMap<String, usize> =
            [("BlockTransactions", 1), ("ErgoTree", 5), ("SigmaBoolean", 1), ("marked", 2)]
                .iter()
                .map(|(k, n)| (k.to_string(), *n))
                .collect();
        assert_eq!(why, want);
    }

    // Port of the former tools/test_checkout.py: a throwaway local "remote" with v1 then v2 on
    // branch main; resolve_impl clones into <impl-path>/<repo-name>, honors a bare branch (latest)
    // vs a @<sha> pin, and maps a null impl to "-".
    #[test]
    fn resolve_impl_checkout() {
        let tmp = std::env::temp_dir().join(format!("santa-checkout-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        let remote = tmp.join("remote");
        fs::create_dir_all(&remote).unwrap();
        let rs = remote.to_str().unwrap();
        let g = |args: &[&str]| assert!(Command::new("git").args(args).status().unwrap().success());
        g(&["init", "-q", "-b", "main", rs]);
        g(&["-C", rs, "config", "user.email", "t@santa"]);
        g(&["-C", rs, "config", "user.name", "santa"]);
        fs::write(remote.join("VERSION"), "v1\n").unwrap();
        g(&["-C", rs, "add", "."]);
        g(&["-C", rs, "commit", "-qm", "v1"]);
        let sha1 = String::from_utf8(
            Command::new("git").args(["-C", rs, "rev-parse", "HEAD"]).output().unwrap().stdout,
        )
        .unwrap()
        .trim()
        .to_string();
        fs::write(remote.join("VERSION"), "v2\n").unwrap();
        g(&["-C", rs, "add", "."]);
        g(&["-C", rs, "commit", "-qm", "v2"]);

        // 1) bare branch -> latest tip (v2); impl_path is the passed cache dir; checkout at <dir>/remote.
        let cache = tmp.join("cache");
        let got = resolve_impl(Some(&format!("{rs}#main")), "remote", &cache).unwrap();
        assert_eq!(got.arg, cache.to_string_lossy());
        let checkout = cache.join("remote");
        assert_eq!(got.checkout.as_deref(), Some(checkout.as_path()));
        let sha_out = got.sha;
        assert!(checkout.join(".git").is_dir());
        assert_eq!(fs::read_to_string(checkout.join("VERSION")).unwrap().trim(), "v2");
        assert!(sha_out.as_ref().is_some_and(|s| s.len() == 40));

        // 2) @<sha> pin -> that commit (v1), not the tip.
        let cache2 = tmp.join("cache2");
        let sha_pin = resolve_impl(Some(&format!("{rs}#main@{sha1}")), "remote", &cache2).unwrap().sha;
        assert_eq!(fs::read_to_string(cache2.join("remote").join("VERSION")).unwrap().trim(), "v1");
        assert_eq!(sha_pin, Some(sha1.clone()));

        // 3) null impl -> ("-", None).
        let none = resolve_impl(None, "remote", &cache).unwrap();
        assert_eq!((none.arg.as_str(), none.checkout, none.sha), ("-", None, None));

        let _ = fs::remove_dir_all(&tmp);
    }

    fn git_init(dir: &Path) {
        fs::create_dir_all(dir).unwrap();
        let d = dir.to_str().unwrap();
        assert!(Command::new("git").args(["init", "-q", "-b", "main", d]).status().unwrap().success());
        git_identity(dir);
    }

    fn git_identity(dir: &Path) {
        let d = dir.to_str().unwrap();
        for args in [["-C", d, "config", "user.email", "t@santa"], ["-C", d, "config", "user.name", "santa"]] {
            assert!(Command::new("git").args(args).status().unwrap().success());
        }
    }

    /// Writes VERSION in `dir` and commits it; returns the commit's sha.
    fn git_commit(dir: &Path, version: &str) -> String {
        let d = dir.to_str().unwrap();
        fs::write(dir.join("VERSION"), format!("{version}\n")).unwrap();
        for args in [vec!["-C", d, "add", "."], vec!["-C", d, "commit", "-qm", version]] {
            assert!(Command::new("git").args(&args).status().unwrap().success());
        }
        let out = Command::new("git").args(["-C", d, "rev-parse", "HEAD"]).output().unwrap().stdout;
        String::from_utf8(out).unwrap().trim().to_string()
    }

    fn version_at(dir: &Path) -> String {
        fs::read_to_string(dir.join("VERSION")).unwrap().trim().to_string()
    }

    /// The runner finds its impl as <impl-path>/<repo-name>, the name of the CONFIGURED impl, whatever the dir an
    /// override points at is called; and `<url>#<branch>` is that branch's tip at THAT url, whichever url the cached
    /// clone came from before (a local checkout ahead of the upstream must not leak into the next board run).
    #[test]
    fn resolve_impl_clones_under_the_configured_name_and_follows_the_url() {
        let tmp = scratch("resolve-follow");
        let (up, fork) = (tmp.join("upstream"), tmp.join("my-fork"));
        git_init(&up);
        git_commit(&up, "u1");
        assert!(Command::new("git").args(["clone", "-q", up.to_str().unwrap(), fork.to_str().unwrap()])
            .status().unwrap().success());
        git_identity(&fork);
        git_commit(&fork, "f1"); // the fork's main is AHEAD of upstream's
        let cache = tmp.join("cache");
        let at = |url: &Path| {
            let r = resolve_impl(Some(&format!("{}#main", url.display())), "ergots", &cache).unwrap();
            assert_eq!(r.checkout.as_deref(), Some(cache.join("ergots").as_path()));
            version_at(&cache.join("ergots"))
        };
        assert_eq!(at(&up), "u1");
        assert_eq!(at(&fork), "f1");
        assert_eq!(at(&up), "u1", "the fork's main must not stand in for upstream's");
        // the same tag name on another commit in each (the cache mirrors whichever url the spec names)
        let tag = |dir: &Path| {
            let d = dir.to_str().unwrap();
            assert!(Command::new("git").args(["-C", d, "tag", "-f", "v1"]).status().unwrap().success());
        };
        tag(&up);
        tag(&fork);
        assert_eq!(at(&fork), "f1");
        assert_eq!(at(&up), "u1");
        let _ = fs::remove_dir_all(&tmp);
    }

    /// A PR's base is often a commit no branch of the checkout holds (only its `origin/main` does), and a cache
    /// cloned earlier fetches only what the branches reach: a pinned commit missing after the fetch is fetched by sha.
    #[test]
    fn resolve_impl_fetches_a_pinned_commit_no_branch_holds() {
        let tmp = scratch("resolve-sha");
        let (up, dev) = (tmp.join("upstream"), tmp.join("dev"));
        git_init(&up);
        git_commit(&up, "u1");
        assert!(Command::new("git").args(["clone", "-q", up.to_str().unwrap(), dev.to_str().unwrap()])
            .status().unwrap().success());
        let cache = tmp.join("cache");
        resolve_impl(Some(&format!("{}#main", up.display())), "ergots", &cache).unwrap(); // the cache, at u1
        let u2 = git_commit(&up, "u2");
        assert!(Command::new("git").args(["-C", dev.to_str().unwrap(), "fetch", "-q"]).status().unwrap().success());
        // u2 is under dev's refs/remotes/origin/main only; dev's main is still u1
        let r = resolve_impl(Some(&format!("{}#{u2}", dev.display())), "ergots", &cache).unwrap();
        assert_eq!((r.sha.as_deref(), version_at(&cache.join("ergots")).as_str()), (Some(u2.as_str()), "u2"));
        let _ = fs::remove_dir_all(&tmp);
    }

    /// A plain path (no `#ref`) is graded in place: the runner sees that directory, uncommitted edits included, under
    /// the configured repo name; its sha says when the tree is dirty.
    #[test]
    fn resolve_impl_grades_a_plain_path_in_place() {
        let tmp = scratch("resolve-in-place");
        let wt = tmp.join("my-worktree");
        git_init(&wt);
        let sha = git_commit(&wt, "w1");
        let cache = tmp.join("cache");
        let r = resolve_impl(Some(wt.to_str().unwrap()), "ergots", &cache).unwrap();
        // a runner that finds its impl as <impl-path>/<name> sees the tree through that link…
        let seen = PathBuf::from(&r.arg).join("ergots");
        assert_eq!(fs::canonicalize(&seen).unwrap(), fs::canonicalize(&wt).unwrap());
        // …while a hosted runner is handed the tree's REAL path: run through the link, its build would record the
        // same crates under two paths in one target dir (the developer's next build of it then fails)
        assert_eq!(r.checkout, Some(fs::canonicalize(&wt).unwrap()));
        assert!(!cache.join("ergots").exists(), "in place must not touch the clone cache");
        assert_eq!(r.sha.as_deref(), Some(sha.as_str()));
        fs::write(wt.join("VERSION"), "w2-uncommitted\n").unwrap();
        let r = resolve_impl(Some(wt.to_str().unwrap()), "ergots", &cache).unwrap();
        assert_eq!(r.sha, Some(format!("{sha}-dirty")));
        assert_eq!(version_at(&seen), "w2-uncommitted");
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn repo_name_is_the_last_component_of_the_url() {
        assert_eq!(repo_name("https://github.com/mwaddip/ergots.git#master"), "ergots");
        assert_eq!(repo_name("/home/u/sigma-rust/#develop@abc"), "sigma-rust");
        assert_eq!(repo_name("/home/u/my-worktree"), "my-worktree");
    }

    fn args(xs: &[&str]) -> Result<Args, String> {
        parse_args(&xs.iter().map(|s| s.to_string()).collect::<Vec<_>>())
    }

    #[test]
    fn conform_takes_runner_names_clean_and_one_impl_override() {
        let a = args(&[]).unwrap();
        assert!(!a.clean && a.impl_.is_none() && a.names.is_empty());
        let a = args(&["--clean", "dasher", "vixen"]).unwrap();
        assert!(a.clean && a.names == ["dasher", "vixen"]);
        let a = args(&["dasher", "--impl", "/w/ergots"]).unwrap();
        assert_eq!((a.names.as_slice(), a.impl_.as_deref()), (["dasher".to_string()].as_slice(), Some("/w/ergots")));
        // `./conform --help` used to start a full run: an unknown option is refused instead
        for bad in [&["--help"][..], &["--impl"], &["--impl", "/w"], &["a", "b", "--impl", "/w"]] {
            assert!(args(bad).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn conform_takes_a_hosted_runner_alone() {
        let a = args(&["--hosted", "/w/sigma-rust#main"]).unwrap();
        assert_eq!(a.hosted.as_deref(), Some("/w/sigma-rust#main"));
        assert!(a.names.is_empty() && a.impl_.is_none() && a.runner_dir.is_none() && a.label.is_none());
        let a = args(&["--hosted", "/w/x", "--runner-dir", "ci/santa", "--label", "x (PR)"]).unwrap();
        assert_eq!((a.runner_dir.as_deref(), a.label.as_deref()), (Some("ci/santa"), Some("x (PR)")));
        // the implementation's own runner is not one under runners/: no names, no --impl beside it; and the options
        // that describe it mean nothing without it
        for bad in [&["--hosted"][..], &["--hosted", "/w", "dasher"], &["--hosted", "/w", "--impl", "/v"],
                    &["--runner-dir", "santa"], &["--label", "x"], &["dasher", "--runner-dir", "santa"]] {
            assert!(args(bad).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn a_hosted_runner_needs_no_entry_in_santa() {
        let tmp = scratch("hosted-entry");
        let checkout = tmp.join("sigma-rust");
        fs::create_dir_all(&checkout).unwrap();
        // a plain path: graded in place, named after its directory, the runner in `santa` by default
        let m = hosted_entry(checkout.to_str().unwrap(), None, None).unwrap();
        assert_eq!((m.name.as_str(), m.label.as_str(), m.impl_name.as_deref()), ("sigma-rust", "sigma-rust", Some("sigma-rust")));
        assert_eq!(m.impl_, Some(fs::canonicalize(&checkout).unwrap().to_string_lossy().into_owned()));
        assert!(matches!(&m.host, Host::Impl(p) if p == "santa") && !m.control);
        // a ref of a url or a path, its runner elsewhere, a label for the comment
        let m = hosted_entry("https://github.com/ergoplatform/sigma-rust.git#abc123", Some("ci/santa"), Some("ergoplatform/sigma-rust")).unwrap();
        assert_eq!((m.name.as_str(), m.label.as_str()), ("sigma-rust", "ergoplatform/sigma-rust"));
        assert_eq!(m.impl_.as_deref(), Some("https://github.com/ergoplatform/sigma-rust.git#abc123"));
        assert!(matches!(&m.host, Host::Impl(p) if p == "ci/santa"));
        // the runner stays inside the checkout; a path must exist
        for dir in ["../santa", "/santa", "", "a/../b"] {
            assert!(hosted_entry(checkout.to_str().unwrap(), Some(dir), None).is_err(), "{dir:?}");
        }
        assert!(hosted_entry(tmp.join("missing").to_str().unwrap(), None, None).is_err());
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn select_runners_by_name_in_discovery_order() {
        let all = || vec![runner("dasher", false), runner("rudolph", true), runner("vixen", false)];
        let names = |rs: Vec<Runner>| rs.into_iter().map(|r| r.name).collect::<Vec<_>>();
        assert_eq!(names(select_runners(all(), &[]).unwrap()), ["dasher", "rudolph", "vixen"]);
        let picked = select_runners(all(), &["vixen".into(), "dasher".into()]).unwrap();
        assert_eq!(names(picked), ["dasher", "vixen"]);
        let e = select_runners(all(), &["blitzen".into()]).err().unwrap();
        assert!(e.contains("blitzen") && e.contains("rudolph"), "{e}");
    }

    #[test]
    fn an_impl_override_keeps_the_configured_name() {
        let tmp = scratch("override");
        let mut m = runner("dasher", false);
        m.impl_ = Some("https://github.com/mwaddip/ergots.git#master".into());
        m.impl_name = Some("ergots".into());
        override_impl(&mut m, "/w/my-fork#main@abc").unwrap();
        assert_eq!((m.impl_.as_deref(), m.impl_name.as_deref()), (Some("/w/my-fork#main@abc"), Some("ergots")));
        override_impl(&mut m, tmp.to_str().unwrap()).unwrap(); // a plain path: an existing dir, made absolute
        assert_eq!(m.impl_, Some(fs::canonicalize(&tmp).unwrap().to_string_lossy().into_owned()));
        assert!(override_impl(&mut m, tmp.join("missing").to_str().unwrap()).is_err());
        let mut control = runner("rudolph", true); // impl null: nothing to override
        assert!(override_impl(&mut control, tmp.to_str().unwrap()).is_err());
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn summary_reports_coverage_gaps_without_value_coverage() {
        // A slice that is 100% coverage-gaps has value_total 0 (no value-graded entry); its
        // not-impl count must still render. dasher's eval/v6/authored (22 not-impl) printed a
        // blank line before this was pinned.
        let c = super::Counts { not_impl: 22, ..Default::default() };
        assert_eq!(c.summary(), "22 not-impl");
    }

    #[test]
    fn summary_and_red_total_count_panicked() {
        let c = super::Counts { panicked: 2, ..Default::default() };
        assert_eq!(c.summary(), "2 panicked");
        assert_eq!(c.red_total(), 2);
    }

    #[test]
    fn summary_pins_full_slice_format() {
        // dasher's eval/v6/spec shape: value coverage, then gaps, then cost, then reject — in order.
        let c = super::Counts {
            value_total: 10,
            value_nice: 10,
            not_impl: 254,
            cost_graded: 10,
            cost_nice: 10,
            reject_total: 3,
            reject_nice: 3,
            ..Default::default()
        };
        assert_eq!(c.summary(), "value 10/10 · 254 not-impl · cost 10/10 · reject 3/3");
    }

    #[test]
    fn expected_brief_wire_follows_the_grader() {
        // A wire entry's expected side is what the wire grading type compares against: `errored` for a reject entry,
        // `expected_bytes_hex` for a non-identity round-trip, the entry's own bytes otherwise. A panicked or
        // not-implemented reject entry used to read "roundtrip <input bytes>".
        let brief = |e: serde_json::Value| super::expected_brief(&e, true, false, false);
        assert_eq!(brief(serde_json::json!({"bytes_hex": "0a0b", "error": "errored"})), "errored");
        assert_eq!(brief(serde_json::json!({"bytes_hex": "0a0b", "expected_bytes_hex": "0c0d"})), "roundtrip 0c0d");
        assert_eq!(brief(serde_json::json!({"bytes_hex": "0a0b"})), "roundtrip 0a0b");
    }

    #[test]
    fn summary_and_red_total_count_roundtrip() {
        // A wire slice: the single round-trip dimension renders, and the differs count as red.
        let c = super::Counts { roundtrip_total: 4, roundtrip_nice: 3, roundtrip_coal: 1, ..Default::default() };
        assert_eq!(c.summary(), "roundtrip 3/4");
        assert_eq!(c.red_total(), 1);
    }

    #[test]
    fn summary_reports_authds_dims() {
        // A mixed authds slice: prove's proof+digest, verify's accepted+results, and the shared
        // digest total accumulating contributions from both kinds — pins order and format so a
        // future refactor can't silently drop a dim back to a blank console line (Finding 2).
        let c = super::Counts {
            authds_proof_total: 2, authds_proof_nice: 1,
            authds_digest_total: 3, authds_digest_nice: 2,
            authds_accepted_total: 2, authds_accepted_nice: 2,
            authds_results_total: 1, authds_results_nice: 0,
            ..Default::default()
        };
        assert_eq!(c.summary(), "proof 1/2 · digest 2/3 · accepted 2/2 · results 0/1");
    }

    // ── transaction tally tests ──────────────────────────────────────────────

    /// Build a synthetic santa-transaction/v1 vector JSON with the given entries.
    /// Each entry: (name, expected_valid, expected_cost). A null cost means cost:null in expected.
    fn tx_vector(entries: &[(&str, bool, Option<i64>)]) -> serde_json::Value {
        let arr: Vec<serde_json::Value> = entries.iter().map(|(name, valid, cost)| {
            let cost_val: serde_json::Value = match cost {
                Some(c) => serde_json::json!(c),
                None => serde_json::Value::Null,
            };
            serde_json::json!({
                "name": name,
                "source": "synthetic",
                "tx": {},
                "input_boxes": [],
                "data_input_boxes": [],
                "expected": {"valid": valid, "cost": cost_val, "reason": serde_json::Value::Null}
            })
        }).collect();
        serde_json::json!({
            "schema": "santa-transaction/v1",
            "op": "tx:test:synthetic",
            "blessed_by": "test",
            "entries": arr
        })
    }

    static TALLY_TX_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

    /// Run the real tally over a synthetic transaction vector, written into a temporary vectors/ tree, graded through
    /// the committed vectors/grading.json. Returns the Counts for the single slice.
    fn tally_tx_inline(
        vec_json: &serde_json::Value,
        actuals_obj: serde_json::Map<String, serde_json::Value>,
    ) -> super::Counts {
        let n = TALLY_TX_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let root = std::env::temp_dir().join(format!("conform-tally-tx-{}-{n}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let dir = root.join("vectors").join("transaction").join("v6").join("authored");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("t.json"), vec_json.to_string()).unwrap();
        let mut actuals = std::collections::BTreeMap::new();
        actuals.insert("transaction/v6/authored/t.json".to_string(), serde_json::Value::Object(actuals_obj));
        let c = super::tally(&actuals, true, &root, &grading()).into_values().next().expect("one slice");
        fs::remove_dir_all(&root).ok();
        c
    }

    #[test]
    fn tally_tx_all_nice_green_slice() {
        // Two entries: accept(cost declared) + reject — both nice. No reds, clean summary.
        let vec = tx_vector(&[
            ("tx-accept", true, Some(14846)),
            ("tx-reject", false, None),
        ]);
        let mut act = serde_json::Map::new();
        act.insert("tx-accept".into(), serde_json::json!({"valid": true, "cost": 14846, "error": null}));
        act.insert("tx-reject".into(), serde_json::json!({"valid": false, "cost": null, "error": null}));
        let c = tally_tx_inline(&vec, act);
        assert_eq!(c.tx_valid_total, 2);
        assert_eq!(c.tx_valid_nice, 2);
        assert_eq!(c.tx_valid_coal, 0);
        assert_eq!(c.cost_graded, 1);
        assert_eq!(c.cost_nice, 1);
        assert_eq!(c.cost_coal, 0);
        assert_eq!(c.red_total(), 0);
        assert_eq!(c.summary(), "valid 2/2 · cost 1/1");
    }

    #[test]
    fn tally_tx_valid_mismatch_records_red_with_reason() {
        // accept + {valid:false} => valid=value coal; actual carries "reason" -> surfaced as note.
        let vec = tx_vector(&[("tx-bad", true, None)]);
        let mut act = serde_json::Map::new();
        act.insert("tx-bad".into(), serde_json::json!({"valid": false, "cost": null, "error": null, "reason": "token preservation violated"}));
        let c = tally_tx_inline(&vec, act);
        assert_eq!(c.tx_valid_total, 1);
        assert_eq!(c.tx_valid_coal, 1);
        assert_eq!(c.red_total(), 1);
        assert_eq!(c.red[0]["dim"], "valid");
        assert_eq!(c.red[0]["entry"], "tx-bad");
        assert_eq!(c.red[0]["note"], "token preservation violated");
    }

    #[test]
    fn tally_tx_cost_mismatch_valid_green() {
        // accept(cost declared) + {valid:true, cost:mismatch} => valid=nice, cost=cost (coal).
        let vec = tx_vector(&[("tx-cost-bad", true, Some(14846))]);
        let mut act = serde_json::Map::new();
        act.insert("tx-cost-bad".into(), serde_json::json!({"valid": true, "cost": 9999, "error": null}));
        let c = tally_tx_inline(&vec, act);
        assert_eq!(c.tx_valid_nice, 1);
        assert_eq!(c.tx_valid_coal, 0);
        assert_eq!(c.cost_graded, 1);
        assert_eq!(c.cost_coal, 1);
        assert_eq!(c.cost_nice, 0);
        assert_eq!(c.red_total(), 1);
        // The cost-red entry has dim "cost"; no valid-red entry.
        assert_eq!(c.red.len(), 1);
        assert_eq!(c.red[0]["dim"], "cost");
    }

    #[test]
    fn tally_tx_cost_na_not_counted_in_denominator() {
        // accept(no cost in expected) + {valid:true, cost:null} => valid=nice, cost n/a; 0 cost_graded.
        let vec = tx_vector(&[("tx-no-cost", true, None)]);
        let mut act = serde_json::Map::new();
        act.insert("tx-no-cost".into(), serde_json::json!({"valid": true, "cost": null, "error": null}));
        let c = tally_tx_inline(&vec, act);
        assert_eq!(c.tx_valid_nice, 1);
        assert_eq!(c.cost_graded, 0);
        assert_eq!(c.red_total(), 0);
    }

    #[test]
    fn tally_tx_not_impl_and_panicked_land_in_shared_counters() {
        let vec = tx_vector(&[
            ("tx-not-impl", true, None),
            ("tx-panicked", true, None),
        ]);
        let mut act = serde_json::Map::new();
        act.insert("tx-not-impl".into(), serde_json::json!({"valid": null, "cost": null, "error": "not-implemented"}));
        act.insert("tx-panicked".into(), serde_json::json!({"valid": null, "cost": null, "error": "panicked", "note": "OOM"}));
        let c = tally_tx_inline(&vec, act);
        assert_eq!(c.not_impl, 1);
        assert_eq!(c.panicked, 1);
        assert_eq!(c.tx_valid_total, 0); // not-impl and panicked don't touch tx_valid counters
        assert_eq!(c.red_total(), 2);
    }

    #[test]
    fn select_any_version_always_matches_for_mounted_tier() {
        let vs = vec![
            ("chain/any/captured/R.json".into(), "chain".into(), "any".into(), "captured".into(), "R".into()),
            ("chain/v6/captured/V.json".into(), "chain".into(), "v6".into(), "captured".into(), "V".into()),
            ("eval/v5/spec/E.json".into(), "eval".into(), "v5".into(), "spec".into(), "E".into()),
        ];
        // a v5 runner mounting chain still gets the `any` vector (and not the v6 one)
        let picked = select(&vs, "v5", &["chain".into()]);
        let rels: Vec<&str> = picked.iter().map(|v| v.0.as_str()).collect();
        assert_eq!(rels, vec!["chain/any/captured/R.json"]);
    }

    #[test]
    fn tally_grades_wire_roundtrip_against_committed_box_vector() {
        use serde_json::{json, Map, Value};
        use std::collections::BTreeMap;
        let root = super::repo_root();
        let rel = "wire/v5/vendored/Box.json".to_string();
        let vec: Value =
            serde_json::from_str(&fs::read_to_string(root.join("vectors").join(&rel)).unwrap()).unwrap();
        let entries = vec["entries"].as_array().unwrap();
        let key = ("wire".to_string(), "v5".to_string(), "vendored".to_string());

        // Echo actuals: every entry reserializes to its own committed bytes -> all round-trip nice.
        let mut act = Map::new();
        for e in entries {
            act.insert(e["name"].as_str().unwrap().to_string(), json!({"bytes_hex": e["bytes_hex"], "error": null}));
        }
        let mut actuals = BTreeMap::new();
        actuals.insert(rel.clone(), Value::Object(act.clone()));
        let c = &super::tally(&actuals, true, &root, &grading())[&key];
        assert_eq!(c.roundtrip_total, entries.len() as u64);
        assert_eq!(c.roundtrip_nice, entries.len() as u64);
        assert_eq!(c.red_total(), 0);

        // Corrupt one reserialization -> exactly one differ (coal), tagged dim "roundtrip".
        act.insert(entries[0]["name"].as_str().unwrap().to_string(), json!({"bytes_hex": "00", "error": null}));
        let mut actuals2 = BTreeMap::new();
        actuals2.insert(rel, Value::Object(act));
        let c2 = &super::tally(&actuals2, true, &root, &grading())[&key];
        assert_eq!(c2.roundtrip_coal, 1);
        assert_eq!(c2.red_total(), 1);
        assert_eq!(c2.red[0]["dim"], "roundtrip");
    }

    // ── chain tally tests ────────────────────────────────────────────────────

    /// Write a minimal santa-chain/v1 retargeting vector file under a temp vectors/ tree.
    /// Returns (tmp_root PathBuf, relpath, slice_key). Caller must drop tmp_root last.
    fn chain_vector_temp(nbits: u64, tag: &str) -> (PathBuf, String, (String, String, String)) {
        let tmp = std::env::temp_dir()
            .join(format!("santa-chain-tally-test-{}-{}-{}", std::process::id(), tag, nbits));
        let rel = "chain/any/captured/Retargeting.testnet.json";
        let vec_dir = tmp.join("vectors/chain/any/captured");
        fs::create_dir_all(&vec_dir).unwrap();
        let vec_json = serde_json::json!({
            "schema": "santa-chain/v1",
            "op": "chain:test:synthetic",
            "blessed_by": "test",
            "entries": [{
                "name": "retargeting-test",
                "source": "testnet:test@1",
                "kind": "retargeting",
                "settings": { "epoch_length": 128, "use_last_epochs": 8,
                               "block_interval_ms": 45000, "initial_nbits": 16842752 },
                "payload": { "target_height": 129, "anchor_headers": [] },
                "expected": { "nbits": nbits }
            }]
        });
        fs::write(tmp.join("vectors").join(rel), serde_json::to_string(&vec_json).unwrap()).unwrap();
        let key = ("chain".to_string(), "any".to_string(), "captured".to_string());
        (tmp, rel.to_string(), key)
    }

    #[test]
    fn tally_chain_all_nice_green_slice() {
        use serde_json::json;
        use std::collections::BTreeMap;
        let nbits: u64 = 84150434;
        let (tmp, rel, key) = chain_vector_temp(nbits, "green");
        let mut actuals = BTreeMap::new();
        actuals.insert(rel, json!({"retargeting-test": {"nbits": nbits, "error": null}}));
        let slices = super::tally(&actuals, false, &tmp, &grading());
        let c = &slices[&key];
        assert_eq!(c.chain_value_total, 1);
        assert_eq!(c.chain_value_nice, 1);
        assert_eq!(c.chain_value_coal, 0);
        assert_eq!(c.red_total(), 0);
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn tally_chain_mismatch_records_red_with_brief() {
        use serde_json::json;
        use std::collections::BTreeMap;
        let nbits: u64 = 84150434;
        let (tmp, rel, key) = chain_vector_temp(nbits, "mismatch");
        let mut actuals = BTreeMap::new();
        // off by one — mismatches the expected nbits
        actuals.insert(rel, json!({"retargeting-test": {"nbits": nbits + 1, "error": null}}));
        let slices = super::tally(&actuals, false, &tmp, &grading());
        let c = &slices[&key];
        assert_eq!(c.chain_value_total, 1);
        assert_eq!(c.chain_value_coal, 1);
        assert_eq!(c.red_total(), 1);
        assert_eq!(c.red[0]["dim"], "value");
        assert_eq!(c.red[0]["entry"], "retargeting-test");
        // expected field is a per-kind phrase — "nbits <N>" — must contain the expected nbits
        let exp_str = c.red[0]["expected"].as_str().unwrap_or("");
        assert!(exp_str.contains("nbits"),
            "expected brief should contain 'nbits', got: {exp_str}");
        assert!(exp_str.contains(&nbits.to_string()),
            "expected brief should contain {nbits}, got: {exp_str}");
        // got field is a per-kind phrase — "nbits <N>" — must contain the wrong (actual) nbits
        let got_str = c.red[0]["got"].as_str().unwrap_or("");
        let wrong_nbits = nbits + 1;
        assert!(got_str.contains("nbits"),
            "got brief should contain 'nbits', got: {got_str}");
        assert!(got_str.contains(&wrong_nbits.to_string()),
            "got brief should contain {wrong_nbits}, got: {got_str}");
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn tally_chain_not_impl_counts_coverage() {
        use serde_json::json;
        use std::collections::BTreeMap;
        let nbits: u64 = 84150434;
        let (tmp, rel, key) = chain_vector_temp(nbits, "notimpl");
        let mut actuals = BTreeMap::new();
        actuals.insert(rel, json!({
            "retargeting-test": {
                "nbits": null, "parameters": null, "activated_update": null,
                "error": "not-implemented"
            }
        }));
        let slices = super::tally(&actuals, false, &tmp, &grading());
        let c = &slices[&key];
        assert_eq!(c.not_impl, 1);
        assert_eq!(c.chain_value_total, 0);
        assert_eq!(c.chain_value_nice, 0);
        assert_eq!(c.chain_value_coal, 0);
        let _ = fs::remove_dir_all(&tmp);
    }

    // ── authds tally tests ───────────────────────────────────────────────────

    // Fix round 2: write_authds_prove_vector and write_authds_verify_vector both used to key
    // their temp root on process::id() + the caller's tag alone, with no kind-namespacing. Two
    // callers passing the same descriptive tag (e.g. both "digest-coal", one prove one verify)
    // computed the byte-identical path, and cargo test's parallel threads raced each other's
    // fs::remove_dir_all cleanup against the sibling's still-running tally() read — an observed
    // ~3%-of-runs NotFound panic, not hypothetical. A monotonic counter makes every call's root
    // unique by construction: it can never collide, even if a future test reuses "digest-coal"
    // again, because uniqueness no longer depends on any caller picking a distinct string.
    static AUTHDS_TEST_DIR_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

    /// The tag is a human-readable label only (visible in the dir name for debugging leftovers);
    /// the counter, not the tag, is what guarantees no two calls — same tag or not, same helper
    /// or not — ever compute the same root.
    fn authds_test_root(tag: &str) -> std::path::PathBuf {
        let n = AUTHDS_TEST_DIR_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        std::env::temp_dir()
            .join(format!("santa-authds-tally-test-{}-{}-{}", std::process::id(), n, tag))
    }

    /// Write a minimal santa-authds/v1 avl_prove vector under a temp vectors/ tree.
    fn write_authds_prove_vector(tag: &str, proofs: &[&str]) -> std::path::PathBuf {
        let root = authds_test_root(tag);
        let dir = root.join("vectors").join("authds").join("any").join("vendored");
        fs::create_dir_all(&dir).unwrap();
        let v = serde_json::json!({
            "schema": "santa-authds/v1",
            "op": "authds:vendored:probe",
            "blessed_by": "jvm:scrypto-3.0.0",
            "entries": [{
                "name": "probe#0",
                "source": "ergots:probe",
                "kind": "avl_prove",
                "settings": {"key_length": 32, "value_length": null},
                "payload": {"operations": [], "gen_proof_after": []},
                "expected": {"proofs": proofs, "digests": ["11"]}
            }]
        });
        fs::write(dir.join("AvlProve.probe.json"), serde_json::to_string(&v).unwrap()).unwrap();
        root
    }

    #[test]
    fn authds_prove_dims_tally_independently() {
        use serde_json::json;
        use std::collections::BTreeMap;
        let root = write_authds_prove_vector("indep", &["aa"]);
        let mut actuals = BTreeMap::new();
        actuals.insert(
            "authds/any/vendored/AvlProve.probe.json".to_string(),
            json!({"probe#0": {"proofs": ["ff"], "digests": ["11"], "error": null}}),
        );
        let slices = super::tally(&actuals, true, &root, &grading());
        let c = slices.values().next().unwrap();
        assert_eq!(c.authds_proof_coal, 1, "wrong proof bytes must be coal");
        assert_eq!(c.authds_digest_nice, 1, "matching digest must still be nice");
        assert_eq!(c.red_total(), 1);
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn authds_prove_digest_coal_independently_of_nice_proof() {
        // The mirror of authds_prove_dims_tally_independently: proof matches, digest doesn't.
        // Proves authds_digest_coal reaches red_total() from the *prove* arm specifically.
        use serde_json::json;
        use std::collections::BTreeMap;
        let root = write_authds_prove_vector("digest-coal", &["aa"]);
        let mut actuals = BTreeMap::new();
        actuals.insert(
            "authds/any/vendored/AvlProve.probe.json".to_string(),
            json!({"probe#0": {"proofs": ["aa"], "digests": ["22"], "error": null}}),
        );
        let slices = super::tally(&actuals, true, &root, &grading());
        let c = slices.values().next().unwrap();
        assert_eq!(c.authds_proof_nice, 1, "matching proof must still be nice");
        assert_eq!(c.authds_digest_coal, 1, "wrong digest must be coal");
        assert_eq!(c.red_total(), 1);
        fs::remove_dir_all(&root).ok();
    }

    /// Write a minimal santa-authds/v1 avl_verify vector under a temp vectors/ tree, with the
    /// given `expected` triple. Mirrors write_authds_prove_vector's shape for the verify kind.
    fn write_authds_verify_vector(
        tag: &str,
        expected_accepted: bool,
        expected_results: serde_json::Value,
        expected_digest: serde_json::Value,
    ) -> std::path::PathBuf {
        let root = authds_test_root(tag);
        let dir = root.join("vectors").join("authds").join("any").join("vendored");
        fs::create_dir_all(&dir).unwrap();
        let v = serde_json::json!({
            "schema": "santa-authds/v1",
            "op": "authds:vendored:probe",
            "blessed_by": "jvm:scrypto-3.0.0",
            "entries": [{
                "name": "probe#0",
                "source": "ergots:probe",
                "kind": "avl_verify",
                "settings": {"key_length": 32, "value_length": null,
                             "max_num_operations": null, "max_deletes": null},
                "payload": {"starting_digest_hex": "00", "proof_hex": "aa", "operations": []},
                "expected": {
                    "proof_accepted": expected_accepted,
                    "results": expected_results,
                    "new_digest_hex": expected_digest
                }
            }]
        });
        fs::write(dir.join("AvlVerify.probe.json"), serde_json::to_string(&v).unwrap()).unwrap();
        root
    }

    #[test]
    fn authds_verify_accepted_coal_suppresses_downstream_to_na() {
        // expected rejects the proof; actual over-accepts it (the reject arm's failure mode).
        // accepted must grade coal, and results/digest must stay untouched at n/a — proving
        // authds_accepted_coal reaches red_total(), and that a coal accepted truly withholds
        // the downstream totals rather than merely leaving their coal counters at 0.
        use serde_json::json;
        use std::collections::BTreeMap;
        let root = write_authds_verify_vector("accepted-coal", false, json!([]), json!(null));
        let mut actuals = BTreeMap::new();
        actuals.insert(
            "authds/any/vendored/AvlVerify.probe.json".to_string(),
            json!({"probe#0": {
                "proof_accepted": true,
                "results": [{"ok": true, "value": null}],
                "new_digest_hex": "33",
                "error": null
            }}),
        );
        let slices = super::tally(&actuals, true, &root, &grading());
        let c = slices.values().next().unwrap();
        assert_eq!(c.authds_accepted_coal, 1, "over-accept must be coal");
        assert_eq!(c.authds_results_total, 0, "results stays n/a (untouched), not merely coal-free");
        assert_eq!(c.authds_digest_total, 0, "digest stays n/a (untouched), not merely coal-free");
        assert_eq!(c.red_total(), 1);
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn authds_verify_results_coal_suppresses_digest_to_na() {
        // accepted matches (nice); one operation's outcome diverges -> results coal. digest is
        // downstream of results, so it must stay untouched at n/a. Proves authds_results_coal
        // reaches red_total().
        use serde_json::json;
        use std::collections::BTreeMap;
        let root = write_authds_verify_vector(
            "results-coal", true, json!([{"ok": true, "value": null}]), json!("33"));
        let mut actuals = BTreeMap::new();
        actuals.insert(
            "authds/any/vendored/AvlVerify.probe.json".to_string(),
            json!({"probe#0": {
                "proof_accepted": true,
                "results": [{"ok": false, "value": null}],
                "new_digest_hex": "33",
                "error": null
            }}),
        );
        let slices = super::tally(&actuals, true, &root, &grading());
        let c = slices.values().next().unwrap();
        assert_eq!(c.authds_accepted_nice, 1, "accepted must still be nice");
        assert_eq!(c.authds_results_coal, 1, "diverging operation outcome must be coal");
        assert_eq!(c.authds_digest_total, 0, "digest stays n/a (untouched) when results is coal");
        assert_eq!(c.red_total(), 1);
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn authds_verify_digest_coal_after_clean_chain() {
        // accepted and results both match (both nice); only the final digest diverges. Proves
        // authds_digest_coal reaches red_total() from the *verify* arm specifically (the prove
        // arm is covered separately by authds_prove_digest_coal_independently_of_nice_proof).
        use serde_json::json;
        use std::collections::BTreeMap;
        let root = write_authds_verify_vector(
            "digest-coal", true, json!([{"ok": true, "value": null}]), json!("33"));
        let mut actuals = BTreeMap::new();
        actuals.insert(
            "authds/any/vendored/AvlVerify.probe.json".to_string(),
            json!({"probe#0": {
                "proof_accepted": true,
                "results": [{"ok": true, "value": null}],
                "new_digest_hex": "ff",
                "error": null
            }}),
        );
        let slices = super::tally(&actuals, true, &root, &grading());
        let c = slices.values().next().unwrap();
        assert_eq!(c.authds_accepted_nice, 1, "accepted must still be nice");
        assert_eq!(c.authds_results_nice, 1, "results must still be nice");
        assert_eq!(c.authds_digest_coal, 1, "wrong final digest must be coal");
        assert_eq!(c.red_total(), 1);
        fs::remove_dir_all(&root).ok();
    }

    // ── actuals and bounded files ─────────────────────────────────────────────

    static BOUND_TEST_DIR_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

    fn bound_root(tag: &str) -> std::path::PathBuf {
        let n = BOUND_TEST_DIR_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let d = std::env::temp_dir().join(format!("conform-test-{}-{n}-{tag}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn one_entry_vector() -> serde_json::Value {
        json!({"schema": "santa-eval/v2", "op": "x", "limits": {"memory_mb": 1024, "wall_s": 10},
               "entries": [{"name": "e#0", "expected": {"value": {"kind": "Int", "value": 1}, "cost": 5, "error": null}}]})
    }

    #[test]
    fn actuals_for_success_reads_the_file() {
        let d = bound_root("success");
        let ofile = d.join("x.json");
        let a = json!({"e#0": {"value": {"kind": "Int", "value": 1}, "cost": 5, "error": null}});
        fs::write(&ofile, a.to_string()).unwrap();
        let s = schemas();
        assert_eq!(super::actuals_for(&ofile, &one_entry_vector(), &s, "eval"), a);
        assert_eq!(super::actuals_for(&d.join("absent.json"), &one_entry_vector(), &s, "eval"), json!({}));
    }

    fn schemas() -> super::ActualsSchemas {
        super::ActualsSchemas::load(&super::repo_root())
    }

    /// A runner's file is held to its tier's actuals schema entry by entry: an actual that fails it is `panicked`
    /// (coal), the note naming the schema and the field; an actual that holds, the tier-neutral not-implemented
    /// among them, is graded as written.
    #[test]
    fn actuals_for_holds_each_entry_to_its_tier_schema() {
        let d = bound_root("schema-eval");
        let ofile = d.join("x.json");
        let vector = json!({"schema": "santa-eval/v2", "entries": [{"name": "e#0"}, {"name": "e#1"}, {"name": "e#2"}]});
        let ok = json!({"value": {"kind": "Int", "value": 1}, "cost": 5, "error": null});
        fs::write(&ofile, json!({
            "e#0": ok,
            "e#1": {"value": {"kind": "Int", "value": 1}, "cost": "5", "error": null},
            "e#2": {"error": "not-implemented"}
        }).to_string()).unwrap();
        let a = super::actuals_for(&ofile, &vector, &schemas(), "eval");
        assert_eq!(a["e#0"], ok);
        assert_eq!(a["e#2"], json!({"error": "not-implemented"}));
        assert_eq!(a["e#1"]["error"], "panicked");
        assert_eq!(a["e#1"].as_object().unwrap().len(), 2, "{}", a["e#1"]);
        let note = a["e#1"]["note"].as_str().unwrap();
        assert!(note.contains("santa-eval.actuals.schema.json") && note.contains("cost: "), "{note}");
    }

    /// Where an entry may take the tier's own result or the tier-neutral not-implemented (anyOf), the note gives
    /// why the tier's result fails (here a block accept whose `reason` is null), not the anyOf's summary.
    #[test]
    fn actuals_for_names_the_tier_result_fault() {
        let d = bound_root("schema-block");
        let ofile = d.join("x.json");
        let vector = json!({"schema": "santa-block/v1", "entries": [{"name": "b#0"}]});
        let digest = format!("{}01", "ab".repeat(32));
        fs::write(&ofile, json!({"b#0": {"valid": true, "post_digest": digest, "cost": 7, "error": null, "reason": null}})
            .to_string()).unwrap();
        let a = super::actuals_for(&ofile, &vector, &schemas(), "block");
        assert_eq!(a["b#0"]["error"], "panicked");
        let note = a["b#0"]["note"].as_str().unwrap();
        assert!(note.contains("reason: ") && !note.contains("anyOf"), "{note}");
    }

    /// A file that is not JSON, or not a JSON object, fails every entry of its vector the same way (it used to abort
    /// the whole run).
    #[test]
    fn actuals_for_a_file_that_is_not_an_object_fails_every_entry() {
        let d = bound_root("schema-file");
        let ofile = d.join("x.json");
        let vector = json!({"schema": "santa-eval/v2", "entries": [{"name": "e#0"}, {"name": "e#1"}]});
        for (body, says) in [("{\"e#0\": ", "not JSON"), ("[1]", "not a JSON object")] {
            fs::write(&ofile, body).unwrap();
            let a = super::actuals_for(&ofile, &vector, &schemas(), "eval");
            for n in ["e#0", "e#1"] {
                assert_eq!(a[n]["error"], "panicked", "{body}");
                assert!(a[n]["note"].as_str().unwrap().contains(says), "{body}: {}", a[n]);
            }
        }
    }

    #[test]
    fn tally_counts_exceeded() {
        use std::collections::BTreeMap;
        let root = bound_root("tally");
        let dir = root.join("vectors").join("eval").join("v6").join("authored");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("x.json"), one_entry_vector().to_string()).unwrap();
        let mut actuals = BTreeMap::new();
        // the closed form a runner writes: the budget shown comes from the vector's `limits`
        actuals.insert("eval/v6/authored/x.json".to_string(), json!({"e#0": {"error": "exceeded", "limit": "memory"}}));
        let slices = super::tally(&actuals, true, &root, &grading());
        let c = slices.values().next().unwrap();
        assert_eq!(c.exceeded, 1);
        assert_eq!(c.red_total(), 1);
        assert_eq!(c.red[0]["dim"], "exceeded");
        assert_eq!(c.red[0]["got"], "exceeded memory (1024 MiB, 10 s)");
        assert_eq!(c.summary(), "1 exceeded");
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn over_budget_controls_names_only_controls() {
        let rs = vec![runner("rudolph", true), runner("dasher", false)];
        let r = |n: &str, f: serde_json::Value| json!({"name": n, "mark": "coal", "bounded_failures": f});
        assert_eq!(super::over_budget_controls(&rs, &[r("rudolph", json!(["eval/v6/authored/x.json"])), r("dasher", json!([]))]),
                   vec!["rudolph"]);
        assert!(super::over_budget_controls(&rs, &[r("rudolph", json!([])), r("dasher", json!(["eval/v6/authored/x.json"]))]).is_empty());
    }

    #[test]
    fn fail_message_names_the_ceiling() {
        assert_eq!(super::fail_message(&super::RunFail::Ceiling("memory 4096 MiB".into())),
                   "santa-run exceeded the safety ceiling (memory 4096 MiB)");
        assert_eq!(super::fail_message(&super::RunFail::Exit(101)), "santa-run exited 101: could not build/run");
        assert_eq!(super::fail_message(&super::RunFail::Results("no results.json".into())), "santa-run's outputs: no results.json");
    }

    /// `vectors/grading.json` is the grading rules, not a vector: discovery skips it.
    #[test]
    fn discovery_skips_the_grading_file() {
        let tmp = std::env::temp_dir().join(format!("conform-grading-skip-{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        let dir = tmp.join("eval").join("v6").join("authored");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("x.json"), "{}").unwrap();
        fs::write(tmp.join("grading.json"), "{}").unwrap();
        let v = super::discover_vectors(&tmp);
        assert_eq!(v.len(), 1);
        assert_eq!(v[0].0, "eval/v6/authored/x.json");
        fs::remove_dir_all(&tmp).ok();
    }

    /// The committed grading rules, as main loads them.
    fn grading() -> santa_check::grading::Grading {
        super::load_grading(&super::repo_root())
    }

    /// tally grades through vectors/grading.json: a block accept whose post-state digest is wrong is nice on
    /// `valid`, red on `post_digest` (one red record, dim post_digest), and cost (after the digest) not graded.
    #[test]
    fn tally_grades_through_the_grading_file() {
        use std::collections::BTreeMap;
        let root = std::env::temp_dir().join(format!("conform-tally-grading-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let dir = root.join("vectors").join("block").join("v6").join("authored");
        fs::create_dir_all(&dir).unwrap();
        let digest = format!("{}01", "ab".repeat(32));
        fs::write(dir.join("b.json"), json!({"schema": "santa-block/v1", "op": "b", "blessed_by": "test", "entries": [
            {"name": "b#0", "expected": {"valid": true, "post_digest": digest, "cost": 7}}]}).to_string()).unwrap();
        let mut actuals = BTreeMap::new();
        actuals.insert("block/v6/authored/b.json".to_string(),
            json!({"b#0": {"valid": true, "post_digest": format!("{}01", "cd".repeat(32)), "cost": 7, "error": null}}));
        let slices = super::tally(&actuals, true, &root, &grading());
        let c = &slices[&("block".to_string(), "v6".to_string(), "authored".to_string())];
        assert_eq!((c.block_valid_total, c.block_valid_nice), (1, 1));
        assert_eq!((c.post_digest_total, c.post_digest_coal), (1, 1));
        assert_eq!(c.cost_graded, 0);
        assert_eq!(c.red.len(), 1);
        assert_eq!(c.red[0]["dim"], "post_digest");
        fs::remove_dir_all(&root).ok();
    }

    /// Before any runner runs, every vector file's grading label resolves and every dimension of the reachable types
    /// has a board counter: a grading mistake stops conform at once, not after a whole runner.
    #[test]
    fn grading_is_checked_before_any_runner_runs() {
        let root = std::env::temp_dir().join(format!("conform-grading-check-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let dir = root.join("vectors").join("eval").join("v6").join("authored");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("x.json"), json!({"schema": "santa-eval/v2", "entries": []}).to_string()).unwrap();
        let vectors = super::discover_vectors(&root.join("vectors"));
        assert_eq!(super::check_grading(&grading(), &vectors, &root), Ok(()));
        // a dimension the board has no counter for, in the eval type
        let mut doc: serde_json::Value = serde_json::from_str(&fs::read_to_string(super::repo_root().join("vectors/grading.json")).unwrap()).unwrap();
        doc["types"]["eval"]["branches"][0]["dims"][0]["name"] = json!("rejekt");
        let bad = santa_check::grading::Grading::parse(&doc).unwrap();
        let e = super::check_grading(&bad, &vectors, &root).unwrap_err();
        assert!(e.contains("rejekt"), "{e}");
        // a file naming a label the grading file does not define
        fs::write(dir.join("x.json"), json!({"schema": "santa-eval/v2", "grading": "nope", "entries": []}).to_string()).unwrap();
        let e = super::check_grading(&grading(), &vectors, &root).unwrap_err();
        assert!(e.contains("nope"), "{e}");
        fs::remove_dir_all(&root).ok();
    }

    /// A bounded file whose process went over its budget answers the tier-neutral `{"error": "exceeded", "limit":
    /// "memory" | "time"}`, which every tier's actuals schema admits; the form is closed, `limit` required.
    #[test]
    fn every_actuals_schema_admits_the_tier_neutral_exceeded() {
        let s = schemas();
        for tier in ["eval", "wire", "transaction", "block", "chain", "authds", "nipopow"] {
            for limit in ["memory", "time"] {
                let a = json!({"error": "exceeded", "limit": limit});
                assert_eq!(s.fault(tier, "e", &a), None, "{tier}: {a}");
            }
            for a in [
                json!({"error": "exceeded"}),
                json!({"error": "exceeded", "limit": "disk"}),
                json!({"error": "exceeded", "limit": "time", "budget": {"memory_mb": 1024, "wall_s": 10}}),
                json!({"error": "exceeded", "limit": "time", "note": "x"}),
            ] {
                assert!(s.fault(tier, "e", &a).is_some(), "{tier} admits {a}");
            }
        }
    }

    /// conform's selection reproduces every case of oracle/selection.json (runner-integration §4.1), through the
    /// real discovery and wire-fact reading over a temporary vectors/ tree built from the oracle's `files`.
    #[test]
    fn the_selection_oracle_is_reproduced() {
        let doc: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(super::repo_root().join("oracle/selection.json")).unwrap()).unwrap();
        assert_eq!(doc["schema"], "santa-oracle-selection/v1");
        let root = std::env::temp_dir().join(format!("conform-selection-oracle-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let vdir = root.join("vectors");
        for f in doc["files"].as_array().unwrap() {
            let path = vdir.join(f["path"].as_str().unwrap());
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            let body = if let Some(kind) = f.get("kind") {
                let mut w = json!({"entries": [{"kind": kind}]});
                if let Some(a) = f.get("activation_rule") {
                    w["activation_rule"] = a.clone();
                }
                w.to_string()
            } else if path.extension().and_then(|x| x.to_str()) != Some("json") {
                "not a vector".to_string()
            } else if f["path"] == "grading.json" {
                "{}".to_string()
            } else {
                json!({"entries": []}).to_string()
            };
            fs::write(&path, body).unwrap();
        }
        let vectors = super::discover_vectors(&vdir);
        let facts = super::wire_facts(&vdir, &vectors).unwrap();
        let cases = doc["cases"].as_array().unwrap();
        assert!(!cases.is_empty());
        for c in cases {
            let scope = super::parse_scope(&c["scope"]).unwrap_or_else(|e| panic!("{}: {e}", c["name"]));
            let got: Vec<String> = super::selection(&vectors, &facts, &scope).iter().map(|v| v.0.clone()).collect();
            let want: Vec<String> = c["selects"].as_array().unwrap().iter().map(|p| p.as_str().unwrap().to_string()).collect();
            assert_eq!(got, want, "case {}", c["name"]);
        }
        fs::remove_dir_all(&root).ok();
    }

    /// schema/santa-runner-results.schema.json (santa-runner-results/v1) admits a complete results file, with
    /// `santa` a commit or null, and refuses each fault a runner could make.
    #[test]
    fn the_runner_results_schema_admits_a_results_file_and_refuses_faults() {
        let path = super::repo_root().join("schema/santa-runner-results.schema.json");
        let schema: serde_json::Value = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
        let v = jsonschema::validator_for(&schema).unwrap();
        let good = json!({
            "schema": "santa-runner-results/v1",
            "santa": "4ce4855ef9b1b98ec14158ad12cc57ecb8a1265a",
            "scope": {"version": "v6", "tiers": ["eval", "wire"], "cost": true, "kinds": ["Box"], "activation": false},
            "slices": {
                "eval/v6/authored": {
                    "files": ["eval/v6/authored/AND.json", "eval/v6/authored/OR.json"],
                    "dims": {"value": {"graded": 9, "nice": 8}, "cost": {"graded": 8, "nice": 8}},
                    "not_implemented": 1, "panicked": 0, "exceeded": 0,
                    "red": [
                        {"file": "eval/v6/authored/AND.json", "entry": "and-1", "dim": "value",
                         "expected": "{\"kind\":\"Boolean\",\"value\":true} @ cost 20", "got": "{\"kind\":\"Boolean\",\"value\":false}"},
                        {"file": "eval/v6/authored/OR.json", "entry": "or-3", "dim": "not-implemented",
                         "expected": "errored", "got": "not-implemented"}
                    ]
                }
            },
            "totals": {"red": 2, "not_implemented": 1}
        });
        assert!(v.is_valid(&good), "{:?}", v.validate(&good).err().map(|e| e.to_string()));
        let mut null_santa = good.clone();
        null_santa["santa"] = json!(null);
        assert!(v.is_valid(&null_santa));
        let slice = "/slices/eval~1v6~1authored";
        type Fault = Box<dyn Fn(&mut serde_json::Value)>;
        let faults: Vec<(&str, Fault)> = vec![
            ("no santa key", Box::new(|d| { d.as_object_mut().unwrap().remove("santa"); })),
            ("slice key eval/v7/authored", Box::new(|d| {
                let s = d["slices"].as_object_mut().unwrap().remove("eval/v6/authored").unwrap();
                d["slices"]["eval/v7/authored"] = s;
            })),
            ("dim valud", Box::new(move |d| {
                let x = d.pointer_mut(&format!("{slice}/dims")).unwrap().as_object_mut().unwrap();
                let val = x.remove("value").unwrap();
                x.insert("valud".into(), val);
            })),
            ("red without dim", Box::new(move |d| {
                d.pointer_mut(&format!("{slice}/red/0")).unwrap().as_object_mut().unwrap().remove("dim");
            })),
            ("red dim errored", Box::new(move |d| { *d.pointer_mut(&format!("{slice}/red/0/dim")).unwrap() = json!("errored"); })),
            ("panicked -1", Box::new(move |d| { *d.pointer_mut(&format!("{slice}/panicked")).unwrap() = json!(-1); })),
            ("extra top-level key", Box::new(|d| { d["extra"] = json!(1); })),
            ("dims.value without nice", Box::new(move |d| {
                d.pointer_mut(&format!("{slice}/dims/value")).unwrap().as_object_mut().unwrap().remove("nice");
            })),
            ("scope version any", Box::new(|d| { d["scope"]["version"] = json!("any"); })),
        ];
        for (what, f) in faults {
            let mut d = good.clone();
            f(&mut d);
            assert!(!v.is_valid(&d), "the schema admits a results file with {what}");
        }
    }

    /// A scope names what it means, as the results schema requires: no empty, unknown or repeated tier, no repeated
    /// kind (an unknown tier would select nothing, silently).
    #[test]
    fn a_scope_refuses_empty_unknown_or_repeated_tiers_and_repeated_kinds() {
        let e = |j: serde_json::Value| super::parse_scope(&j).err().unwrap_or_default();
        assert!(e(json!({"version": "v6", "tiers": []})).contains("tiers"), "empty tiers");
        assert!(e(json!({"version": "v6", "tiers": ["evil"]})).contains("evil"), "unknown tier");
        assert!(e(json!({"version": "v6", "tiers": ["eval", "eval"]})).contains("eval"), "repeated tier");
        assert!(e(json!({"version": "v6", "tiers": ["wire"], "kinds": ["Box", "Box"]})).contains("Box"), "repeated kind");
        assert!(super::parse_scope(&json!({"version": "v6", "tiers": ["eval", "wire"], "kinds": ["Box"]})).is_ok());
    }

    /// A script the ceiling tests run as `bash <script>`: executing a file just written races with other test threads'
    /// forks ("text file busy"), reading it does not.
    fn ceiling_script(tag: &str, body: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("conform-ceiling-{}-{tag}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        let s = d.join("run.sh");
        fs::write(&s, format!("#!/usr/bin/env bash\n{body}\n")).unwrap();
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&s, fs::Permissions::from_mode(0o755)).unwrap();
        s
    }

    /// A run past its wall time is killed: the safety ceiling (time).
    #[test]
    fn the_ceiling_kills_a_run_past_its_wall_time() {
        let s = ceiling_script("wall", "sleep 30");
        let t0 = std::time::Instant::now();
        let r = super::run_under_ceiling(Command::new("bash").arg(&s), &super::Ceiling { data_mb: 8192, wall_s: 1 });
        assert!(matches!(&r, Err(super::RunFail::Ceiling(l)) if l == "time 1 s"), "{:?}", r.as_ref().map(|s| s.code()));
        assert!(t0.elapsed() < std::time::Duration::from_secs(5));
    }

    /// The whole process group dies at the ceiling, a grandchild included.
    #[test]
    fn the_ceiling_kills_the_whole_group() {
        let s = ceiling_script("group", "sleep 30 &\necho $! > \"$(dirname \"$0\")/child.pid\"\nsleep 30");
        let r = super::run_under_ceiling(Command::new("bash").arg(&s), &super::Ceiling { data_mb: 8192, wall_s: 1 });
        assert!(matches!(r, Err(super::RunFail::Ceiling(_))));
        let pid = fs::read_to_string(s.parent().unwrap().join("child.pid")).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(200));
        let alive = Command::new("kill").args(["-0", pid.trim()]).status().unwrap().success();
        assert!(!alive, "the grandchild {} outlived the ceiling", pid.trim());
    }

    /// Each process runs under the data limit.
    #[test]
    fn the_ceiling_limits_each_process_data() {
        let s = ceiling_script("data", "exec python3 -I -c 'bytearray(64 << 20)'");
        let small = super::run_under_ceiling(Command::new("bash").arg(&s), &super::Ceiling { data_mb: 32, wall_s: 30 });
        assert!(matches!(&small, Ok(st) if !st.success()));
        let big = super::run_under_ceiling(Command::new("bash").arg(&s), &super::Ceiling { data_mb: 8192, wall_s: 30 });
        assert!(matches!(&big, Ok(st) if st.success()));
    }

    fn out_dir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("conform-out-{}-{tag}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn results_doc(santa: serde_json::Value) -> serde_json::Value {
        json!({
            "schema": "santa-runner-results/v1", "santa": santa,
            "scope": {"version": "v6", "tiers": ["eval"], "cost": true},
            "slices": {"eval/v6/authored": {"files": ["eval/v6/authored/x.json"], "dims": {"value": {"graded": 1, "nice": 1}},
                       "not_implemented": 0, "panicked": 0, "exceeded": 0, "red": []}},
            "totals": {"red": 0, "not_implemented": 0}
        })
    }

    /// A results.json conform cannot use is refused, saying why: absent, not JSON, failing its schema, or graded
    /// against another SANTA commit (null included).
    #[test]
    fn read_results_refuses_what_it_cannot_use() {
        let v = super::load_results_schema(&super::repo_root());
        let head = "4ce4855ef9b1b98ec14158ad12cc57ecb8a1265a";
        let d = out_dir("results");
        assert_eq!(super::read_results(&d, &v, head).unwrap_err(), "no results.json");
        fs::write(d.join("results.json"), "{").unwrap();
        assert!(super::read_results(&d, &v, head).unwrap_err().starts_with("results.json is not JSON"));
        let mut no_totals = results_doc(json!(head));
        no_totals.as_object_mut().unwrap().remove("totals");
        fs::write(d.join("results.json"), no_totals.to_string()).unwrap();
        assert!(super::read_results(&d, &v, head).unwrap_err().starts_with("results.json fails"));
        fs::write(d.join("results.json"), results_doc(json!("015ec7a97a5fce9e5d16693c77566ec65803086d")).to_string()).unwrap();
        assert!(super::read_results(&d, &v, head).unwrap_err().contains("not this checkout's"));
        fs::write(d.join("results.json"), results_doc(json!(null)).to_string()).unwrap();
        assert!(super::read_results(&d, &v, head).unwrap_err().contains("null"));
        fs::write(d.join("results.json"), results_doc(json!(head)).to_string()).unwrap();
        assert!(super::read_results(&d, &v, head).is_ok());
        assert_eq!(super::head(&super::repo_root()).map(|h| h.len()), Some(40));
    }

    /// The actuals tree: each selected file at actuals/<relpath>, held to its tier's schema entry by entry; an absent
    /// file is {} (every entry of it grades red).
    #[test]
    fn read_actuals_reads_the_tree_and_fills_gaps() {
        let root = out_dir("actuals-root");
        let dir = root.join("vectors").join("eval").join("v6").join("authored");
        fs::create_dir_all(&dir).unwrap();
        let vector = json!({"schema": "santa-eval/v2", "entries": [{"name": "e#0"}, {"name": "e#1"}]});
        fs::write(dir.join("a.json"), vector.to_string()).unwrap();
        fs::write(dir.join("b.json"), vector.to_string()).unwrap();
        let out = out_dir("actuals-out");
        let adir = out.join("actuals").join("eval").join("v6").join("authored");
        fs::create_dir_all(&adir).unwrap();
        let ok = json!({"value": {"kind": "Int", "value": 1}, "cost": 5, "error": null});
        fs::write(adir.join("a.json"), json!({"e#0": ok, "e#1": {"value": 1, "cost": "x", "error": null}}).to_string()).unwrap();
        let vs = super::discover_vectors(&root.join("vectors"));
        let selected: Vec<&super::Vec5> = vs.iter().collect();
        let a = super::read_actuals(&out, &selected, &root, &schemas());
        assert_eq!(a["eval/v6/authored/a.json"]["e#0"], ok);
        assert_eq!(a["eval/v6/authored/a.json"]["e#1"]["error"], "panicked");
        assert!(a["eval/v6/authored/a.json"]["e#1"]["note"].as_str().unwrap().contains("santa-eval.actuals.schema.json"));
        assert_eq!(a["eval/v6/authored/b.json"], json!({}));
    }

    fn canary_with(reds: &[(&str, &str, &str)]) -> BTreeMap<(String, String, String), super::Counts> {
        let mut c = super::Counts::default();
        for (f, e, d) in reds {
            c.red.push(json!({"file": f, "entry": e, "dim": d, "op": "x", "expected": "", "got": ""}));
        }
        BTreeMap::from([(("eval".to_string(), "v6".to_string(), "authored".to_string()), c)])
    }

    fn runner_with(files: &[&str], reds: &[(&str, &str, &str)]) -> serde_json::Value {
        let red: Vec<serde_json::Value> = reds.iter()
            .map(|(f, e, d)| json!({"file": f, "entry": e, "dim": d, "expected": "", "got": ""})).collect();
        json!({"slices": {"eval/v6/authored": {"files": files, "red": red}}})
    }

    fn sel(files: &[&str]) -> std::collections::BTreeSet<String> {
        files.iter().map(|f| f.to_string()).collect()
    }

    const F: &str = "eval/v6/authored/f.json";

    #[test]
    fn grader_agrees_when_the_red_sets_match() {
        let g = super::grader(&runner_with(&[F], &[(F, "e1", "value")]), &canary_with(&[(F, "e1", "value")]), &sel(&[F]));
        assert_eq!(g["agrees"], true);
        assert_eq!(g["disagreement_count"], 0);
        assert_eq!(g["disagreements"], json!([]));
    }

    #[test]
    fn grader_lists_each_side_of_a_disagreement() {
        let g = super::grader(&runner_with(&[F], &[(F, "e1", "value")]), &canary_with(&[(F, "e2", "cost")]), &sel(&[F]));
        assert_eq!(g["agrees"], false);
        assert_eq!(g["disagreements"], json!([
            {"file": F, "entry": "e1", "dim": "value", "runner": "red", "santa": "nice"},
            {"file": F, "entry": "e2", "dim": "cost", "runner": "nice", "santa": "red"},
        ]));
    }

    #[test]
    fn grader_names_missing_and_extra_files() {
        let g = super::grader(&runner_with(&[F, "eval/v6/authored/extra.json"], &[]), &canary_with(&[]),
            &sel(&[F, "eval/v6/authored/missing.json"]));
        assert_eq!(g["agrees"], false);
        assert_eq!(g["missing_files"], json!(["eval/v6/authored/missing.json"]));
        assert_eq!(g["extra_files"], json!(["eval/v6/authored/extra.json"]));
    }

    #[test]
    fn grader_caps_the_list_and_keeps_the_count() {
        let names: Vec<String> = (0..250).map(|i| format!("e{i:03}")).collect();
        let reds: Vec<(&str, &str, &str)> = names.iter().map(|n| (F, n.as_str(), "value")).collect();
        let g = super::grader(&runner_with(&[F], &reds), &canary_with(&[]), &sel(&[F]));
        assert_eq!(g["disagreement_count"], 250);
        assert_eq!(g["disagreements"].as_array().unwrap().len(), super::MAX_DISAGREEMENTS);
    }

    #[test]
    fn tally_red_records_carry_their_file() {
        let root = bound_root("tally-file");
        let dir = root.join("vectors").join("eval").join("v6").join("authored");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("x.json"), one_entry_vector().to_string()).unwrap();
        let actuals = BTreeMap::from([("eval/v6/authored/x.json".to_string(), json!({"e#0": {"error": "panicked", "note": "boom"}}))]);
        let slices = super::tally(&actuals, true, &root, &grading());
        assert_eq!(slices.values().next().unwrap().red[0]["file"], "eval/v6/authored/x.json");
        fs::remove_dir_all(&root).ok();
    }

    const PLAIN: &str = "eval/v6/authored/HOF_currying_Apply_of_Apply.json";
    const BOUNDED: &str = "eval/v6/authored/Global.serialize_shared_nested_bounded_G_size_only_32768x4096.json";

    /// The fixture runner (tests/fixture-runner/santa-run) over two real eval files, one of them bounded.
    fn run_fixture(mode: &str, tag: &str) -> Result<super::RunOutputs, super::RunFail> {
        let root = super::repo_root();
        let all = super::discover_vectors(&root.join("vectors"));
        let vectors: Vec<Vec5> = all.into_iter().filter(|v| v.0 == PLAIN || v.0 == BOUNDED).collect();
        assert_eq!(vectors.len(), 2);
        let facts = super::wire_facts(&root.join("vectors"), &vectors).unwrap();
        let schemas = schemas();
        let results_schema = super::load_results_schema(&root);
        let head = super::head(&root).unwrap();
        let corpus = super::Corpus { root: &root, vectors: &vectors, facts: &facts, schemas: &schemas, results_schema: &results_schema, head: &head };
        let ws = std::env::temp_dir().join(format!("conform-fixture-{}-{tag}", std::process::id()));
        let _ = fs::remove_dir_all(&ws);
        fs::create_dir_all(&ws).unwrap();
        let run = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixture-runner/santa-run");
        std::env::set_var("FIXTURE_FILES", format!("{PLAIN}\n{BOUNDED}"));
        std::env::set_var("FIXTURE_MODE", mode);
        let r = super::run_runner(&run, "-", &scope(), &ws, &corpus);
        std::env::remove_var("FIXTURE_MODE");
        r
    }

    /// conform runs a self-contained runner, reads its outputs and re-grades them: an honest runner agrees with the
    /// canary, a lying one is caught, a silent one is refused.
    #[test]
    fn a_fixture_runner_is_run_and_canaried() {
        let root = super::repo_root();
        let (actuals, failures, results) = run_fixture("honest", "honest").unwrap_or_else(|e| panic!("{e:?}"));
        assert_eq!(actuals.keys().cloned().collect::<Vec<_>>(), vec![BOUNDED.to_string(), PLAIN.to_string()]);
        assert!(actuals.values().all(|a| a.as_object().is_some_and(|m| !m.is_empty())));
        assert!(failures.is_empty());
        let selected: std::collections::BTreeSet<String> = actuals.keys().cloned().collect();
        let canary = super::tally(&actuals, true, &root, &grading());
        assert_eq!(super::grader(&results, &canary, &selected)["agrees"], true);

        let (actuals, _, results) = run_fixture("lying", "lying").unwrap_or_else(|e| panic!("{e:?}"));
        let canary = super::tally(&actuals, true, &root, &grading());
        let g = super::grader(&results, &canary, &selected);
        assert_eq!(g["agrees"], false);
        assert_eq!(g["disagreement_count"], 1);
        assert_eq!(g["disagreements"][0]["runner"], "red");
        assert_eq!(g["disagreements"][0]["santa"], "nice");

        match run_fixture("silent", "silent") {
            Err(super::RunFail::Results(m)) => assert_eq!(m, "no results.json"),
            other => panic!("{:?}", other.map(|_| ())),
        }
    }

    /// The control check reads the actuals: a bounded file whose entry exceeded or panicked is a bounded failure; a
    /// plain file's panic is not.
    #[test]
    fn bounded_failures_come_from_the_actuals() {
        let actuals = BTreeMap::from([
            (BOUNDED.to_string(), json!({"G-size-only-32768x4096-a3#0": {"error": "exceeded", "limit": "memory"}})),
            (PLAIN.to_string(), json!({"x": {"error": "panicked", "note": "boom"}})),
        ]);
        assert_eq!(super::bounded_failures(&actuals, &super::repo_root()), vec![BOUNDED.to_string()]);
    }
}

