# comment.jq — delta.json (delta.jq's output) as markdown: SANTA's PR check (pr-check/README.md), copied into an
# implementation's repository and run by its own workflow.
#
#   jq -r -f comment.jq --argjson cap 50   delta.json > comment.md     # the comment: at most 50 files per section
#   jq -r -f comment.jq --argjson cap null delta.json >> "$GITHUB_STEP_SUMMARY"   # the job summary: every file, then
#                                                                                  # every entry with its answers
#
# The layout is SANTA's santa-delta's: the counts, newly red and now green one row per vector file (the largest
# first), red another way folded, still red counted. Every cell is escaped, so a name cannot break the table or
# open a tag. The comment is cut at 60,000 characters (GitHub's limit is 65,536), the job summary at 1,000,000.

def esc: gsub("&"; "&amp;") | gsub("<"; "&lt;") | gsub(">"; "&gt;") | gsub("\""; "&quot;") | gsub("\\|"; "&#124;")
  | gsub("[\n\r]"; " ");
def code: "<code>\(esc)</code>";
def brief: if type == "string" then . else tojson end;
def plural($one; $many): "\(.) \(if . == 1 then $one else $many end)";
def short: if . == null or . == "" then "—" else "`\(.[0:7])`" end;
def dimset: keys | join("+");
def side_text: to_entries | sort_by(.key)
  | map("\(.key | esc): expected \(.value.expected | brief | code), got \(.value.got | brief | code)") | join("; ");

# a section's key in delta.json, and its name
def field($s): {newly: "newly_red", green: "now_green", changed: "changed"}[$s];
def title($s): {newly: "🪨 Newly red", green: "🎁 Now green", changed: "↔ Red another way"}[$s];

# a section's changes, one row per vector file: the largest first, its split (by dimension, or "base → head") too
def rows($s):
  group_by([.slice, .file])
  | map({slice: .[0].slice, file: .[0].file, entries: length,
         split: ([.[] | if $s == "newly" then (.head | keys[]) elif $s == "green" then (.base | keys[])
                        else "\(.base | dimset) → \(.head | dimset)" end]
                 | group_by(.) | map({k: .[0], n: length}) | sort_by(-.n, .k))})
  | sort_by(-.entries, .slice, .file);

def heading($d; $s):
  ($d[field($s)]) as $ch
  | "\(title($s)): \($ch | length | plural("entry"; "entries")) in \($ch | rows($s) | length | plural("file"; "files"))";

# a section's file table, at most $cap rows (null: all), the rest summed in one line
def table($s; $cap):
  rows($s) as $rs
  | (if $cap == null then ($rs | length) else $cap end) as $n
  | "| slice | file | entries | \(if $s == "changed" then "change" else "dimensions" end) |\n|---|---|---:|---|\n"
    + ([$rs[0:$n][] | "| \(.slice | code) | \(.file | code) | \(.entries) | \([.split[] | "\(.k | esc) \(.n)"] | join(" · ")) |\n"]
       | join(""))
    + (if ($rs | length) > $n then
         $rs[$n:] as $rest
         | "\n_… \($rest | length) more \(if ($rest | length) == 1 then "file" else "files" end) (\([$rest[].entries] | add | plural("entry"; "entries"))): see the job summary._\n"
       else "" end);

def files_table($title; $files):
  if ($files | length) == 0 then ""
  else "### \($title)\n\n| file |\n|---|\n" + ([$files[] | "| \(code) |\n"] | join("")) + "\n" end;

def block($cap):
  . as $d
  | "## 🎅 SANTA · \($d.label | esc)\n\nbase \($d.base_sha | short) → head \($d.head_sha | short)\n\n"
    + (if $d.outcome != "compared" then "⚠️ No delta: \($d.reason | esc)\n\n"
       else
         (if $d.scope_changed then
            "**Scope changed:** base \($d.scope_changed.base | code) → head \($d.scope_changed.head | code)\n\n"
          else "" end)
         + (([$d.newly_red, $d.now_green, $d.changed] | map(length) | add) > 0) as $moved
         | (($d.files.only_base + $d.files.only_head | length) > 0) as $files_moved
         | if ($moved | not) and ($files_moved | not) then
             "No change: \($d.still_red | plural("entry"; "entries")) still red, as at base.\n\n"
           else
             "| 🪨 newly red | 🎁 now green | ↔ red another way | still red |\n|---:|---:|---:|---:|\n"
             + "| \($d.newly_red | length) | \($d.now_green | length) | \($d.changed | length) | \($d.still_red) |\n\n"
             + ([("newly", "green") as $s | select(($d[field($s)] | length) > 0)
                 | "### \(heading($d; $s))\n\n\($d[field($s)] | table($s; $cap))\n"] | join(""))
             + files_table("Graded at head only (not at base)"; $d.files.only_head)
             + files_table("Graded at base only (not at head)"; $d.files.only_base)
             + (if ($d.changed | length) > 0 then
                  "<details><summary>\(heading($d; "changed"))</summary>\n\n\($d.changed | table("changed"; $cap))\n</details>\n\n"
                else "" end)
           end
       end);

# the job summary's tail: every entry of a section, grouped by file, with its answers
def per_entry:
  . as $d
  | [("newly", "changed", "green") as $s
     | ($d[field($s)]) as $ch
     | select(($ch | length) > 0)
     | "### \(heading($d; $s)), per entry\n\n"
       + (reduce $ch[] as $c ({file: null, out: []};
            (if .file != [$c.slice, $c.file] then
               .out += ["\n**\($c.slice | code) · \($c.file | code)**\n\n"] | .file = [$c.slice, $c.file]
             else . end)
            | .out += ["- \($c.entry | code): \(if $s == "newly" then ($c.head | side_text)
                                               elif $s == "green" then "was \($c.base | side_text)"
                                               else "base \($c.base | side_text) → head \($c.head | side_text)" end)\n"])
          | .out | join(""))
       + "\n"]
  | join("");

# the markdown cut at whole lines to $limit characters, the cut line last
def cut($limit; $line):
  if length <= $limit then .
  else
    split("\n") as $lines
    | [foreach $lines[] as $l (0; . + ($l | length) + 1)] as $ends
    | ([$ends | to_entries[] | select(.value + ($line | length) + 2 > $limit) | .key] | first // ($lines | length)) as $n
    | ($lines[0:$n] | join("\n")) + "\n\n" + $line
  end;

(block($cap) + (if $cap == null and .outcome == "compared" then per_entry else "" end))
| if $cap == null then cut(1000000; "_… cut at 1,000,000 characters: every entry is in delta.json._")
  else cut(60000; "_… cut at 60,000 characters: see the job summary._") end
| sub("\n+$"; "")
