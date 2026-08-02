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

    // AuthDS tier (santa-authds/v1): independent schemas, no cross-ref.
    let authds_vec_schema = load(&schema_dir.join("santa-authds.vector.schema.json"));
    let authds_act_schema = load(&schema_dir.join("santa-authds.actuals.schema.json"));
    let authds_vec_validator =
        jsonschema::validator_for(&authds_vec_schema).expect("authds vector schema invalid");
    let authds_act_validator =
        jsonschema::validator_for(&authds_act_schema).expect("authds actuals schema invalid");
    println!("[meta] authds vector + actuals schemas: built (valid Draft 2020-12)");

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

    // Chain tier (santa-chain/v1): independent schemas, no cross-ref.
    let chain_vec_schema = load(&schema_dir.join("santa-chain.vector.schema.json"));
    let chain_act_schema = load(&schema_dir.join("santa-chain.actuals.schema.json"));
    let chain_vec_validator =
        jsonschema::validator_for(&chain_vec_schema).expect("chain vector schema invalid");
    let chain_act_validator =
        jsonschema::validator_for(&chain_act_schema).expect("chain actuals schema invalid");
    println!("[meta] chain vector + actuals schemas: built (valid Draft 2020-12)");

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

    // AuthDS corpus: validate every committed authds vector against the authds vector schema.
    // vectors/authds/ may not exist yet (empty corpus) — json_files handles missing dirs gracefully.
    let authds_files = json_files(&root.join("vectors").join("authds"));
    println!("\n[authds corpus] validating {} committed authds vectors:", authds_files.len());
    let mut aok = 0;
    for f in &authds_files {
        let doc = load(f);
        let errors: Vec<_> = authds_vec_validator.iter_errors(&doc).collect();
        if errors.is_empty() {
            aok += 1;
        } else {
            errs += 1;
            println!("  FAIL {}", f.file_name().unwrap().to_string_lossy());
            for e in errors.iter().take(4) {
                println!("      {}", truncate(&e.to_string(), 180));
            }
        }
    }
    println!("[authds corpus] {aok}/{} valid", authds_files.len());
    errs += authds_path_guard(&root, &authds_files);
    errs += authds_actuals_guards(&authds_act_validator);

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

    // Chain corpus: validate every committed chain vector against the chain vector schema.
    // vectors/chain/ may not exist yet (empty corpus) — json_files handles missing dirs gracefully.
    let chain_files = json_files(&root.join("vectors").join("chain"));
    println!("\n[chain corpus] validating {} committed chain vectors:", chain_files.len());
    let mut chok = 0;
    for f in &chain_files {
        let doc = load(f);
        let errors: Vec<_> = chain_vec_validator.iter_errors(&doc).collect();
        if errors.is_empty() {
            chok += 1;
        } else {
            errs += 1;
            println!("  FAIL {}", f.file_name().unwrap().to_string_lossy());
            for e in errors.iter().take(4) {
                println!("      {}", truncate(&e.to_string(), 180));
            }
        }
    }
    println!("[chain corpus] {chok}/{} valid", chain_files.len());
    errs += chain_path_guard(&root, &chain_files);
    errs += chain_actuals_guards(&chain_act_validator);

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
        ("errored carries reason", json!({"e#0": {"bytes_hex": null, "error": "errored", "reason": "codec: bad bytes"}}), true),
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

/// AuthDS taxonomy path <-> in-data envelope guard. Per docs/specs/authds-tier.md, authds
/// vectors live at a single fixed cell: tier "authds" => schema "santa-authds/"; version must
/// be "any" (AVL proofs are not ErgoTree-versioned, unlike eval/wire/tx/block); provenance must
/// be "vendored" (ergots' prover/verifier fixtures: inputs vendored, expectations re-derived
/// through jvm-blesser). Per-entry source must start with the ergots avltree fixture prefix.
/// Structural invariant the schema cannot express (JSON Schema has no cross-array length
/// equality): for avl_prove entries, payload.gen_proof_after, expected.proofs, and
/// expected.digests must be parallel arrays of the same length.
fn authds_path_guard(root: &Path, files: &[PathBuf]) -> u32 {
    println!("\n[authds catalogue] path <-> envelope guard:");
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
        if tier != "authds" || !schema.starts_with("santa-authds/") {
            g += 1;
            println!("  [WRONG] {}: schema {schema:?} != tier {tier:?}", rel.display());
        }
        // authds is a single fixed cell (not ErgoTree-versioned, single vendored source family):
        // version must be "any", provenance must be "vendored". Unlike wire/tx/block/chain there
        // is no variance to accommodate here.
        if version != "any" {
            g += 1;
            println!("  [WRONG] {}: unknown version label {version:?} (authds is not ErgoTree-versioned; expected 'any')", rel.display());
        }
        if prov != "vendored" {
            g += 1;
            println!("  [WRONG] {}: unknown provenance {prov:?} (expected 'vendored')", rel.display());
        }
        // Per-entry source must start with the ergots avltree fixture prefix (docs/specs/authds-tier.md
        // `## Vectors / taxonomy`: source: "ergots:packages/avltree/test/fixtures/<set>/<name>").
        let bad_src: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| {
                        let src = e["source"].as_str().unwrap_or("");
                        !src.starts_with("ergots:packages/avltree/test/fixtures/")
                    })
                    .filter_map(|e| e["name"].as_str())
                    .collect()
            })
            .unwrap_or_default();
        if !bad_src.is_empty() {
            let head = &bad_src[..bad_src.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: entry source(s) missing ergots avltree fixture prefix: {head:?}", rel.display());
        }
        // Structural invariant the schema cannot express: avl_prove's gen_proof_after, proofs,
        // and digests are parallel arrays — same length, entry by entry.
        let bad_lengths: Vec<String> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| e["kind"].as_str() == Some("avl_prove"))
                    .filter_map(|e| {
                        let name = e["name"].as_str().unwrap_or("(unnamed)");
                        let gpa = e["payload"]["gen_proof_after"].as_array()?.len();
                        let proofs = e["expected"]["proofs"].as_array()?.len();
                        let digests = e["expected"]["digests"].as_array()?.len();
                        if gpa != proofs || gpa != digests {
                            Some(format!(
                                "{name} (gen_proof_after={gpa}, proofs={proofs}, digests={digests})"
                            ))
                        } else {
                            None
                        }
                    })
                    .collect()
            })
            .unwrap_or_default();
        if !bad_lengths.is_empty() {
            let head = &bad_lengths[..bad_lengths.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: avl_prove parallel-array length mismatch: {head:?}", rel.display());
        }
    }
    if g == 0 {
        println!("  [OK] all {} authds paths agree with their envelopes", files.len());
    } else {
        println!("  {g} authds path/envelope mismatch(es)");
    }
    g
}

/// AuthDS actuals asymmetry guards: an avl_prove verdict carries proofs+digests (both non-null)
/// + null error; an avl_verify verdict carries proof_accepted+results (both non-null, results may
/// be an empty array) + new_digest_hex present as a key (string OR null are both legitimate
/// values — null means a poisoned verifier or a rejected proof, not an absent field) + null error.
/// Rows carry no kind tag, so the accept shape is keyed off which fields are present, and the
/// union shape (other kind's fields present-and-null) is legal. Any non-null error forces every
/// verdict field, if present, to null. `note` iff error == "panicked" — forbidden even on
/// "errored" (the stricter block/wire idiom; chain's laxer "errored may optionally carry note"
/// does not apply here).
fn authds_actuals_guards(v: &Validator) -> u32 {
    let checks: &[(&str, Value, bool)] = &[
        // ---- valid cases ----
        ("prove-ok", json!({"p#0": {"proofs": ["aa"], "digests": ["bb"], "error": null}}), true),
        ("verify-ok", json!({"v#0": {"proof_accepted": true, "results": [{"ok": true, "value": "cc"}], "new_digest_hex": "dd", "error": null}}), true),
        ("verify-ok w/ poisoned digest null", json!({"v#0": {"proof_accepted": true, "results": [{"ok": false, "value": null}], "new_digest_hex": null, "error": null}}), true),
        ("verify-ok w/ proof rejected (empty results, null digest)", json!({"v#0": {"proof_accepted": false, "results": [], "new_digest_hex": null, "error": null}}), true),
        ("union shape: prove row w/ other kind's fields null", json!({"p#0": {"proofs": ["aa"], "digests": ["bb"], "proof_accepted": null, "results": null, "new_digest_hex": null, "error": null}}), true),
        ("errored", json!({"p#0": {"error": "errored"}}), true),
        ("not-implemented", json!({"p#0": {"error": "not-implemented"}}), true),
        ("panicked carries note", json!({"p#0": {"error": "panicked", "note": "boom"}}), true),
        // ---- invalid cases ----
        ("empty file rejected", json!({}), false),
        ("empty row rejected", json!({"p#0": {}}), false),
        ("bogus error value rejected", json!({"p#0": {"error": "bogus"}}), false),
        ("errored still carrying proofs/digests rejected", json!({"p#0": {"error": "errored", "proofs": ["aa"], "digests": ["bb"]}}), false),
        ("errored still carrying proof_accepted rejected", json!({"v#0": {"error": "errored", "proof_accepted": true}}), false),
        ("error null w/ zero verdict fields rejected", json!({"p#0": {"error": null}}), false),
        ("error null w/ only proofs (missing digests) rejected", json!({"p#0": {"error": null, "proofs": ["aa"]}}), false),
        ("error null w/ verify fields missing new_digest_hex key rejected", json!({"v#0": {"error": null, "proof_accepted": true, "results": []}}), false),
        ("panicked without note rejected", json!({"p#0": {"error": "panicked"}}), false),
        ("note on success rejected", json!({"p#0": {"proofs": ["aa"], "digests": ["bb"], "error": null, "note": "x"}}), false),
        ("note on errored rejected", json!({"p#0": {"error": "errored", "note": "x"}}), false),
        ("extra unknown field rejected", json!({"p#0": {"proofs": ["aa"], "digests": ["bb"], "error": null, "bogus_field": 1}}), false),
    ];
    println!("\n[authds actuals] asymmetry guards:");
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
        // block.adProofs.proofBytes must be a non-empty string on every entry,
        // unless the entry is a proofless block (simulated by empty proofBytes).
        let bad_proof: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| {
                        let proof_bytes = e["block"]["adProofs"]["proofBytes"].as_str();
                        if proof_bytes.is_none_or(|s| s.is_empty()) {
                            // Proofless block entries carry empty proofBytes by design.
                            let source = e["source"].as_str().unwrap_or("");
                            !source.contains("proofless")
                        } else {
                            false
                        }
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

/// Chain taxonomy path <-> in-data envelope guard. tier "chain" => schema "santa-chain/";
/// version ∈ {"v5","v6","any"} (retargeting ⇒ "any"; voting ⇒ "v5"|"v6"); provenance ∈
/// {spec, authored, vendored, captured}. Per-entry kind must be in {retargeting, voting}.
/// Recalculation-point rule: retargeting entry's target_height must satisfy
///   (T - 1) % L == 0
/// where L = eip37_epoch_length if both eip37 settings are present and T >= eip37_activation_height,
/// else epoch_length.
fn chain_path_guard(root: &Path, files: &[PathBuf]) -> u32 {
    let valid_versions: &[&str] = &["v5", "v6", "any"];
    let valid_provenances: &[&str] = &["spec", "authored", "vendored", "captured"];
    println!("\n[chain catalogue] path <-> envelope guard:");
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
            println!("  [WRONG] {}: not <tier>/<version>/<provenance>/<file>.json", rel.display());
            continue;
        }
        let (tier, version, prov) = (&parts[0], &parts[1], &parts[2]);

        // Tier must be "chain" and schema must start with "santa-chain/".
        let doc = load(f);
        let schema = doc.get("schema").and_then(Value::as_str).unwrap_or("");
        if tier != "chain" || !schema.starts_with("santa-chain/") {
            g += 1;
            println!("  [WRONG] {}: schema {schema:?} != tier {tier:?}", rel.display());
        }

        // Version must be in the known set.
        if !valid_versions.contains(&version.as_str()) {
            g += 1;
            println!("  [WRONG] {}: unknown version label {version:?} (expected v5|v6|any)", rel.display());
        }

        // Provenance must be in the known set.
        if !valid_provenances.contains(&prov.as_str()) {
            g += 1;
            println!("  [WRONG] {}: unknown provenance {prov:?} (expected spec|authored|vendored|captured)", rel.display());
        }

        // Provenance source agreement.
        let bad_src: Vec<&str> = doc["entries"]
            .as_array()
            .map(|es| {
                es.iter()
                    .filter(|e| {
                        let src = e["source"].as_str().unwrap_or("");
                        match prov.as_str() {
                            "captured" => !src.starts_with("testnet:"),
                            "authored" => !src.starts_with("santa:"),
                            // contract §6: no source convention pinned for spec provenance in v1; accept any non-empty source.
                            "spec" => src.is_empty(),
                            "vendored" => false, // vendored sources are unconstrained
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
            println!("  [WRONG] {}: provenance {prov:?} but entry source(s) disagree: {head:?}", rel.display());
        }

        // Per-entry kind and kind↔version sanity.
        let entries = doc["entries"].as_array();
        let bad_kind: Vec<String> = entries
            .map(|es| {
                es.iter()
                    .filter_map(|e| {
                        let kind = e["kind"].as_str().unwrap_or("");
                        let name = e["name"].as_str().unwrap_or("(unnamed)");
                        match kind {
                            "retargeting" | "header_votes" => {
                                // retargeting / header_votes ⇒ version must be "any"
                                if version != "any" {
                                    Some(format!("{name} ({kind} requires version=any, got {version})"))
                                } else {
                                    None
                                }
                            }
                            "voting" | "fork_vote_gate" => {
                                // voting / fork_vote_gate ⇒ version must be "v5" or "v6"
                                if version != "v5" && version != "v6" {
                                    Some(format!("{name} ({kind} requires version=v5|v6, got {version})"))
                                } else {
                                    None
                                }
                            }
                            other => {
                                Some(format!("{name} (unknown kind {other:?})"))
                            }
                        }
                    })
                    .collect()
            })
            .unwrap_or_default();
        if !bad_kind.is_empty() {
            let head = &bad_kind[..bad_kind.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: kind/version violation(s): {head:?}", rel.display());
        }

        // Recalculation-point rule: for each retargeting entry,
        // (target_height - 1) % governing_epoch_length == 0.
        let bad_recalc: Vec<String> = entries
            .map(|es| {
                es.iter()
                    .filter_map(|e| {
                        if e["kind"].as_str() != Some("retargeting") {
                            return None;
                        }
                        let name = e["name"].as_str().unwrap_or("(unnamed)");
                        let t = e["payload"]["target_height"].as_i64()?;
                        let settings = &e["settings"];
                        let epoch_length = settings["epoch_length"].as_i64()?;
                        // Governing epoch length: use eip37_epoch_length when both eip37 settings
                        // are present AND target_height >= eip37_activation_height.
                        let governing_l = if let (Some(eip37_act), Some(eip37_l)) = (
                            settings["eip37_activation_height"].as_i64(),
                            settings["eip37_epoch_length"].as_i64(),
                        ) {
                            if t >= eip37_act { eip37_l } else { epoch_length }
                        } else {
                            epoch_length
                        };
                        if governing_l <= 0 {
                            Some(format!("{name} (governing epoch_length={governing_l} is zero/negative — schema-invalid)"))
                        } else if (t - 1) % governing_l != 0 {
                            Some(format!("{name} (target_height={t}, (T-1)%{governing_l}={} != 0)", (t - 1) % governing_l))
                        } else {
                            None
                        }
                    })
                    .collect()
            })
            .unwrap_or_default();
        if !bad_recalc.is_empty() {
            let head = &bad_recalc[..bad_recalc.len().min(3)];
            g += 1;
            println!("  [WRONG] {}: recalculation-point violation(s): {head:?}", rel.display());
        }
    }
    if g == 0 {
        println!("  [OK] all {} chain paths agree with their envelopes", files.len());
    } else {
        println!("  {g} chain path/envelope mismatch(es)");
    }
    g
}

/// Chain actuals asymmetry guards: retargeting ok carries nbits + null error; voting ok carries
/// parameters + activated_update + null error; error non-null => kind-value fields null.
fn chain_actuals_guards(v: &Validator) -> u32 {
    let checks: &[(&str, Value, bool)] = &[
        // Retargeting: valid verdict
        ("retargeting-ok", json!({"r#0": {"nbits": 84150434, "error": null}}), true),
        // Voting: valid verdict
        ("voting-ok", json!({"v#0": {"parameters": {"table": {"1": 1000000}}, "activated_update": "0000", "error": null}}), true),
        // Union shape: other kind's fields present-and-null is legal
        ("union-retargeting", json!({"r#0": {"nbits": 84150434, "parameters": null, "activated_update": null, "error": null}}), true),
        ("union-voting", json!({"v#0": {"nbits": null, "parameters": {"table": {}}, "activated_update": "0000", "error": null}}), true),
        // Error modes
        ("errored", json!({"r#0": {"nbits": null, "error": "errored"}}), true),
        ("not-implemented", json!({"r#0": {"nbits": null, "error": "not-implemented"}}), true),
        ("panicked carries note", json!({"r#0": {"nbits": null, "error": "panicked", "note": "boom"}}), true),
        // note on errored is explicitly allowed (contract §3)
        ("note on errored is allowed", json!({"r#0": {"nbits": null, "error": "errored", "note": "decode failed: bad bytes"}}), true),
        // voting ok with null activated_update rejected (both parameters AND activated_update required non-null for voting verdict)
        ("voting ok w/ null activated_update rejected", json!({"v#0": {"parameters": {"table": {}}, "activated_update": null, "error": null}}), false),
        // unknown error value rejected
        ("unknown error value rejected", json!({"r#0": {"nbits": null, "error": "unknown-error"}}), false),
        // nbits as string rejected
        ("nbits as string rejected", json!({"r#0": {"nbits": "84150434", "error": null}}), false),
        // parameters as non-object rejected
        ("parameters as string rejected", json!({"v#0": {"parameters": "invalid", "activated_update": "0000", "error": null}}), false),
        // extra unknown field rejected
        ("extra field rejected", json!({"r#0": {"nbits": 84150434, "error": null, "cost": 99}}), false),
        // note forbidden on success (contract §3: note absent for success row)
        ("note on success rejected", json!({"r#0": {"nbits": 84150434, "error": null, "note": "extra"}}), false),
        // note forbidden on not-implemented (contract §3: note absent for not-implemented row)
        ("note on not-implemented rejected", json!({"r#0": {"nbits": null, "error": "not-implemented", "note": "x"}}), false),
    ];
    println!("\n[chain actuals] asymmetry guards:");
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

    // ── Chain tier tests ─────────────────────────────────────────────────────────

    fn chain_vec_validator() -> Validator {
        let schema = load(&schema_dir().join("santa-chain.vector.schema.json"));
        jsonschema::validator_for(&schema).expect("chain vector schema invalid")
    }

    /// Minimal well-formed retargeting vector (filed under chain/any/captured/).
    fn minimal_retargeting_vector() -> Value {
        json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "retargeting-test-001",
                "source": "testnet:testnet-retarget@393601",
                "kind": "retargeting",
                "settings": {
                    "epoch_length": 128,
                    "use_last_epochs": 8,
                    "block_interval_ms": 45000,
                    "initial_nbits": 16842752
                },
                "payload": {
                    "target_height": 393601,
                    "anchor_headers": [
                        { "height": 393473, "nBits": 84128203, "timestamp": 1781000000000i64, "parentId": "0000000000000000000000000000000000000000000000000000000000000000aa" }
                    ]
                },
                "expected": { "nbits": 84150434 }
            }]
        })
    }

    /// A well-formed retargeting vector validates against the chain schema AND chain_path_guard
    /// returns 0 when filed under chain/any/captured/.
    #[test]
    fn chain_vector_well_formed_passes() {
        let v = chain_vec_validator();
        let doc = minimal_retargeting_vector();
        assert!(v.is_valid(&doc), "well-formed chain retargeting vector should pass schema");

        let tmp = std::env::temp_dir().join(format!("santa-chain-test-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let fpath = vdir.join("Retargeting.test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert_eq!(bad, 0, "well-formed retargeting under chain/any/captured/ must have 0 path-guard failures");
    }

    /// chain_path_guard fires [WRONG] for an unknown version label (e.g. v9).
    #[test]
    fn chain_path_guard_bad_version_fires_wrong() {
        let tmp = std::env::temp_dir().join(format!("santa-chain-badver-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("v9").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let doc = minimal_retargeting_vector();
        let fpath = vdir.join("Retargeting.test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "version v9 must fire at least one [WRONG]");
    }

    /// chain_path_guard accepts the "any" version label without complaint.
    #[test]
    fn chain_path_guard_any_is_legal() {
        let tmp = std::env::temp_dir().join(format!("santa-chain-any-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let doc = minimal_retargeting_vector();
        let fpath = vdir.join("Retargeting.test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert_eq!(bad, 0, "version 'any' must be accepted without [WRONG]");
    }

    /// chain_path_guard fires [WRONG] for an unknown kind (e.g. "mining").
    #[test]
    fn chain_path_guard_unknown_kind_fires_wrong() {
        let v = chain_vec_validator();
        let doc = json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "bad-kind",
                "source": "testnet:x",
                "kind": "mining",
                "settings": {},
                "payload": {},
                "expected": {}
            }]
        });
        // Schema itself should reject "mining" kind.
        assert!(!v.is_valid(&doc), "kind:mining must fail schema validation");

        // AND: chain_path_guard's `other =>` arm (~:746-748) must fire [WRONG] when the guard
        // is handed a "mining" entry — so break-the-arm stays visibly red.
        let tmp = std::env::temp_dir().join(format!("santa-chain-badkind-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let fpath = vdir.join("Mining.test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "kind:mining must fire at least one [WRONG] in chain_path_guard (other=> arm covered)");
    }

    /// kind↔version sanity: voting entry under "any" fires [WRONG].
    #[test]
    fn chain_path_guard_kind_version_sanity() {
        let tmp = std::env::temp_dir().join(format!("santa-chain-kvsanity-{}", std::process::id()));
        // voting under "any" must fire WRONG
        let vdir = tmp.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let doc = json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "voting-under-any",
                "source": "testnet:x",
                "kind": "voting",
                "settings": { "voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32 },
                "payload": {
                    "boundary_height": 256,
                    "current_parameters": { "table": { "1": 1000000 } },
                    "vote_stream": [],
                    "boundary_votes": "000000",
                    "proposed_update": "0000"
                },
                "expected": {
                    "parameters": { "table": { "1": 1000000 } },
                    "activated_update": "0000"
                }
            }]
        });
        let fpath = vdir.join("Voting.test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "voting under 'any' must fire [WRONG] (kind↔version sanity)");
    }

    /// kind↔version sanity: fork_vote_gate under "any" fires [WRONG]; under "v6" passes.
    #[test]
    fn chain_path_guard_fork_vote_gate_kind_version_sanity() {
        // fork_vote_gate under "any" must fire WRONG
        let tmp = std::env::temp_dir().join(format!("santa-chain-gate-kvsanity-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("any").join("authored");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let doc = json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "gate-under-any",
                "source": "santa:fork_vote_gate:test",
                "kind": "fork_vote_gate",
                "settings": { "voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32, "version2_activation_height": 417792 },
                "payload": { "height": 6655, "header_votes": "780000", "current_parameters": { "table": { "1": 1250000 } } },
                "expected": { "valid": true }
            }]
        });
        let fpath = vdir.join("Gate.any.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "fork_vote_gate under 'any' must fire [WRONG] (kind↔version sanity)");

        // fork_vote_gate under "v6" must pass
        let tmp2 = std::env::temp_dir().join(format!("santa-chain-gate-v6-{}", std::process::id()));
        let vdir2 = tmp2.join("vectors").join("chain").join("v6").join("authored");
        fs::create_dir_all(&vdir2).expect("create temp dir");
        let fpath2 = vdir2.join("Gate.v6.json");
        fs::write(&fpath2, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad2 = chain_path_guard(&tmp2, &[fpath2]);
        let _ = fs::remove_dir_all(&tmp2);
        assert_eq!(bad2, 0, "fork_vote_gate under chain/v6/authored/ must PASS chain_path_guard");
    }

    /// Voting accept arm: a well-formed voting entry under chain/v5/captured/ passes chain_path_guard (bad==0).
    #[test]
    fn chain_path_guard_voting_under_v5_passes() {
        let tmp = std::env::temp_dir().join(format!("santa-chain-v5voting-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("v5").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let doc = json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "voting-under-v5",
                "source": "testnet:x",
                "kind": "voting",
                "settings": { "voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32 },
                "payload": {
                    "boundary_height": 256,
                    "current_parameters": { "table": { "1": 1000000 } },
                    "vote_stream": [],
                    "boundary_votes": "000000",
                    "proposed_update": "0000"
                },
                "expected": {
                    "parameters": { "table": { "1": 1000000 } },
                    "activated_update": "0000"
                }
            }]
        });
        let fpath = vdir.join("Voting.v5.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert_eq!(bad, 0, "voting under chain/v5/captured/ must PASS chain_path_guard (bad==0)");
    }

    /// recalculation-point rule: a retargeting entry whose target_height is NOT a recalculation
    /// point fires [WRONG].
    #[test]
    fn chain_path_guard_midepoch_target_fires_wrong() {
        let tmp = std::env::temp_dir().join(format!("santa-chain-midepoch-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");
        // epoch_length=128 => recalc when (T-1)%128==0, i.e. T=129,257,...
        // target_height=393602 => (393602-1)%128 = 393601%128 = 1 != 0 => NOT a recalc point
        let doc = json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "midepoch-target",
                "source": "testnet:x",
                "kind": "retargeting",
                "settings": {
                    "epoch_length": 128,
                    "use_last_epochs": 8,
                    "block_interval_ms": 45000,
                    "initial_nbits": 16842752
                },
                "payload": {
                    "target_height": 393602,
                    "anchor_headers": [
                        { "height": 393473, "nBits": 84128203, "timestamp": 1781000000000i64, "parentId": "0000000000000000000000000000000000000000000000000000000000000000aa" }
                    ]
                },
                "expected": { "nbits": 84150434 }
            }]
        });
        let fpath = vdir.join("Retargeting.midepoch.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "mid-epoch target_height must fire [WRONG] (recalculation-point rule)");
    }

    /// chain_actuals_guards returns 0 failures against the real schema.
    #[test]
    fn chain_actuals_guards_all_pass() {
        let schema = load(&schema_dir().join("santa-chain.actuals.schema.json"));
        let v = jsonschema::validator_for(&schema).expect("chain actuals schema invalid");
        let bad = chain_actuals_guards(&v);
        assert_eq!(bad, 0, "chain_actuals_guards should report 0 failures");
    }

    fn chain_act_validator() -> Validator {
        let schema = load(&schema_dir().join("santa-chain.actuals.schema.json"));
        jsonschema::validator_for(&schema).expect("chain actuals schema invalid")
    }

    /// Actuals: panicked without note must be rejected (contract §3: note required with panicked).
    #[test]
    fn chain_actuals_panicked_without_note_rejected() {
        let v = chain_act_validator();
        let doc = json!({"r#0": {"nbits": null, "error": "panicked"}});
        assert!(!v.is_valid(&doc), "panicked without note must be rejected by chain actuals schema");
    }

    /// Actuals: value field non-null with non-null error must be rejected (contract §3: value null iff error non-null).
    #[test]
    fn chain_actuals_value_with_error_rejected() {
        let v = chain_act_validator();
        // nbits non-null while error is "errored"
        let doc = json!({"r#0": {"nbits": 84150434, "error": "errored"}});
        assert!(!v.is_valid(&doc), "nbits non-null with error:errored must be rejected by chain actuals schema");
        // parameters non-null while error is "not-implemented"
        let doc2 = json!({"v#0": {"parameters": {"table": {"1": 1000000}}, "activated_update": "0000", "error": "not-implemented"}});
        assert!(!v.is_valid(&doc2), "parameters/activated_update non-null with error:not-implemented must be rejected");
    }

    /// Actuals: empty file (zero entries) must be rejected (contract §3: minProperties 1).
    #[test]
    fn chain_actuals_empty_file_rejected() {
        let v = chain_act_validator();
        let doc = json!({});
        assert!(!v.is_valid(&doc), "empty actuals file must be rejected (minProperties 1)");
    }

    /// EIP-37 L-branch guard: eip37 pair present, T >= eip37_activation_height, (T-1) % eip37_epoch_length == 0
    /// but (T-1) % epoch_length != 0 => PASSES (eip37 L governs).
    /// Mirrored case below activation => WRONG (eip37 arm not active, classic L governs and (T-1)%L != 0).
    #[test]
    fn chain_path_guard_eip37_governing_length() {
        let tmp = std::env::temp_dir().join(format!("santa-chain-eip37-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");

        // Constants: T=844929, eip37_activation_height=844673, eip37_epoch_length=128, epoch_length=1024.
        // (T-1)=844928: 844928=128·6601 ⇒ eip37-L hit (eip37 arm governs since T≥844673).
        // 844928%1024=128 ≠ 0 ⇒ classic-L miss (not a classic recalculation point).
        let doc_governs = json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "eip37-governs",
                "source": "testnet:x",
                "kind": "retargeting",
                "settings": {
                    "epoch_length": 1024,
                    "use_last_epochs": 8,
                    "block_interval_ms": 120000,
                    "initial_nbits": 16842752,
                    "eip37_activation_height": 844673,
                    "eip37_epoch_length": 128
                },
                "payload": {
                    "target_height": 844929,
                    "anchor_headers": [
                        { "height": 844801, "nBits": 83934920, "timestamp": 1781063957902i64, "parentId": "0000000000000000000000000000000000000000000000000000000000000000aa" }
                    ]
                },
                "expected": { "nbits": 84150434 }
            }]
        });
        let fpath = vdir.join("Retargeting.eip37.json");
        fs::write(&fpath, serde_json::to_string(&doc_governs).unwrap()).expect("write temp vector");
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert_eq!(bad, 0, "eip37 arm governs: (T-1)%eip37_epoch_length==0 must PASS chain_path_guard");

        // Case 2: below activation — eip37 arm does NOT govern; classic epoch_length applies.
        // T = 844929 same target but now T < eip37_activation_height: change activation to 900000.
        // (844929-1) % 1024 = 128 != 0 => NOT a classic recalc point => must fire WRONG.
        let tmp2 = std::env::temp_dir().join(format!("santa-chain-eip37b-{}", std::process::id()));
        let vdir2 = tmp2.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir2).expect("create temp dir");
        let doc_below = json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "eip37-below-activation",
                "source": "testnet:x",
                "kind": "retargeting",
                "settings": {
                    "epoch_length": 1024,
                    "use_last_epochs": 8,
                    "block_interval_ms": 120000,
                    "initial_nbits": 16842752,
                    "eip37_activation_height": 900000,
                    "eip37_epoch_length": 128
                },
                "payload": {
                    "target_height": 844929,
                    "anchor_headers": [
                        { "height": 843905, "nBits": 83934920, "timestamp": 1781063957902i64, "parentId": "0000000000000000000000000000000000000000000000000000000000000000aa" }
                    ]
                },
                "expected": { "nbits": 84150434 }
            }]
        });
        let fpath2 = vdir2.join("Retargeting.eip37below.json");
        fs::write(&fpath2, serde_json::to_string(&doc_below).unwrap()).expect("write temp vector");
        let bad2 = chain_path_guard(&tmp2, &[fpath2]);
        let _ = fs::remove_dir_all(&tmp2);
        assert!(bad2 > 0, "eip37 below activation: classic epoch_length governs, (T-1)%epoch_length!=0 must fire [WRONG]");

        // Case 3: T == eip37_activation_height exactly (the boundary itself). eip37 arm governs
        // since T >= activation_height; (T-1)%eip37_epoch_length == 0 must PASS.
        // T=844929, act=844929, eip37_l=128, epoch_l=1024: same arithmetic as case 1.
        let tmp3 = std::env::temp_dir().join(format!("santa-chain-eip37c-{}", std::process::id()));
        let vdir3 = tmp3.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir3).expect("create temp dir");
        let doc_exact = json!({
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "eip37-exact-boundary",
                "source": "testnet:x",
                "kind": "retargeting",
                "settings": {
                    "epoch_length": 1024,
                    "use_last_epochs": 8,
                    "block_interval_ms": 120000,
                    "initial_nbits": 16842752,
                    "eip37_activation_height": 844929,
                    "eip37_epoch_length": 128
                },
                "payload": {
                    "target_height": 844929,
                    "anchor_headers": [
                        { "height": 844801, "nBits": 83934920, "timestamp": 1781063957902i64, "parentId": "0000000000000000000000000000000000000000000000000000000000000000aa" }
                    ]
                },
                "expected": { "nbits": 84150434 }
            }]
        });
        let fpath3 = vdir3.join("Retargeting.eip37exact.json");
        fs::write(&fpath3, serde_json::to_string(&doc_exact).unwrap()).expect("write temp vector");
        let bad3 = chain_path_guard(&tmp3, &[fpath3]);
        let _ = fs::remove_dir_all(&tmp3);
        assert_eq!(bad3, 0, "T == eip37_activation_height: eip37 arm governs, (T-1)%eip37_epoch_length==0 must PASS");
    }

    /// Guard zero-division hardening: governing L == 0 fires [WRONG], never panics.
    /// (epoch_length = 0 is schema-invalid and won't appear in committed vectors, but the
    /// guard must not panic if handed such input — it must print [WRONG] and return non-zero.)
    #[test]
    fn chain_path_guard_zero_epoch_length_fires_wrong_not_panic() {
        let tmp = std::env::temp_dir().join(format!("santa-chain-zerol-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("chain").join("any").join("captured");
        fs::create_dir_all(&vdir).expect("create temp dir");
        // Construct a JSON bypassing the schema (write raw) with epoch_length = 0.
        // The guard reads settings["epoch_length"].as_i64() — if it returns 0 we'd divide by zero.
        // The vector schema rejects minimum:1, so this can't come from a blessed vector — but the
        // guard must not panic on such input regardless.
        let raw = r#"{
            "schema": "santa-chain/v1",
            "blessed_by": "test",
            "entries": [{
                "name": "zero-epoch",
                "source": "testnet:x",
                "kind": "retargeting",
                "settings": {
                    "epoch_length": 0,
                    "use_last_epochs": 8,
                    "block_interval_ms": 45000,
                    "initial_nbits": 16842752
                },
                "payload": {
                    "target_height": 1,
                    "anchor_headers": [
                        { "height": 1, "nBits": 16842752, "timestamp": 1000000000000, "parentId": "0000000000000000000000000000000000000000000000000000000000000000aa" }
                    ]
                },
                "expected": { "nbits": 16842752 }
            }]
        }"#;
        let fpath = vdir.join("Retargeting.zero.json");
        fs::write(&fpath, raw).expect("write temp vector");
        // This must not panic — it should just return > 0 (fires [WRONG] or skips via None from as_i64()).
        let bad = chain_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        // epoch_length=0: settings["epoch_length"].as_i64() returns Some(0).
        // governing_l will be 0. (t-1) % 0 would panic in Rust. The guard must handle this.
        // The test verifies: no panic (test completes), and bad > 0 (fires [WRONG]).
        assert!(bad > 0, "epoch_length=0 must fire [WRONG] without panicking");
    }

    #[test]
    fn chain_voting_reject_form_expected_passes() {
        let v = chain_vec_validator();
        let doc = serde_json::json!({
            "schema": "santa-chain/v1",
            "blessed_by": "jvm:ergo-core-6.0.2.1-chain-model",
            "entries": [{
                "name": "hostile-122-without-121",
                "source": "santa:hostile_tables:no-121",
                "kind": "voting",
                "settings": {"voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32},
                "payload": {
                    "boundary_height": 2688,
                    "current_parameters": {"table": {"1": 1250000, "122": 2560, "123": 4}},
                    "vote_stream": [{"height": 2560, "votes": "780000"}],
                    "boundary_votes": "000000",
                    "proposed_update": "0000"
                },
                "expected": {"error": "errored"},
                "diagnostic": {"note": "x", "oracle_note": "java.util.NoSuchElementException: key not found: 121"}
            }]
        });
        assert!(v.is_valid(&doc), "voting reject-form expected must validate");
    }

    #[test]
    fn chain_fork_vote_gate_forms() {
        let v = chain_vec_validator();
        let mut doc = serde_json::json!({
            "schema": "santa-chain/v1",
            "blessed_by": "jvm:ergo-core-6.0.2.1-chain-model",
            "entries": [{
                "name": "gate-pass",
                "source": "santa:fork_vote_gate:pass",
                "kind": "fork_vote_gate",
                "settings": {"voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32, "version2_activation_height": 417792},
                "payload": {
                    "height": 6655,
                    "header_votes": "780000",
                    "current_parameters": {"table": {"1": 1250000, "121": 3686, "122": 2560, "123": 4}}
                },
                "expected": {"valid": true}
            }]
        });
        assert!(v.is_valid(&doc), "gate accept form (valid bool) must validate");
        doc["entries"][0]["expected"] = serde_json::json!({"valid": false});
        assert!(v.is_valid(&doc), "valid:false is a first-class clean verdict");
        doc["entries"][0]["expected"] = serde_json::json!({"error": "errored"});
        assert!(v.is_valid(&doc), "the reject form applies to the gate kind too");
        // settings must carry all four fields for this kind (uniformity, enr ask):
        doc["entries"][0]["expected"] = serde_json::json!({"valid": true});
        doc["entries"][0]["settings"] = serde_json::json!({"voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32});
        assert!(!v.is_valid(&doc), "version2_activation_height is REQUIRED on gate entries");
        // boundary-free heights are legal (mid-epoch is the point) — but 0 is not:
        doc["entries"][0]["settings"] = serde_json::json!({"voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32, "version2_activation_height": 417792});
        doc["entries"][0]["payload"]["height"] = serde_json::json!(0);
        assert!(!v.is_valid(&doc), "height >= 1");
    }

    #[test]
    fn chain_voting_reject_form_with_value_keys_fails() {
        let v = chain_vec_validator();
        let mut doc = serde_json::json!({
            "schema": "santa-chain/v1",
            "blessed_by": "jvm:ergo-core-6.0.2.1-chain-model",
            "entries": [{
                "name": "bad",
                "source": "santa:hostile_tables:bad",
                "kind": "voting",
                "settings": {"voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32},
                "payload": {
                    "boundary_height": 2688,
                    "current_parameters": {"table": {"1": 1}},
                    "vote_stream": [],
                    "boundary_votes": "000000",
                    "proposed_update": "0000"
                },
                "expected": {"error": "errored", "activated_update": "0000"}
            }]
        });
        assert!(!v.is_valid(&doc), "error + value keys together must fail the oneOf");
        // and a retargeting reject form must fail (accept-only kind):
        doc["entries"][0]["kind"] = serde_json::json!("retargeting");
        doc["entries"][0]["settings"] = serde_json::json!({
            "epoch_length": 128, "use_last_epochs": 8, "block_interval_ms": 120000, "initial_nbits": 16842752});
        doc["entries"][0]["payload"] = serde_json::json!({
            "target_height": 129, "anchor_headers": [{}]});
        doc["entries"][0]["expected"] = serde_json::json!({"error": "errored"});
        assert!(!v.is_valid(&doc), "retargeting must stay accept-only");
    }

    #[test]
    fn chain_voting_reject_form_only_errored_token() {
        let v = chain_vec_validator();
        let mut doc = serde_json::json!({
            "schema": "santa-chain/v1",
            "blessed_by": "jvm:ergo-core-6.0.2.1-chain-model",
            "entries": [{
                "name": "bad-token",
                "source": "santa:hostile_tables:bad-token",
                "kind": "voting",
                "settings": {"voting_length": 128, "soft_fork_epochs": 32, "activation_epochs": 32},
                "payload": {
                    "boundary_height": 2688,
                    "current_parameters": {"table": {"1": 1}},
                    "vote_stream": [],
                    "boundary_votes": "000000",
                    "proposed_update": "0000"
                },
                "expected": {"error": "panicked"}
            }]
        });
        assert!(!v.is_valid(&doc), "panicked is never a blessable expected (const errored)");
        doc["entries"][0]["expected"] = serde_json::json!({});
        assert!(!v.is_valid(&doc), "empty expected matches neither oneOf branch");
    }

    // ── AuthDS tier tests ────────────────────────────────────────────────────────

    fn authds_vec_validator() -> Validator {
        let schema = load(&schema_dir().join("santa-authds.vector.schema.json"));
        jsonschema::validator_for(&schema).expect("authds vector schema invalid")
    }

    fn authds_act_validator() -> Validator {
        let schema = load(&schema_dir().join("santa-authds.actuals.schema.json"));
        jsonschema::validator_for(&schema).expect("authds actuals schema invalid")
    }

    /// Minimal well-formed avl_prove vector, correctly filed under authds/any/vendored/.
    fn minimal_authds_prove_vector() -> Value {
        json!({
            "schema": "santa-authds/v1",
            "op": "avl_prove",
            "blessed_by": "test",
            "entries": [{
                "name": "prove-test-001",
                "source": "ergots:packages/avltree/test/fixtures/prover/insert-basic",
                "kind": "avl_prove",
                "settings": { "key_length": 32, "value_length": null },
                "payload": {
                    "operations": [ { "tag": "Insert", "key_hex": "aa", "value_hex": "bb" } ],
                    "gen_proof_after": [0]
                },
                "expected": { "proofs": ["aabb"], "digests": ["ccdd"] }
            }]
        })
    }

    /// A well-formed avl_prove vector validates against the authds schema AND authds_path_guard
    /// returns 0 when filed under authds/any/vendored/.
    #[test]
    fn authds_path_guard_well_formed_passes() {
        let v = authds_vec_validator();
        let doc = minimal_authds_prove_vector();
        assert!(v.is_valid(&doc), "well-formed authds prove vector should pass schema");

        let tmp = std::env::temp_dir().join(format!("santa-authds-test-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("authds").join("any").join("vendored");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let fpath = vdir.join("AvlProve.test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = authds_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert_eq!(bad, 0, "well-formed prove vector under authds/any/vendored/ must have 0 path-guard failures");
    }

    /// Finding 2 regression: gen_proof_after / proofs / digests must be parallel arrays. A
    /// 2-index gen_proof_after with 3 proofs and 1 digest must fire [WRONG].
    #[test]
    fn authds_path_guard_prove_length_mismatch_fires_wrong() {
        let tmp = std::env::temp_dir().join(format!("santa-authds-lenmismatch-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("authds").join("any").join("vendored");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let mut doc = minimal_authds_prove_vector();
        doc["entries"][0]["payload"]["gen_proof_after"] = json!([0, 1]);
        doc["entries"][0]["expected"]["proofs"] = json!(["aabb", "ccdd", "eeff"]);
        doc["entries"][0]["expected"]["digests"] = json!(["1122"]);
        let fpath = vdir.join("AvlProve.lenmismatch.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = authds_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "gen_proof_after=2, proofs=3, digests=1 must fire [WRONG] (parallel-array invariant)");
    }

    /// authds vectors must be filed under version "any" — any other label fires [WRONG].
    #[test]
    fn authds_path_guard_bad_version_fires_wrong() {
        let tmp = std::env::temp_dir().join(format!("santa-authds-badver-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("authds").join("v1").join("vendored");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let doc = minimal_authds_prove_vector();
        let fpath = vdir.join("AvlProve.test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = authds_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "version other than 'any' must fire [WRONG]");
    }

    /// authds vectors must be filed under provenance "vendored" — any other label fires [WRONG].
    #[test]
    fn authds_path_guard_bad_provenance_fires_wrong() {
        let tmp = std::env::temp_dir().join(format!("santa-authds-badprov-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("authds").join("any").join("authored");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let doc = minimal_authds_prove_vector();
        let fpath = vdir.join("AvlProve.test.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = authds_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "provenance other than 'vendored' must fire [WRONG]");
    }

    /// Per-entry source must start with the ergots avltree fixture prefix.
    #[test]
    fn authds_path_guard_bad_source_prefix_fires_wrong() {
        let tmp = std::env::temp_dir().join(format!("santa-authds-badsrc-{}", std::process::id()));
        let vdir = tmp.join("vectors").join("authds").join("any").join("vendored");
        fs::create_dir_all(&vdir).expect("create temp dir");
        let mut doc = minimal_authds_prove_vector();
        doc["entries"][0]["source"] = json!("santa:hand-authored");
        let fpath = vdir.join("AvlProve.badsrc.json");
        fs::write(&fpath, serde_json::to_string(&doc).unwrap()).expect("write temp vector");
        let bad = authds_path_guard(&tmp, &[fpath]);
        let _ = fs::remove_dir_all(&tmp);
        assert!(bad > 0, "entry source not starting with the ergots avltree fixture prefix must fire [WRONG]");
    }

    /// authds_actuals_guards returns 0 failures against the real schema.
    #[test]
    fn authds_actuals_guards_all_pass() {
        let v = authds_act_validator();
        let bad = authds_actuals_guards(&v);
        assert_eq!(bad, 0, "authds_actuals_guards should report 0 failures");
    }

    /// Actuals: empty file (zero entries) must be rejected (contract: minProperties 1).
    #[test]
    fn authds_actuals_empty_file_rejected() {
        let v = authds_act_validator();
        let doc = json!({});
        assert!(!v.is_valid(&doc), "empty actuals file must be rejected (minProperties 1)");
    }

    /// Actuals: panicked without note must be rejected.
    #[test]
    fn authds_actuals_panicked_without_note_rejected() {
        let v = authds_act_validator();
        let doc = json!({"p#0": {"error": "panicked"}});
        assert!(!v.is_valid(&doc), "panicked without note must be rejected by authds actuals schema");
    }

    /// Actuals: a non-null error must not carry verdict field values (proofs/digests here).
    #[test]
    fn authds_actuals_errored_with_verdict_fields_rejected() {
        let v = authds_act_validator();
        let doc = json!({"p#0": {"error": "errored", "proofs": ["aa"], "digests": ["bb"]}});
        assert!(!v.is_valid(&doc), "errored row carrying non-null proofs/digests must be rejected");
    }

    /// Actuals: error null with zero verdict fields present (no shape satisfied) must be rejected.
    #[test]
    fn authds_actuals_null_error_missing_verdict_rejected() {
        let v = authds_act_validator();
        let doc = json!({"p#0": {"error": null}});
        assert!(!v.is_valid(&doc), "error:null with no verdict fields must be rejected (no verdict produced)");
    }

    /// Actuals: error null carrying only proof-shaped fields (verify's new_digest_hex key entirely
    /// absent) must be rejected — the missing-key vs. present-as-null distinction is load-bearing.
    #[test]
    fn authds_actuals_verify_missing_new_digest_hex_key_rejected() {
        let v = authds_act_validator();
        let doc = json!({"v#0": {"error": null, "proof_accepted": true, "results": []}});
        assert!(!v.is_valid(&doc), "avl_verify verdict missing the new_digest_hex key entirely must be rejected");
    }

    /// Actuals: new_digest_hex present as null (poisoned verifier / rejected proof) is a legitimate
    /// value, not an absent field — must be accepted alongside a full verdict.
    #[test]
    fn authds_actuals_verify_null_digest_is_legitimate_value() {
        let v = authds_act_validator();
        let doc = json!({"v#0": {"error": null, "proof_accepted": false, "results": [], "new_digest_hex": null}});
        assert!(v.is_valid(&doc), "new_digest_hex: null with proof_accepted/results present is a legitimate avl_verify verdict");
    }
}
