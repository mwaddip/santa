package santa

// Transaction-tier companion of AuthoredEvalGenericProducers (sigma-rust's santa-probe-specialised-patch-array-2,
// 2026-11-17; THE RULE is there): the producers a node holds GENERIC, as spends.
//
//  - ROW: per receiver, method, tree version and sigma-rust's gradeable class, the first authored row in the table's
//    order, as `sigmaProp({ val v = <row>; true })` at the row's tree version. The JVM evaluates the ValDef, so the box
//    spends exactly where the row answers (measured on every row: the wrap answers `true` exactly there, cost + 12).
//    Not the `getOrElse` / `ByIndex` rows: their direct default of another type gives the call a type variable, and a
//    ValDef of it throws (`Unknown type IV`) where the bare row answers, so no such wrap carries them.
//  - REQ: sigma-rust's three whole trees (tree v0): `sigmaProp(SELF.R4[Coll[Byte]].get.patch(0, Coll[Int](), 0).size
//    == 2)`, the same over variable 1, and `sigmaProp(SELF.R1[Coll[Byte]].get.patch(0, Coll[Int](), 0).size > 0)`.
//
// Each spends the context's own SELF with the tree under test spliced into its RAW bytes (its registers and token as
// sigma-rust wrote them), the input carrying the context's RAW extension spliced over the writer's re-encoding (the
// JVM writer turns an `83…` of booleans into `85…`), one output carrying the token. At activated 3 (block 4) and, for a
// tree-v0 row, at activated 2 (block 3), under nine headers. extract() FAILS LOUD unless each verdict is the eval
// tier's.

import io.circe.Json
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.interpreter.{ContextExtension, ProverResult}
import sigma.serialization.SigmaSerializer
import org.ergoplatform.{ErgoBox, Input}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxGenericProducers {
  val Op = "generic-producers"
  val Source = "santa:authored-generic-producers"
  val Paths: Map[Int, String] = Map(
    2 -> "transaction/v5/authored/generic-producers.json",
    3 -> "transaction/v6/authored/generic-producers.json")
  private val E = AuthoredEvalGenericProducers
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private def vlq(n: Int): String = { val w = SigmaSerializer.startWriter(); w.putUInt(n.toLong); hx(w.toBytes) }
  def treeAt(tv: Int, root: String): String =
    if (tv == 0) "00" + root else f"${8 + tv}%02x" + vlq(root.length / 2) + root

  /** `sigmaProp({ val v = <row>; true })`. */
  def wrap(body: String): String = "d1" + "d801" + "d60b" + body + "0101"

  /** The first family's extension (its ctx.env), and the P2PK tree both SELF boxes carry. */
  val CtxExtHex = "02010e020102020d0201"
  private val P2pkTree = "0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

  /** One spend: a family, a name, the tree, the context (rv-rc or the first family's), its activated version and verdict. */
  final case class Spend(family: String, name: String, treeVersion: Int, activated: Int, treeHex: String, rv: Boolean,
                         valid: Boolean, what: String, why: String) {
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
  }

  /** The requested trees: (name, tree v0 bytes). */
  val Requested: Seq[(String, String, String)] = Seq(
    ("R4.cc|empty-ints|size==2", "00d193b1dc0c13e4c6a7040e030400100004000404",
      "`sigmaProp(SELF.R4[Coll[Byte]].get.patch(0, Coll[Int](), 0).size == 2)`, R4 the expression `83020202010202`"),
    ("var1.cc|empty-ints|size==2", "00d193b1dc0c13e4e3010e030400100004000404",
      "`sigmaProp(getVar[Coll[Byte]](1).get.patch(0, Coll[Int](), 0).size == 2)`, variable 1 the expression " +
        "`83020202010202`"),
    ("R1|empty-ints|size>0", "00d191b1dc0c13e4c6a7010e030400100004000400",
      "`sigmaProp(SELF.R1[Coll[Byte]].get.patch(0, Coll[Int](), 0).size > 0)`, R1 read as a register"))

  def spends(): Vector[Spend] = {
    val rows = E.authored()
    val picked = rows.map(_._1).filterNot(_.item)
      .groupBy(r => (r.receiver, r.method, r.treeVersion, r.grade)).values.map(_.head.name).toSet
    val fromRows = rows.filter { case (r, _) => picked(r.name) }.map { case (r, o) =>
      val answers = o.isInstanceOf[E.Answer]
      Spend("ROW", r.name, r.treeVersion, r.activated, treeAt(r.treeVersion, wrap(r.body)), r.rv, answers,
        s"`sigmaProp({ val v = <row>; true })` over ${E.call(r)}",
        if (answers) s"SEPARATING ROW: the receiver is ${E.Holder(r.receiver)._2}, GENERIC on a node: the call answers " +
          "and the box SPENDS. A port that holds it specialised refuses the call, and the box with it."
        else s"CONTROL OF THE CLASS: the receiver is ${E.Holder(r.receiver)._2}, SPECIALISED on a node: the call " +
          "throws and the box does NOT spend.")
    }
    val req = for { (n, t, w) <- Requested; a <- Seq(2, 3) }
      yield Spend("REQ", n, 0, a, t, rv = true, valid = true, w,
        "The receiver is GENERIC on a node, so the empty Coll[Int] patch answers the receiver unchanged: the box SPENDS. " +
          "A port that holds it specialised refuses.")
    fromRows ++ req
  }

  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  /** The spent box's raw bytes, the transaction's bytes, and the JVM's verdict. */
  def validate(s: Spend): (Array[Byte], String, TxEngine.Verdict) = {
    val (selfHex, extHex) = if (s.rv) (E.RvSelfHex, E.RvExtHex) else (AuthoredEvalPatchArrayClass.SelfHex, CtxExtHex)
    require(selfHex.startsWith("c0843d" + P2pkTree), "SELF layout")
    val raw = b("c0843d" + s.treeHex + selfHex.drop(6 + P2pkTree.length))
    val bx = VersionContext.withVersions(3, 3)(ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(raw)))
    require(java.util.Arrays.equals(bx.id, Blake2b256(raw)), s"${s.name}: the box id hashes its bytes")
    val e = VersionContext.withVersions(3, 3)(ContextExtension.serializer.parse(SigmaSerializer.startReader(b(extHex))))
    val written = VersionContext.withVersions(3, 3) {
      val w = SigmaSerializer.startWriter(); ContextExtension.serializer.serialize(e, w); hx(w.toBytes)
    }
    val txw = VersionContext.withVersions(3, 3)(hex(txBytes(tx(
      Seq(Input(bx.id, new ProverResult(Array.emptyByteArray, e))),
      Seq(candidate(bx.value, bx.creationHeight, tokens = bx.additionalTokens.toArray.toSeq))))))
    require(txw.indexOf(written) >= 0 && txw.indexOf(written) == txw.lastIndexOf(written), s"${s.name}: the extension")
    val txHex = txw.replace(written, extHex)
    val c = ctxOf(s.activated)
    (raw, txHex, TxEngine.validateBytes(txHex, Seq(hx(raw)), Nil, c.headersHex, c.preHeader, AuthoredTxStorageRent.params))
  }

  def extract(): Map[Int, Json] = spends().groupBy(_.activated).map { case (a, ss) =>
    val c = ctxOf(a)
    val entries = ss.zipWithIndex.map { case (s, i) =>
      val (raw, txHex, v) = validate(s)
      require(v.valid == s.valid, s"${s.name} a$a: want valid=${s.valid}, got ${v.valid} ${v.reason.getOrElse("")}")
      val ctx = if (s.rv) "sigma-rust's rv-rc SELF (R4..R9 and a token as it wrote them) and its five variables"
                else "the first family's SELF (R4..R6 and a token) and its two variables"
      Json.obj(
        "name"                 -> Json.fromString(s"generic-producers-spend-${s.family.toLowerCase}-${s.slug}-a$a#$i"),
        "source"               -> Json.fromString(Source),
        "description"          -> Json.fromString(s"[${s.family}|${s.name}] ${s.what} (tree v${s.treeVersion}) guards " +
          s"the box being SPENT at activated $a, under nine headers: $ctx, as raw bytes. ${s.why}"),
        "tx_bytes_hex"         -> Json.fromString(txHex),
        "input_boxes_hex"      -> Json.arr(Json.fromString(hx(raw))),
        "data_input_boxes_hex" -> Json.arr(),
        "headers_hex"          -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader"            -> c.preHeader,
        "parameters"           -> AuthoredTxStorageRent.params,
        "context"              -> Json.obj("height" -> Json.fromInt(H)),
        "version"              -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(s.treeVersion)),
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
