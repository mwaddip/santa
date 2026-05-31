import { describe, it, expect } from 'vitest'
import { runVector } from '../src/runner'

describe('runVector — outcome taxonomy', () => {
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
    const { actuals } = runVector(vec)
    expect(actuals['g']).toEqual({
      value: { kind: 'GroupElement', bytes_hex: '0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798' },
      cost: 305, error: null,
    })
    expect(actuals['bad']).toEqual({ value: null, cost: null, error: 'errored' })
  })

  it('v2: Coll.indices (implemented) binds input at var 1 → covered success', () => {
    const vec = {
      schema: 'santa-eval/v2', op: 'Coll.indices', blessed_by: 'x', source: 's',
      entries: [{
        name: 'i', tree_bytes_hex: '1b1000dad9010110db0c0e720101e4e30110',
        input: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 1 }, { kind: 'Int', value: 2 }] },
        version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null },
      }],
    }
    const { actuals } = runVector(vec)
    expect(actuals['i']).toEqual({
      value: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 0 }, { kind: 'Int', value: 1 }] },
      cost: 96, error: null, // 91 + 5 AddToEnvironment (ergots fix 2026-06-01)
    })
  })

  it('abstain (not implemented = v6): Coll.reverse → abstainedNotImpl, NOT actuals/errored', () => {
    const vec = {
      schema: 'santa-eval/v2', op: 'Coll.reverse', blessed_by: 'x', source: 's',
      entries: [{
        name: 'r', tree_bytes_hex: '1b1000dad9010110db0c1e720101e4e30110',
        input: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 1 }, { kind: 'Int', value: 2 }] },
        version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null },
      }],
    }
    const { actuals, abstainedNotImpl } = runVector(vec)
    expect('r' in actuals).toBe(false)
    expect(abstainedNotImpl).toContain('r')
  })

  it('abstain·v6: UnsignedBigInt input (out of v5 scope) → abstainedV6, NOT gap/errored', () => {
    const vec = {
      schema: 'santa-eval/v2', op: 'UnsignedBigInt methods', blessed_by: 'x', source: 's',
      entries: [{
        name: 'u', tree_bytes_hex: '1b1000dad9010110db0c0e720101e4e30110',
        input: { kind: 'UnsignedBigInt', value: '42' },
        version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null },
      }],
    }
    const { actuals, abstainedV6, abstainedNotImpl } = runVector(vec)
    expect('u' in actuals).toBe(false)
    expect(abstainedV6).toContain('u')
    expect(abstainedNotImpl).not.toContain('u')
  })
})
