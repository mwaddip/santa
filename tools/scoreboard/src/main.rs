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
                    let red = s.get("red").and_then(|x| x.as_array()).map(|a| a.len()).unwrap_or(0);
                    let title = format!(
                        "value {}/{} \u{b7} cost {}/{} \u{b7} reject {}/{} \u{b7} panicked {}",
                        g("value_nice"), g("value_total"), g("cost_nice"), g("cost_graded"),
                        g("reject_nice"), g("reject_total"), g("panicked")
                    );
                    if red > 0 {
                        body.push_str(&format!("<td class=\"coal\" title=\"{title}\">{coal_icon} {red}</td>"));
                    } else if cost_graded {
                        body.push_str(&format!("<td class=\"nice\" title=\"{title}\">{nice_icon}</td>"));
                    } else {
                        body.push_str(&format!(
                            "<td class=\"partial\" title=\"{title} \u{b7} value-only (cost not graded)\">{nice_icon}</td>"
                        ));
                    }
                }
            }
        }
        body.push_str("</tr>\n");
    }

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
 .src {{ color: #666; font-size: .8rem; font-weight: normal; }}
 .meta {{ color: #666; font-size: .85rem; }}
 .legend {{ margin-top: 1rem; font-size: .9rem; }}
 .legend > div {{ margin: .2rem 0; }}
 .legend .sw {{ display: inline-block; width: .9em; height: .9em; border: 1px solid #ccc; vertical-align: -.1em; margin-right: .45em; }}
 .legend .sw.nice {{ background: #e7f6e7; }}
 .legend .sw.partial {{ background: #fdf0c4; }}
 .legend .sw.coal {{ background: #fde6e6; }}
 .legend .sw.na {{ background: #f7f7f7; }}
 .legend .hint {{ color: #666; margin-top: .5rem; }}
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
<div class="legend">
<div><span class="sw nice"></span><b>green</b> — value + cost pass</div>
<div><span class="sw partial"></span><b>amber</b> — value-only pass (cost not graded)</div>
<div><span class="sw coal"></span><b>red</b> {coal_icon} N — N divergences (the deliverable)</div>
<div><span class="sw na"></span><b>grey</b> — not in scope</div>
<div class="hint">Hover a cell for the value / cost / reject / panicked breakdown.</div>
</div>
<p class="meta">Generated from <code>{escaped_git_ref}</code>.</p>
</body></html>
"#
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
}
