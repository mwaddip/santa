package santa

// ─────────────────────────────────────────────────────────────────────────────
// Authored checkType / non-pair-tuple witnesses — four hand-crafted WIRE forms the
// JVM oracle (sigma-state 6.0.3) ERRORS on, each pinning a distinct type-check /
// tuple-arity seam. Constructed + JVM-oracle-confirmed by the W1 spike. All four are
// reject vectors (value/cost null, error "errored"); re-blessed here through the SAME
// EvalCore oracle (fail-loud if any outcome drifts to a success).
//
//   Tuple.checkType_unsupported — TWO entries, same JVM error string
//     ("RuntimeException: Unsupported tuple type (SBoolean,SBoolean,SBoolean)") via
//     two DISTINCT SType.checkType seams (an inline (Bool,Bool,Bool) constant is not a
//     legal tuple-item type — sigma-state models tuples as nested pairs):
//       inline#0      008602480101010101010402
//         v0 pair Tuple( (Bool,Bool,Bool) inline constant, Int 1 ) — checkType rejects
//         the 3-tuple item via the INLINE-CONSTANT path.
//       placeholder#0 1002480101010101010402860273007301
//         seg v0 (header 0x10); the (Bool,Bool,Bool) constant is lifted to the constants
//         array, body Tuple(ConstantPlaceholder(0,(Bool,Bool,Bool)), Int 1) — same error
//         but via ConstantPlaceholder.eval's checkType (values.scala:408-414), a distinct
//         seam from the inline form. (Tree = re-serialization of inline#0 with segregated
//         constants; blessed from the exact spike bytes.)
//
//   SelectField.non_pair    008c6001040a01
//     v0 SelectField(Const((Int,)[5]), 1) — a 1-tuple, not a pair. JVM
//     InterpreterException ("Invalid type returned by evaluator … resulting value:
//     Coll(5)"): SelectField.eval only matches a runtime Tuple2; a 1-tuple is a Coll
//     (transformers.scala:300-307).
//
//   EQ.non_pair_tuple_operand   10060402040404060402040404069386037300730173028603730373047305
//     seg v0 EQ(Tuple(1,2,3), Tuple(1,2,3)). JVM InterpreterException ("Invalid tuple
//     Tuple(…)"): Tuple.eval rejects arity≠2 (values.scala:797) BEFORE EQ compares.
//     This is the EQ-WRAPPED sibling of AuthoredArithTuple's `Tuple.non_pair_arity3`
//     (distinct: proves a non-pair tuple can't even be an EQ OPERAND). NOTE: ergots
//     evaluates this to `true @ 17` → the vector pins an ergots OVER-ACCEPT.
//
// All four are ErgoTree v0 (native header 0x00 / segregated-v0 0x10), so
// version = {activated 2, ergoTree 0}; eval is version-independent (spike: identical
// at activated 2 and 3), pinned on the broad v5/mainnet surface — exactly like
// AuthoredArithTuple's non-pair vector, with which these co-locate in
// vectors/eval/v5/authored/.
// ─────────────────────────────────────────────────────────────────────────────

import io.circe.Json

object AuthoredCheckType {

  val Activated: Byte = 2 // v5 / mainnet surface
  val ErgoTreeV0: Int = 0 // the witness trees' native header (plain v0 / segregated v0)
  val Source = "santa:authored-checktype"

  val OpTupleCheckType = "Tuple.checkType_unsupported"
  val OpSelectField    = "SelectField.non_pair"
  val OpEqNonPair      = "EQ.non_pair_tuple_operand"

  // closed trees (inline / segregated constants, no var read) → the dummy input is ignored
  private val dummyInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  def extract(): Map[String, Json] = {
    val tupleCheckType = Seq(
      SpecExtract.authoredRejectEntryV(OpTupleCheckType,
        "{ Tuple( (Bool,Bool,Bool) inline constant, 1 ) }  // JVM: Unsupported tuple type — SType.checkType rejects a 3-tuple item (inline-constant seam)",
        "008602480101010101010402", "checktype-inline-errored#0", dummyInput, Activated, ErgoTreeV0),
      SpecExtract.authoredRejectEntryV(OpTupleCheckType,
        "{ Tuple( ConstantPlaceholder(0,(Bool,Bool,Bool)), 1 ) }  // JVM: Unsupported tuple type — same error via ConstantPlaceholder.eval checkType (distinct seam)",
        "1002480101010101010402860273007301", "checktype-placeholder-errored#0", dummyInput, Activated, ErgoTreeV0))
    val selectField = Seq(
      SpecExtract.authoredRejectEntryV(OpSelectField,
        "{ SelectField((5,), 1) — a 1-tuple, not a pair }  // JVM: Invalid type returned by evaluator (1-tuple is a Coll, not a Tuple2)",
        "008c6001040a01", "selectfield-non-pair-errored#0", dummyInput, Activated, ErgoTreeV0))
    val eqNonPair = Seq(
      SpecExtract.authoredRejectEntryV(OpEqNonPair,
        "{ EQ((1,2,3), (1,2,3)) — non-pair tuple operands }  // JVM: Invalid tuple — Tuple.eval rejects arity!=2 before EQ compares (ergots over-accepts → true @ 17)",
        "10060402040404060402040404069386037300730173028603730373047305", "eq-non-pair-tuple-errored#0", dummyInput, Activated, ErgoTreeV0))
    Map(
      OpTupleCheckType -> SpecExtract.authoredEnvelope(OpTupleCheckType, tupleCheckType, Source),
      OpSelectField    -> SpecExtract.authoredEnvelope(OpSelectField, selectField, Source),
      OpEqNonPair      -> SpecExtract.authoredEnvelope(OpEqNonPair, eqNonPair, Source))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredCheckType", extract(), outDir)
}
