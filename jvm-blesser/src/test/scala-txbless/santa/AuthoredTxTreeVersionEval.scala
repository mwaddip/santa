package santa

// AuthoredTxTreeVersionEval — probe A (sigma-rust, 2026-10-02): a tree deserialized DURING reduction is version-checked
// against the context's activated version. `deserializeErgoTree` reads `VersionContext.current.activatedVersion`
// (ErgoTreeSerializer.scala:150) and throws `SerializerException("Tree version (N) is above activated script version
// (M)")` when the tree is higher; `verify` runs the whole reduction under `VersionContext.withVersions(activated,
// treeVersion)` (Interpreter.scala:366), so any such decode compares against the context's activated version (3 here).
// The failure is not a `ValidationException` (so `trySoftForkable` does not turn it into TrueSigmaProp) and not a
// `ClassCastException` (so Kiama's strategy does not swallow it): the spend is invalid. The parse check (the wire
// `tree_version_above_activated` vectors and the transaction `tree-version-above-activated` ones) covers the tree at
// ingest and at the start of a spend; these cover a tree deserialized while the script runs.
//
// All spends are at block version 4 (activated 3). B4 is a box whose tree is `0c 02 08 d3` (v4, size-flagged,
// SigmaProp(true)); B3 the same with `0b 02 08 d3` (v3). The B4 form is invalid, the B3 twin valid. The fork
// (14aad9c0) over-accepts the B4 form: it compares no version during reduction. Blessed through
// TxEngine.validateBytes (ergo-core 6.0.6) under the storage-rent vectors' synthetic context (H = 1051200).
//
// Candidate 1: the Box is a *constant* inside the script a Deserialize node decodes. The decode path is
// deserializeMeasured (Interpreter.scala:99-107) -> ValueSerializer.deserialize; the Box constant's data is read by
// DataSerializer for SBox (DataSerializer.scala:33-38) -> ErgoBox.sigmaSerializer.parse -> deserializeErgoTree
// (ErgoBoxCandidate.scala:194).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{BoolToSigmaProp, BoxConstant, ByteArrayConstant, ConcreteCollection, ErgoTree, ExtractAmount,
  EvaluatedValue, GetVar, Global, GT, IntConstant, LongConstant, MethodCall, OptionGet, SBox, SByte, SCollection,
  SGlobalMethods, SInt, SizeOf, SOption, SigmaPropConstant, SType, STypeVar, SubstConstants}
import sigma.data.{AvlTreeData, CBox, TrivialProp}
import sigma.interpreter.ContextExtension
import sigma.serialization.{ErgoTreeSerializer, ValueSerializer}
import sigmastate.helpers.{ErgoLikeContextTesting, ErgoLikeTestInterpreter}
import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.{NonMandatoryRegisterId, R4}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxTreeVersionEval {
  val SpendPath = "transaction/v6/authored/tree-version-above-activated-eval.json"
  private val V3: Byte = VersionContext.V6SoftForkVersion
  private val Activated = 3
  private val ErgoTreeV = 0
  private val V = 1000000000L // the spent box's value, and the inner box's: ExtractAmount > 0 holds
  private val H = AuthoredTxStorageRent.H
  private val VerErr = "Tree version (4) is above activated script version (3)"

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private type Val = EvaluatedValue[_ <: SType]

  /** A box whose tree is `treeHexOfBox`, built under (ver, ver) so a v4 tree is allowed (B4: ver 4; B3: ver 3). */
  private def innerBox(treeHexOfBox: String, ver: Byte): ErgoBox = VersionContext.withVersions(ver, ver) {
    box("santa:tx-tree-version-eval:inner", V, 1, ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHexOfBox)))
  }

  /** The bytes of `sigmaProp(ExtractAmount(BoxConstant(B)) > 0L)` (SigmaProp) or just `ExtractAmount(B) > 0L` (Boolean).
    * The Box constant embeds B's bytes, which carry B's tree, so decoding these bytes re-parses that tree. */
  private def decoded(treeHexOfBox: String, ver: Byte, asSigmaProp: Boolean): Array[Byte] = VersionContext.withVersions(ver, ver) {
    val gt = GT(ExtractAmount(BoxConstant(CBox(innerBox(treeHexOfBox, ver)))), LongConstant(0L))
    ValueSerializer.serialize(if (asSigmaProp) BoolToSigmaProp(gt) else gt)
  }

  /** The spent box: `treeHex` parsed at (3, 3), with R4 = `r4` if given. Its bytes carry the tree as given. */
  private def spent(i: Int, treeHex: String, r4: Option[Val]): ErgoBox = VersionContext.withVersions(V3, V3) {
    val t = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex))
    val regs = r4.map(v => Map[NonMandatoryRegisterId, Val](R4 -> v)).getOrElse(Map.empty[NonMandatoryRegisterId, Val])
    val bx = box(s"santa:tx-tree-version-eval:$i", V, 1, tree = t, regs = regs)
    require(hex(bx.bytes).startsWith(hex(vlqU32(V)) + treeHex), s"#$i: the box must carry the tree as given: ${hex(bx.bytes)}")
    bx
  }

  private def validate(txHex: String, inputs: Seq[ErgoBox]): TxEngine.Verdict =
    TxEngine.validateBytes(txHex, inputs.map(x => hex(x.bytes)), Nil, AuthoredTxStorageRent.headersHex,
      AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)

  /** The cause chain of a failed reduction of the same spend, run directly on sigma-state's interpreter. TxEngine's
    * reason names only the outermost exception (the MalformedModifierError); the version error is deeper. */
  private def reductionCauses(bx: ErgoBox, e: ContextExtension): List[String] = VersionContext.withVersions(V3, V3) {
    val t = tx(Seq(input(bx, e)), Seq(candidate(bx.value, 1)))
    val ctx = ErgoLikeContextTesting(H, AvlTreeData.dummy, Array.fill(33)(2.toByte), IndexedSeq(bx), t, bx, V3, e)
    Try(new ErgoLikeTestInterpreter().fullReduction(bx.ergoTree, ctx)) match {
      case Success(r) => sys.error(s"the direct reduction must fail, got $r")
      case Failure(x) =>
        Iterator.iterate(x)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList
    }
  }

  /** `sigmaProp(If(false, c, true))`: c is the dead branch, never evaluated, but still visited at substitution. */
  private def dead(c: String): String = "00" + "d1" + "95" + "0100" + c + "0101"

  private def entry(i: Int, slug: String, description: String, treeHex: String, want: Boolean,
                    r4: Option[Val] = None, var1: Option[Val] = None, because: String = "",
                    cause: Option[String] = None): Json = {
    val bx = spent(i, treeHex, r4)
    val e = var1.map(v => ext(1.toByte -> v)).getOrElse(ext())
    val txHex = VersionContext.withVersions(V3, V3) { hex(txBytes(tx(Seq(input(bx, e)), Seq(candidate(V, 1))))) }
    val v = validate(txHex, Seq(bx))
    require(v.valid == want, s"$slug#$i: want valid=$want, got valid=${v.valid} ${v.reason.getOrElse("")}")
    require(v.reason.getOrElse("").contains(because), s"$slug#$i: the reason must mention '$because': ${v.reason}")
    cause.foreach { c =>
      val chain = reductionCauses(bx, e)
      require(chain.exists(_.contains(c)), s"$slug#$i: the cause must mention '$c': ${chain.mkString(" <- ")}")
    }
    Json.obj(
      "name"                 -> Json.fromString(s"$slug#$i"),
      "source"               -> Json.fromString("santa:authored-tx-tree-version-eval:spend"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(txHex),
      "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
      "data_input_boxes_hex" -> Json.arr(),
      "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
      "preHeader"            -> AuthoredTxStorageRent.preHeader,
      "parameters"           -> AuthoredTxStorageRent.params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(Activated), "ergoTree" -> Json.fromInt(ErgoTreeV)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  private def bc(treeHexOfBox: String, ver: Byte, asSigmaProp: Boolean): Option[Val] =
    Some(ByteArrayConstant(decoded(treeHexOfBox, ver, asSigmaProp)))

  // ── candidate 3: SubstConstants reads every constant, so a Box constant's tree is re-parsed ──────────
  /** sigmaProp(SizeOf(SubstConstants(getVar[Coll[Byte]](1).get, Coll[Int](), Coll[Int]())) > 0): the spent tree. */
  private val substSpentTreeHex: String = VersionContext.withVersions(V3, V3) {
    val emptyInts   = ConcreteCollection(IndexedSeq.empty[sigma.ast.Value[SInt.type]], SInt)
    val scriptBytes = OptionGet(GetVar(1.toByte, SOption(SCollection(SByte))))
    val expr        = BoolToSigmaProp(GT(SizeOf(SubstConstants(scriptBytes, emptyInts, emptyInts)), IntConstant(0)))
    hex(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(ErgoTree.fromProposition(ErgoTree.ZeroHeader, expr)))
  }
  /** A segregated template whose constant 0 is a Box with tree `treeHexOfBox` (v4 or v3). */
  private def boxTemplate(treeHexOfBox: String, ver: Byte): Array[Byte] = VersionContext.withVersions(ver, ver) {
    val body = BoolToSigmaProp(GT(ExtractAmount(BoxConstant(CBox(innerBox(treeHexOfBox, ver)))), LongConstant(0L)))
    ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(ErgoTree.withSegregation(ErgoTree.ZeroHeader, body))
  }
  /** A segregated template with a v4 HEADER and no Box constant (the 3b control). */
  private val v4HeaderTemplate: Array[Byte] = VersionContext.withVersions(4.toByte, 4.toByte) {
    ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(
      ErgoTree.withSegregation(ErgoTree.setVersionBits(ErgoTree.ZeroHeader, 4.toByte), SigmaPropConstant(TrivialProp.TrueProp)))
  }

  // ── candidate 2: Global.deserializeTo[Box] reads the bytes as a box, re-parsing its tree ─────────────
  /** sigmaProp(ExtractAmount(Global.deserializeTo[Box](getVar[Coll[Byte]](1).get)) > 0L), a v3 tree (deserializeTo is
    * a v6 method, so the enclosing tree must be v3). */
  private val deserializeToSpentTreeHex: String = VersionContext.withVersions(V3, V3) {
    val tv  = STypeVar("T")
    val m   = SGlobalMethods.deserializeToMethod.withConcreteTypes(Map(tv -> SBox))
    val arg = OptionGet(GetVar(1.toByte, SOption(SCollection(SByte))))
    val box = MethodCall(Global, m, IndexedSeq(arg), Map(tv -> SBox)).asInstanceOf[sigma.ast.Value[SBox.type]]
    val expr = BoolToSigmaProp(GT(ExtractAmount(box), LongConstant(0L)))
    hex(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(
      ErgoTree.fromProposition(ErgoTree.setVersionBits(ErgoTree.ZeroHeader, 3.toByte), expr)))
  }
  /** A box with tree `treeHexOfBox` as ErgoBox.sigmaSerializer writes it (what deserializeTo[Box] reads). */
  private def innerBoxSer(treeHexOfBox: String, ver: Byte): Array[Byte] = VersionContext.withVersions(ver, ver) {
    ErgoBox.sigmaSerializer.toBytes(innerBox(treeHexOfBox, ver))
  }

  private def spendEntries: Seq[Json] = {
    val note = "The input spends a box of value 1000000000 into one output of the same value, with no proof. The " +
      "decoded script is sigmaProp(ExtractAmount(B) > 0L) (or the Boolean ExtractAmount(B) > 0L), where B is a Box " +
      "*constant* whose tree is v4 (above activated 3) or v3 (the twin). Decoding it re-parses B's tree."
    val why = "Decoding runs ValueSerializer.deserialize, which reads the Box constant's bytes with DataSerializer for " +
      "SBox -> ErgoBox.parse -> deserializeErgoTree, and that compares the tree's version with the context's activated " +
      "version (3). The v4 tree throws a SerializerException ('" + VerErr + "'), which is not a soft fork and not " +
      "swallowed: the spend is invalid. The fork compares no version during reduction and over-accepts."
    val twin = "The v3 twin decodes without a version error; ExtractAmount(B) is 1000000000 > 0, so the script reduces " +
      "to sigmaProp(true): valid."
    Seq(
      entry(0, "context-deserialize-box-v4-reject",
        s"The spent tree is DeserializeContext(1, SigmaProp) as root (00 d4 08 01), and variable 1 holds the bytes of " +
        s"sigmaProp(ExtractAmount(B4) > 0L). $why $note",
        "00" + "d40801", want = false, var1 = bc("0c0208d3", 4, asSigmaProp = true),
        because = "Scripts of all transaction inputs", cause = Some(VerErr)),
      entry(1, "context-deserialize-box-v3-accept",
        s"The twin of #0: variable 1 holds sigmaProp(ExtractAmount(B3) > 0L). $twin $note",
        "00" + "d40801", want = true, var1 = bc("0b0208d3", 3, asSigmaProp = true)),
      entry(2, "register-deserialize-box-v4-reject",
        s"The spent tree is DeserializeRegister(R4, SigmaProp) as root (00 d5 04 08 00), and R4 holds the bytes of " +
        s"sigmaProp(ExtractAmount(B4) > 0L). The decode path is the same as #0. $why $note",
        "00" + "d5040800", want = false, r4 = bc("0c0208d3", 4, asSigmaProp = true),
        because = "Scripts of all transaction inputs", cause = Some(VerErr)),
      entry(3, "register-deserialize-box-v3-accept",
        s"The twin of #2: R4 holds sigmaProp(ExtractAmount(B3) > 0L). $twin $note",
        "00" + "d5040800", want = true, r4 = bc("0b0208d3", 3, asSigmaProp = true)),
      entry(4, "dead-context-deserialize-box-v4-reject",
        s"The spent tree is sigmaProp(If(false, DeserializeContext(1, Boolean), true)) (dead branch), and variable 1 " +
        s"holds the Boolean ExtractAmount(B4) > 0L. The decode runs at substitution even in the dead branch (as " +
        s"deserialize-substitution-spend #19 shows), so the version error still throws: invalid. $why $note",
        dead("d40101"), want = false, var1 = bc("0c0208d3", 4, asSigmaProp = false),
        because = "Scripts of all transaction inputs", cause = Some(VerErr)),
      entry(5, "dead-context-deserialize-box-v3-accept",
        s"The twin of #4: variable 1 holds the Boolean ExtractAmount(B3) > 0L. It decodes, the branch is dead, and " +
        s"If(false, _, true) is true: valid. $note",
        dead("d40101"), want = true, var1 = bc("0b0208d3", 3, asSigmaProp = false)),
      entry(6, "substconstants-box-v4-reject",
        s"The spent tree is sigmaProp(SizeOf(SubstConstants(getVar[Coll[Byte]](1).get, Coll[Int](), Coll[Int]())) > 0), " +
        "and variable 1 is a segregated template whose constant 0 is a Box with a v4 tree. substituteConstants reads " +
        "every constant with deserializeConstants (ErgoTreeSerializer.scala:245), whatever the positions asked for, so " +
        "the Box constant's v4 tree is re-parsed and the version error throws: invalid. The fork over-accepts. The " +
        s"decoded script is not used; empty positions leave the template unchanged.",
        substSpentTreeHex, want = false, var1 = Some(ByteArrayConstant(boxTemplate("0c0208d3", 4))),
        because = "Scripts of all transaction inputs", cause = Some(VerErr)),
      entry(7, "substconstants-box-v3-accept",
        s"The twin of #6: constant 0 is a Box with a v3 tree. Every constant is read without a version error, and " +
        "SubstConstants with empty positions returns the template's bytes unchanged, whose size is > 0: valid.",
        substSpentTreeHex, want = true, var1 = Some(ByteArrayConstant(boxTemplate("0b0208d3", 3)))),
      entry(8, "substconstants-v4-header-template-accept",
        s"The control: variable 1 is a segregated template with a v4 HEADER and no Box constant. substituteConstants " +
        "reads the header with deserializeHeaderAndSize, not deserializeErgoTree, so the template's own v4 version is " +
        "never compared: valid. It shows that only a nested Box constant's version is checked here, not the template's " +
        "own — the asymmetry a parse-time check would not have.",
        substSpentTreeHex, want = true, var1 = Some(ByteArrayConstant(v4HeaderTemplate))),
      entry(9, "deserializeto-box-v4-reject",
        s"The spent tree is sigmaProp(ExtractAmount(Global.deserializeTo[Box](getVar[Coll[Byte]](1).get)) > 0L), a v3 " +
        "tree (deserializeTo is a v6 method), and variable 1 is a Box with a v4 tree, as ErgoBox.sigmaSerializer writes " +
        "it. deserializeTo reads the bytes with DataSerializer for SBox -> ErgoBox.sigmaSerializer.parse -> " +
        "deserializeErgoTree (DataSerializer.scala:33-38), which compares the v4 tree with the activated version (3) " +
        "and throws: invalid. The fork over-accepts, comparing no version while it runs.",
        deserializeToSpentTreeHex, want = false, var1 = Some(ByteArrayConstant(innerBoxSer("0c0208d3", 4))),
        because = "Scripts of all transaction inputs", cause = Some(VerErr)),
      entry(10, "deserializeto-box-v3-accept",
        s"The twin of #9: variable 1 is a Box with a v3 tree. It deserializes without a version error, its value is " +
        "1000000000 > 0, and the script reduces to sigmaProp(true): valid.",
        deserializeToSpentTreeHex, want = true, var1 = Some(ByteArrayConstant(innerBoxSer("0b0208d3", 3)))))
  }

  private def envelope(op: String, es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString(op),
    "blessed_by" -> Json.fromString(AuthoredTxStorageRent.BlessedBy),
    "entries"    -> Json.arr(es: _*))

  def blessAll(): Seq[(String, Json)] =
    Seq(SpendPath -> envelope("tx:authored:tree-version-above-activated-eval", spendEntries))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
