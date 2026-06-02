import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import Ajv2020 from 'ajv/dist/2020'
import { runVector } from '../src/runner'
import { structuralEqual } from './_match'

const here = path.dirname(fileURLToPath(import.meta.url))
const evalDir = path.resolve(here, '../../vectors/eval')
const schemaDir = path.resolve(here, '../../schema')
// Dasher = ergots, a v5/mainnet library. Its manifest declares version v5, tier eval, and whether it
// claims the cost dimension. We grade only what it claims (cost gated on `cost`), and slice outcomes
// by provenance (spec vs authored) so each ledger is independent — adding authored vectors never
// perturbs the spec pins. Scope is input-side (which vectors we run); that is why there is no
// "abstain" (runner-contract §3).
const manifest = JSON.parse(readFileSync(path.resolve(here, '../../runners/dasher/runner.json'), 'utf8'))
const CLAIMS_COST: boolean = manifest.cost

function walkVectors(dir: string, base = ''): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((d) => {
    const rel = base ? `${base}/${d.name}` : d.name
    if (d.isDirectory()) return walkVectors(path.join(dir, d.name), rel)
    return d.name.endsWith('.json') ? [rel] : []
  })
}
const v5Root = path.join(evalDir, 'v5')
const files = walkVectors(v5Root).sort() // e.g. "spec/plus.json"; later "authored/<family>.json"

// Reuse the frozen actuals schema as a conformance oracle (§3 postcondition).
const ajv = new Ajv2020({ strict: false })
ajv.addSchema(JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.vector.schema.json'), 'utf8')))
const validateActuals = ajv.compile(JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.actuals.schema.json'), 'utf8')))

type Slice = {
  valueTotal: number; valueNice: number; valueCoal: string[]
  notImpl: string[]; unrepr: string[]
  costGraded: number; costNice: number; costCoal: string[]
  rejectTotal: number; rejectNice: number; rejectCoal: string[]
}
const blank = (): Slice => ({
  valueTotal: 0, valueNice: 0, valueCoal: [], notImpl: [], unrepr: [],
  costGraded: 0, costNice: 0, costCoal: [], rejectTotal: 0, rejectNice: 0, rejectCoal: [],
})
const slices: Record<string, Slice> = {}
const schemaErrors: string[] = []
const sliceOf = (file: string) => file.split('/')[0] // "spec" | "authored"
let total = 0

// ---- Run the v5 corpus once, accounting per provenance slice. Every entry yields exactly one
// verdict (totality §3). Coverage gaps (not-implemented / unrepresentable) take precedence over the
// accept/reject value classification — this mirrors tools/compare.grade so every comparator returns
// the same verdict (runner-contract §6). ----
for (const file of files) {
  const doc = JSON.parse(readFileSync(path.join(v5Root, file), 'utf8'))
  const actuals = runVector(doc)
  total += doc.entries.length
  if (!validateActuals(actuals)) schemaErrors.push(`${file}: ${JSON.stringify(validateActuals.errors)}`)
  const s = (slices[sliceOf(file)] ??= blank())
  for (const e of doc.entries) {
    const act = actuals[e.name] as { value: unknown; cost: number | null; error: string | null } | undefined
    const exp = e.expected as { value: unknown; cost: number | null; error: string | null }
    // coverage precedence: the runner didn't engage with the op, whatever the vector expected
    if (act && act.error === 'not-implemented') { s.notImpl.push(`${file} :: ${e.name}`); continue }
    if (act && act.error === 'unrepresentable') { s.unrepr.push(`${file} :: ${e.name}`); continue }
    if (exp.error === 'errored') { // reject vector — one verdict: did it reject identically?
      s.rejectTotal++
      if (act && act.error === 'errored') s.rejectNice++
      else s.rejectCoal.push(`${file} :: ${e.name} — expected errored, got ${JSON.stringify({ value: act?.value, error: act?.error })}`)
      continue
    }
    // accept vector — independent value + cost verdicts
    s.valueTotal++
    if (!act) { s.valueCoal.push(`${file} :: ${e.name} — MISSING from actuals`); continue }
    if (act.error === null && structuralEqual(act.value, exp.value)) {
      s.valueNice++
      if (CLAIMS_COST) { // cost graded only when the runner claims the cost dimension
        s.costGraded++
        if (act.cost === exp.cost) s.costNice++
        else s.costCoal.push(`${file} :: ${String(e.name).slice(0, 28)}  cost ${act.cost} vs ${exp.cost} (Δ${(act.cost ?? 0) - (exp.cost ?? 0)})`)
      }
    } else {
      s.valueCoal.push(`${file} :: ${e.name}\n    actual:   ${JSON.stringify(act)}\n    expected: ${JSON.stringify(exp)}`)
    }
  }
}

const spec = slices['spec'] ?? blank()
const redOf = (s: Slice) => s.valueCoal.length + s.notImpl.length + s.unrepr.length + s.costCoal.length + s.rejectCoal.length
const accounted = Object.values(slices).reduce((n, s) => n + s.valueTotal + s.notImpl.length + s.unrepr.length + s.rejectTotal, 0)
console.log(`\n=== Dasher conformance · v5 (${files.length} files / ${total} entries, cost=${CLAIMS_COST}) ===`)
for (const [name, s] of Object.entries(slices)) {
  console.log(`  [${name}] value ${s.valueNice}/${s.valueTotal} · cost ${s.costNice}/${s.costGraded}` +
    ` · not-impl ${s.notImpl.length} · unrepr ${s.unrepr.length} · reject ${s.rejectNice}/${s.rejectTotal} · RED ${redOf(s)}`)
}
if (spec.notImpl.length) console.log(`  spec not-implemented (v5 method gaps):\n    ${spec.notImpl.join('\n    ')}`)
if (spec.valueCoal.length) console.log(`  spec value:\n    ${spec.valueCoal.join('\n    ')}`)
if (spec.costCoal.length) console.log(`  spec cost:\n    ${spec.costCoal.join('\n    ')}`)
if (spec.rejectCoal.length) console.log(`  spec reject:\n    ${spec.rejectCoal.join('\n    ')}`)

describe('Dasher e2e conformance vs blessed v5 corpus (per-provenance slice)', () => {
  it('every actuals object validates against the actuals schema (§3)', () => {
    expect(schemaErrors).toEqual([])
  })

  // Totality (§3): every entry lands in exactly one bucket (coverage / reject / accept-value) — none
  // dropped, none double-counted.
  it('full accounting: every entry bucketed exactly once (no drops, no abstain)', () => {
    expect(accounted).toBe(total)
  })

  // ---- spec slice. ergots has LANDED every routed v5 fix, so Dasher v5 is now FULLY conformant
  // (1705/1705, RED 0): the 10 value + 36 cost divergences (a60bb12 coercion #1, ce555d3 reject #2,
  // 5e56367 substConstants, 8125612 empty-flatMap A3, 740af17 the 6 SANTA-found divergences, + cost
  // charges) AND the 27 not-implemented methods Coll.updated/updateMany/GroupElement.negate (35eac6b).
  // SANTA's box inputs are also re-blessed to ≥min (santa-rebless-min-box-value) so they're
  // consensus-valid. These pins are now a full-green REGRESSION guard — any count > 0 is a new
  // divergence/gap to surface, never hide (divergences are the deliverable). ----
  it('spec value divergences: 0 (ergots fixed the routed v5 value bugs)', () => {
    expect(spec.valueCoal, `value coal:\n${spec.valueCoal.join('\n')}`).toHaveLength(0)
  })
  it('spec cost divergences: 0 (ergots fixed the routed v5 cost-model gaps)', () => {
    expect(spec.costCoal, `cost coal:\n${spec.costCoal.join('\n')}`).toHaveLength(0)
  })
  it('spec not-implemented: 0 (Coll.updated/updateMany/GroupElement.negate implemented, ergots 35eac6b)', () => {
    expect(spec.notImpl, `not-impl:\n${spec.notImpl.join('\n')}`).toHaveLength(0)
  })
  it('spec unrepresentable: 0 (the Header-ts cases live in v6, not run here)', () => {
    expect(spec.unrepr).toHaveLength(0)
  })
  it('spec reject divergences: 0 — ergots rejects every input the JVM rejects', () => {
    expect(spec.rejectCoal, `reject:\n${spec.rejectCoal.join('\n')}`).toHaveLength(0)
  })
})
