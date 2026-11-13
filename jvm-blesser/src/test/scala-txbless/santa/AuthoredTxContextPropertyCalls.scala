package santa

// AuthoredTxContextPropertyCalls — the CONSENSUS form of the Context property-call family
// (`AuthoredEvalContextPropertyCalls`, sigma-rust's santa-probe-context-property-calls, 2026-11-13), with the
// distinct values the eval tier's contract §2 context cannot give: there HEIGHT is 0 and SELF is input 0, so an
// evaluator that wires a method id to the wrong context field can still answer right. Here it cannot.
//
// THE SPEND: two inputs, the probe box SECOND (SELF = input 1, value 1234567890; input 0 is a true-script box of
// 1000000000) and three outputs, at preHeader height H = 1051200, whose nine headers sit at H-9..H-1. Each probe
// script is `sigmaProp(p)` for one of, per method:
//  * `CONTEXT.X == X` (the property call against its node) and `CONTEXT.X…  == k` (against the true value): VALID;
//  * the SEPARATING ROW (`!`), the value a mis-wired evaluator would read: INVALID on the JVM —
//      INPUTS.size == 3   is the OUTPUTS count  (id 4 wired to OUTPUTS),
//      OUTPUTS.size == 2  is the INPUTS count   (id 5 wired to INPUTS),
//      HEIGHT == H-1      is headers(0).height  (id 6 wired to the last header, the parent),
//      SELF.value == 1e9  is INPUTS(0).value    (id 7 wired to the first input).
//    The box parses and a node carries it, so anyone can create it; such an evaluator makes `p` true and lets a
//    spender take it.
//  * the node control (`c`), the same check through the dedicated node: VALID, the cost baseline.
// Each at tree v3 (sized) and tree v0 (unsized: its root is a SigmaProp, so the box parses). An evaluator with NO
// evaluator for the id refuses every VALID row (sigma-rust's `NotFound`) and so is red there.
//
// Blessed through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under the storage-rent vectors'
// synthetic context, as every tx probe is. Measured first: santa.spike.ContextPropertyCallProbe §3.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.serialization.ErgoTreeSerializer
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxContextPropertyCalls {
  val Path = "transaction/v6/authored/context-property-calls.json"
  val Op = "context-property-calls"
  val Source = "santa:authored-context-property-calls"
  val Activated: Byte = 3
  val H: Int = AuthoredTxStorageRent.H
  val ProbeValue = 1234567890L
  val OtherValue = 1000000000L
  val TreeVersions: Seq[Int] = Seq(3, 0)

  private val E = AuthoredEvalContextPropertyCalls
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def coll(v: Value[SType]) = v.asInstanceOf[Value[SCollection[SType]]]
  private def boxV(v: Value[SType]) = v.asInstanceOf[Value[SBox.type]]
  private def eqv(a: Value[SType], c: Value[SType]): Value[SType] = any(EQ(a, c))
  private def k(i: Int): Value[SType] = any(IntConstant(i))
  private def kl(l: Long): Value[SType] = any(LongConstant(l))

  /** (id, script, predicate at tree version t, valid on the JVM) */
  case class Row(id: String, script: String, p: Int => Value[SType], valid: Boolean, why: String)

  private def pc(id: Int, t: Int) = E.pc(id, Activated, t)
  private def sizeOf(v: Value[SType]) = any(SizeOf(coll(v)))
  private def value(v: Value[SType]) = any(ExtractAmount(boxV(v)))

  private val equal = "THE CALL IS THE NODE: the property call returns the node's value, so the script reduces to " +
    "true and the box SPENDS. An evaluator with no evaluator for the id refuses it."
  private val exact = "The call against the TRUE value in this context, so a wired-but-wrong field is caught."
  private val control = "THE CONTROL: the same check through the dedicated node; the cost baseline, and what " +
    "shows the carrier and the wrapper are sound."
  private def separating(reads: String) = "**THE SEPARATING ROW.** INVALID on the JVM: the property call returns " +
    s"the node's value, so `p` is false. $reads, so an evaluator wired that way makes `p` true and lets a spender " +
    "TAKE a box anyone can create: an over-accept on reachable state."

  val Rows: Seq[Row] = Seq(
    Row("I=", "CONTEXT.INPUTS == INPUTS", t => eqv(pc(4, t), any(Inputs)), valid = true, equal),
    Row("I2", "CONTEXT.INPUTS.size == 2", t => eqv(sizeOf(pc(4, t)), k(2)), valid = true, exact),
    Row("I3!", "CONTEXT.INPUTS.size == 3", t => eqv(sizeOf(pc(4, t)), k(3)), valid = false,
      separating("3 is the OUTPUTS count")),
    Row("Ic", "INPUTS.size == 2", _ => eqv(sizeOf(any(Inputs)), k(2)), valid = true, control),
    Row("O=", "CONTEXT.OUTPUTS == OUTPUTS", t => eqv(pc(5, t), any(Outputs)), valid = true, equal),
    Row("O3", "CONTEXT.OUTPUTS.size == 3", t => eqv(sizeOf(pc(5, t)), k(3)), valid = true, exact),
    Row("O2!", "CONTEXT.OUTPUTS.size == 2", t => eqv(sizeOf(pc(5, t)), k(2)), valid = false,
      separating("2 is the INPUTS count")),
    Row("Oc", "OUTPUTS.size == 3", _ => eqv(sizeOf(any(Outputs)), k(3)), valid = true, control),
    Row("H=", "CONTEXT.HEIGHT == HEIGHT", t => eqv(pc(6, t), any(Height)), valid = true, equal),
    Row("Hk", s"CONTEXT.HEIGHT == $H", t => eqv(pc(6, t), k(H)), valid = true, exact),
    Row("Hk!", s"CONTEXT.HEIGHT == ${H - 1}", t => eqv(pc(6, t), k(H - 1)), valid = false,
      separating(s"${H - 1} is `CONTEXT.headers(0).height`, the parent's")),
    Row("Hc", s"HEIGHT == $H", _ => eqv(any(Height), k(H)), valid = true, control),
    Row("S=", "CONTEXT.SELF == SELF", t => eqv(pc(7, t), any(Self)), valid = true, equal),
    Row("Sv", s"CONTEXT.SELF.value == ${ProbeValue}L", t => eqv(value(pc(7, t)), kl(ProbeValue)), valid = true, exact),
    Row("Sv!", s"CONTEXT.SELF.value == ${OtherValue}L", t => eqv(value(pc(7, t)), kl(OtherValue)), valid = false,
      separating(s"$OtherValue is `INPUTS(0).value`, the other input's")),
    Row("Sc", s"SELF.value == ${ProbeValue}L", _ => eqv(value(any(Self)), kl(ProbeValue)), valid = true, control))

  /** `sigmaProp(p)` at tree version t, through the eval half's own tree writer. */
  def treeOf(r: Row, t: Int): String = E.tree(any(BoolToSigmaProp(r.p(t).asInstanceOf[Value[SBoolean.type]])), Activated, t)

  def extract(): Json = {
    val entries = TreeVersions.flatMap(t => Rows.map(r => (t, r))).zipWithIndex.map { case ((t, r), i) =>
      val treeHex = treeOf(r, t)
      val slug = r.id.toLowerCase.replace("=", "-eq").replace("!", "-sep")
      val (txHex, boxes) = VersionContext.withVersions(Activated, t.toByte) {
        val tree = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex))
        val other = box(s"santa:cpc:other:$slug:t$t", OtherValue, 1)
        val probe = box(s"santa:cpc:probe:$slug:t$t", ProbeValue, 1, tree)
        val outs = Seq(candidate(OtherValue, 1), candidate(OtherValue, 1), candidate(ProbeValue - OtherValue, 1))
        (hex(txBytes(tx(Seq(input(other, ext()), input(probe, ext())), outs))), Seq(hex(other.bytes), hex(probe.bytes)))
      }
      val v = TxEngine.validateBytes(txHex, boxes, Nil,
        AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)
      require(v.valid == r.valid, s"row ${r.id} t$t: want valid=${r.valid}, got ${v.valid} ${v.reason.getOrElse("")}")
      Json.obj(
        "name"                 -> Json.fromString(s"context-property-calls-spend-$slug-t$t#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(s"[${r.id}] `sigmaProp(${r.script})`, tree v$t, guards input 1 " +
          s"(SELF, value $ProbeValue) of two, three outputs, at height $H. ${r.why}"),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(boxes.map(Json.fromString): _*),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
        "preHeader"            -> AuthoredTxStorageRent.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(Activated.toInt), "ergoTree" -> Json.fromInt(t)),
        "expected"             -> Json.obj(
          "valid"  -> Json.fromBoolean(v.valid),
          "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
          "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
    }
    Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = {
    val path = vectorsRoot.resolve(Path)
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, extract().spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
