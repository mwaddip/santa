package santa

// Transaction-tier companion of AuthoredEvalCollectionLengthLimit: sigma-rust's whole trees
// (`trees`, `sigmaProp(<producer>.size > 0)` for the six producers that can return more than they were given, at the last
// accepted and the first refused count, constants inline and segregated, 44 rows), each guarding a box SPENT through
// TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under nine headers, at the activated version the row names
// (3: block 4; 2: block 3). The box is PLAIN (1,000,000 nanoErg, created at 100, no token, no register), the input's
// extension empty: nothing in the context but the tree. The jars' fullReduction is TrueProp exactly where the box spends;
// measured first (santa.spike.CollectionLengthLimitProbe): 64 / 64. A refused tree is in reach of any block (the accepted
// `append` tree costs 805). One file per activated version: `collection-length-limit`.
//
// THE BOX HOLDS THE TREE AS THE WRITER WRITES IT (a node re-writes the tree it parses); the description says so where
// that differs from the row's bytes. extract() FAILS LOUD unless each verdict is the jars'.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxCollectionLengthLimit {
  val Source = "santa:authored-collection-length-limit"
  val Op = "collection-length-limit"
  def path(activated: Int): String = s"transaction/${if (activated == 3) "v6" else "v5"}/authored/$Op.json"
  private val H = AuthoredTxStorageRent.H
  val E: AuthoredEvalCollectionLengthLimit.type = AuthoredEvalCollectionLengthLimit
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  val P2pk = "0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
  val PlainSelf: String = "c0843d" + P2pk + "640000" + "11" * 32 + "00"

  def spends(r: E.Row): Boolean = r.jarsOutcome == "value" && r.jarsValue == "TrueProp"
  def description(r: E.Row): String =
    s"[${r.name}] sigma-rust's whole tree (tree v${r.treeVersion}) guards a plain box (no token, no register; the input's " +
      s"extension empty), SPENT at activated ${r.activated} under nine headers; the jars' fullReduction: " +
      s"${r.jarsOutcome match { case "value" => r.jarsValue; case _ => s"throws ${r.jarsValue}" }}. " +
      s"The box ${if (spends(r)) "SPENDS" else "does NOT spend"}. ${E.label(r)}" +
      (if (boxTree(r) != r.rowTreeHex) s" The box holds the tree as the writer writes it (`${boxTree(r)}`; the row's " +
        s"bytes `${r.rowTreeHex}`): a node re-writes the tree it parses." else "")

  /** The tree as a box a node created holds it: the writer's form. */
  def boxTree(r: E.Row): String = VersionContext.withVersions(3, 3)(hx(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(
    ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(r.rowTreeHex)))))
  def boxHex(r: E.Row): String = "c0843d" + boxTree(r) + PlainSelf.drop(6 + P2pk.length)
  /** Per activated version: the rows it holds, the first of a tree; and the repeats left out. */
  def plan(): Map[Int, (Vector[E.Row], Vector[E.Row])] =
    E.allRows().filter(_.whole).groupBy(_.activated).map { case (a, rs) =>
      val seen = collection.mutable.Set[String]()
      a -> rs.partition(r => seen.add(boxTree(r)))
    }

  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  def validate(r: E.Row): (String, String, TxEngine.Verdict) = {
    val c = ctxOf(r.activated)
    val raw = b(boxHex(r))
    val bx = VersionContext.withVersions(3, 3)(org.ergoplatform.ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(raw)))
    require(java.util.Arrays.equals(bx.id, scorex.crypto.hash.Blake2b256(raw)), s"${r.name}: box id")
    val txHex = VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(org.ergoplatform.Input(bx.id,
      new sigma.interpreter.ProverResult(Array.emptyByteArray, sigma.interpreter.ContextExtension.empty))), Seq(candidate(1000000L, 100))))))
    (hx(raw), txHex, TxEngine.validateBytes(txHex, Seq(hx(raw)), Nil, c.headersHex, c.preHeader, AuthoredTxStorageRent.params))
  }

  def extract(): Map[Int, Json] = plan().map { case (a, (rs, _)) =>
    val c = ctxOf(a)
    val entries = rs.zipWithIndex.map { case (r, i) =>
      val (boxHex, txHex, v) = validate(r)
      require(v.valid == spends(r), s"${r.name} a$a: the jars ${r.jarsOutcome} ${r.jarsValue}, got valid=${v.valid} ${v.reason.getOrElse("")}")
      Json.obj(
        "name" -> Json.fromString(s"$Op-${r.slug}-a$a#$i"),
        "source" -> Json.fromString(Source), "description" -> Json.fromString(description(r)),
        "tx_bytes_hex" -> Json.fromString(txHex), "input_boxes_hex" -> Json.arr(Json.fromString(boxHex)),
        "data_input_boxes_hex" -> Json.arr(), "headers_hex" -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader" -> c.preHeader, "parameters" -> AuthoredTxStorageRent.params,
        "context" -> Json.obj("height" -> Json.fromInt(H)),
        "version" -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(r.treeVersion)),
        "expected" -> Json.obj("valid" -> Json.fromBoolean(v.valid), "cost" -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
          "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
    }
    a -> Json.obj("schema" -> Json.fromString("santa-transaction/v1"), "op" -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"), "entries" -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = extract().foreach { case (a, env) =>
    val p = vectorsRoot.resolve(path(a))
    java.nio.file.Files.createDirectories(p.getParent)
    java.nio.file.Files.write(p, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
