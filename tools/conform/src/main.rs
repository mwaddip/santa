//! SANTA conformance orchestrator (Rust port of the former tools/conform.py). Presence-as-state
//! over runners/*/: each dir with a valid runner.json + executable santa-run is run against the
//! blessed corpus; the santa-check lib decides nice/coal in-process; a side-by-side table is printed
//! and the structured result written to .santa/results.json. See docs/contract/runner-integration.md.
use santa_check::{grade, grade_authds, grade_block, grade_chain, grade_transaction, grade_wire};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};
use std::{env, fs};

const VERSIONS: [&str; 2] = ["v5", "v6"]; // ordered; cumulative (a runner's version implies all lower)

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

struct Runner {
    name: String,
    label: String,
    version: String,
    tiers: Vec<String>,
    cost: bool,
    impl_: Option<String>,
    run: PathBuf,
}

/// presence-as-state: a runner dir is valid iff it has runner.json + an executable santa-run.
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
        let run = d.join("santa-run");
        let executable = run.is_file()
            && fs::metadata(&run).map(|m| m.permissions().mode() & 0o111 != 0).unwrap_or(false);
        if !executable {
            eprintln!("  skip {}: no executable santa-run", d.file_name().unwrap().to_string_lossy());
            continue;
        }
        let j: Value = serde_json::from_str(&fs::read_to_string(d.join("runner.json")).unwrap())
            .unwrap_or_else(|e| die(format!("bad runner.json in {}: {e}", d.display())));
        out.push(Runner {
            name: j["name"].as_str().unwrap().to_string(),
            label: j["label"].as_str().unwrap().to_string(),
            version: j["version"].as_str().unwrap().to_string(),
            tiers: j["tiers"].as_array().unwrap().iter().map(|t| t.as_str().unwrap().to_string()).collect(),
            cost: j.get("cost").and_then(Value::as_bool).unwrap_or(true),
            impl_: j.get("impl").and_then(Value::as_str).map(String::from),
            run,
        });
    }
    out
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

/// impl = "<url>#<ref>" (ref = branch/tag for latest, a <sha>, or "<branch>@<sha>"). Clone/fetch into
/// cache_dir/<repo-name>, check out the ref; return (cache_dir, resolved HEAD sha) (passed to
/// santa-run as <impl-path>, where the runner finds its dep as ./<repo-name>). impl null -> ("-", None).
fn resolve_impl(impl_: &Option<String>, cache_dir: &Path) -> Result<(String, Option<String>), i32> {
    let spec = match impl_ {
        Some(s) if !s.is_empty() => s,
        _ => return Ok(("-".to_string(), None)),
    };
    let (url, refspec) = spec
        .split_once('#')
        .filter(|(u, r)| !u.is_empty() && !r.is_empty())
        .unwrap_or_else(|| die(format!("bad impl '{spec}': expected <url>#<ref>")));
    let (branch, sha) = match refspec.split_once('@') {
        Some((b, s)) => (b, Some(s)),
        None => (refspec, None),
    };
    let target = sha.unwrap_or(branch); // @sha pins; otherwise the branch/tag/sha
    let name = url.trim_end_matches('/').rsplit('/').next().unwrap().trim_end_matches(".git");
    fs::create_dir_all(cache_dir).ok();
    let dest = cache_dir.join(name);
    let dest_s = dest.to_str().unwrap();
    if !dest.join(".git").is_dir() {
        git(&["clone", "-q", url, dest_s])?;
    }
    git(&["-C", dest_s, "fetch", "-q", "--all", "--tags"])?;
    git(&["-C", dest_s, "checkout", "-q", target])?;
    if sha.is_none() {
        // bare branch -> move to its latest tip (best-effort, like conform.py's check=False)
        let _ = git(&["-C", dest_s, "pull", "-q", "--ff-only"]);
    }
    let resolved_sha = git_out(&["-C", dest_s, "rev-parse", "HEAD"]);
    Ok((cache_dir.to_string_lossy().into_owned(), resolved_sha))
}

/// Filter by (version<=, tiers), stage selected vectors flat as symlinks, run, collect actuals keyed
/// by source relpath. Everything per-runner lives under .santa/<name>/. Err(code) = the runner (or
/// its impl checkout) failed to run — surfaced as ⚠️, not graded.
fn run_one(m: &Runner, root: &Path, vectors: &[Vec5]) -> Result<(BTreeMap<String, Value>, Option<String>), i32> {
    let ws = root.join(".santa").join(&m.name);
    let (impl_path, sha) = resolve_impl(&m.impl_, &ws)?;
    let selected = select(vectors, &m.version, &m.tiers);
    let indir = ws.join("in");
    let _ = fs::remove_dir_all(&indir);
    fs::create_dir_all(&indir).unwrap();
    let odir = ws.join("out");
    let _ = fs::remove_dir_all(&odir);
    fs::create_dir_all(&odir).unwrap();
    let mut staged: Vec<(String, String)> = Vec::new(); // (staged filename, source relpath)
    for v in &selected {
        let rel = &v.0;
        let name = rel.replace('/', "__"); // flatten; unique (no path component contains "__")
        symlink(root.join("vectors").join(rel), indir.join(&name)).unwrap();
        staged.push((name, rel.clone()));
    }
    match Command::new(&m.run)
        .arg(&impl_path)
        .arg(&indir)
        .arg(&odir)
        .status()
    {
        Ok(s) if s.success() => {}
        Ok(s) => return Err(s.code().unwrap_or(1)),
        Err(_) => return Err(1),
    }
    let mut res = BTreeMap::new();
    for (name, rel) in staged {
        let ofile = odir.join(&name);
        // Totality: an absent file becomes {} so every entry grades (act None -> coal), surfacing the
        // breach rather than silently dropping the vector.
        let act = if ofile.is_file() {
            serde_json::from_str(&fs::read_to_string(&ofile).unwrap()).unwrap()
        } else {
            json!({})
        };
        res.insert(rel, act);
    }
    Ok((res, sha))
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
    red: Vec<Value>,
}

impl Counts {
    fn red_total(&self) -> u64 {
        self.value_coal + self.not_impl + self.cost_coal + self.reject_coal + self.panicked + self.roundtrip_coal + self.tx_valid_coal + self.block_valid_coal + self.post_digest_coal + self.chain_value_coal
        + self.authds_proof_coal + self.authds_digest_coal
        + self.authds_accepted_coal + self.authds_results_coal
    }
    fn to_json(&self) -> Value {
        json!({
            "value_total": self.value_total, "value_nice": self.value_nice, "value_coal": self.value_coal,
            "not_impl": self.not_impl,
            "cost_graded": self.cost_graded, "cost_nice": self.cost_nice, "cost_coal": self.cost_coal,
            "reject_total": self.reject_total, "reject_nice": self.reject_nice, "reject_coal": self.reject_coal,
            "panicked": self.panicked,
            "roundtrip_total": self.roundtrip_total, "roundtrip_nice": self.roundtrip_nice, "roundtrip_coal": self.roundtrip_coal,
            "tx_valid_total": self.tx_valid_total, "tx_valid_nice": self.tx_valid_nice, "tx_valid_coal": self.tx_valid_coal,
            "block_valid_total": self.block_valid_total, "block_valid_nice": self.block_valid_nice, "block_valid_coal": self.block_valid_coal,
            "post_digest_total": self.post_digest_total, "post_digest_nice": self.post_digest_nice, "post_digest_coal": self.post_digest_coal,
            "chain_value_total": self.chain_value_total, "chain_value_nice": self.chain_value_nice, "chain_value_coal": self.chain_value_coal,
            "authds_proof_total": self.authds_proof_total, "authds_proof_nice": self.authds_proof_nice, "authds_proof_coal": self.authds_proof_coal,
            "authds_digest_total": self.authds_digest_total, "authds_digest_nice": self.authds_digest_nice, "authds_digest_coal": self.authds_digest_coal,
            "authds_accepted_total": self.authds_accepted_total, "authds_accepted_nice": self.authds_accepted_nice, "authds_accepted_coal": self.authds_accepted_coal,
            "authds_results_total": self.authds_results_total, "authds_results_nice": self.authds_results_nice, "authds_results_coal": self.authds_results_coal,
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
/// error tag), wire "roundtrip <bytes…>", tx "valid <v> @ cost <c>",
/// chain per-kind phrase (nbits / parameters+update).
fn expected_brief(e: &Value, is_wire: bool, is_tx: bool, is_chain: bool) -> String {
    let exp = &e["expected"];
    if is_wire {
        format!("roundtrip {}", brief(&e["bytes_hex"], 48))
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
        for e in vec["entries"].as_array().unwrap() {
            let name = e["name"].as_str().unwrap();
            let actual = act.get(name).cloned().unwrap_or(Value::Null);
            let script = e["script"].as_str();
            let g = if is_wire {
                grade_wire(&actual, e)
            } else if is_tx {
                grade_transaction(&actual, &e["expected"])
            } else if is_block {
                grade_block(&actual, &e["expected"])
            } else if is_chain {
                grade_chain(&actual, e)
            } else if is_authds {
                grade_authds(&actual, e)
            } else {
                grade(&actual, &e["expected"], claims_cost)
            };
            match g["kind"].as_str() {
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

fn main() -> ExitCode {
    let root = repo_root();
    if env::args().any(|a| a == "--clean") {
        let _ = fs::remove_dir_all(root.join(".santa"));
    }
    let runners = discover(&root.join("runners"));
    if runners.is_empty() {
        println!("no runners under runners/*/ (need runner.json + executable santa-run)");
        return ExitCode::FAILURE;
    }
    println!("\n=== SANTA conformance · {} runner(s) ===", runners.len());
    let vectors = discover_vectors(&root.join("vectors"));
    let mut results: Vec<Value> = Vec::new();
    for m in &runners {
        eprintln!("running {} …", m.name);
        match run_one(m, &root, &vectors) {
            Err(code) => {
                println!("  ⚠️  {}  — santa-run exited {code}: could not build/run (see error above)", m.label);
                results.push(json!({
                    "name": m.name, "label": m.label, "version": m.version, "tiers": m.tiers, "cost": m.cost,
                    "impl": &m.impl_, "sha": Value::Null,
                    "mark": "errored", "red_total": Value::Null, "slices": {},
                    "error": format!("santa-run exited {code}: could not build/run"),
                }));
            }
            Ok((actuals, sha)) => {
                let slices = tally(&actuals, m.cost, &root);
                let agg_red: u64 = slices.values().map(Counts::red_total).sum();
                let mark = if agg_red == 0 { "🎁" } else { "🪨" };
                println!(
                    "  {mark} {}  (version≤{}, tiers={}, cost={})",
                    m.label,
                    m.version,
                    m.tiers.join(","),
                    if m.cost { "True" } else { "False" }
                );
                for ((tier, version, prov), c) in &slices {
                    println!("      {tier}/{version}/{prov}: {}", c.summary());
                }
                let slices_json: serde_json::Map<String, Value> = slices
                    .iter()
                    .map(|((t, v, p), c)| (format!("{t}/{v}/{p}"), c.to_json()))
                    .collect();
                results.push(json!({
                    "name": m.name, "label": m.label, "version": m.version, "tiers": m.tiers, "cost": m.cost,
                    "impl": &m.impl_, "sha": sha,
                    "mark": if agg_red == 0 { "nice" } else { "coal" }, "red_total": agg_red, "slices": slices_json,
                }));
            }
        }
    }
    write_results(&root, &results);
    eprintln!("\nresults → .santa/results.json");
    ExitCode::SUCCESS
}

#[cfg(test)]
mod tests {
    use super::{parse_relpath, resolve_impl, select, Vec5};
    use std::fs;
    use std::path::PathBuf;
    use std::process::Command;

    fn sample() -> Vec<Vec5> {
        ["eval/v5/spec/a.json", "eval/v6/spec/b.json", "eval/v5/authored/c.json", "wire/v5/spec/d.json"]
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
            ["eval/v5/authored/c.json", "eval/v5/spec/a.json", "eval/v6/spec/b.json"]
        );
    }

    #[test]
    fn select_v5_eval_excludes_v6_and_other_tiers() {
        assert_eq!(
            selected_rels("v5", &["eval"]),
            ["eval/v5/authored/c.json", "eval/v5/spec/a.json"]
        );
    }

    #[test]
    fn select_auto_includes_authored_provenance() {
        assert!(selected_rels("v5", &["eval"]).iter().any(|r| r.contains("/authored/")));
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
        let (impl_path, sha_out) = resolve_impl(&Some(format!("{rs}#main")), &cache).unwrap();
        assert_eq!(impl_path, cache.to_string_lossy());
        let checkout = cache.join("remote");
        assert!(checkout.join(".git").is_dir());
        assert_eq!(fs::read_to_string(checkout.join("VERSION")).unwrap().trim(), "v2");
        assert!(sha_out.as_ref().is_some_and(|s| s.len() == 40));

        // 2) @<sha> pin -> that commit (v1), not the tip.
        let cache2 = tmp.join("cache2");
        let (_, sha_pin) = resolve_impl(&Some(format!("{rs}#main@{sha1}")), &cache2).unwrap();
        assert_eq!(fs::read_to_string(cache2.join("remote").join("VERSION")).unwrap().trim(), "v1");
        assert_eq!(sha_pin, Some(sha1.clone()));

        // 3) null impl -> ("-", None).
        assert_eq!(resolve_impl(&None, &cache).unwrap(), ("-".to_string(), None));

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
            let g = santa_check::grade_transaction(&actual, &e["expected"]);
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
}
