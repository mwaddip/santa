import type { SValue } from '@ergots/ergoscript'
import { bytesToHex } from './hex'

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json }

/** ergots SValue → SANTA canonical JSON (contract §4). `treeVersion` is needed
 *  only by the bytes-kinds (Box/Header/SigmaProp) wire serializer in Task 5. */
export function encodeSValue(v: SValue, treeVersion: number): Json {
  switch (v.kind) {
    case 'Boolean': return { kind: 'Boolean', value: v.value }
    case 'Byte': return { kind: 'Byte', value: v.value }
    case 'Short': return { kind: 'Short', value: v.value }
    case 'Int': return { kind: 'Int', value: v.value }
    case 'Long': return { kind: 'Long', value: v.value.toString(10) }
    case 'BigInt': return { kind: 'BigInt', value: v.value.toString(10) }
    case 'GroupElement': return { kind: 'GroupElement', bytes_hex: bytesToHex(v.value) }
    default:
      throw new Error(`encode: unmodeled/unexpected ergots result kind '${(v as SValue).kind}'`)
  }
}
