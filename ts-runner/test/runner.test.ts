import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { runVector } from '../src/runner'

const here = path.dirname(fileURLToPath(import.meta.url))

describe('runVector — outcome taxonomy (runner-contract §3)', () => {
  it('v1: decode-point → GroupElement success + an errored entry', () => {
    const vec = {
      schema: 'santa-eval/v1', op: 'decode_point', blessed_by: 'x',
      entries: [
        { name: 'g', tree_bytes_hex: '00ee0e210279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798',
          version: { activated: 3, ergoTree: 0 }, expected: { value: null, cost: null, error: null } },
        { name: 'bad', tree_bytes_hex: '00ee0e200000000000000000000000000000000000000000000000000000000000000000',
          version: { activated: 3, ergoTree: 0 }, expected: { value: null, cost: null, error: 'errored' } },
      ],
    }
    const actuals = runVector(vec)
    expect(actuals['g']).toEqual({
      value: { kind: 'GroupElement', bytes_hex: '0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798' },
      cost: 305, error: null,
    })
    expect(actuals['bad']).toEqual({ value: null, cost: null, error: 'errored' })
  })

  it('v2: Coll.indices (implemented) binds input at var 1 → success', () => {
    const vec = {
      schema: 'santa-eval/v2', op: 'Coll.indices', blessed_by: 'x', source: 's',
      entries: [{
        name: 'i', tree_bytes_hex: '1b1000dad9010110db0c0e720101e4e30110',
        input: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 1 }, { kind: 'Int', value: 2 }] },
        version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null },
      }],
    }
    const actuals = runVector(vec)
    expect(actuals['i']).toEqual({
      value: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 0 }, { kind: 'Int', value: 1 }] },
      cost: 96, error: null, // 91 + 5 AddToEnvironment (ergots fix 2026-06-01)
    })
  })

  it('op the runner lacks (Coll.reverse) → not-implemented, present in actuals (NOT omitted)', () => {
    const vec = {
      schema: 'santa-eval/v2', op: 'Coll.reverse', blessed_by: 'x', source: 's',
      entries: [{
        name: 'r', tree_bytes_hex: '1b1000dad9010110db0c1e720101e4e30110',
        input: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 1 }, { kind: 'Int', value: 2 }] },
        version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null },
      }],
    }
    const actuals = runVector(vec)
    expect('r' in actuals).toBe(true) // totality: every entry yields an outcome
    expect(actuals['r']).toEqual({ value: null, cost: null, error: 'not-implemented' })
  })

  it('type the runner lacks (UnsignedBigInt input) → not-implemented, NOT errored/omitted', () => {
    const vec = {
      schema: 'santa-eval/v2', op: 'UnsignedBigInt methods', blessed_by: 'x', source: 's',
      entries: [{
        name: 'u', tree_bytes_hex: '1b1000dad9010110db0c0e720101e4e30110',
        input: { kind: 'UnsignedBigInt', value: '42' },
        version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null },
      }],
    }
    const actuals = runVector(vec)
    expect(actuals['u']).toEqual({ value: null, cost: null, error: 'not-implemented' })
  })

  it('value the runner has the type for but cannot represent (Header ts > 2^53) → unrepresentable', () => {
    // REAL fixture: the v6 Header corpus carries a Header whose timestamp is
    // 4928911477310178288 (> Number.MAX_SAFE_INTEGER). ergots HAS SHeader, but its codec
    // rejects the decode with ReaderError 'vlq-overflow' (scorex header.ts:69) → the runner
    // must report this as `unrepresentable`, NOT not-implemented and NOT a re-throw.
    type FixtureEntry = { name: string; tree_bytes_hex: string; input?: { kind: string; bytes_hex: string }; version: { activated: number; ergoTree: number } }
    const fixture = JSON.parse(
      readFileSync(path.resolve(here, '../../vectors/eval/v6/spec/Header_new_methods.json'), 'utf8'),
    ) as { schema: string; op: string; blessed_by: string; source: string; entries: FixtureEntry[] }
    const overflowEntry = fixture.entries[0]
    const vec = {
      schema: fixture.schema, op: fixture.op, blessed_by: fixture.blessed_by, source: fixture.source,
      entries: [overflowEntry],
    }
    const actuals = runVector(vec)
    // Pin the EXACT tag — not merely "not nice": value:null, cost:null, error:'unrepresentable'.
    expect(actuals[overflowEntry.name]).toEqual({ value: null, cost: null, error: 'unrepresentable' })
  })

  it('a parse failure that is NOT an unsupported-type re-throws (no silent swallowing)', () => {
    // The whole design rests on this: a decode/parse failure the runner does NOT recognize as
    // an unsupported-type or a repr-limit must PROPAGATE, never become a tagged outcome. Empty
    // tree bytes make parseTree throw ErgoTreeParseError('empty ErgoTree bytes', code 'empty') —
    // code is NOT 'unsupported-type', so isUnsupportedType() is false and runEntry re-throws.
    const vec = {
      schema: 'santa-eval/v2', op: 'malformed', blessed_by: 'x', source: 's',
      entries: [{
        name: 'oops', tree_bytes_hex: '',
        version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null },
      }],
    }
    // Pin the message so this guards the parseTree re-throw arm specifically — if `throw err`
    // were replaced by a swallow (e.g. returning an `errored` Result), runVector would NOT throw
    // and this expectation would fail.
    expect(() => runVector(vec)).toThrow(/empty ErgoTree bytes/)
  })
})
