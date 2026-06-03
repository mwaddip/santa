//! SANTA schema gate (Rust port of the former schema/validate.py). The schemas are the
//! machine-checkable half of the runner contract (docs/contract/runner-contract.md); this is their
//! regression gate. It (1) builds both schemas (valid Draft 2020-12), (2) validates every committed
//! vector under vectors/eval/ against the vector schema, (2b) checks the taxonomy path agrees with
//! each vector's in-data envelope, and (3) spot-checks the actuals schema's load-bearing asymmetries
//! (cost-as-number, Long-as-string). Exit 0 = all good; non-zero = at least one failure (a CI gate).
use jsonschema::{Registry, Resource, Validator};
use serde_json::{json, Value};
use std::{
    fs,
    path::{Path, PathBuf},
    process::ExitCode,
};

fn load(p: &Path) -> Value {
    let text = fs::read_to_string(p).unwrap_or_else(|e| panic!("read {}: {e}", p.display()));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("parse {}: {e}", p.display()))
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        format!("{}…", s.chars().take(n).collect::<String>())
    }
}

/// Recursively collect *.json under `dir`, sorted.
fn json_files(dir: &Path) -> Vec<PathBuf> {
    fn walk(d: &Path, out: &mut Vec<PathBuf>) {
        if let Ok(rd) = fs::read_dir(d) {
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() {
                    walk(&p, out);
                } else if p.extension().and_then(|x| x.to_str()) == Some("json") {
                    out.push(p);
                }
            }
        }
    }
    let mut out = Vec::new();
    walk(dir, &mut out);
    out.sort();
    out
}

fn main() -> ExitCode {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../.."); // tools/validate -> repo root
    let schema_dir = root.join("schema");
    let vec_schema = load(&schema_dir.join("santa-eval.vector.schema.json"));
    let act_schema = load(&schema_dir.join("santa-eval.actuals.schema.json"));
    let mut errs: u32 = 0;

    // 1. Build both validators (compiling a schema validates it as Draft 2020-12). The actuals
    //    schema cross-refs the vector schema's svalue $def, so register the vector schema as a
    //    resource — under its $id and its bare filename (the relative form the $ref uses).
    let vec_validator = jsonschema::validator_for(&vec_schema).expect("vector schema invalid");
    let vec_id = vec_schema["$id"].as_str().expect("vector schema $id");
    // The actuals schema cross-refs the vector schema's svalue $def; register the vector schema in a
    // referencing Registry under its $id so the relative $ref resolves against the actuals base.
    let registry = Registry::new()
        .add(vec_id, Resource::from_contents(vec_schema.clone()))
        .expect("register vector schema")
        .prepare()
        .expect("prepare registry");
    let act_validator = jsonschema::options()
        .with_registry(&registry)
        .build(&act_schema)
        .expect("actuals schema invalid");
    println!("[meta] vector + actuals schemas: built (valid Draft 2020-12)");

    // Wire tier (santa-wire/v1): independent schemas, no cross-ref (hex is inlined).
    let wire_vec_schema = load(&schema_dir.join("santa-wire.vector.schema.json"));
    let wire_act_schema = load(&schema_dir.join("santa-wire.actuals.schema.json"));
    let wire_vec_validator =
        jsonschema::validator_for(&wire_vec_schema).expect("wire vector schema invalid");
    let wire_act_validator =
        jsonschema::validator_for(&wire_act_schema).expect("wire actuals schema invalid");
    println!("[meta] wire vector + actuals schemas: built (valid Draft 2020-12)");

    // 2. Every committed vector validates against the vector schema.
    let files = json_files(&root.join("vectors").join("eval"));
    println!("\n[corpus] validating {} committed vectors:", files.len());
    let mut ok = 0;
    for f in &files {
        let doc = load(f);
        let errors: Vec<_> = vec_validator.iter_errors(&doc).collect();
        if errors.is_empty() {
            ok += 1;
        } else {
            errs += 1;
            println!("  FAIL {}", f.file_name().unwrap().to_string_lossy());
            for e in errors.iter().take(4) {
                println!("      {}", truncate(&e.to_string(), 180));
            }
        }
    }
    println!("[corpus] {ok}/{} valid", files.len());

    errs += path_envelope_guard(&root, &files);
    errs += actuals_guards(&act_validator);

    // Wire corpus: validate every committed wire vector against the wire vector schema.
    let wire_files = json_files(&root.join("vectors").join("wire"));
    println!("\n[wire corpus] validating {} committed wire vectors:", wire_files.len());
    let mut wok = 0;
    for f in &wire_files {
        let doc = load(f);
        let errors: Vec<_> = wire_vec_validator.iter_errors(&doc).collect();
        if errors.is_empty() {
            wok += 1;
        } else {
            errs += 1;
            println!("  FAIL {}", f.file_name().unwrap().to_string_lossy());
            for e in errors.iter().take(4) {
                println!("      {}", truncate(&e.to_string(), 180));
            }
        }
    }
    println!("[wire corpus] {wok}/{} valid", wire_files.len());
    errs += wire_path_guard(&root, &wire_files);
    errs += wire_actuals_guards(&wire_act_validator);

    println!(
        "\n=== {} ===",
        if errs == 0 {
            "ALL CHECKS PASSED".to_string()
        } else {
            format!("{errs} FAILURE(S)")
        }
    );
    if errs == 0 {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

/// 2b. The self-describing taxonomy path must agree with the in-data envelope, so the fast
/// path-selector and the self-contained envelope can never silently drift.
fn path_envelope_guard(root: &Path, files: &[PathBuf]) -> u32 {
    let version_activated = |v: &str| match v {
        "v5" => Some(2i64),
        "v6" => Some(3i64),
        _ => None,
    };
    let tier_prefix = |t: &str| match t {
        "eval" => Some("santa-eval/"),
        _ => None,
    };
    println!("\n[catalogue] path <-> envelope guard:");
    let vroot = root.join("vectors");
    let mut g: u32 = 0;
    for f in files {
        let rel = f.strip_prefix(&vroot).unwrap();
        let parts: Vec<String> = rel
            .components()
            .map(|c| c.as_os_str().to_string_lossy().into_owned())
            .collect();
        if parts.len() != 4 {
            g += 1;
            println!("  [WRONG] {}: not <tier>/<version>/<provenance>/<op>.json", rel.display());
            continue;
        }
        let (tier, version, prov) = (&parts[0], &parts[1], &parts[2]);
        let doc = load(f);
        let schema = doc.get("schema").and_then(Value::as_str).unwrap_or("");
        match tier_prefix(tier) {
            Some(pfx) if schema.starts_with(pfx) => {}
            _ => {
                g += 1;
                println!("  [WRONG] {}: schema {schema:?} != tier {tier:?}", rel.display());
            }
        }
        let source = doc.get("source").and_then(Value::as_str).unwrap_or("");
        let is_authored = source.starts_with("santa:authored");
        if (prov == "authored") != is_authored {
            g += 1;
            println!("  [WRONG] {}: provenance {prov:?} vs source {source:?}", rel.display());
        }
        let want = version_activated(version);
        let off: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| e["version"]["activated"].as_i64() != want)
                    .filter_map(|e| e["name"].as_str())
                    .collect()
            })
            .unwrap_or_default();
        if want.is_none() || !off.is_empty() {
            let head = &off[..off.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: version {version:?} wants activated={want:?}, off: {head:?}", rel.display());
        }
    }
    if g == 0 {
        println!("  [OK] all {} paths agree with their envelopes", files.len());
    } else {
        println!("  {g} path/envelope mismatch(es)");
    }
    g
}

/// 3. The actuals schema's load-bearing asymmetries must stay enforced.
fn actuals_guards(v: &Validator) -> u32 {
    let checks: &[(&str, Value, bool)] = &[
        ("success", json!({"x#0": {"value": {"kind": "Int", "value": 6}, "cost": 36, "error": null}}), true),
        ("errored", json!({"x#0": {"value": null, "cost": null, "error": "errored"}}), true),
        ("success w/ string cost rejected", json!({"x#0": {"value": {"kind": "Int", "value": 6}, "cost": "36", "error": null}}), false),
        ("errored w/ non-null cost rejected", json!({"x#0": {"value": null, "cost": 5, "error": "errored"}}), false),
        ("Long as string", json!({"x#0": {"value": {"kind": "Long", "value": "9000000000"}, "cost": 1, "error": null}}), true),
        ("success w/ null cost accepted", json!({"x#0": {"value": {"kind": "Int", "value": 6}, "cost": null, "error": null}}), true),
        ("Long as number rejected", json!({"x#0": {"value": {"kind": "Long", "value": 9000000000i64}, "cost": 1, "error": null}}), false),
        ("Int as string rejected", json!({"x#0": {"value": {"kind": "Int", "value": "42"}, "cost": 1, "error": null}}), false),
        ("panicked carries note", json!({"x#0": {"value": null, "cost": null, "error": "panicked", "note": "boom: unmodeled kind"}}), true),
        ("panicked without note rejected", json!({"x#0": {"value": null, "cost": null, "error": "panicked"}}), false),
        ("note on non-panicked rejected", json!({"x#0": {"value": null, "cost": null, "error": "errored", "note": "x"}}), false),
        ("panicked w/ non-null value rejected", json!({"x#0": {"value": {"kind": "Int", "value": 6}, "cost": null, "error": "panicked", "note": "b"}}), false),
    ];
    println!("\n[actuals] asymmetry guards:");
    let mut bad: u32 = 0;
    for (label, doc, want) in checks {
        let got = v.is_valid(doc);
        let good = got == *want;
        if !good {
            bad += 1;
        }
        println!("  [{}] {label}: valid={got} (want {want})", if good { "OK" } else { "WRONG" });
    }
    bad
}

/// Wire taxonomy path <-> in-data envelope guard (parallel to path_envelope_guard, wire
/// rules). tier "wire" => schema "santa-wire/"; version v5->activated 2, v6->3; provenance
/// "authored" iff source is NOT a capture (captures use a "testnet:" source).
fn wire_path_guard(root: &Path, files: &[PathBuf]) -> u32 {
    let version_activated = |v: &str| match v {
        "v5" => Some(2i64),
        "v6" => Some(3i64),
        _ => None,
    };
    println!("\n[wire catalogue] path <-> envelope guard:");
    let vroot = root.join("vectors");
    let mut g: u32 = 0;
    for f in files {
        let rel = f.strip_prefix(&vroot).unwrap();
        let parts: Vec<String> = rel
            .components()
            .map(|c| c.as_os_str().to_string_lossy().into_owned())
            .collect();
        if parts.len() != 4 {
            g += 1;
            println!("  [WRONG] {}: not <tier>/<version>/<provenance>/<op>.json", rel.display());
            continue;
        }
        let (tier, version, prov) = (&parts[0], &parts[1], &parts[2]);
        let doc = load(f);
        let schema = doc.get("schema").and_then(Value::as_str).unwrap_or("");
        if tier != "wire" || !schema.starts_with("santa-wire/") {
            g += 1;
            println!("  [WRONG] {}: schema {schema:?} != tier {tier:?}", rel.display());
        }
        let source = doc.get("source").and_then(Value::as_str).unwrap_or("");
        let is_authored = !source.starts_with("testnet:");
        if (prov == "authored") != is_authored {
            g += 1;
            println!("  [WRONG] {}: provenance {prov:?} vs source {source:?}", rel.display());
        }
        let want = version_activated(version);
        let off: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| e["version"]["activated"].as_i64() != want)
                    .filter_map(|e| e["name"].as_str())
                    .collect()
            })
            .unwrap_or_default();
        if want.is_none() || !off.is_empty() {
            let head = &off[..off.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: version {version:?} wants activated={want:?}, off: {head:?}", rel.display());
        }
    }
    if g == 0 {
        println!("  [OK] all {} wire paths agree with their envelopes", files.len());
    } else {
        println!("  {g} wire path/envelope mismatch(es)");
    }
    g
}

/// Wire actuals asymmetry guards: round-trip-ok carries hex bytes + null error; any tag
/// carries null bytes. (No cost dimension at the wire tier.)
fn wire_actuals_guards(v: &Validator) -> u32 {
    let checks: &[(&str, Value, bool)] = &[
        ("roundtrip-ok", json!({"e#0": {"bytes_hex": "c0843d", "error": null}}), true),
        ("errored", json!({"e#0": {"bytes_hex": null, "error": "errored"}}), true),
        ("not-implemented", json!({"e#0": {"bytes_hex": null, "error": "not-implemented"}}), true),
        ("ok w/ null bytes rejected", json!({"e#0": {"bytes_hex": null, "error": null}}), false),
        ("errored w/ bytes rejected", json!({"e#0": {"bytes_hex": "c0", "error": "errored"}}), false),
        ("upper-case hex rejected", json!({"e#0": {"bytes_hex": "C0", "error": null}}), false),
        ("extra cost field rejected", json!({"e#0": {"bytes_hex": "c0", "error": null, "cost": 1}}), false),
        ("panicked carries note", json!({"e#0": {"bytes_hex": null, "error": "panicked", "note": "boom"}}), true),
        ("panicked without note rejected", json!({"e#0": {"bytes_hex": null, "error": "panicked"}}), false),
        ("note on non-panicked rejected", json!({"e#0": {"bytes_hex": null, "error": "errored", "note": "x"}}), false),
    ];
    println!("\n[wire actuals] asymmetry guards:");
    let mut bad: u32 = 0;
    for (label, doc, want) in checks {
        let got = v.is_valid(doc);
        let good = got == *want;
        if !good {
            bad += 1;
        }
        println!("  [{}] {label}: valid={got} (want {want})", if good { "OK" } else { "WRONG" });
    }
    bad
}
