import { ErgoTreeParseError } from '@ergots/ergoscript'

/** Thrown SANTA-side when an entry's input/tree needs a type this runner does not
 *  implement (e.g. the v6 SUnsignedBigInt SType). The runner catches it and reports the
 *  entry as `not-implemented` — a faithful outcome, never omitted (runner-contract §3). */
export class UnsupportedTypeError extends Error {
  constructor(public readonly reason: string) {
    super(reason)
    this.name = 'UnsupportedTypeError'
  }
}

/** True iff `err` is ergots' SUnsignedBigInt SType rejection (`STypeParseError` code
 *  'unsupported-type') — a type this runner does not implement, as opposed to a genuine
 *  parse bug. Keyed on the stable `.code`; falls back to the message for the wrapped
 *  ErgoTreeParseError case. Any other parse failure must stay loud. */
export function isUnsupportedType(err: unknown): boolean {
  const code = (err as { code?: unknown })?.code
  if (code === 'unsupported-type') return true
  const msg = err instanceof Error ? err.message : String(err)
  return err instanceof ErgoTreeParseError && /SUnsignedBigInt.*v6-only/i.test(msg)
}
