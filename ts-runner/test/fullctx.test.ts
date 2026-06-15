import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { runVector } from '../src/runner'
import { structuralEqual } from './_match'

// The santa-eval/v6-fullctx captured corpus: 62 real testnet inputs, each blessed by the JVM
// (sigma-state 6.0.3) to a value + cost. ergots was proven JVM-equivalent across the whole
// testnet v6 chain (the walker walk), so every one of these MUST eval to its blessed value AND
// cost — error null, no not-implemented. This is the acceptance criterion for the v6-fullctx arm:
// the 62 flip from coverage-gap (not-impl) to green.
const here = path.dirname(fileURLToPath(import.meta.url))
const capturedDir = path.resolve(here, '../../vectors/eval/v6/captured')
const files = readdirSync(capturedDir).filter((f) => f.endsWith('.json')).sort()

type Expected = { value: unknown; cost: number | null; error: string | null }
type Entry = { name: string; expected: Expected }
type Vector = { schema: string; entries: Entry[] }

describe('santa-eval/v6-fullctx — captured corpus evals to blessed value + cost', () => {
  it('the corpus is present (62 goldens)', () => {
    expect(files.length).toBe(62)
  })

  for (const file of files) {
    const doc = JSON.parse(readFileSync(path.join(capturedDir, file), 'utf8')) as Vector
    it(`${file}: every entry → blessed value + cost (error null)`, () => {
      const actuals = runVector(doc as never)
      for (const e of doc.entries) {
        const act = actuals[e.name] as { value: unknown; cost: number | null; error: string | null }
        const exp = e.expected
        // The blessed corpus is all-accept (real on-chain spends): expected.error is null.
        expect(act, `${e.name}: missing from actuals`).toBeDefined()
        expect(act.error, `${e.name}: error tag (note: ${(act as { note?: string }).note ?? ''})`).toBe(exp.error)
        expect(
          structuralEqual(act.value, exp.value),
          `${e.name}: value ${JSON.stringify(act.value)} != blessed ${JSON.stringify(exp.value)}`,
        ).toBe(true)
        expect(act.cost, `${e.name}: cost`).toBe(exp.cost)
      }
    })
  }
})
