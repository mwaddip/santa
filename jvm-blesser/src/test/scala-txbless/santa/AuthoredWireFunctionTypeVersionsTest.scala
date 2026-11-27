package santa

import io.circe.Json

/** Anchors the wire vectors for where a function type is read, by the version context. extract() re-derives every
  * verdict through the JVM; this pins each entry's verdict, pair and bytes, hand-derived. */
class AuthoredWireFunctionTypeVersionsTest extends munit.FunSuite {
  import AuthoredWireFunctionTypeVersions._
  private lazy val v = extract()

  private def entries(slot: String, op: String): List[Json] =
    v(slot)(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
  private def pairOf(e: Json): (Int, Int) = {
    val c = e.hcursor.downField("version")
    (c.get[Int]("activated").toOption.get, c.get[Int]("ergoTree").toOption.get)
  }
  private val ext = "01" + "00" + Func // input 0's extension {0: the function-typed value}

  test("a transaction's extension value: the tree half reads the type, the activated half picks the rule") {
    val want = Map(
      "v6" -> List((3, 3) -> false, (3, 0) -> true, (3, 2) -> true),
      "v5" -> List((2, 2) -> true),
      "v4" -> List((1, 1) -> true, (0, 0) -> true, (1, 3) -> false))
    want.foreach { case (slot, w) =>
      val es = entries(slot, OpTx)
      assertEquals(es.map(pairOf), w.map(_._1), slot)
      assertEquals(es.map(isReject), w.map(_._2), slot)
      es.foreach(e => assert(bytesHex(e).contains(ext), s"$slot: input 0's extension"))
    }
  }

  test("block sections: version 3 refuses at the parse (v5 slot), version 4 round-trips (v6 slot)") {
    // header id (32 bytes), VLQ(10,000,000 + block version), one transaction
    for ((slot, marker, rejects) <- Seq(("v5", "83ade20401", true), ("v6", "84ade20401", false))) {
      val es = entries(slot, OpBlock)
      assertEquals(es.map(isReject), List(rejects, rejects), slot)
      es.foreach(e => assertEquals(bytesHex(e).substring(64, 74), marker, slot))
    }
    assert(bytesHex(entries("v5", OpBlock)(0)).contains("0008d3" + "010001" + Func), "v3 section: output R4")
    assert(bytesHex(entries("v5", OpBlock)(1)).contains(ext), "v3 section: extension")
    assert(bytesHex(entries("v6", OpBlock)(0)).contains(ext), "v4 section: extension")
    assert(bytesHex(entries("v6", OpBlock)(1)).contains("0008d3" + "010001" + Func), "v4 section: output R4")
  }

  test("envelopes: santa-wire/v1, the activation marker, authored source, kind matches the op") {
    for ((slot, ops) <- v; (op, env) <- ops) {
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[Boolean]("activation_rule").toOption, Some(true))
      val kind = if (op == OpTx) "Transaction" else "BlockTransactions"
      entries(slot, op).foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some(kind))
        assertEquals(e.hcursor.get[String]("source").toOption, Some(Source))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored-ftv")
    writeVectors(outDir)
    for ((slot, ops) <- v; op <- ops.keys)
      assert(java.nio.file.Files.exists(outDir.resolve(slot).resolve(s"$op.json")), s"$slot/$op.json")
  }
}
