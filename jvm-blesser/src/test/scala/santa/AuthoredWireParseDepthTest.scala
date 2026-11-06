package santa

import io.circe.Json

/** Anchors the parse-depth wire vectors: one accept/reject pair per path the reader's depth counters
  * run through. Type-nesting vectors pin MaxTypeDepth = 8 (TypeSerializer, sigma-state v6.0.7);
  * value-nesting vectors pin MaxTreeDepth = 110 (CoreByteReader). extract() re-derives every verdict
  * through WireCanonicalize and fails loud on a wrong-reason reject, a non-canonical accept, or a
  * size-flagged tree that degraded instead of parsing. */
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

  /** Each op holds [accept, reject]; `accept`/`reject` must occur in the respective entry's bytes. */
  private def pair(op: String)(check: (String, Boolean) => Unit): Unit = {
    val es = entries(op)
    assertEquals(es.map(isReject), List(false, true), op)
    es.foreach(e => check(bytesHex(e), isReject(e)))
  }

  test("register: output R4 = Coll^10[Byte] accepts at type depth 8, Coll^11 rejects") {
    pair(AuthoredWireParseDepth.OpRegister) { (hex, reject) =>
      val n = if (reject) 11 else 10
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

  test("segregated constant: Coll^10[Byte] accepts at type depth 8, Coll^11 rejects") {
    pair(AuthoredWireParseDepth.OpSegregatedConstant) { (hex, reject) =>
      val n = if (reject) 11 else 10
      // header 0x10 (v0, segregation, no size); constants [Coll^n[Byte], SigmaProp(true)]; body placeholder 1
      assert(hex.contains("10" + "02" + collN(n) + "08d3" + "7301"), s"segregated Coll^$n[Byte]")
    }
  }

  test("SigmaBoolean: 107 nested CAND in an extension SigmaProp accept, 108 reject") {
    pair(AuthoredWireParseDepth.OpSigmaBoolean) { (hex, reject) =>
      val k = if (reject) 108 else 107
      // [count 01][id 01][SigmaProp 08] then CAND(inner, TrueProp) k deep: (96 02)^k d3 (d3)^k
      val ext = "0101" + "08" + "9602" * k + "d3" + "d3" * k
      assertEquals(hex.substring(68, 68 + ext.length), ext, s"$k nested CAND")
    }
  }

  test("nested box: a Box extension value's size-flagged tree constant Coll^10 accepts, Coll^11 rejects") {
    pair(AuthoredWireParseDepth.OpNestedBox) { (hex, reject) =>
      val n = if (reject) 11 else 10
      // [count 01][id 01][Box 63] ... the box's tree: header 0x18, size VLQ, 2 constants, placeholder 1
      assertEquals(hex.substring(68, 74), "010163")
      // size = constants_count(1) + Coll^n type+data + SigmaProp(08 d3) + body(73 01)
      val collBytes = collN(n).length / 2
      val size = "%02x".format(1 + collBytes + 2 + 2)
      assert(hex.contains("18" + size + "02" + collN(n) + "08d3" + "7301"),
        s"box tree with Coll^$n[Byte]")
    }
  }

  test("degraded tree leak: output 0 degrades 10 levels deep; output 1 LogicalNot^98 accepts, 99 rejects") {
    pair(AuthoredWireParseDepth.OpDegradeLeak) { (hex, reject) =>
      val m = if (reject) 99 else 98
      // output 0: header 0x0b (v3, size), size 10, BoolToSigmaProp, 8 LogicalNot, unknown opcode 0xfd
      assert(hex.contains("0b" + "0a" + "d1" + "ef" * 8 + "fd"), "degrading tree")
      // output 1's tree: header 0x18, size = m + 6, one constant Boolean true, body
      val size = if (reject) "69" else "68"
      val tree = "18" + size + "01" + "0101" + "d1" + "ef" * m + "7300"
      assert(hex.contains(tree), s"output 1 tree with $m LogicalNot")
    }
  }

  test("nested degrade leak: a degrade inside output 0's parsing tree leaves 10 levels; LogicalNot^98 accepts, 99 rejects") {
    pair(AuthoredWireParseDepth.OpNestedDegradeLeak) { (hex, reject) =>
      val m = if (reject) 99 else 98
      // output 0's tree: header 0x18 (v0, size, segregation), carries the degrading box
      val degrading = "0b" + "0a" + "d1" + "ef" * 8 + "fd"
      assert(hex.contains(degrading), "degrading tree inside output 0's box constant")
      // output 1's tree: same LogicalNot chain
      val size = if (reject) "69" else "68"
      val tree = "18" + size + "01" + "0101" + "d1" + "ef" * m + "7300"
      assert(hex.contains(tree), s"output 1 tree with $m LogicalNot")
    }
  }

  test("envelopes: santa-wire/v1, the node's v6 tx parse context (3, 3), Transaction kind, 6.0.7 blessing") {
    assertEquals(vectors.keySet, Set(AuthoredWireParseDepth.OpRegister, AuthoredWireParseDepth.OpTreeBody,
      AuthoredWireParseDepth.OpSegregatedConstant, AuthoredWireParseDepth.OpSigmaBoolean,
      AuthoredWireParseDepth.OpNestedBox, AuthoredWireParseDepth.OpDegradeLeak,
      AuthoredWireParseDepth.OpNestedDegradeLeak))
    vectors.values.foreach { env =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
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
