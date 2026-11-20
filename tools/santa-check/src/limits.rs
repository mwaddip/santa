//! A bounded vector file's resource budget (`limits`) and the ceilings over it. See runner-integration.md §3a.
use serde_json::Value;

/// The most memory a bounded file may declare (MiB). conform's whole-run data limit is twice this, so a runner's
/// backstop for a bounded child fits beneath it.
pub const MEMORY_CEILING_MB: u64 = 4096;
/// The most wall-clock time a bounded file may declare (s).
pub const WALL_CEILING_S: u64 = 60;

/// A bounded file's budget: memory in MiB and wall-clock time in seconds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    pub memory_mb: u64,
    pub wall_s: u64,
}

/// Ok(None): no top-level `limits` (an ordinary file). Ok(Some): a valid bounded file. Err: what is wrong with it.
pub fn check_bounded(doc: &Value) -> Result<Option<Limits>, String> {
    let Some(l) = doc.get("limits") else { return Ok(None) };
    let obj = l.as_object().ok_or_else(|| format!("limits is not an object: {l}"))?;
    if let Some(k) = obj.keys().find(|k| *k != "memory_mb" && *k != "wall_s") {
        return Err(format!("limits has an unknown field {k:?}"));
    }
    let field = |k: &str, ceiling: u64| -> Result<u64, String> {
        let v = obj.get(k).ok_or_else(|| format!("limits.{k} is missing"))?;
        let n = v.as_u64().ok_or_else(|| format!("limits.{k} is not a whole number: {v}"))?;
        if n == 0 || n > ceiling {
            return Err(format!("limits.{k} = {n} is outside 1..={ceiling}"));
        }
        Ok(n)
    };
    let limits = Limits { memory_mb: field("memory_mb", MEMORY_CEILING_MB)?, wall_s: field("wall_s", WALL_CEILING_S)? };
    let n = doc.get("entries").and_then(Value::as_array).map_or(0, Vec::len);
    if n != 1 {
        return Err(format!("a bounded file holds exactly one entry, this one {n}"));
    }
    Ok(Some(limits))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn bounded(limits: serde_json::Value, entries: usize) -> serde_json::Value {
        json!({"limits": limits, "entries": vec![json!({}); entries]})
    }

    #[test]
    fn absent_is_ordinary() {
        assert_eq!(check_bounded(&json!({"entries": [{}, {}]})), Ok(None));
    }

    #[test]
    fn default_budget_one_entry() {
        assert_eq!(
            check_bounded(&bounded(json!({"memory_mb": 1024, "wall_s": 10}), 1)),
            Ok(Some(Limits { memory_mb: 1024, wall_s: 10 }))
        );
    }

    #[test]
    fn bounded_needs_exactly_one_entry() {
        let two = check_bounded(&bounded(json!({"memory_mb": 1024, "wall_s": 10}), 2));
        assert!(two.as_ref().is_err_and(|e| e.contains("exactly one entry")), "{two:?}");
        assert!(check_bounded(&bounded(json!({"memory_mb": 1024, "wall_s": 10}), 0)).is_err());
    }

    #[test]
    fn ceilings() {
        assert!(check_bounded(&bounded(json!({"memory_mb": 4096, "wall_s": 10}), 1)).is_ok());
        let mem = check_bounded(&bounded(json!({"memory_mb": 4097, "wall_s": 10}), 1));
        assert!(mem.as_ref().is_err_and(|e| e.contains("4096")), "{mem:?}");
        assert!(check_bounded(&bounded(json!({"memory_mb": 1024, "wall_s": 60}), 1)).is_ok());
        let wall = check_bounded(&bounded(json!({"memory_mb": 1024, "wall_s": 61}), 1));
        assert!(wall.as_ref().is_err_and(|e| e.contains("60")), "{wall:?}");
    }

    #[test]
    fn malformed() {
        for l in [
            json!({"memory_mb": 0, "wall_s": 10}),
            json!({"memory_mb": 1024}),
            json!({"memory_mb": 1024, "wall_s": 10, "cpu": 1}),
            json!({"memory_mb": 1.5, "wall_s": 10}),
            json!({"memory_mb": "1024", "wall_s": 10}),
            json!(5),
        ] {
            assert!(check_bounded(&bounded(l.clone(), 1)).is_err(), "accepted {l}");
        }
    }
}
