package santa

import io.circe.Json

/** Anchors the ContextExtension parse-rule wire vectors (CheckV6Type, value depth, duplicate ids).
  * extract() re-derives every verdict through WireCanonicalize and fails loud on a wrong-reason reject
  * or a non-canonical accept; this pins each entry's verdict and its extension bytes, hand-derived from
  * the sigmastate v6.0.6 type and data encodings. A failure means sigma-state changed how it parses an
  * extension value, or the blesser built a different value than the entry describes. */
class AuthoredWireContextExtensionParseTest extends munit.FunSuite {
  private lazy val vectors = AuthoredWireContextExtensionParse.extract()

  private def entries(op: String): List[Json] =
    vectors(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def isReject(e: Json): Boolean = e.hcursor.get[String]("error").toOption.contains("errored")
  private def str(e: Json, field: String): String = e.hcursor.get[String](field).toOption.getOrElse(fail(field))

  /** `bytes` bytes of input 0's extension, which starts at byte 34 (inputs count + boxId + empty-proof
    * length), i.e. hex offset 68. */
  private def extAt(hex: String, bytes: Int): String = hex.substring(68, 68 + 2 * bytes)

  test("v6 type: UBI, Coll[Option[Int]], Coll[Header], (Int, UBI) reject; their v5-type twins accept") {
    val es = entries(AuthoredWireContextExtensionParse.OpV6Type)
    // [count 01][id 01][type][data]
    val want = List(
      true  -> ("0101" + "09" + "0101"),          // UnsignedBigInt(1): type 9, UShort length 1, byte 01
      false -> ("0101" + "06" + "0101"),          // BigInt(1): type 6, same data layout
      true  -> ("0101" + "0c28" + "00"),          // Coll[Option[Int]]: generic Coll 12, Option[Int] 36+4, empty
      true  -> ("0101" + "0c68" + "00"),          // Coll[Header]: generic Coll 12, SHeader 104, empty
      false -> ("0101" + "10" + "00"),            // Coll[Int]: 12+4, empty
      true  -> ("0101" + "4009" + "02" + "0101"), // (Int, UBI): Pair1 60+4, then UBI 9; Int 1 zigzag 02
      false -> ("0101" + "4006" + "02" + "0101")) // (Int, BigInt)
    assertEquals(es.size, want.size)
    es.zip(want).foreach { case (e, (reject, ext)) =>
      assertEquals(isReject(e), reject, str(e, "name"))
      assertEquals(extAt(str(e, "bytes_hex"), ext.length / 2), ext, str(e, "name"))
    }
  }

  test("depth: Coll^109[Byte] accepts at the 110-level cap, Coll^110[Byte] rejects") {
    val es = entries(AuthoredWireContextExtensionParse.OpDepth)
    assertEquals(es.map(isReject), List(false, true))
    // Coll^n[Byte] type: (n-2) generic Coll 0x0c, then Coll[Coll[Byte]] 0x1a. Data: (n-1) outer
    // lengths of 1, then the empty innermost Coll[Byte].
    def collN(n: Int): String = "0c" * (n - 2) + "1a" + "01" * (n - 1) + "00"
    Seq(0 -> 109, 1 -> 110).foreach { case (i, n) =>
      val ext = "0101" + collN(n)
      assertEquals(extAt(str(es(i), "bytes_hex"), ext.length / 2), ext, s"Coll^$n[Byte]")
    }
  }

  test("duplicate ids collapse to the last value, at the id's first position") {
    val es = entries(AuthoredWireContextExtensionParse.OpDupIds)
    // Int(k) is type 04 then zigzag VLQ 2k. Inputs are raw (the JVM serializer cannot write a
    // repeated id); outputs are the JVM re-serialization.
    val want = List(
      ("03" + "05" + "0402" + "07" + "0404" + "05" + "0406") -> ("02" + "05" + "0406" + "07" + "0404"),
      ("03" + "07" + "0402" + "05" + "0404" + "07" + "0406") -> ("02" + "07" + "0406" + "05" + "0404"))
    assertEquals(es.size, want.size)
    es.zip(want).foreach { case (e, (in, out)) =>
      val name = str(e, "name")
      assert(!isReject(e), name)
      val b = str(e, "bytes_hex")
      val x = str(e, "expected_bytes_hex")
      assertEquals(extAt(b, in.length / 2), in, name)
      assertEquals(extAt(x, out.length / 2), out, name)
      assertEquals(x.take(68), b.take(68), s"$name: bytes before the extension")
      assertEquals(x.drop(68 + out.length), b.drop(68 + in.length), s"$name: bytes after the extension")
    }
  }

  test("envelopes: santa-wire/v1, the node's v6 tx parse context (3, 3), authored source, 6.0.6 blessing") {
    assertEquals(vectors.keySet, Set(AuthoredWireContextExtensionParse.OpV6Type,
      AuthoredWireContextExtensionParse.OpDepth, AuthoredWireContextExtensionParse.OpDupIds))
    vectors.values.foreach { env =>
      val c = env.hcursor
      assertEquals(c.get[String]("schema").toOption, Some("santa-wire/v1"))
      assertEquals(c.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.6"))
      c.downField("entries").as[List[Json]].getOrElse(Nil).foreach { e =>
        assertEquals(e.hcursor.get[String]("kind").toOption, Some("Transaction"))
        assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3))
        assertEquals(e.hcursor.downField("version").get[Int]("ergoTree").toOption, Some(3))
        assert(e.hcursor.get[String]("source").toOption.exists(_.startsWith("santa:")))
      }
    }
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "wire-authored")
    AuthoredWireContextExtensionParse.writeVectors(outDir)
    vectors.keys.foreach { op =>
      assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json")), s"staging $op.json not written")
    }
  }
}
