package santa

// Authored eval vectors for sigma-rust's santa-probe-context-property-calls (2026-11-13). Measured first in
// santa.spike.ContextPropertyCallProbe; the reply is prompts/drafts/sigma-rust-context-property-calls-reply.md.
//
// THE RULE, read in 6.0.7 and measured: `SContextMethods` lists INPUTS (4), OUTPUTS (5), HEIGHT (6) and SELF (7)
// (methods.scala:1742-1745) beside their dedicated nodes `Inputs`/`Outputs`/`Height`/`Self`, in the method table
// of EVERY tree version (`commonMethods`). A tree can carry the method form: a `PropertyCall` on `CONTEXT`,
// `db 65 <id> fe`. The JVM EVALUATES it to the node's value. `property(name, tpe, id, node)` (methods.scala:222)
// gives the method the node's own cost kind (Fixed 10 / 10 / 26 / 10); `MethodCall.eval` charges its own 4
// (values.scala:1363) and that fixed cost around the reflective invoke of `sigma.Context.INPUTS()` etc.
// (:1377-1378, SMethod.scala:124), after `Context`'s 1. So the call costs exactly the node + 5.
//
// THE FAMILY, `Context.property_call_forms` (v5: activated 2, tree 0) and `Context.property_call_forms_v6`
// (activated 3, trees 3 and 0): the probe's four rows I/O/H/S beside their node controls (its eight trees, verbatim
// bodies), the three collection/box calls bare beside their bare nodes, and `call == node` for each. The context is
// the contract §2 one: INPUTS = [SELF], OUTPUTS = [], HEIGHT 0, SELF.value 1000000, var 1 = Int 0 (unread). Every
// row accepts: a refusal is the divergence (sigma-rust's `NotFound`).
//
// THE BARE BOX ROWS (Ib/Ibc/Sb/Sbc) ARE AUTHORED AT TREE v3 ONLY. Their graded value is the SELF box, which
// carries the row's own tree, and an UNSIZED tree whose root is not a SigmaProp is a box no node can parse:
// `ErgoBox.sigmaSerializer` refuses `c0843d 00a4 …` (rule 1001, "ErgoTree serialized without size bit";
// measured, ContextPropertyCallProbe §4), while the v3 box (`0b01a4`, sized) parses and re-serializes
// byte-identically. The oracle could still return the v0 box, because EvalCore wraps SELF around an
// already-parsed tree; a v0 row would grade bytes that no chain can hold. So 18 rows at a sized tree, 14 at v0.

import io.circe.Json
import scorex.util.encode.Base16

import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{SigmaSerializer, ValueSerializer}

object AuthoredEvalContextPropertyCalls {
  val V5: Byte = VersionContext.JitActivationVersion // 2
  val V6: Byte = VersionContext.V6SoftForkVersion    // 3
  val Source = "santa:authored-context-property-calls"
  val Stamp = "jvm:sigma-state-6.0.7"
  val OpV5 = "Context.property_call_forms"
  val OpV6 = "Context.property_call_forms_v6"
  /** (activated, tree version) per file, in entry order. */
  val Combos: Map[String, Seq[(Byte, Int)]] = Map(OpV5 -> Seq((V5, 0)), OpV6 -> Seq((V6, 3), (V6, 0)))

  val IntInput: Json = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def coll(v: Value[SType]) = v.asInstanceOf[Value[SCollection[SType]]]
  private def box(v: Value[SType]) = v.asInstanceOf[Value[SBox.type]]

  /** `CONTEXT.<method id>` with no arguments, as the JVM writer emits it: a `PropertyCall` (db). */
  def pc(id: Int, a: Byte, t: Int): Value[SType] = VersionContext.withVersions(a, t.toByte) {
    any(MethodCall(any(Context), SMethod.fromIds(101.toByte, id.toByte), IndexedSeq(), Map()))
  }
  def body(root: Value[SType], a: Byte, t: Int): String = VersionContext.withVersions(a, t.toByte) {
    val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(root, w); hx(w.toBytes)
  }
  /** Tree version 0: header 00, unsized. Tree version 3: header 0b (size bit), the size, the body. */
  def tree(root: Value[SType], a: Byte, t: Int): String = {
    val bd = body(root, a, t)
    t match {
      case 0 => "00" + bd
      case 3 => "0b" + vlq(bd.length / 2) + bd
    }
  }

  /** One row: an id (a control is the node form of the row named by its id less `c`), its script, its root. */
  case class Row(id: String, script: String, mk: (Byte, Int) => Value[SType], slug: String,
                 returnsBox: Boolean = false) {
    /** A row whose value is the SELF box only at a sized tree (see the header). */
    def authoredAt(t: Int): Boolean = !(returnsBox && t == 0)
  }

  private val sep = "SEPARATING: an implementation with no evaluator for the method id refuses."
  private def plus5(node: String) =
    s"the method carries the node's own cost kind, and MethodCall.eval adds its 4 and Context's 1: $node + 5"

  val Rows: Seq[Row] = Seq(
    Row("I", "`CONTEXT.INPUTS.size`, INPUTS as a PropertyCall (SContext 4, `db6504fe`) — Int 1 at cost 29: " +
      s"${plus5("Ic's 24")}. $sep", (a, t) => any(SizeOf(coll(pc(4, a, t)))), "inputs-propertycall-size-accept"),
    Row("Ic", "`INPUTS.size`, the `Inputs` node (`a4`) — Int 1 at cost 24. CONTROL for I.",
      (_, _) => any(SizeOf(coll(any(Inputs)))), "inputs-node-size-accept"),
    Row("O", "`CONTEXT.OUTPUTS.size`, OUTPUTS as a PropertyCall (SContext 5, `db6505fe`) — Int 0 at cost 29: " +
      s"${plus5("Oc's 24")}. $sep", (a, t) => any(SizeOf(coll(pc(5, a, t)))), "outputs-propertycall-size-accept"),
    Row("Oc", "`OUTPUTS.size`, the `Outputs` node (`a5`) — Int 0 at cost 24. CONTROL for O.",
      (_, _) => any(SizeOf(coll(any(Outputs)))), "outputs-node-size-accept"),
    Row("H", "`CONTEXT.HEIGHT`, HEIGHT as a PropertyCall (SContext 6, `db6506fe`) — Int 0 at cost 31: " +
      s"${plus5("Hc's 26")} (Height's own cost kind is Fixed 26). $sep",
      (a, t) => pc(6, a, t), "height-propertycall-accept"),
    Row("Hc", "`HEIGHT`, the `Height` node (`a3`) — Int 0 at cost 26. CONTROL for H.",
      (_, _) => any(Height), "height-node-accept"),
    Row("S", "`CONTEXT.SELF.value`, SELF as a PropertyCall (SContext 7, `db6507fe`) — Long 1000000 at cost 23: " +
      s"${plus5("Sc's 18")}. $sep", (a, t) => any(ExtractAmount(box(pc(7, a, t)))), "self-propertycall-value-accept"),
    Row("Sc", "`SELF.value`, the `Self` node (`a7`) — Long 1000000 at cost 18. CONTROL for S.",
      (_, _) => any(ExtractAmount(box(any(Self)))), "self-node-value-accept"),
    Row("Ib", "`CONTEXT.INPUTS`, bare — `Coll[Box](SELF)` at cost 15: Context 1 + PropertyCall 4 + the method's " +
      s"Fixed 10, the `Inputs` node's 10 (Ibc) + 5. $sep", (a, t) => pc(4, a, t), "inputs-propertycall-bare-accept", returnsBox = true),
    Row("Ibc", "`INPUTS`, the bare `Inputs` node — `Coll[Box](SELF)` at cost 10. CONTROL for Ib.",
      (_, _) => any(Inputs), "inputs-node-bare-accept", returnsBox = true),
    Row("Ob", "`CONTEXT.OUTPUTS`, bare — `Coll[Box]()` at cost 15, the `Outputs` node's 10 (Obc) + 5. " + sep,
      (a, t) => pc(5, a, t), "outputs-propertycall-bare-accept"),
    Row("Obc", "`OUTPUTS`, the bare `Outputs` node — `Coll[Box]()` at cost 10. CONTROL for Ob.",
      (_, _) => any(Outputs), "outputs-node-bare-accept"),
    Row("Sb", "`CONTEXT.SELF`, bare — the SELF box at cost 15, the `Self` node's 10 (Sbc) + 5. " + sep,
      (a, t) => pc(7, a, t), "self-propertycall-bare-accept", returnsBox = true),
    Row("Sbc", "`SELF`, the bare `Self` node — the SELF box at cost 10. CONTROL for Sb.",
      (_, _) => any(Self), "self-node-bare-accept", returnsBox = true),
    Row("I=", "`CONTEXT.INPUTS == INPUTS` — true at cost 46: the call's value IS the node's. " + sep,
      (a, t) => any(EQ(pc(4, a, t), any(Inputs))), "inputs-propertycall-eq-node-accept"),
    Row("O=", "`CONTEXT.OUTPUTS == OUTPUTS` — true at cost 41. " + sep,
      (a, t) => any(EQ(pc(5, a, t), any(Outputs))), "outputs-propertycall-eq-node-accept"),
    Row("H=", "`CONTEXT.HEIGHT == HEIGHT` — true at cost 60. " + sep,
      (a, t) => any(EQ(pc(6, a, t), any(Height))), "height-propertycall-eq-node-accept"),
    Row("S=", "`CONTEXT.SELF == SELF` — true at cost 31. " + sep,
      (a, t) => any(EQ(pc(7, a, t), any(Self))), "self-propertycall-eq-node-accept"))

  def extract(): Map[String, Json] =
    Seq(OpV5, OpV6).map { op =>
      val entries = Combos(op).flatMap { case (a, t) => Rows.filter(_.authoredAt(t)).map(r => (a, t, r)) }.zipWithIndex.map {
        case ((a, t, r), i) =>
          SpecExtract.authoredEntryV(op, s"[${r.id}] ${r.script}  // tree v$t, activated $a", tree(r.mk(a, t), a, t),
            s"${r.slug}-t$t#$i", IntInput, a, ergoTree = t)
      }
      op -> SpecExtract.authoredEnvelope(op, entries, Source).mapObject(_.add("blessed_by", Json.fromString(Stamp)))
    }.toMap

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalContextPropertyCalls", extract(), outDir)
}
