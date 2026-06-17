import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { runTransactionVector } from '../src/runner'

const here = path.dirname(fileURLToPath(import.meta.url))
// Typed as runTransactionVector's own parameter (TxVector) so the bytes-anchored TxEntry shape
// (tx_bytes_hex + box/header hex) is enforced — the test loads a real captured vector.
const loadVector = (rel: string): Parameters<typeof runTransactionVector>[0] =>
  JSON.parse(readFileSync(path.resolve(here, '../../vectors/transaction', rel), 'utf8'))

describe('runTransactionVector — santa-transaction/v1 (bytes-anchored tx arm)', () => {
  it('keystone seed bigint-downcast-2666: the tx arm validates it valid:true (a JVM-accepted captured tx)', async () => {
    // ergots now has a real aggregate tx arm (@ergots/transaction); runTransactionVector is async
    // and returns a verdict per entry — not the old not-implemented stub.
    const vec = loadVector('v6/captured/bigint-downcast-2666.json')
    const actuals = await runTransactionVector(vec)
    // totality: one outcome per entry, keyed by name, none omitted
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ valid: true, cost: null, error: null })
    }
  })
})
