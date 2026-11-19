package santa

// Transaction-tier companion of AuthoredEvalProducerCensus (sigma-rust's santa-probe-specialised-patch-array-4,
// 2026-11-17; THE RULE is there): whole trees over the producers, as spends.
//
//  - PAIRS (generic-producers-pairs): over R2 read as a register (SELF's, an output's, through `getReg`) and SELF's
//    `tokens` (the control both sides get right): `(<it> ++ (if (false) <an empty collection of its own type> else
//    Coll[(Byte, Boolean)]())).size == 1`, `{ val v = ByIndex(<it>, 0, default = 1); true }`,
//    `<it>.patch(0, Coll[Int]().indices, 0).size == 1` (tree v0 and v3), `<it>.startsWith(Coll[Int]().indices)` (v3).
//    Every one spends on a node.
//  - CENSUS (generic-producers-census): over every census receiver, `<it>.patch(0, Coll[Int](), 0).size == <its size>`
//    (v0 and v3) and `<it>.startsWith(Coll[Int]())` (v3); `executeFromVar[Boolean](n)` over a variable holding an
//    expression or a constant; and over the context's own fields (a header's, the pre-header's, the last block's UTXO
//    root, `minerPubKey`, SELF's R1 and `propositionBytes`): those two and `{ val v = ByIndex(<it>, 0, default =
//    true); true }`.
//
// sigma-rust's trees (src/test/resources/sigma-rust-patch-array-class/spends-producer-census.tsv, with the verdict its
// SpendProbe printed through this TxEngine). Each spends sigma-rust's transaction (AuthoredEvalProducerCensus): its
// SELF with the tree under test spliced in (registers and token as sigma-rust wrote them), its second input, data input
// and two outputs, both extensions as their RAW bytes spliced over the writer's re-encoding; at activated 3 (block 4)
// and, for a tree-v0 tree, at activated 2 (block 3), under nine headers. extract() FAILS LOUD unless each verdict is
// the one sigma-rust measured (where it measured one) and the class rule's.

import io.circe.Json
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16

import org.ergoplatform.{DataInput, ErgoBox, ErgoBoxCandidate, ErgoLikeTransaction, Input}
import sigma.VersionContext
import sigma.interpreter.{ContextExtension, ProverResult}
import sigma.serialization.SigmaSerializer
import santa.runner.TxEngine

object AuthoredTxProducerCensus {
  val Source = "santa:authored-generic-producers"
  val SpendsFile: java.nio.file.Path =
    java.nio.file.Paths.get("src", "test", "resources", "sigma-rust-patch-array-class", "spends-producer-census.tsv")
  val Ops: Seq[String] = Seq("generic-producers-pairs", "generic-producers-census")
  def path(op: String, activated: Int): String = s"transaction/${if (activated == 3) "v6" else "v5"}/authored/$op.json"
  private val E = AuthoredEvalProducerCensus
  private val H = AuthoredTxStorageRent.H

  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase

  final case class Spend(set: String, name: String, treeHex: String, activated: Int, measured: String) {
    private val f = name.split('|')
    def method: String = f(0)
    def receiver: String = f(1)
    def treeVersion: Int = if (treeHex.startsWith("00")) 0 else Integer.parseInt(treeHex.take(2), 16) & 7
    def op: String = if (set == "pairs") "generic-producers-pairs" else "generic-producers-census"
    def slug: String = name.replace('|', '-').replaceAll("[^A-Za-z0-9.+_=-]", "_")
    /** sigma-rust's verdict, where it measured one (`VALID @cost` / `INVALID @null`). */
    def measuredValid: Option[Boolean] = measured match {
      case "-" => None
      case m   => Some(m.startsWith("VALID"))
    }
    def measuredCost: Option[Long] = measured.split(" @").lift(1).filter(_ != "null").map(_.toLong)
  }

  def spends(): Vector[Spend] =
    new String(java.nio.file.Files.readAllBytes(SpendsFile), "UTF-8").split("\n").toVector.drop(1).filter(_.trim.nonEmpty)
      .map(_.split("\t", -1)).map(c => Spend(c(0), c(1), c(2), c(3).toInt, c(4)))

  // ── what each tree reads ─────────────────────────────────────────────────────────────────────────
  /** The context's own fields (the `context` set): the expression, and whether a node holds it GENERIC. */
  val ContextField: Map[String, (String, Boolean)] = Map(
    "header.id" -> ("CONTEXT.headers(0).id", false), "header.parentId" -> ("CONTEXT.headers(0).parentId", false),
    "header.ADProofsRoot" -> ("CONTEXT.headers(0).ADProofsRoot", false),
    "header.stateRoot.digest" -> ("CONTEXT.headers(0).stateRoot.digest", false),
    "header.transactionsRoot" -> ("CONTEXT.headers(0).transactionsRoot", false),
    "header.extensionRoot" -> ("CONTEXT.headers(0).extensionRoot", false),
    "header.minerPk.getEncoded" -> ("CONTEXT.headers(0).minerPk.getEncoded", false),
    "header.powOnetimePk.getEncoded" -> ("CONTEXT.headers(0).powOnetimePk.getEncoded", false),
    "header.powNonce" -> ("CONTEXT.headers(0).powNonce", false), "header.votes" -> ("CONTEXT.headers(0).votes", false),
    "lastBlockUtxoRoot.digest" -> ("CONTEXT.LastBlockUtxoRootHash.digest", false),
    "minerPubKey" -> ("CONTEXT.minerPubKey", false),
    "preHeader.parentId" -> ("CONTEXT.preHeader.parentId", true), "preHeader.votes" -> ("CONTEXT.preHeader.votes", true),
    "preHeader.minerPk.getEncoded" -> ("CONTEXT.preHeader.minerPk.getEncoded", false),
    "SELF.R1" -> ("SELF.R1[Coll[Byte]].get", true), "SELF.propositionBytes.ctl" -> ("SELF.propositionBytes", false))
  /** The pairs set's receivers. */
  private val PairReceiver: Map[String, String] = Map(
    "SELF.R2" -> "SELF.R2[Coll[(Coll[Byte], Long)]].get", "SELF.tokens.ctl" -> "SELF.tokens",
    "OUT0.R2" -> "OUTPUTS(0).R2[Coll[(Coll[Byte], Long)]].get",
    "SELF.getReg2" -> "SELF.getReg[Coll[(Coll[Byte], Long)]](2).get")
  /** The variables `executeFromVar` reads, and what each holds. */
  private val DeserializedVar: Map[String, (Int, String)] = Map(
    "var4.ccones" -> (4, "variable 4, the collection EXPRESSION `83020202010201` (the bytes of `true`)"),
    "var7.const.ctl" -> (7, "variable 7, the Coll[Byte] CONSTANT `0e020102`"),
    "var1.cc" -> (1, "variable 1, the collection EXPRESSION `83020202010202`"))

  /** The tree as a script, and whether a node spends it by the class rule. */
  def tree(s: Spend): (String, Boolean) = s.set match {
    case "pairs" =>
      val x = PairReceiver(s.receiver)
      (s.method match {
        case "append.size==1" =>
          s"`sigmaProp(($x ++ (if (false) <an empty collection of its own type> else Coll[(Byte, Boolean)]())).size == 1)`"
        case "byIndex.default.valdef" => s"`sigmaProp({ val v = ByIndex($x, 0, default = 1); true })`"
        case "patch.indices.size==1"  => s"`sigmaProp($x.patch(0, Coll[Int]().indices, 0).size == 1)`"
        case "startsWith.indices"     => s"`sigmaProp($x.startsWith(Coll[Int]().indices))`"
      }, true)
    case "census" if s.method == "deserializeContext" =>
      val (n, _) = DeserializedVar(s.receiver)
      (s"`sigmaProp(executeFromVar[Boolean]($n))`", true)
    case "census" =>
      val x = E.Receiver(s.receiver)._1
      val size = if (s.receiver.endsWith(".empty")) "0" else s"$x.size"
      (s.method match {
        case "patch.size" => s"`sigmaProp($x.patch(0, Coll[Int](), 0).size == $size)`"
        case "startsWith" => s"`sigmaProp($x.startsWith(Coll[Int]()))`"
      }, !s.receiver.contains("tuple") && !s.receiver.endsWith(".ctl"))
    case "context" =>
      val (x, generic) = ContextField(s.receiver)
      (s.method match {
        case "byIndex.valdef" => s"`sigmaProp({ val v = ByIndex($x, 0, default = true); true })`"
        case "patch.size"     => s"`sigmaProp($x.patch(0, Coll[Int](), 0).size == $x.size)`"
        case "startsWith"     => s"`sigmaProp($x.startsWith(Coll[Int]()))`"
      }, generic)
  }

  def why(s: Spend, valid: Boolean): String = s.set match {
    case "pairs" if s.receiver == "SELF.tokens.ctl" =>
      "THE CONTROL: `Box.tokens`, a GENERIC collection of pairs on a node, which every port reads right: the box SPENDS."
    case "pairs" =>
      "R2 read as a register is built as `Box.tokens` is, a GENERIC collection of pairs on a node, so the call answers and " +
        "the box SPENDS. A port that reads it as the data constant's pair of arrays refuses the call, and the box with it."
    case "census" if s.method == "deserializeContext" =>
      s"${DeserializedVar(s.receiver)._2}: its bytes are read as a serialized Boolean whatever the collection's " +
        "class, and the box SPENDS."
    case "census" =>
      val holder = E.Receiver(s.receiver)._2
      if (s.receiver.contains("tuple"))
        s"THE ITEM SELECTION THROWS: the receiver is $holder, a Coll[Any] on the JVM. The read hands it back (the " +
          "readers check only the stored type), and `SelectField` (`._1`), which matches a Tuple2 only, refuses it: " +
          "the box does NOT spend."
      else if (valid)
        s"NODE'S CLASS: the receiver is $holder, a GENERIC collection on a node: the call answers and the box SPENDS. " +
          "A port that holds it specialised refuses the call, and the box with it."
      else
        s"CONTROL OF THE CLASS: the receiver is $holder, a SPECIALISED collection on a node, which reads the argument " +
          "through its own array class: the call throws and the box does NOT spend."
    case "context" =>
      if (valid)
        "NODE'S CLASS: a GENERIC collection on a node (the pre-header's field ergo-core builds with `toColl`, or R1 read " +
          "as a register): the box SPENDS. A port that holds it specialised refuses."
      else
        "CONTROL OF THE CLASS: a SPECIALISED collection on a node (read from bytes, or a property): the call throws and " +
          "the box does NOT spend."
  }

  private def parseBox(h: String): ErgoBox = ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(b(h)))
  private def parseExt(h: String): ContextExtension = ContextExtension.serializer.parse(SigmaSerializer.startReader(b(h)))
  private def written(e: ContextExtension): String = {
    val w = SigmaSerializer.startWriter(); ContextExtension.serializer.serialize(e, w); hx(w.toBytes)
  }

  /** SELF's raw bytes with the tree spliced in, the transaction's bytes, and the JVM's verdict. */
  def validate(s: Spend): (String, String, TxEngine.Verdict) = {
    require(E.SelfHex.startsWith("c0843d" + E.P2pkTree), "SELF layout")
    val raw = "c0843d" + s.treeHex + E.SelfHex.drop(6 + E.P2pkTree.length)
    val txHex = VersionContext.withVersions(3, 3) {
      val self = parseBox(raw)
      require(java.util.Arrays.equals(self.id, Blake2b256(b(raw))), s"${s.name}: the box id hashes its bytes")
      val (e0, e1) = (parseExt(E.Ext0Hex), parseExt(E.Ext1Hex))
      val tx = new ErgoLikeTransaction(
        IndexedSeq(Input(self.id, new ProverResult(Array.emptyByteArray, e0)),
                   Input(parseBox(E.In1Hex).id, new ProverResult(Array.emptyByteArray, e1))),
        IndexedSeq(DataInput(parseBox(E.DataHex).id)),
        E.OutCandidates.map(o => ErgoBoxCandidate.serializer.parse(SigmaSerializer.startReader(b(o)))).toIndexedSeq)
      Seq(e0 -> E.Ext0Hex, e1 -> E.Ext1Hex).foldLeft(hx(ErgoLikeTransaction.serializer.toBytes(tx))) {
        case (t, (e, rawExt)) =>
          val w = written(e)
          if (w == rawExt) t
          else {
            require(t.indexOf(w) >= 0 && t.indexOf(w) == t.lastIndexOf(w), s"${s.name}: the extension, once")
            t.replace(w, rawExt)
          }
      }
    }
    val c = if (s.activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3
    (raw, txHex, TxEngine.validateBytes(txHex, Seq(raw, E.In1Hex), Seq(E.DataHex), c.headersHex, c.preHeader,
      AuthoredTxStorageRent.params))
  }

  def extract(): Map[(String, Int), Json] = {
    val ss = spends()
    val validated = ss.map(s => s -> validate(s))
    val v3 = validated.collect { case (s, (_, _, v)) if s.activated == 3 => (s.set, s.name) -> v.valid }.toMap
    validated.groupBy { case (s, _) => (s.op, s.activated) }.map { case ((op, a), group) =>
      val c = if (a == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3
      val entries = group.zipWithIndex.map { case ((s, (raw, txHex, v)), i) =>
        val (script, generic) = tree(s)
        require(v.valid == generic, s"${s.name} a$a: the class rule says ${generic}, got ${v.valid} ${v.reason.getOrElse("")}")
        s.measuredValid.foreach(m => require(v.valid == m && v.cost == s.measuredCost,
          s"${s.name} a$a: sigma-rust measured ${s.measured}, got ${v.valid} @${v.cost}"))
        if (s.measuredValid.isEmpty) require(v.valid == v3((s.set, s.name)), s"${s.name}: activated 2 is not 3's verdict")
        Json.obj(
          "name"                 -> Json.fromString(s"$op-spend-${s.set}-${s.slug}-a$a#$i"),
          "source"               -> Json.fromString(Source),
          "description"          -> Json.fromString(s"[${s.set}|${s.name}] $script (tree v${s.treeVersion}) guards " +
            s"the box being SPENT at activated $a, under nine headers, in sigma-rust's transaction (SELF with R4..R9 " +
            "and a token as it wrote them, a second input, a data input, two outputs; both extensions as raw bytes). " +
            why(s, v.valid)),
          "tx_bytes_hex"         -> Json.fromString(txHex),
          "input_boxes_hex"      -> Json.arr(Json.fromString(raw), Json.fromString(E.In1Hex)),
          "data_input_boxes_hex" -> Json.arr(Json.fromString(E.DataHex)),
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
      (op, a) -> Json.obj(
        "schema"     -> Json.fromString("santa-transaction/v1"),
        "op"         -> Json.fromString(op),
        "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"),
        "entries"    -> Json.arr(entries: _*))
    }
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = extract().foreach { case ((op, a), env) =>
    val p = vectorsRoot.resolve(path(op, a))
    java.nio.file.Files.createDirectories(p.getParent)
    java.nio.file.Files.write(p, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
