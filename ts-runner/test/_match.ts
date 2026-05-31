/** Structural equality per runner-contract §5:
 *  - objects: key-order-INsensitive, identical key sets, recursive
 *  - arrays: order-SENSITIVE (Coll/Tuple items are positional)
 *  - numbers numeric, strings exact, null === null only.
 *  Implementable identically in any language — this is the conformance comparator. */
export function structuralEqual(a: unknown, b: unknown): boolean {
  if (a === null || b === null) return a === b
  if (typeof a !== typeof b) return false
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false
    return a.every((x, i) => structuralEqual(x, b[i])) // order-SENSITIVE
  }
  if (typeof a === 'object') {
    const ao = a as Record<string, unknown>
    const bo = b as Record<string, unknown>
    const ak = Object.keys(ao)
    const bk = Object.keys(bo)
    if (ak.length !== bk.length) return false
    return ak.every((k) => k in bo && structuralEqual(ao[k], bo[k])) // key-order-INsensitive
  }
  return a === b // number numeric, string exact, boolean exact
}
