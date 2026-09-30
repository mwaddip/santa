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

  // The degrade gate: size-flagged trees whose parse throws something other than a ValidationException (rejects),
  // each beside an accept twin. vlq(2^31) = 80 80 80 80 08, vlq(2^31 - 1) = ff ff ff ff 07; a nested box is
  // value c0843d, tree 00 08 d3, the height, no tokens, the registers, a 32-byte tx id and index 00.
  private val Zeros31 = zeros(31)
  private def nested(height: String, regs: String): String = Value + "0008d3" + height + "00" + regs + zeros(32) + "00"
  private val GateTrees: List[(String, Boolean)] = List(
    "08027305" -> true,                                                        // ConstantPlaceholder(5), no constants
    "18050108d37300" -> false,                                                 // segregated SigmaProp(true), placeholder 0
    "080100" -> true,                                                          // a constant of type code 0
    "08020801" -> true,                                                        // SigmaProp, SigmaBoolean opcode 0x01
    "080208d3" -> false,                                                       // SigmaProp(true)
    "0823" + "0621" + "0001" + Zeros31 -> true,                                // BigInt of declared size 33
    "0822" + "0620" + "01" + Zeros31 -> false,                                 // BigInt of size 32 (degrades)
    "0810" + "d801" + "d6" + "8080808008" + "08d3" + "72" + "8080808008" -> true,  // ValDef/ValUse id 2^31
    "0810" + "d801" + "d6" + "ffffffff07" + "08d3" + "72" + "ffffffff07" -> false, // id 2^31 - 1
    "0809" + "d801" + "d701" + "ff" + "08d3" + "7201" -> true,                 // FunDef, type-argument count -1
    "080a" + "d801" + "d701" + "01" + "04" + "08d3" + "7201" -> true,          // FunDef, type argument Int
    "080c" + "d801" + "d701" + "01" + "670154" + "08d3" + "7201" -> false,     // FunDef, type argument T
    "1b0a" + "01" + "7001040401" + "04" + "02" + "7300" -> true,               // v3 SFunc, type parameter Int
    "1b0c" + "01" + "7001040401" + "670154" + "02" + "7300" -> false,          // v3 SFunc, type parameter T (degrades)
    "1834" + "0263" + nested("8080808008", "00") + "08d37301" -> true,         // Box constant, height 2^31
    "1834" + "0263" + nested("ffffffff07", "00") + "08d37301" -> false,        // Box constant, height 2^31 - 1
    "1831" + "0263" + nested("01", "01" + "a3") + "08d37301" -> true,          // Box constant, R4 = Height
    "183e" + "0263" + nested("01", "07" + "0402" * 7) + "08d37301" -> true,    // Box constant, 7 registers
    "183c" + "0263" + nested("01", "06" + "0402" * 6) + "08d37301" -> false)   // Box constant, 6 registers

  Seq(AuthoredWireBoxTreeParse.OpBoxGate -> "Box", AuthoredWireBoxTreeParse.OpTxGate -> "Transaction").foreach {
    case (op, kind) =>
      test(s"$kind degrade gate: 11 non-ValidationException rejects, each with its accept twin") {
        val es = entries(op)
        assertEquals(es.map(isReject), GateTrees.map(_._2))
        GateTrees.zip(es).foreach { case ((tree, _), e) =>
          assert(bytesHex(e).contains(Value + tree + Fields), s"candidate with tree $tree")
        }
      }
  }

  // Parse acceptance: node-construction checks at parse. Each tree wraps its node in BoolToSigmaProp (d1); v0
  // unsized unless the header says otherwise (08 = v0 sized, 0b = v3 sized). Int 1 = 04 02, Long 1 = 05 02,
  // true = 01 01.
  private val AcceptanceTrees: List[(String, Boolean)] = List(
    "00d1ae0402d90101040101" -> false,         // Exists(Int 1, (x: Int) => true)
    "00d1ef0402" -> false,                     // LogicalNot(Int 1)
    "00d17f" -> false,                         // TrueLeaf, opcode 7f
    "00d180" -> false,                         // FalseLeaf, opcode 80
    "00d193db630104020502" -> false,           // EQ(PropertyCall(SBox.value, Int 1), Long 1)
    "00d1da040200" -> false,                   // Apply(Int 1, [])
    "00d193b1b3040204040400" -> true,          // EQ(SizeOf(Append(Int 1, Int 2)), Int 0)
    "080ad193b1b3040204040400" -> true,        // the same, sized
    "00d193b1b40402040004020400" -> true,      // EQ(SizeOf(Slice(Int 1, 0, 1)), Int 0)
    "080cd193b1b40402040004020400" -> true,    // the same, sized
    "00d1e60402" -> false,                     // OptionIsDefined(Int 1)
    "0b06d19304020502" -> true,                // v3 EQ(Int 1, Long 1)
    "00d19304020502" -> false,                 // v0 EQ(Int 1, Long 1): upcast
    "0b06d19304020402" -> false,               // v3 EQ(Int 1, Int 1)
    "00d19101010101" -> true,                  // GT(true, true)
    "0806d19101010101" -> true,                // the same, sized
    "00d19104020402" -> false,                 // GT(Int 1, Int 1)
    "00d193f2010101010400" -> true,            // EQ(BitOr(true, true), Int 0)
    "0809d193f2010101010400" -> true,          // the same, sized
    "00d193f2040204020400" -> false,           // EQ(BitOr(Int 1, Int 1), Int 0)
    "00d193b18301040502" + "0402" -> true,     // EQ(SizeOf(Coll[Int](Long 1)), Int 1): an item of the wrong type
    "080ad193b18301040502" + "0402" -> true,   // the same, sized
    "00d193b18301040402" + "0402" -> false,    // EQ(SizeOf(Coll[Int](Int 1)), Int 1)
    "00d1e6dc650bfe010200" -> false,           // MethodCall(CONTEXT, SContext method 11 getVar, [Byte 0]).isDefined
    "0809d1e6dc650bfe010200" -> false,         // the same, sized
    // ergots' node-construction requests (2026-09-30). Upcast = 7e, Downcast = 7d: input, then the target type.
    "0804" + "7e010105" -> true,               // sized root Upcast(true, Long)
    "0804" + "7e100005" -> true,               // sized root Upcast(Coll[Int](), Long)
    "0804" + "7d010102" -> true,               // sized root Downcast(true, Byte)
    "0804" + "7d100002" -> true,               // sized root Downcast(Coll[Int](), Byte)
    "0804" + "7e040205" -> false,              // sized root Upcast(Int 1, Long): a Long root, rule 1001 degrades it
    "0804" + "7e040201" -> true,               // sized root Upcast(Int 1, Boolean): a non-numeric target
    // EQ(SizeOf(Coll[Long](Plus(Int 1, Long 2))), Int 1): v0 upcasts the Int, v3 keeps Plus an Int
    "00" + "d193b1830105" + "9a04020504" + "0402" -> false,
    "0b0d" + "d193b1830105" + "9a04020504" + "0402" -> true,
    "00" + "d1b20d0101050000" -> true,         // ByIndex(Coll(true), Long 0): v0 upcasts the index to Int, and fails
    "0b08" + "d1b20d0101050000" -> false,      // the same at v3: the index is not checked
    "00" + "d8010402" + "08d3" -> true,        // BlockValue([Int 1], SigmaProp(true)): an item that is not a ValDef
    "0806" + "d8010402" + "08d3" -> true,      // the same, sized
    "00" + "d801d6010402" + "08d3" -> false,   // BlockValue([ValDef(1, Int 1)], SigmaProp(true))
    "00" + "d1e6c6a70a04" -> true,             // SELF.R10[Int].isDefined
    "00" + "d1e6c6a78004" -> true,             // register id 0x80
    "00" + "d1e6c6a70904" -> false,            // SELF.R9[Int].isDefined
    "00" + "d50a0800" -> true,                 // root DeserializeRegister(R10, SigmaProp)
    "00" + "d5090800" -> false,                // root DeserializeRegister(R9, SigmaProp)
    "0b07" + "d1e6dc650bfe00" -> true,         // v3 MethodCall(CONTEXT, method 11) with no arguments
    "00" + "d1e6dc650bfe00" -> false,          // the same at v0: parses, written back as a PropertyCall (db)
    "0b09" + "d1e6dc650bfe010200" -> false)    // v3 with its argument, Byte 0

  // The ordering pair (#46, #47): a size-flagged v0 tree declared 12 bytes, BoolToSigmaProp(If(EQ(Upcast(<input>, Long),
  // Long 0), Coll[Byte](4083), ...)). The Upcast is built at candidate offset 10; the Coll[Byte]'s bulk read runs from
  // 17 to 4100, past the tree window (4099), so the read of If's third child trips it. After the degrade the box
  // resumes at 17: height 1, no tokens, R4 = Coll[Byte](4077). vlq(4083) = f3 1f, vlq(4077) = ed 1f.
  private def orderingCand(input: String): String = Value + "08" + "0c" + "d1" + "95" + "93" + "7e" + input + "05" +
    "0500" + "0e" + "f31f" + "01" + "00" + "01" + "0e" + "ed1f" + zeros(4077)

  Seq(AuthoredWireBoxTreeParse.OpBoxAcceptance -> "Box", AuthoredWireBoxTreeParse.OpTxAcceptance -> "Transaction").foreach {
    case (op, kind) =>
      test(s"$kind parse acceptance: unchecked nodes parse; erased casts, builder constraints and a mistyped item reject") {
        val es = entries(op)
        assertEquals(es.map(isReject), AcceptanceTrees.map(_._2) ++ List(true, false))
        AcceptanceTrees.zip(es).foreach { case ((tree, _), e) =>
          assert(bytesHex(e).contains(Value + tree + Fields), s"candidate with tree $tree")
        }
        // The ordering pair differs only in the Upcast's input: true (01 01), then Int 1 (04 02).
        val Seq(order, orderTwin) = es.drop(AcceptanceTrees.size)
        assertEquals(orderingCand("0101").length, 2 * 4100)
        assert(bytesHex(order).contains(orderingCand("0101")), "the Upcast(true) candidate")
        assert(bytesHex(orderTwin).contains(orderingCand("0402")), "the Upcast(Int 1) candidate")
        assertEquals(bytesHex(order).replace(orderingCand("0101"), orderingCand("0402")), bytesHex(orderTwin))
        // TrueLeaf and FalseLeaf are Boolean constants: the JVM writes them back as 01 01 and 01 00, not 7f and 80.
        // A v0 MethodCall with no arguments is written back as a PropertyCall: dc and the argument count become db.
        def rewritten(e: Json): Option[String] = e.hcursor.get[String]("expected_bytes_hex").toOption
        val want = Map(2 -> ("00d17f", "00d10101"), 3 -> ("00d180", "00d10100"),
          44 -> ("00d1e6dc650bfe00", "00d1e6db650bfe"))
        es.zipWithIndex.foreach { case (e, i) =>
          want.get(i) match {
            case Some((from, to)) =>
              assertEquals(rewritten(e), Some(bytesHex(e).replace(Value + from + Fields, Value + to + Fields)))
            case None => assertEquals(rewritten(e), None, s"entry $i round-trips to itself")
          }
        }
      }
  }

  test("envelopes: santa-wire/v1, the node's v6 parse context (3, 3), authored source, 6.0.6 blessing") {
    assertEquals(vectors.keySet, Set(AuthoredWireBoxTreeParse.OpBoxWindow, AuthoredWireBoxTreeParse.OpTxWindow,
      AuthoredWireBoxTreeParse.OpBoxRoot, AuthoredWireBoxTreeParse.OpTxRoot,
      AuthoredWireBoxTreeParse.OpBoxFunc, AuthoredWireBoxTreeParse.OpTxFunc,
      AuthoredWireBoxTreeParse.OpBoxValUse, AuthoredWireBoxTreeParse.OpTxValUse,
      AuthoredWireBoxTreeParse.OpBoxGate, AuthoredWireBoxTreeParse.OpTxGate,
      AuthoredWireBoxTreeParse.OpBoxAcceptance, AuthoredWireBoxTreeParse.OpTxAcceptance))
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
