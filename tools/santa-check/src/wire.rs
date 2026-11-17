//! Wire-file rules shared by validate and conform (runner-contract-wire §1).
use serde_json::Value;

/// The one kind a wire file's entries share, or why there is none (no entries; an entry without a kind; two kinds).
/// ./validate refuses such a file and conform stops on it, naming it; neither guesses a kind.
pub fn one_kind(doc: &Value) -> Result<String, String> {
    let entries = doc["entries"].as_array().map(Vec::as_slice).unwrap_or(&[]);
    let mut kinds: Vec<&str> = entries.iter().map(|e| e["kind"].as_str().unwrap_or("")).collect();
    kinds.sort_unstable();
    kinds.dedup();
    match kinds.as_slice() {
        [k] if !k.is_empty() => Ok(k.to_string()),
        [] => Err("no entries".to_string()),
        ks => Err(format!("a wire file holds one kind, this one {ks:?}")),
    }
}

#[cfg(test)]
mod tests {
    use super::one_kind;
    use serde_json::json;

    /// A wire file holds one kind (runner-contract-wire §1): conform selects by it, a file at a time.
    #[test]
    fn one_kind_flags_mixed_empty_and_missing() {
        let doc = |kinds: &[&str]| json!({ "entries": kinds.iter().map(|k| json!({ "kind": k })).collect::<Vec<_>>() });
        assert_eq!(one_kind(&doc(&["Box", "Box"])), Ok("Box".to_string()));
        assert!(one_kind(&doc(&["Box", "Transaction"])).is_err());
        assert!(one_kind(&doc(&[])).is_err());
        assert!(one_kind(&json!({ "entries": [{ "name": "no kind" }] })).is_err());
        assert!(one_kind(&json!({})).is_err());
    }
}
