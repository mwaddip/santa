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

/** One entry's faithful outcome. The runner emits exactly one of these for EVERY entry —
 *  it never omits, never abstains, never pre-judges scope (runner-contract §3):
 *    - error null              → evaluated to a value (value + cost present)
 *    - error 'errored'         → the runner implements the op and eval threw (coarse; §7)
 *    - error 'not-implemented' → the runner has no impl for this op/method/type
 *    - error 'unrepresentable' → the runner HAS the type but can't represent this value
 *    - error 'panicked'        → an otherwise-uncaught throw on this entry, caught so the run
 *                                continues; value/cost null, message in `note`. Coal
 *                                unconditionally (§6) — a crash is not a clean rejection.
 *  Every non-null tag has value:null, cost:null (panicked also carries `note`). Scope (which
 *  entries a conformer is expected to satisfy) is selected on the INPUT side (which vector
 *  dirs are run) and judged downstream — never here. */
type Result = {
  value: Json
  cost: number | null
  error: null | 'errored' | 'not-implemented' | 'unrepresentable' | 'panicked'
  note?: string
}

// Frozen so a consumer of the public runVector/runEntry API can't mutate the shared
// singleton (these are returned by reference, not copied, on every gap/limit hit).
const NOT_IMPL: Result = Object.freeze({ value: null, cost: null, error: 'not-implemented' })
const UNREPRESENTABLE: Result = Object.freeze({ value: null, cost: null, error: 'unrepresentable' })

/** ergots ReaderError code for an input the v5 codec cannot represent (a Header
 *  timestamp > Number.MAX_SAFE_INTEGER). Confirmed in @ergots/scorex header.ts:72. */
function isReprLimit(err: unknown): boolean {
  return (err as { code?: unknown })?.code === 'vlq-overflow'
}

/** Run one entry → exactly one Result. Never-panic invariant (runner-contract §3): any
 *  otherwise-uncaught throw — a malformed entry, an unexpected codec or internal error — is
 *  caught here and surfaced as the `panicked` outcome (coal, message in `note`) so the run
 *  continues; a crash on one entry never aborts the file. The inner `throw err` sites land here. */
export function runEntry(schema: string, e: Entry): Result {
  try {
    return runEntryInner(schema, e)
  } catch (err) {
    const note = err instanceof Error ? `${err.name}: ${err.message}` : String(err)
    return { value: null, cost: null, error: 'panicked', note }
  }
}

/** The per-entry pipeline ([0] parse → [1] decode/bind → [2] eval/encode). Unrecognized throws
 *  propagate to runEntry's panic-net above (→ `panicked`). */
function runEntryInner(schema: string, e: Entry): Result {
  const treeVersion = e.version.ergoTree

  // [0] parseTree — a type the runner doesn't implement (e.g. v6 SUnsignedBigInt) ⇒
  //     not-implemented; ANY other parse failure falls through to runEntry's panic-net
  //     (⇒ panicked), NOT errored.
  let tree
  try {
    tree = parseTree(hexToBytes(e.tree_bytes_hex))
  } catch (err) {
    if (isUnsupportedType(err)) return NOT_IMPL
    throw err
  }

  // [1] decode + bind input at ctx var 1 (v2 only).
  //     A type the runner doesn't implement ⇒ not-implemented; an input it can't represent
  //     (Header ts overflow ⇒ ReaderError 'vlq-overflow') ⇒ unrepresentable; else ⇒ panic-net.
  let ctx
  try {
    if (schema === 'santa-eval/v2') {
      if (!e.input) throw new Error(`missing input in v2 entry '${e.name}'`)
      const { value, tpe } = decodeSValue(e.input, treeVersion)
      // ConstPlaceholder resolution needs the tree's segregated constants; evaluateWith
      // (unlike evaluate) does NOT auto-populate them — pass explicitly.
      ctx = makeContext({ treeVersion, constants: tree.constants, extension: { values: { 1: { tpe, value } } } })
    } else {
      ctx = makeContext({ treeVersion, constants: tree.constants })
    }
  } catch (err) {
    if (err instanceof UnsupportedTypeError) return NOT_IMPL
    if (isReprLimit(err)) return UNREPRESENTABLE
    throw err
  }

  // [2] eval ⇒ [3] encode ⇒ [4] capture.
  //     encode's deliberate UnsupportedTypeError (UnsignedBigInt) ⇒ not-implemented;
  //     EvalError 'method-not-implemented' ⇒ not-implemented (the runner lacks this op/method);
  //     any other EvalError ⇒ errored; a non-EvalError throw ⇒ runEntry's panic-net (panicked).
  try {
    const out = evaluateWith(tree, ctx)
    return { value: encodeSValue(out, treeVersion), cost: ctx.jitCost, error: null }
  } catch (err) {
    if (err instanceof UnsupportedTypeError) return NOT_IMPL // encode marked a deliberate not-impl (UnsignedBigInt)
    if (err instanceof EvalError && err.code === 'method-not-implemented') return NOT_IMPL
    if (err instanceof EvalError) return { value: null, cost: null, error: 'errored' }
    throw err
  }
}

/** run(vector) → actuals: exactly one Result per entry, keyed by name. Total — never omits
 *  an entry, never aborts the file (runner-contract §3). No scope logic, no buckets. */
export function runVector(doc: Vector): Record<string, Result> {
  const actuals: Record<string, Result> = {}
  for (const e of doc.entries) {
    actuals[e.name] = runEntry(doc.schema ?? 'santa-eval/v1', e)
  }
  return actuals
}

// ---- CLI: runner <vector.json> [actuals-out.json] (mirrors Runner.scala) ----
function main(argv: string[]): void {
  const vecPath = argv[2]
  if (!vecPath) {
    console.error('usage: runner <vector.json> [<actuals-out.json>]')
    process.exit(2)
  }
  const doc = JSON.parse(readFileSync(vecPath, 'utf8')) as Vector
  const actuals = runVector(doc)
  const json = JSON.stringify(actuals, null, 2)
  const outPath = argv[3]
  if (outPath) {
    writeFileSync(outPath, json)
    console.error(`actuals → ${outPath}`)
  } else {
    console.log(json)
  }
}

// Run main only when invoked as the bin (not when imported). pathToFileURL resolves a
// relative argv path to an absolute file URL so the comparison is robust.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main(process.argv)
