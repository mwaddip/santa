package santa

import io.circe.Json

/** Anchors deserialize-substitution-spend (ergots' node-construction requests, 2026-09-30): spends of trees that
  * carry DeserializeRegister or DeserializeContext. Each entry's tree, its R4 or variable 1, and its verdict are
  * pinned here, hand-assembled from the sigmastate v6.0.6 opcodes; the blesser re-derives every verdict through
  * TxEngine.validateBytes and fails loud on a wrong one or a wrong reason. A failure means the blesser built a
  * different transaction, or sigma-state changed how it substitutes. */
class AuthoredTxDeserializeSubstitutionTest extends munit.FunSuite {
  private lazy val blessed = AuthoredTxDeserializeSubstitution.blessAll().toMap
  private def entries: List[Json] = blessed(AuthoredTxDeserializeSubstitution.SpendPath).hcursor.downField("entries")
    .as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def valid(e: Json): Boolean =
    e.hcursor.downField("expected").get[Boolean]("valid").fold(err => fail(s"valid: $err"), identity)
  private def cost(e: Json): Long = e.hcursor.downField("expected").get[Long]("cost").fold(err => fail(s"cost: $err"), identity)
  private def reason(e: Json): String = e.hcursor.downField("expected").get[String]("reason").getOrElse("")
  private def str(e: Json, field: String): String = e.hcursor.get[String](field).fold(err => fail(s"$field: $err"), identity)
  private def inputBox(e: Json): String =
    e.hcursor.downField("input_boxes_hex").downArray.as[String].fold(err => fail(s"input box: $err"), identity)

  // Opcodes: ByIndex b2, Tuple 86, Filter b5, FuncValue d9, OptionGet e4, GetVar e3, DeserializeRegister d5 (register,
  // type, default flag, default), DeserializeContext d4 (type, id), If 95, EQ 93, GT 91, SizeOf b1, Negation f0,
  // Plus 9a, Apply da. Types: SAny 61, Int 04, SigmaProp 08, Coll[Int] 10, Coll[Long] 11, Option[Boolean] 25.
  private val BI      = "b2" + "860204000400" + "0400" + "00" // ByIndex(Tuple(0, 0), 0)
  private val FBI     = "b5" + BI + "d9010104" + "0101"     // Filter(BI, (i: Int) => true)
  private val GV      = "e4" + "e30161"                      // OptionGet(GetVar(1, SAny))
  private val OGBI    = "e4" + BI                            // OptionGet(BI)
  private val Apply00 = "da" + "0400" + "01" + "0400"        // Apply(Int 0, [Int 0])
  private def dr(t: String, d: Option[String]): String = "d5" + "04" + t + d.map("01" + _).getOrElse("00")
  private def dead(c: String): String = "00" + "d1" + "95" + "0100" + c + "0101" // sigmaProp(If(false, c, true))
  private def live(c: String): String = "00" + "d1" + c
  private def bytes(h: String): String = "0e" + "%02x".format(h.length / 2) + h // a short Coll[Byte] constant

  /** (tree, R4 as written in the box, variable 1 as written in the extension, valid, a reason fragment). */
  private val Cases: List[(String, Option[String], Option[String], Boolean, String)] = List(
    (dead("93" + dr("61", None) + GV), Some(bytes(OGBI)), None, true, ""),                        // S3j
    (live("93" + dr("04", None) + "0402"), Some(bytes("950101" + "0402" + "b1" + OGBI)), None, false,
      "Should be overriden"),                                                                     // L1
    (live("93" + dr("04", None) + "0402"), Some(bytes("950101" + "0402" + "0404")), None, true, ""), // L1's twin
    (dead("93" + dr("61", Some(FBI)) + GV), Some(bytes(FBI)), None, true, ""),                    // S3
    (dead("93" + dr("04", None) + "0402"), Some("0402"), None, true, ""),                         // S15
    (live("93" + dr("04", None) + "0402"), Some("0402"), None, false, "Should be overriden"),     // S15, live
    (dead("93" + dr("61", Some(FBI)) + GV), None, None, true, ""),                                // S2
    (dead("91" + "b1" + dr("61", Some(FBI)) + "0400"), None, None, true, ""),                     // S1
    (dead("93" + "950101" + dr("61", Some(FBI)) + GV + GV), None, None, false, "InvocationTargetException"), // S8
    (dead("91" + "f0" + dr("04", Some("1000")) + "0400"), None, None, false, "InvocationTargetException"),   // S5
    (dead("e4" + dr("25", Some("0d00"))), None, None, false, "InvocationTargetException"),                   // S6
    (dead("91" + "9a" + dr("04", Some(FBI)) + "0400" + "0400"), None, None, false, "InvocationTargetException"), // S9
    (dead("93" + dr("61", None) + GV), Some(bytes(Apply00)), None, false,
      "expected deserialized value to have type SAny; got NoType"),                               // S10
    (dead("93" + "d46101" + GV), None, Some(bytes(FBI)), true, ""),                               // S16
    (dead("93" + "d46101" + GV), None, Some(bytes(OGBI)), true, ""),                              // S16b
    (dead("93" + "d46101" + GV), None, Some(bytes(Apply00)), false, "ValidationRule(1000"),       // S16c
    // R1: the tree 10 01 04 .. decodes as the Coll[Int] constant (2): type 10, length 01, item 04 (zigzag 2).
    ("10" + "01" + "0404" + "d193" + "b2" + "d5011000" + "0400" + "00" + "7300", None, None, true, ""),
    ("10" + "01" + "0402" + "d193" + "b2" + "d5011000" + "0400" + "00" + "7300", None, None, false, "Success((false"),
    ("10" + "01" + "0402" + "d193" + "b1" + "d5011100" + "7300", None, None, false,
      "expected deserialized value to have type Coll[SLong$]; got Coll[SInt$]"),
    (dead("93" + "d5016100" + GV), None, None, false, "InvalidTypePrefix"),                       // R1 of an unsized v0 tree
    // The root: DeserializeRegister(R4, SigmaProp, default), R4 absent.
    ("00" + "d5040801" + "0101", None, None, true, ""),
    ("00" + "d5040801" + "0100", None, None, false, "Success((false"),
    ("00" + "d5040801" + "0402", None, None, false,
      "Context-dependent pre-processing should produce tree of type Boolean or SigmaProp"),
    ("00" + "d5040800", None, None, false, "Should be overriden"))

  test("24 spends: the tree, R4 and variable 1 as pinned, the JVM's verdict and reason") {
    val es = entries
    assertEquals(es.size, Cases.size)
    es.zip(Cases).zipWithIndex.foreach { case ((e, (tree, r4, var1, want, because)), i) =>
      assertEquals(valid(e), want, s"entry $i")
      // the spent box: value 1000000000 (80 94 eb dc 03), the tree, height 1, no tokens, then its registers
      val regs = r4.map("01" + _).getOrElse("00")
      assert(inputBox(e).startsWith("8094ebdc03" + tree + "01" + "00" + regs), s"entry $i: box ${inputBox(e).take(120)}")
      // input 0's extension, {1: var1} or empty: tx byte 34, after the input count, the box id and the empty proof
      assertEquals(str(e, "tx_bytes_hex").substring(68, 68 + var1.map(v => 4 + v.length).getOrElse(2)),
        var1.map(v => "01" + "01" + v).getOrElse("00"), s"entry $i: the extension")
      assert(reason(e).contains(because), s"entry $i: '${reason(e)}' should mention '$because'")
    }
  }

  test("the decode is charged twice its length only when it completes") {
    val es = entries
    // S3 decodes F(BI) (17 bytes) and swallows the cast at the type read; S2 has no R4. The same tree otherwise.
    assertEquals(cost(es(3)) - cost(es(6)), 2L * 17)
    // S16 decodes F(BI) from variable 1; S16b's decode of OptionGet(BI) throws inside, before the charge.
    assertEquals(cost(es(13)) - cost(es(14)), 2L * 17)
  }

  test("envelope and context: santa-transaction/v1, the storage-rent synthetic context, v6 activated") {
    val env = blessed(AuthoredTxDeserializeSubstitution.SpendPath)
    assertEquals(env.hcursor.get[String]("schema").toOption, Some("santa-transaction/v1"))
    assertEquals(env.hcursor.get[String]("op").toOption, Some("tx:authored:deserialize-substitution-spend"))
    assertEquals(env.hcursor.get[String]("blessed_by").toOption, Some(AuthoredTxStorageRent.BlessedBy))
    entries.foreach { e =>
      assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
      assertEquals(e.hcursor.downField("context").get[Int]("height").toOption, Some(AuthoredTxStorageRent.H))
    }
  }

  test("write vectors") {
    AuthoredTxDeserializeSubstitution.writeVectors(blessed.toSeq, java.nio.file.Paths.get("..", "vectors"))
  }
}
