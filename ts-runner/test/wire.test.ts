import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { runWireVector } from '../src/runner'

const here = path.dirname(fileURLToPath(import.meta.url))
const loadVector = (
  rel: string,
): { schema: string; entries: { name: string; kind: string; bytes_hex: string; expected_bytes_hex?: string; error?: string; version: { activated: number; ergoTree: number } }[] } =>
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

  it('ErgoTree (STypeVar names): round-trips to the JVM-canonical lossy bytes — ergots master adopted the U+FFFD collapse', () => {
    const vec = loadVector('v6/authored/STypeVar.name_utf8_roundtrip.json')
    const actuals = runWireVector(vec)
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    // ergots master now lossy-decodes the ill-formed type-var names (the JVM's U+FFFD collapse) and
    // re-serializes the structural tree, so each entry round-trips to its blessed JVM-canonical output
    // (expected_bytes_hex, non-identity) — converged, no longer the panicked classification gap.
    for (const e of vec.entries) {
      expect(actuals[e.name].error).toBeNull()
      expect(actuals[e.name].bytes_hex).toBe(e.expected_bytes_hex ?? e.bytes_hex)
    }
  })

  it('ErgoTree (SHeader-constant reject): a typed codec rejection grades errored, not panicked', () => {
    // The (b) reject vector: a size-flagged tree with a segregated SHeader constant the JVM rejects.
    // ergots throws a typed SValueParseError parsing the constant; isWireCodecError maps it to `errored`
    // (a faithful reject), NOT the panic-net. Guards the typed-codec-error classification.
    const vec = loadVector('v6/authored/ErgoTree.unparsed_soft_fork_header_constant.json')
    const actuals = runWireVector(vec)
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ bytes_hex: null, error: 'errored' })
    }
  })
})
