import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import Ajv2020 from 'ajv/dist/2020'
import { runVector } from '../src/runner'
import { structuralEqual } from './_match'

const here = path.dirname(fileURLToPath(import.meta.url))
const vectorsDir = path.resolve(here, '../../vectors/eval')
const schemaDir = path.resolve(here, '../../schema')
function walkVectors(dir: string, base = ''): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((d) => {
    const rel = base ? `${base}/${d.name}` : d.name
    if (d.isDirectory()) return walkVectors(path.join(dir, d.name), rel)
    return d.name.endsWith('.json') ? [rel] : []
  })
}
const files = walkVectors(vectorsDir).sort()

// Reuse the frozen actuals schema as a conformance oracle (§3 postcondition).
const ajv = new Ajv2020({ strict: false })
ajv.addSchema(JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.vector.schema.json'), 'utf8')))
const validateActuals = ajv.compile(JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.actuals.schema.json'), 'utf8')))

// ---- Run the whole committed corpus once. ----
let total = 0, covered = 0, nice = 0, abstainedV6 = 0, abstainedNotImpl = 0
const reprDivergences: string[] = []
const valueCoal: string[] = []      // value/error mismatch — a Dasher bug or an ergots VALUE divergence (10 known v5: negation×4, substConstants×5, flatMap-empty×1; routed to ergots)
const costDivergences: string[] = [] // value matches, cost differs — 36 known v5 ergots COST-model gaps (flatMap/indexOf/NEQ/propBytes); the AddToEnvironment −5 is resolved
const schemaErrors: string[] = []

for (const file of files) {
  const doc = JSON.parse(readFileSync(path.join(vectorsDir, file), 'utf8'))
  const r = runVector(doc)
  total += doc.entries.length
  covered += Object.keys(r.actuals).length
  abstainedV6 += r.abstainedV6.length
  abstainedNotImpl += r.abstainedNotImpl.length
  for (const n of r.reprDivergences) reprDivergences.push(`${file}::${n}`)
  if (Object.keys(r.actuals).length > 0 && !validateActuals(r.actuals)) {
    schemaErrors.push(`${file}: ${JSON.stringify(validateActuals.errors)}`)
  }
  for (const e of doc.entries) {
    if (!(e.name in r.actuals)) continue
    const act = r.actuals[e.name] as { value: unknown; cost: number | null; error: string | null }
    const exp = e.expected as { value: unknown; cost: number | null; error: string | null }
    if (structuralEqual(act, exp)) { nice++; continue }
    if (act.error === null && exp.error === null && structuralEqual(act.value, exp.value) && act.cost !== exp.cost) {
      costDivergences.push(`${file} :: ${String(e.name).slice(0, 28)}  cost ${act.cost} vs ${exp.cost} (Δ${(act.cost ?? 0) - (exp.cost ?? 0)})`)
    } else {
      valueCoal.push(`${file} :: ${e.name}\n    actual:   ${JSON.stringify(act)}\n    expected: ${JSON.stringify(exp)}`)
    }
  }
}

console.log(`\n=== Dasher conformance (${files.length} files / ${total} entries) ===`)
console.log(`  covered ${covered} = nice ${nice} + value-divergent ${valueCoal.length} + cost-divergent ${costDivergences.length}`)
console.log(`  abstain ${abstainedV6 + abstainedNotImpl} = v6 UnsignedBigInt ${abstainedV6} + not-implemented(v6) ${abstainedNotImpl}`)
console.log(`  DIVERGENCES → route to ergots: ${valueCoal.length} value + ${costDivergences.length} cost + ${reprDivergences.length} repr (reported; ergots' concern to fix)`)
if (valueCoal.length) console.log(`  value-divergences:\n    ${valueCoal.join('\n    ')}`)
if (costDivergences.length) console.log(`  cost-divergences:\n    ${costDivergences.join('\n    ')}`)
if (reprDivergences.length) console.log(`  repr-divergences:\n    ${reprDivergences.join('\n    ')}`)

describe('Dasher e2e conformance vs blessed corpus', () => {
  // 10 known v5 ergots VALUE divergences (negation overflow ×4, substConstants ×5, flatMap empty-Coll
  // type ×1), routed to ergots (docs/findings/eval-jvm-vs-ergots.md). Recording the baseline so the gate
  // flags any CHANGE — a Dasher bug or new ergots divergence (count↑), or an ergots fix (count↓) — never
  // silent. The full list prints in the summary above. Drop the count as ergots fixes land.
  it('value divergences are exactly the 10 recorded v5 ergots bugs (count flags any change)', () => {
    expect(valueCoal, `value coal:\n${valueCoal.join('\n')}`).toHaveLength(10)
  })

  it('every actuals object validates against the frozen actuals schema (§3)', () => {
    expect(schemaErrors).toEqual([])
  })

  it('full accounting: covered + abstain + repr == every entry (no silent drops)', () => {
    expect(covered + abstainedV6 + abstainedNotImpl + reprDivergences.length).toBe(total)
  })

  // ---- Known ergots divergences: routed to ergots, recorded here so the gate flags any change. ----
  // 36 v5 ergots COST-model gaps (flatMap, indexOf, NEQ-nested, propBytes — ergots mostly under-charges;
  // distinct from the AddToEnvironment −5, which is resolved). Recorded as the baseline; a new or fixed
  // cost divergence changes the count and fails here (then re-baseline / un-pin).
  it('cost divergences are exactly the 36 recorded v5 ergots cost-model gaps (count flags any change)', () => {
    expect(costDivergences).toHaveLength(36)
  })
  // When ergots represents Header.timestamp as a full Long, these 2 become covered → update to 0.
  it('representation divergences are exactly the 2 known Header-timestamp entries (ergots cap bug)', () => {
    expect(reprDivergences).toHaveLength(2)
  })
})
