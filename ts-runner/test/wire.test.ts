import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { runWireVector } from '../src/runner'

const here = path.dirname(fileURLToPath(import.meta.url))
const loadVector = (
  rel: string,
): { schema: string; entries: { name: string; kind: string; bytes_hex: string; version: { activated: number; ergoTree: number } }[] } =>
  JSON.parse(readFileSync(path.resolve(here, '../../vectors/wire', rel), 'utf8'))

describe('runWireVector — wire round-trip (santa-wire/v1)', () => {
  it('Box: every entry round-trips to its own canonical bytes (public parseSValue/serializeSValue(SBox))', () => {
    const vec = loadVector('v5/vendored/Box.json')
    const actuals = runWireVector(vec)
    // totality: one outcome per entry, keyed by name, none omitted
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ bytes_hex: e.bytes_hex, error: null })
    }
  })

  it('SigmaBoolean: every entry round-trips to its own canonical bytes (public parseSigmaBoolean/serializeSigmaBoolean)', () => {
    const vec = loadVector('v5/vendored/SigmaBoolean.json')
    const actuals = runWireVector(vec)
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ bytes_hex: e.bytes_hex, error: null })
    }
  })

  it('Constant: every entry round-trips to its own canonical bytes (public parseSType+parseSValue / serializeSType+serializeSValue)', () => {
    const vec = loadVector('v5/vendored/Constant.json')
    const actuals = runWireVector(vec)
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ bytes_hex: e.bytes_hex, error: null })
    }
  })

  it('Transaction: not-implemented — ergots has no transaction serializer (it is an eval library, not a tx builder)', () => {
    const vec = loadVector('v5/vendored/Transaction.json')
    const actuals = runWireVector(vec)
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ bytes_hex: null, error: 'not-implemented' })
    }
  })

  it('ErgoTree: the structural round-trip engages — a faithful divergence on the ill-formed STypeVar names', () => {
    const vec = loadVector('v6/authored/STypeVar.name_utf8_roundtrip.json')
    const actuals = runWireVector(vec)
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    // The arm engages (never not-implemented). ergots strict-UTF-8-rejects every ill-formed type-var
    // name and throws an UNTYPED error, so the harness classifies it `panicked` (the classification
    // gap — same shape as the eval STypeVar arm). It flips to `errored` if ergots types the reject, or
    // green once ergots adopts the JVM's lossy U+FFFD collapse. The node fix is the ergots session's;
    // the runner arm is SANTA's. Update this assertion when ergots converges.
    for (const e of vec.entries) {
      expect(actuals[e.name].bytes_hex).toBeNull()
      expect(actuals[e.name].error).toBe('panicked')
    }
  })
})
