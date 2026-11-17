package santa

// Transaction-tier companion of AuthoredEvalPatchArrayClass (sigma-rust's santa-probe-specialised-patch-array,
// 2026-11-17; THE RULE is there): a specialised collection's `patch` casts the patch's array, also an empty one.
//
// For every receiver of the eval rows (37 built by the script, 27 the context brings) at each of its tree versions,
// two of its rows: the first MOVE (the jars throw) and the first CONTROL (the jars answer), in the table's order. Each is
// the tree `sigmaProp(<row>.size >= 0)`, true for whatever value the call answers, at the row's tree version, guarding a
// box SPENT through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful): at activated 3 (block 4, the storage-rent
// context) and, for a tree-v0 row, at activated 2 too (the window re-linked at block 3, AuthoredTxTreeVersion.Block3).
// Every spend has the same shape, so the context receivers find what they read: the box holds R4 = Coll[Byte](1, 2),
// R5 = Coll(true, false), R6 = Coll(Coll[Byte](1), Coll[Byte](2)) and one token (0x22 x 32, carried to the output), and
// the input's extension holds variable 1 = Coll[Byte](1, 2) and 2 = Coll(true, false): sigma-rust's ctx.env values,
// read from the same bytes. A move does not spend on the JVM; a port that reads the patch's items spends it. Except on
// `CONTEXT.preHeader.parentId` and `.votes`: a node builds those two GENERIC (ergo-core's PreHeader.toSigma, `toColl`),
// so their moves spend, against sigma-rust's table (measured here first; the eval families follow, EvalCore.preHeaderBytes).
//
// extract() FAILS LOUD unless each verdict is the jars' (a value spends, a throw does not).

import io.circe.Json
import org.ergoplatform.ErgoBox
import scorex.util.encode.Base16
import sigma.{Colls, VersionContext}
import sigma.ast.{EvaluatedValue, SType}
import sigma.data.Digest32Coll
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxPatchArrayClass {
  val Op = "patch-array-class"
  val Source = "santa:authored-patch-array-class"
  val Paths: Map[Int, String] = Map(
    2 -> "transaction/v5/authored/patch-array-class.json",
    3 -> "transaction/v6/authored/patch-array-class.json")
  private val V = 1000000000L
  private val H = AuthoredTxStorageRent.H
  private val E = AuthoredEvalPatchArrayClass

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  private def treeAt(tv: Int, root: String): String =
    if (tv == 0) "00" + root else f"${8 + tv}%02x" + vlq(root.length / 2) + root

  /** `sigmaProp(<row>.size >= 0)`: BoolToSigmaProp(GE(SizeOf(<row>), 0)). */
  def rootOf(body: String): String = "d192b1" + body + "0400"

  /** One spend: an eval row (its first activated version's) at one activated version. */
  final case class Spend(row: AuthoredEvalPatchArrayClass.Row, activated: Int) {
    def name: String = row.name
    /** A move in sigma-rust's table: a patch the jars refuse over a specialised receiver. */
    def move: Boolean = row.jarsOutcome == "throws"
    /** What a node does: spends where the call answers (a move over the pre-header's parentId or votes answers). */
    def spendsOnANode: Boolean = row.nodeAnswers
    def treeHex: String = treeAt(row.treeVersion, rootOf(row.body))
    def slug: String = row.slug
  }

  /** Per (receiver, tree version), the first move and the first control in the table's order, at each activated
    * version of the row. */
  def spends(): Vector[Spend] = {
    val rows = E.rows()
    val seen = collection.mutable.Set[String]()
    val firstOfName = rows.filter(r => seen.add(r.name))
    val picked = firstOfName.groupBy(r => (r.receiver, r.treeVersion, r.jarsOutcome)).values.map(_.head.name).toSet
    rows.filter(r => picked(r.name)).map(r => Spend(r, r.activated))
  }

  // ── the spend's box and input ────────────────────────────────────────────────────────────────────
  private def constantOf(hex: String): EvaluatedValue[_ <: SType] = AuthoredEvalSubstConstantsLift.constantOf(hex)
  val R6Bytes = "1a0201010102"
  val TheToken: ErgoBox.Token = (Digest32Coll @@ Colls.fromArray(Array.fill(32)(0x22.toByte)), 1L)
  private def regs: Regs = Map[ErgoBox.NonMandatoryRegisterId, EvaluatedValue[_ <: SType]](
    ErgoBox.R4 -> constantOf(E.Var1Bytes), ErgoBox.R5 -> constantOf(E.Var2Bytes), ErgoBox.R6 -> constantOf(R6Bytes))
  private def extension = ext(1.toByte -> constantOf(E.Var1Bytes), 2.toByte -> constantOf(E.Var2Bytes))

  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  private def description(s: Spend): String = {
    val r = s.row
    val why = if (s.move && s.spendsOnANode)
      "SEPARATING ROW: a node builds the pre-header's `parentId` and `votes` as GENERIC collections (ergo-core's " +
        "`PreHeader.toSigma`, `toColl`), which take an empty patch of any class: the call answers and the box SPENDS. " +
        "A port that treats them as specialised, as the collections a box, a register or a header brings are, refuses it."
    else if (s.move)
      "SEPARATING ROW: the JVM's specialised `patch` casts the patch's array before anything is stored and throws, so " +
        "the box does NOT spend; a port that reads the patch's items answers a value, whose size is never negative, " +
        "and spends it."
    else "The call answers, its size is never negative, and the box SPENDS."
    s"[${r.name}] `sigmaProp(${E.Receiver(r.receiver)}.patch(0, <patch>, 0).size >= 0)`, the patch ${E.patchText(r)} " +
      s"(tree v${r.treeVersion}), guards the box being SPENT at activated ${s.activated}, under nine headers. The box " +
      "holds R4 = Coll[Byte](1, 2), R5 = Coll(true, false), R6 = Coll(Coll[Byte](1), Coll[Byte](2)) and one token; the " +
      s"input's variable 1 = Coll[Byte](1, 2), 2 = Coll(true, false). $why"
  }

  /** The spend's box, its transaction's bytes, and the JVM's verdict. */
  def validate(s: Spend): (ErgoBox, String, TxEngine.Verdict) = {
    val (a, c) = (s.activated, ctxOf(s.activated))
    val tree = VersionContext.withVersions(3, 3)(ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(s.treeHex)))
    require(hx(VersionContext.withVersions(3, 3)(tree.bytes)) == s.treeHex, s"${s.name}: the tree must travel as written")
    val bx = VersionContext.withVersions(3, 3)(box(s"santa:pac:${s.name}:a$a", V, 1, tree, Seq(TheToken), regs))
    val txHex = VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(input(bx, extension)),
      Seq(candidate(V, 1, tokens = Seq(TheToken)))))))
    (bx, txHex, TxEngine.validateBytes(txHex, Seq(hex(bx.bytes)), Nil, c.headersHex, c.preHeader,
      AuthoredTxStorageRent.params))
  }

  def extract(): Map[Int, Json] = spends().groupBy(_.activated).map { case (a, ss) =>
    val c = ctxOf(a)
    val entries = ss.zipWithIndex.map { case (s, i) =>
      val (bx, txHex, v) = validate(s)
      require(v.valid == s.spendsOnANode, s"${s.name} a$a: the jars ${s.row.jarsOutcome}, got valid=${v.valid} " +
        v.reason.getOrElse(""))
      // `patch` is a MethodCall, which the JVM invokes reflectively: the reason names the wrapper, and the eval families
      // pin the ClassCastException inside it on the same rows.
      if (!v.valid) require(v.reason.exists(_.contains("#0 => Failure(java.lang.reflect.InvocationTargetException)")),
        s"${s.name} a$a: ${v.reason}")
      Json.obj(
        "name"                 -> Json.fromString(s"patch-array-class-spend-${s.slug}-a$a#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(description(s)),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(Json.fromString(hex(bx.bytes))),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader"            -> c.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(s.row.treeVersion)),
        "expected"             -> Json.obj(
          "valid"  -> Json.fromBoolean(v.valid),
          "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
          "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
    }
    a -> Json.obj(
      "schema"     -> Json.fromString("santa-transaction/v1"),
      "op"         -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
      "entries"    -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = extract().foreach { case (a, env) =>
    val path = vectorsRoot.resolve(Paths(a))
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
