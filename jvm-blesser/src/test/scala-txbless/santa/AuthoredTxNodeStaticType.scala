package santa

// AuthoredTxNodeStaticType — the SPEND form of the node-static-type families (sigma-rust's
// santa-probe-node-static-type, 2026-11-16; THE RULE is in AuthoredEvalNodeStaticType): `Exponentiate` and
// `MultiplyGroup` are typed GroupElement whatever their operands. Measured first in santa.spike.NodeStaticTypeProbe.
//
// The request's direction "an output whose tree sigmastate rejects" is a refusal at the READ: ergo-core's
// ErgoTransactionSerializer refuses those transactions before any validation (measured), so it lives in the wire tier
// (`Transaction.tree_group_node_static_type`), whose reject arm grades exactly that. Here: spends, where a box guarded by
// a tree the JVM reads is spent (or not), under the storage-rent vectors' synthetic context through TxEngine.validateBytes
// (ergo-core 6.0.7 validateStateful), as every tx blesser does.
//
//  - REQ: two of the request's trees, in its four forms: `sigmaProp(true)` (the carrier) and `sigmaProp(true ||
//    Exponentiate(1, 2) == g)` (read, and the branch is not taken: it spends). The request's `prop.exp.anyge==g` is no
//    spend: its exponent is BigInt 2 (`06 01 02`), so it reads `2g == g`, false on the JVM; `prop.mul.anyge==g` reads
//    `g + g == g`, false. Neither separates, so:
//  - EQ: `sigmaProp(X == Exponentiate(g, 2))`, X the node over x = `(g, true, 1L)(0)` (typed Any) or over g (the
//    control): 2g on both sides, so it spends; a port that types X by its left operand refuses `Any == GroupElement`.
//  - DCTX / DREG: the node as a script read at evaluation (`executeFromVar[T](1)`, DeserializeContext; and
//    `SELF.R4` deserialized as T, DeserializeRegister). The interpreter substitutes every such node before it evaluates,
//    taken or not, and checks the script's type against T (`Interpreter.substDeserialize`; measured: a
//    ValidationException for DeserializeContext, a RuntimeException for DeserializeRegister). With T = GroupElement and the script `Exponentiate(x, 2)` over the Any-typed x,
//    the types agree and the box spends; with T = Int behind `true ||` and the script `Exponentiate(1, 2)`, they do not,
//    and the spend is INVALID although the branch is never taken: a port that types the script by its left operand
//    reads Int and accepts. Controls: the script `1` (matches Int), and a group element script against Int (refused by
//    any reading: the check exists).
// Forms: REQ in the request's four (v0 unsized; v0, v1, v3 sized), the rest in two (v0 unsized, v3 sized).

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{ErgoTreeSerializer, OpCodes, SigmaSerializer, ValueSerializer}
import org.ergoplatform.ErgoBox
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxNodeStaticType {
  val Path = "transaction/v6/authored/group-node-static-type.json"
  val Op = "group-node-static-type"
  val Source = "santa:authored-node-static-type"
  private val Activated = 3
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H
  private val S = AuthoredEvalNodeStaticType

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def ctx[A](f: => A): A = VersionContext.withVersions(Activated.toByte, Activated.toByte)(f)
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  private def body(v: SValueOf): String = ctx { val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(v, w); hx(w.toBytes) }
  private type SValueOf = sigma.ast.syntax.SValue
  private def read(hex: String): SValueOf = ctx(ValueSerializer.deserialize(SigmaSerializer.startReader(b(hex))))

  /** `Exponentiate(g, 2)`, the right side every EQ / DCTX / DREG tree compares with. */
  val Exp2g: String = S.nodeBytes("exp", "ctl")
  private def ge(hex: String): Value[SGroupElement.type] = read(hex).asInstanceOf[Value[SGroupElement.type]]
  private def plusOneIsOne(v: Value[SInt.type]): sigma.ast.syntax.BoolValue =
    BinOr(TrueLeaf, EQ(ArithOp(v, IntConstant(1), OpCodes.PlusCode), IntConstant(1)))

  /** The trees' bodies, by family. */
  def eqBody(x: String): String = "d193" + x + Exp2g
  val DctxGE: String = body(BoolToSigmaProp(EQ(DeserializeContext(1.toByte, SGroupElement), ge(Exp2g))))
  val DctxInt: String = body(BoolToSigmaProp(plusOneIsOne(DeserializeContext(1.toByte, SInt))))
  val DregGE: String = body(BoolToSigmaProp(EQ(DeserializeRegister(ErgoBox.R4, SGroupElement), ge(Exp2g))))
  val DregInt: String = body(BoolToSigmaProp(plusOneIsOne(DeserializeRegister(ErgoBox.R4, SInt))))

  /** The scripts a DCTX / DREG spend reads: the node over x, or the Int 1. */
  val Scripts: Map[String, String] = Map(
    "exp.anyge" -> S.nodeBytes("exp", "anyge"), "exp.ctl" -> S.nodeBytes("exp", "ctl"),
    "mul.anyge" -> S.nodeBytes("mul", "anyge"), "mul.ctl" -> S.nodeBytes("mul", "ctl"),
    "exp.int" -> S.nodeBytes("exp", "int"), "mul.int" -> S.nodeBytes("mul", "int"), "int-1" -> "0402")
  private val ScriptText: Map[String, String] = Map(
    "exp.anyge" -> "`Exponentiate((g, true, 1L)(0), 2)` (x typed Any, the generator)",
    "exp.ctl" -> "`Exponentiate(g, 2)`", "mul.anyge" -> "`MultiplyGroup((g, true, 1L)(0), g)` (x typed Any, the generator)",
    "mul.ctl" -> "`MultiplyGroup(g, g)`", "exp.int" -> "`Exponentiate(1, 2)`", "mul.int" -> "`MultiplyGroup(1, g)`",
    "int-1" -> "the Int constant 1")

  private val ExprText: Map[String, String] = Map(
    "exp.anyge" -> "Exponentiate((g, true, 1L)(0), 2)", "exp.ctl" -> "Exponentiate(g, 2)",
    "mul.anyge" -> "MultiplyGroup((g, true, 1L)(0), g)", "mul.ctl" -> "MultiplyGroup(g, g)")

  /** One spend: a family, an id, the tree's form, its bytes, where its script travels, and whether it must spend. */
  case class Spend(family: String, id: String, form: String, treeHex: String, extVar: Option[String],
                   r4: Option[String], valid: Boolean, what: String, label: String) {
    def name: String = s"$family|$id|$form"
    def slug: String = name.toLowerCase.replace('|', '-').replaceAll("[^a-z0-9.+_=-]", "_")
  }

  private def forms(bodyHex: String, all: Boolean): Seq[(String, String)] = {
    val sized = (h: String) => h + vlq(bodyHex.length / 2) + bodyHex
    if (all) Seq("v0" -> ("00" + bodyHex), "v0-sized" -> sized("08"), "v1-sized" -> sized("09"), "v3-sized" -> sized("0b"))
    else Seq("v0" -> ("00" + bodyHex), "v3-sized" -> sized("0b"))
  }

  private val Separating = "THE SEPARATING ROW."
  private def anyEq = s"$Separating The JVM reads the node as GroupElement on both sides of `==`, and the box SPENDS. " +
    "A port that types the node by its left operand reads `Any == GroupElement`, which the builder refuses, so it " +
    "cannot read the box's tree: a box the JVM lets its owner spend, stuck there."

  def spends(): Seq[Spend] = {
    val req = AuthoredWireNodeStaticType.trees().filter(t => t.root == "true-prop" || t.root == "prop.untaken.exp.int==g")
      .map { t =>
        if (t.root == "true-prop")
          Spend("REQ", t.root, t.form, t.bytes, None, None, valid = true, "`sigmaProp(true)`",
            "THE CONTROL: the carrier and the context spend.")
        else
          Spend("REQ", t.root, t.form, t.bytes, None, None, valid = true, "`sigmaProp(true || Exponentiate(1, 2) == g)`",
            s"$Separating The JVM reads the node as GroupElement, so `==` with g is read, and the branch is never " +
              "taken: the box SPENDS. A port that types the node by its left operand reads `Int == GroupElement`, " +
              "which the builder refuses, so it cannot read the box's tree.")
      }
    val eq = for {
      x <- Seq("exp.anyge", "exp.ctl", "mul.anyge", "mul.ctl")
      (form, tree) <- forms(eqBody(Scripts(x)), all = false)
    } yield Spend("EQ", x, form, tree, None, None, valid = true, s"`sigmaProp(${ExprText(x)} == Exponentiate(g, 2))`",
      if (x.endsWith("ctl")) "THE CONTROL: the same tree over g; 2g on both sides." else anyEq)
    def deser(family: String, gt: String, it: String, read: String, slot: String) = {
      val geRows = for {
        x <- Seq("exp.anyge", "exp.ctl", "mul.anyge", "mul.ctl")
        (form, tree) <- forms(gt, all = false)
      } yield Spend(family, s"ge.$x", form, tree, if (family == "DCTX") Some(Scripts(x)) else None,
        if (family == "DREG") Some(Scripts(x)) else None, valid = true,
        s"`sigmaProp($read[GroupElement] == Exponentiate(g, 2))`, $slot = the bytes of ${ScriptText(x)}",
        if (x.endsWith("ctl")) "THE CONTROL: the script is over g; every reading types it GroupElement."
        else s"$Separating The interpreter substitutes the script before it evaluates and checks its type against " +
          "GroupElement: the node is GroupElement, they agree, and the box SPENDS. A port that types the script by " +
          "its left operand reads Any, refuses it, and cannot spend.")
      val intRows = for {
        x <- Seq("int-1", "exp.int", "mul.int", "exp.ctl")
        (form, tree) <- forms(it, all = false)
      } yield Spend(family, s"int.$x", form, tree, if (family == "DCTX") Some(Scripts(x)) else None,
        if (family == "DREG") Some(Scripts(x)) else None, valid = x == "int-1",
        s"`sigmaProp(true || $read[Int] + 1 == 1)`, $slot = the bytes of ${ScriptText(x)}",
        x match {
          case "int-1" => "THE CONTROL: the script is an Int; it matches, the branch is not taken, the box SPENDS."
          case "exp.ctl" => "THE CONTROL OF THE CHECK: a group element against Int is refused by any reading of the " +
            "type, also where the branch is not taken; the spend is INVALID."
          case _ => s"$Separating The interpreter substitutes every such node before it evaluates, taken or not, and " +
            "checks the script's type against Int: the node is GroupElement, so the spend is INVALID although the " +
            "branch never runs. A port that types the script by its left operand (the Int 1) substitutes it, never " +
            "takes the branch and lets anyone spend the box: the over-accept."
        })
      geRows ++ intRows
    }
    req ++ eq ++ deser("DCTX", DctxGE, DctxInt, "executeFromVar", "var 1 (a context extension variable)") ++
      deser("DREG", DregGE, DregInt, "SELF.R4 deserialized as", "SELF.R4 (a Coll[Byte] register)")
  }

  def extract(): Json = {
    val entries = spends().zipWithIndex.map { case (s, i) =>
      val tree = ctx(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(s.treeHex)))
      require(hx(tree.bytes) == s.treeHex, s"${s.name}: the tree must travel as written")
      val regs: Regs = s.r4.map(sc => Map[ErgoBox.NonMandatoryRegisterId, EvaluatedValue[_ <: SType]](
        ErgoBox.R4 -> ByteArrayConstant(b(sc)))).getOrElse(Map())
      val bx = ctx(box(s"santa:nst:${s.name}", V, 1, tree, Nil, regs))
      val e = s.extVar.map(sc => ext(1.toByte -> ByteArrayConstant(b(sc)))).getOrElse(ext())
      val txHex = ctx(hex(txBytes(tx(Seq(input(bx, e)), Seq(candidate(V, 1))))))
      val v = TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil,
        AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)
      require(v.valid == s.valid, s"${s.name}: want valid=${s.valid}, got ${v.valid} ${v.reason.getOrElse("")}")
      val form = s.form match {
        case "v0" => "unsized, v0"; case "v0-sized" => "sized, v0"; case "v1-sized" => "sized, v1"
        case "v3-sized" => "sized, v3"
      }
      Json.obj(
        "name"                 -> Json.fromString(s"group-node-static-type-spend-${s.slug}#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(s"[${s.name}] ${s.what} (the tree $form) guards the box being " +
          s"SPENT. ${s.label}"),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
        "preHeader"            -> AuthoredTxStorageRent.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(Activated),
                                           "ergoTree"  -> Json.fromInt(Activated)),
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
