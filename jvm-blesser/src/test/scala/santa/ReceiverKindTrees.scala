package santa

// A v3 MethodCall whose RECEIVER is of another kind. Shared by the eval, wire and transaction
// blessers so all three grade the same bytes.
//
// Measured on the JVM at 6.0.7, activated 3, by `santa.spike.ReceiverKindProbe`; the request is
// `prompts/santa-probe-receiver-kind.md` (sigma-rust session, 2026-11-08). Every expression hex
// below is the sigma-rust session's own; all were checked against the ASTs and all parse.
//
// `MethodCallSerializer.parse` builds the call with no receiver check — `specializeFor(obj.tpe, …)`
// returns the method unchanged when the types do not unify — so a call whose receiver is an `Int`
// constant PARSES. At eval the receiver reaches a reflective invoke, which refuses it.
//
// **THE EXCEPTION CLASS IS `IllegalArgumentException`, NOT `ClassCastException`** — and the
// sigma-rust session reached that itself, correcting its own probe while this was being measured:
// the `ReflectionData` lambdas with the `asInstanceOf` casts are the **Scala.js** path, while on
// the JVM the call goes through `java.lang.reflect`, which rejects the receiver before any lambda
// body runs. Measured, exactly as their correction predicts, including its guess about powHit:
//   `IllegalArgumentException: object is not an instance of declaring class`   (rows 2,3,5,9,10)
//   `IllegalArgumentException: argument type mismatch`                          (row 7, powHit)
// powHit differs because its receiver is `args(1)` rather than the invocation target, so
// reflection reports an argument mismatch instead of a bad receiver. Neither message is stable
// enough to match on, and the CLASS differs by platform — so the eval tier's coarse `errored` is
// the right granularity here, and a conformer must not match either string.
//
// Line numbers verified at 6.0.7 — every one the probe cited from 6.0.6 still holds, and the ids
// and costs match: `encodeNbits` id 6 FixedCost(25) (`methods.scala:1939`), `decodeNbits` id 7
// FixedCost(50) (`:1944`), `getVarFromInput` (`:1755`), `powHit` (`:1884`). Type codes:
// SGlobal 106 = 0x6a, SContext 101 = 0x65.

import sigma.VersionContext

object ReceiverKindTrees {
  val Activated: Byte = VersionContext.V6SoftForkVersion // 3

  /** One row: the bare call, for the eval tier. `control` rows evaluate; the rest throw. */
  case class Row(n: Int, slug: String, expr: String, control: Boolean, what: String)

  val rows: Seq[Row] = Seq(
    Row(1, "encode_nbits_global_control", "dc6a06dd01060101", control = true,
      "`Global.encodeNbits(BigInt 1)` with the receiver `Global` — the CONTROL. Evaluates to " +
      "`16842752L` at cost 39."),
    Row(2, "encode_nbits_int_receiver", "dc6a06040201060101", control = false,
      "`encodeNbits` with the receiver an `Int` CONSTANT. Parses — the serializer checks no " +
      "receiver — and the reflective invoke then refuses it. **SEPARATING:** an implementation " +
      "whose evaluator never reads its receiver returns the control's value instead."),
    Row(3, "encode_nbits_context_receiver", "dc6a06fe01060101", control = false,
      "`encodeNbits` with the receiver `CONTEXT` — a wrong receiver that is nonetheless a " +
      "first-class one, not a constant. Same refusal, which is what shows the check is about the " +
      "KIND and not about constant-versus-node. **SEPARATING.**"),
    Row(4, "decode_nbits_global_control", "dc6a07dd010580808810", control = true,
      "`Global.decodeNbits(16842752L)` with the receiver `Global` — the CONTROL. Evaluates to " +
      "`BigInt 1` at cost 64."),
    Row(5, "decode_nbits_int_receiver", "dc6a070402010580808810", control = false,
      "`decodeNbits` with an `Int` receiver. **SEPARATING.**"),
    Row(6, "pow_hit_global_control", "dc6a08dd0504400e070a101b8c6a4f2e0e08000000000000002c0e04000000000480808001",
      control = true,
      "`Global.powHit(32, msg, nonce, h, 1048576)` with the receiver `Global` — the CONTROL. " +
      "Evaluates to the UnsignedBigInt the sigma-rust session's own `pow_hit_eval` test expects, " +
      "at cost 765."),
    Row(7, "pow_hit_int_receiver", "dc6a0804020504400e070a101b8c6a4f2e0e08000000000000002c0e04000000000480808001",
      control = false,
      "`powHit` with an `Int` receiver. Its receiver is `args(1)` rather than the invocation " +
      "target, so reflection reports `argument type mismatch` instead of `object is not an " +
      "instance of declaring class` — a different message from every other row here, and the " +
      "reason to grade this family coarsely. **SEPARATING.**"),
    Row(8, "get_var_from_input_context_control", "dc650cfe020300020104", control = true,
      "`CONTEXT.getVarFromInput[Int](0, 1)` with the receiver `CONTEXT` — the CONTROL. Evaluates " +
      "to `None` at cost 25 (the canonical eval context binds no such variable)."),
    Row(9, "get_var_from_input_global_receiver", "dc650cdd020300020104", control = false,
      "`getVarFromInput` with the receiver `Global` — the mirror of row 3: a `Context` method " +
      "given the `Global` singleton. **SEPARATING.**"),
    Row(10, "get_var_from_input_int_receiver", "dc650c0402020300020104", control = false,
      "`getVarFromInput` with an `Int` receiver. **SEPARATING.**"))

  def rowBySlug(slug: String): Row =
    rows.find(_.slug == slug).getOrElse(sys.error(s"no such row: $slug"))

  /** The v3 size-flagged tree around a bare call. Its root is NOT a SigmaProp, so a STRICT parse
    * degrades it on rule 1001 (`CheckDeserializedScriptIsSigmaProp`) — the eval tier reads it
    * through the lenient path, as every value-returning eval vector does. */
  def evalTree(expr: String): String =
    "0b" + RentFixtures.hex(RentFixtures.vlqU32((expr.length / 2).toLong)) + expr

  /** `sigmaProp(row == control)`, the wrapped form: a proper SigmaProp root, so it PARSES. These
    * are the sigma-rust session's own encodings and all seven round-trip identically on the JVM.
    * The control row (1) reduces to `SigmaProp(true)`; the other six fail at eval — which is the
    * form a node sees, and where this becomes a spend-level over-accept. */
  val wrappedTrees: Seq[(Int, String)] = Seq(
    1  -> "0b12d193dc6a06dd01060101dc6a06dd01060101",
    2  -> "0b13d193dc6a06040201060101dc6a06dd01060101",
    3  -> "0b12d193dc6a06fe01060101dc6a06dd01060101",
    5  -> "0b17d193dc6a070402010580808810dc6a07dd010580808810",
    7  -> "0b4dd193dc6a0804020504400e070a101b8c6a4f2e0e08000000000000002c0e04000000000480808001dc6a08dd0504400e070a101b8c6a4f2e0e08000000000000002c0e04000000000480808001",
    9  -> "0b16d193dc650cdd020300020104dc650cfe020300020104",
    10 -> "0b17d193dc650c0402020300020104dc650cfe020300020104")

  def wrappedFor(n: Int): String =
    wrappedTrees.find(_._1 == n).map(_._2).getOrElse(sys.error(s"no wrapped tree for row $n"))

  /** Row 1's wrapper is the only one that reduces to true, so it is the spend control. */
  val SpendControlRow = 1
}
