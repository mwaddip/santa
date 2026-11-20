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
use santa_check::{grade, grade_authds, grade_block, grade_chain, grade_nipopow, grade_transaction, grade_wire};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::{Component, Path, PathBuf};
use std::process::{Command, ExitCode};
use std::{env, fs};

/// Protocol slots, ordered; cumulative (a runner's version implies all lower). `v4` is everything below the JIT
/// activation (an entry's `version.activated` 0 or 1), `v5` is activated 2, `v6` activated 3.
const VERSIONS: [&str; 3] = ["v4", "v5", "v6"];

/// The wire kinds, as the wire vector schema's `kind` enum lists them (a test holds the two equal). A runner's
/// manifest may declare the ones it implements (`kinds`); a wire file holds one kind (./validate).
const WIRE_KINDS: [&str; 7] = ["Constant", "Box", "Transaction", "Header", "SigmaBoolean", "ErgoTree", "BlockTransactions"];

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
enum RunFail {
    /// santa-run exited non-zero: build, impl checkout, or a crash over the ordinary files.
    Exit(i32),
    /// The ordinary run hit santa-bound's safety ceiling ("memory 4096 MiB" | "time 600 s"): it protects the machine.
    Ceiling(String),
    /// santa-bound's records are missing: the runner's last line skipped it, or a bounded file has no record.
    Bound(String),
    /// The implementation's checkout holds no runner where SANTA's `runner` points, or one conform refuses.
    Hosted(String),
}

fn fail_message(f: &RunFail) -> String {
    match f {
        RunFail::Exit(c) => format!("santa-run exited {c}: could not build/run"),
        RunFail::Ceiling(l) => format!("santa-run exceeded the safety ceiling ({l})"),
        RunFail::Bound(m) => m.clone(),
        RunFail::Hosted(m) => format!("the implementation's runner: {m}"),
    }
}

/// santa-bound is built beside conform (the ./conform wrapper builds both, same profile).
fn santa_bound_path() -> PathBuf {
    match env::current_exe().ok().and_then(|e| e.parent().map(|d| d.join("santa-bound"))) {
        Some(p) if p.is_file() => p,
        _ => die("santa-bound is not built next to conform: run ./conform, whose wrapper builds it".into()),
    }
}

/// santa-bound's status records: one per bounded file (by staged name), and the ordinary run's.
struct Bound {
    bounded: BTreeMap<String, Value>,
    ordinary: Option<Value>,
}

fn read_json(p: &Path) -> Option<Value> {
    serde_json::from_str(&fs::read_to_string(p).ok()?).ok()
}

/// The status dir santa-bound wrote. Err when there is no plan (the runner's last line skipped santa-bound, so its
/// bounded files ran unbounded) or a planned bounded file has no record.
fn read_bound(status: &Path) -> Result<Bound, String> {
    let plan = read_json(&status.join("plan.json")).ok_or_else(|| {
        "santa-run did not run its last line through santa-bound (no plan.json), so its bounded files ran unbounded"
            .to_string()
    })?;
    let mut bounded = BTreeMap::new();
    for name in plan["bounded"].as_object().into_iter().flat_map(|m| m.keys()) {
        let r = read_json(&status.join("bounded").join(name))
            .ok_or_else(|| format!("santa-bound left no record for the bounded file {name}"))?;
        bounded.insert(name.clone(), r);
    }
    Ok(Bound { bounded, ordinary: read_json(&status.join("ordinary.json")) })
}

/// One staged file's actuals. A bounded file that exceeded its budget or crashed gets the actual conform writes for
/// each entry (`exceeded` / `panicked`), whatever file its process left; otherwise the runner's file, or {} if absent.
fn actuals_for(ofile: &Path, record: Option<&Value>, vector: &Value) -> Value {
    let each = |a: Value| -> Value {
        let names = vector["entries"].as_array().into_iter().flatten().filter_map(|e| e["name"].as_str());
        Value::Object(names.map(|n| (n.to_string(), a.clone())).collect())
    };
    match record {
        Some(r) if r["result"] == "exceeded" => {
            each(json!({"error": "exceeded", "limit": r["limit"], "budget": r["limits"]}))
        }
        Some(r) if r["result"] == "crashed" => {
            each(json!({"error": "panicked", "note": format!("process {}", r["detail"].as_str().unwrap_or("?"))}))
        }
        // Totality: an absent file becomes {} so every entry grades (act None -> coal), surfacing the
        // breach rather than silently dropping the vector.
        _ if ofile.is_file() => serde_json::from_str(&fs::read_to_string(ofile).unwrap()).unwrap(),
        _ => json!({}),
    }
}

/// The ordinary run's ceiling hit, as "memory 4096 MiB" / "time 600 s".
fn ceiling_hit(ordinary: &Value) -> Option<String> {
    let l = &ordinary["limits"];
    match (ordinary["result"].as_str(), ordinary["limit"].as_str()) {
        (Some("exceeded"), Some("memory")) => Some(format!("memory {} MiB", l["memory_mb"])),
        (Some("exceeded"), Some("time")) => Some(format!("time {} s", l["wall_s"])),
        _ => None,
    }
}

/// A runner that ran: the scope it was graded at, its actuals by relpath, the impl sha, and the bounded files that
/// exceeded or crashed.
struct Ran {
    scope: Scope,
    actuals: BTreeMap<String, Value>,
    sha: Option<String>,
    failures: Vec<String>,
}

/// Runs one runner over its selection. A failure carries the scope when conform got far enough to know it (a hosted
/// runner's is read from its checkout), so the grid can still say which tiers went ungraded.
fn run_one(
    m: &Runner,
    root: &Path,
    vectors: &[Vec5],
    facts: &BTreeMap<String, WireFacts>,
) -> Result<Ran, (Option<Scope>, RunFail)> {
    let ws = root.join(".santa").join(&m.name);
    let imp = resolve_impl(m.impl_.as_deref(), m.impl_name.as_deref().unwrap_or(""), &ws)
        .map_err(|c| (m.declared().cloned(), RunFail::Exit(c)))?;
    let (scope, run) = match &m.host {
        Host::Santa { scope, run } => (scope.clone(), run.clone()),
        Host::Impl(path) => {
            let checkout = imp.checkout.as_deref().expect("a runner entry has an impl (parse_manifest)");
            hosted_runner(checkout, path).map_err(|e| (None, RunFail::Hosted(e)))?
        }
    };
    match run_staged(&run, &imp.arg, &scope, &ws, root, vectors, facts) {
        Ok((actuals, failures)) => Ok(Ran { scope, actuals, sha: imp.sha, failures }),
        Err(f) => Err((Some(scope), f)),
    }
}

/// Stages the scope's selection (version<=, tiers, kinds, activation) flat as symlinks under `ws` (= .santa/<name>/,
/// where everything per-runner lives) and runs `run` over it: (actuals by relpath, the bounded files that exceeded or
/// crashed).
fn run_staged(
    run: &Path,
    impl_path: &str,
    scope: &Scope,
    ws: &Path,
    root: &Path,
    vectors: &[Vec5],
    facts: &BTreeMap<String, WireFacts>,
) -> Result<(BTreeMap<String, Value>, Vec<String>), RunFail> {
    let selected: Vec<&Vec5> = select(vectors, &scope.version, &scope.tiers)
        .into_iter()
        .filter(|v| in_scope(&v.0, facts, &scope.kinds, scope.activation))
        .collect();
    let indir = ws.join("in");
    let _ = fs::remove_dir_all(&indir);
    fs::create_dir_all(&indir).unwrap();
    let odir = ws.join("out");
    let _ = fs::remove_dir_all(&odir);
    fs::create_dir_all(&odir).unwrap();
    // santa-bound's status dir sits on the same filesystem as the staging, so its unit dirs list in the same order.
    let status = ws.join("bound");
    let _ = fs::remove_dir_all(&status);
    let mut staged: Vec<(String, String)> = Vec::new(); // (staged filename, source relpath)
    for v in &selected {
        let rel = &v.0;
        let name = rel.replace('/', "__"); // flatten; unique (no path component contains "__")
        symlink(root.join("vectors").join(rel), indir.join(&name)).unwrap();
        staged.push((name, rel.clone()));
    }
    match Command::new(run)
        .arg(impl_path)
        .arg(&indir)
        .arg(&odir)
        .env("SANTA_BOUND", santa_bound_path())
        .env("SANTA_BOUND_STATUS", &status)
        .status()
    {
        Ok(s) if s.success() => {}
        Ok(s) => {
            let ceiling = read_bound(&status).ok().and_then(|b| b.ordinary).as_ref().and_then(ceiling_hit);
            return Err(ceiling.map_or(RunFail::Exit(s.code().unwrap_or(1)), RunFail::Ceiling));
        }
        Err(_) => return Err(RunFail::Exit(1)),
    }
    let bound = read_bound(&status).map_err(RunFail::Bound)?;
    let mut res = BTreeMap::new();
    let mut failures = Vec::new();
    for (name, rel) in staged {
        let ofile = odir.join(&name);
        let act = match bound.bounded.get(&name) {
            Some(r) if r["result"] != "success" => {
                failures.push(rel.clone());
                let vector: Value =
                    serde_json::from_str(&fs::read_to_string(root.join("vectors").join(&rel)).unwrap()).unwrap();
                actuals_for(&ofile, Some(r), &vector)
            }
            r => actuals_for(&ofile, r, &Value::Null),
        };
        res.insert(rel, act);
    }
    Ok((res, failures))
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
    // a bounded file whose process hit its budget (santa-bound); conform writes the actual, never a runner
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
/// error tag), wire "roundtrip <bytes…>" (the bytes grade_wire compares against:
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

/// actuals: {relpath: actuals_obj}. Returns {(tier,version,provenance): counts + red}, slice keys
/// ordered (BTreeMap), entries in vector order — matching conform.py's sorted iteration.
fn tally(actuals: &BTreeMap<String, Value>, claims_cost: bool, root: &Path) -> BTreeMap<(String, String, String), Counts> {
    let mut slices: BTreeMap<(String, String, String), Counts> = BTreeMap::new();
    for (rel, act) in actuals {
        let (tier, version, prov, op) = parse_relpath(rel);
        let c = slices.entry((tier, version, prov)).or_default();
        let vec: Value =
            serde_json::from_str(&fs::read_to_string(root.join("vectors").join(rel)).unwrap()).unwrap();
        // Dispatch on the vector's schema discriminator.
        let schema = vec["schema"].as_str().unwrap_or("");
        let is_wire = schema.starts_with("santa-wire/");
        let is_tx = schema.starts_with("santa-transaction/");
        let is_block = schema.starts_with("santa-block/");
        let is_chain = schema.starts_with("santa-chain/");
        let is_authds = schema.starts_with("santa-authds/");
        let is_nipopow = schema.starts_with("santa-nipopow/");
        for e in vec["entries"].as_array().unwrap() {
            let name = e["name"].as_str().unwrap();
            let actual = act.get(name).cloned().unwrap_or(Value::Null);
            let script = e["script"].as_str();
            let g = if is_wire {
                grade_wire(&actual, e)
            } else if is_tx {
                grade_transaction(&actual, &e["expected"], claims_cost)
            } else if is_block {
                grade_block(&actual, &e["expected"], claims_cost)
            } else if is_chain {
                grade_chain(&actual, e)
            } else if is_authds {
                grade_authds(&actual, e)
            } else if is_nipopow {
                grade_nipopow(&actual, e, &vec["chain"])
            } else {
                grade(&actual, &e["expected"], claims_cost)
            };
            match g["kind"].as_str() {
                Some("exceeded") => {
                    c.exceeded += 1;
                    let b = &actual["budget"];
                    c.red.push(red_detail(&op, name, "exceeded",
                        expected_brief(e, is_wire, is_tx, is_chain),
                        format!("exceeded {} ({} MiB, {} s)", g["limit"].as_str().unwrap_or("?"), b["memory_mb"], b["wall_s"]),
                        None, script));
                }
                Some("panicked") => {
                    c.panicked += 1;
                    c.red.push(red_detail(&op, name, "panicked",
                        expected_brief(e, is_wire, is_tx, is_chain), "panicked".to_string(),
                        Some(actual.get("note").cloned().unwrap_or(Value::Null)), script));
                }
                Some("coverage") => {
                    // not-implemented is the only coverage tag (unrepresentable was removed end-to-end).
                    let tag = g["tag"].as_str().unwrap();
                    c.not_impl += 1;
                    c.red.push(red_detail(&op, name, tag,
                        expected_brief(e, is_wire, is_tx, is_chain), tag.to_string(), None, script));
                }
                Some("reject") => {
                    c.reject_total += 1;
                    if g["verdict"] == "nice" {
                        c.reject_nice += 1;
                    } else {
                        c.reject_coal += 1;
                        c.red.push(red_detail(&op, name, "reject",
                            "errored".to_string(), got_brief(&actual, is_wire, is_tx), None, script));
                    }
                }
                Some("roundtrip") => {
                    c.roundtrip_total += 1;
                    if g["verdict"] == "nice" {
                        c.roundtrip_nice += 1;
                    } else {
                        c.roundtrip_coal += 1;
                        c.red.push(red_detail(&op, name, "roundtrip",
                            expected_brief(e, is_wire, is_tx, is_chain), got_brief(&actual, is_wire, is_tx),
                            actual.get("reason").cloned(), script));
                    }
                }
                Some("transaction") => {
                    // valid dim: always graded; cost dim: only when verdict's cost != "n/a".
                    c.tx_valid_total += 1;
                    if g["valid"] == "nice" {
                        c.tx_valid_nice += 1;
                    } else {
                        c.tx_valid_coal += 1;
                        // Surface the actual's "reason" string as note when present (reject diagnostic).
                        let note = actual.get("reason").filter(|v| !v.is_null()).cloned();
                        c.red.push(red_detail(&op, name, "valid",
                            format!("valid {}", e["expected"]["valid"]),
                            got_brief(&actual, false, true), note, script));
                    }
                    if g["cost"] != "n/a" {
                        c.cost_graded += 1;
                        if g["cost"] == "nice" {
                            c.cost_nice += 1;
                        } else {
                            c.cost_coal += 1;
                            c.red.push(red_detail(&op, name, "cost",
                                format!("cost {}", e["expected"]["cost"]),
                                format!("cost {}", actual["cost"]), None, script));
                        }
                    }
                }
                Some("block") => {
                    // valid dim: always graded.
                    c.block_valid_total += 1;
                    if g["valid"] == "nice" {
                        c.block_valid_nice += 1;
                    } else {
                        c.block_valid_coal += 1;
                        let note = actual.get("reason").filter(|v| !v.is_null()).cloned();
                        // Block actuals carry {valid, cost} (like tx) — render that shape, not
                        // the eval `value @ cost` fallback. error tag renders as itself.
                        let got = match actual["error"].as_str() {
                            Some(err) => err.to_string(),
                            None => format!("valid {} @ cost {}", actual["valid"], actual["cost"]),
                        };
                        c.red.push(red_detail(&op, name, "valid",
                            format!("valid {}", e["expected"]["valid"]), got, note, script));
                    }
                    // post_digest dim: skipped when "n/a" (reject vectors, or valid failed).
                    if g["post_digest"] != "n/a" {
                        c.post_digest_total += 1;
                        if g["post_digest"] == "nice" {
                            c.post_digest_nice += 1;
                        } else {
                            c.post_digest_coal += 1;
                            c.red.push(red_detail(&op, name, "post_digest",
                                format!("post_digest {}", brief(&e["expected"]["post_digest"], 64)),
                                format!("post_digest {}", brief(&actual["post_digest"], 64)),
                                None, script));
                        }
                    }
                    // cost dim: skipped when "n/a"; reuses shared cost counters (same concept as tx/eval).
                    if g["cost"] != "n/a" {
                        c.cost_graded += 1;
                        if g["cost"] == "nice" {
                            c.cost_nice += 1;
                        } else {
                            c.cost_coal += 1;
                            c.red.push(red_detail(&op, name, "cost",
                                format!("cost {}", e["expected"]["cost"]),
                                format!("cost {}", actual["cost"]), None, script));
                        }
                    }
                }
                Some("chain") => {
                    // Single value dimension; cost is not-applicable for chain.
                    c.chain_value_total += 1;
                    if g["value"] == "nice" {
                        c.chain_value_nice += 1;
                    } else {
                        c.chain_value_coal += 1;
                        let note = actual.get("note").filter(|v| !v.is_null()).cloned();
                        let expected = expected_brief(e, false, false, true);
                        let got = match actual["error"].as_str() {
                            Some(err) => err.to_string(),
                            None => match e["kind"].as_str() {
                                Some("retargeting") => format!("nbits {}", actual["nbits"]),
                                Some("voting") => format!("parameters {} @ update {}",
                                    brief(&actual["parameters"]["table"], 96), actual["activated_update"]),
                                _ => brief(&actual, 96),
                            },
                        };
                        c.red.push(red_detail(&op, name, "value",
                            expected, got, note, script));
                    }
                }
                Some("authds_prove") => {
                    c.authds_proof_total += 1;
                    if g["proof"] == "nice" {
                        c.authds_proof_nice += 1;
                    } else {
                        c.authds_proof_coal += 1;
                        c.red.push(red_detail(&op, name, "proof",
                            format!("proofs {}", e["expected"]["proofs"]),
                            format!("proofs {}", actual["proofs"]), None, script));
                    }
                    c.authds_digest_total += 1;
                    if g["digest"] == "nice" {
                        c.authds_digest_nice += 1;
                    } else {
                        c.authds_digest_coal += 1;
                        c.red.push(red_detail(&op, name, "digest",
                            format!("digests {}", e["expected"]["digests"]),
                            format!("digests {}", actual["digests"]), None, script));
                    }
                }
                Some("authds_verify") => {
                    c.authds_accepted_total += 1;
                    if g["accepted"] == "nice" {
                        c.authds_accepted_nice += 1;
                    } else {
                        c.authds_accepted_coal += 1;
                        c.red.push(red_detail(&op, name, "accepted",
                            format!("proof_accepted {}", e["expected"]["proof_accepted"]),
                            format!("proof_accepted {}", actual["proof_accepted"]), None, script));
                    }
                    if g["results"] != "n/a" {
                        c.authds_results_total += 1;
                        if g["results"] == "nice" {
                            c.authds_results_nice += 1;
                        } else {
                            c.authds_results_coal += 1;
                            c.red.push(red_detail(&op, name, "results",
                                format!("results {}", e["expected"]["results"]),
                                format!("results {}", actual["results"]), None, script));
                        }
                    }
                    if g["digest"] != "n/a" {
                        c.authds_digest_total += 1;
                        if g["digest"] == "nice" {
                            c.authds_digest_nice += 1;
                        } else {
                            c.authds_digest_coal += 1;
                            c.red.push(red_detail(&op, name, "digest",
                                format!("new_digest_hex {}", e["expected"]["new_digest_hex"]),
                                format!("new_digest_hex {}", actual["new_digest_hex"]), None, script));
                        }
                    }
                }
                Some("nipopow_interlinks") => {
                    c.nipopow_interlinks_total += 1;
                    if g["interlinks"] == "nice" {
                        c.nipopow_interlinks_nice += 1;
                    } else {
                        c.nipopow_interlinks_coal += 1;
                        c.red.push(red_detail(&op, name, "interlinks",
                            "interlinks match".to_string(),
                            "interlinks diverge".to_string(), None, script));
                    }
                }
                Some("nipopow_prove") => {
                    c.nipopow_proof_total += 1;
                    if g["proof"] == "nice" {
                        c.nipopow_proof_nice += 1;
                    } else {
                        c.nipopow_proof_coal += 1;
                        c.red.push(red_detail(&op, name, "proof",
                            format!("proofHex {}", brief(&e["expected"]["proofHex"], 48)),
                            format!("proofHex {}", brief(actual.get("proofHex").unwrap_or(&Value::Null), 48)),
                            None, script));
                    }
                }
                _ => {
                    c.value_total += 1;
                    if g["value"] == "nice" {
                        c.value_nice += 1;
                    } else {
                        c.value_coal += 1;
                        c.red.push(red_detail(&op, name, "value",
                            expected_brief(e, false, false, false), got_brief(&actual, false, false),
                            None, script));
                    }
                    if g["cost"] != "n/a" {
                        c.cost_graded += 1;
                        if g["cost"] == "nice" {
                            c.cost_nice += 1;
                        } else {
                            c.cost_coal += 1;
                            c.red.push(red_detail(&op, name, "cost",
                                format!("cost {}", e["expected"]["cost"]),
                                format!("cost {}", actual["cost"]), None, script));
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
  no runner named: every runner under runners/; --impl (one runner named) replaces its impl for this run,
  a path without #<ref> being graded in place, uncommitted edits included";

/// conform's command line: `--clean`, the runners to run (none = all), and one runner's impl override.
struct Args {
    clean: bool,
    impl_: Option<String>,
    names: Vec<String>,
}

fn parse_args(args: &[String]) -> Result<Args, String> {
    let mut a = Args { clean: false, impl_: None, names: vec![] };
    let mut it = args.iter();
    while let Some(x) = it.next() {
        match x.as_str() {
            "--clean" => a.clean = true,
            "--impl" => a.impl_ = Some(it.next().ok_or("--impl needs a value")?.clone()),
            o if o.starts_with('-') => return Err(format!("unknown option {o:?}")),
            n => a.names.push(n.to_string()),
        }
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

/// `--impl`: this run grades `spec` instead of the configured impl, under the configured repo name. A plain path
/// must be an existing directory; it is made absolute (it is graded in place, through a link).
fn override_impl(m: &mut Runner, spec: &str) -> Result<(), String> {
    if m.impl_name.is_none() {
        return Err(format!("{} has no impl to override", m.name));
    }
    m.impl_ = Some(if spec.contains('#') {
        spec.to_string()
    } else {
        let dir = fs::canonicalize(spec).map_err(|e| format!("--impl {spec}: {e}"))?;
        if !dir.is_dir() {
            return Err(format!("--impl {spec}: not a directory"));
        }
        dir.to_string_lossy().into_owned()
    });
    Ok(())
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
    let mut runners = select_runners(discover(&root.join("runners")), &args.names).unwrap_or_else(|e| die(e));
    if let Some(spec) = &args.impl_ {
        override_impl(&mut runners[0], spec).unwrap_or_else(|e| die(e));
    }
    if runners.is_empty() {
        println!("no runners under runners/*/ (need runner.json + an executable santa-run or a \"runner\" key)");
        return ExitCode::FAILURE;
    }
    println!("\n=== SANTA conformance · {} runner(s) ===", runners.len());
    let vectors = discover_vectors(&root.join("vectors"));
    let facts = wire_facts(&root.join("vectors"), &vectors).unwrap_or_else(|e| die(e));
    let mut results: Vec<Value> = Vec::new();
    for m in &runners {
        eprintln!("running {} …", m.name);
        match run_one(m, &root, &vectors, &facts) {
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
                let slices = tally(&ran.actuals, sc.cost, &root);
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
                let slices_json: serde_json::Map<String, Value> = slices
                    .iter()
                    .map(|((t, v, p), c)| (format!("{t}/{v}/{p}"), c.to_json()))
                    .collect();
                results.push(result_row(m, Some(sc), json!({
                    "sha": ran.sha,
                    "mark": if agg_red == 0 { "nice" } else { "coal" }, "red_total": agg_red, "slices": slices_json,
                    "bounded_failures": ran.failures,
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
    use super::{discover_dir, discover_vectors, hosted_runner, in_scope, override_impl, parse_args, parse_hosted_manifest, parse_manifest, repo_name, select_runners, Args, parse_relpath, repo_root, resolve_impl, scope_suffix, select, unrun_controls, wire_facts, Host, Runner, Scope, Vec5, WireFacts, WIRE_KINDS};
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
        // A wire entry's expected side is what grade_wire compares against: `errored` for a reject entry,
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

    /// Run tally against a synthetic in-memory transaction vector (avoids disk I/O).
    /// Returns the Counts for the single slice.
    fn tally_tx_inline(
        vec_json: &serde_json::Value,
        actuals_obj: serde_json::Map<String, serde_json::Value>,
    ) -> super::Counts {
        use serde_json::Value;
        // Inline: reproduce the tx arm of the tally loop directly (tally() reads vectors from disk
        // by relpath; avoiding a temp tree here keeps the test self-contained).
        let vec = vec_json;
        let mut c = super::Counts::default();
        for e in vec["entries"].as_array().unwrap() {
            let name = e["name"].as_str().unwrap();
            let actual = actuals_obj.get(name).cloned().unwrap_or(Value::Null);
            let g = santa_check::grade_transaction(&actual, &e["expected"], true);
            match g["kind"].as_str() {
                Some("panicked") => {
                    c.panicked += 1;
                    c.red.push(serde_json::json!({"op": "test", "entry": name, "dim": "panicked",
                        "note": actual.get("note").cloned().unwrap_or(Value::Null)}));
                }
                Some("coverage") => {
                    let tag = g["tag"].as_str().unwrap();
                    c.not_impl += 1;
                    c.red.push(serde_json::json!({"op": "test", "entry": name, "dim": tag}));
                }
                Some("transaction") => {
                    c.tx_valid_total += 1;
                    if g["valid"] == "nice" {
                        c.tx_valid_nice += 1;
                    } else {
                        c.tx_valid_coal += 1;
                        let note = actual.get("reason").filter(|v| !v.is_null()).cloned();
                        let mut detail = serde_json::json!({"op": "test", "entry": name, "dim": "valid"});
                        if let Some(n) = note {
                            detail["note"] = n;
                        }
                        c.red.push(detail);
                    }
                    if g["cost"] != "n/a" {
                        c.cost_graded += 1;
                        if g["cost"] == "nice" {
                            c.cost_nice += 1;
                        } else {
                            c.cost_coal += 1;
                            c.red.push(serde_json::json!({"op": "test", "entry": name, "dim": "cost"}));
                        }
                    }
                }
                _ => unreachable!("unexpected verdict kind in tx tally test"),
            }
        }
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
        let c = &super::tally(&actuals, true, &root)[&key];
        assert_eq!(c.roundtrip_total, entries.len() as u64);
        assert_eq!(c.roundtrip_nice, entries.len() as u64);
        assert_eq!(c.red_total(), 0);

        // Corrupt one reserialization -> exactly one differ (coal), tagged dim "roundtrip".
        act.insert(entries[0]["name"].as_str().unwrap().to_string(), json!({"bytes_hex": "00", "error": null}));
        let mut actuals2 = BTreeMap::new();
        actuals2.insert(rel, Value::Object(act));
        let c2 = &super::tally(&actuals2, true, &root)[&key];
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
        let slices = super::tally(&actuals, false, &tmp);
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
        let slices = super::tally(&actuals, false, &tmp);
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
        let slices = super::tally(&actuals, false, &tmp);
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
        let slices = super::tally(&actuals, true, &root);
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
        let slices = super::tally(&actuals, true, &root);
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
        let slices = super::tally(&actuals, true, &root);
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
        let slices = super::tally(&actuals, true, &root);
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
        let slices = super::tally(&actuals, true, &root);
        let c = slices.values().next().unwrap();
        assert_eq!(c.authds_accepted_nice, 1, "accepted must still be nice");
        assert_eq!(c.authds_results_nice, 1, "results must still be nice");
        assert_eq!(c.authds_digest_coal, 1, "wrong final digest must be coal");
        assert_eq!(c.red_total(), 1);
        fs::remove_dir_all(&root).ok();
    }

    // ── resource bounds (santa-bound) ─────────────────────────────────────────

    static BOUND_TEST_DIR_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

    fn bound_root(tag: &str) -> std::path::PathBuf {
        let n = BOUND_TEST_DIR_SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let d = std::env::temp_dir().join(format!("santa-bound-conform-test-{}-{n}-{tag}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn one_entry_vector() -> serde_json::Value {
        json!({"schema": "santa-eval/v2", "op": "x", "limits": {"memory_mb": 1024, "wall_s": 10},
               "entries": [{"name": "e#0", "expected": {"value": {"kind": "Int", "value": 1}, "cost": 5, "error": null}}]})
    }

    fn record(result: &str, extra: serde_json::Value) -> serde_json::Value {
        let mut r = json!({"file": "x.json", "limits": {"memory_mb": 1024, "wall_s": 10}, "result": result});
        for (k, v) in extra.as_object().unwrap() {
            r[k] = v.clone();
        }
        r
    }

    #[test]
    fn read_bound_requires_a_plan() {
        let d = bound_root("noplan");
        let e = super::read_bound(&d).map(|_| ()).unwrap_err();
        assert!(e.contains("santa-bound"), "{e}");
    }

    #[test]
    fn read_bound_requires_a_record_per_bounded_file() {
        let d = bound_root("norecord");
        fs::create_dir_all(d.join("bounded")).unwrap();
        fs::write(d.join("plan.json"), json!({"ordinary": [], "bounded": {"x.json": {"memory_mb": 1024, "wall_s": 10}}}).to_string()).unwrap();
        let e = super::read_bound(&d).map(|_| ()).unwrap_err();
        assert!(e.contains("x.json"), "{e}");
        fs::write(d.join("bounded").join("x.json"), record("success", json!({})).to_string()).unwrap();
        let b = super::read_bound(&d).unwrap();
        assert_eq!(b.bounded["x.json"]["result"], "success");
        assert!(b.ordinary.is_none());
    }

    #[test]
    fn actuals_for_exceeded_ignores_a_partial_file() {
        let d = bound_root("partial");
        let ofile = d.join("x.json");
        fs::write(&ofile, json!({"e#0": {"value": {"kind": "Int", "value": 1}, "cost": 5, "error": null}}).to_string()).unwrap();
        let r = record("exceeded", json!({"limit": "memory"}));
        assert_eq!(
            super::actuals_for(&ofile, Some(&r), &one_entry_vector()),
            json!({"e#0": {"error": "exceeded", "limit": "memory", "budget": {"memory_mb": 1024, "wall_s": 10}}})
        );
    }

    #[test]
    fn actuals_for_crashed_is_panicked() {
        let d = bound_root("crashed");
        let r = record("crashed", json!({"detail": "exit status 134"}));
        assert_eq!(
            super::actuals_for(&d.join("x.json"), Some(&r), &one_entry_vector()),
            json!({"e#0": {"error": "panicked", "note": "process exit status 134"}})
        );
    }

    #[test]
    fn actuals_for_success_reads_the_file() {
        let d = bound_root("success");
        let ofile = d.join("x.json");
        let a = json!({"e#0": {"value": {"kind": "Int", "value": 1}, "cost": 5, "error": null}});
        fs::write(&ofile, a.to_string()).unwrap();
        let r = record("success", json!({}));
        assert_eq!(super::actuals_for(&ofile, Some(&r), &one_entry_vector()), a);
        assert_eq!(super::actuals_for(&ofile, None, &one_entry_vector()), a);
        assert_eq!(super::actuals_for(&d.join("absent.json"), None, &one_entry_vector()), json!({}));
    }

    #[test]
    fn tally_counts_exceeded() {
        use std::collections::BTreeMap;
        let root = bound_root("tally");
        let dir = root.join("vectors").join("eval").join("v6").join("authored");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("x.json"), one_entry_vector().to_string()).unwrap();
        let mut actuals = BTreeMap::new();
        actuals.insert("eval/v6/authored/x.json".to_string(),
            json!({"e#0": {"error": "exceeded", "limit": "memory", "budget": {"memory_mb": 1024, "wall_s": 10}}}));
        let slices = super::tally(&actuals, true, &root);
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
        assert_eq!(super::fail_message(&super::RunFail::Bound("boom".into())), "boom");
    }
}
