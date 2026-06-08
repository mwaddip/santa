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

    // Transaction tier (santa-transaction/v1): independent schemas, no cross-ref.
    let tx_vec_schema = load(&schema_dir.join("santa-transaction.vector.schema.json"));
    let tx_act_schema = load(&schema_dir.join("santa-transaction.actuals.schema.json"));
    let tx_vec_validator =
        jsonschema::validator_for(&tx_vec_schema).expect("transaction vector schema invalid");
    let tx_act_validator =
        jsonschema::validator_for(&tx_act_schema).expect("transaction actuals schema invalid");
    println!("[meta] transaction vector + actuals schemas: built (valid Draft 2020-12)");

    // Block tier (santa-block/v1): independent schemas, no cross-ref.
    let block_vec_schema = load(&schema_dir.join("santa-block.vector.schema.json"));
    let block_act_schema = load(&schema_dir.join("santa-block.actuals.schema.json"));
    let block_vec_validator =
        jsonschema::validator_for(&block_vec_schema).expect("block vector schema invalid");
    let block_act_validator =
        jsonschema::validator_for(&block_act_schema).expect("block actuals schema invalid");
    println!("[meta] block vector + actuals schemas: built (valid Draft 2020-12)");

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

    // Transaction corpus: validate every committed transaction vector against the tx vector schema.
    let tx_files = json_files(&root.join("vectors").join("transaction"));
    println!("\n[transaction corpus] validating {} committed transaction vectors:", tx_files.len());
    let mut tok = 0;
    for f in &tx_files {
        let doc = load(f);
        let errors: Vec<_> = tx_vec_validator.iter_errors(&doc).collect();
        if errors.is_empty() {
            tok += 1;
        } else {
            errs += 1;
            println!("  FAIL {}", f.file_name().unwrap().to_string_lossy());
            for e in errors.iter().take(4) {
                println!("      {}", truncate(&e.to_string(), 180));
            }
        }
    }
    println!("[transaction corpus] {tok}/{} valid", tx_files.len());
    errs += tx_path_guard(&root, &tx_files);
    errs += transaction_actuals_guards(&tx_act_validator);

    // Block corpus: validate every committed block vector against the block vector schema.
    let block_files = json_files(&root.join("vectors").join("block"));
    println!("\n[block corpus] validating {} committed block vectors:", block_files.len());
    let mut bok = 0;
    for f in &block_files {
        let doc = load(f);
        let errors: Vec<_> = block_vec_validator.iter_errors(&doc).collect();
        if errors.is_empty() {
            bok += 1;
        } else {
            errs += 1;
            println!("  FAIL {}", f.file_name().unwrap().to_string_lossy());
            for e in errors.iter().take(4) {
                println!("      {}", truncate(&e.to_string(), 180));
            }
        }
    }
    println!("[block corpus] {bok}/{} valid", block_files.len());
    errs += block_path_guard(&root, &block_files);
    errs += block_actuals_guards(&block_act_validator);

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
/// follows each entry's `source`: framework => vendored, testnet:/santa: => authored, spec => spec.
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
        // Provenance follows each entry's `source` (per-entry, so one slice can vendor from
        // several frameworks): a framework source => vendored; testnet:/santa: => authored;
        // sigma-state:/spec: => spec. Every entry's source must agree with the dir.
        let prov_of = |src: &str| -> &'static str {
            if src.starts_with("testnet:") || src.starts_with("santa:") {
                "authored"
            } else if src.starts_with("sigma-state:") || src.starts_with("spec:") {
                "spec"
            } else {
                "vendored"
            }
        };
        let bad_src: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| {
                        let s = e["source"].as_str().unwrap_or("");
                        s.is_empty() || prov_of(s) != prov.as_str()
                    })
                    .filter_map(|e| e["name"].as_str())
                    .collect()
            })
            .unwrap_or_default();
        if !bad_src.is_empty() {
            let head = &bad_src[..bad_src.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: provenance {prov:?} but entry source(s) disagree: {head:?}", rel.display());
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

/// Transaction actuals asymmetry guards: error null => valid non-null (verdict present);
/// any non-null error => valid null; panicked carries note.
fn transaction_actuals_guards(v: &Validator) -> u32 {
    let checks: &[(&str, Value, bool)] = &[
        // valid cases
        ("accepted", json!({"t#0": {"valid": true, "cost": 1200, "error": null}}), true),
        ("rejected", json!({"t#0": {"valid": false, "cost": null, "error": null}}), true),
        // cost is integer-or-null independent of valid; a clean rejection may carry a non-null cost.
        ("rejected with cost", json!({"t#0": {"valid": false, "cost": 500, "error": null}}), true),
        ("errored", json!({"t#0": {"valid": null, "cost": null, "error": "errored"}}), true),
        ("not-implemented", json!({"t#0": {"valid": null, "cost": null, "error": "not-implemented"}}), true),
        ("panicked carries note", json!({"t#0": {"valid": null, "cost": null, "error": "panicked", "note": "boom"}}), true),
        // invalid: error null AND valid null (no verdict)
        ("error null + valid null rejected", json!({"t#0": {"valid": null, "cost": null, "error": null}}), false),
        // invalid: non-null error AND non-null valid
        ("errored + valid non-null rejected", json!({"t#0": {"valid": false, "cost": null, "error": "errored"}}), false),
        ("panicked + valid non-null rejected", json!({"t#0": {"valid": true, "cost": null, "error": "panicked", "note": "b"}}), false),
        // invalid: cost as string
        ("string cost rejected", json!({"t#0": {"valid": true, "cost": "1200", "error": null}}), false),
        // invalid: panicked without note
        ("panicked without note rejected", json!({"t#0": {"valid": null, "cost": null, "error": "panicked"}}), false),
        // invalid: note on non-panicked
        ("note on non-panicked rejected", json!({"t#0": {"valid": null, "cost": null, "error": "errored", "note": "x"}}), false),
    ];
    println!("\n[transaction actuals] asymmetry guards:");
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

/// Block taxonomy path <-> in-data envelope guard. tier "block" => schema "santa-block/";
/// version v6 => activated 3; provenance captured => every entry source starts with "testnet:"
/// AND expected.valid == true; provenance authored => every entry source starts with "santa:".
/// accept arm (valid:true): post_digest non-null AND cost non-null AND reason null.
/// reject arm (valid:false): post_digest null AND cost null AND reason non-null string.
/// block.adProofs.proofBytes must be a non-empty string on every entry.
fn block_path_guard(root: &Path, files: &[PathBuf]) -> u32 {
    let version_activated = |v: &str| match v {
        "v6" => Some(3i64),
        _ => None,
    };
    println!("\n[block catalogue] path <-> envelope guard:");
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
        if tier != "block" || !schema.starts_with("santa-block/") {
            g += 1;
            println!("  [WRONG] {}: schema {schema:?} != tier {tier:?}", rel.display());
        }
        // version -> activated: unknown version fires [WRONG] unconditionally (mirrors wire/tx).
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
        // Provenance: captured => source starts with "testnet:" AND expected.valid == true.
        // authored => source starts with "santa:".
        // Any other provenance is unknown: report each offending entry.
        let bad_src: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| {
                        let src = e["source"].as_str().unwrap_or("");
                        match prov.as_str() {
                            "captured" => {
                                let wrong_src = !src.starts_with("testnet:");
                                let wrong_valid = e["expected"]["valid"].as_bool() != Some(true);
                                wrong_src || wrong_valid
                            }
                            "authored" => !src.starts_with("santa:"),
                            _ => true,
                        }
                    })
                    .filter_map(|e| e["name"].as_str())
                    .collect()
            })
            .unwrap_or_default();
        if !bad_src.is_empty() {
            let head = &bad_src[..bad_src.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: provenance {prov:?} but entry source/expected disagree: {head:?}", rel.display());
        }
        // accept arm (valid:true): post_digest non-null AND cost non-null AND reason null.
        // reject arm (valid:false): post_digest null AND cost null AND reason non-null string.
        let bad_shape: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| {
                        let exp = &e["expected"];
                        match exp["valid"].as_bool() {
                            Some(true) => {
                                exp["post_digest"].is_null()
                                    || exp["cost"].is_null()
                                    || !exp["reason"].is_null()
                            }
                            Some(false) => {
                                !exp["post_digest"].is_null()
                                    || !exp["cost"].is_null()
                                    || exp["reason"].as_str().is_none()
                            }
                            None => false, // schema already rejects this
                        }
                    })
                    .filter_map(|e| e["name"].as_str())
                    .collect()
            })
            .unwrap_or_default();
        if !bad_shape.is_empty() {
            let head = &bad_shape[..bad_shape.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: expected arm shape violation: {head:?}", rel.display());
        }
        // block.adProofs.proofBytes must be a non-empty string on every entry.
        let bad_proof: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| {
                        let proof_bytes = e["block"]["adProofs"]["proofBytes"].as_str();
                        proof_bytes.map_or(true, |s| s.is_empty())
                    })
                    .filter_map(|e| e["name"].as_str())
                    .collect()
            })
            .unwrap_or_default();
        if !bad_proof.is_empty() {
            let head = &bad_proof[..bad_proof.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: block.adProofs.proofBytes missing or empty: {head:?}", rel.display());
        }
    }
    if g == 0 {
        println!("  [OK] all {} block paths agree with their envelopes", files.len());
    } else {
        println!("  {g} block path/envelope mismatch(es)");
    }
    g
}

/// Block actuals asymmetry guards: error null => valid non-null (verdict present);
/// any non-null error => valid, post_digest, cost all null; panicked carries note.
fn block_actuals_guards(v: &Validator) -> u32 {
    let checks: &[(&str, Value, bool)] = &[
        // valid cases
        ("accepted", json!({"block__0": {"valid": true, "post_digest": "0000000000000000000000000000000000000000000000000000000000000000ab", "cost": 1200, "error": null}}), true),
        ("rejected", json!({"block__0": {"valid": false, "post_digest": null, "cost": null, "error": null}}), true),
        ("rejected with reason", json!({"block__0": {"valid": false, "post_digest": null, "cost": null, "error": null, "reason": "invalid tx"}}), true),
        ("errored", json!({"block__0": {"valid": null, "post_digest": null, "cost": null, "error": "errored"}}), true),
        ("not-implemented", json!({"block__0": {"valid": null, "post_digest": null, "cost": null, "error": "not-implemented"}}), true),
        ("panicked carries note", json!({"block__0": {"valid": null, "post_digest": null, "cost": null, "error": "panicked", "note": "boom"}}), true),
        // invalid: error null AND valid null (no verdict)
        ("error null + valid null rejected", json!({"block__0": {"valid": null, "post_digest": null, "cost": null, "error": null}}), false),
        // invalid: non-null error AND non-null valid
        ("errored + valid non-null rejected", json!({"block__0": {"valid": false, "post_digest": null, "cost": null, "error": "errored"}}), false),
        ("panicked + valid non-null rejected", json!({"block__0": {"valid": true, "post_digest": null, "cost": null, "error": "panicked", "note": "b"}}), false),
        // invalid: error non-null AND post_digest non-null
        ("errored + post_digest non-null rejected", json!({"block__0": {"valid": null, "post_digest": "0000000000000000000000000000000000000000000000000000000000000000ab", "cost": null, "error": "errored"}}), false),
        // invalid: error non-null AND cost non-null
        ("errored + cost non-null rejected", json!({"block__0": {"valid": null, "post_digest": null, "cost": 500, "error": "errored"}}), false),
        // invalid: cost as string
        ("string cost rejected", json!({"block__0": {"valid": true, "post_digest": "0000000000000000000000000000000000000000000000000000000000000000ab", "cost": "1200", "error": null}}), false),
        // invalid: panicked without note
        ("panicked without note rejected", json!({"block__0": {"valid": null, "post_digest": null, "cost": null, "error": "panicked"}}), false),
        // invalid: note on non-panicked
        ("note on non-panicked rejected", json!({"block__0": {"valid": null, "post_digest": null, "cost": null, "error": "errored", "note": "x"}}), false),
    ];
    println!("\n[block actuals] asymmetry guards:");
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

/// Transaction taxonomy path <-> in-data envelope guard. tier "transaction" => schema
/// "santa-transaction/v1"; version v6 => activated 3; provenance captured => every entry
/// source starts with "testnet:" AND expected.valid == true; provenance authored => every
/// entry source starts with "santa:".
fn tx_path_guard(root: &Path, files: &[PathBuf]) -> u32 {
    let version_activated = |v: &str| match v {
        "v6" => Some(3i64),
        _ => None,
    };
    println!("\n[transaction catalogue] path <-> envelope guard:");
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
        if tier != "transaction" || !schema.starts_with("santa-transaction/") {
            g += 1;
            println!("  [WRONG] {}: schema {schema:?} != tier {tier:?}", rel.display());
        }
        // version -> activated: unknown version fires [WRONG] unconditionally (mirrors wire).
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
        // Provenance: captured => source starts with "testnet:" AND expected.valid == true.
        // authored => source starts with "santa:".
        let bad_src: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| {
                        let src = e["source"].as_str().unwrap_or("");
                        match prov.as_str() {
                            "captured" => {
                                let wrong_src = !src.starts_with("testnet:");
                                let wrong_valid = e["expected"]["valid"].as_bool() != Some(true);
                                wrong_src || wrong_valid
                            }
                            "authored" => !src.starts_with("santa:"),
                            _ => false,
                        }
                    })
                    .filter_map(|e| e["name"].as_str())
                    .collect()
            })
            .unwrap_or_default();
        if !bad_src.is_empty() {
            let head = &bad_src[..bad_src.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: provenance {prov:?} but entry source/expected disagree: {head:?}", rel.display());
        }
    }
    if g == 0 {
        println!("  [OK] all {} transaction paths agree with their envelopes", files.len());
    } else {
        println!("  {g} transaction path/envelope mismatch(es)");
    }
    g
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn schema_dir() -> std::path::PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../..").join("schema")
    }

    fn tx_vec_validator() -> Validator {
        let schema = load(&schema_dir().join("santa-transaction.vector.schema.json"));
        jsonschema::validator_for(&schema).expect("tx vector schema invalid")
    }

    fn tx_act_validator() -> Validator {
        let schema = load(&schema_dir().join("santa-transaction.actuals.schema.json"));
        jsonschema::validator_for(&schema).expect("tx actuals schema invalid")
    }

    fn minimal_tx_entry(source: &str, valid: bool, reason: Option<&str>) -> Value {
        json!({
            "name": "test-entry",
            "source": source,
            "tx": { "id": "abc", "inputs": [], "dataInputs": [], "outputs": [] },
            "inputBoxes": [{}],
            "dataInputBoxes": [],
            "context": { "height": 100 },
            "version": { "activated": 3, "ergoTree": 1 },
            "expected": {
                "valid": valid,
                "cost": if valid { json!(1000) } else { json!(null) },
                "reason": reason.map(|r| json!(r)).unwrap_or(json!(null))
            }
        })
    }

    fn minimal_tx_vector(source: &str, valid: bool, reason: Option<&str>) -> Value {
        json!({
            "schema": "santa-transaction/v1",
            "op": "test-op",
            "blessed_by": "test",
            "entries": [minimal_tx_entry(source, valid, reason)]
        })
    }

    /// A well-formed santa-transaction/v1 vector passes the transaction schema guard.
    #[test]
    fn tx_vector_well_formed_passes() {
        let v = tx_vec_validator();
        let doc = minimal_tx_vector("testnet:chain-xyz", true, None);
        assert!(v.is_valid(&doc), "well-formed tx vector should pass schema");
    }

    /// valid:true with a non-null reason must be rejected by the vector schema.
    #[test]
    fn tx_vector_valid_true_with_reason_fails() {
        let v = tx_vec_validator();
        let doc = minimal_tx_vector("testnet:chain-xyz", true, Some("spurious reason"));
        assert!(!v.is_valid(&doc), "valid:true + non-null reason must fail schema");
    }

    /// Actuals: error null AND valid null — no verdict — must fail.
    #[test]
    fn tx_actuals_error_null_valid_null_fails() {
        let v = tx_act_validator();
        let doc = json!({"t#0": {"valid": null, "cost": null, "error": null}});
        assert!(!v.is_valid(&doc), "error:null + valid:null must fail actuals schema");
    }

    /// Actuals: non-null error ("errored") with valid:false — must fail (error non-null => valid null).
    #[test]
    fn tx_actuals_errored_with_valid_false_fails() {
        let v = tx_act_validator();
        let doc = json!({"t#0": {"valid": false, "cost": null, "error": "errored"}});
        assert!(!v.is_valid(&doc), "error:errored + valid:false must fail actuals schema");
    }

    /// transaction_actuals_guards returns 0 failures against the real schema.
    #[test]
    fn tx_actuals_guards_all_pass() {
        let schema = load(&schema_dir().join("santa-transaction.actuals.schema.json"));
        let v = jsonschema::validator_for(&schema).expect("tx actuals schema invalid");
        let bad = transaction_actuals_guards(&v);
        assert_eq!(bad, 0, "transaction_actuals_guards should report 0 failures");
    }

    /// tx_path_guard on an empty file list is a no-op (returns 0).
    #[test]
    fn tx_path_guard_empty_tree_ok() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../.."); // tools/validate -> repo root
        let bad = tx_path_guard(&root, &[]);
        assert_eq!(bad, 0, "empty tx tree must not produce path-guard failures");
    }

    /// tx_path_guard fires [WRONG] for an unrecognized version directory (e.g. v7), mirroring
    /// wire_path_guard's unconditional unknown-version rejection.
    #[test]
    fn tx_path_guard_unknown_version_fires_wrong() {
        // Build a temp vectors/transaction/v7/authored/box-test.json tree.
        let tmp = std::env::temp_dir().join(format!("santa-test-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("transaction").join("v7").join("authored");
        fs::create_dir_all(&vdir).expect("create temp dir");
        // A well-formed vector whose entry has activated=3 (valid for v6) but filed under v7.
        let doc = json!({
            "schema": "santa-transaction/v1",
            "op": "test-op",
            "blessed_by": "test",
            "entries": [{
                "name": "test-entry",
                "source": "santa:authored",
                "tx": { "id": "abc", "inputs": [], "dataInputs": [], "outputs": [] },
                "inputBoxes": [{}],
                "dataInputBoxes": [],
                "context": { "height": 100 },
                "version": { "activated": 3, "ergoTree": 1 },
                "expected": { "valid": true, "cost": 1000, "reason": null }
            }]
        });
        let fpath = vdir.join("box-test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = tx_path_guard(&tmp, &[fpath]);
        // Clean up regardless.
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "unknown version directory v7 must fire at least one [WRONG]");
    }
}
