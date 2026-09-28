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
        (outer("1b", "3f", nested("0008d3", "07" + OptionIntSome1 + "0402" * 6)), false, None))) // 7 registers, R4 Option
    }
  }

  // Count bounds: the declared size covers the prefix; the n bytes after it are a bulk read that crosses the tree window
  // and, from where a degrade resumes, the box's fields: height 1, no tokens, R4 = Coll[Byte](n - 6) of zeros.
  private def payload(n: Int, vlqNMinus6: String): String = "01" + "00" + "01" + "0e" + vlqNMinus6 + zeros(n - 6)
  private val CountPrefixes: List[(String, Boolean)] = List(
    "0809" + "ea" + "a18d06" + "d1930e" + "f61f" -> true,    // SigmaAnd of 100001 items: above MaxArrayLength
    "0809" + "ea" + "a08d06" + "d1930e" + "f61f" -> false,   // 100000 items: reads on, the window degrades it
    "0808" + "83" + "808004" + "0e" + "0e" + "f71f" -> true, // ConcreteCollection of 65536: getUShort's range
    "0808" + "83" + "ffff03" + "0e" + "0e" + "f71f" -> false,// 65535: reads on, degrades
    "0809" + "da" + "0402" + "f0a204" + "0e" + "f61f" -> false, // Apply of 70000 arguments: reads on, degrades
    "0809" + "da" + "0402" + "a18d06" + "0e" + "f61f" -> true)  // 100001 arguments: above MaxArrayLength

  kinds(OpBoxCountBounds, OpTxCountBounds).foreach { case (op, kind) =>
    test(s"$kind count bounds: above the bound rejects; at the bound reads on and the tree window degrades it") {
      val es = entries(op)
      assertEquals(es.map(isReject), CountPrefixes.map(_._2))
      CountPrefixes.zip(es).foreach { case ((prefix, _), e) =>
        val body = if (prefix.startsWith("0808")) payload(4087, "f11f") else payload(4086, "f01f")
        assert(bytesHex(e).contains(Value + prefix + body), s"candidate with prefix $prefix")
        assertEquals(rewritten(e), None)
      }
      if (kind == "Transaction") assert(bytesHex(es.head).endsWith(Value + "0008d3" + "02" + "0000"),
        "a second output follows, so the peek after the bulk read lands on a real byte")
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

  kinds(OpBoxBoolPair, OpTxBoolPair).foreach { case (op, kind) =>
    test(s"$kind 85 pair form: only a relation reads 85 as a Boolean pair; an arithmetic operand is a collection") {
      pinTrees(op, List(
        ("00d193" + "9a" + "850101" + "850101" + "850101", false, None), // EQ(Plus(C, C), C), C = Coll[Boolean](true)
        ("00d193" + "99" + "850101" + "850101" + "850101", false, None), // Minus
        ("00d193" + "8503", false, None)))                                // EQ(true, true) as the pair form
    }
  }

  kinds(OpBoxSigmaBoolean, OpTxSigmaBoolean).foreach { case (op, kind) =>
    test(s"$kind SigmaBoolean in a tree: CTHRESHOLD with 256 children rejects sized, 255 parses, CAND() parses") {
      pinTrees(op, List(
        ("088502" + "08" + "98" + "01" + "8002" + "d3" * 256, true, None),
        ("088402" + "08" + "98" + "01" + "ff01" + "d3" * 255, false, None),
        ("00" + "08" + "9600", false, None)))
    }
  }

  test("SigmaBoolean conjectures: CTHRESHOLD bounds k and n; CAND and COR take no children; k = 0 is allowed") {
    val es = entries(OpConjectures)
    val want = List(
      "98" + "01" + "8002" + "d3" * 256 -> true, "98" + "01" + "ff01" + "d3" * 255 -> false, "980201d3" -> true,
      "9600" -> false, "9700" -> false, "980001d3" -> false, "980000" -> false)
    assertEquals(es.map(isReject), want.map(_._2))
    want.zip(es).foreach { case ((sb, _), e) => assertEquals(bytesHex(e), sb) }
  }

  test("envelopes: santa-wire/v1, version (3, 3), authored source, 6.0.6 blessing, kind matches the op") {
    assertEquals(vectors.keySet.size, 15)
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
