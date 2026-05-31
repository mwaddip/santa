import type { SValue, SType } from '@ergots/ergoscript'
import { serializeSValue } from '@ergots/ergoscript'
import { ByteWriter } from '@ergots/scorex'
import { bytesToHex } from './hex'
import { stypeToSanta } from './stype'
import type { Json } from './json'

export type { Json }

function serializeBytesKind(t: SType, v: SValue, treeVersion: number): string {
  const w = new ByteWriter()
  serializeSValue(t, v, treeVersion, w)
  return bytesToHex(w.toBytes())
}

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
    case 'Coll':
      return { kind: 'Coll', elem: stypeToSanta(v.elem), items: v.items.map((i) => encodeSValue(i, treeVersion)) }
    case 'Tuple':
      return { kind: 'Tuple', items: v.items.map((i) => encodeSValue(i, treeVersion)) }
    case 'Option':
      // SANTA Option has NO elem (contract §4); drop ergots' elem.
      return { kind: 'Option', value: v.value === null ? null : encodeSValue(v.value, treeVersion) }
    case 'Box':
      return { kind: 'Box', bytes_hex: serializeBytesKind({ tag: 'SBox' }, v, treeVersion) }
    case 'Header':
      return { kind: 'Header', bytes_hex: serializeBytesKind({ tag: 'SHeader' }, v, treeVersion) }
    case 'SigmaProp':
      return { kind: 'SigmaProp', raw_hex: serializeBytesKind({ tag: 'SSigmaProp' }, v, treeVersion) }
    default:
      throw new Error(`encode: unmodeled/unexpected ergots result kind '${(v as SValue).kind}'`)
  }
}
