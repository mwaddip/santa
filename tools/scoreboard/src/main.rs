use std::collections::BTreeMap;
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

/// Self-contained HTML dashboard: one row per (runner, slice) with per-dimension tallies.
fn dashboard(results: &Value, git_ref: &str) -> String {
    let mut rows = String::new();
    let runners = results.get("runners").and_then(|r| r.as_array()).cloned().unwrap_or_default();
    let empty = serde_json::Map::new();
    for runner in &runners {
        let name = runner.get("name").and_then(|n| n.as_str()).unwrap_or("?");
        let mark = runner.get("mark").and_then(|m| m.as_str()).unwrap_or("?");
        let icon = if mark == "nice" { "\u{1f381}" } else { "\u{1faa8}" }; // 🎁 / 🪨
        let control = if CONTROL_RUNNERS.contains(&name) { " <em>(control)</em>" } else { "" };
        let slices = runner.get("slices").and_then(|s| s.as_object()).unwrap_or(&empty);
        let mut keys: Vec<&String> = slices.keys().collect();
        keys.sort();
        for key in keys {
            let s = &slices[key];
            let g = |f: &str| s.get(f).and_then(|v| v.as_u64()).unwrap_or(0);
            let red = s.get("red").and_then(|r| r.as_array()).map(|a| a.len()).unwrap_or(0);
            let cls = if red > 0 { " class=\"red\"" } else { "" };
            rows.push_str(&format!(
                "<tr{cls}><td>{icon} {name}{control}</td><td>{key}</td><td>{}/{}</td>\
                 <td>{}/{}</td><td>{}/{}</td><td>{}</td><td>{}</td></tr>\n",
                g("value_nice"), g("value_total"),
                g("cost_nice"), g("cost_graded"),
                g("reject_nice"), g("reject_total"),
                g("unrepr"), red
            ));
        }
    }
    let nice_icon = "\u{1f381}"; // 🎁
    let coal_icon = "\u{1faa8}"; // 🪨
    format!(
        r#"<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>SANTA conformance scoreboard</title>
<style>
 body {{ font: 15px/1.5 system-ui, sans-serif; margin: 2rem; color: #222; }}
 h1 {{ font-size: 1.4rem; }}
 table {{ border-collapse: collapse; margin-top: 1rem; }}
 th, td {{ border: 1px solid #ccc; padding: .35rem .6rem; text-align: left; }}
 th {{ background: #f4f4f4; }}
 tr.red td {{ background: #fff0f0; }}
 .meta {{ color: #666; font-size: .85rem; }}
</style></head><body>
<h1>SANTA conformance scoreboard</h1>
<p class="meta">Sigma-Anchored Node Test Apparatus — cross-implementation Ergo consensus conformance.
{nice_icon} all-nice · {coal_icon} divergences found (the deliverable). Generated from <code>{git_ref}</code>.</p>
<table>
<thead><tr><th>runner</th><th>slice</th><th>value</th><th>cost</th><th>reject</th><th>unrepr</th><th>red</th></tr></thead>
<tbody>
{rows}</tbody>
</table>
</body></html>
"#
    )
}

fn main() {
    println!("scoreboard: not yet implemented");
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn sample() -> Value {
        json!({
          "schema": "santa-results/v1",
          "runners": [
            { "name": "rudolph", "mark": "nice", "red_total": 0,
              "slices": { "eval/v5/spec": {"value_nice":1558,"value_total":1558,"cost_nice":1558,"cost_graded":1558,"reject_nice":147,"reject_total":147,"unrepr":0,"red":[]} } },
            { "name": "dasher", "mark": "nice", "red_total": 0,
              "slices": { "eval/v5/spec": {"value_nice":1558,"value_total":1558,"cost_nice":1558,"cost_graded":1558,"reject_nice":147,"reject_total":147,"unrepr":0,"red":[]} } },
            { "name": "blitzen-eni", "mark": "coal", "red_total": 3,
              "slices": {
                "eval/v5/spec": {"value_nice":1558,"value_total":1558,"cost_nice":1558,"cost_graded":1558,"reject_nice":147,"reject_total":147,"unrepr":0,"red":[]},
                "eval/v6/authored": {"value_nice":14,"value_total":14,"cost_nice":3,"cost_graded":14,"unrepr":3,"reject_total":0,"reject_nice":0,
                  "red":[{"dim":"cost","entry":"a","op":"x"},{"dim":"cost","entry":"b","op":"y"},{"dim":"unrepresentable","entry":"c","op":"z"}]}
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
        // v5 clean, v6 has 3 red (2 cost + 1 unrepr); BTreeMap keeps v5 before v6.
        assert_eq!(b["message"], "v5 \u{2713} \u{b7} v6 \u{2717} (3)"); // "v5 ✓ · v6 ✗ (3)"
    }

    #[test]
    fn dashboard_lists_runners_and_flags_red_rows() {
        let html = dashboard(&sample(), "abc123");
        assert!(html.contains("\u{1f381} dasher"));        // 🎁 dasher (nice)
        assert!(html.contains("\u{1faa8} blitzen-eni"));   // 🪨 blitzen-eni (coal)
        assert!(html.contains("(control)"));               // rudolph annotated
        assert!(html.contains("class=\"red\""));           // the v6/authored divergent row
        assert!(html.contains("abc123"));                  // the stamped ref
        assert!(html.contains("eval/v6/authored"));
    }
}
