package santa

import io.circe.Json

/** Anchors the reader-scope wire vectors: a block's transactions each parse on a fresh reader, while the outputs of
  * one transaction share theirs. extract() re-derives every verdict through the JVM (ergo-core's
  * BlockTransactionsSerializer for a block section, WireCanonicalize for a transaction) and checks that a shared reader
  * would answer differently; this pins each entry's verdict and its framing, hand-derived. A failure means ergo-core or
  * sigma-state changed where reader state lives, or the blesser built different bytes. */
class AuthoredWireReaderScopeTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireReaderScope.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))

  // Size-flagged v3 tree degrading at depth 109: BoolToSigmaProp, 107 LogicalNot, unknown opcode 0xfd.
  private val Degrading = "0b" + "6d" + "d1" + "ef" * 107 + "fd"
  // v0 tree BlockValue(ValDef(1, SigmaProp(true)), ValUse(1)), and the bare ValUse(1).
  private val Defining = "00" + "d801" + "d601" + "08d3" + "7201"
  private val UsingOnly = "00" + "7201"
  private def cand(value: String, tree: String): String = value + tree + "01" + "00" + "00" // height 1, no tokens/regs
  private val V1M = "c0843d"  // 1000000
  private val V2M = "80897a"  // 2000000

  test("block section: framed as header id, VLQ(10000000 + version 4) = 84 ad e2 04, then the tx count") {
    entries(AuthoredWireReaderScope.OpBlock).foreach { e =>
      assertEquals(bytesHex(e).substring(64, 72), "84ade204", "block version 4 marker after the 32-byte header id")
      assertEquals(bytesHex(e).substring(72, 74), "02", "two transactions")
    }
  }

  test("block section: depth and ValDef state do not cross transactions") {
    val es = entries(AuthoredWireReaderScope.OpBlock)
    assertEquals(es.map(isReject), List(false, false, true, false))
    val hex = es.map(bytesHex)
    // #0 tx A (output degrades at depth 109) then tx B: its degrading output comes first
    assert(hex(0).indexOf(cand(V1M, Degrading)) < hex(0).lastIndexOf(cand(V1M, "0008d3")), "A's degrade before B's plain output")
    // #1 the same two transactions, plain first
    assert(hex(1).indexOf(cand(V1M, "0008d3")) < hex(1).indexOf(cand(V1M, Degrading)), "B's plain output before A's degrade")
    // #2 tx A defines ValDef(1), tx B's only output is the bare ValUse(1)
    assert(hex(2).indexOf(cand(V1M, Defining)) < hex(2).indexOf(cand(V1M, UsingOnly)), "A defines, then B uses")
    // #3 the twin: tx B defines its own
    assertEquals(hex(3).split(cand(V1M, Defining), -1).length - 1, 2, "both transactions define ValDef(1)")
  }

  test("one transaction: a ValDef in output 0 reaches output 1; the reverse order rejects") {
    val es = entries(AuthoredWireReaderScope.OpTx)
    assertEquals(es.map(isReject), List(false, true))
    assert(bytesHex(es(0)).endsWith("02" + cand(V1M, Defining) + cand(V2M, UsingOnly)), "outputs: defining, then using")
    assert(bytesHex(es(1)).endsWith("02" + cand(V1M, UsingOnly) + cand(V2M, Defining)), "outputs: using, then defining")
  }

  test("envelopes: santa-wire/v1, version (3, 3), authored source, the blessing path, kind matches the op") {
    assertEquals(vectors.keySet, Set(AuthoredWireReaderScope.OpBlock, AuthoredWireReaderScope.OpTx))
    val stamp = Map(AuthoredWireReaderScope.OpBlock -> "jvm:ergo-core-6.0.6-BlockTransactionsSerializer",
      AuthoredWireReaderScope.OpTx -> "jvm:sigma-state-6.0.6")
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some(stamp(op)))
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some(op.takeWhile(_ != '.')))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
        assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:")))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireReaderScope.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
