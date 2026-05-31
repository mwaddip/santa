import { ErgoTreeParseError } from '@ergots/ergoscript'

/** Thrown SANTA-side when an entry needs a kind/type outside Dasher's declared
 *  (v5) scope. Caught by the runner → the entry is ABSTAINED (omitted from
 *  actuals), never `errored`. */
export class AbstainError extends Error {
  constructor(public readonly reason: string) {
    super(reason)
    this.name = 'AbstainError'
  }
}

/** True iff `err` is ergots' specific v6 SUnsignedBigInt SType rejection
 *  (`STypeParseError` code 'unsupported-type'), as opposed to a genuine parse
 *  bug. Keyed on the stable `.code` so it survives message changes; falls back
 *  to the message for the wrapped case. Any other parse failure must stay loud. */
export function isV6TypeRejection(err: unknown): boolean {
  const code = (err as { code?: unknown })?.code
  if (code === 'unsupported-type') return true
  const msg = err instanceof Error ? err.message : String(err)
  return err instanceof ErgoTreeParseError && /SUnsignedBigInt.*v6-only/i.test(msg)
}
