use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use serde_json::Value;

/// Runners that are NOT conformers under test (the JVM oracle/control); shown in the
/// dashboard but never badged.
const CONTROL_RUNNERS: &[&str] = &["rudolph"];

/// Per-version red tally for one runner: version tag ("v5"/"v6") -> total red entries.
/// Slice keys are "<tier>/<version>/<provenance>" (e.g. "eval/v6/authored"); a version is
/// nice iff every slice for it has an empty `red` list.
fn version_reds(runner: &Value) -> BTreeMap<String, u64> {
    let mut m: BTreeMap<String, u64> = BTreeMap::new();
    if let Some(slices) = runner.get("slices").and_then(|s| s.as_object()) {
        for (key, slice) in slices {
            let version = key.split('/').nth(1).unwrap_or("?").to_string();
            let red = slice.get("red").and_then(|r| r.as_array()).map(|a| a.len() as u64).unwrap_or(0);
            *m.entry(version).or_insert(0) += red;
        }
    }
    m
}

/// One shields.io endpoint JSON per conformer (control runners excluded).
fn badges(results: &Value) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let runners = results.get("runners").and_then(|r| r.as_array()).cloned().unwrap_or_default();
    for runner in &runners {
        let name = runner.get("name").and_then(|n| n.as_str()).unwrap_or("?");
        if CONTROL_RUNNERS.contains(&name) {
            continue;
        }
        let reds = version_reds(runner);
        let parts: Vec<String> = reds
            .iter()
            .map(|(ver, &r)| if r == 0 { format!("{ver} \u{2713}") } else { format!("{ver} \u{2717} ({r})") })
            .collect();
        let message = if parts.is_empty() { "no slices".to_string() } else { parts.join(" \u{b7} ") };
        let total: u64 = reds.values().sum();
        let color = if total == 0 { "brightgreen" } else { "red" };
        let badge = serde_json::json!({
            "schemaVersion": 1,
            "label": name,
            "message": message,
            "color": color,
        });
        out.push((name.to_string(), serde_json::to_string_pretty(&badge).unwrap()));
    }
    out
}

/// Escape text interpolated into the HTML dashboard. & must be replaced first.
fn html_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// Human-readable source label for the scoreboard: "owner/repo#branch @ sha9", or "—" for null impl.
fn source_label(runner: &Value) -> String {
    match runner.get("impl").and_then(|v| v.as_str()).filter(|s| !s.is_empty()) {
        None => "\u{2014}".to_string(), // — em dash
        Some(s) => {
            let (url, refspec) = s.split_once('#').unwrap_or((s, ""));
            let repo = url.trim_end_matches('/').trim_end_matches(".git")
                .trim_start_matches("https://github.com/").trim_start_matches("git@github.com:");
            let sha = runner.get("sha").and_then(|v| v.as_str()).unwrap_or("");
            let sha9 = &sha[..sha.len().min(9)];
            if sha9.is_empty() { format!("{repo}#{refspec}") } else { format!("{repo}#{refspec} @ {sha9}") }
        }
    }
}

/// Self-contained HTML dashboard: a slice × runner MATRIX. Rows are slices, columns are runners;
/// each cell is green (🎁 nice) / red (🪨 N, coal with N divergences) / grey (— the runner doesn't
/// cover that slice), so a passing cell stays green even when others in its slice row diverge. The
/// per-dimension breakdown (value/cost/reject/panicked) is in each cell's tooltip.
fn dashboard(results: &Value, git_ref: &str) -> String {
    let nice_icon = "\u{1f381}"; // 🎁
    let coal_icon = "\u{1faa8}"; // 🪨
    let runners = results.get("runners").and_then(|r| r.as_array()).cloned().unwrap_or_default();

    // Matrix rows = every slice key, union across runners, sorted.
    let mut slice_keys: BTreeSet<String> = BTreeSet::new();
    for r in &runners {
        if let Some(s) = r.get("slices").and_then(|s| s.as_object()) {
            for k in s.keys() {
                slice_keys.insert(k.clone());
            }
        }
    }

    // Header row: "slice" + one column per runner (mark · name · source@sha).
    let mut header = String::from("<tr><th>slice</th>");
    for r in &runners {
        let name = r.get("name").and_then(|n| n.as_str()).unwrap_or("?");
        let mark = r.get("mark").and_then(|m| m.as_str()).unwrap_or("?");
        let icon = match mark {
            "nice" => nice_icon,
            "coal" => coal_icon,
            _ => "\u{26a0}", // ⚠ errored (could not build/run)
        };
        let control = if CONTROL_RUNNERS.contains(&name) { " <em>(control)</em>" } else { "" };
        header.push_str(&format!(
            "<th>{icon} {}{control}<br><span class=\"src\">{}</span></th>",
            html_escape(name),
            html_escape(&source_label(r))
        ));
    }
    header.push_str("</tr>");

    // Body: one row per slice; one cell per runner (colour by pass/fail, dims in the tooltip).
    let empty = serde_json::Map::new();
    let mut body = String::new();
    for sk in &slice_keys {
        body.push_str(&format!("<tr><th class=\"slice\">{}</th>", html_escape(sk)));
        for r in &runners {
            // Whether this runner grades cost at all (cost:false = value-only, e.g. upstream sigma-rust
            // with no jit-costing). A value-only PASS is amber, not green: it cleared value but cost was
            // never checked, so it must not look like a full value+cost pass.
            let cost_graded = r.get("cost").and_then(|c| c.as_bool()).unwrap_or(true);
            let slices = r.get("slices").and_then(|s| s.as_object()).unwrap_or(&empty);
            match slices.get(sk) {
                None => body.push_str("<td class=\"na\">\u{2014}</td>"), // — not in this runner's scope
                Some(s) => {
                    let g = |f: &str| s.get(f).and_then(|v| v.as_u64()).unwrap_or(0);
                    let red_arr = s.get("red").and_then(|x| x.as_array()).cloned().unwrap_or_default();
                    let red = red_arr.len();
                    // Wire slices grade a single round-trip verdict (no value/cost/reject) — keyed on
                    // the slice path. Build the matching tooltip; wire is never amber (it has no cost).
                    let is_wire = sk.starts_with("wire/");
                    let is_tx = sk.starts_with("transaction/");
                    let is_block = sk.starts_with("block/");
                    let is_chain = sk.starts_with("chain/");
                    let is_authds = sk.starts_with("authds/");
                    let title = if is_wire {
                        let mut t = format!("roundtrip {}/{}", g("roundtrip_nice"), g("roundtrip_total"));
                        if g("not_impl") > 0 { t.push_str(&format!(" \u{b7} not-impl {}", g("not_impl"))); }
                        if g("panicked") > 0 { t.push_str(&format!(" \u{b7} panicked {}", g("panicked"))); }
                        t
                    } else if is_tx {
                        // Transaction tier: valid + cost (when graded) + optional not-impl/panicked gaps.
                        let mut t = format!("valid {}/{}", g("tx_valid_nice"), g("tx_valid_total"));
                        if g("cost_graded") > 0 {
                            t.push_str(&format!(" \u{b7} cost {}/{}", g("cost_nice"), g("cost_graded")));
                        }
                        if g("not_impl") > 0 { t.push_str(&format!(" \u{b7} not-impl {}", g("not_impl"))); }
                        if g("panicked") > 0 { t.push_str(&format!(" \u{b7} panicked {}", g("panicked"))); }
                        // Append dim-labelled details for genuine divergences (valid/cost) — skip
                        // not-implemented entries since they are already counted in the not-impl N summary.
                        for entry in &red_arr {
                            let dim = entry.get("dim").and_then(|v| v.as_str()).unwrap_or("?");
                            if dim == "not-implemented" { continue; }
                            let name = entry.get("entry").and_then(|v| v.as_str()).unwrap_or("?");
                            t.push_str(&format!(" \u{b7} [{dim}] {}", html_escape(name)));
                            if let Some(note) = entry.get("note").and_then(|v| v.as_str()) {
                                // Truncate long notes at first newline for tooltip brevity.
                                let brief = note.lines().next().unwrap_or(note);
                                t.push_str(&format!(": {}", html_escape(brief)));
                            }
                        }
                        t
                    } else if is_block {
                        // Block tier: valid + optional not-impl/panicked gaps.
                        let mut t = format!("valid {}/{}", g("block_valid_nice"), g("block_valid_total"));
                        if g("not_impl") > 0 { t.push_str(&format!(" \u{b7} not-impl {}", g("not_impl"))); }
                        if g("panicked") > 0 { t.push_str(&format!(" \u{b7} panicked {}", g("panicked"))); }
                        t
                    } else if is_chain {
                        // Chain tier: single value dimension (cost is n/a) + optional not-impl/panicked gaps.
                        let mut t = format!("value {}/{}", g("chain_value_nice"), g("chain_value_total"));
                        if g("not_impl") > 0 { t.push_str(&format!(" \u{b7} not-impl {}", g("not_impl"))); }
                        if g("panicked") > 0 { t.push_str(&format!(" \u{b7} panicked {}", g("panicked"))); }
                        t
                    } else if is_authds {
                        // AuthDS tier (AVL prover + verifier). authds_prove tallies proof + digest
                        // INDEPENDENTLY on every entry (no suppression) — a coal proof next to a nice
                        // digest (a correct digest computed from non-canonical proof bytes) is this
                        // tier's signature divergence and must stay visible, not averaged away.
                        // authds_verify chains accepted -> results -> digest, skipping n/a dims, so
                        // its totals are legitimately smaller. Mirrors block's abridged tooltip: the
                        // digest total (shared by both kinds) is shown once, not duplicated.
                        let mut t = format!("prove {}/{}", g("authds_proof_nice"), g("authds_proof_total"));
                        t.push_str(&format!(" \u{b7} verify {}/{}",
                            g("authds_accepted_nice"), g("authds_accepted_total")));
                        t.push_str(&format!(" \u{b7} digest {}/{}",
                            g("authds_digest_nice"), g("authds_digest_total")));
                        if g("not_impl") > 0 { t.push_str(&format!(" \u{b7} not-impl {}", g("not_impl"))); }
                        if g("panicked") > 0 { t.push_str(&format!(" \u{b7} panicked {}", g("panicked"))); }
                        t
                    } else {
                        format!(
                            "value {}/{} \u{b7} cost {}/{} \u{b7} reject {}/{} \u{b7} panicked {}",
                            g("value_nice"), g("value_total"), g("cost_nice"), g("cost_graded"),
                            g("reject_nice"), g("reject_total"), g("panicked")
                        )
                    };
                    // For tx/block/chain/authds slices: if every red entry is dim="not-implemented" it
                    // is a coverage/roadmap cell (the impl doesn't support this tier yet), not a genuine
                    // divergence. Render with the not-impl count and a distinct style rather than as a
                    // red coal cell.
                    let all_not_impl = (is_tx || is_block || is_chain || is_authds) && red > 0
                        && red_arr.iter().all(|e| e.get("dim").and_then(|v| v.as_str()) == Some("not-implemented"));
                    // AuthDS grades FOUR independent coal counters per slice (proof/digest/accepted/
                    // results) — a divergence in ANY one of them must flip the cell red even when the
                    // shared `red` list under-counts it, so the decision below is widened to also check
                    // the counters directly (not solely the `red` array length every other tier uses).
                    let red = if is_authds {
                        red.max((g("authds_proof_coal") + g("authds_digest_coal")
                            + g("authds_accepted_coal") + g("authds_results_coal")) as usize)
                    } else {
                        red
                    };
                    if all_not_impl {
                        body.push_str(&format!(
                            "<td class=\"coverage\" title=\"{title}\">not-impl {}</td>",
                            g("not_impl")
                        ));
                    } else if red > 0 {
                        body.push_str(&format!("<td class=\"coal\" title=\"{title}\">{coal_icon} {red}</td>"));
                    } else if is_wire || cost_graded {
                        // Wire round-trip pass, or a full value+cost pass (eval/tx on a cost-graded runner) → green.
                        body.push_str(&format!("<td class=\"nice\" title=\"{title}\">{nice_icon}</td>"));
                    } else {
                        // cost:false runner: value-only pass (eval) or valid-only pass (tx) — amber.
                        body.push_str(&format!(
                            "<td class=\"partial\" title=\"{title} \u{b7} value-only (cost not graded)\">{nice_icon}</td>"
                        ));
                    }
                }
            }
        }
        body.push_str("</tr>\n");
    }

    let details = coal_details(results);
    let escaped_git_ref = html_escape(git_ref);
    format!(
        r#"<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>SANTA conformance scoreboard</title>
<style>
 body {{ font: 15px/1.5 system-ui, sans-serif; margin: 2rem; color: #222; }}
 h1 {{ font-size: 1.4rem; }}
 table {{ border-collapse: collapse; margin-top: 1rem; }}
 th, td {{ border: 1px solid #ccc; padding: .35rem .6rem; }}
 thead th {{ background: #f4f4f4; vertical-align: top; text-align: left; }}
 th.slice {{ background: #f4f4f4; font-family: ui-monospace, monospace; text-align: left; }}
 td.nice {{ background: #e7f6e7; text-align: center; }}
 td.partial {{ background: #fdf0c4; text-align: center; }}
 td.coal {{ background: #fde6e6; text-align: center; white-space: nowrap; }}
 td.na {{ background: #f7f7f7; color: #aaa; text-align: center; }}
 td.coverage {{ background: #e8eaf6; color: #444; text-align: center; }}
 .src {{ color: #666; font-size: .8rem; font-weight: normal; }}
 .meta {{ color: #666; font-size: .85rem; }}
 .legend {{ margin-top: 1rem; font-size: .9rem; }}
 .legend > div {{ margin: .2rem 0; }}
 .legend .sw {{ display: inline-block; width: .9em; height: .9em; border: 1px solid #ccc; vertical-align: -.1em; margin-right: .45em; }}
 .legend .sw.nice {{ background: #e7f6e7; }}
 .legend .sw.partial {{ background: #fdf0c4; }}
 .legend .sw.coal {{ background: #fde6e6; }}
 .legend .sw.na {{ background: #f7f7f7; }}
 .legend .sw.coverage {{ background: #e8eaf6; }}
 .legend .hint {{ color: #666; margin-top: .5rem; }}
 h2 {{ font-size: 1.15rem; margin-top: 2rem; }}
 details.coal-details {{ margin: .5rem 0; border: 1px solid #e5c9c9; border-radius: 4px; padding: .35rem .8rem; background: #fffafa; }}
 details.coal-details summary {{ cursor: pointer; }}
 details.coal-details h3.slice-h {{ font-family: ui-monospace, monospace; font-size: .85rem; margin: .6rem 0 .15rem; }}
 details.coal-details table.coal-table {{ border-collapse: collapse; margin: .25rem 0 .75rem; }}
 details.coal-details table.coal-table th, details.coal-details table.coal-table td {{ border: 1px solid #e0caca; padding: .2rem .5rem; font-size: .82rem; text-align: left; vertical-align: top; }}
 details.coal-details table.coal-table th {{ background: #f9eded; font-weight: 600; }}
 details.coal-details table.coal-table code {{ word-break: break-all; }}
 details.coal-details .dim {{ color: #a33; }}
 details.coal-details .note {{ color: #666; }}
</style></head><body>
<h1>SANTA conformance scoreboard</h1>
<p class="meta">Sigma-Anchored Node Test Apparatus — cross-implementation Ergo consensus conformance.</p>
<table>
<thead>
{header}
</thead>
<tbody>
{body}</tbody>
</table>
{details}
<div class="legend">
<div><span class="sw nice"></span><b>green</b> — value + cost pass (wire: round-trip-ok)</div>
<div><span class="sw partial"></span><b>amber</b> — value-only pass (cost not graded)</div>
<div><span class="sw coal"></span><b>red</b> {coal_icon} N — N divergences (the deliverable)</div>
<div><span class="sw na"></span><b>grey</b> — not in scope</div>
<div><span class="sw coverage"></span><b>blue</b> — not-impl (roadmap; no verdict yet)</div>
<div class="hint">Hover a cell for the per-dimension breakdown — eval value / cost / reject, wire round-trip, tx valid / cost, block valid, chain value, or authds prove / verify / digest.</div>
</div>
<p class="meta">Generated from <code>{escaped_git_ref}</code>.</p>
</body></html>
"#
    )
}

/// Per-runner collapsible coal-detail tables, rendered below the matrix. One `<details>`
/// per runner that carries ANY red entries — DEFAULT COLLAPSED (no `open` attribute):
/// the totals stay the page, the instances are opt-in. Inside each block the instances
/// are grouped by slice into a 4-column table: vector/entry (+dim; the entry's script —
/// when conform recorded one — as the hover title) | expected | result | message (the
/// note's first line, same brevity convention as the cell tooltips). The expected/got
/// briefs are pre-rendered by conform at grading time; entries from an older
/// results.json (without them) render empty cells. The count in the summary equals the
/// badge's red tally (not-implemented included — the details are the full red list,
/// coverage/roadmap dims visibly labelled rather than filtered).
fn coal_details(results: &Value) -> String {
    let coal_icon = "\u{1faa8}"; // 🪨
    let runners = results.get("runners").and_then(|r| r.as_array()).cloned().unwrap_or_default();
    let empty = serde_json::Map::new();
    let mut blocks = String::new();
    for r in &runners {
        let name = r.get("name").and_then(|n| n.as_str()).unwrap_or("?");
        let slices = r.get("slices").and_then(|s| s.as_object()).unwrap_or(&empty);
        let mut total = 0usize;
        let mut groups: Vec<(String, Vec<String>)> = Vec::new();
        for (sk, slice) in slices.iter().collect::<BTreeMap<_, _>>() {
            let red_arr = slice.get("red").and_then(|x| x.as_array()).cloned().unwrap_or_default();
            if red_arr.is_empty() {
                continue;
            }
            let mut rows = Vec::new();
            for e in &red_arr {
                let s = |f: &str| e.get(f).and_then(|v| v.as_str()).unwrap_or("");
                let (op, entry, dim) = (s("op"), s("entry"), s("dim"));
                let title = match e.get("script").and_then(|v| v.as_str()) {
                    Some(sc) => format!(" title=\"{}\"", html_escape(sc)),
                    None => String::new(),
                };
                let message = e.get("note").and_then(|v| v.as_str())
                    .map(|n| n.lines().next().unwrap_or(n))
                    .unwrap_or("");
                rows.push(format!(
                    "<tr><td{title}><code>{} / {}</code> <span class=\"dim\">[{}]</span></td>\
<td><code>{}</code></td><td><code>{}</code></td><td class=\"note\">{}</td></tr>",
                    html_escape(if op.is_empty() { "?" } else { op }),
                    html_escape(if entry.is_empty() { "?" } else { entry }),
                    html_escape(if dim.is_empty() { "?" } else { dim }),
                    html_escape(s("expected")),
                    html_escape(s("got")),
                    html_escape(message)
                ));
            }
            total += red_arr.len();
            groups.push((sk.clone(), rows));
        }
        if total == 0 {
            continue; // fully-green (or out-of-scope) libraries get no block at all
        }
        let control = if CONTROL_RUNNERS.contains(&name) { " <em>(control)</em>" } else { "" };
        blocks.push_str(&format!(
            "<details class=\"coal-details\"><summary>{coal_icon} <b>{}</b>{control} \u{2014} {total} coal</summary>\n",
            html_escape(name)
        ));
        for (sk, rows) in groups {
            blocks.push_str(&format!(
                "<h3 class=\"slice-h\">{}</h3>\n<table class=\"coal-table\">\n\
<tr><th>vector / entry</th><th>expected</th><th>result</th><th>message</th></tr>\n{}\n</table>\n",
                html_escape(&sk),
                rows.join("\n")
            ));
        }
        blocks.push_str("</details>\n");
    }
    if blocks.is_empty() {
        return String::new(); // a fully-green grid renders no section at all
    }
    format!(
        "<h2>Coal details</h2>\n<p class=\"meta\">Every red instance behind the matrix counts, \
grouped by library \u{2014} collapsed by default, click a library to expand. Hover a \
vector name for its script.</p>\n{blocks}"
    )
}

/// tools/scoreboard -> repo root (CWD-independent; the mise wrapper runs us from tools/).
/// Mirrors conform::repo_root.
fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn main() {
    let root = repo_root();
    let args: Vec<String> = env::args().skip(1).collect();
    let results_path = args.first().map(PathBuf::from).unwrap_or_else(|| root.join(".santa/results.json"));
    let out_dir = args.get(1).map(PathBuf::from).unwrap_or_else(|| root.join("site"));

    let raw = fs::read_to_string(&results_path)
        .unwrap_or_else(|e| panic!("scoreboard: cannot read {}: {e}", results_path.display()));
    let results: Value = serde_json::from_str(&raw)
        .unwrap_or_else(|e| panic!("scoreboard: invalid JSON in {}: {e}", results_path.display()));

    let git_ref = env::var("SANTA_SCOREBOARD_REF").unwrap_or_else(|_| "(local)".to_string());

    let badges_dir = out_dir.join("badges");
    fs::create_dir_all(&badges_dir).expect("scoreboard: create badges dir");
    let pairs = badges(&results);
    for (name, json) in &pairs {
        let path = badges_dir.join(format!("{name}.json"));
        fs::write(&path, json).unwrap_or_else(|e| panic!("scoreboard: write {}: {e}", path.display()));
    }

    let index = out_dir.join("index.html");
    fs::write(&index, dashboard(&results, &git_ref))
        .unwrap_or_else(|e| panic!("scoreboard: write {}: {e}", index.display()));

    println!("scoreboard: wrote {} badges + {}", pairs.len(), index.display());
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn sample() -> Value {
        json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "rudolph", "mark": "nice", "red_total": 0, "impl": null,
              "slices": { "eval/v5/spec": {"value_nice":1558,"value_total":1558,"cost_nice":1558,"cost_graded":1558,"reject_nice":147,"reject_total":147,"red":[]} } },
            { "name": "dasher", "mark": "nice", "red_total": 0,
              "slices": { "eval/v5/spec": {"value_nice":1558,"value_total":1558,"cost_nice":1558,"cost_graded":1558,"reject_nice":147,"reject_total":147,"red":[]} } },
            { "name": "blitzen-eni", "mark": "coal", "red_total": 3,
              "impl": "https://github.com/mwaddip/sigma-rust.git#ergo-node-integration",
              "sha": "abcdef1234567890abcdef1234567890abcdef12",
              "slices": {
                "eval/v5/spec": {"value_nice":1558,"value_total":1558,"cost_nice":1558,"cost_graded":1558,"reject_nice":147,"reject_total":147,"red":[]},
                "eval/v6/authored": {"value_nice":14,"value_total":14,"cost_nice":3,"cost_graded":14,"reject_total":0,"reject_nice":0,
                  "red":[{"dim":"cost","entry":"a","op":"x"},{"dim":"cost","entry":"b","op":"y"},{"dim":"panicked","entry":"c","op":"z"}]}
              } },
            { "name": "blitzen-develop", "mark": "coal", "red_total": 1, "cost": false,
              "impl": "https://github.com/ergoplatform/sigma-rust.git#develop",
              "sha": "7f927613c5a70000000000000000000000abcdef",
              "slices": {
                "eval/v5/spec": {"value_nice":1548,"value_total":1558,"cost_graded":0,"reject_nice":147,"reject_total":147,
                  "red":[{"dim":"value","entry":"m","op":"n"}]},
                "eval/v6/authored": {"value_nice":14,"value_total":14,"cost_graded":0,"reject_total":0,"reject_nice":0,"red":[]}
              } }
          ]
        })
    }

    #[test]
    fn excludes_control_runner() {
        let names: Vec<String> = badges(&sample()).into_iter().map(|(n, _)| n).collect();
        assert!(!names.contains(&"rudolph".to_string()), "rudolph must not be badged");
        assert!(names.contains(&"dasher".to_string()));
        assert!(names.contains(&"blitzen-eni".to_string()));
    }

    #[test]
    fn nice_conformer_is_green() {
        let (_, j) = badges(&sample()).into_iter().find(|(n, _)| n == "dasher").unwrap();
        let b: Value = serde_json::from_str(&j).unwrap();
        assert_eq!(b["schemaVersion"], 1);
        assert_eq!(b["label"], "dasher");
        assert_eq!(b["color"], "brightgreen");
        assert_eq!(b["message"], "v5 \u{2713}"); // "v5 ✓"
    }

    #[test]
    fn divergent_conformer_shows_per_version_reds() {
        let (_, j) = badges(&sample()).into_iter().find(|(n, _)| n == "blitzen-eni").unwrap();
        let b: Value = serde_json::from_str(&j).unwrap();
        assert_eq!(b["color"], "red");
        // v5 clean, v6 has 3 red (2 cost + 1 panicked); BTreeMap keeps v5 before v6.
        assert_eq!(b["message"], "v5 \u{2713} \u{b7} v6 \u{2717} (3)"); // "v5 ✓ · v6 ✗ (3)"
    }

    #[test]
    fn dashboard_matrix_colours_cells_by_pass() {
        let html = dashboard(&sample(), "abc123");
        // columns = runners (header carries mark · name · source@sha)
        assert!(html.contains("\u{1f381} dasher"));        // 🎁 dasher (nice) column header
        assert!(html.contains("\u{1faa8} blitzen-eni"));   // 🪨 blitzen-eni (coal) column header
        assert!(html.contains("(control)"));               // rudolph annotated
        assert!(html.contains("mwaddip/sigma-rust#ergo-node-integration @ abcdef123")); // source in header
        // rows = slices
        assert!(html.contains("class=\"slice\""));         // slice row headers
        assert!(html.contains("eval/v6/authored"));        // a slice row
        // cells coloured by pass/fail — a passing cell is green even in a row with divergences
        assert!(html.contains("class=\"nice\""));          // green = cost-graded pass
        assert!(html.contains("class=\"partial\""));       // amber = value-only pass (blitzen-develop × v6/authored)
        assert!(html.contains("class=\"coal\""));          // red = blitzen-eni × eval/v6/authored diverges
        assert!(html.contains("class=\"na\""));            // rudolph/dasher don't cover eval/v6/authored
        assert!(html.contains("panicked 0")); // tooltip carries the panicked figure
        assert!(!html.contains("unrepr")); // unrepresentable removed end-to-end
        assert!(html.contains("abc123"));                  // the stamped ref
        assert!(html.contains("\u{2014}"));                // — (na cell / null-impl source)
    }

    // ── Coal-details section (per-library collapsible red lists) ───────────────────────────────

    #[test]
    fn coal_details_one_collapsed_block_per_coal_library() {
        let html = dashboard(&sample(), "abc123");
        // section present, with both coal-bearing libraries
        assert!(html.contains("<h2>Coal details</h2>"));
        assert!(html.contains("<b>blitzen-eni</b> \u{2014} 3 coal"),
            "blitzen-eni block must carry its red total");
        assert!(html.contains("<b>blitzen-develop</b> \u{2014} 1 coal"),
            "blitzen-develop block must carry its red total");
        // exactly the two coal libraries get blocks — green ones none
        assert_eq!(html.matches("<details class=\"coal-details\">").count(), 2,
            "only coal-bearing libraries get a details block");
        assert!(!html.contains("<b>dasher</b> \u{2014}"), "green dasher must have no block");
        // DEFAULT COLLAPSED: plain <details>, never <details open>
        assert!(!html.contains("<details open"), "details must default collapsed");
        // grouped by slice, instances carry op / entry [dim] in the table's name column
        assert!(html.contains("<h3 class=\"slice-h\">eval/v6/authored</h3>"));
        assert!(html.contains("<code>x / a</code> <span class=\"dim\">[cost]</span>"));
        assert!(html.contains("<span class=\"dim\">[panicked]</span>"));
        // the 4-column table shape
        assert!(html.contains("<th>vector / entry</th><th>expected</th><th>result</th><th>message</th>"));
    }

    #[test]
    fn coal_details_renders_columns_note_and_script_title() {
        let html = dashboard(&tx_fixture(), "test");
        // note's first line lands in the message column
        assert!(html.contains("<td class=\"note\">Verifier error: AtLeast bound</td>"),
            "a red entry's note must render (first line) in the message column");
        // expected/got briefs (conform-enriched) land in their columns
        assert!(html.contains("<td><code>valid true</code></td><td><code>valid false @ cost null</code></td>"),
            "expected/got briefs must render as columns");
        // a recorded script becomes the name cell's hover title
        assert!(html.contains("title=\"{ sigmaProp(...) }\""),
            "the entry's script must render as the name cell title");
        // entries without expected/got (older results.json) render empty cells, not a crash
        assert!(html.contains("<td><code></code></td><td><code></code></td>"),
            "absent briefs must render as empty cells");
        // dasher's all-not-impl tx reds still count + list (visibly labelled, not filtered)
        assert!(html.contains("<b>dasher</b> \u{2014} 4 coal"));
        assert!(html.contains("<span class=\"dim\">[not-implemented]</span>"));
    }

    #[test]
    fn coal_details_absent_on_fully_green_grid() {
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "rudolph", "mark": "nice", "red_total": 0, "impl": null,
              "slices": { "eval/v5/spec": {"value_nice":1,"value_total":1,"cost_nice":1,"cost_graded":1,"reject_nice":0,"reject_total":0,"red":[]} } }
          ]
        });
        let html = dashboard(&data, "ref");
        assert!(!html.contains("Coal details"), "a green grid must render no details section");
        assert!(!html.contains("<details class=\"coal-details\">"),
            "no details element on a green grid (the CSS selector alone may remain)");
    }

    #[test]
    fn dashboard_renders_wire_cells_with_roundtrip_verdict() {
        // Wire grades a single round-trip verdict — no value/cost/reject. The cell tooltip shows
        // `roundtrip N/M` (+ gaps), and a clean round-trip is GREEN even for a cost:false runner
        // (wire has no cost dimension, so a wire pass is never amber/value-only).
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "blitzen-develop", "mark": "nice", "red_total": 0, "cost": false,
              "impl": "https://github.com/ergoplatform/sigma-rust.git#develop", "sha": "abc",
              "slices": { "wire/v5/vendored": {"roundtrip_nice": 11, "roundtrip_total": 11, "not_impl": 0, "panicked": 0, "red": []} } },
            { "name": "dasher", "mark": "coal", "red_total": 2,
              "slices": { "wire/v5/vendored": {"roundtrip_nice": 4, "roundtrip_total": 4, "not_impl": 2, "panicked": 0,
                "red": [{"dim":"not-implemented","entry":"a","op":"SigmaBoolean"},{"dim":"not-implemented","entry":"b","op":"SigmaBoolean"}]} } }
          ]
        });
        let html = dashboard(&data, "ref");
        // round-trip verdict in the tooltip, not value/cost
        assert!(html.contains("roundtrip 11/11"), "wire tooltip must show roundtrip N/M");
        // clean round-trip is green even for cost:false — wire has no cost dimension (not amber)
        assert!(html.contains("class=\"nice\" title=\"roundtrip 11/11\""), "clean wire cell must be green, not amber");
        assert!(!html.contains("value-only (cost not graded)"), "wire cells never carry the value-only amber note");
        // a wire slice with gaps shows the round-trip count + the gap
        assert!(html.contains("roundtrip 4/4 \u{b7} not-impl 2"), "wire tooltip must show not-impl gaps");
    }

    // ── Transaction tier cell tests ─────────────────────────────────────────────────────────────

    fn tx_fixture() -> Value {
        json!({
          "schema": "santa-results/v1",
          "runners": [
            // rudolph: control, no tx slice → grey —
            { "name": "rudolph", "mark": "nice", "red_total": 0, "impl": null, "slices": {} },
            // comet: also no tx slice → grey —
            { "name": "comet", "mark": "nice", "red_total": 0, "slices": {} },
            // dasher: all not-implemented → coverage cell, not coal
            { "name": "dasher", "mark": "coal", "red_total": 4,
              "slices": { "transaction/v6/captured": {
                "tx_valid_total": 0, "tx_valid_nice": 0, "tx_valid_coal": 0,
                "cost_graded": 0, "cost_nice": 0, "cost_coal": 0,
                "not_impl": 4, "panicked": 0,
                "red": [
                  {"dim":"not-implemented","entry":"atleast-degenerate-bound-184137","op":"atleast-degenerate-bound-184137"},
                  {"dim":"not-implemented","entry":"bigint-downcast-2666","op":"bigint-downcast-2666"},
                  {"dim":"not-implemented","entry":"deserialize-context-111927","op":"deserialize-context-111927"},
                  {"dim":"not-implemented","entry":"powhit-return-type-28474","op":"powhit-return-type-28474"}
                ]
              }}
            },
            // blitzen-develop: all valid-coal → RED 4
            { "name": "blitzen-develop", "mark": "coal", "red_total": 4, "cost": false,
              "slices": { "transaction/v6/captured": {
                "tx_valid_total": 4, "tx_valid_nice": 0, "tx_valid_coal": 4,
                "cost_graded": 0, "cost_nice": 0, "cost_coal": 0,
                "not_impl": 0, "panicked": 0,
                "red": [
                  {"dim":"valid","entry":"atleast-degenerate-bound-184137","note":"Verifier error: AtLeast bound","op":"atleast-degenerate-bound-184137",
                   "expected":"valid true","got":"valid false @ cost null","script":"{ sigmaProp(...) }"},
                  {"dim":"valid","entry":"bigint-downcast-2666","note":"Verifier error: cannot downcast BigInt","op":"bigint-downcast-2666"},
                  {"dim":"valid","entry":"deserialize-context-111927","note":"Verifier error: context extension var 0","op":"deserialize-context-111927"},
                  {"dim":"valid","entry":"powhit-return-type-28474","note":"Verifier error: invalid condition tpe","op":"powhit-return-type-28474"}
                ]
              }}
            },
            // blitzen-eni: valid 4/4 nice, cost 1/4 (3 coal) → RED 3
            { "name": "blitzen-eni", "mark": "coal", "red_total": 3,
              "slices": { "transaction/v6/captured": {
                "tx_valid_total": 4, "tx_valid_nice": 4, "tx_valid_coal": 0,
                "cost_graded": 4, "cost_nice": 1, "cost_coal": 3,
                "not_impl": 0, "panicked": 0,
                "red": [
                  {"dim":"cost","entry":"bigint-downcast-2666","op":"bigint-downcast-2666"},
                  {"dim":"cost","entry":"deserialize-context-111927","op":"deserialize-context-111927"},
                  {"dim":"cost","entry":"powhit-return-type-28474","op":"powhit-return-type-28474"}
                ]
              }}
            },
            // all-nice: valid 4/4 + cost 4/4 → GREEN
            { "name": "full-nice", "mark": "nice", "red_total": 0,
              "slices": { "transaction/v6/captured": {
                "tx_valid_total": 4, "tx_valid_nice": 4, "tx_valid_coal": 0,
                "cost_graded": 4, "cost_nice": 4, "cost_coal": 0,
                "not_impl": 0, "panicked": 0,
                "red": []
              }}
            },
            // cost-false passing valid: valid 4/4, cost_graded=0 → AMBER (value-only)
            { "name": "cost-false-pass", "mark": "nice", "red_total": 0, "cost": false,
              "slices": { "transaction/v6/captured": {
                "tx_valid_total": 4, "tx_valid_nice": 4, "tx_valid_coal": 0,
                "cost_graded": 0, "cost_nice": 0, "cost_coal": 0,
                "not_impl": 0, "panicked": 0,
                "red": []
              }}
            }
          ]
        })
    }

    #[test]
    fn tx_all_nice_valid_and_cost_is_green() {
        let html = dashboard(&tx_fixture(), "test");
        // full-nice has red=[] and cost_graded runner → green cell
        // tooltip must show valid 4/4 · cost 4/4
        assert!(html.contains("class=\"nice\" title=\"valid 4/4 \u{b7} cost 4/4\""),
            "all-nice tx cell must be green with valid+cost tooltip");
    }

    #[test]
    fn tx_valid_nice_cost_partial_coal_is_red_with_count() {
        let html = dashboard(&tx_fixture(), "test");
        // blitzen-eni: red=3 (all cost entries); valid 4/4 · cost 1/4 in tooltip
        assert!(html.contains("class=\"coal\" title=\"valid 4/4 \u{b7} cost 1/4"),
            "blitzen-eni tx cell must be red with valid+cost breakdown in tooltip");
        // the cell shows the coal icon and count 3
        assert!(html.contains("\u{1faa8} 3"),
            "blitzen-eni tx cell must show coal icon and count 3");
    }

    #[test]
    fn tx_all_valid_coal_is_red_with_count_4() {
        let html = dashboard(&tx_fixture(), "test");
        // blitzen-develop: red=4 (all valid entries); valid 0/4 in tooltip
        assert!(html.contains("class=\"coal\" title=\"valid 0/4"),
            "blitzen-develop tx cell must be red with valid 0/4 in tooltip");
        assert!(html.contains("\u{1faa8} 4"),
            "blitzen-develop tx cell must show coal icon and count 4");
    }

    #[test]
    fn tx_red_tooltip_includes_entry_details_with_note() {
        let html = dashboard(&tx_fixture(), "test");
        // blitzen-develop entries have notes — first line of note should appear in tooltip
        assert!(html.contains("[valid] atleast-degenerate-bound-184137: Verifier error: AtLeast bound"),
            "tx tooltip must include dim+entry+note for red entries");
    }

    #[test]
    fn tx_cost_false_runner_passing_valid_is_amber() {
        let html = dashboard(&tx_fixture(), "test");
        // cost-false-pass: red=0, cost:false runner → amber (value-only)
        // tooltip shows valid 4/4 (no cost since cost_graded=0) + amber suffix
        assert!(html.contains("class=\"partial\" title=\"valid 4/4 \u{b7} value-only (cost not graded)\""),
            "cost:false runner passing tx valid must be amber with value-only note");
    }

    #[test]
    fn tx_all_not_impl_is_coverage_cell_not_coal() {
        let html = dashboard(&tx_fixture(), "test");
        // dasher: all red entries are dim=not-implemented → coverage cell
        assert!(html.contains("class=\"coverage\""),
            "all-not-impl tx slice must render as coverage cell, not coal");
        assert!(html.contains("not-impl 4"),
            "coverage cell must show not-impl count");
        // must NOT appear as a coal cell
        // (dasher has no other coal slices in this fixture)
        // verify the coverage cell has the not-impl tooltip
        assert!(html.contains("class=\"coverage\" title=\"valid 0/0 \u{b7} not-impl 4\""),
            "coverage cell must carry the not-impl tooltip");
    }

    #[test]
    fn tx_no_slice_runners_get_grey_na_cell() {
        let html = dashboard(&tx_fixture(), "test");
        // rudolph and comet have no tx slice → their cells must be na (grey —)
        // The na cell content is just — (em dash), class=na
        // We can't assert per-runner position directly, but at least two na cells exist
        let na_count = html.matches("class=\"na\"").count();
        assert!(na_count >= 2, "rudolph+comet must both get grey na cells for the tx row, found {na_count}");
    }

    // ── Block + Chain tier cell tests ──────────────────────────────────────────────────────────

    #[test]
    fn block_tooltip_shows_valid_counts() {
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "rudolph", "mark": "nice", "red_total": 0, "impl": null,
              "slices": { "block/v6/captured": {
                "block_valid_total": 3, "block_valid_nice": 3, "block_valid_coal": 0,
                "not_impl": 0, "panicked": 0, "red": []
              }}
            }
          ]
        });
        let html = dashboard(&data, "ref");
        // clean block cell must be green with valid N/M tooltip
        assert!(html.contains("class=\"nice\" title=\"valid 3/3\""),
            "clean block cell must be green with valid N/M tooltip");
    }

    #[test]
    fn block_all_not_impl_is_coverage_cell_not_coal() {
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "donner", "mark": "coal", "red_total": 2,
              "slices": { "block/v6/authored": {
                "block_valid_total": 0, "block_valid_nice": 0, "block_valid_coal": 0,
                "not_impl": 2, "panicked": 0,
                "red": [
                  {"dim":"not-implemented","entry":"a","op":"a"},
                  {"dim":"not-implemented","entry":"b","op":"b"}
                ]
              }}
            }
          ]
        });
        let html = dashboard(&data, "ref");
        assert!(html.contains("class=\"coverage\""),
            "all-not-impl block slice must render as coverage cell");
        assert!(html.contains("not-impl 2"),
            "coverage cell must show not-impl count");
    }

    #[test]
    fn chain_tooltip_shows_value_counts() {
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "rudolph", "mark": "nice", "red_total": 0, "impl": null,
              "slices": { "chain/v6/authored": {
                "chain_value_total": 4, "chain_value_nice": 4, "chain_value_coal": 0,
                "not_impl": 0, "panicked": 0, "red": []
              }}
            }
          ]
        });
        let html = dashboard(&data, "ref");
        // clean chain cell must be green with value N/M tooltip (not value_nice / value_total)
        assert!(html.contains("class=\"nice\" title=\"value 4/4\""),
            "clean chain cell must be green with value N/M tooltip");
        // must not fall through to the eval-shaped fallback (which would read value_total=0)
        assert!(!html.contains("value 4/4 \u{b7} cost"),
            "chain tooltip must not include cost dimension");
    }

    #[test]
    fn chain_value_coal_is_red_cell() {
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "some-runner", "mark": "coal", "red_total": 1,
              "slices": { "chain/any/authored": {
                "chain_value_total": 3, "chain_value_nice": 2, "chain_value_coal": 1,
                "not_impl": 0, "panicked": 0,
                "red": [{"dim":"value","entry":"foo","op":"bar"}]
              }}
            }
          ]
        });
        let html = dashboard(&data, "ref");
        assert!(html.contains("class=\"coal\" title=\"value 2/3\""),
            "chain cell with coal must be red with value N/M tooltip");
        assert!(html.contains("\u{1faa8} 1"),
            "chain coal cell must show coal icon and count 1");
    }

    #[test]
    fn chain_all_not_impl_is_coverage_cell_not_coal() {
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "dasher", "mark": "coal", "red_total": 5,
              "slices": { "chain/any/authored": {
                "chain_value_total": 0, "chain_value_nice": 0, "chain_value_coal": 0,
                "not_impl": 5, "panicked": 0,
                "red": [
                  {"dim":"not-implemented","entry":"a","op":"a"},
                  {"dim":"not-implemented","entry":"b","op":"b"},
                  {"dim":"not-implemented","entry":"c","op":"c"},
                  {"dim":"not-implemented","entry":"d","op":"d"},
                  {"dim":"not-implemented","entry":"e","op":"e"}
                ]
              }}
            }
          ]
        });
        let html = dashboard(&data, "ref");
        assert!(html.contains("class=\"coverage\""),
            "all-not-impl chain slice must render as coverage cell, not coal");
        assert!(html.contains("not-impl 5"),
            "coverage cell must show not-impl count");
        assert!(html.contains("class=\"coverage\" title=\"value 0/0 \u{b7} not-impl 5\""),
            "coverage cell must carry the not-impl tooltip");
    }

    #[test]
    fn tx_badge_red_count_includes_valid_coal_and_cost_coal() {
        // blitzen-eni: 3 cost-coal red entries flow into version_reds → v6 badge red=3
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "blitzen-eni", "mark": "coal", "red_total": 3,
              "slices": { "transaction/v6/captured": {
                "tx_valid_total": 4, "tx_valid_nice": 4, "tx_valid_coal": 0,
                "cost_graded": 4, "cost_nice": 1, "cost_coal": 3,
                "not_impl": 0, "panicked": 0,
                "red": [
                  {"dim":"cost","entry":"a","op":"a"},
                  {"dim":"cost","entry":"b","op":"b"},
                  {"dim":"cost","entry":"c","op":"c"}
                ]
              }}
            }
          ]
        });
        let (_, j) = badges(&data).into_iter().find(|(n, _)| n == "blitzen-eni").unwrap();
        let b: Value = serde_json::from_str(&j).unwrap();
        assert_eq!(b["color"], "red");
        assert_eq!(b["message"], "v6 \u{2717} (3)"); // "v6 ✗ (3)"
    }

    // ── AuthDS tier cell tests (AVL prover + verifier) ─────────────────────────────────────────

    #[test]
    fn dashboard_renders_authds_cells() {
        let results = json!({"runners": [
            {"name": "rudolph", "label": "rudolph", "version": "v6", "tiers": ["authds"], "cost": true,
             "slices": {"authds/any/vendored": {
                "authds_proof_total": 10, "authds_proof_nice": 10, "authds_proof_coal": 0,
                "authds_digest_total": 60, "authds_digest_nice": 60, "authds_digest_coal": 0,
                "authds_accepted_total": 50, "authds_accepted_nice": 50, "authds_accepted_coal": 0,
                "authds_results_total": 46, "authds_results_nice": 46, "authds_results_coal": 0,
                "not_impl": 0, "panicked": 0, "red": []}}}]});
        let html = dashboard(&results, "ref");
        assert!(html.contains("prove 10/10"), "authds tooltip must show the prove dim");
        assert!(html.contains("class=\"nice\""), "a clean authds cell must be green");
    }

    #[test]
    fn authds_proof_coal_is_red_cell() {
        let results = json!({"runners": [
            {"name": "dasher", "label": "dasher", "version": "v6", "tiers": ["authds"], "cost": true,
             "slices": {"authds/any/vendored": {
                "authds_proof_total": 10, "authds_proof_nice": 9, "authds_proof_coal": 1,
                "authds_digest_total": 60, "authds_digest_nice": 60, "authds_digest_coal": 0,
                "authds_accepted_total": 50, "authds_accepted_nice": 50, "authds_accepted_coal": 0,
                "authds_results_total": 46, "authds_results_nice": 46, "authds_results_coal": 0,
                "not_impl": 0, "panicked": 0, "red": []}}}]});
        let html = dashboard(&results, "ref");
        assert!(html.contains("class=\"coal\""), "a proof-coal authds cell must be red");
    }

    // The three tests below pin the *other* three coal counters independently, mirroring
    // authds_proof_coal_is_red_cell — each flips exactly one of the four authds coal counters
    // with everything else (including `red: []`, same as the fixtures above) clean, so the coal
    // decision is proven to key on all four counters directly rather than on the shared `red`
    // array length (which these fixtures deliberately leave empty). This is the failure mode the
    // brief calls out: a cell must not go green just because one of the four coal counters was
    // left unchecked.

    #[test]
    fn authds_digest_coal_is_red_cell() {
        let results = json!({"runners": [
            {"name": "dasher", "label": "dasher", "version": "v6", "tiers": ["authds"], "cost": true,
             "slices": {"authds/any/vendored": {
                "authds_proof_total": 10, "authds_proof_nice": 10, "authds_proof_coal": 0,
                "authds_digest_total": 60, "authds_digest_nice": 59, "authds_digest_coal": 1,
                "authds_accepted_total": 50, "authds_accepted_nice": 50, "authds_accepted_coal": 0,
                "authds_results_total": 46, "authds_results_nice": 46, "authds_results_coal": 0,
                "not_impl": 0, "panicked": 0, "red": []}}}]});
        let html = dashboard(&results, "ref");
        assert!(html.contains("class=\"coal\""), "a digest-coal authds cell must be red");
    }

    #[test]
    fn authds_accepted_coal_is_red_cell() {
        let results = json!({"runners": [
            {"name": "dasher", "label": "dasher", "version": "v6", "tiers": ["authds"], "cost": true,
             "slices": {"authds/any/vendored": {
                "authds_proof_total": 10, "authds_proof_nice": 10, "authds_proof_coal": 0,
                "authds_digest_total": 60, "authds_digest_nice": 60, "authds_digest_coal": 0,
                "authds_accepted_total": 50, "authds_accepted_nice": 49, "authds_accepted_coal": 1,
                "authds_results_total": 46, "authds_results_nice": 46, "authds_results_coal": 0,
                "not_impl": 0, "panicked": 0, "red": []}}}]});
        let html = dashboard(&results, "ref");
        assert!(html.contains("class=\"coal\""), "an accepted-coal authds cell must be red");
    }

    #[test]
    fn authds_results_coal_is_red_cell() {
        let results = json!({"runners": [
            {"name": "dasher", "label": "dasher", "version": "v6", "tiers": ["authds"], "cost": true,
             "slices": {"authds/any/vendored": {
                "authds_proof_total": 10, "authds_proof_nice": 10, "authds_proof_coal": 0,
                "authds_digest_total": 60, "authds_digest_nice": 60, "authds_digest_coal": 0,
                "authds_accepted_total": 50, "authds_accepted_nice": 50, "authds_accepted_coal": 0,
                "authds_results_total": 46, "authds_results_nice": 45, "authds_results_coal": 1,
                "not_impl": 0, "panicked": 0, "red": []}}}]});
        let html = dashboard(&results, "ref");
        assert!(html.contains("class=\"coal\""), "a results-coal authds cell must be red");
    }

    #[test]
    fn authds_all_not_impl_is_coverage_cell_not_coal() {
        // Mirrors block_all_not_impl_is_coverage_cell_not_coal / chain's equivalent: a slice
        // where every red entry is dim="not-implemented" is a roadmap/coverage cell, not coal.
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "dasher", "mark": "coal", "red_total": 3,
              "slices": { "authds/any/vendored": {
                "authds_proof_total": 0, "authds_proof_nice": 0, "authds_proof_coal": 0,
                "authds_digest_total": 0, "authds_digest_nice": 0, "authds_digest_coal": 0,
                "authds_accepted_total": 0, "authds_accepted_nice": 0, "authds_accepted_coal": 0,
                "authds_results_total": 0, "authds_results_nice": 0, "authds_results_coal": 0,
                "not_impl": 3, "panicked": 0,
                "red": [
                  {"dim":"not-implemented","entry":"a","op":"a"},
                  {"dim":"not-implemented","entry":"b","op":"b"},
                  {"dim":"not-implemented","entry":"c","op":"c"}
                ]
              }}
            }
          ]
        });
        let html = dashboard(&data, "ref");
        assert!(html.contains("class=\"coverage\""),
            "all-not-impl authds slice must render as coverage cell, not coal");
        assert!(html.contains("not-impl 3"),
            "coverage cell must show not-impl count");
    }

    #[test]
    fn authds_cost_false_runner_clean_slice_is_amber() {
        // AuthDS has no cost dimension of its own (like chain). This pins the deliberate choice
        // to follow chain's precedent (subject to the runner's cost:false flag like every other
        // non-wire tier) rather than wire's explicit exemption ("wire is never amber, it has no
        // cost") — nothing in the brief or the neighbouring arms carves out a chain/authds
        // exemption, so a cost:false runner's clean authds slice renders amber, not green.
        let data = json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "blitzen-develop", "mark": "nice", "red_total": 0, "cost": false,
              "slices": { "authds/any/vendored": {
                "authds_proof_total": 10, "authds_proof_nice": 10, "authds_proof_coal": 0,
                "authds_digest_total": 60, "authds_digest_nice": 60, "authds_digest_coal": 0,
                "authds_accepted_total": 50, "authds_accepted_nice": 50, "authds_accepted_coal": 0,
                "authds_results_total": 46, "authds_results_nice": 46, "authds_results_coal": 0,
                "not_impl": 0, "panicked": 0, "red": []
              }}
            }
          ]
        });
        let html = dashboard(&data, "ref");
        assert!(html.contains("class=\"partial\""),
            "cost:false runner's clean authds slice must be amber, matching chain's precedent");
    }
}
