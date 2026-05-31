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
const files = readdirSync(vectorsDir).filter((f) => f.endsWith('.json')).sort()

// Reuse the frozen actuals schema as a conformance oracle (§3 postcondition).
const ajv = new Ajv2020({ strict: false })
ajv.addSchema(JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.vector.schema.json'), 'utf8')))
const validateActuals = ajv.compile(JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.actuals.schema.json'), 'utf8')))

// ---- Run the whole committed corpus once. ----
let total = 0, covered = 0, nice = 0, abstainedV6 = 0, abstainedNotImpl = 0
const reprDivergences: string[] = []
const valueCoal: string[] = []      // value/error mismatch — a Dasher bug or a NEW ergots value divergence (must be empty)
const costDivergences: string[] = [] // value matches, cost differs — the known ergots AddToEnvironment(5)/lambda cost bug
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
console.log(`  covered ${covered} = nice ${nice} + cost-divergent ${costDivergences.length}`)
console.log(`  abstain ${abstainedV6 + abstainedNotImpl} = v6 UnsignedBigInt ${abstainedV6} + not-implemented(v6) ${abstainedNotImpl}`)
console.log(`  DIVERGENCES → route to ergots: ${costDivergences.length} cost (AddToEnvironment lambda bug) + ${reprDivergences.length} repr (Header ts cap)`)
if (costDivergences.length) console.log(`  cost-divergences:\n    ${costDivergences.join('\n    ')}`)
if (reprDivergences.length) console.log(`  repr-divergences:\n    ${reprDivergences.join('\n    ')}`)

describe('Dasher e2e conformance vs blessed corpus', () => {
  it('Dasher correctness: every covered entry matches the JVM on VALUE (no value/error divergence)', () => {
    // A value/error mismatch would be a Dasher bug OR a NEW ergots value divergence — never silent.
    expect(valueCoal, `value coal:\n${valueCoal.join('\n')}`).toEqual([])
  })

  it('every actuals object validates against the frozen actuals schema (§3)', () => {
    expect(schemaErrors).toEqual([])
  })

  it('full accounting: covered + abstain + repr == every entry (no silent drops)', () => {
    expect(covered + abstainedV6 + abstainedNotImpl + reprDivergences.length).toBe(total)
  })

  // ---- Known ergots divergences: routed to ergots, pinned here so a NEW divergence fails the gate. ----
  // AddToEnvironment lambda-cost bug RESOLVED 2026-06-01 (sigma-rust d59d8d9f + ergots 6171d32,
  // ergots dist rebuilt) — was 6, now 0. Kept as a regression guard: a new cost divergence fails here.
  it('no cost divergences — AddToEnvironment lambda-cost bug fixed (regression guard)', () => {
    expect(costDivergences).toHaveLength(0)
  })
  // When ergots represents Header.timestamp as a full Long, these 2 become covered → update to 0.
  it('representation divergences are exactly the 2 known Header-timestamp entries (ergots cap bug)', () => {
    expect(reprDivergences).toHaveLength(2)
  })
})
