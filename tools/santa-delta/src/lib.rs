//! SANTA's PR delta (the PR check's step 2): two conform `results.json` files, a pull request's base and head graded
//! on ONE SANTA checkout, compared entry by entry. An entry is `(slice, op, entry)`; its red dimensions at each side
//! (`value` / `cost` / `reject` / `not-implemented` / `panicked` / …, each with its expected and got) put it in one of
//! four classes: newly red, now green, red another way, or unchanged. The comment groups them by vector file; the job
//! summary and the JSON carry every entry.
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

/// An entry's red dimensions on one side: dim -> (expected, got). Empty = not red there.
pub type Dims = BTreeMap<String, (Value, Value)>;

/// One entry whose red dimensions differ between base and head.
#[derive(Debug, Clone, PartialEq)]
pub struct Change {
    pub slice: String,
    pub op: String,
    pub entry: String,
    pub base: Dims,
    pub head: Dims,
    pub script: Option<String>,
}

/// A slice graded on one side only (the runner's scope moved), with its red entries there.
#[derive(Debug, Clone, PartialEq)]
pub struct SliceCount {
    pub slice: String,
    pub red_entries: usize,
}

/// What a runner's two sides compare to.
#[derive(Debug, Clone, PartialEq)]
pub struct Compared {
    pub newly_red: Vec<Change>,
    pub now_green: Vec<Change>,
    pub changed: Vec<Change>,
    pub unchanged_red: usize,
    pub newly_graded: Vec<SliceCount>,
    pub no_longer_graded: Vec<SliceCount>,
    /// (base, head) when the declared scope differs.
    pub scope_changed: Option<(String, String)>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    Compared(Compared),
    /// Why there is no delta: a side that could not build/run, or a runner on one side only.
    NotCompared(String),
}

#[derive(Debug, Clone, PartialEq)]
pub struct RunnerDelta {
    pub name: String,
    pub label: String,
    pub base_sha: Option<String>,
    pub head_sha: Option<String>,
    pub outcome: Outcome,
}

/// One row of a section: a vector file, its entry count, and the split (by dimension, or by "base → head" for red
/// another way), largest first.
#[derive(Debug, Clone, PartialEq)]
pub struct Row {
    pub slice: String,
    pub op: String,
    pub entries: usize,
    pub split: Vec<(String, usize)>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Section {
    NewlyRed,
    NowGreen,
    Changed,
}

/// The comment lists at most this many files per section; the job summary lists them all.
pub const COMMENT_ROWS: usize = 50;

/// Every runner in either file, by name: its delta, or why there is none.
pub fn delta(base: &Value, head: &Value) -> Vec<RunnerDelta> {
    let (b, h) = (by_name(base), by_name(head));
    let names: BTreeSet<&String> = b.keys().chain(h.keys()).collect();
    names.into_iter().map(|n| runner_delta(n, b.get(n).copied(), h.get(n).copied())).collect()
}

fn by_name(results: &Value) -> BTreeMap<String, &Value> {
    results["runners"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|r| Some((r["name"].as_str()?.to_string(), r)))
        .collect()
}

fn runner_delta(name: &str, b: Option<&Value>, h: Option<&Value>) -> RunnerDelta {
    let sha = |r: Option<&Value>| r.and_then(|r| r["sha"].as_str()).map(String::from);
    let errored = |r: &Value| {
        (r["mark"] == "errored").then(|| r["error"].as_str().unwrap_or("could not build/run").to_string())
    };
    let outcome = match (b, h) {
        (None, _) => Outcome::NotCompared("not graded at base (the runner is new at head)".into()),
        (_, None) => Outcome::NotCompared("not graded at head".into()),
        (Some(b), Some(h)) => match (errored(b), errored(h)) {
            (Some(eb), Some(eh)) => Outcome::NotCompared(format!("neither side could run: base: {eb}; head: {eh}")),
            (Some(e), None) => Outcome::NotCompared(format!("base could not run: {e}")),
            (None, Some(e)) => Outcome::NotCompared(format!("head could not run: {e}")),
            (None, None) => Outcome::Compared(compare(b, h)),
        },
    };
    RunnerDelta {
        name: name.to_string(),
        label: h.or(b).and_then(|r| r["label"].as_str()).unwrap_or(name).to_string(),
        base_sha: sha(b),
        head_sha: sha(h),
        outcome,
    }
}

/// A side's red entries: slice -> (op, entry) -> (its red dims, its script).
type Reds = BTreeMap<String, BTreeMap<(String, String), (Dims, Option<String>)>>;

fn reds(r: &Value) -> Reds {
    let mut out = Reds::new();
    for (slice, v) in r["slices"].as_object().into_iter().flatten() {
        let entries = out.entry(slice.clone()).or_default();
        for red in v["red"].as_array().into_iter().flatten() {
            let s = |k: &str| red[k].as_str().unwrap_or("").to_string();
            let (dims, script) = entries.entry((s("op"), s("entry"))).or_default();
            dims.insert(s("dim"), (red["expected"].clone(), red["got"].clone()));
            if script.is_none() {
                *script = red["script"].as_str().map(String::from);
            }
        }
    }
    out
}

/// The declared scope as conform's header line shows it.
fn scope_text(r: &Value) -> String {
    let list = |k: &str| {
        r[k].as_array().map(|a| a.iter().filter_map(Value::as_str).collect::<Vec<_>>().join(","))
    };
    let mut s = format!(
        "version≤{}, tiers={}, cost={}",
        r["version"].as_str().unwrap_or("?"),
        list("tiers").unwrap_or_default(),
        r["cost"]
    );
    if let Some(ks) = list("kinds") {
        s.push_str(&format!(", kinds={ks}"));
    }
    if r["activation"] == false {
        s.push_str(", activation=false");
    }
    s
}

fn compare(b: &Value, h: &Value) -> Compared {
    let (bs, hs) = (reds(b), reds(h));
    let mut c = Compared {
        newly_red: vec![],
        now_green: vec![],
        changed: vec![],
        unchanged_red: 0,
        newly_graded: vec![],
        no_longer_graded: vec![],
        scope_changed: None,
    };
    let slices: BTreeSet<&String> = bs.keys().chain(hs.keys()).collect();
    for slice in slices {
        match (bs.get(slice), hs.get(slice)) {
            (Some(x), Some(y)) => {
                let keys: BTreeSet<&(String, String)> = x.keys().chain(y.keys()).collect();
                let none = (Dims::new(), None);
                for k in keys {
                    let (bd, bscript) = x.get(k).unwrap_or(&none);
                    let (hd, hscript) = y.get(k).unwrap_or(&none);
                    let change = Change {
                        slice: slice.clone(),
                        op: k.0.clone(),
                        entry: k.1.clone(),
                        base: bd.clone(),
                        head: hd.clone(),
                        script: hscript.clone().or_else(|| bscript.clone()),
                    };
                    match (bd.is_empty(), hd.is_empty()) {
                        (true, false) => c.newly_red.push(change),
                        (false, true) => c.now_green.push(change),
                        _ if bd != hd => c.changed.push(change),
                        _ => c.unchanged_red += 1,
                    }
                }
            }
            (None, Some(y)) => c.newly_graded.push(SliceCount { slice: slice.clone(), red_entries: y.len() }),
            (Some(x), None) => c.no_longer_graded.push(SliceCount { slice: slice.clone(), red_entries: x.len() }),
            (None, None) => unreachable!(),
        }
    }
    let (sb, sh) = (scope_text(b), scope_text(h));
    if sb != sh {
        c.scope_changed = Some((sb, sh));
    }
    c
}

fn join_dims(d: &Dims) -> String {
    d.keys().cloned().collect::<Vec<_>>().join("+")
}

/// A section's changes, one row per vector file: the largest file first, its split largest first.
pub fn rows(changes: &[Change], section: Section) -> Vec<Row> {
    // (slice, op) -> (entries, split key -> count)
    type Tally<'a> = BTreeMap<(&'a str, &'a str), (usize, BTreeMap<String, usize>)>;
    let mut by = Tally::new();
    for c in changes {
        let (n, split) = by.entry((&c.slice, &c.op)).or_default();
        *n += 1;
        let keys: Vec<String> = match section {
            Section::NewlyRed => c.head.keys().cloned().collect(),
            Section::NowGreen => c.base.keys().cloned().collect(),
            Section::Changed => vec![format!("{} → {}", join_dims(&c.base), join_dims(&c.head))],
        };
        for k in keys {
            *split.entry(k).or_default() += 1;
        }
    }
    let mut rows: Vec<Row> = by
        .into_iter()
        .map(|((slice, op), (entries, split))| {
            let mut split: Vec<(String, usize)> = split.into_iter().collect();
            split.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
            Row { slice: slice.to_string(), op: op.to_string(), entries, split }
        })
        .collect();
    rows.sort_by(|a, b| b.entries.cmp(&a.entries).then_with(|| (&a.slice, &a.op).cmp(&(&b.slice, &b.op))));
    rows
}

/// Table-safe and HTML-safe text: GitHub renders the markdown, so `|` would split a cell and `<` start a tag.
fn esc(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('|', "&#124;")
        .replace(['\n', '\r'], " ")
}

fn code(s: &str) -> String {
    format!("<code>{}</code>", esc(s))
}

fn brief(v: &Value) -> String {
    v.as_str().map_or_else(|| v.to_string(), String::from)
}

fn plural(n: usize, one: &str, many: &str) -> String {
    format!("{n} {}", if n == 1 { one } else { many })
}

fn short(sha: &Option<String>) -> String {
    sha.as_deref().map_or("—".to_string(), |s| format!("`{}`", &s[..s.len().min(7)]))
}

fn section_of(c: &Compared, s: Section) -> &[Change] {
    match s {
        Section::NewlyRed => &c.newly_red,
        Section::NowGreen => &c.now_green,
        Section::Changed => &c.changed,
    }
}

fn heading(c: &Compared, s: Section) -> String {
    let n = section_of(c, s).len();
    let files = rows(section_of(c, s), s).len();
    let name = match s {
        Section::NewlyRed => "🪨 Newly red",
        Section::NowGreen => "🎁 Now green",
        Section::Changed => "↔ Red another way",
    };
    format!("{name}: {} in {}", plural(n, "entry", "entries"), plural(files, "file", "files"))
}

/// A section's file table, at most `cap` rows (the rest summed in one line).
fn table(changes: &[Change], s: Section, cap: usize) -> String {
    let rs = rows(changes, s);
    let col = if s == Section::Changed { "change" } else { "dimensions" };
    let mut t = format!("| slice | file | entries | {col} |\n|---|---|---:|---|\n");
    for r in rs.iter().take(cap) {
        let split: Vec<String> = r.split.iter().map(|(k, n)| format!("{} {n}", esc(k))).collect();
        t.push_str(&format!("| {} | {} | {} | {} |\n", code(&r.slice), code(&r.op), r.entries, split.join(" · ")));
    }
    if rs.len() > cap {
        let rest = &rs[cap..];
        let n: usize = rest.iter().map(|r| r.entries).sum();
        let files = if rest.len() == 1 { "file" } else { "files" };
        t.push_str(&format!(
            "\n_… {} more {files} ({}): see the job summary._\n",
            rest.len(),
            plural(n, "entry", "entries")
        ));
    }
    t
}

fn slice_table(title: &str, xs: &[SliceCount]) -> String {
    let mut t = format!("### {title}\n\n| slice | red entries |\n|---|---:|\n");
    for x in xs {
        t.push_str(&format!("| {} | {} |\n", code(&x.slice), x.red_entries));
    }
    t + "\n"
}

/// A runner's comment block (or the summary's head of it, uncapped).
fn block(d: &RunnerDelta, cap: usize) -> String {
    let mut md = format!(
        "## 🎅 SANTA · {}\n\nbase {} → head {}\n\n",
        esc(&d.label),
        short(&d.base_sha),
        short(&d.head_sha)
    );
    let c = match &d.outcome {
        Outcome::NotCompared(why) => return md + &format!("⚠️ No delta: {}\n\n", esc(why)),
        Outcome::Compared(c) => c,
    };
    if let Some((b, h)) = &c.scope_changed {
        md.push_str(&format!("**Scope changed:** base {} → head {}\n\n", code(b), code(h)));
    }
    let moved = !(c.newly_red.is_empty() && c.now_green.is_empty() && c.changed.is_empty());
    if !moved && c.newly_graded.is_empty() && c.no_longer_graded.is_empty() {
        return md + &format!("No change: {} still red, as at base.\n\n", plural(c.unchanged_red, "entry", "entries"));
    }
    md.push_str(&format!(
        "| 🪨 newly red | 🎁 now green | ↔ red another way | still red |\n|---:|---:|---:|---:|\n| {} | {} | {} | {} |\n\n",
        c.newly_red.len(),
        c.now_green.len(),
        c.changed.len(),
        c.unchanged_red
    ));
    for s in [Section::NewlyRed, Section::NowGreen] {
        if !section_of(c, s).is_empty() {
            md.push_str(&format!("### {}\n\n{}\n", heading(c, s), table(section_of(c, s), s, cap)));
        }
    }
    if !c.newly_graded.is_empty() {
        md.push_str(&slice_table("Newly graded (not graded at base)", &c.newly_graded));
    }
    if !c.no_longer_graded.is_empty() {
        md.push_str(&slice_table("No longer graded (not graded at head)", &c.no_longer_graded));
    }
    if !c.changed.is_empty() {
        md.push_str(&format!(
            "<details><summary>{}</summary>\n\n{}\n</details>\n\n",
            heading(c, Section::Changed),
            table(&c.changed, Section::Changed, cap)
        ));
    }
    md
}

/// The PR comment: per runner, the counts and one row per vector file (at most `COMMENT_ROWS` per section).
pub fn comment(ds: &[RunnerDelta]) -> String {
    ds.iter().map(|d| block(d, COMMENT_ROWS)).collect()
}

fn side_text(d: &Dims) -> String {
    d.iter()
        .map(|(dim, (e, g))| format!("{}: expected {}, got {}", esc(dim), code(&brief(e)), code(&brief(g))))
        .collect::<Vec<_>>()
        .join("; ")
}

const TRUNCATED: &str = "\n_… truncated at the job summary's size limit: delta.json holds every entry._\n";

/// The job summary: every file row, then every entry (newly red, red another way, now green) while it fits in
/// `budget` bytes; past it, a note pointing at delta.json.
pub fn summary(ds: &[RunnerDelta], budget: usize) -> String {
    let mut lines: Vec<String> = Vec::new();
    for d in ds {
        lines.extend(block(d, usize::MAX).split_inclusive('\n').map(String::from));
        let Outcome::Compared(c) = &d.outcome else { continue };
        for s in [Section::NewlyRed, Section::Changed, Section::NowGreen] {
            let changes = section_of(c, s);
            if changes.is_empty() {
                continue;
            }
            lines.push(format!("### {}, per entry\n\n", heading(c, s)));
            let mut file: Option<(&str, &str)> = None;
            for ch in changes {
                if file != Some((&ch.slice, &ch.op)) {
                    file = Some((&ch.slice, &ch.op));
                    lines.push(format!("\n**{} · {}**\n\n", code(&ch.slice), code(&ch.op)));
                }
                let what = match s {
                    Section::NewlyRed => side_text(&ch.head),
                    Section::NowGreen => format!("was {}", side_text(&ch.base)),
                    Section::Changed => format!("base {} → head {}", side_text(&ch.base), side_text(&ch.head)),
                };
                lines.push(format!("- {}: {what}\n", code(&ch.entry)));
            }
            lines.push("\n".into());
        }
    }
    let mut md = String::new();
    for line in &lines {
        if md.len() + line.len() + TRUNCATED.len() > budget {
            return md + TRUNCATED;
        }
        md.push_str(line);
    }
    md
}

fn dims_json(d: &Dims) -> Value {
    Value::Object(d.iter().map(|(k, (e, g))| (k.clone(), json!({"expected": e, "got": g}))).collect())
}

fn changes_json(cs: &[Change]) -> Value {
    cs.iter()
        .map(|c| {
            json!({"slice": c.slice, "op": c.op, "entry": c.entry, "script": c.script,
                   "base": dims_json(&c.base), "head": dims_json(&c.head)})
        })
        .collect()
}

fn slices_json(xs: &[SliceCount]) -> Value {
    xs.iter().map(|x| json!({"slice": x.slice, "red_entries": x.red_entries})).collect()
}

/// Every entry of every class, both sides' expected / got (the artifact; the summary may be cut short).
pub fn to_json(ds: &[RunnerDelta]) -> Value {
    let runners: Vec<Value> = ds
        .iter()
        .map(|d| {
            let mut r = json!({"name": d.name, "label": d.label, "base": {"sha": d.base_sha}, "head": {"sha": d.head_sha}});
            match &d.outcome {
                Outcome::NotCompared(why) => {
                    r["compared"] = json!(false);
                    r["reason"] = json!(why);
                }
                Outcome::Compared(c) => {
                    r["compared"] = json!(true);
                    r["newly_red"] = changes_json(&c.newly_red);
                    r["now_green"] = changes_json(&c.now_green);
                    r["changed"] = changes_json(&c.changed);
                    r["unchanged_red"] = json!(c.unchanged_red);
                    r["newly_graded"] = slices_json(&c.newly_graded);
                    r["no_longer_graded"] = slices_json(&c.no_longer_graded);
                    r["scope_changed"] = c.scope_changed.as_ref().map_or(Value::Null, |(b, h)| json!({"base": b, "head": h}));
                }
            }
            r
        })
        .collect();
    json!({"schema": "santa-delta/v1", "runners": runners})
}

#[cfg(test)]
mod tests {
    use super::*;

    fn red(op: &str, entry: &str, dim: &str, got: &str) -> Value {
        json!({"op": op, "entry": entry, "dim": dim, "expected": "E", "got": got, "script": format!("s:{entry}")})
    }

    fn ran(name: &str, sha: &str, version: &str, slices: &[(&str, Vec<Value>)]) -> Value {
        let slices: serde_json::Map<String, Value> =
            slices.iter().map(|(s, reds)| (s.to_string(), json!({"red": reds}))).collect();
        json!({"name": name, "label": format!("{name} (label)"), "version": version, "tiers": ["eval"], "cost": true,
               "kinds": null, "activation": true, "impl": "x#main", "sha": sha, "mark": "coal", "slices": slices})
    }

    fn results(runners: Vec<Value>) -> Value {
        json!({"schema": "santa-results/v1", "runners": runners})
    }

    fn compared(d: &RunnerDelta) -> &Compared {
        match &d.outcome {
            Outcome::Compared(c) => c,
            Outcome::NotCompared(why) => panic!("not compared: {why}"),
        }
    }

    fn entries(cs: &[Change]) -> Vec<&str> {
        cs.iter().map(|c| c.entry.as_str()).collect()
    }

    const S: &str = "eval/v6/authored";

    #[test]
    fn an_entry_is_classified_by_its_red_dims_at_base_and_head() {
        let base = results(vec![ran("dasher", "b", "v6", &[(S, vec![
            red("f", "A", "value", "1"),
            red("f", "B", "not-implemented", "not-implemented"),
            red("f", "C", "value", "2"),
            red("f", "D", "cost", "9"),
        ])])]);
        let head = results(vec![ran("dasher", "h", "v6", &[(S, vec![
            red("f", "A", "value", "1"),  // the same: unchanged
            red("f", "B", "value", "3"),  // another dimension
            red("f", "C", "value", "5"),  // the same dimension, another answer
            red("f", "E", "reject", "x"), // red only at head
        ])])]);
        let ds = delta(&base, &head);
        assert_eq!(ds.len(), 1);
        assert_eq!((ds[0].base_sha.as_deref(), ds[0].head_sha.as_deref()), (Some("b"), Some("h")));
        let c = compared(&ds[0]);
        assert_eq!(entries(&c.newly_red), ["E"]);
        assert_eq!(entries(&c.now_green), ["D"]);
        assert_eq!(entries(&c.changed), ["B", "C"]);
        assert_eq!(c.unchanged_red, 1);
        let b = &c.changed[0];
        assert_eq!(b.base.keys().collect::<Vec<_>>(), ["not-implemented"]);
        assert_eq!(b.head["value"], (json!("E"), json!("3")));
        assert_eq!(b.script.as_deref(), Some("s:B"));
    }

    /// The same entry name in two files (or two slices) is two entries.
    #[test]
    fn an_entry_is_keyed_by_slice_and_file_too() {
        let base = results(vec![ran("r", "b", "v6", &[(S, vec![red("f", "A", "value", "1")])])]);
        let head = results(vec![ran("r", "h", "v6", &[(S, vec![red("g", "A", "value", "1")])])]);
        let c = compared(&delta(&base, &head)[0]).clone();
        assert_eq!((c.newly_red[0].op.as_str(), c.now_green[0].op.as_str()), ("g", "f"));
    }

    #[test]
    fn rows_group_by_file_with_a_split() {
        let base = results(vec![ran("r", "b", "v6", &[(S, vec![
            red("g", "N", "not-implemented", "not-implemented"),
            red("g", "M", "not-implemented", "not-implemented"),
        ])])]);
        let head = results(vec![ran("r", "h", "v6", &[(S, vec![
            red("f", "A", "value", "1"),
            red("f", "B", "value", "1"),
            red("f", "C", "value", "1"),
            red("f", "C", "cost", "1"),
            red("e", "D", "reject", "1"),
            red("g", "N", "value", "1"),
            red("g", "M", "value", "1"),
        ])])]);
        let c = compared(&delta(&base, &head)[0]).clone();
        let row = |op: &str, entries: usize, split: &[(&str, usize)]| Row {
            slice: S.into(),
            op: op.into(),
            entries,
            split: split.iter().map(|(k, n)| (k.to_string(), *n)).collect(),
        };
        // largest file first; within it, the largest dimension first
        assert_eq!(rows(&c.newly_red, Section::NewlyRed),
                   [row("f", 3, &[("value", 3), ("cost", 1)]), row("e", 1, &[("reject", 1)])]);
        assert_eq!(rows(&c.changed, Section::Changed), [row("g", 2, &[("not-implemented → value", 2)])]);
    }

    /// Absence from base's red list is not green when base never graded that slice.
    #[test]
    fn a_slice_graded_on_one_side_only_is_listed_apart() {
        let base = results(vec![ran("r", "b", "v5", &[(S, vec![]), ("eval/v5/spec", vec![red("f", "Z", "value", "1")])])]);
        let head = results(vec![ran("r", "h", "v6", &[(S, vec![]),
            ("wire/v6/authored", vec![red("w", "A", "roundtrip", "1"), red("w", "B", "roundtrip", "1")])])]);
        let c = compared(&delta(&base, &head)[0]).clone();
        assert!(c.newly_red.is_empty() && c.now_green.is_empty());
        assert_eq!(c.newly_graded, [SliceCount { slice: "wire/v6/authored".into(), red_entries: 2 }]);
        assert_eq!(c.no_longer_graded, [SliceCount { slice: "eval/v5/spec".into(), red_entries: 1 }]);
        assert!(c.scope_changed.as_ref().is_some_and(|(b, h)| b.contains("v5") && h.contains("v6")), "{:?}", c.scope_changed);
    }

    #[test]
    fn an_errored_side_is_not_compared() {
        let base = results(vec![ran("r", "b", "v6", &[(S, vec![])])]);
        let mut errored = ran("r", "h", "v6", &[]);
        errored["mark"] = json!("errored");
        errored["error"] = json!("santa-run exited 101: could not build/run");
        let ds = delta(&base, &results(vec![errored]));
        match &ds[0].outcome {
            Outcome::NotCompared(why) => assert!(why.contains("head") && why.contains("exited 101"), "{why}"),
            o => panic!("{o:?}"),
        }
        assert!(comment(&ds).contains("exited 101"));
    }

    #[test]
    fn a_runner_on_one_side_only_is_named() {
        let base = results(vec![ran("r", "b", "v6", &[]), ran("gone", "b", "v6", &[])]);
        let head = results(vec![ran("r", "h", "v6", &[]), ran("new", "h", "v6", &[])]);
        let ds = delta(&base, &head);
        let why = |n: &str| match &ds.iter().find(|d| d.name == n).unwrap().outcome {
            Outcome::NotCompared(w) => w.clone(),
            o => panic!("{n}: {o:?}"),
        };
        assert!(why("gone").contains("head"), "{}", why("gone"));
        assert!(why("new").contains("base"), "{}", why("new"));
        assert!(matches!(ds.iter().find(|d| d.name == "r").unwrap().outcome, Outcome::Compared(_)));
    }

    fn mixed() -> Vec<RunnerDelta> {
        let base = results(vec![ran("dasher", "bbbbbbbbbb", "v6", &[(S, vec![
            red("f", "G", "value", "1"),
            red("f", "C", "not-implemented", "not-implemented"),
            red("f", "U", "cost", "1"),
        ])])]);
        let head = results(vec![ran("dasher", "hhhhhhhhhh", "v6", &[(S, vec![
            red("f", "N", "value", "1"),
            red("f", "C", "value", "2"),
            red("f", "U", "cost", "1"),
        ])])]);
        delta(&base, &head)
    }

    #[test]
    fn the_comment_puts_newly_red_first_and_folds_red_another_way() {
        let md = comment(&mixed());
        assert!(md.contains("dasher (label)") && md.contains("`bbbbbbb`") && md.contains("`hhhhhhh`"), "{md}");
        let at = |s: &str| md.find(s).unwrap_or_else(|| panic!("{s:?} missing:\n{md}"));
        assert!(at("Newly red") < at("Now green"));
        assert!(at("Now green") < at("<details>"));
        assert!(at("<details>") < at("Red another way"));
        assert!(md.contains("still red"), "{md}");
    }

    #[test]
    fn the_comment_caps_rows_per_section_and_the_summary_does_not() {
        let base = results(vec![ran("r", "b", "v6", &[(S, vec![])])]);
        let reds: Vec<Value> = (0..COMMENT_ROWS + 10).map(|i| red(&format!("file{i:03}"), "A", "value", "1")).collect();
        let ds = delta(&base, &results(vec![ran("r", "h", "v6", &[(S, reds)])]));
        let md = comment(&ds);
        assert_eq!(md.matches("file0").count(), COMMENT_ROWS, "{md}");
        assert!(md.contains("10 more files"), "{md}");
        let last = format!("<code>file{:03}</code>", COMMENT_ROWS + 9);
        assert!(!md.contains(&last) && summary(&ds, 1 << 20).contains(&last));
    }

    #[test]
    fn the_summary_lists_every_entry_until_its_budget() {
        let ds = mixed();
        let full = summary(&ds, 1 << 20);
        for e in ["<code>N</code>", "<code>G</code>", "<code>C</code>"] {
            assert!(full.contains(e), "{e} missing:\n{full}");
        }
        assert!(!full.contains("truncated"));
        let cut = summary(&ds, full.len() - 1);
        assert!(cut.contains("truncated") && cut.contains("delta.json"), "{cut}");
    }

    #[test]
    fn cells_escape_pipes_and_html() {
        let base = results(vec![ran("r", "b", "v6", &[(S, vec![])])]);
        let ds = delta(&base, &results(vec![ran("r", "h", "v6", &[(S, vec![red("a|b<c>", "x|y", "value", "<1|2>")])])]));
        for md in [comment(&ds), summary(&ds, 1 << 20)] {
            assert!(md.contains("a&#124;b&lt;c&gt;") && !md.contains("a|b"), "{md}");
        }
        assert!(summary(&ds, 1 << 20).contains("&lt;1&#124;2&gt;"));
    }

    #[test]
    fn the_json_holds_every_entry_with_both_sides() {
        let j = to_json(&mixed());
        assert_eq!(j["schema"], "santa-delta/v1");
        let r = &j["runners"][0];
        assert_eq!((r["name"].as_str(), r["base"]["sha"].as_str()), (Some("dasher"), Some("bbbbbbbbbb")));
        assert_eq!(r["unchanged_red"], 1);
        let c = &r["changed"][0];
        assert_eq!((c["slice"].as_str(), c["op"].as_str(), c["entry"].as_str()), (Some(S), Some("f"), Some("C")));
        assert_eq!(c["base"]["not-implemented"]["got"], "not-implemented");
        assert_eq!(c["head"]["value"], json!({"expected": "E", "got": "2"}));
        assert_eq!(r["newly_red"][0]["entry"], "N");
        assert_eq!(r["now_green"][0]["entry"], "G");
    }

    #[test]
    fn no_change_says_so() {
        let side = |sha: &str| results(vec![ran("r", sha, "v6", &[(S, vec![red("f", "A", "value", "1")])])]);
        let ds = delta(&side("b"), &side("h"));
        let c = compared(&ds[0]);
        assert!(c.newly_red.is_empty() && c.now_green.is_empty() && c.changed.is_empty() && c.unchanged_red == 1);
        assert!(comment(&ds).contains("No change"), "{}", comment(&ds));
    }
}
