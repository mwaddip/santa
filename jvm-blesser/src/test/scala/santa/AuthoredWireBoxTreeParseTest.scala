package santa

import io.circe.Json

/** Anchors the box-tree parse vectors: the MaxPropositionSize tree read window and the unsized-tree root-type check.
  * extract() re-derives every verdict through WireCanonicalize and fails loud on a wrong-reason reject, a
  * non-canonical accept, or a tree that parsed where it should degrade (or the reverse); this pins each entry's
  * verdict and the candidate bytes, hand-derived from the sigmastate v6.0.6 encodings and window arithmetic. A
  * failure means sigma-state changed a window or the root check, or the blesser built a different candidate. */
class AuthoredWireBoxTreeParseTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireBoxTreeParse.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def bytesHex(e: Json): String = e.hcursor.get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))

  // Candidate = value c0843d (3 bytes: the box window starts 3 bytes before the tree window) | tree | fields.
  // A Box entry is the candidate followed by a 32-byte tx id and index 00; a Transaction entry carries it as an
  // output.
  private val Value = "c0843d"
  private def zeros(n: Int): String = "00" * n
  private val Fields = "01" + "00" + "00" // creation height 1, no tokens, no registers

  // V1: sized v0 tree declared 5, body BoolToSigmaProp(EQ(Coll[Byte](4090 bytes), ...)). The bulk read starts at
  // candidate offset 10 and ends at 4100 > 4099 (tree window: tree start 3 + 4096); the next read trips it and the
  // tree degrades to its declared 5 bytes. The box then reads its fields from offset 10: height 1, no tokens,
  // R4 = Coll[Byte](4084 bytes), whose bulk read starts at 16 and crosses the box window at 4096.
  private val V1 = Value + "08" + "05" + "d1" + "93" + "0e" + "fa1f" +
    "01" + "00" + "01" + "0e" + "f41f" + zeros(4084)
  // V2: the same kind of body in an unsized v0 tree, completed with EQ's second operand (empty Coll[Byte]).
  private val V2 = Value + "00" + "d1" + "93" + "0e" + "fb1f" + zeros(4091) + "0e00" + Fields
  // V3: sized, segregated v0 tree of 4094 bytes (constants Coll[Byte](4086), SigmaProp(true); body placeholder 1)
  // ending at candidate offset 4100: its last reads sit in (4096, 4099], legal under the tree window; then the box
  // window is back and the creation-height read at 4100 > 4096 fails.
  private val V3 = Value + "18" + "fe1f" + "02" + "0e" + "f61f" + zeros(4086) + "08d3" + "7301" + Fields
  // V4: the same tree with Coll[Byte](4080), ending at 4094: the box reads at 4094, 4095, 4096 all start in time.
  private val V4 = Value + "18" + "f81f" + "02" + "0e" + "f01f" + zeros(4080) + "08d3" + "7301" + Fields

  test("Box tree window: V1 degrades and accepts; V2 and V3 reject; V4 accepts") {
    val es = entries(AuthoredWireBoxTreeParse.OpBoxWindow)
    val want = List(false -> V1, true -> V2, true -> V3, false -> V4)
    assertEquals(es.size, want.size)
    es.zip(want).foreach { case (e, (reject, cand)) =>
      assertEquals(isReject(e), reject)
      assert(bytesHex(e).startsWith(cand), "the Box starts with the candidate")
      assertEquals(bytesHex(e).length, cand.length + 2 * 33, "then a 32-byte tx id and index 00")
      assert(bytesHex(e).endsWith("00"))
    }
  }

  test("Transaction tree window: V1 mid-tx accepts, V1 as the last output rejects (the peek), V2 and V3 reject, V4 accepts") {
    val es = entries(AuthoredWireBoxTreeParse.OpTxWindow)
    assertEquals(es.map(isReject), List(false, true, true, true, false))
    val hex = es.map(bytesHex)
    // V1 followed by a second output: the peek after the bulk read lands on a real byte, the read trips the window
    assert(hex(0).contains(V1 + Value + "0008d3" + "02" + "0000"), "V1 then output 1 (height 2)")
    // the same V1 as the last output: the peek after the bulk read runs past the end of the bytes
    assert(hex(1).endsWith(V1), "V1 as the last output")
    assert(hex(2).endsWith(V2) && hex(3).endsWith(V3) && hex(4).endsWith(V4), "V2, V3, V4 as the last output")
  }

  Seq(AuthoredWireBoxTreeParse.OpBoxRoot -> "Box", AuthoredWireBoxTreeParse.OpTxRoot -> "Transaction").foreach {
    case (op, kind) =>
      test(s"$kind root type: unsized SigmaProp accepts, unsized Int rejects, sized Int degrades and accepts") {
        val es = entries(op)
        assertEquals(es.map(isReject), List(false, true, false))
        // v0 unsized: 00 then the root; v0 sized: 08, size 2, then the root. Int 1 = 04 02, TrueProp = 08 d3.
        Seq("00" + "08d3", "00" + "0402", "08" + "02" + "0402").zip(es).foreach { case (tree, e) =>
          assert(bytesHex(e).contains(Value + tree + Fields), s"candidate with tree $tree")
        }
      }
  }

  // SFunc(Int => Int): type code 0x70, one domain type, Int (04), range Int (04), no type params.
  private val FuncType = "70" + "01" + "04" + "04" + "00"
  // Sized, segregated tree of version v (header 0x18 | v): one constant of the function type, then 02 and body
  // placeholder 0 (9 content bytes, all inside the declared size).
  private def funcConstTree(v: Int): String = "%02x".format(0x18 | v) + "09" + "01" + FuncType + "02" + "7300"

  test("Box function type: an R4 of type 0x70 rejects, an Int R4 accepts, v2 and v3 trees with a 0x70 constant degrade") {
    val es = entries(AuthoredWireBoxTreeParse.OpBoxFunc)
    assertEquals(es.map(isReject), List(true, false, false, false))
    // [1 register][R4] after height 1 and no tokens; the R4 function type has no data, so nothing more is read
    Seq(Value + "0008d3" + "01" + "00" + "01" + FuncType, Value + "0008d3" + "01" + "00" + "01" + "0402",
      Value + funcConstTree(2) + Fields, Value + funcConstTree(3) + Fields).zip(es).foreach { case (cand, e) =>
      assert(bytesHex(e).startsWith(cand), s"box starts with $cand")
    }
  }

  test("Transaction function type: an extension value of type 0x70 rejects, Int accepts, v2 and v3 tree outputs degrade") {
    val es = entries(AuthoredWireBoxTreeParse.OpTxFunc)
    assertEquals(es.map(isReject), List(true, false, false, false))
    // input 0's extension starts at byte 34 (hex 68): [count 01][id 01][value]
    assertEquals(bytesHex(es(0)).substring(68, 68 + 14), "0101" + FuncType)
    assertEquals(bytesHex(es(1)).substring(68, 68 + 8), "0101" + "0402")
    assert(bytesHex(es(2)).endsWith(Value + funcConstTree(2) + Fields))
    assert(bytesHex(es(3)).endsWith(Value + funcConstTree(3) + Fields))
  }

  Seq(AuthoredWireBoxTreeParse.OpBoxValUse -> "Box", AuthoredWireBoxTreeParse.OpTxValUse -> "Transaction").foreach {
    case (op, kind) =>
      test(s"$kind unbound ValUse: sized 08 02 72 01 rejects, the bound twin accepts, unsized 00 72 01 rejects") {
        val es = entries(op)
        assertEquals(es.map(isReject), List(true, false, true))
        // ValUse(1) = 72 01; the twin is BlockValue (d8) of one ValDef (d6) id 1 = SigmaProp(true), then ValUse(1)
        Seq("08" + "02" + "7201", "08" + "08" + "d801" + "d601" + "08d3" + "7201", "00" + "7201").zip(es).foreach {
          case (tree, e) => assert(bytesHex(e).contains(Value + tree + Fields), s"candidate with tree $tree")
        }
      }
  }

  test("envelopes: santa-wire/v1, the node's v6 parse context (3, 3), authored source, 6.0.6 blessing") {
    assertEquals(vectors.keySet, Set(AuthoredWireBoxTreeParse.OpBoxWindow, AuthoredWireBoxTreeParse.OpTxWindow,
      AuthoredWireBoxTreeParse.OpBoxRoot, AuthoredWireBoxTreeParse.OpTxRoot,
      AuthoredWireBoxTreeParse.OpBoxFunc, AuthoredWireBoxTreeParse.OpTxFunc,
      AuthoredWireBoxTreeParse.OpBoxValUse, AuthoredWireBoxTreeParse.OpTxValUse))
    vectors.foreach { case (op, env) =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
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
    AuthoredWireBoxTreeParse.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
