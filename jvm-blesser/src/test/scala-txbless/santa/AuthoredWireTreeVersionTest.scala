package santa

import io.circe.Json

/** Anchors the wire vectors for a tree whose version is above the activated script version. extract() and extractV5()
  * re-derive every verdict through the JVM (the reject's reason, the degrade rule, the round-trip); this pins each
  * entry's verdict and its bytes, hand-derived from the sigmastate v6.0.6 encodings. A failure means sigma-state or
  * ergo-core changed where a tree's version is checked, or the blesser built different bytes. */
class AuthoredWireTreeVersionTest extends munit.FunSuite {
  import AuthoredWireTreeVersion._
  private lazy val v6 = extract()
  private lazy val v5 = extractV5()

  private def entries(vectors: Map[String, Json], op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))

  private val Value = "c0843d"                 // 1000000
  private val Fields = "01" + "00" + "00"      // creation height 1, no tokens, no registers
  private def kinds(box: String, tx: String): Seq[(String, String)] = Seq(box -> "Box", tx -> "Transaction")

  // A nested box, as a Box constant's data: value 1000000, `tree`, height 1, no tokens, no registers, a zero tx id,
  // index 0. The outer tree holds it as constant 0 (type 63), with SigmaProp(true) as constant 1 and the body
  // ConstantPlaceholder(1); `size` is the outer tree's declared size.
  private def nested(tree: String): String = Value + tree + "01" + "00" + "00" + "00" * 32 + "00"
  private def outer(header: String, size: String, nestedBox: String): String =
    header + size + "02" + "63" + nestedBox + "08d3" + "7301"
  /** A candidate with the plain tree 00 08 d3 whose R4 is a Box constant. */
  private def withBoxR4(nestedBox: String): String = Value + "0008d3" + "01" + "00" + "01" + "63" + nestedBox

  /** Checks the verdicts, and that entry i holds candidate i. */
  private def pin(es: List[Json], want: List[(String, Boolean)]): Unit = {
    assertEquals(es.map(isReject), want.map(_._2))
    want.zip(es).foreach { case ((cand, _), e) => assert(bytesHex(e).contains(cand), s"candidate $cand") }
  }

  // The candidates shared by both kinds under (3, 3), and whether the JVM rejects each.
  private val V6Candidates: List[(String, Boolean)] = List(
    Value + "0c0208d3" + Fields -> true,    // v4
    Value + "0d0208d3" + Fields -> true,    // v5
    Value + "0e0208d3" + Fields -> true,    // v6
    Value + "0f0208d3" + Fields -> true,    // v7
    Value + "0b0208d3" + Fields -> false,   // v3: the control
    Value + "1c0208d3" + Fields -> true,    // v4, segregated, 8 constants declared, the first of type code 211
    Value + "1b0208d3" + Fields -> false,   // the same at v3: it degrades (rule 1018)
    Value + "0408d3" + Fields -> true,      // v4 without the size bit: rule 1012
    Value + "ec0208d3" + Fields -> true,    // v4 with bits 5, 6 and 7 set
    Value + outer("1b", "31", nested("0c0208d3")) + Fields -> true,  // a v4 tree nested in a Box constant
    Value + outer("1b", "31", nested("0b0208d3")) + Fields -> false, // the same at v3
    Value + outer("1b", "30", nested("0408d3")) + Fields -> false,   // nested v4 without the size bit: degrades (1012)
    withBoxR4(nested("0c0208d3")) -> true,  // R4 = a Box whose tree is v4
    withBoxR4(nested("0b0208d3")) -> false) // the same at v3

  kinds(OpBox, OpTx).foreach { case (op, kind) =>
    test(s"$kind under (3, 3): a tree above v3 rejects, before its constants and after the size-bit rule") {
      val es = entries(v6, op)
      pin(es.take(V6Candidates.size), V6Candidates)
      if (kind == "Box") assertEquals(es.size, V6Candidates.size)
    }
  }

  test("Transaction under (3, 3): a Box constant in an input's extension is checked too") {
    val es = entries(v6, OpTx).drop(V6Candidates.size)
    // one input: box id, no proof, extension {0: Box}; then no data inputs, no tokens, one plain output
    def ext(tree: String): String = "00" + "01" + "00" + "63" + nested(tree) + "00" + "00" + "01" + Value + "0008d3" + Fields
    pin(es, List(ext("0c0208d3") -> true, ext("0b0208d3") -> false))
  }

  // A block section: header id (32 bytes), VLQ(10,000,000 + block version), the tx count, the transactions.
  private def sectionOf(e: Json): (String, String) = (bytesHex(e).substring(64, 72), bytesHex(e).substring(72, 74))

  test("block sections of version 4: each transaction is parsed under (3, 3)") {
    val es = entries(v6, OpBlock)
    es.foreach(e => assertEquals(sectionOf(e), ("84ade204", "01"), "block version 4, one transaction"))
    pin(es, List(Value + "0c0208d3" + Fields -> true, Value + "0f0208d3" + Fields -> true, Value + "0b0208d3" + Fields -> false))
  }

  kinds(OpBox, OpTx).foreach { case (op, kind) =>
    test(s"$kind under (2, 2): a v3 tree rejects, soft failure or not; v2 parses") {
      pin(entries(v5, op), List(
        Value + "0b0208d3" + Fields -> true,    // v3
        Value + "0a0208d3" + Fields -> false,   // v2: the control
        Value + "0c0208d3" + Fields -> true,    // v4
        Value + "1b0208d3" + Fields -> true,    // v3, constant of type code 211: the version comes first
        Value + outer("1a", "31", nested("0b0208d3")) + Fields -> true,    // a v3 tree nested in a v2 tree's Box constant
        Value + outer("1a", "31", nested("0a0208d3")) + Fields -> false))  // the same at v2
    }
  }

  test("block sections of version 3: parsed outside any version context, so only the size-bit rule rejects") {
    val es = entries(v5, OpBlock)
    es.foreach(e => assertEquals(sectionOf(e), ("83ade204", "01"), "block version 3, one transaction"))
    pin(es, List(
      Value + "0c0208d3" + Fields -> false,   // v4
      Value + "0f0208d3" + Fields -> false,   // v7
      Value + "0b0208d3" + Fields -> false,   // v3, above the block's activated version 2
      Value + "1c0208d3" + Fields -> false,   // v4, constant of type code 211: degrades (rule 1008)
      Value + "0408d3" + Fields -> true))     // v4 without the size bit: rule 1012 holds in every context
  }

  test("envelopes: santa-wire/v1, the directory's version pair, authored source, the blessing path, kind matches the op") {
    assertEquals(v6.keySet, Set(OpBox, OpTx, OpBlock))
    assertEquals(v5.keySet, Set(OpBox, OpTx, OpBlock))
    for ((vectors, pair) <- Seq(v6 -> 3, v5 -> 2); (op, env) <- vectors) {
      val c = env.hcursor
      val kind = op.takeWhile(_ != '.')
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some(
        if (kind == "BlockTransactions") "jvm:ergo-core-6.0.6-BlockTransactionsSerializer" else "jvm:sigma-state-6.0.6"))
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some(kind))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(pair))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(pair))
        assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:")))
      }
    }
  }

  test("write staging files") {
    val (outDir, outDirV5) = (java.nio.file.Paths.get("target", "wire-authored"), java.nio.file.Paths.get("target", "wire-authored-v5"))
    writeVectors(outDir)
    writeVectorsV5(outDirV5)
    Seq(OpBox, OpTx, OpBlock).foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"$op.json not written")
      assert(java.nio.file.Files.exists(outDirV5.resolve(s"$op.json")), s"v5 $op.json not written")
    }
  }
}
