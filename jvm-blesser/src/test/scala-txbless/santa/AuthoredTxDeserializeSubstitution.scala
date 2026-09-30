package santa

// AuthoredTxDeserializeSubstitution — the spend part of ergots' node-construction requests (2026-09-30), their
// residual 12: spends of trees that carry DeserializeRegister or DeserializeContext. Blessed through
// TxEngine.validateBytes (ergo-core 6.0.6 validateStateful) under the storage-rent vectors' synthetic context
// (AuthoredTxStorageRent: ten v4 headers below H = 1051200, the launch parameters).
//
// How the JVM substitutes (sigma-state v6.0.6):
// - A tree with a Deserialize node is reduced by reductionWithDeserialize (`Interpreter.scala:240-265`). From V6 it
//   charges the tree's bytes × 2 (CostPerTreeByte, `:88`) and runs applyDeserializeContextJITC (`:149-157`) inside
//   trySoftForkable: everywherebu(strategy(substDeserialize)), then toValidScriptTypeJITC (`:598-602`), which wraps a
//   Boolean root in BoolToSigmaProp and throws for a root that is neither Boolean nor SigmaProp.
// - Kiama's strategy catches a ClassCastException and returns None (`Rewriter.scala:180-191`): the node stays where
//   it is, and throws if it is ever evaluated ("Should be overriden"). The catch covers substDeserialize only.
// - everywherebu rebuilds each ancestor of a replaced node through its constructor, by reflection. A constructor that
//   reads the new child's type and throws rejects the spend, wrapped in InvocationTargetException, even in a branch
//   that is never evaluated.
// - DeserializeRegister (`ErgoLikeInterpreter.scala:17-37`) reads the register through ErgoBoxCandidate.get, which
//   synthesizes R0-R3 (`ErgoBoxCandidate.scala:69-83`). `eba.value.toArray` throws ClassCastException for a register
//   that is not a Coll[Byte]. The decode (deserializeMeasured, `Interpreter.scala:99-107`) charges twice the length
//   once ValueSerializer.deserialize returns; a decoded type other than the declared one is a sys.error; an absent
//   register gives the default, untyped.
// - DeserializeContext (`Interpreter.scala:110-126`) takes a Coll[Byte] variable only, and a decoded type other than
//   the declared one fails rule 1000 (CheckDeserializedScriptType).

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.{ByteArrayConstant, EvaluatedValue, IntConstant, SType}
import sigma.data.AvlTreeData
import sigma.interpreter.ContextExtension
import sigma.serialization.ErgoTreeSerializer
import sigmastate.helpers.{ErgoLikeContextTesting, ErgoLikeTestInterpreter}
import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.{NonMandatoryRegisterId, R4}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxDeserializeSubstitution {
  val SpendPath = "transaction/v6/authored/deserialize-substitution-spend.json"
  private val V3: Byte = VersionContext.V6SoftForkVersion
  private val Activated = 3
  private val ErgoTreeV = 0
  private val V = 1000000000L // the spent box's value
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private type Val = EvaluatedValue[_ <: SType]

  /** The spent box: `treeHex` parsed at (3, 3), with R4 = `r4` if given. Its bytes carry the tree as given. */
  private def spent(i: Int, treeHex: String, r4: Option[Val]): ErgoBox = VersionContext.withVersions(V3, V3) {
    val t = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex))
    val regs = r4.map(v => Map[NonMandatoryRegisterId, Val](R4 -> v)).getOrElse(Map.empty[NonMandatoryRegisterId, Val])
    val bx = box(s"santa:tx-deserialize-substitution:$i", V, 1, tree = t, regs = regs)
    require(hex(bx.bytes).startsWith(hex(vlqU32(V)) + treeHex), s"#$i: the box must carry the tree as given: ${hex(bx.bytes)}")
    bx
  }

  private def validate(txHex: String, inputs: Seq[ErgoBox]): TxEngine.Verdict =
    TxEngine.validateBytes(txHex, inputs.map(x => hex(x.bytes)), Nil, AuthoredTxStorageRent.headersHex,
      AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)

  /** The cause chain of a failed reduction of the same spend, run directly on sigma-state's interpreter. TxEngine's
    * reason names only the outermost exception; for a failed rebuild that is the reflective wrapper. */
  private def reductionCauses(bx: ErgoBox, e: ContextExtension): List[String] = VersionContext.withVersions(V3, V3) {
    val t = tx(Seq(input(bx, e)), Seq(candidate(bx.value, 1)))
    val ctx = ErgoLikeContextTesting(H, AvlTreeData.dummy, Array.fill(33)(2.toByte), IndexedSeq(bx), t, bx, V3, e)
    Try(new ErgoLikeTestInterpreter().fullReduction(bx.ergoTree, ctx)) match {
      case Success(r) => sys.error(s"the direct reduction must fail, got $r")
      case Failure(x) =>
        Iterator.iterate(x)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").toList
    }
  }

  /** Spend the box `treeHex` (R4 = `r4`) into one output of the same value, with no proof and input 0's extension
    * {1: `var1`} or empty. The JVM's verdict must be `want`, its reason must mention `because`, and a direct reduction's
    * cause chain must mention `cause`. */
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
      "source"               -> Json.fromString("santa:authored-tx-deserialize-substitution:spend"),
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

  // Opcodes: ByIndex b2, Tuple 86, Filter b5, FuncValue d9, OptionGet e4, GetVar e3, DeserializeRegister d5 (register,
  // type, default flag, default), DeserializeContext d4 (type, id), If 95, EQ 93, GT 91, SizeOf b1, Negation f0,
  // Plus 9a, Apply da. Types: SAny 61, Int 04, SigmaProp 08, Coll[Int] 10, Coll[Long] 11, Option[Boolean] 25.
  private val BI      = "b2" + "860204000400" + "0400" + "00" // ByIndex(Tuple(0, 0), 0): a tuple's items are SAny
  private val FBI     = "b5" + BI + "d9010104" + "0101"     // Filter(BI, (i: Int) => true), 17 bytes
  private val GV      = "e4" + "e30161"                      // OptionGet(GetVar(1, SAny))
  private val OGBI    = "e4" + BI                            // OptionGet(BI)
  private val Apply00 = "da" + "0400" + "01" + "0400"        // Apply(Int 0, [Int 0])
  private def dr(t: String, d: Option[String]): String = "d5" + "04" + t + d.map("01" + _).getOrElse("00")
  private def dead(c: String): String = "00" + "d1" + "95" + "0100" + c + "0101" // sigmaProp(If(false, c, true))
  private def live(c: String): String = "00" + "d1" + c
  private def bytes(h: String): Option[Val] = Some(ByteArrayConstant(b(h)))

  private def spendEntries: Seq[Json] = {
    val note = "The input spends a box of value 1000000000 into one output of the same value, with no proof. BI = " +
      "ByIndex(Tuple(0, 0), 0), typed SAny (a tuple is a collection of SAny); F(x) = Filter(x, (i: Int) => true); " +
      "GV = OptionGet(GetVar(1, SAny)); dead(c) = sigmaProp(If(false, c, true)), whose c is never evaluated."
    val stays = "Kiama's strategy catches a ClassCastException and returns None (Rewriter.scala:180-191), so the node " +
      "stays where it is"
    val rebuilt = "everywherebu rebuilds the ancestors of the replaced node through their constructors, by reflection " +
      "and outside the strategy's catch"
    val overriden = "Should be overriden in class sigma.ast.DeserializeRegister"
    Seq(
      entry(0, "s3j-decode-cast-swallowed-dead-accept",
        "ergots' S3j. The spent tree is dead(EQ(DeserializeRegister(R4, SAny), GV)), and R4 holds the bytes of " +
        "OptionGet(BI). Decoding them builds OptionGet, whose constructor casts its input's type to SOption " +
        "(transformers.scala:600-601), and BI is typed SAny: a ClassCastException inside the decode. " + stays +
        s", and in the dead branch it is never evaluated: valid. The decode threw before its charge. $note",
        dead("93" + dr("61", None) + GV), want = true, r4 = bytes(OGBI)),
      entry(1, "l1-decode-cast-swallowed-live-reject",
        "ergots' L1. The spent tree is sigmaProp(DeserializeRegister(R4, Int) == 1), live, and R4 holds the bytes of " +
        "If(true, 1, SizeOf(OptionGet(BI))). The decode fails in OptionGet's constructor as in #0, the node stays, " +
        s"and evaluating it throws ('$overriden'): invalid. An impl that substitutes the decoded If reduces the " +
        s"script to 1 == 1 and accepts: the over-accept. $note",
        live("93" + dr("04", None) + "0402"), want = false, r4 = bytes("950101" + "0402" + "b1" + OGBI),
        because = overriden),
      entry(2, "l1-twin-decodes-live-accept",
        "The twin: R4 holds the bytes of If(true, 1, 2), which decode, typed Int as declared. The node is replaced, and " +
        s"1 == 1: valid. $note",
        live("93" + dr("04", None) + "0402"), want = true, r4 = bytes("950101" + "0402" + "0404")),
      entry(3, "s3-type-read-cast-swallowed-dead-accept",
        "ergots' S3. The spent tree is dead(EQ(DeserializeRegister(R4, SAny, F(BI)), GV)), and R4 holds the bytes of " +
        "F(BI). The decode succeeds (Filter reads no child type when it is built) and is charged 2 x 17 = 34 " +
        "(Interpreter.scala:99-107). The type check then reads F(BI)'s type, whose `def tpe = input.tpe` casts BI's " +
        "SAny to a collection (transformers.scala:121): a ClassCastException, swallowed as well, so the node stays: " +
        s"valid. It costs #6's cost plus the 34. $note",
        dead("93" + dr("61", Some(FBI)) + GV), want = true, r4 = bytes(FBI)),
      entry(4, "s15-int-register-dead-accept",
        "ergots' S15. The spent tree is dead(EQ(DeserializeRegister(R4, Int), 1)), and R4 is Int 1. DeserializeRegister " +
        "takes the register as an EvaluatedValue and calls value.toArray (ErgoLikeInterpreter.scala:20-22): a " +
        s"ClassCastException for an Int. $stays, and the default is not reached: valid. $note",
        dead("93" + dr("04", None) + "0402"), want = true, r4 = Some(IntConstant(1))),
      entry(5, "s15-int-register-live-reject",
        s"The same live: sigmaProp(DeserializeRegister(R4, Int) == 1), R4 = Int 1. The node stays and is evaluated " +
        s"('$overriden'): invalid. An impl that takes an Int R4 as the value accepts: the over-accept. $note",
        live("93" + dr("04", None) + "0402"), want = false, r4 = Some(IntConstant(1)), because = overriden),
      entry(6, "s2-default-under-eq-accept",
        "ergots' S2. The spent tree is dead(EQ(DeserializeRegister(R4, SAny, F(BI)), GV)), and R4 is absent: the default " +
        s"F(BI) replaces the node, untyped (ErgoLikeInterpreter.scala:37). $rebuilt: EQ, If and BoolToSigmaProp read " +
        "no type of the changed child, so they build, and the branch is dead: valid. An impl that type-checks the " +
        s"default against the declared type rejects. $note",
        dead("93" + dr("61", Some(FBI)) + GV), want = true),
      entry(7, "s1-default-under-sizeof-gt-accept",
        "ergots' S1. The spent tree is dead(GT(SizeOf(DeserializeRegister(R4, SAny, F(BI))), 0)), R4 absent: SizeOf " +
        s"and GT read no child type when they are rebuilt: valid. $note",
        dead("91" + "b1" + dr("61", Some(FBI)) + "0400"), want = true),
      entry(8, "s8-default-if-rebuilt-reject",
        "ergots' S8. The spent tree is dead(EQ(If(true, DeserializeRegister(R4, SAny, F(BI)), GV), GV)), R4 absent. " +
        s"$rebuilt. If's constructor reads all three children's types (Quadruple.opType, trees.scala:1313), and " +
        "F(BI)'s type read throws ClassCastException: the spend is invalid, although the branch is dead. An impl that " +
        s"does not read the types of rebuilt nodes accepts. $note",
        dead("93" + ("950101" + dr("61", Some(FBI)) + GV) + GV), want = false,
        because = "InvocationTargetException", cause = Some("SAny$ cannot be cast to class sigma.ast.SCollection")),
      entry(9, "s5-default-negation-rebuilt-reject",
        "ergots' S5. The spent tree is dead(GT(Negation(DeserializeRegister(R4, Int, Coll[Int]())), 0)), R4 absent. The " +
        "rebuilt Negation requires a numeric input (trees.scala:881-882), and the default is a Coll[Int]: " +
        s"IllegalArgumentException, invalid. $note",
        dead("91" + "f0" + dr("04", Some("1000")) + "0400"), want = false,
        because = "InvocationTargetException", cause = Some("invalid type Coll[SInt$]")),
      entry(10, "s6-default-optionget-rebuilt-reject",
        "ergots' S6. The spent tree is dead(OptionGet(DeserializeRegister(R4, Option[Boolean], Coll[Boolean]()))), R4 " +
        "absent. The rebuilt OptionGet casts its input's type to SOption (transformers.scala:600-601): " +
        s"ClassCastException, invalid. $note",
        dead("e4" + dr("25", Some("0d00"))), want = false,
        because = "InvocationTargetException", cause = Some("SCollectionType cannot be cast to class sigma.ast.SOption")),
      entry(11, "s9-default-plus-rebuilt-reject",
        "ergots' S9. The spent tree is dead(GT(Plus(DeserializeRegister(R4, Int, F(BI)), 0), 0)), R4 absent. The rebuilt " +
        "Plus reads both operands' types (ArithOp's opType, trees.scala:704-708), and F(BI)'s read throws " +
        s"ClassCastException: invalid. $note",
        dead("91" + ("9a" + dr("04", Some(FBI)) + "0400") + "0400"), want = false,
        because = "InvocationTargetException", cause = Some("SAny$ cannot be cast to class sigma.ast.SCollection")),
      entry(12, "s10-decoded-notype-vs-sany-reject",
        "ergots' S10. The spent tree is dead(EQ(DeserializeRegister(R4, SAny), GV)), and R4 holds the bytes of " +
        "Apply(Int 0, [Int 0]). They decode, and an Apply of a non-function is typed NoType (values.scala:1247-1251). " +
        "NoType is not SAny, so DeserializeRegister fails with sys.error (ErgoLikeInterpreter.scala:25-26), which the " +
        "strategy does not catch: invalid, although the branch is dead. An impl that counts NoType as equal to SAny " +
        s"accepts. $note",
        dead("93" + dr("61", None) + GV), want = false, r4 = bytes(Apply00),
        because = "expected deserialized value to have type SAny; got NoType"),
      entry(13, "s16-context-type-read-cast-swallowed-dead-accept",
        "ergots' S16. The spent tree is dead(EQ(DeserializeContext(SAny, 1), GV)), and variable 1 holds the bytes of " +
        "F(BI). The decode succeeds and is charged 34; rule 1000's type check (CheckDeserializedScriptType) reads " +
        s"F(BI)'s type and throws ClassCastException. $stays: valid. It costs #14's cost plus the 34. $note",
        dead("93" + "d46101" + GV), want = true, var1 = bytes(FBI)),
      entry(14, "s16b-context-decode-cast-swallowed-dead-accept",
        "ergots' S16b. The same tree, with variable 1 holding the bytes of OptionGet(BI): the decode itself throws " +
        s"ClassCastException (OptionGet's constructor), before its charge. $stays: valid. $note",
        dead("93" + "d46101" + GV), want = true, var1 = bytes(OGBI)),
      entry(15, "s16c-context-decoded-notype-vs-sany-reject",
        "ergots' S16c. The same tree, with variable 1 holding the bytes of Apply(Int 0, [Int 0]): they decode as " +
        "NoType, and rule 1000 (CheckDeserializedScriptType) throws a ValidationException. trySoftForkable would " +
        s"replace the tree only if the rule were soft-forked: invalid. $note",
        dead("93" + "d46101" + GV), want = false, var1 = bytes(Apply00), because = "ValidationRule(1000"),
      entry(16, "r1-self-proposition-decoded-accept",
        "DeserializeRegister(R1) reads SELF's proposition bytes (ErgoBoxCandidate.get, ErgoBoxCandidate.scala:72). The " +
        "spent tree is 10 01 04 04 d1 93 b2 d5 01 10 00 04 00 00 73 00: segregated, constant 0 = Int 2, and the body " +
        "sigmaProp(DeserializeRegister(R1, Coll[Int])(0) == placeholder 0). Read as a value, the tree's bytes are a " +
        "Coll[Int] constant (type 10) of length 01 whose item is the zigzag 04, so 2; the decode ignores the rest. " +
        s"That is the declared type, so the node becomes Coll[Int](2), and 2 == 2: valid. $note",
        "10" + "01" + "0404" + "d193" + "b2" + "d5011000" + "0400" + "00" + "7300", want = true),
      entry(17, "r1-self-proposition-value-reject",
        "The twin: constant 0 = Int 1 (10 01 04 02 ...). The decode still gives Coll[Int](2), and 2 == 1 is false: " +
        s"invalid. It pins the decoded value. $note",
        "10" + "01" + "0402" + "d193" + "b2" + "d5011000" + "0400" + "00" + "7300", want = false,
        because = "Success((false"),
      entry(18, "r1-self-proposition-type-mismatch-reject",
        "The spent tree 10 01 04 02 d1 93 b1 d5 01 11 00 73 00, sigmaProp(SizeOf(DeserializeRegister(R1, Coll[Long])) " +
        "== placeholder 0), declares Coll[Long]. Its bytes still decode as a Coll[Int]: sys.error, invalid. " + note,
        "10" + "01" + "0402" + "d193" + "b1" + "d5011100" + "7300", want = false,
        because = "expected deserialized value to have type Coll[SLong$]; got Coll[SInt$]"),
      entry(19, "r1-unsized-tree-decode-fails-reject",
        "The spent tree is dead(EQ(DeserializeRegister(R1, SAny), GV)), an unsized v0 tree. Its bytes start with 00, a " +
        "constant of type code 0, so the decode throws InvalidTypePrefix, a SerializerException the strategy does not " +
        "catch: invalid, although the branch is dead. An impl that does not synthesize R1, or treats it as absent, " +
        s"accepts. $note",
        dead("93" + "d5016100" + GV), want = false, because = "InvalidTypePrefix"),
      entry(20, "root-boolean-default-true-accept",
        "The spent tree is the root DeserializeRegister(R4, SigmaProp, default true) (00 d5 04 08 01 01 01), typed " +
        "SigmaProp at parse, as declared. R4 is absent, so the default true, a Boolean, replaces the root, and " +
        "toValidScriptTypeJITC wraps a Boolean root in BoolToSigmaProp (Interpreter.scala:598-602): sigmaProp(true), " +
        s"valid. An impl that requires the substituted root to be a SigmaProp rejects. $note",
        "00" + "d5040801" + "0101", want = true),
      entry(21, "root-boolean-default-false-reject",
        s"The same with the default false: sigmaProp(false), invalid. $note",
        "00" + "d5040801" + "0100", want = false, because = "Success((false"),
      entry(22, "root-int-default-reject",
        "The same with the default Int 1: toValidScriptTypeJITC throws for a root that is neither Boolean nor " +
        s"SigmaProp: invalid. $note",
        "00" + "d5040801" + "0402", want = false,
        because = "Context-dependent pre-processing should produce tree of type Boolean or SigmaProp"),
      entry(23, "root-no-default-reject",
        s"The root DeserializeRegister(R4, SigmaProp) with no default, R4 absent: the node stays, and evaluating the " +
        s"root throws ('$overriden'): invalid. $note",
        "00" + "d5040800", want = false, because = overriden))
  }

  private def envelope(op: String, es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString(op),
    "blessed_by" -> Json.fromString(AuthoredTxStorageRent.BlessedBy),
    "entries"    -> Json.arr(es: _*))

  def blessAll(): Seq[(String, Json)] =
    Seq(SpendPath -> envelope("tx:authored:deserialize-substitution-spend", spendEntries))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
