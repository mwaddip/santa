# SANTA grading rules (`vectors/grading.json`)

> **How an entry is graded, as data.** Every way SANTA grades a runner's answer is written in
> [`vectors/grading.json`](../../vectors/grading.json), in the small language this document specifies. An
> implementation grades by interpreting that file, so a grading type SANTA adds needs no code from anyone; only a
> new building block of the language does. SANTA's own grader, `santa-check`, is such an interpreter, and
> [`oracle/verdicts-grading.json`](../../oracle/verdicts-grading.json) is the test every interpreter must pass.

## 1. The file

- **`vectors/grading.json`**, `"schema": "santa-grading/v1"`, checked against
  [`schema/santa-grading.schema.json`](../../schema/santa-grading.schema.json) by `./validate`.
- **`types`**: the grading types, by label.
- **`tiers`**: each tier's default label. A vector file is graded by its tier's default unless its envelope names
  another label, `"grading": "<label>"` (an optional key of every tier's vector schema). `./validate` checks that every
  tier has a default and that every label a file names exists.
- The labels today are the tiers' names (`eval`, `wire`, `transaction`, `block`, `chain`, `authds`, `nipopow`), each
  its tier's default.

## 2. Grading one entry

An entry is graded from four things: the vector entry (with its `expected`), the runner's actual for it, the vector
file's envelope, and whether the runner claims the cost dimension (`runner.json` `cost`). A **missing actual is graded
as the empty object `{}`**.

1. **The global outcomes come first**, in this order, whatever the type: an actual whose `error` is `"exceeded"` is
   **exceeded** (with its `limit`); `"panicked"` is **panicked**; `"not-implemented"` is **not-implemented**. All three
   are red. They are not in the file.
2. **The first branch whose `when` holds applies.** `when` is an object of `path: literal`; it holds when every path's
   value (`null` if absent) structurally equals its literal (§3). A branch without `when` always holds.
3. **Each dimension of the branch, in order**, is:
   - **n/a** (not graded, not counted) when a dimension it `requires` is not nice, or when it is `claimed` and the
     runner does not claim cost or the first comparison's `b` operand is `null`;
   - else **nice** when every comparison holds and, unless `"clean": false`, the actual's `error` is null or absent;
   - else **red**.
4. **No branch holds:** each dimension in the type's `unmatched` list is red; a type without `unmatched` is an error
   (the file does not cover the entry).

A dimension's `requires` may name only dimensions listed before it in the same branch. The defaults: `when` absent =
always; `requires` = `[]`; `claimed` = `false`; `clean` = `true`; `case_insensitive` = `false`; `unmatched` = `[]`.

## 3. Comparisons and operands

A comparison is `{ "a": <operand>, "b": <operand>, "case_insensitive": <bool> }`. It holds when the two operands are
**structurally equal**:

- objects regardless of key order, with identical key sets and equal values;
- arrays in order;
- numbers numerically (`5` equals `5.0`);
- strings exactly;
- `null` only `null`.

With `"case_insensitive": true` it holds when both operands are strings equal ignoring ASCII case; any other operand
fails it.

An operand is an object with exactly one of:

| Form | Value |
|---|---|
| `{"path": "<root>.<key>…"}` | the value at the path, `null` if absent |
| `{"lit": <JSON>}` | the literal |
| `{"first": ["<path>", …]}` | the first path whose value is present and not `null`, else `null` |
| `{"pluck": "<path>", "field": "<key>"}` | when the path's value is an array: an array of each element's `field` (`null` where absent); else `null` |

A path starts at a **root**: `actual`, `expected` (shorthand for `entry.expected`), `entry`, or `file` (the vector
file's envelope), followed by `.`-separated object keys.

## 4. The verdict

```json
{ "outcome": "graded", "dims": { "<dimension>": "nice" | "red" } }
{ "outcome": "exceeded", "limit": "memory" | "time" }
{ "outcome": "panicked" }
{ "outcome": "not-implemented" }
```

Only graded dimensions appear: an n/a one is not graded, so a verdict does not depend on which ungraded dimensions a
branch lists. The dimension names are the board's: `value`, `cost`, `reject`, `roundtrip`, `valid`, `post_digest`,
`proof`, `digest`, `accepted`, `results`, `interlinks`.

## 5. Strictness

An interpreter refuses to grade, never guesses, on: a schema version it does not know; an unknown key anywhere in the
file; an operand with no form or two; a path with an unknown root or an empty key; a `requires` of a later dimension; a
label that does not exist; a tier without a default; an entry no branch covers in a type without `unmatched`. A new
grading type built from these blocks is just data. A new building block raises the schema version, and an interpreter
that does not know it refuses, loudly, as a runner refuses a vector schema it was not written for
([runner-integration §3](runner-integration.md#3-the-entrypoint--santa-run)).

## 6. Conformance

[`oracle/verdicts-grading.json`](../../oracle/verdicts-grading.json) (`santa-oracle-grading/v1`) holds cases of
`(label, entry, file, actual, claims_cost) → verdict`, at least one per grading type; a `null` actual is a missing one,
an absent `file` is `{}`, an absent `claims_cost` is `false`. An interpreter is conformant when it reproduces every
verdict. `santa-check` runs it on every build of SANTA's tools; a runner checks its own interpreter against the oracle
of the SANTA checkout it grades with.
