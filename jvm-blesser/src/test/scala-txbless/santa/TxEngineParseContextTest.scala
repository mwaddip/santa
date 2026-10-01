package santa

import scala.util.{Failure, Success, Try}

import io.circe.Json
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import santa.runner.TxEngine

/** TxEngine parses as a node does when it validates a block (ergo v6.0.6):
  *  - a block's transactions under (blockVersion - 1, blockVersion - 1) from block version 4, and outside any version
  *    context before that (`BlockTransactions.scala:184-202`);
  *  - a box read from the UTXO set outside any version context (`UtxoStateReader.scala:122-124`).
  * A tree's version is compared with the activated version only inside a context of activated version 2 or more
  * (sigma-state `VersionContext.scala:20`, `ErgoTreeSerializer.scala:150-154`), so a tree above the activated version
  * tells the contexts apart. Every byte below is written out by hand; cwd = jvm-blesser/. */
class TxEngineParseContextTest extends munit.FunSuite {
  private def b(h: String): Array[Byte] = Base16.decode(h).get
  private def hex(a: Array[Byte]): String = Base16.encode(a)

  private val PlainTree = "0008d3"   // v0, SigmaProp(true)
  private val V4Tree    = "0c0208d3" // v4, size-flagged, SigmaProp(true)
  private val V7Tree    = "0f0208d3" // v7, size-flagged, SigmaProp(true)

  /** A box: value 1000000 (c0 84 3d), `tree`, creation height 1, no tokens, no registers, tx id `fill` × 32, index 0. */
  private def box(tree: String, fill: String): String = "c0843d" + tree + "01" + "00" + "00" + fill * 32 + "00"
  private def idOf(boxHex: String): String = hex(Blake2b256.hash(b(boxHex)))
  /** A transaction: one input (no proof, no extension), `dataBoxes` as data inputs, no tokens, and one output of value
    * 1000000 with `outputTree`, creation height 1. */
  private def tx(inputBox: String, dataBoxes: Seq[String], outputTree: String): String =
    "01" + idOf(inputBox) + "00" + "00" +
      f"${dataBoxes.size}%02x" + dataBoxes.map(idOf).mkString +
      "00" + "01" + "c0843d" + outputTree + "01" + "00" + "00"

  private def preHeader(blockVersion: Int): Json =
    AuthoredTxStorageRent.preHeader.mapObject(_.add("version", Json.fromInt(blockVersion)))
  private def validate(txHex: String, inputs: Seq[String], data: Seq[String], blockVersion: Int): Try[TxEngine.Verdict] =
    Try(TxEngine.validateBytes(txHex, inputs, data, AuthoredTxStorageRent.headersHex, preHeader(blockVersion),
      AuthoredTxStorageRent.params))
  private def causes(t: Throwable): String =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).map(c => s"${c.getClass.getName}: ${c.getMessage}").mkString(" <- ")

  test("an input box whose tree is v7 is read as from the UTXO set, and its spend is invalid for the tree's version") {
    val spent = box(V7Tree, "11")
    validate(tx(spent, Nil, PlainTree), Seq(spent), Nil, blockVersion = 4) match {
      case Failure(t) => fail(s"the engine must reach a verdict, but the parse threw: ${causes(t)}")
      case Success(v) =>
        assert(!v.valid, "a tree above the activated version does not spend")
        assert(v.reason.exists(_.contains("ErgoTree version 7 is higher than activated 3")), s"reason: ${v.reason}")
    }
  }

  test("a data-input box whose tree is v7 is read as from the UTXO set, and the spend is valid") {
    val (spent, data) = (box(PlainTree, "22"), box(V7Tree, "33"))
    validate(tx(spent, Seq(data), PlainTree), Seq(spent), Seq(data), blockVersion = 4) match {
      case Failure(t) => fail(s"the engine must reach a verdict, but the parse threw: ${causes(t)}")
      case Success(v) =>
        assert(v.valid, s"a data input's script never runs: ${v.reason}")
        // 10000 to start, 2000 for the input, 100 for the data input, 100 for the output, 5 for the SigmaProp constant
        assertEquals(v.cost, Some(12205L))
    }
  }

  test("a block-version-4 transaction is parsed under (3, 3): an output whose tree is v4 does not parse") {
    val spent = box(PlainTree, "44")
    validate(tx(spent, Nil, V4Tree), Seq(spent), Nil, blockVersion = 4) match {
      case Success(v) => fail(s"the transaction must not parse, but the engine gave a verdict: $v")
      case Failure(t) =>
        assert(causes(t).contains("Tree version (4) is above activated script version (3)"), causes(t))
    }
  }

  test("a block-version-3 transaction is parsed outside any version context: an output whose tree is v4 is valid") {
    val spent = box(PlainTree, "55")
    validate(tx(spent, Nil, V4Tree), Seq(spent), Nil, blockVersion = 3) match {
      case Failure(t) => fail(s"the engine must reach a verdict, but the parse threw: ${causes(t)}")
      case Success(v) =>
        assert(v.valid, s"a block below version 4 takes any tree version in an output: ${v.reason}")
        // 10000 to start, 2000 for the input, 100 for the output, 5 for the SigmaProp constant
        assertEquals(v.cost, Some(12105L))
    }
  }
}
