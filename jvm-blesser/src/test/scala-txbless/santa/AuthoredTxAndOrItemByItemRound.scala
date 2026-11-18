package santa

// Transaction-tier companion of AuthoredEvalAndOrItemByItemRound: sigma-rust's whole trees of the round ("whole tree, the
// request's context", 52 rows, 78 at their activated versions), each guarding a box SPENT through TxEngine.validateBytes
// (ergo-core 6.0.7 validateStateful) under nine headers, at activated 3 (block 4) and, for a tree-v0 row, at 2 (block 3).
// The box is the request's SELF with the tree spliced in (R4..R9 as their raw bytes), the input's extension its raw bytes
// (variables 1 to 8). The jars' fullReduction (the rows' answer) is TrueProp exactly where the box spends; measured first
// (santa.spike.AndOrItemByItemRoundProbe): 78 / 78.
//
// extract() FAILS LOUD unless each verdict is the jars'.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxAndOrItemByItemRound {
  val Op = "and-or-item-by-item-context"
  val Source = "santa:authored-and-or-item-by-item"
  val Paths: Map[Int, String] = Map(
    2 -> "transaction/v5/authored/and-or-item-by-item-context.json",
    3 -> "transaction/v6/authored/and-or-item-by-item-context.json")
  private val H = AuthoredTxStorageRent.H
  private val E = AuthoredEvalAndOrItemByItemRound
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  private val P2pk = "0008cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

  final case class Spend(name: String, treeHex: String, treeVersion: Int, activated: Int, jarsOutcome: String,
                         jarsValue: String, jarsCost: String, cls: String) {
    def spends: Boolean = jarsOutcome == "value" && jarsValue.contains("TrueProp")
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_").replaceAll("_+", "_")
    def description: String =
      s"[$name] sigma-rust's whole tree (tree v$treeVersion) guards the request's SELF being SPENT at activated " +
        s"$activated, under nine headers, the input's extension its variables 1 to 8; the jars' fullReduction: " +
        s"${if (jarsOutcome == "value") jarsValue.take(80) else s"throws $jarsValue"}. The box " +
        s"${if (spends) "SPENDS" else "does NOT spend"}. ${E.label(E.Row("", name, treeVersion, activated, "", jarsOutcome, jarsValue, jarsCost, cls))}"
  }

  def spends(): Vector[Spend] = {
    val lines = new String(java.nio.file.Files.readAllBytes(E.RowsFile), "UTF-8").split("\n").toVector
    lines.drop(1).filter(_.trim.nonEmpty).map(_.split("\t", -1)).filter(_(0).startsWith("whole tree")).flatMap { c =>
      val as = c(3) match { case "2 and 3" => Seq(2, 3); case a => Seq(a.toInt) }
      as.map(a => Spend(c(1), c(4), c(2).toInt, a, c(5), c(6), c(7), c(9)))
    }
  }

  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  def validate(s: Spend): (String, String, TxEngine.Verdict) = {
    val c = ctxOf(s.activated)
    val written = VersionContext.withVersions(3, 3)(hx(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(
      ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(s.treeHex)))))
    require(written == s.treeHex, s"${s.name}: the tree is in the writer's form")
    require(E.SelfHex.startsWith("c0843d" + P2pk), "SELF layout")
    val raw = b("c0843d" + s.treeHex + E.SelfHex.drop(6 + P2pk.length))
    val bx = VersionContext.withVersions(3, 3)(org.ergoplatform.ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(raw)))
    require(java.util.Arrays.equals(bx.id, scorex.crypto.hash.Blake2b256(raw)), s"${s.name}: box id")
    val e = VersionContext.withVersions(3, 3)(sigma.interpreter.ContextExtension.serializer.parse(SigmaSerializer.startReader(b(E.ExtHex))))
    val w = { val wr = SigmaSerializer.startWriter(); VersionContext.withVersions(3, 3)(sigma.interpreter.ContextExtension.serializer.serialize(e, wr)); hx(wr.toBytes) }
    val txw = VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(org.ergoplatform.Input(bx.id,
      new sigma.interpreter.ProverResult(Array.emptyByteArray, e))), Seq(candidate(1000000L, 100))))))
    require(txw.indexOf(w) >= 0 && txw.indexOf(w) == txw.lastIndexOf(w), s"${s.name}: the extension in the tx")
    val txHex = txw.replace(w, E.ExtHex)
    (hx(raw), txHex, TxEngine.validateBytes(txHex, Seq(hx(raw)), Nil, c.headersHex, c.preHeader, AuthoredTxStorageRent.params))
  }

  def extract(): Map[Int, Json] = spends().groupBy(_.activated).map { case (a, ss) =>
    val c = ctxOf(a)
    val entries = ss.zipWithIndex.map { case (s, i) =>
      val (boxHex, txHex, v) = validate(s)
      require(v.valid == s.spends, s"${s.name} a$a: the jars ${s.jarsOutcome} ${s.jarsValue}, got valid=${v.valid} ${v.reason.getOrElse("")}")
      Json.obj(
        "name" -> Json.fromString(s"and-or-item-by-item-context-${s.slug}-a$a#$i"),
        "source" -> Json.fromString(Source), "description" -> Json.fromString(s.description),
        "tx_bytes_hex" -> Json.fromString(txHex), "input_boxes_hex" -> Json.arr(Json.fromString(boxHex)),
        "data_input_boxes_hex" -> Json.arr(), "headers_hex" -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader" -> c.preHeader, "parameters" -> AuthoredTxStorageRent.params,
        "context" -> Json.obj("height" -> Json.fromInt(H)),
        "version" -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(s.treeVersion)),
        "expected" -> Json.obj("valid" -> Json.fromBoolean(v.valid), "cost" -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
          "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
    }
    a -> Json.obj("schema" -> Json.fromString("santa-transaction/v1"), "op" -> Json.fromString(Op),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"), "entries" -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = extract().foreach { case (a, env) =>
    val path = vectorsRoot.resolve(Paths(a))
    java.nio.file.Files.createDirectories(path.getParent)
    java.nio.file.Files.write(path, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
