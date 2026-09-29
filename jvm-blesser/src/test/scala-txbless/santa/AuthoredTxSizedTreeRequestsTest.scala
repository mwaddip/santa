package santa

import io.circe.Json

/** Bless + lock the transaction vectors built for ergots' sized-tree requests. blessAll() fails loud unless the JVM
  * (TxEngine.validateBytes, ergo-core 6.0.6) gives each entry its verdict; this pins the verdicts, the spent trees and
  * the proofs' lengths, hand-derived, and writes the vectors. A failure means ergo-core/sigma-state changed how such a
  * spend verifies or how an output is re-encoded, or the blesser built different bytes. */
class AuthoredTxSizedTreeRequestsTest extends munit.FunSuite {
  import AuthoredTxSizedTreeRequests._
  private lazy val blessed = blessAll().toMap

  private def entries(path: String): List[Json] =
    blessed(path).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def valid(e: Json): Boolean = e.hcursor.downField("expected").get[Boolean]("valid").toOption.get
  private def str(e: Json, k: String): String = e.hcursor.get[String](k).toOption.getOrElse(fail(k))
  private def inputBox(e: Json): String = e.hcursor.downField("input_boxes_hex").as[List[String]].toOption.get.head
  /** The proof length byte: after the 1-byte input count and the 32-byte box id. */
  private def proofLen(e: Json): String = str(e, "tx_bytes_hex").substring(66, 68)

  test("spend: a rule-1001-degraded tree does not spend; empty conjectures spend only with their Fiat-Shamir proof; " +
    "empty SigmaAnd/SigmaOr nodes fail to evaluate") {
    val es = entries(SpendPath)
    val want = List(
      ("0807" + "95" + "0100" + "0402" + "08d3", false, "00"), // If(false, 1, sigmaProp(true)): degrades (1001)
      ("0807" + "95" + "0101" + "08d3" + "0402", true, "00"),  // If(true, sigmaProp(true), 1): parses, spends
      ("0807" + "95" + "0100" + "08d3" + "0402", false, "00"), // If(false, sigmaProp(true), 1): the Int branch
      ("00" + "08" + "9600", true, "18"),                      // CAND() with its 24-byte proof
      ("00" + "08" + "9600", false, "00"),                     // CAND() with no proof
      ("00" + "08" + "980000", true, "18"),                    // CTHRESHOLD(0, []) with its 24-byte proof
      ("00" + "08" + "980000", false, "00"),                   // CTHRESHOLD(0, []) with no proof
      ("00" + "08" + "9700", false, "00"),                     // COR() with no proof
      ("00" + "08" + "9601" + "d3", false, "00"),              // CAND([TrueProp]) with no proof
      ("00" + "08" + "9800" + "01" + "d3", false, "00"),       // CTHRESHOLD(0, [TrueProp]) with no proof
      ("00" + "ea00", false, "00"),                            // the SigmaAnd() node: CAND.normalized requires items
      ("00" + "eb00", false, "00"),                            // the SigmaOr() node: COR.normalized, the same
      ("00" + "ea8002" + "08d3" * 256, true, "00"))            // SigmaAnd of 256 sigmaProp(true): TrueProp, no proof
    assertEquals(es.map(valid), want.map(_._2))
    want.zip(es).foreach { case ((tree, _, len), e) =>
      // the spent box: value 1000000000 (5 VLQ bytes), then the tree
      assert(inputBox(e).startsWith("8094ebdc03" + tree), s"input box with tree $tree")
      assertEquals(proofLen(e), len, s"proof length for $tree")
    }
  }

  test("output bytes: propositionBytes are the tree as received, bytes and ids the re-encoded tree") {
    val es = entries(OutputBytesPath)
    val raw = List("08030" + "8d3", "0801" + "08d3", "0802" + "08d3", "0803" + "08d3", "0803" + "08d3")
    assertEquals(es.map(valid), List(true, true, true, false, false))
    raw.zip(es).foreach { case (r, e) =>
      assert(str(e, "tx_bytes_hex").contains("c0843d" + r), s"output 0 carries the tree as received, $r")
      assertEquals(proofLen(e), "38", "a 56-byte Schnorr proof")
    }
    // The script's constants: what propositionBytes and bytes.slice(3, 7) must equal.
    val enc = "080208d3"
    val consts = List((raw(0), enc), (raw(1), enc), (enc, enc), (enc, enc), (raw(4), raw(4)))
    consts.zip(es).foreach { case ((prop, slice), e) =>
      assert(inputBox(e).contains("0e04" + prop), s"the script compares propositionBytes with $prop")
      assert(inputBox(e).contains("0e04" + slice), s"the script compares bytes.slice(3, 7) with $slice")
    }
  }

  test("envelopes and context: santa-transaction/v1, the storage-rent synthetic context, v6 activated") {
    blessed.values.foreach { env =>
      assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-transaction/v1"))
      assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some(AuthoredTxStorageRent.BlessedBy))
      env.hcursor.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
        assertEquals(e.hcursor.downField("headers_hex").as[List[String]].toOption.map(_.size), Some(10))
      }
    }
  }

  test("write vectors") {
    writeVectors(blessed.toSeq, java.nio.file.Paths.get("..", "vectors"))
  }
}
