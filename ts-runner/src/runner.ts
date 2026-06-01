import { readFileSync, writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { parseTree, evaluateWith, makeContext, EvalError } from '@ergots/ergoscript'
import { decodeSValue } from './decode'
import { encodeSValue, type Json } from './encode'
import { UnsupportedTypeError, isUnsupportedType } from './abstain'
import { hexToBytes } from './hex'

interface Entry {
  name: string
  tree_bytes_hex: string
  input?: { [k: string]: Json }
  version: { activated: number; ergoTree: number }
}
interface Vector { schema: string; entries: Entry[] }
type Result = { value: Json; cost: number | null; error: null | 'errored' }

// Non-success outcomes. All but `errored` are OMITTED from the actuals file
// (the frozen schema has only success/errored); Dasher tracks each distinctly.
// Two verdict classes:
//   - clean ABSTENTION (out of ergots' declared v5/mainnet scope; NOT a bug; neither nice nor
//     naughty; flips to covered when ergots gains v6): ABSTAIN_V6 (v6 UnsignedBigInt kind/type)
//     and ABSTAIN_NOT_IMPL (op not on mainnet ⇒ not implemented ⇒ a v6 feature).
//   - DIVERGENCE (naughty — a real ergots bug in something it DOES support; route to ergots):
//     REPR_DIVERGENCE (Header input ergots can't represent). (Cost divergences also score
//     naughty, but they evaluate, so they land in `actuals` and the comparator catches them.)
const ABSTAIN_V6 = Symbol('abstain-v6')             // v6 UnsignedBigInt — out of v5 scope (pre-eval) — clean abstain
const ABSTAIN_NOT_IMPL = Symbol('abstain-not-impl') // op not on mainnet ⇒ not implemented ⇒ v6 (eval throws 'method-not-implemented') — clean abstain
const REPR_DIVERGENCE = Symbol('repr-divergence')   // v5 input ergots can't represent, e.g. Header ts > 2^53 (pre-eval) — naughty (ergots bug)
type Outcome = Result | typeof ABSTAIN_V6 | typeof ABSTAIN_NOT_IMPL | typeof REPR_DIVERGENCE

/** ergots ReaderError code for an input the v5 codec cannot represent (a Header
 *  timestamp > Number.MAX_SAFE_INTEGER). Confirmed in @ergots/scorex header.ts:72. */
function isReprLimit(err: unknown): boolean {
  return (err as { code?: unknown })?.code === 'vlq-overflow'
}

/** Run one entry → exactly one Outcome. */
export function runEntry(schema: string, e: Entry): Outcome {
  const treeVersion = e.version.ergoTree

  // [0] parseTree — a v6 SUnsignedBigInt type on the wire ⇒ ABSTAIN_V6 (pre-eval);
  //     ANY other parse failure is a loud bug (re-throw), NOT errored.
  let tree
  try {
    tree = parseTree(hexToBytes(e.tree_bytes_hex))
  } catch (err) {
    if (isUnsupportedType(err)) return ABSTAIN_V6
    throw err
  }

  // [1] decode + bind input at ctx var 1 (v2 only).
  //     UnsignedBigInt ⇒ ABSTAIN_V6; an input ergots can't represent
  //     (Header ts overflow ⇒ ReaderError 'vlq-overflow') ⇒ REPR_DIVERGENCE;
  //     anything else ⇒ loud re-throw.
  let ctx
  try {
    if (schema === 'santa-eval/v2') {
      if (!e.input) throw new Error(`missing input in v2 entry '${e.name}'`)
      const { value, tpe } = decodeSValue(e.input, treeVersion)
      // ConstPlaceholder resolution needs the tree's segregated constants;
      // evaluateWith (unlike evaluate) does NOT auto-populate them — pass explicitly.
      ctx = makeContext({ treeVersion, constants: tree.constants, extension: { values: { 1: { tpe, value } } } })
    } else {
      ctx = makeContext({ treeVersion, constants: tree.constants })
    }
  } catch (err) {
    if (err instanceof UnsupportedTypeError) return ABSTAIN_V6
    if (isReprLimit(err)) return REPR_DIVERGENCE
    throw err
  }

  // [2] eval ⇒ [3] encode ⇒ [4] capture.
  //     EvalError 'method-not-implemented' ⇒ ABSTAIN_NOT_IMPL (op not on mainnet ⇒ a v6
  //     feature ergots doesn't implement ⇒ out of scope, clean abstain — NOT errored);
  //     any other EvalError ⇒ errored; a non-EvalError throw ⇒ loud re-throw.
  try {
    const out = evaluateWith(tree, ctx)
    return { value: encodeSValue(out, treeVersion), cost: ctx.jitCost, error: null }
  } catch (err) {
    if (err instanceof EvalError && err.code === 'method-not-implemented') return ABSTAIN_NOT_IMPL
    if (err instanceof EvalError) return { value: null, cost: null, error: 'errored' }
    throw err
  }
}

export interface RunReport {
  /** The actuals file: per-entry {value,cost,error}, keyed by name. Contains
   *  success + errored entries only (abstain/divergence entries are omitted). */
  actuals: Record<string, Result>
  /** CLEAN ABSTENTION — v6 UnsignedBigInt kind/type, out of v5 scope. Not a bug. */
  abstainedV6: string[]
  /** CLEAN ABSTENTION — op not on mainnet ⇒ not implemented ⇒ a v6 feature, out of v5
   *  scope. Not a bug; flips to covered when ergots gains v6. */
  abstainedNotImpl: string[]
  /** DIVERGENCE (naughty) — a v5 input ergots can't represent (Header ts overflow): a
   *  real ergots bug in a type it DOES support. Omitted from actuals; route to ergots. */
  reprDivergences: string[]
}

/** run(vector) → actuals + Dasher-side scope metadata. */
export function runVector(doc: Vector): RunReport {
  const actuals: Record<string, Result> = {}
  const abstainedV6: string[] = []
  const abstainedNotImpl: string[] = []
  const reprDivergences: string[] = []
  for (const e of doc.entries) {
    const r = runEntry(doc.schema ?? 'santa-eval/v1', e)
    if (r === ABSTAIN_V6) abstainedV6.push(e.name)
    else if (r === ABSTAIN_NOT_IMPL) abstainedNotImpl.push(e.name)
    else if (r === REPR_DIVERGENCE) reprDivergences.push(e.name)
    else actuals[e.name] = r
  }
  return { actuals, abstainedV6, abstainedNotImpl, reprDivergences }
}

// ---- CLI: runner <vector.json> [actuals-out.json] (mirrors Runner.scala) ----
function main(argv: string[]): void {
  const vecPath = argv[2]
  if (!vecPath) {
    console.error('usage: runner <vector.json> [<actuals-out.json>]')
    process.exit(2)
  }
  const doc = JSON.parse(readFileSync(vecPath, 'utf8')) as Vector
  const { actuals, abstainedV6, abstainedNotImpl, reprDivergences } = runVector(doc)
  const json = JSON.stringify(actuals, null, 2)
  const outPath = argv[3]
  if (outPath) {
    writeFileSync(outPath, json)
    console.error(`actuals → ${outPath}`)
  } else {
    console.log(json)
  }
  if (abstainedV6.length) console.error(`abstain · v6 UnsignedBigInt (out of scope): ${abstainedV6.length}`)
  if (abstainedNotImpl.length) console.error(`abstain · op not implemented = v6 (out of scope): ${abstainedNotImpl.length}`)
  if (reprDivergences.length) console.error(`DIVERGENCE · ergots cannot represent input (route to ergots): ${reprDivergences.length} — ${reprDivergences.join(', ')}`)
}

// Run main only when invoked as the bin (not when imported). pathToFileURL resolves a
// relative argv path to an absolute file URL so the comparison is robust.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv)
