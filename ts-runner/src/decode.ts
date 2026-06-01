import type { SType, SValue } from '@ergots/ergoscript'
import { parseSValue } from '@ergots/ergoscript'
import { ByteReader } from '@ergots/scorex'
import { hexToBytes } from './hex'
import { stypeFromSanta } from './stype'
import { UnsupportedTypeError } from './abstain'
import type { Json } from './json'

/** A SANTA canonical-JSON object node (an SValue or SType). */
type JsonObj = { [k: string]: Json }

export interface Decoded { value: SValue; tpe: SType }

/** SANTA canonical JSON → ergots SValue + its SType (the tpe needed for the
 *  ctx var-1 binding). Inverse of encodeSValue. UnsignedBigInt is a type this runner does
 *  not implement → throws UnsupportedTypeError, surfaced as a `not-implemented` outcome
 *  (runner-contract §3). */
export function decodeSValue(j: JsonObj, treeVersion: number): Decoded {
  const kind = j['kind'] as string
  switch (kind) {
    case 'Boolean': return { value: { kind: 'Boolean', value: j['value'] as boolean }, tpe: { tag: 'SBoolean' } }
    case 'Byte': return { value: { kind: 'Byte', value: j['value'] as number }, tpe: { tag: 'SByte' } }
    case 'Short': return { value: { kind: 'Short', value: j['value'] as number }, tpe: { tag: 'SShort' } }
    case 'Int': return { value: { kind: 'Int', value: j['value'] as number }, tpe: { tag: 'SInt' } }
    case 'Long': return { value: { kind: 'Long', value: BigInt(j['value'] as string) }, tpe: { tag: 'SLong' } }
    case 'BigInt': return { value: { kind: 'BigInt', value: BigInt(j['value'] as string) }, tpe: { tag: 'SBigInt' } }
    case 'UnsignedBigInt': throw new UnsupportedTypeError('SValue UnsignedBigInt is v6-only')
    case 'GroupElement':
      return { value: { kind: 'GroupElement', value: hexToBytes(j['bytes_hex'] as string) }, tpe: { tag: 'SGroupElement' } }
    case 'Box': return parseBytesKind({ tag: 'SBox' }, j['bytes_hex'] as string, treeVersion)
    case 'Header': return parseBytesKind({ tag: 'SHeader' }, j['bytes_hex'] as string, treeVersion)
    case 'SigmaProp': return parseBytesKind({ tag: 'SSigmaProp' }, j['raw_hex'] as string, treeVersion)
    case 'Coll': {
      const elem = stypeFromSanta(j['elem'] as JsonObj)
      const items = (j['items'] as JsonObj[]).map((i) => decodeSValue(i, treeVersion).value)
      return { value: { kind: 'Coll', elem, items }, tpe: { tag: 'SColl', elem } }
    }
    case 'Tuple': {
      const decoded = (j['items'] as JsonObj[]).map((i) => decodeSValue(i, treeVersion))
      return {
        value: { kind: 'Tuple', items: decoded.map((d) => d.value) },
        tpe: { tag: 'STuple', items: decoded.map((d) => d.tpe) },
      }
    }
    case 'Option': {
      if (j['value'] === null) {
        // None carries no inner value, so its element type is unrecoverable from
        // JSON alone. No None-typed Option INPUT exists in the covered corpus
        // (Options appear as results, where decode is not invoked). If Task 9
        // surfaces one, recover elem from the parsed tree's arg type — do not guess.
        throw new Error('decode: None-Option input has no recoverable elem (unexpected in corpus)')
      }
      const inner = decodeSValue(j['value'] as JsonObj, treeVersion)
      return { value: { kind: 'Option', elem: inner.tpe, value: inner.value }, tpe: { tag: 'SOption', elem: inner.tpe } }
    }
    default:
      throw new Error(`decode: unknown SANTA SValue kind '${kind}'`)
  }
}

function parseBytesKind(tpe: SType, hex: string, treeVersion: number): Decoded {
  const value = parseSValue(tpe, treeVersion, new ByteReader(hexToBytes(hex)))
  return { value, tpe }
}
