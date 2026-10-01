package santa

import scala.util.{Success, Try}

/** A `BlockTransactions` entry is parsed as a node parses a block section: the section's own block version picks the
  * context (ergo v6.0.6 `BlockTransactions.scala:184-202`), and the entry's version pair does not reach it. Below
  * block version 4 the JVM parses each transaction outside any version context, where a tree's version is not
  * compared with the activated one (sigma-state `VersionContext.scala:20`). The section is written out by hand. */
class WireBlockTransactionsContextTest extends munit.FunSuite {
  /** A block section of version 3 holding one transaction, whose only output's tree is `tree`. */
  private def sectionV3(tree: String): String =
    "aa" * 32 +                     // header id
      "83ade204" +                  // VLQ(10,000,000 + block version 3)
      "01" +                        // one transaction
      "01" + "bb" * 32 + "00" + "00" + // one input: box id, no proof, no extension
      "00" + "00" +                 // no data inputs, no tokens
      "01" + "c0843d" + tree + "01" + "00" + "00" // one output: value 1000000, the tree, height 1, no tokens, no registers

  test("a block-version-3 section is parsed outside the entry's version pair: a v4 tree round-trips") {
    val section = sectionV3("0c0208d3") // v4, size-flagged, SigmaProp(true)
    assertEquals(Try(WireCanonicalize.canonicalize("BlockTransactions", section, 2, 2)), Success(section))
  }
}
