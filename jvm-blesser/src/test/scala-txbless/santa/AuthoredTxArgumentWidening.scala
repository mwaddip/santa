package santa
// AuthoredTxArgumentWidening — the CONSENSUS form of the argument-widening family (AuthoredEvalArgumentWidening;
// sigma-rust's santa-probe-argument-kind, fork PR 88): a widened argument behaves in a spend as it does at eval,
// through all three reflective paths, and the refusals refuse the spend. Measured then by SANTA
// (santa.spike.ArgumentKindProbe §2; prompts/drafts/sigma-rust-argument-kind-reply.md, Ask 2) and vectored now.
//
// THE SPEND: one input whose box carries the row's script (tree v3), its extension var 1 = Int 5, one output, under
// the storage-rent vectors' synthetic context. The script is `sigmaProp(row == row)` unless noted, so a row that
// evaluates (to anything) spends and a row that throws does not. W10's `getVarFromInput` reads var 1 of input 0, so
// there the row reads `Some(5)`, and `sigmaProp(row.get == 5)` / `== 6` pin the value.
//
// Also the one row the round never asked for: an INT argument where `getVarFromInput` declares a Short (N1). Java
// reflection never narrows, so it is refused, where an implementation that converts any integer reads `Some(5)`.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast._
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer, ValueSerializer}
import santa.runner.TxEngine
import RentFixtures._

object AuthoredTxArgumentWidening {
  val Path = "transaction/v6/authored/argument-widening.json"
  val Op = "argument-widening"
  val Source = "santa:authored-argument-widening"
  val Activated: Byte = 3
  val H: Int = AuthoredTxStorageRent.H
  val Value = 1000000000L

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  private def any(v: Value[_ <: SType]): Value[SType] = v.asInstanceOf[Value[SType]]
  private def bool(v: Value[SType]) = v.asInstanceOf[Value[SBoolean.type]]
  private def parse(body: String): Value[SType] = VersionContext.withVersions(Activated, Activated)(
    ValueSerializer.deserialize(b(body)).asInstanceOf[Value[SType]])
  private def eval(id: String): String = AuthoredEvalArgumentWidening.Rows.find(_.id == id).getOrElse(sys.error(id)).body

  /** (id, script text, the SigmaProp root, valid on the JVM, why) */
  case class Row(id: String, script: String, root: () => Value[SType], valid: Boolean, why: String)
  private def selfEq(id: String): () => Value[SType] = () => {
    val r = parse(eval(id)); any(BoolToSigmaProp(bool(any(EQ(r, r)))))
  }
  private def getIs(id: String, k: Int): () => Value[SType] = () =>
    any(BoolToSigmaProp(bool(any(EQ(any(OptionGet(parse(eval(id)).asInstanceOf[Value[SOption[SType]]])), any(IntConstant(k)))))))
  private def asIs(id: String): () => Value[SType] = () => parse(eval(id))

  private val Spends = "SPENDS: the row evaluates, so `row == row` is true."
  private val Widens = s"The argument is widened by Java reflection, as at eval. $Spends SEPARATING: an implementation " +
    "that refuses the narrower argument refuses the spend."
  private val Control = s"CONTROL: the declared type. $Spends"
  private val Refused = "INVALID: the row throws, so the script cannot reduce."

  val Rows: Seq[Row] = Seq(
    Row("W1c", "c.apply(1) == c.apply(1)", selfEq("W1c"), valid = true, Control),
    Row("W1", "lie-Byte apply, == itself", selfEq("W1"), valid = true, Widens),
    Row("W1s", "lie-Short apply, == itself", selfEq("W1s"), valid = true, Widens),
    Row("W7b", "Global.decodeNbits(1.toByte), == itself", selfEq("W7b"), valid = true, Widens),
    Row("W9c", "SELF.getReg[Long](0), == itself", selfEq("W9c"), valid = true, Control),
    Row("W9", "SELF.getReg[Long](lie(0, 0.toByte)), == itself", selfEq("W9"), valid = true, Widens),
    Row("W9s", "SELF.getReg[Long](0.toShort), == itself", selfEq("W9s"), valid = true, Widens),
    Row("W10c", "CONTEXT.getVarFromInput[Int](0.toShort, 1.toByte).get == 5", getIs("W10c", 5), valid = true,
      "CONTROL: input 0's var 1 is Int 5."),
    Row("W10", "CONTEXT.getVarFromInput[Int](lie(0.toShort, 0.toByte), 1.toByte).get == 5", getIs("W10", 5),
      valid = true, "A Byte where the input index is a Short: widened, the call reads Some(5). SEPARATING: an " +
        "implementation that refuses the Byte refuses the spend."),
    Row("W10!", "CONTEXT.getVarFromInput[Int](lie(0.toShort, 0.toByte), 1.toByte).get == 6", getIs("W10", 6),
      valid = false, "INVALID: the call reads 5. Pins that the widened call reads the RIGHT variable, not a default."),
    Row("Q2", "sigmaProp(Coll(true, true).apply(1.toByte))", asIs("Q2"), valid = true, "The IV-typed widened call " +
      "reaches a script through `sigmaProp`, which does not check its operand's static type: SPENDS. SEPARATING: an " +
      "implementation that refuses the Byte argument refuses the spend."),
    Row("Q2c", "sigmaProp(Coll(true, true).apply(1))", asIs("Q2c"), valid = true, "CONTROL for Q2."),
    Row("W1n", "lie-Long apply, == itself", selfEq("W1n"), valid = false, s"$Refused Reflection never narrows a Long " +
      "to an Int. SEPARATING: an implementation that converts any integer lets it spend."),
    Row("X1", "1.shiftLeft(lie(1, 1.toByte)) == itself", selfEq("X1"), valid = false, s"$Refused `shiftLeft` is a " +
      "handler that casts its argument. SEPARATING, as W1n."),
    Row("W1d", "c.apply(1.toByte) == itself", selfEq("W1d"), valid = false, s"$Refused Both operands are typed `IV` " +
      "(the Byte constant leaves `apply` unspecialised), so the tree parses and `EQ`'s operand check throws `Unknown " +
      "type IV`: not the widening, the consumer. SEPARATING: a fix that specialises the call evaluates it."),
    Row("N1", "CONTEXT.getVarFromInput[Int](lie(0.toShort, 0), 1.toByte).get == 5",
      () => any(BoolToSigmaProp(bool(any(EQ(any(OptionGet(VersionContext.withVersions(Activated, Activated) {
        MethodCall(any(Context), SMethod.fromIds(101.toByte, 12.toByte),
          IndexedSeq(any(If(FalseLeaf, any(ShortConstant(0)), any(IntConstant(0)))), any(ByteConstant(1))),
          Map(SContextMethods.getVarFromInputMethod.stype.tpeParams.head.ident -> SInt))
      }.asInstanceOf[Value[SOption[SType]]])), any(IntConstant(5))))))), valid = false,
      s"$Refused An INT where the input index is a Short: reflection never narrows. SEPARATING: an implementation " +
        "that converts any integer reads Some(5) and lets it spend."))

  def treeOf(r: Row): String = {
    val body = VersionContext.withVersions(Activated, Activated) {
      val w = SigmaSerializer.startWriter(); ValueSerializer.serialize(r.root(), w); hx(w.toBytes)
    }
    "0b" + vlq(body.length / 2) + body
  }

  def extract(): Json = {
    val entries = Rows.zipWithIndex.map { case (r, i) =>
      val treeHex = treeOf(r)
      val slug = r.id.toLowerCase.replace("!", "-sep")
      val (txHex, boxes) = VersionContext.withVersions(Activated, Activated) {
        val tree = ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(treeHex))
        val bx = box(s"santa:aw:$slug", Value, 1, tree)
        (hex(txBytes(tx(Seq(input(bx, ext(1.toByte -> IntConstant(5)))), Seq(candidate(Value, 1))))), Seq(hex(bx.bytes)))
      }
      val v = TxEngine.validateBytes(txHex, boxes, Nil,
        AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader, AuthoredTxStorageRent.params)
      require(v.valid == r.valid, s"row ${r.id}: want valid=${r.valid}, got ${v.valid} ${v.reason.getOrElse("")}")
      Json.obj(
        "name"                 -> Json.fromString(s"argument-widening-spend-$slug#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(s"[${r.id}] `${r.script}`, tree v3, guards the one input (its " +
          s"extension var 1 = Int 5), one output, at height $H. ${r.why}"),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(boxes.map(Json.fromString): _*),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(AuthoredTxStorageRent.headersHex.map(Json.fromString): _*),
        "preHeader"            -> AuthoredTxStorageRent.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(Activated.toInt), "ergoTree" -> Json.fromInt(3)),
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
