package santa

// AuthoredTxTreeVersion — transaction-tier vectors for a box whose tree is above the activated script version
// (sigma-rust's probe request, 2026-10-01). Blessed through TxEngine.validateBytes (ergo-core 6.0.6 validateStateful).
//
// 1. The spend check. Interpreter.verify runs checkSoftForkCondition before anything else (sigma-state
//    `Interpreter.scala:362`, `:298-331`). With an activated version of at most 3, a tree whose version is above it
//    throws an InterpreterException, "ErgoTree version N is higher than activated M" (`:325-328`): the spend is invalid.
// 2. How a node gets there. The parse check (the wire vectors tree_version_above_activated) applies only inside a
//    version context of activated version 2 or more. A node reads a box from the UTXO set outside any version context
//    (ergo `UtxoStateReader.scala:122-124`), so a box whose tree is above the activated version is read. Such a box
//    could be created in a block below version 4, whose transactions are parsed outside any version context too
//    (`BlockTransactions.scala:184-202`): the block-version-3 file creates two.
// 3. What does not run the check. A data input's script never runs. The storage-rent path is taken before
//    Interpreter.verify (ergo-wallet `ErgoInterpreter.scala:72-84`), so a box above the activated version is still
//    collected once its value no longer covers its fee.
//
// Contexts. Block version 4: the storage-rent vectors' synthetic context (AuthoredTxStorageRent: ten v4 headers below
// H = 1051200, the launch parameters). Block version 3: the same ten headers at version 3, re-linked, and the
// preHeader at version 3 on top. Every tree here is a SigmaProp(true) constant, so a spend needs no proof, and an impl
// that runs the script of a tree above the activated version accepts.

import io.circe.Json
import scorex.crypto.authds.ADKey
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.VersionContext
import sigma.ast.ShortConstant
import sigma.interpreter.{ContextExtension, ProverResult}
import org.ergoplatform.{DataInput, ErgoBox, ErgoLikeTransaction, Input}
import org.ergoplatform.modifiers.history.header.HeaderSerializer
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import santa.runner.TxEngine

import RentFixtures._

object AuthoredTxTreeVersion {
  val V6Path = "transaction/v6/authored/tree-version-above-activated.json"
  val V5Path = "transaction/v5/authored/tree-version-above-activated.json"
  private val V = 1000000000L // the value of every box and output but the rent box's
  private val H = AuthoredTxStorageRent.H
  private val PlainTree = "0008d3"

  // 10000 to start, 2000 for the input, 100 for the output; then 5 for a SigmaProp constant's script, or
  // StorageContractCost = 50 for a storage-rent spend; 100 more for a data input.
  private val ScriptCost    = 12105L
  private val DataInputCost = 12205L
  private val RentCost      = 12150L

  private def b(h: String): Array[Byte] = Base16.decode(h).get

  /** A block context: its version, the ten headers below H (NEWEST-first) and the preHeader. */
  private final case class Ctx(blockVersion: Int, headersHex: Seq[String], preHeader: Json)
  private val Block4 = Ctx(4, AuthoredTxStorageRent.headersHex, AuthoredTxStorageRent.preHeader)
  /** The same ten headers at version 3. A header's id covers its version, so they are re-linked oldest first. */
  private val Block3: Ctx = {
    val oldestFirst = AuthoredTxStorageRent.headersHex.map(h => HeaderSerializer.parseBytes(b(h))).reverse
    val relinked = oldestFirst.tail.foldLeft(Vector(oldestFirst.head.copy(version = 3.toByte))) { (acc, h) =>
      acc :+ h.copy(version = 3.toByte, parentId = acc.last.id)
    }
    val newestFirst = relinked.reverse
    Ctx(3, newestFirst.map(h => hex(HeaderSerializer.toBytes(h))), AuthoredTxStorageRent.preHeader.mapObject(
      _.add("version", Json.fromInt(3)).add("parentId", Json.fromString(newestFirst.head.id))))
  }

  /** A box's bytes: a plain sigmaProp(true) box of `value` created at `height`, its tree (00 08 d3) swapped for `tree`. */
  private def boxBytes(label: String, tree: String, height: Int, value: Long = V): Array[Byte] = {
    val plain = hex(VersionContext.withVersions(3, 3) { ErgoBox.sigmaSerializer.toBytes(box(label, value, height)) })
    val at = hex(vlqU32(value)).length
    require(plain.substring(at).startsWith(PlainTree), s"unexpected box layout $plain")
    b(plain.take(at) + tree + plain.drop(at + PlainTree.length))
  }
  /** A box's id hashes its bytes. Checked against the box as the JVM reads it from the UTXO set, outside any version
    * context: TxEngine pairs boxes with inputs by position, so a wrong id would go unnoticed. */
  private def idOf(raw: Array[Byte]): ADKey = {
    val id = ADKey @@ (Blake2b256.hash(raw): Array[Byte])
    require(java.util.Arrays.equals(ErgoBoxSerializer.parseBytes(raw).id, id), s"box id of ${hex(raw)}")
    id
  }
  private def inputOf(raw: Array[Byte], extension: ContextExtension): Input =
    Input(idOf(raw), ProverResult(Array.emptyByteArray, extension))

  /** One input spending `raw` with no proof, `data` as data inputs, and one plain output. */
  private def spendTx(raw: Array[Byte], data: Seq[Array[Byte]] = Nil, extension: ContextExtension = ext(),
                      outValue: Long = V, outHeight: Int = 1): Array[Byte] =
    txBytes(new ErgoLikeTransaction(IndexedSeq(inputOf(raw, extension)), data.map(d => DataInput(idOf(d))).toIndexedSeq,
      IndexedSeq(candidate(outValue, outHeight))))

  /** `bytes` with its one occurrence of `from` replaced by `to`. */
  private def replaceOnce(bytes: Array[Byte], from: Array[Byte], to: Array[Byte]): Array[Byte] = {
    val at = bytes.indexOfSlice(from)
    require(at >= 0 && bytes.indexOfSlice(from, at + 1) < 0, s"${hex(from)} must occur exactly once in ${hex(bytes)}")
    bytes.take(at) ++ to ++ bytes.drop(at + from.length)
  }

  /** Bless one entry: the JVM must find it valid at the cost `want.right`, or invalid for the reason `want.left`. */
  private def entry(c: Ctx, name: String, arm: String, description: String, txRaw: Array[Byte],
                    inputs: Seq[Array[Byte]], data: Seq[Array[Byte]], want: Either[String, Long]): Json = {
    val v = TxEngine.validateBytes(hex(txRaw), inputs.map(hex), data.map(hex), c.headersHex, c.preHeader,
      AuthoredTxStorageRent.params)
    want match {
      case Right(cost) => require(v.valid && v.cost.contains(cost),
        s"$name: want valid at cost $cost, got valid=${v.valid} cost=${v.cost} ${v.reason.getOrElse("")}")
      case Left(reason) => require(!v.valid && v.reason.exists(_.contains(reason)),
        s"$name: want invalid for '$reason', got valid=${v.valid} ${v.reason.getOrElse("")}")
    }
    Json.obj(
      "name"                 -> Json.fromString(name),
      "source"               -> Json.fromString(s"santa:authored-tx-tree-version:$arm"),
      "description"          -> Json.fromString(description),
      "tx_bytes_hex"         -> Json.fromString(hex(txRaw)),
      "input_boxes_hex"      -> Json.arr(inputs.map(x => Json.fromString(hex(x))): _*),
      "data_input_boxes_hex" -> Json.arr(data.map(x => Json.fromString(hex(x))): _*),
      "headers_hex"          -> Json.arr(c.headersHex.map(Json.fromString): _*),
      "preHeader"            -> c.preHeader,
      "parameters"           -> AuthoredTxStorageRent.params,
      "context"              -> Json.obj("height" -> Json.fromInt(H)),
      "version"              -> Json.obj("activated" -> Json.fromInt(c.blockVersion - 1), "ergoTree" -> Json.fromInt(0)),
      "expected"             -> Json.obj(
        "valid"  -> Json.fromBoolean(v.valid),
        "cost"   -> v.cost.map(Json.fromLong).getOrElse(Json.Null),
        "reason" -> v.reason.map(Json.fromString).getOrElse(Json.Null)))
  }

  private def above(tree: Int, activated: Int) = s"ErgoTree version $tree is higher than activated $activated"
  private val spendNote = "The input spends a box of value 1000000000 into one plain output of the same value, with no proof."
  private val utxoRead = "A node reads a box from the UTXO set outside any version context (ergo " +
    "UtxoStateReader.scala:122-124), where a tree's version is not compared with anything (sigma-state " +
    "VersionContext.scala:20)"

  /** A height-0 box with `tree` whose value is its own storage fee (which depends on the value's VLQ length). */
  private def rentBox(label: String, tree: String): Array[Byte] = {
    var raw = boxBytes(label, tree, 0, StorageFeeFactor.toLong * 44)
    var guard = 0
    while (ErgoBoxSerializer.parseBytes(raw).value != StorageFeeFactor.toLong * raw.length) {
      raw = boxBytes(label, tree, 0, StorageFeeFactor.toLong * raw.length); guard += 1
      require(guard < 10, s"$label: value/fee fixpoint did not converge")
    }
    raw
  }

  private def v6Entries: Seq[Json] = {
    val c = Block4
    val spends = Seq("0c" -> 4, "0d" -> 5, "0e" -> 6, "0f" -> 7).zipWithIndex.map { case ((h, n), i) =>
      val raw = boxBytes(s"santa:ttv:input-v$n", h + "0208d3", 1)
      entry(c, s"input-tree-v$n-reject#$i", "input",
        s"The spent box's tree is v$n ($h 02 08 d3: size-flagged, SigmaProp(true)), above the activated version, 3. " +
        s"$utxoRead, so the box is read. Interpreter.verify then runs checkSoftForkCondition before anything else " +
        "(Interpreter.scala:362, :325-328): the tree's version is above the activated one, an InterpreterException, " +
        "and the transaction is invalid. An impl without that check evaluates the tree to sigmaProp(true) and " +
        s"accepts. One that refuses the box when it reads it reaches no verdict. $spendNote",
        spendTx(raw), Seq(raw), Nil, Left(above(n, 3)))
    }
    val v3 = boxBytes("santa:ttv:input-v3", "0b0208d3", 1)
    val (plain, dataV7) = (boxBytes("santa:ttv:data:spent", PlainTree, 1), boxBytes("santa:ttv:data:v7", "0f0208d3", 1))
    val rent = rentBox("santa:ttv:rent-v4", "0c0208d3")
    val rentValue = StorageFeeFactor.toLong * rent.length
    spends ++ Seq(
      entry(c, "input-tree-v3-accept#4", "input",
        s"The control: the spent box's tree is v3 (0b 02 08 d3), the activated version. Valid. $spendNote",
        spendTx(v3), Seq(v3), Nil, Right(ScriptCost)),
      entry(c, "data-input-tree-v7-accept#5", "data-input",
        "A plain sigmaProp(true) box is spent, and the transaction has one data input: a box whose tree is v7 " +
        s"(0f 02 08 d3). $utxoRead, so the data input is read like any box, and its script never runs: valid, at 100 " +
        "more for the data input. An impl that refuses a box above the activated version when it reads it reaches " +
        "no verdict.", spendTx(plain, Seq(dataV7)), Seq(plain), Seq(dataV7), Right(DataInputCost)),
      entry(c, "rent-tree-v4-dust-accept#6", "rent",
        "Storage rent. The spent box's tree is v4 (0c 02 08 d3). It was created at height 0, so at H = 1051200 it is " +
        s"exactly StoragePeriod old, and its value, $rentValue, equals its storage fee ($StorageFeeFactor × " +
        s"${rent.length} bytes). The input has no proof and carries var 127 = Short(0). ErgoInterpreter.verify takes " +
        "the storage-rent path before Interpreter.verify (ergo-wallet ErgoInterpreter.scala:72-84), so the tree's " +
        "version is never compared: the fee is not covered (value - fee <= 0), the box is spendable whatever the " +
        "output, and the input costs StorageContractCost = 50. Valid. An impl that compares the tree's version before " +
        "it takes the rent path rejects: the over-reject.",
        spendTx(rent, extension = ext(StorageIndexVarId -> ShortConstant(0)), outValue = rentValue, outHeight = H),
        Seq(rent), Nil, Right(RentCost)),
      entry(c, "rent-tree-v4-no-index-reject#7", "rent",
        "The twin: the same box and output, without var 127. The rent path is not taken, the script path runs " +
        "checkSoftForkCondition, and the transaction is invalid.",
        spendTx(rent, outValue = rentValue, outHeight = H), Seq(rent), Nil, Left(above(4, 3))))
  }

  private def v5Entries: Seq[Json] = {
    val c = Block3
    val (v3, v2) = (boxBytes("santa:ttv:v5:input-v3", "0b0208d3", 1), boxBytes("santa:ttv:v5:input-v2", "0a0208d3", 1))
    val block3 = "Block version 3, so the activated version is 2."
    def created(i: Int, n: Int, tree: String, description: String): Json = {
      val plain = boxBytes(s"santa:ttv:v5:output-v$n", PlainTree, 1)
      val out = (t: String) => vlqU32(V) ++ b(t + "01" + "00" + "00")
      entry(c, s"output-tree-v$n-accept#$i", "output", description,
        replaceOnce(spendTx(plain), out(PlainTree), out(tree)), Seq(plain), Nil, Right(ScriptCost))
    }
    Seq(
      entry(c, "input-tree-v3-reject#0", "input",
        s"$block3 The spent box's tree is v3 (0b 02 08 d3: size-flagged, SigmaProp(true)). $utxoRead, so the box is " +
        "read, and checkSoftForkCondition throws 'ErgoTree version 3 is higher than activated 2' (Interpreter.scala:" +
        "325-328): invalid. A v3 tree could not be spent before block version 4. An impl without the check evaluates " +
        s"the tree and accepts. $spendNote", spendTx(v3), Seq(v3), Nil, Left(above(3, 2))),
      entry(c, "input-tree-v2-accept#1", "input",
        s"The control: $block3 The spent box's tree is v2 (0a 02 08 d3), the activated version. Valid. $spendNote",
        spendTx(v2), Seq(v2), Nil, Right(ScriptCost)),
      created(2, 3, "0b0208d3",
        s"$block3 A plain sigmaProp(true) box is spent into one output whose tree is v3 (0b 02 08 d3), above the " +
        "activated version. Below block version 4 a node parses a block's transactions outside any version context " +
        "(ergo BlockTransactions.scala:184-202), so the output's tree is not compared with the activated version, " +
        "and nothing in the transaction's validation looks at it: valid. So a box whose tree was above the activated " +
        "version could be created, though not spent (#0). An impl that parses the transaction under (2, 2) reaches " +
        "no verdict."),
      created(3, 4, "0c0208d3",
        s"$block3 The same with an output whose tree is v4 (0c 02 08 d3): valid. A box like this one does not spend " +
        "by its script while the activated version is 3 (the v6 file's #0), and no block of version 4 can create one " +
        "(the wire vectors tree_version_above_activated)."))
  }

  private def envelope(es: Seq[Json]): Json = Json.obj(
    "schema"     -> Json.fromString("santa-transaction/v1"),
    "op"         -> Json.fromString("tx:authored:tree-version-above-activated"),
    "blessed_by" -> Json.fromString(AuthoredTxStorageRent.BlessedBy),
    "entries"    -> Json.arr(es: _*))

  def blessAll(): Seq[(String, Json)] = Seq(V6Path -> envelope(v6Entries), V5Path -> envelope(v5Entries))

  def writeVectors(blessed: Seq[(String, Json)], vectorsRoot: java.nio.file.Path): Unit =
    blessed.foreach { case (rel, env) =>
      val f = vectorsRoot.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, env.spaces2.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    }
}
