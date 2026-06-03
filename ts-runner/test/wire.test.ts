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
    const vec = loadVector('v5/authored/Box.json')
    const actuals = runWireVector(vec)
    // totality: one outcome per entry, keyed by name, none omitted
    expect(Object.keys(actuals)).toEqual(vec.entries.map((e) => e.name))
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ bytes_hex: e.bytes_hex, error: null })
    }
  })

  it('SigmaBoolean: not-implemented until ergots exports the bare-SigmaBoolean wire functions', () => {
    const vec = loadVector('v5/authored/SigmaBoolean.json')
    const actuals = runWireVector(vec)
    for (const e of vec.entries) {
      expect(actuals[e.name]).toEqual({ bytes_hex: null, error: 'not-implemented' })
    }
  })
})
