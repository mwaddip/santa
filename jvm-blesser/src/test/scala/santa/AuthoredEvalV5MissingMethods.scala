package santa

// Authored eval vectors — the 15 method ids sigma-rust's tables lack, plus a dedicated-node
// control for each operation so the cost the MethodCall encoding adds is visible.
//
// The split, the two `NoSuchMethodException`s and the checked count table are all in
// `V5MissingMethodTrees`. The one-line version: four of the six method names invoke and two do
// not, so ELEVEN of the fifteen ids are a dead parser-visible surface — reachable, listed, and
// not invocable — while the dedicated node for each of those operations works fine.

import io.circe.Json
import sigma.ast._

object AuthoredEvalV5MissingMethods {
  val Op        = "MethodCall.v5_methods_missing_in_rust"
  val OpControl = "MethodCall.v5_methods_dedicated_node_control"
  val Source    = "santa:authored-v5-methods-missing"
  private val V3: Byte = V5MissingMethodTrees.Activated

  private val dummyInput: Json =
    Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))

  /** The dedicated node for each operation, as the JVM writes it. Each is the control for the
    * MethodCall row of the same operation: same job, different encoding, and for R0..R9 and
    * getOrElse the control WORKS where the MethodCall form cannot be invoked at all. */
  private def controls: Seq[(String, String, Value[SType])] = Seq(
    ("exponentiate", "`Exponentiate(g, BigInt 5)` — the dedicated node for `exp`. Cost 915, " +
      "against the MethodCall encoding's 928.",
      Exponentiate(GroupGenerator, BigIntConstant(5)).asInstanceOf[Value[SType]]),
    ("multiply_group", "`MultiplyGroup(g, g)` — the dedicated node for `multiply`. Cost 60, " +
      "against the MethodCall encoding's 82.",
      MultiplyGroup(GroupGenerator, GroupGenerator).asInstanceOf[Value[SType]]),
    ("option_is_defined", "`OptionIsDefined(getVar[Int](1))` — the dedicated node for " +
      "`isDefined`, cost 20. Not a like-for-like cost against the MethodCall rows (their receiver " +
      "is `Global.some`/`none`, this one's is a context variable), so read it as the floor rather " +
      "than as a delta.",
      OptionIsDefined(GetVar(1.toByte, SOption(SInt))).asInstanceOf[Value[SType]]),
    ("option_get", "`OptionGet(getVar[Int](1))` — the dedicated node for `get`, cost 25.",
      OptionGet(GetVar(1.toByte, SOption(SInt))).asInstanceOf[Value[SType]]),
    ("option_get_or_else", "`OptionGetOrElse(getVar[Int](1), 9)` — the dedicated node for " +
      "`getOrElse`, cost 30. **THE IMPORTANT CONTROL:** the MethodCall form of this same " +
      "operation cannot be invoked at all (`NoSuchMethodException: scala.Option.getOrElse(int)`), " +
      "so the pair shows the failure belongs to the ENCODING and not to the operation.",
      OptionGetOrElse(GetVar(1.toByte, SOption(SInt)), IntConstant(9)).asInstanceOf[Value[SType]]),
    ("extract_register_as", "`ExtractRegisterAs(SELF, R4, Option[Int])` — the dedicated node for " +
      "`SELF.R4`, cost 60, answering `None` (contract §2: SELF's registers are ABSENT). **THE " +
      "OTHER IMPORTANT CONTROL:** `SELF.R4` as a MethodCall gives " +
      "`NoSuchMethodException: sigma.Box.R4()`, so again the operation is fine and the encoding " +
      "is not.",
      ExtractRegisterAs(Self, org.ergoplatform.ErgoBox.R4, SOption(SInt)).asInstanceOf[Value[SType]]))

  private def bodyOf(v: Value[SType]): String = sigma.VersionContext.withVersions(V3, V3) {
    val w = sigma.serialization.SigmaSerializer.startWriter()
    sigma.serialization.ValueSerializer.serialize(v, w)
    scorex.util.encode.Base16.encode(w.toBytes)
  }

  def extract(): Map[String, Json] = {
    val main = V5MissingMethodTrees.rows.map { r =>
      val tree = V5MissingMethodTrees.evalTree(r.expr)
      val name = s"${r.slug}-${if (r.accept) "accept" else "errored"}#${r.n - 1}"
      val script = s"${r.what}  // row ${r.n} of the probe"
      if (r.accept) SpecExtract.authoredEntry(Op, script, tree, name, dummyInput, V3)
      else SpecExtract.authoredRejectEntry(Op, script, tree, name, dummyInput, V3)
    }
    val ctrl = controls.zipWithIndex.map { case ((slug, what, v), i) =>
      SpecExtract.authoredEntry(OpControl, what,
        V5MissingMethodTrees.evalTree(bodyOf(v)), s"$slug-dedicated-node-accept#$i", dummyInput, V3)
    }
    def env(op: String, es: Seq[Json]): Json =
      SpecExtract.authoredEnvelope(op, es, Source)
        .mapObject(_.add("blessed_by", Json.fromString("jvm:sigma-state-6.0.7")))
    Map(Op -> env(Op, main), OpControl -> env(OpControl, ctrl))
  }

  def writeVectors(outDir: java.nio.file.Path): Unit =
    SpecExtract.writeStaging("AuthoredEvalV5MissingMethods", extract(), outDir)
}
