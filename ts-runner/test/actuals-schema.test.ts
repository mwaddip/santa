import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import Ajv2020 from 'ajv/dist/2020'
import { encodeSValue } from '../src/encode'

const here = path.dirname(fileURLToPath(import.meta.url))
const schemaDir = path.resolve(here, '../../schema')
const vectorSchema = JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.vector.schema.json'), 'utf8'))
const actualsSchema = JSON.parse(readFileSync(path.join(schemaDir, 'santa-eval.actuals.schema.json'), 'utf8'))

const ajv = new Ajv2020({ strict: false })
ajv.addSchema(vectorSchema) // referenced by actuals via $id
const validate = ajv.compile(actualsSchema)

describe('encoder/runner output validates against the frozen actuals schema', () => {
  it('a success actuals object validates', () => {
    const actuals = {
      'x#0': { value: encodeSValue({ kind: 'Long', value: 7n }, 3), cost: 96, error: null },
      'x#1': { value: null, cost: null, error: 'errored' },
    }
    const ok = validate(actuals)
    expect(validate.errors ?? []).toEqual([])
    expect(ok).toBe(true)
  })
})
