import { readFileSync, writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import {
  parseTree, evaluateWith, makeContext, EvalError,
  parseSValue, serializeSValue, parseSType, serializeSType, SValueParseError, SValueSerializeError, type SType,
  parseSigmaBoolean, serializeSigmaBoolean, SigmaBooleanParseError, SigmaBooleanSerializeError,
  type ContextExtension, type ErgoBox,
} from '@ergots/ergoscript'
import { ByteReader, ByteWriter } from '@ergots/scorex'
import { decodeSValue } from './decode'
import { encodeSValue, type Json } from './encode'
import { UnsupportedTypeError, isUnsupportedType } from './abstain'
import { hexToBytes, bytesToHex } from './hex'

/** An SValue in SANTA canonical JSON (`{kind, …}`, contract §4). */
type SValueJson = { [k: string]: Json }

interface Entry {
  name: string
  tree_bytes_hex: string
  /** v2 + v4: the single input bound at ctx var 1. */
  input?: SValueJson
  /** v3: one spending-tx input per element, EACH with its own ContextExtension
   *  (`{<varId 0-255>: SValue}`) — read by Context.getVarFromInput; SELF = input 0. */
  inputs?: { extension: { [varId: string]: SValueJson } }[]
  /** v4: SELF's additional registers (`{"4".."9": SValue}` — R4-R9). */
  selfRegisters?: { [regId: string]: SValueJson }
  version: { activated: number; ergoTree: number }
}
interface Vector { schema: string; entries: Entry[] }

interface WireEntry {
  name: string
  kind: string
  bytes_hex: string
  version: { activated: number; ergoTree: number }
}
interface WireVector { schema: string; entries: WireEntry[] }

/** One entry's faithful outcome. The runner emits exactly one of these for EVERY entry —
 *  it never omits, never abstains, never pre-judges scope (runner-contract §3):
 *    - error null              → evaluated to a value (value + cost present)
 *    - error 'errored'         → the runner implements the op and eval threw (coarse; §7)
 *    - error 'not-implemented' → the runner has no impl for this op/method/type
 *    - error 'panicked'        → an otherwise-uncaught throw on this entry, caught so the run
 *                                continues; value/cost null, message in `note`. Coal
 *                                unconditionally (§6) — a crash is not a clean rejection.
 *  Every non-null tag has value:null, cost:null (panicked also carries `note`). Scope (which
 *  entries a conformer is expected to satisfy) is selected on the INPUT side (which vector
 *  dirs are run) and judged downstream — never here. */
type Result = {
  value: Json
  cost: number | null
  error: null | 'errored' | 'not-implemented' | 'panicked'
  note?: string
}

// Frozen so a consumer of the public runVector/runEntry API can't mutate the shared
// singleton (returned by reference, not copied, on every gap hit).
const NOT_IMPL: Result = Object.freeze({ value: null, cost: null, error: 'not-implemented' })

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

  // [1] decode + bind the entry's context per its envelope (rudolph Runner.scala dispatch):
  //       v2: single `input` → var 1 in SELF's ContextExtension (EvalCore.evalApplied);
  //       v3: `inputs[]` → the spending tx has ONE input per element, EACH with its own
  //           ContextExtension — read by Context.getVarFromInput(inputIdx, varId); SELF = input 0
  //           (EvalCore.evalWithInputExtensions);
  //       v4: v2's single `input` (var 1) PLUS `selfRegisters` on SELF's additional registers
  //           R4-R9 — read by dynamic-index Box.getReg (EvalCore.evalWithSelfRegistersAndVar1).
  //     A type the runner doesn't implement ⇒ not-implemented. ANY other decode failure —
  //     including an input ergots' own codec rejects at runtime (e.g. a Header timestamp > 2⁵³,
  //     which @ergots/scorex throws on as ReaderError 'vlq-overflow') — falls through to
  //     runEntry's panic-net ⇒ `panicked`. We record ergots' ACTUAL failure (message in `note`);
  //     we do not pre-classify it into a softer "unrepresentable" bucket on ergots' behalf.
  let ctx
  try {
    if (schema === 'santa-eval/v2') {
      if (!e.input) throw new Error(`missing input in v2 entry '${e.name}'`)
      const { value, tpe } = decodeSValue(e.input, treeVersion)
      // ConstPlaceholder resolution needs the tree's segregated constants; evaluateWith
      // (unlike evaluate) does NOT auto-populate them — pass explicitly.
      ctx = makeContext({ treeVersion, constants: tree.constants, extension: { values: { 1: { tpe, value } } } })
    } else if (schema === 'santa-eval/v3') {
      if (!e.inputs) throw new Error(`missing inputs in v3 entry '${e.name}'`)
      const inputExtensions = e.inputs.map((inp) => decodeExtension(inp.extension, treeVersion))
      // Top-level extension stays empty (self-getVar reads nothing), exactly as EvalCore's
      // contextWithInputExtensions passes ContextExtension.empty.
      ctx = makeContext({ treeVersion, constants: tree.constants, inputExtensions })
    } else if (schema === 'santa-eval/v4') {
      if (!e.input) throw new Error(`missing input in v4 entry '${e.name}'`)
      if (!e.selfRegisters) throw new Error(`missing selfRegisters in v4 entry '${e.name}'`)
      const { value, tpe } = decodeSValue(e.input, treeVersion)
      ctx = makeContext({
        treeVersion, constants: tree.constants,
        selfBox: dummySelfBox(e.selfRegisters, hexToBytes(e.tree_bytes_hex), treeVersion),
        extension: { values: { 1: { tpe, value } } },
      })
    } else {
      ctx = makeContext({ treeVersion, constants: tree.constants })
    }
  } catch (err) {
    if (err instanceof UnsupportedTypeError) return NOT_IMPL
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

/** SANTA `{<varId 0-255>: SValue}` map → an ergots ContextExtension. Keys are the unsigned
 *  wire byte on both sides (the 101:12 handler normalizes its signed Byte operand to this
 *  domain), so `Number(id)` is the identity bridge. */
function decodeExtension(ext: { [varId: string]: SValueJson }, treeVersion: number): ContextExtension {
  const values: ContextExtension['values'] = {}
  for (const [id, j] of Object.entries(ext)) {
    const { value, tpe } = decodeSValue(j, treeVersion)
    values[Number(id)] = { tpe, value }
  }
  return { values }
}

/** The v4 SELF box: EvalCore's dummy (value 1000000, ergoTree = the entry's tree, all-zero
 *  txId, index 0, creationHeight 0) carrying `selfRegisters` as its additional registers.
 *  Ids outside 4..9 are dropped, mirroring EvalCore.evalWithSelfRegistersAndVar1's collect —
 *  mandatory R0-R3 are synthesized from the box fields by both the JVM and ergots. */
function dummySelfBox(regs: { [regId: string]: SValueJson }, treeBytes: Uint8Array, treeVersion: number): ErgoBox {
  const registers: ErgoBox['registers'] = {}
  for (const [id, j] of Object.entries(regs)) {
    const n = Number(id)
    if (n < 4 || n > 9) continue
    const { value, tpe } = decodeSValue(j, treeVersion)
    registers[n] = { tpe, value }
  }
  return {
    value: 1000000n,
    ergoTreeBytes: treeBytes,
    registers,
    tokens: [],
    creationHeight: 0,
    txId: new Uint8Array(32),
    index: 0,
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

/** One wire entry's outcome — the round-trip analog of Result: a single bytes_hex replaces
 *  value+cost (the wire tier has no cost dimension). Same totality + never-panic invariants
 *  (runner-contract §3 / docs/specs/wire-tier.md):
 *    - error null              → reserialized; bytes_hex present (graded byte-identical downstream)
 *    - error 'errored'         → the runner's codec rejected the bytes (parse/reserialize threw)
 *    - error 'not-implemented' → no serializer for this kind reachable via the public API
 *    - error 'panicked'        → an otherwise-uncaught throw, caught so the run continues; note set */
type WireResult = {
  bytes_hex: string | null
  error: null | 'errored' | 'not-implemented' | 'panicked'
  note?: string
}

const SBOX: SType = { tag: 'SBox' }

/** Round-trip one wire entry → exactly one WireResult. Never-panic net mirrors runEntry: any
 *  otherwise-uncaught throw becomes `panicked` (note carries the message) so the run continues. */
export function runWireEntry(e: WireEntry): WireResult {
  try {
    return runWireEntryInner(e)
  } catch (err) {
    const note = err instanceof Error ? `${err.name}: ${err.message}` : String(err)
    return { bytes_hex: null, error: 'panicked', note }
  }
}

function runWireEntryInner(e: WireEntry): WireResult {
  const treeVersion = e.version.ergoTree
  switch (e.kind) {
    case 'Box': {
      // Full ErgoBox::sigma_serialize form (value … tokens … registers … tx_id … index) via the
      // public SValue codec — parseSValue(SBox)/serializeSValue(SBox) handle the with-ref form.
      const bytes = hexToBytes(e.bytes_hex)
      try {
        const value = parseSValue(SBOX, treeVersion, new ByteReader(bytes))
        const w = new ByteWriter()
        serializeSValue(SBOX, value, treeVersion, w)
        return { bytes_hex: bytesToHex(w.toBytes()), error: null }
      } catch (err) {
        // A codec rejection of the bytes is `errored` — a real round-trip divergence if the JVM
        // accepted them. Anything unexpected falls through to runWireEntry's panic-net.
        if (err instanceof SValueParseError || err instanceof SValueSerializeError) {
          return { bytes_hex: null, error: 'errored' }
        }
        throw err
      }
    }
    case 'SigmaBoolean': {
      // Bare SigmaBoolean (op_code + payload — the inner proposition tree, no SValue framing),
      // round-tripped via ergots' public parseSigmaBoolean/serializeSigmaBoolean (exported in
      // ergoscript-v6 @ 122957d, the reply to prompts/ergots-wire-sigmaboolean-export.md).
      const bytes = hexToBytes(e.bytes_hex)
      try {
        const sb = parseSigmaBoolean(new ByteReader(bytes))
        const w = new ByteWriter()
        serializeSigmaBoolean(sb, w)
        return { bytes_hex: bytesToHex(w.toBytes()), error: null }
      } catch (err) {
        if (err instanceof SigmaBooleanParseError || err instanceof SigmaBooleanSerializeError) {
          return { bytes_hex: null, error: 'errored' }
        }
        throw err
      }
    }
    case 'Constant': {
      // Self-describing constant: SType || SValue (the ContextExtension / register form). Read the
      // type, then its data, and write both back — via ergots' public parseSType + parseSValue /
      // serializeSType + serializeSValue (the codec ergots exposes for ContextExtension decoding).
      const bytes = hexToBytes(e.bytes_hex)
      try {
        const r = new ByteReader(bytes)
        const type = parseSType(r)
        const value = parseSValue(type, treeVersion, r)
        const w = new ByteWriter()
        serializeSType(type, w)
        serializeSValue(type, value, treeVersion, w)
        return { bytes_hex: bytesToHex(w.toBytes()), error: null }
      } catch (err) {
        if (err instanceof SValueParseError || err instanceof SValueSerializeError) {
          return { bytes_hex: null, error: 'errored' }
        }
        throw err
      }
    }
    default:
      // Transaction + Header stay not-implemented: ergots has no transaction serializer (it is an
      // eval library, not a tx builder), and a bare Header parses via a different (scorex) path.
      return { bytes_hex: null, error: 'not-implemented' }
  }
}

/** run(wire vector) → actuals: exactly one WireResult per entry, keyed by name (total). */
export function runWireVector(doc: WireVector): Record<string, WireResult> {
  const actuals: Record<string, WireResult> = {}
  for (const e of doc.entries) actuals[e.name] = runWireEntry(e)
  return actuals
}

// ---- Transaction tier ----
// ergots is an eval library; aggregate tx validation (conservation, tokens, min-value, cost) is
// not implemented. Returning valid:true from script-verify alone would be an unsound false-green
// (e.g. a non-conserving tx passes script checks but must be rejected). The faithful outcome for
// every entry is not-implemented — a coverage verdict, not coal. These entries are the growth
// ledger: they flip green as ergots grows its tx-validation layer (runner-contract §6).

interface TxVectorEntry { name: string }
interface TxVector { schema: string; entries: TxVectorEntry[] }

/** One transaction entry's faithful outcome. */
type TxResult = {
  valid: boolean | null
  cost: number | null
  error: null | 'errored' | 'not-implemented' | 'panicked'
  note?: string
}

// Frozen singleton — returned by reference on every entry (never mutated).
const TX_NOT_IMPL: TxResult = Object.freeze({ valid: null, cost: null, error: 'not-implemented' })

/** run(transaction vector) → actuals: exactly one TxResult per entry, keyed by name (total). */
export function runTransactionVector(doc: TxVector): Record<string, TxResult> {
  const actuals: Record<string, TxResult> = {}
  for (const e of doc.entries) actuals[e.name] = TX_NOT_IMPL
  return actuals
}

// ---- CLI: runner <vector.json> [actuals-out.json] (mirrors Runner.scala) ----
function main(argv: string[]): void {
  const vecPath = argv[2]
  if (!vecPath) {
    console.error('usage: runner <vector.json> [<actuals-out.json>]')
    process.exit(2)
  }
  const raw = JSON.parse(readFileSync(vecPath, 'utf8')) as { schema?: string }
  const schema = raw.schema ?? ''
  const actuals = schema.startsWith('santa-wire/')
    ? runWireVector(raw as WireVector)
    : schema.startsWith('santa-transaction/')
    ? runTransactionVector(raw as TxVector)
    : runVector(raw as Vector)
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
