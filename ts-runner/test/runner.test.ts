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

  it('UnsignedBigInt INPUTS bridge (GroupElement.expUnsigned spec) → green vs blessed for every entry', () => {
    // Twice re-pinned: Coll.reverse → ergots implemented it (v6 P2); expUnsigned-as-not-impl →
    // the BRIDGE grew its UnsignedBigInt codec (every spec entry feeds a (GroupElement,
    // UnsignedBigInt) tuple through decode.ts), so the file now grades green end-to-end —
    // value AND cost — against the JVM-blessed expected. ergots' eval side landed in v6 P7a
    // (eval/exponentiate.ts via method-call.ts); the bridge was the last not-impl cause.
    const raw = JSON.parse(
      readFileSync(path.resolve(here, '../../vectors/eval/v6/spec/GroupElement.expUnsigned.json'), 'utf8'),
    ) as { entries: Array<{ name: string; expected: unknown }> }
    const actuals = runVector(raw as unknown as Parameters<typeof runVector>[0])
    expect(Object.keys(actuals)).toEqual(raw.entries.map((e) => e.name)) // totality: no entry omitted
    for (const e of raw.entries) {
      expect(actuals[e.name], e.name).toEqual(e.expected)
    }
  })

  it('UnsignedBigInt RESULT encode (BigInt.toUnsigned spec) → green vs blessed for every entry', () => {
    // The result-direction twin of the expUnsigned input test: BigInt.toUnsigned produces an
    // UnsignedBigInt SValue, exercising encode.ts' UBI arm (decimal string, decode-consistent).
    const raw = JSON.parse(
      readFileSync(path.resolve(here, '../../vectors/eval/v6/spec/BigInt.toUnsigned.json'), 'utf8'),
    ) as { entries: Array<{ name: string; expected: unknown }> }
    const actuals = runVector(raw as unknown as Parameters<typeof runVector>[0])
    expect(Object.keys(actuals)).toEqual(raw.entries.map((e) => e.name))
    for (const e of raw.entries) {
      expect(actuals[e.name], e.name).toEqual(e.expected)
    }
  })

  it("an input ergots' codec rejects at runtime (Header ts > 2^53) → panicked, not a pre-classified excuse", () => {
    // REAL fixture: the v6 Header corpus carries a Header whose timestamp is
    // 4928911477310178288 (> Number.MAX_SAFE_INTEGER). ergots HAS SHeader, but its codec
    // throws ReaderError 'vlq-overflow' (scorex header.ts) decoding it. The runner records
    // ergots' ACTUAL failure — the never-panic net catches the throw as `panicked`, message in
    // `note` — rather than relabelling it the softer `unrepresentable` on ergots' behalf. Same
    // coal grade; the divergence now speaks for itself and self-heals when ergots widens the type.
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
    const act = actuals[overflowEntry.name]
    // No pre-classification: value/cost null, error 'panicked', and a non-empty note carrying
    // ergots' own message (we do NOT pin the exact text — that would re-couple us to a brittle
    // internal codec string, the very thing the removed `isReprLimit` guard did).
    expect(act.error).toBe('panicked')
    expect(act.value).toBeNull()
    expect(act.cost).toBeNull()
    expect(typeof act.note).toBe('string')
    expect((act.note ?? '').length).toBeGreaterThan(0)
  })

  it('AvlTree serialize input decodes (no crash) — ergots now evaluates serialize(AvlTree) → value, total', () => {
    // Regression: decode.ts had no `case 'AvlTree'`, so an AvlTree SValue input threw
    // `unknown SANTA SValue kind 'AvlTree'` and ABORTED the whole vector (a §3 totality break) —
    // even though ergots fully supports SAvlTree (parse-svalue.ts `case 'SAvlTree'`, type code 100).
    // The gap was the SANTA-side bridge, not ergots. With the decode case added, the input decodes
    // and the run stays total (every entry graded, no abort).
    //
    // ergots SINCE closed the eval gap too: serialize(AvlTree) used to throw EvalError
    // 'method-not-implemented'; it now returns a Coll[Byte]. This is a runner-FAITHFULNESS check — it
    // asserts the runner reports what ergots produces (no crash, a value), NOT that the value/cost
    // match the JVM bless. That comparison is ./conform's job: the value matches, but ergots
    // UNDERCHARGES the cost (126 vs JVM 127, Δ−1) — a new divergence surfaced by closing the gap,
    // recorded in docs/findings/eval-jvm-vs-ergots.md, never pinned green here.
    const fixture = JSON.parse(
      readFileSync(path.resolve(here, '../../vectors/eval/v6/authored/Global.serialize_AvlTree.json'), 'utf8'),
    ) as Parameters<typeof runVector>[0]
    const actuals = runVector(fixture)
    expect(Object.keys(actuals)).toEqual(fixture.entries.map((e) => e.name)) // totality: no abort
    for (const e of fixture.entries) {
      const a = actuals[e.name] as { value: unknown; cost: number | null; error: string | null }
      expect(a.error).toBeNull() // no crash, no not-implemented — ergots evaluates it
      expect((a.value as { kind?: string }).kind).toBe('Coll') // serialize → Coll[Byte]
      expect(typeof a.cost).toBe('number') // cost present (graded vs the bless by ./conform, not here)
    }
  })

  it('v3: inputs[].extension builds per-input ContextExtensions (getVarFromInput) — every entry matches the bless', () => {
    // The harness gap this pins: the bridge used to evaluate santa-eval/v3 entries with NO
    // context (the bare `else` arm), so getVarFromInput read absent inputExtensions → None for
    // every entry — mis-presenting ergots (which implements 101:12) as value-divergent on the
    // present-* entries. The v3 envelope: the spending tx has ONE input per `inputs[]` element,
    // EACH carrying its own ContextExtension (SELF = input 0) — EvalCore.evalWithInputExtensions.
    for (const file of ['Context.getVarFromInput.json', 'Context.getVarFromInput_multi_input.json']) {
      const fixture = JSON.parse(
        readFileSync(path.resolve(here, `../../vectors/eval/v6/authored/${file}`), 'utf8'),
      ) as Parameters<typeof runVector>[0] & {
        entries: { name: string; expected: { value: unknown; cost: number | null; error: string | null } }[]
      }
      const actuals = runVector(fixture)
      expect(Object.keys(actuals), file).toEqual(fixture.entries.map((e) => e.name)) // totality
      for (const e of fixture.entries) {
        // value+cost+error all blessed-equal — incl. negative-varid-0xff#5 (wire key 255 ≡ Byte -1).
        expect(actuals[e.name], `${file} :: ${e.name}`).toEqual(e.expected)
      }
    }
  })

  it('v4: selfRegisters (R4-R9) + var-1 input build the SELF box (dynamic-index getReg) — every entry matches the bless', () => {
    // v4 = v2's single `input` (var 1) PLUS selfRegisters applied to SELF's additional
    // registers — EvalCore.evalWithSelfRegistersAndVar1. Pre-fix the bare arm gave ergots no
    // selfBox, so SELF threw context-field-missing → 'errored' for all four entries (the
    // reject-wrong-type#1 entry passed by accident; the three value entries graded coal).
    const fixture = JSON.parse(
      readFileSync(path.resolve(here, '../../vectors/eval/v6/authored/Box.getReg_dynamic_index.json'), 'utf8'),
    ) as Parameters<typeof runVector>[0] & {
      entries: { name: string; expected: { value: unknown; cost: number | null; error: string | null } }[]
    }
    const actuals = runVector(fixture)
    expect(Object.keys(actuals)).toEqual(fixture.entries.map((e) => e.name)) // totality
    for (const e of fixture.entries) {
      // Some(Long 7)@89 / reject (register-type-mismatch → errored) / None×2@89.
      expect(actuals[e.name], e.name).toEqual(e.expected)
    }
  })

  it('an unrecognized failure becomes `panicked` (note carries the message) and the run continues', () => {
    // Never-panic invariant (runner-contract §3): a parse/codec failure the runner does NOT
    // recognize as unsupported-type or a repr-limit must NOT abort the file — it becomes the
    // `panicked` outcome (coal, message in `note`), and later entries still grade. Empty tree
    // bytes make parseTree throw ErgoTreeParseError('empty ErgoTree bytes', code 'empty') —
    // code is NOT 'unsupported-type', so it falls through to runEntry's panic-net.
    const vec = {
      schema: 'santa-eval/v2', op: 'malformed', blessed_by: 'x', source: 's',
      entries: [
        { name: 'oops', tree_bytes_hex: '',
          version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null } },
        // A valid entry AFTER the panic — it must still produce its outcome (run continues).
        // This is the Coll.indices tree from the v2 test above → [0,1], cost 96.
        { name: 'after', tree_bytes_hex: '1b1000dad9010110db0c0e720101e4e30110',
          input: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 1 }, { kind: 'Int', value: 2 }] },
          version: { activated: 3, ergoTree: 3 }, expected: { value: null, cost: null, error: null } },
      ],
    }
    const actuals = runVector(vec)
    expect(actuals['oops'].error).toBe('panicked')
    expect(actuals['oops'].value).toBeNull()
    expect(actuals['oops'].cost).toBeNull()
    expect(actuals['oops'].note).toMatch(/empty ErgoTree bytes/)
    // run continues: the second entry still grades
    expect(actuals['after']).toEqual({
      value: { kind: 'Coll', elem: { tag: 'SInt' }, items: [{ kind: 'Int', value: 0 }, { kind: 'Int', value: 1 }] },
      cost: 96, error: null,
    })
  })
})
