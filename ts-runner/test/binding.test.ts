import { describe, it, expect } from 'vitest'
import { ByteReader, ByteWriter } from '@ergots/scorex'
import {
  parseSType, parseSValue, serializeSType, serializeSValue, type SType, type SValue,
} from '@ergots/ergoscript'
import { extensionBinding, registerBinding, BindingRefused, NoExtensionReader, type ExtensionReader } from '../src/binding'
import { decodeSValue } from '../src/decode'
import { bytesToHex } from '../src/hex'
import { runEntry } from '../src/runner'

/** A constant's bytes as a node would carry them: its type, then its data. */
function constHex(tpe: SType, value: SValue): string {
  const w = new ByteWriter()
  serializeSType(tpe, w)
  serializeSValue(tpe, value, 3, w)
  return bytesToHex(w.toBytes())
}
const valueJson = (hex: string) => ({ kind: 'Value', bytes_hex: hex })
const SINT: SType = { tag: 'SInt' }
const SLONG: SType = { tag: 'SLong' }

// A STAND-IN for ergots' extension reader, which @ergots/transaction does not export from its root:
// the same wire grammar (a count byte, then per variable an id byte, its type and its data). It checks
// the adapter's framing and its leftover-bytes rule; it is not a claim about ergots' reader.
const standInReader: ExtensionReader = (r: ByteReader) => {
  const n = r.readU8()
  const values = new Map<number, { tpe: SType; value: SValue }>()
  for (let i = 0; i < n; i++) {
    const id = r.readU8()
    const tpe = parseSType(r, 3)
    values.set(id, { tpe, value: parseSValue(tpe, 3, r) })
  }
  return { values }
}

describe('Value binding at an extension position (var 1, v3/v5/full-context extensions)', () => {
  it('is read by the extension reader and equals the JSON form of the same value', () => {
    const j = decodeSValue({ kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 1 }, { kind: 'Int', value: 2 }] }, 3)
    expect(extensionBinding(valueJson(constHex(j.tpe, j.value)), 3, standInReader)).toEqual(j)
  })
  it('an SValue still goes through the JSON decoder', () => {
    expect(extensionBinding({ kind: 'Int', value: 7 }, 3, null)).toEqual(decodeSValue({ kind: 'Int', value: 7 }, 3))
  })
  it('bytes left after the value are refused', () => {
    expect(() => extensionBinding(valueJson(constHex(SINT, { kind: 'Int', value: 7 }) + '00'), 3, standInReader))
      .toThrow(BindingRefused)
  })
  it('without an extension reader in the impl it is not-implemented, never rebuilt from an SValue', () => {
    expect(() => extensionBinding(valueJson(constHex(SINT, { kind: 'Int', value: 7 })), 3, null))
      .toThrow(NoExtensionReader)
  })
})

describe('Value binding at a register position (selfRegisters)', () => {
  it("is read by ergots' parseAdditionalRegisters and equals the JSON form of the same value", () => {
    const j = decodeSValue({ kind: 'Long', value: '7' }, 3)
    expect(registerBinding(valueJson(constHex(j.tpe, j.value)), 3, 3)).toEqual(j)
  })
  it('rule 1019: an UnsignedBigInt is refused, as the JVM register reader refuses it', () => {
    const ubi = constHex({ tag: 'SUnsignedBigInt' }, { kind: 'UnsignedBigInt', value: 1n })
    expect(() => registerBinding(valueJson(ubi), 3, 3)).toThrow(BindingRefused)
  })
  it('bytes left after the value are refused', () => {
    expect(() => registerBinding(valueJson(constHex(SLONG, { kind: 'Long', value: 7n }) + '00'), 3, 3))
      .toThrow(BindingRefused)
  })
})

// Box.getReg_dynamic_index accept-r4-long#0 (committed): SELF.getReg[Long](var 1) with R4 = Long 7.
const getRegEntry = {
  name: 'accept-r4-long#0',
  tree_bytes_hex: '1b0b00dc6313a701e4e3010405',
  input: { kind: 'Int', value: 4 } as Record<string, unknown>,
  selfRegisters: { '4': { kind: 'Long', value: '7' } } as Record<string, unknown>,
  version: { activated: 3, ergoTree: 3 },
}
const blessed = { value: { kind: 'Option', value: { kind: 'Long', value: '7' } }, cost: 89, error: null }

describe('runEntry with Value bindings', () => {
  it('a register Value gives the same verdict as its JSON form', () => {
    const e = { ...getRegEntry, selfRegisters: { '4': valueJson(constHex(SLONG, { kind: 'Long', value: 7n })) } }
    expect(runEntry('santa-eval/v4', e as never)).toEqual(blessed)
  })
  it('a register Value the reader refuses is errored', () => {
    const e = { ...getRegEntry, selfRegisters: { '4': valueJson(constHex(SLONG, { kind: 'Long', value: 7n }) + '00') } }
    expect(runEntry('santa-eval/v4', e as never).error).toBe('errored')
  })
  it('a var-1 Value is read by the extension reader when the impl provides one', () => {
    const e = { ...getRegEntry, input: valueJson(constHex(SINT, { kind: 'Int', value: 4 })) }
    expect(runEntry('santa-eval/v4', e as never, standInReader)).toEqual(blessed)
  })
  it('a var-1 Value is not-implemented when the impl has no extension reader', () => {
    const e = { ...getRegEntry, input: valueJson(constHex(SINT, { kind: 'Int', value: 4 })) }
    expect(runEntry('santa-eval/v4', e as never, null).error).toBe('not-implemented')
  })
})
