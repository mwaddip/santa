package santa

import io.circe.Json

/** Anchors the vectors built for ergots' sized-tree requests. extract() re-derives every verdict through the JVM
  * (the reject reason, the degrade rule, the re-serialized bytes); this pins each entry's verdict and its tree,
  * hand-derived from the sigmastate v6.0.6 encodings. A failure means sigma-state changed one of these parse rules, or
  * the blesser built different bytes. */
class AuthoredWireSizedTreeRequestsTest extends munit.FunSuite {
  import AuthoredWireSizedTreeRequests._
  private lazy val vectors = extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
  private def rewritten(e: Json): Option[String] = e.hcursor.get[String]("expected_bytes_hex").toOption

  private val Value = "c0843d"
  private val Fields = "01" + "00" + "00" // creation height 1, no tokens, no registers
  private def zeros(n: Int): String = "00" * n
  private def kinds(box: String, tx: String): Seq[(String, String)] = Seq(box -> "Box", tx -> "Transaction")

  /** Checks the verdicts, that each entry holds Value + tree + Fields, and each non-identity entry's rewritten tree. */
  private def pinTrees(op: String, trees: List[(String, Boolean, Option[String])]): Unit = {
    val es = entries(op)
    assertEquals(es.map(isReject), trees.map(_._2))
    trees.zip(es).foreach { case ((tree, _, to), e) =>
      assert(bytesHex(e).contains(Value + tree + Fields), s"candidate with tree $tree")
      assertEquals(rewritten(e), to.map(t => bytesHex(e).replace(Value + tree + Fields, Value + t + Fields)), tree)
    }
  }

  // A nested box: value 1000000, `tree`, height 1, no tokens, `regs`, a zero tx id, index 0. The outer tree holds it as
  // constant 0 (type 63), with SigmaProp(true) as constant 1 and the body ConstantPlaceholder(1).
  private def nested(tree: String, regs: String): String = Value + tree + "01" + "00" + regs + zeros(32) + "00"
  private def outer(header: String, size: String, nestedBox: String): String =
    header + size + "02" + "63" + nestedBox + "08d3" + "7301"
  private val OptionIntSome1 = "28" + "01" + "02"
  private val HeaderValue = AuthoredWireUnparsedSoftForkHeaderConstant.Hex.drop(10).dropRight(4) // after 1a db01 01 68

  kinds(OpBoxNested, OpTxNested).foreach { case (op, kind) =>
    test(s"$kind nested: an unsized nested tree's failure escapes; a nested ValidationException degrades the outer tree") {
      pinTrees(op, List(
        (outer("18", "30", nested("00d1fd", "00")), true, None),              // nested unsized, unknown opcode fd
        (outer("18", "31", nested("0802d1fd", "00")), false, None),           // nested sized: it degrades, the outer parses
        (outer("18", "30", nested("0108d3", "00")), false, None),             // nested v1 without the size bit (1012)
        (outer("10", "", nested("0108d3", "00")), true, None),                // the same, unsized outer
        (outer("1b", "33", nested("0008d3", "01" + OptionIntSome1)), false, None), // R4 Option in a v3 tree (1019)
        (outer("10", "", nested("0008d3", "01" + OptionIntSome1)), true, None),    // the same, unsized v0 outer
        (outer("19", "8802", nested("0008d3", "01" + "68" + HeaderValue)), true, None),  // R4 SHeader, v1 outer
        (outer("1b", "8802", nested("0008d3", "01" + "68" + HeaderValue)), false, None), // R4 SHeader, v3 outer (1019)
        (outer("1b", "3f", nested("0008d3", "07" + OptionIntSome1 + "0402" * 6)), false, None), // 7 registers, R4 Option
        (outer("18", "30", nested("000402", "00")), true, None),             // nested unsized, Int root (1001)
        (outer("18", "31", nested("08020402", "00")), false, None),          // nested sized: it degrades, the outer parses
        // ergots' 2026-09-30 request: a v3 nested tree (0b 02 08 d3) whose box's R4 is read under the ENCLOSING tree's
        // version. One constant, the Box; the body sigmaProp(Upcast(true, Long)) rejects if the parse ever reaches it.
        (outerBody("18", "35", nested(V3Tree, "01" + "090105")), false, None),      // R4 UnsignedBigInt, v0 outer (1017)
        (outerBody("18", "34", nested(V3Tree, "01" + "0402")), true, None),         // R4 Int: the body rejects
        (outerBody("18", "37", nested(V3Tree, "01" + "7001040400")), false, None),  // R4 typed SFunc, v0 outer (1018)
        (outerBody("18", "8a02", nested(V3Tree, "01" + "68" + HeaderValue)), true, None),  // R4 SHeader, v0 outer
        (outerBody("1b", "8a02", nested(V3Tree, "01" + "68" + HeaderValue)), false, None), // R4 SHeader, v3 outer (1019)
        (outerBody("1b", "35", nested(V3Tree, "01" + "090105")), false, None)))     // R4 UnsignedBigInt, v3 outer (1019)
    }
  }
  private val V3Tree = "0b02" + "08d3"
  private def outerBody(header: String, size: String, nestedBox: String): String =
    header + size + "01" + "63" + nestedBox + "d1" + "7e" + "0101" + "05"

  // Count bounds: the declared size covers the prefix; the n bytes after it are a bulk read that crosses the tree window
  // and, from where a degrade resumes, the box's fields: height 1, no tokens, R4 = Coll[Byte](n - 6) of zeros.
  private def payload(n: Int, vlqNMinus6: String): String = "01" + "00" + "01" + "0e" + vlqNMinus6 + zeros(n - 6)
  private val Payload4086 = payload(4086, "f01f")
  private val Payload4087 = payload(4087, "f11f")
  // The candidate after its value, and whether the JVM rejects it. All but the last cross the tree window (a Transaction
  // has a second output after them); in the last, the input ends before the window (a Transaction's last output).
  private val CountCandidates: List[(String, Boolean)] = List(
    "0809" + "ea" + "a18d06" + "d1930e" + "f61f" + Payload4086 -> true,    // SigmaAnd of 100001 items: above MaxArrayLength
    "0809" + "ea" + "a08d06" + "d1930e" + "f61f" + Payload4086 -> false,   // 100000 items: reads on, the window degrades it
    "0808" + "83" + "808004" + "0e" + "0e" + "f71f" + Payload4087 -> true, // ConcreteCollection of 65536: getUShort's range
    "0808" + "83" + "ffff03" + "0e" + "0e" + "f71f" + Payload4087 -> false,// 65535: reads on, degrades
    "0809" + "da" + "0402" + "f0a204" + "0e" + "f61f" + Payload4086 -> false, // Apply of 70000 arguments: reads on, degrades
    "0809" + "da" + "0402" + "a18d06" + "0e" + "f61f" + Payload4086 -> true,  // 100001 arguments: above MaxArrayLength
    "1806" + "a18d06" + "0e" + "f91f" + payload(4089, "f31f") -> true,     // 100001 constants: above MaxArrayLength
    "1806" + "a08d06" + "0e" + "f91f" + payload(4089, "f31f") -> false,    // 100000 constants: reads on, degrades
    "1805" + "8120" + "0e" + "fa1f" + payload(4090, "f41f") -> false,      // 4097 constants: reads on, degrades
    "1804" + "8120" + "0e" + "40" + Fields -> true)                        // 4097 constants: the input ends first

  kinds(OpBoxCountBounds, OpTxCountBounds).foreach { case (op, kind) =>
    test(s"$kind count bounds: above the bound rejects; at the bound reads on, and the window degrades or the input ends") {
      val es = entries(op)
      assertEquals(es.map(isReject), CountCandidates.map(_._2))
      CountCandidates.zip(es).foreach { case ((candidate, _), e) =>
        assert(bytesHex(e).contains(Value + candidate), s"candidate ${candidate.take(40)}")
        assertEquals(rewritten(e), None)
      }
      if (kind == "Transaction") {
        es.init.foreach(e => assert(bytesHex(e).endsWith(Value + "0008d3" + "02" + "0000"),
          "a second output follows, so the peek after the bulk read lands on a real byte"))
        assert(bytesHex(es.last).endsWith(Value + CountCandidates.last._1), "the input ends with the crafted output")
      }
    }
  }

  kinds(OpBoxCountWrap, OpTxCountWrap).foreach { case (op, kind) =>
    test(s"$kind count wrap: a negative constants count means none; getUShort truncates to 32 bits before its check") {
      pinTrees(op, List(
        ("1807" + "ffffffff0f" + "08d3", false, Some("1803" + "00" + "08d3")),     // constants count 2^32 - 1
        ("1807" + "8080808008" + "08d3", false, Some("1803" + "00" + "08d3")),     // 2^31
        ("10" + "ffffffff0f" + "08d3", false, Some("10" + "00" + "08d3")),         // unsized
        ("00d193b1" + "85" + "8080808010" + "0400", false, Some("00d193b1" + "8500" + "0400")),         // 2^32 -> 0
        ("00d193b1" + "85" + "8180808010" + "01" + "0402", false, Some("00d193b1" + "850101" + "0402")), // 2^32+1 -> 1
        ("00d193b1" + "83" + "8080808010" + "04" + "0400", false, Some("00d193b1" + "8300" + "04" + "0400")))) // Coll[Int]
    }
  }

  kinds(OpBoxHeaderBits, OpTxHeaderBits).foreach { case (op, kind) =>
    test(s"$kind header bits 5-7: the tree parses and keeps its header byte") {
      pinTrees(op, List("280208d3", "480208d3", "880208d3", "e80208d3", "e008d3").map(t => (t, false, None)))
    }
  }

  kinds(OpBoxRootForms, OpTxRootForms).foreach { case (op, kind) =>
    test(s"$kind root forms: Apply of a Coll[SigmaProp] parses, of an Int degrades; a Coll[Box] root rejects unsized") {
      pinTrees(op, List(
        ("00da1401d3010400", false, None),   // Apply(Coll(sigmaProp(true)), [0]): the root is a SigmaProp
        ("0806da0400010400", false, None),   // Apply(Int 0, [0]), sized: NoType root, rule 1001 degrades it
        ("00db6501fe", true, None),          // CONTEXT.dataInputs, unsized: rule 1001 rejects
        ("0804db6501fe", false, None)))      // the same, sized: degrades
    }
  }

  private val C3 = "850101" * 3
  kinds(OpBoxBoolPair, OpTxBoolPair).foreach { case (op, kind) =>
    test(s"$kind 85 pair form: only a relation reads 85 as a Boolean pair; an arithmetic operand is a collection") {
      pinTrees(op, List(
        ("00d193" + "9a" + "850101" + "850101" + "850101", false, None), // EQ(Plus(C, C), C), C = Coll[Boolean](true)
        ("00d193" + "99" + "850101" + "850101" + "850101", false, None), // Minus
        ("00d193" + "8503", false, None)) ++                              // EQ(true, true) as the pair form
        List("9c", "9d", "9e", "a1", "a2").map(op => ("00d193" + op + C3, false, None)) ++ // Multiply .. Max: unchecked
        List("f2", "f3", "f5").map(op => ("00d193" + op + C3, true, None)) ++  // BitOr, BitAnd, BitXor: numeric only
        List("f2", "f3", "f5").map(op => ("080c" + "d193" + op + C3, true, None))) // the same, sized
    }
  }

  kinds(OpBoxSigmaBoolean, OpTxSigmaBoolean).foreach { case (op, kind) =>
    test(s"$kind SigmaBoolean in a tree: CTHRESHOLD with 256 children rejects sized; CAND/COR and SigmaAnd/SigmaOr of 0 or 256 parse") {
      pinTrees(op, List(
        ("088502" + "08" + "98" + "01" + "8002" + "d3" * 256, true, None),
        ("088402" + "08" + "98" + "01" + "ff01" + "d3" * 255, false, None),
        ("00" + "08" + "9600", false, None),
        ("00" + "08" + "96" + "8002" + "d3" * 256, false, None),  // CAND(256 × TrueProp): no bound on CAND
        ("00" + "08" + "97" + "8002" + "d3" * 256, false, None),  // COR(256 × TrueProp)
        ("00" + "ea00", false, None),                              // SigmaAnd(): no items
        ("00" + "eb00", false, None),                              // SigmaOr()
        ("00" + "ea8002" + "08d3" * 256, false, None),             // SigmaAnd of 256 sigmaProp(true)
        ("00" + "eb8002" + "08d3" * 256, false, None)))            // SigmaOr of 256
    }
  }

  test("SigmaBoolean conjectures: CTHRESHOLD bounds k and n; CAND and COR take 0 or more than 255; counts truncate") {
    val es = entries(OpConjectures)
    val want: List[(String, Boolean, Option[String])] = List(
      ("98" + "01" + "8002" + "d3" * 256, true, None), ("98" + "01" + "ff01" + "d3" * 255, false, None),
      ("980201d3", true, None), ("9600", false, None), ("9700", false, None), ("980001d3", false, None),
      ("980000", false, None),
      ("96" + "8002" + "d3" * 256, false, None),               // CAND(256 × TrueProp)
      ("97" + "8002" + "d3" * 256, false, None),               // COR(256 × TrueProp)
      ("98" + "8002" + "01" + "d3", true, None),               // CTHRESHOLD(256, [TrueProp]): k > n
      ("98" + "8180808010" + "01" + "d3", false, Some("980101d3")), // k = 2^32 + 1 reads as 1
      ("96" + "8180808010" + "d3", false, Some("9601d3")),     // CAND's count 2^32 + 1 reads as 1
      ("96" + "8080848010", true, None))                       // 2^32 + 2^16 reads as 65536: out of range
    assertEquals(es.map(isReject), want.map(_._2))
    want.zip(es).foreach { case ((sb, _, to), e) => assertEquals(bytesHex(e), sb); assertEquals(rewritten(e), to, sb) }
  }

  // getUShort outside trees. A bare box: Value, SigmaProp(true), Fields, the placeholder's tx id, index 0.
  private val TxId = "1d823ee9ea823cc80232a19181efad41d66849c33ed5d0d6c5750b8d60f1d664"
  private val PlainBox = Value + "0008d3" + Fields + TxId + "00"
  test("Box ushort wrap: a box's index and a register's collection length truncate to 32 bits, then 0..65535") {
    val es = entries(OpBoxUShort)
    val r4 = (len: String) => Value + "0008d3" + "01" + "00" + "01" + "0e" + len + "ab" + TxId + "00"
    val want: List[(String, Boolean, Option[String])] = List(
      (PlainBox.dropRight(2) + "8080808010", false, Some(PlainBox)),                 // index 2^32 reads as 0
      (PlainBox.dropRight(2) + "8180808010", false, Some(PlainBox.dropRight(2) + "01")), // 2^32 + 1 as 1
      (PlainBox.dropRight(2) + "8080848010", true, None),                            // 2^32 + 2^16: out of range
      (r4("8180808010"), false, Some(r4("01"))))                                     // R4 Coll[Byte] of 2^32 + 1: 1 byte
    assertEquals(es.map(isReject), want.map(_._2))
    want.zip(es).foreach { case ((bx, _, to), e) => assertEquals(bytesHex(e), bx); assertEquals(rewritten(e), to, bx) }
  }

  test("Transaction ushort wrap: the input, data-input and output counts and a proof's length truncate") {
    val es = entries(OpTxUShort)
    val (inputs, boxId) = ("01", "00a19de1b5fa998df5a48630a611180690abad5270c33f23a79baba2f8840d71")
    val (proof, ext, dataInputs, tokens, outputs) = ("00", "00", "00", "00", "01")
    val tail = Value + "0008d3" + Fields
    def tx(i: String = inputs, p: String = proof, d: String = dataInputs, o: String = outputs): String =
      i + boxId + p + ext + d + tokens + o + tail
    val plain = tx()
    val want: List[(String, Boolean, Option[String])] = List(
      (tx(i = "8180808010"), false, Some(plain)),      // inputs 2^32 + 1: one input
      (tx(i = "8180848010"), true, None),              // 2^32 + 2^16 + 1 reads as 65537: out of range
      (tx(d = "8080808010"), false, Some(plain)),      // data inputs 2^32: none
      (tx(o = "8180808010"), false, Some(plain)),      // outputs 2^32 + 1: one output
      (tx(p = "8180808010ab"), false, Some(tx(p = "01ab")))) // proof length 2^32 + 1: one byte
    assertEquals(es.map(isReject), want.map(_._2))
    want.zip(es).foreach { case ((t, _, to), e) => assertEquals(bytesHex(e), t); assertEquals(rewritten(e), to, t) }
  }

  test("Constant ushort wrap: a collection's length and a BigInt's size truncate to 32 bits, then 0..65535") {
    val es = entries(OpConstUShort)
    val want: List[(String, Boolean, Option[String])] = List(
      ("0e" + "8180808010" + "ab", false, Some("0e01ab")),  // Coll[Byte] of 2^32 + 1: 1 byte
      ("10" + "8180808010" + "02", false, Some("100102")),  // Coll[Int] of 2^32 + 1: 1 item
      ("06" + "8180808010" + "01", false, Some("060101")),  // BigInt of 2^32 + 1 bytes: 1 byte
      ("0e" + "8080848010", true, None))                    // Coll[Byte] of 2^32 + 2^16: out of range
    assertEquals(es.map(isReject), want.map(_._2))
    want.zip(es).foreach { case ((c, _, to), e) => assertEquals(bytesHex(e), c); assertEquals(rewritten(e), to, c) }
  }

  test("envelopes: santa-wire/v1, version (3, 3), authored source, 6.0.6 blessing, kind matches the op") {
    assertEquals(vectors.keySet.size, 18)
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some(op.takeWhile(_ != '.')))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:")))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    writeVectors(outDir)
    vectors.keys.foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"$op.json not written"))
  }
}
