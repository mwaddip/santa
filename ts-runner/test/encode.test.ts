import { describe, it, expect } from 'vitest'
import type { SValue } from '@ergots/ergoscript'
import { parseSValue } from '@ergots/ergoscript'
import { ByteReader } from '@ergots/scorex'
import { encodeSValue } from '../src/encode'
import { hexToBytes } from '../src/hex'

describe('encode — primitives', () => {
  it('Boolean → bare bool', () => {
    expect(encodeSValue({ kind: 'Boolean', value: true }, 3)).toEqual({ kind: 'Boolean', value: true })
  })
  it('Byte/Short/Int → bare number', () => {
    expect(encodeSValue({ kind: 'Byte', value: -5 }, 3)).toEqual({ kind: 'Byte', value: -5 })
    expect(encodeSValue({ kind: 'Short', value: 300 }, 3)).toEqual({ kind: 'Short', value: 300 })
    expect(encodeSValue({ kind: 'Int', value: 2147483647 }, 3)).toEqual({ kind: 'Int', value: 2147483647 })
  })
  it('Long → decimal STRING (beyond JS safe int)', () => {
    expect(encodeSValue({ kind: 'Long', value: 9223372036854775807n }, 3))
      .toEqual({ kind: 'Long', value: '9223372036854775807' })
    expect(encodeSValue({ kind: 'Long', value: -1n }, 3)).toEqual({ kind: 'Long', value: '-1' })
  })
  it('BigInt → decimal string', () => {
    expect(encodeSValue({ kind: 'BigInt', value: -170141183460469231731687303715884105728n }, 3))
      .toEqual({ kind: 'BigInt', value: '-170141183460469231731687303715884105728' })
  })
  it('GroupElement → lower-case bytes_hex', () => {
    const v: SValue = { kind: 'GroupElement', value: new Uint8Array([0x02, 0xAB, 0xff]) }
    expect(encodeSValue(v, 3)).toEqual({ kind: 'GroupElement', bytes_hex: '02abff' })
  })
  it('unmodeled result kind (String) → loud throw', () => {
    expect(() => encodeSValue({ kind: 'String', value: 'x' } as SValue, 3)).toThrow(/unmodeled|unexpected/i)
  })
  it('UnsignedBigInt → decimal string (decode-consistent)', () => {
    expect(encodeSValue({ kind: 'UnsignedBigInt', value: 115792089237316195423570985008687907852837564279074904382605163141518161494337n }, 3))
      .toEqual({ kind: 'UnsignedBigInt', value: '115792089237316195423570985008687907852837564279074904382605163141518161494337' })
  })
})

describe('encode — composites', () => {
  it('Coll → {elem, items} (elem via SType bridge)', () => {
    expect(encodeSValue({ kind: 'Coll', elem: { tag: 'SInt' }, items: [
      { kind: 'Int', value: 2 }, { kind: 'Int', value: 1 },
    ] }, 3)).toEqual({ kind: 'Coll', elem: { tag: 'SInt' }, items: [
      { kind: 'Int', value: 2 }, { kind: 'Int', value: 1 },
    ] })
  })
  it('Tuple → positional items', () => {
    expect(encodeSValue({ kind: 'Tuple', items: [
      { kind: 'Int', value: 1 }, { kind: 'Boolean', value: false },
    ] }, 3)).toEqual({ kind: 'Tuple', items: [
      { kind: 'Int', value: 1 }, { kind: 'Boolean', value: false },
    ] })
  })
  it('Option Some → {value: inner} (drops ergots elem)', () => {
    expect(encodeSValue({ kind: 'Option', elem: { tag: 'SLong' }, value: { kind: 'Long', value: 7n } }, 3))
      .toEqual({ kind: 'Option', value: { kind: 'Long', value: '7' } })
  })
  it('Option None → {value: null}', () => {
    expect(encodeSValue({ kind: 'Option', elem: { tag: 'SInt' }, value: null }, 3))
      .toEqual({ kind: 'Option', value: null })
  })
})

describe('encode — bytes-kinds (Box round-trip)', () => {
  const BOX_HEX = '0a0b0208d3000000000000000000000000000000000000000000000000000000000000000000000000'
  it('Box → bytes_hex equals ergots box serialization (round-trip)', () => {
    const box = parseSValue({ tag: 'SBox' }, 3, new ByteReader(hexToBytes(BOX_HEX)))
    expect(box.kind).toBe('Box')
    expect(encodeSValue(box, 3)).toEqual({ kind: 'Box', bytes_hex: BOX_HEX })
  })
})
