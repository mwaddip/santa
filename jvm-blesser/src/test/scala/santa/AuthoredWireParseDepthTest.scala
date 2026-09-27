package santa

import io.circe.Json

/** Anchors the parse-depth wire vectors: one accept/reject pair per path the reader's level counter
  * (MaxTreeDepth 110) runs through. extract() re-derives every verdict through WireCanonicalize and fails loud
  * on a wrong-reason reject, a non-canonical accept, or a size-flagged tree that degraded instead of parsing;
  * this pins each entry's verdict and the nested bytes, hand-derived from the sigmastate v6.0.6 encodings.
  * A failure means sigma-state changed where it counts a level, or the blesser built a different nesting. */
class AuthoredWireParseDepthTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireParseDepth.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
  private def name(e: Json): String = e.hcursor.get[String]("name").toOption.getOrElse(fail("name"))

  /** Coll^n[Byte] as [type][data]: (n-2) generic Coll 0x0c then Coll[Coll[Byte]] 0x1a; (n-1) outer lengths of
    * 1 then the empty innermost Coll[Byte]. */
  private def collN(n: Int): String = "0c" * (n - 2) + "1a" + "01" * (n - 1) + "00"

  /** Input 0's extension starts at byte 34 (inputs count + boxId + empty-proof length), hex offset 68. */
  private def extAt(hex: String, len: Int): String = hex.substring(68, 68 + len)

  /** Each op holds [accept, reject]; `accept`/`reject` must occur in the respective entry's bytes. */
  private def pair(op: String)(check: (String, Boolean) => Unit): Unit = {
    val es = entries(op)
    assertEquals(es.map(isReject), List(false, true), op)
    es.foreach(e => check(bytesHex(e), isReject(e)))
  }

  test("register: output R4 = Coll^109[Byte] accepts, Coll^110 rejects (getValue + n data levels)") {
    pair(AuthoredWireParseDepth.OpRegister) { (hex, reject) =>
      val n = if (reject) 110 else 109
      // the only output is the last thing in the tx: [1 register][R4 value]
      assert(hex.endsWith("01" + collN(n)), s"Coll^$n[Byte] in R4")
    }
  }

  test("tree body: 108 LogicalNot between BoolToSigmaProp and a placeholder accept, 109 reject") {
    pair(AuthoredWireParseDepth.OpTreeBody) { (hex, reject) =>
      val m = if (reject) 109 else 108
      // header 0x18 (v0, size, segregation); size = m + 6; one constant Boolean true; body
      val size = if (reject) "73" else "72"
      val tree = "18" + size + "01" + "0101" + "d1" + "ef" * m + "7300"
      assert(hex.contains(tree), s"tree with $m LogicalNot")
    }
  }

  test("segregated constant: Coll^110[Byte] accepts, Coll^111 rejects (data levels only)") {
    pair(AuthoredWireParseDepth.OpSegregatedConstant) { (hex, reject) =>
      val n = if (reject) 111 else 110
      // header 0x10 (v0, segregation, no size); constants [Coll^n[Byte], SigmaProp(true)]; body placeholder 1
      assert(hex.contains("10" + "02" + collN(n) + "08d3" + "7301"), s"segregated Coll^$n[Byte]")
    }
  }

  test("SigmaBoolean: 107 nested CAND in an extension SigmaProp accept, 108 reject") {
    pair(AuthoredWireParseDepth.OpSigmaBoolean) { (hex, reject) =>
      val k = if (reject) 108 else 107
      // [count 01][id 01][SigmaProp 08] then CAND(inner, TrueProp) k deep: (96 02)^k d3 (d3)^k
      val ext = "0101" + "08" + "9602" * k + "d3" + "d3" * k
      assertEquals(extAt(hex, ext.length), ext, s"$k nested CAND")
    }
  }

  test("nested box: a Box extension value's size-flagged tree constant Coll^108 accepts, Coll^109 rejects") {
    pair(AuthoredWireParseDepth.OpNestedBox) { (hex, reject) =>
      val n = if (reject) 109 else 108
      // [count 01][id 01][Box 63] ... the box's tree: header 0x18, size 2n+4 (VLQ), constants, placeholder 1
      assertEquals(extAt(hex, 6), "010163")
      val size = if (reject) "de01" else "dc01"
      assert(hex.contains("18" + size + "02" + collN(n) + "08d3" + "7301"), s"box tree with Coll^$n[Byte]")
    }
  }

  test("degraded tree leak: output 0 degrades 10 levels deep; output 1 R4 Coll^99 accepts, Coll^100 rejects") {
    pair(AuthoredWireParseDepth.OpDegradeLeak) { (hex, reject) =>
      val n = if (reject) 100 else 99
      // output 0: header 0x0b (v3, size), size 10, BoolToSigmaProp, 8 LogicalNot, unknown opcode 0xfd
      assert(hex.contains("0b" + "0a" + "d1" + "ef" * 8 + "fd"), "degrading tree")
      assert(hex.endsWith("01" + collN(n)), s"output 1 R4 Coll^$n[Byte]")
    }
  }

  test("nested degrade leak: a degrade inside output 0's parsing tree leaves 10 levels; Coll^99 accepts, 100 rejects") {
    pair(AuthoredWireParseDepth.OpNestedDegradeLeak) { (hex, reject) =>
      val n = if (reject) 100 else 99
      // output 0's tree: header 0x18 (v0, size, segregation), size 0x39 = 57, two constants: a Box (type 0x63:
      // value 1000000, the degrading tree, height 1, no tokens or registers, ...) and SigmaProp(true); body
      // placeholder 1
      val degrading = "0b" + "0a" + "d1" + "ef" * 8 + "fd"
      assert(hex.contains("18" + "39" + "02" + "63" + "c0843d" + degrading + "01" + "0000"), "tree carrying the box")
      assert(hex.endsWith("01" + collN(n)), s"output 1 R4 Coll^$n[Byte]")
    }
  }

  test("envelopes: santa-wire/v1, the node's v6 tx parse context (3, 3), Transaction kind, 6.0.6 blessing") {
    assertEquals(vectors.keySet, Set(AuthoredWireParseDepth.OpRegister, AuthoredWireParseDepth.OpTreeBody,
      AuthoredWireParseDepth.OpSegregatedConstant, AuthoredWireParseDepth.OpSigmaBoolean,
      AuthoredWireParseDepth.OpNestedBox, AuthoredWireParseDepth.OpDegradeLeak,
      AuthoredWireParseDepth.OpNestedDegradeLeak))
    vectors.values.foreach { env =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some("Transaction"), name(e))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
        assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:")))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireParseDepth.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
