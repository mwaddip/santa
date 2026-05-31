import { describe, it, expect } from 'vitest'
import { decodeSValue } from '../src/decode'
import { AbstainError } from '../src/abstain'

describe('decode — value + tpe', () => {
  it('Int → {value, tpe SInt}', () => {
    expect(decodeSValue({ kind: 'Int', value: 5 }, 3))
      .toEqual({ value: { kind: 'Int', value: 5 }, tpe: { tag: 'SInt' } })
  })
  it('Long string → bigint, tpe SLong', () => {
    expect(decodeSValue({ kind: 'Long', value: '9223372036854775807' }, 3))
      .toEqual({ value: { kind: 'Long', value: 9223372036854775807n }, tpe: { tag: 'SLong' } })
  })
  it('GroupElement hex → Uint8Array, tpe SGroupElement', () => {
    const r = decodeSValue({ kind: 'GroupElement', bytes_hex: '02abff' }, 3)
    expect(r.tpe).toEqual({ tag: 'SGroupElement' })
    expect(r.value).toEqual({ kind: 'GroupElement', value: new Uint8Array([0x02, 0xab, 0xff]) })
  })
  it('Coll → {value with elem, tpe SColl}', () => {
    expect(decodeSValue({ kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 1 }] }, 3))
      .toEqual({
        value: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 1 }] },
        tpe: { tag: 'SColl', elem: { tag: 'SInt' } },
      })
  })
  it('Tuple → {value, tpe STuple from item tpes}', () => {
    const r = decodeSValue({ kind: 'Tuple', items: [{ kind: 'Int', value: 1 }, { kind: 'Boolean', value: true }] }, 3)
    expect(r.tpe).toEqual({ tag: 'STuple', items: [{ tag: 'SInt' }, { tag: 'SBoolean' }] })
    expect(r.value).toEqual({ kind: 'Tuple', items: [{ kind: 'Int', value: 1 }, { kind: 'Boolean', value: true }] })
  })
  it('Option Some → infers elem from inner', () => {
    const r = decodeSValue({ kind: 'Option', value: { kind: 'Long', value: '7' } }, 3)
    expect(r.tpe).toEqual({ tag: 'SOption', elem: { tag: 'SLong' } })
    expect(r.value).toEqual({ kind: 'Option', elem: { tag: 'SLong' }, value: { kind: 'Long', value: 7n } })
  })
  it('UnsignedBigInt → AbstainError', () => {
    expect(() => decodeSValue({ kind: 'UnsignedBigInt', value: '42' }, 3)).toThrow(AbstainError)
  })
})
