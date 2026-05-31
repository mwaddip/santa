import { readFileSync, writeFileSync } from 'node:fs'
import { parseTree, evaluateWith, makeContext, EvalError } from '@ergots/ergoscript'
import { decodeSValue } from './decode'
import { encodeSValue, type Json } from './encode'
import { AbstainError, isV6TypeRejection } from './abstain'
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
//   - clean ABSTENTION (out of declared v5 scope; NOT a bug; neither nice nor naughty): ABSTAIN_V6.
//   - NAUGHTY-BY-COVERAGE (ergots fails an in-scope v5 thing the JVM handles; a real bug to
//     route to ergots): COVERAGE_GAP (op unbuilt) and REPR_DIVERGENCE (input unrepresentable).
//     Same verdict, different cause; tracked as separate lists.
const ABSTAIN_V6 = Symbol('abstain-v6')           // v6 UnsignedBigInt — out of declared v5 scope (pre-eval) — clean abstain
const REPR_DIVERGENCE = Symbol('repr-divergence') // v5 input ergots can't represent, e.g. Header ts > 2^53 (pre-eval) — naughty-by-coverage
const COVERAGE_GAP = Symbol('coverage-gap')       // in-scope v5 op ergots hasn't implemented (eval throws) — naughty-by-coverage
type Outcome = Result | typeof ABSTAIN_V6 | typeof REPR_DIVERGENCE | typeof COVERAGE_GAP

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
    if (isV6TypeRejection(err)) return ABSTAIN_V6
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
    if (err instanceof AbstainError) return ABSTAIN_V6
    if (isReprLimit(err)) return REPR_DIVERGENCE
    throw err
  }

  // [2] eval ⇒ [3] encode ⇒ [4] capture.
  //     EvalError 'method-not-implemented' ⇒ COVERAGE_GAP (op ergots hasn't built);
  //     any other EvalError ⇒ errored; a non-EvalError throw ⇒ loud re-throw.
  try {
    const out = evaluateWith(tree, ctx)
    return { value: encodeSValue(out, treeVersion), cost: ctx.jitCost, error: null }
  } catch (err) {
    if (err instanceof EvalError && err.code === 'method-not-implemented') return COVERAGE_GAP
    if (err instanceof EvalError) return { value: null, cost: null, error: 'errored' }
    throw err
  }
}

export interface RunReport {
  /** The actuals file: per-entry {value,cost,error}, keyed by name. Contains
   *  success + errored entries only (abstain/gap/divergence entries are omitted). */
  actuals: Record<string, Result>
  /** CLEAN ABSTENTION — out of declared v5 scope (v6 UnsignedBigInt). Not a bug;
   *  neither nice nor naughty. */
  abstainedV6: string[]
  /** NAUGHTY-BY-COVERAGE — a v5 input ergots can't represent (Header ts overflow):
   *  a real ergots/JVM divergence (ergots bug). Omitted from actuals, but route to
   *  ergots. Same verdict class as `gaps`, different cause. */
  reprDivergences: string[]
  /** NAUGHTY-BY-COVERAGE — an in-scope v5 op ergots hasn't implemented (coverage-gap).
   *  Omitted from actuals; route to ergots. */
  gaps: string[]
}

/** run(vector) → actuals + Dasher-side scope metadata. */
export function runVector(doc: Vector): RunReport {
  const actuals: Record<string, Result> = {}
  const abstainedV6: string[] = []
  const reprDivergences: string[] = []
  const gaps: string[] = []
  for (const e of doc.entries) {
    const r = runEntry(doc.schema ?? 'santa-eval/v1', e)
    if (r === ABSTAIN_V6) abstainedV6.push(e.name)
    else if (r === REPR_DIVERGENCE) reprDivergences.push(e.name)
    else if (r === COVERAGE_GAP) gaps.push(e.name)
    else actuals[e.name] = r
  }
  return { actuals, abstainedV6, reprDivergences, gaps }
}

// ---- CLI: runner <vector.json> [actuals-out.json] (mirrors Runner.scala) ----
function main(argv: string[]): void {
  const vecPath = argv[2]
  if (!vecPath) {
    console.error('usage: runner <vector.json> [<actuals-out.json>]')
    process.exit(2)
  }
  const doc = JSON.parse(readFileSync(vecPath, 'utf8')) as Vector
  const { actuals, abstainedV6, reprDivergences, gaps } = runVector(doc)
  const json = JSON.stringify(actuals, null, 2)
  const outPath = argv[3]
  if (outPath) {
    writeFileSync(outPath, json)
    console.error(`actuals → ${outPath}`)
  } else {
    console.log(json)
  }
  if (abstainedV6.length) console.error(`abstain · v6 UnsignedBigInt (out of scope, clean): ${abstainedV6.length}`)
  if (gaps.length) console.error(`naughty-by-coverage · op not implemented in ergots (route to ergots): ${gaps.length} — ${gaps.join(', ')}`)
  if (reprDivergences.length) console.error(`naughty-by-coverage · ergots cannot represent input (route to ergots): ${reprDivergences.length} — ${reprDivergences.join(', ')}`)
}

// Run main only when invoked as the bin, not when imported.
if (import.meta.url === `file://${process.argv[1]}`) main(process.argv)
