package santa

import io.circe.Json

/** Anchors the vectors for sigma-rust's evaluated-values probe: context-extension and register values that are
  * EvaluatedValues but not Constants. extract() re-derives every verdict and re-serialization through the JVM; this pins
  * each entry's verdict, bytes and version, hand-derived from the sigmastate v6.0.6 encodings. A failure means
  * sigma-state changed how a spending proof's extension or a box's registers parse or are written back, or the blesser
  * built different bytes. */
class AuthoredWireEvaluatedValuesTest extends munit.FunSuite {
  import AuthoredWireEvaluatedValues._
  private lazy val vectors = extract()
  private lazy val vectorsV5 = extractV5()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
  private def rewritten(e: Json): Option[String] = e.hcursor.get[String]("expected_bytes_hex").toOption
  private def version(e: Json): (Int, Int) = {
    val v = e.hcursor.downField("version")
    (v.get[Int]("activated").toOption.get, v.get[Int]("ergoTree").toOption.get)
  }

  private val V33 = (3, 3)
  private val BoxId = "00a19de1b5fa998df5a48630a611180690abad5270c33f23a79baba2f8840d71" // the spent placeholder box
  private val Output = "c0843d" + "0008d3" + "010000" // value 1000000, SigmaProp(true), height 1, no tokens or registers
  /** The one-input transaction (no proof) whose input 0 has the extension {0: value}, one output. */
  private def extTx(value: String): String = "01" + BoxId + "00" + "01" + "00" + value + "00" + "00" + "01" + Output

  test("extension values: EvaluatedValues parse; Constants and Boolean collections are written back in their own form") {
    val es = entries(OpExt)
    val want: List[(String, Boolean, Option[String], (Int, Int))] = List(
      ("7f", false, Some("0101"), V33),                          // X1 TrueLeaf, written back as the constant
      ("80", false, Some("0100"), V33),                          // X2 FalseLeaf
      ("82", false, None, V33),                                  // X3 GroupGenerator
      ("83020404020404", false, None, V33),                      // X4 Coll[Int](1, 2)
      ("83020101010100", false, Some("850201"), V33),            // X5 Coll[Boolean](true, false), constant items
      ("8302017f80", false, Some("850201"), V33),                // X6 the same with TrueLeaf/FalseLeaf items
      ("830001", false, Some("8500"), V33),                      // X7 an empty Coll[Boolean]
      ("850201", false, None, V33),                              // X8 the packed Boolean form
      ("860204020404", false, None, V33),                        // X9 Tuple(1, 2)
      ("8603040204040406", false, None, V33),                    // X10 Tuple(1, 2, 3)
      ("86010402", false, None, V33),                            // X11 Tuple(1)
      ("8600", false, None, V33),                                // X12 Tuple()
      ("86020402a3", false, None, V33),                          // X13 Tuple(1, HEIGHT): items are not cast at parse
      ("830104a3", false, None, V33),                            // X14 Coll[Int](HEIGHT)
      ("860204027e040205", false, None, V33),                    // X15 Tuple(1, Upcast(1, Long)) at tree v3: kept
      ("7300", true, None, V33),                                 // N1 ConstantPlaceholder(0): the store is empty
      ("a3", true, None, V33),                                   // N2 HEIGHT: not an EvaluatedValue
      ("9a04020404", true, None, V33),                           // N3 Plus(1, 2)
      ("860204027300", true, None, V33),                         // N4 Tuple(1, placeholder)
      ("86020402e30004", true, None, V33),                       // N5 Tuple(1, GetVar[Int](0)): Option, rule 1019
      ("8680" + "0402" * 128, true, None, V33),                  // N6 Tuple count 0x80: getByte reads -128
      ("830158860204020404", false, None, V33),                  // C1 a Coll[(Int, Int)] holding a Tuple node
      ("8300700204040400", false, None, V33),                    // C2 an empty Coll of (Int, Int) => Int: v3 types
      ("83007001040400", false, None, V33))                      // C2 twin: an empty Coll of Int => Int
    assertEquals(es.map(isReject), want.map(_._2))
    want.zip(es).foreach { case ((v, _, to, ver), e) =>
      assertEquals(bytesHex(e), extTx(v), v)
      assertEquals(rewritten(e), to.map(extTx), v)
      assertEquals(version(e), ver, v)
    }
  }

  test("v5: below tree v3 an Upcast of a constant is dropped, of HEIGHT kept; function types do not parse") {
    val es = vectorsV5(OpExt).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
    val want: List[(String, Boolean, Option[String])] = List(
      ("860204027e040205", false, Some("860204020402")), // X15 Tuple(1, Upcast(1, Long)): the Upcast of a constant goes
      ("8300700204040400", true, None),                  // C2: the function type code before v3, rule 1008
      ("83007001040400", true, None),                    // C2 twin: the same
      ("860204027ea305", false, None))                   // U1 Tuple(1, Upcast(HEIGHT, Long)): the Upcast stays
    assertEquals(es.map(isReject), want.map(_._2))
    want.zip(es).foreach { case ((v, _, to), e) =>
      assertEquals(bytesHex(e), extTx(v), v)
      assertEquals(rewritten(e), to.map(extTx), v)
      assertEquals(version(e), (2, 2), v)
    }
  }

  // The same values as R4 of a candidate: value 1000000, SigmaProp(true), height 1, no tokens, one register.
  private def withR4(v: String): String = "c0843d" + "0008d3" + "01" + "00" + "01" + v
  private val Regs: List[(String, Boolean, Option[String])] = List(
    ("82", false, None),                             // GroupGenerator
    ("83020404020404", false, None),                 // Coll[Int](1, 2)
    ("7f", false, Some("0101")),                     // TrueLeaf, written back as the constant
    ("83020101010100", false, Some("850201")),       // Coll[Boolean](true, false), written back packed
    ("86020402a3", false, None),                     // Tuple(1, HEIGHT)
    ("86010402", false, None),                       // Tuple(1)
    ("860204020404", false, None),                   // Tuple(1, 2)
    ("830104a3", false, None),                       // Coll[Int](HEIGHT)
    ("7300", true, None),                            // ConstantPlaceholder(0)
    ("a3", true, None),                              // HEIGHT
    ("9a04020404", true, None),                      // Plus(1, 2)
    ("860204027300", true, None),                    // Tuple(1, placeholder)
    ("86020402e30004", true, None),                  // Tuple(1, GetVar[Int](0)): rule 1019
    ("8680" + "0402" * 128, true, None),             // Tuple count 0x80
    ("830158860204020404", false, None),             // C1: a Coll[(Int, Int)] holding a Tuple node
    ("83020202010201", false, None))                 // D1: Coll[Byte](1, 1) as a node
  Seq(OpBoxRegs -> "Box", OpTxRegs -> "Transaction").foreach { case (op, kind) =>
    test(s"$kind registers: the same values as R4 parse or reject alike; 7f and constant Booleans are written back") {
      val es = entries(op)
      assertEquals(es.map(isReject), Regs.map(_._2))
      Regs.zip(es).foreach { case ((v, _, to), e) =>
        assert(bytesHex(e).contains(withR4(v)), s"candidate with R4 = $v")
        assertEquals(rewritten(e), to.map(t => bytesHex(e).replace(withR4(v), withR4(t))), v)
        assertEquals(version(e), V33, v)
      }
      if (kind == "Transaction") es.foreach(e => assert(bytesHex(e).startsWith("01" + BoxId + "00" + "00"),
        "a one-input transaction with no proof and no extension"))
    }
  }

  test("envelopes: santa-wire/v1, authored source, 6.0.6 blessing, kind matches the op") {
    assertEquals(vectors.keySet, Set(OpExt, OpBoxRegs, OpTxRegs))
    assertEquals(vectorsV5.keySet, Set(OpExt))
    (vectors.toSeq ++ vectorsV5.toSeq).foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some(op.takeWhile(_ != '.')))
        assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:")))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    writeVectors(outDir)
    vectors.keys.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"$op.json not written"))
    val outDirV5 = java.nio.file.Paths.get("target", "wire-authored-v5")
    writeVectorsV5(outDirV5)
    assert(java.nio.file.Files.exists(outDirV5.resolve(s"$OpExt.json")), s"v5 $OpExt.json not written")
  }
}
