import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import Ajv2020 from 'ajv/dist/2020'
import { runVector } from '../src/runner'
import { structuralEqual } from './_match'

const here = path.dirname(fileURLToPath(import.meta.url))
// ergots is a v5/mainnet library — its conformance gate runs the v5 INPUT bucket only.
// v6 vectors are a different conformer's column (JVM) and ergots' future; selecting scope on
// the input side (which dirs we run) is *why* there is no "abstain" outcome (runner-contract §3).
const v5Dir = path.resolve(here, '../../vectors/eval/v5')
const schemaDir = path.resolve(here, '../../schema')
function walkVectors(dir: string, base = ''): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((d) => {
    const rel = base ? `${base}/${d.name}` : d.name
    if (d.isDirectory()) return walkVectors(path.join(dir, d.name), rel)
    return d.name.endsWith('.json') ? [rel] : []
  })
}
const files = walkVectors(v5Dir).sort()

// Reuse the frozen actuals schema as a conformance oracle (§3 postcondition).
const ajv = new Ajv2020({ strict: false })
ajv.addSchema(JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.vector.schema.json'), 'utf8')))
const validateActuals = ajv.compile(JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.actuals.schema.json'), 'utf8')))

// ---- Run the whole v5 corpus once. Every entry yields an actual (no omission); the
// comparator decides nice vs RED, and RED is categorized for routing — never hidden. ----
let total = 0, nice = 0
const notImplemented: string[] = []   // ergots lacks this v5 method (gap → route to ergots)
const unrepresentable: string[] = []  // ergots has the type but can't hold this value
const valueCoal: string[] = []        // value/error mismatch
const costDivergences: string[] = []  // value matches, cost differs
const schemaErrors: string[] = []

for (const file of files) {
  const doc = JSON.parse(readFileSync(path.join(v5Dir, file), 'utf8'))
  const actuals = runVector(doc)
  total += doc.entries.length
  if (!validateActuals(actuals)) schemaErrors.push(`${file}: ${JSON.stringify(validateActuals.errors)}`)
  for (const e of doc.entries) {
    const act = actuals[e.name] as { value: unknown; cost: number | null; error: string | null }
    const exp = e.expected as { value: unknown; cost: number | null; error: string | null }
    // Totality (§3) guarantees every entry has an actual; if a future regression drops one,
    // name the offending entry instead of throwing a bare TypeError on `act.error` below.
    if (!act) { valueCoal.push(`${file} :: ${e.name} — MISSING from actuals`); continue }
    if (structuralEqual(act, exp)) { nice++; continue }
    if (act.error === 'not-implemented') notImplemented.push(`${file} :: ${e.name}`)
    else if (act.error === 'unrepresentable') unrepresentable.push(`${file} :: ${e.name}`)
    else if (act.error === null && exp.error === null && structuralEqual(act.value, exp.value) && act.cost !== exp.cost) {
      costDivergences.push(`${file} :: ${String(e.name).slice(0, 28)}  cost ${act.cost} vs ${exp.cost} (Δ${(act.cost ?? 0) - (exp.cost ?? 0)})`)
    } else {
      valueCoal.push(`${file} :: ${e.name}\n    actual:   ${JSON.stringify(act)}\n    expected: ${JSON.stringify(exp)}`)
    }
  }
}

const red = notImplemented.length + unrepresentable.length + valueCoal.length + costDivergences.length
console.log(`\n=== Dasher conformance · v5 (${files.length} files / ${total} entries) ===`)
console.log(`  nice ${nice} · RED ${red} — every entry evaluated; no abstain`)
console.log(`  RED → route to ergots: ${valueCoal.length} value + ${costDivergences.length} cost + ${notImplemented.length} not-implemented + ${unrepresentable.length} unrepresentable`)
if (valueCoal.length) console.log(`  value:\n    ${valueCoal.join('\n    ')}`)
if (costDivergences.length) console.log(`  cost:\n    ${costDivergences.join('\n    ')}`)
if (notImplemented.length) console.log(`  not-implemented (v5 method gaps):\n    ${notImplemented.join('\n    ')}`)
if (unrepresentable.length) console.log(`  unrepresentable:\n    ${unrepresentable.join('\n    ')}`)

describe('Dasher e2e conformance vs blessed v5 corpus', () => {
  it('every actuals object validates against the frozen actuals schema (§3)', () => {
    expect(schemaErrors).toEqual([])
  })

  // Totality (runner-contract §3): every entry yields exactly one actual — none dropped/abstained.
  it('full accounting: nice + RED == every entry (no silent drops, no abstain)', () => {
    expect(nice + red).toBe(total)
  })

  // ---- Known ergots RED, recorded so the gate flags any CHANGE — a regression (count ↑) or an
  // ergots fix (count ↓, then re-baseline) — never hidden. Routed to ergots
  // (docs/findings/eval-jvm-vs-ergots.md, prompts/). This is regression-tracking, NOT
  // green-chasing: the suite's job is to surface divergences, so RED is the runner working. ----
  it('value divergences are exactly the 10 recorded v5 ergots bugs', () => {
    expect(valueCoal, `value coal:\n${valueCoal.join('\n')}`).toHaveLength(10)
  })
  it('cost divergences are exactly the 36 recorded v5 ergots cost-model gaps', () => {
    expect(costDivergences).toHaveLength(36)
  })
  it('not-implemented gaps are exactly the 15 recorded v5 methods ergots lacks', () => {
    expect(notImplemented, `not-implemented:\n${notImplemented.join('\n')}`).toHaveLength(15)
  })
  it('unrepresentable is empty in v5 (the 2 Header-ts cases live in v6, not run here)', () => {
    expect(unrepresentable).toHaveLength(0)
  })
})
