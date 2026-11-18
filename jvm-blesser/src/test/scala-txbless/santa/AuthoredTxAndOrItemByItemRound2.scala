package santa

// Transaction-tier companion of AuthoredEvalAndOrItemByItemRound2: the addendum's whole trees, each guarding a box SPENT
// through TxEngine.validateBytes (ergo-core 6.0.7 validateStateful) under nine headers, at the activated version the
// row names (3: block 4; 2: block 3). Two files per activated version:
//  * `segregated-tuple` ("whole tree, no context read", 219 rows): the tree in a PLAIN box (1,000,000 nanoErg, created
//    at 100, no token, no register), the input's extension empty: nothing in the context but the tree. The segregated
//    tuple of three (refused by the reduction), its twins inside a collection or pair constant, a segregated ladder, the
//    unsegregated twins, and the 4-item `ph` trees the JVM's parse refuses (the box holds them unparsed);
//  * `and-or-item-by-item-producers` ("whole tree, the addendum's context", 425 rows): the tree spliced into the
//    addendum's SELF (one token; R4..R8), the input's extension its raw bytes (variables 1, 2, 10..13): the producers of
//    the collection as whole trees, `executeFromVar` / `DeserializeRegister` (the substitution before the reduction,
//    which only a whole tree has), and the segregated tuple again in this SELF.
// The jars' fullReduction (the rows' answer) is TrueProp exactly where the box spends; measured first
// (santa.spike.AndOrItemByItemRound2Probe): 219 / 219 and 425 / 425. LEFT OUT: a row-activation that repeats an earlier
// one's tree in the same file (the same transaction), and a tree no box can hold: the 4-item `ph` trees (a triple's
// type code over four item types) whose box the parser refuses: at tree v0 both (no size bit to step over a refused
// tree), at v1..v3 OR's; no output could have created such a box. AND's tree-v1..v3 twins are in, held unparsed.
//
// THE BOX HOLDS THE TREE AS THE WRITER WRITES IT (a node re-writes the tree it parses): the `if` rows' `TrueLeaf` (`7f`)
// is written `0101`; the description says so. extract() FAILS LOUD unless each verdict is the jars'.

import io.circe.Json
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.serialization.{ErgoTreeSerializer, SigmaSerializer}
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxAndOrItemByItemRound2 {
  val Source = "santa:authored-and-or-item-by-item"
  val Ops: Seq[String] = Seq("segregated-tuple", "and-or-item-by-item-producers")
  def path(op: String, activated: Int): String = s"transaction/${if (activated == 3) "v6" else "v5"}/authored/$op.json"
  private val H = AuthoredTxStorageRent.H
  val E: AuthoredEvalAndOrItemByItemRound2.type = AuthoredEvalAndOrItemByItemRound2
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hx(bs: Array[Byte]): String = Base16.encode(bs).toLowerCase
  val PlainSelf: String = "c0843d" + E.P2pk + "640000" + "11" * 32 + "00"

  def opOf(r: E.Row): String = if (r.part == "whole tree, no context read") "segregated-tuple" else "and-or-item-by-item-producers"
  def selfOf(r: E.Row): String = if (opOf(r) == "segregated-tuple") PlainSelf else E.Self2
  def extOf(r: E.Row): Option[String] = if (opOf(r) == "segregated-tuple") None else Some(E.Ext2Hex)
  def spends(r: E.Row): Boolean = r.jarsOutcome == "value" && r.jarsValue == "TrueProp"

  /** What the row varies (the eval generator's words where it has them). */
  def label(r: E.Row): String = (r.part, r.f(1)) match {
    case ("whole tree, no context read", _) => E.label(r)
    case (_, "segregated") => E.label(r.copy(part = "whole tree, no context read", name = r.name.replace("|segregated|", "|ph|")))
    case (_, "inline") => E.label(r.copy(part = "whole tree, no context read"))
    case (_, "deserCtx") => "the collection from `executeFromVar` (`DeserializeContext` of a variable's bytes, " +
      "substituted before the reduction): the tuple of three reaches the node, read item by item; declared " +
      "`Coll[Boolean]` over a tuple's bytes, the substitution refuses."
    case (_, "deserReg") => "the collection from `DeserializeRegister` (SELF's R6 / R7 bytes, substituted before the " +
      "reduction): the tuple of three reaches the node, read item by item."
    case (_, c) => s"the collection from the producer `$c` (in the addendum's SELF and extension): what reaches the node " +
      "is read item by item, a tuple's items as a collection's."
  }
  def description(r: E.Row): String =
    s"[${r.name}] sigma-rust's whole tree (tree v${r.treeVersion}) guards " +
      (if (opOf(r) == "segregated-tuple") "a plain box (no token, no register; the input's extension empty)"
       else "the addendum's SELF (one token, R4..R8; the input's extension its variables 1, 2, 10..13)") +
      s", SPENT at activated ${r.activated} under nine headers; the jars' fullReduction: " +
      s"${r.jarsOutcome match { case "value" => r.jarsValue; case "refused at parse" => "refused at parse"; case _ => s"throws ${r.jarsValue}" }}. " +
      s"The box ${if (spends(r)) "SPENDS" else "does NOT spend"}. ${label(r)}" +
      (if (boxTree(r) != r.treeHex) s" The box holds the tree as the writer writes it (`${boxTree(r)}`; the row's " +
        s"bytes `${r.treeHex}`): a node re-writes the tree it parses." else "")

  /** The tree as a box a node created holds it: the writer's form of a tree the JVM parses, else the row's bytes. */
  def boxTree(r: E.Row): String =
    if (E.strictRoot(r)) VersionContext.withVersions(3, 3)(hx(ErgoTreeSerializer.DefaultSerializer.serializeErgoTree(
      ErgoTreeSerializer.DefaultSerializer.deserializeErgoTree(b(r.treeHex)))))
    else r.treeHex
  def boxHex(r: E.Row): String = "c0843d" + boxTree(r) + selfOf(r).drop(6 + E.P2pk.length)
  /** A box can hold the tree: the box parser reads past it. */
  def boxParse(r: E.Row): scala.util.Try[org.ergoplatform.ErgoBox] = scala.util.Try(VersionContext.withVersions(3, 3)(
    org.ergoplatform.ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(b(boxHex(r))))))
  def boxParses(r: E.Row): Boolean = boxParse(r).isSuccess
  /** Per (op, activated): the rows it holds, first occurrence of a tree; and the repeats left out. */
  def plan(): Map[(String, Int), (Vector[E.Row], Vector[E.Row])] =
    E.allRows().filter(r => r.whole && boxParses(r)).groupBy(r => (opOf(r), r.activated)).map { case (k, rs) =>
      val seen = collection.mutable.Set[String]()
      k -> rs.partition(r => seen.add(boxTree(r)))
    }

  private def ctxOf(activated: Int): AuthoredTxTreeVersion.Ctx =
    if (activated == 3) AuthoredTxTreeVersion.Block4 else AuthoredTxTreeVersion.Block3

  def validate(r: E.Row): (String, String, TxEngine.Verdict) = {
    val c = ctxOf(r.activated)
    require(selfOf(r).startsWith("c0843d" + E.P2pk), "SELF layout")
    val raw = b(boxHex(r))
    val bx = VersionContext.withVersions(3, 3)(org.ergoplatform.ErgoBox.sigmaSerializer.parse(SigmaSerializer.startReader(raw)))
    require(java.util.Arrays.equals(bx.id, scorex.crypto.hash.Blake2b256(raw)), s"${r.name}: box id")
    val e = extOf(r).map(h => VersionContext.withVersions(3, 3)(sigma.interpreter.ContextExtension.serializer.parse(SigmaSerializer.startReader(b(h)))))
      .getOrElse(sigma.interpreter.ContextExtension.empty)
    val txw = VersionContext.withVersions(3, 3)(hex(txBytes(tx(Seq(org.ergoplatform.Input(bx.id,
      new sigma.interpreter.ProverResult(Array.emptyByteArray, e))), Seq(candidate(1000000L, 100))))))
    val txHex = extOf(r) match {
      case Some(h) =>
        val w = { val wr = SigmaSerializer.startWriter(); VersionContext.withVersions(3, 3)(sigma.interpreter.ContextExtension.serializer.serialize(e, wr)); hx(wr.toBytes) }
        require(txw.indexOf(w) >= 0 && txw.indexOf(w) == txw.lastIndexOf(w), s"${r.name}: the extension in the tx")
        txw.replace(w, h)
      case None => txw
    }
    (hx(raw), txHex, TxEngine.validateBytes(txHex, Seq(hx(raw)), Nil, c.headersHex, c.preHeader, AuthoredTxStorageRent.params))
  }

  def extract(): Map[(String, Int), Json] = plan().map { case ((op, a), (rs, _)) =>
    val c = ctxOf(a)
    val entries = rs.zipWithIndex.map { case (r, i) =>
      val (boxHex, txHex, v) = validate(r)
      require(v.valid == spends(r), s"${r.name} a$a: the jars ${r.jarsOutcome} ${r.jarsValue}, got valid=${v.valid} ${v.reason.getOrElse("")}")
      Json.obj(
        "name" -> Json.fromString(s"$op-${r.slug}-a$a#$i"),
        "source" -> Json.fromString(Source), "description" -> Json.fromString(description(r)),
        "tx_bytes_hex" -> Json.fromString(txHex), "input_boxes_hex" -> Json.arr(Json.fromString(boxHex)),
        "data_input_boxes_hex" -> Json.arr(), "headers_hex" -> Json.arr(c.headersHex.map(Json.fromString): _*),
        "preHeader" -> c.preHeader, "parameters" -> AuthoredTxStorageRent.params,
        "context" -> Json.obj("height" -> Json.fromInt(H)),
        "version" -> Json.obj("activated" -> Json.fromInt(a), "ergoTree" -> Json.fromInt(r.treeVersion)),
        "expected" -> Json.obj("valid" -> Json.fromBoolean(v.valid), "cost" -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
          "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
    }
    (op, a) -> Json.obj("schema" -> Json.fromString("santa-transaction/v1"), "op" -> Json.fromString(op),
      "blessed_by" -> Json.fromString("jvm:ergo-core-6.0.7-validateStateful"), "entries" -> Json.arr(entries: _*))
  }

  def writeVectors(vectorsRoot: java.nio.file.Path): Unit = extract().foreach { case ((op, a), env) =>
    val p = vectorsRoot.resolve(path(op, a))
    java.nio.file.Files.createDirectories(p.getParent)
    java.nio.file.Files.write(p, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }
}
