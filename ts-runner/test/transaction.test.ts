import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { runTransactionVector } from '../src/runner'

const here = path.dirname(fileURLToPath(import.meta.url))
const loadVector = (
  rel: string,
): { schema: string; entries: { name: string }[] } =>
  JSON.parse(readFileSync(path.resolve(here, '../../vectors/transaction', rel), 'utf8'))

describe('runTransactionVector — santa-transaction/v1 → not-implemented (growth ledger)', () => {
  it('keystone seed: every entry → {valid:null, cost:null, error:"not-implemented"} (ergots lacks aggregate tx validation)', () => {
    const vec = loadVector('v6/captured/bigint-downcast-2666.json')
    const actuals = runTransactionVector(vec)
    // totality: one outcome per entry, keyed by name, none omitted
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ valid: null, cost: null, error: 'not-implemented' })
    }
  })

  it('inline minimal vector: every entry → not-implemented regardless of entry shape', () => {
    const vec = {
      schema: 'santa-transaction/v1',
      entries: [
        { name: 'entry-a' },
        { name: 'entry-b' },
      ],
    }
    const actuals = runTransactionVector(vec as { schema: string; entries: { name: string }[] })
    expect(Object.keys(actuals)).toEqual(['entry-a', 'entry-b'])
    expect(actuals['entry-a']).toEqual({ valid: null, cost: null, error: 'not-implemented' })
    expect(actuals['entry-b']).toEqual({ valid: null, cost: null, error: 'not-implemented' })
  })
})
