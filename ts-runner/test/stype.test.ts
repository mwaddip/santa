import { describe, it, expect } from 'vitest'
import type { SType } from '@ergots/ergoscript'
import { stypeFromSanta, stypeToSanta } from '../src/stype'
import { AbstainError } from '../src/abstain'

type Json = Record<string, unknown>

describe('SType bridge', () => {
  const cases: Array<[Json, SType]> = [
    [{ tag: 'SBoolean' }, { tag: 'SBoolean' }],
    [{ tag: 'SInt' }, { tag: 'SInt' }],
    [{ tag: 'SLong' }, { tag: 'SLong' }],
    [{ tag: 'SBigInt' }, { tag: 'SBigInt' }],
    [{ tag: 'SGroupElement' }, { tag: 'SGroupElement' }],
    [{ tag: 'SBox' }, { tag: 'SBox' }],
    [{ tag: 'SColl', elem: { tag: 'SInt' } }, { tag: 'SColl', elem: { tag: 'SInt' } }],
    [{ tag: 'SOption', elem: { tag: 'SLong' } }, { tag: 'SOption', elem: { tag: 'SLong' } }],
    [
      { tag: 'STuple', items: [{ tag: 'SInt' }, { tag: 'SBoolean' }] },
      { tag: 'STuple', items: [{ tag: 'SInt' }, { tag: 'SBoolean' }] },
    ],
  ]

  for (const [santa, ergots] of cases) {
    it(`${JSON.stringify(santa)} ⇄ ergots SType`, () => {
      expect(stypeFromSanta(santa)).toEqual(ergots)
      expect(stypeToSanta(ergots)).toEqual(santa)
    })
  }

  it('SUnsignedBigInt → AbstainError (decode side)', () => {
    expect(() => stypeFromSanta({ tag: 'SUnsignedBigInt' })).toThrow(AbstainError)
  })

  it('nested SColl[SUnsignedBigInt] → AbstainError', () => {
    expect(() => stypeFromSanta({ tag: 'SColl', elem: { tag: 'SUnsignedBigInt' } })).toThrow(AbstainError)
  })
})
