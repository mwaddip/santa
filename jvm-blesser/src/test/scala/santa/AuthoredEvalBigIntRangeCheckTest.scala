package santa

import java.math.BigInteger

import io.circe.Json

/** Anchors the BigInt range-check family. `SpecExtract` already fails loud if an accept does not
  * evaluate or a reject does; pinned here is what would rot silently while still grading green:
  *
  *  - the TREE version is the gate, not the activated one. The vectors carry activated 3 only (an
  *    activated-2 entry has no home in `v6/`), so the claim lives HERE: the v0 rows must answer
  *    identically at activated 2.
  *  - G1's value is exactly 2^255 and OUT of the 256-bit range, and G0's is 2^255 - 1 and IN it —
  *    the two numbers the family exists to carry.
  *  - each refusal's mechanism, from the oracle's own strings: G0/G1 at v3 the CONSTRUCTOR's
  *    "Too big bigint value", G3 `negate`'s "out of 256 bit range" at both versions, G4/G5
  *    `mod`'s "modulus not positive".
  *  - G0/G1 straddle the gate on the same body; G2-G5 do not.
  */
class AuthoredEvalBigIntRangeCheckTest extends munit.FunSuite {
  private val B = AuthoredEvalBigIntRangeCheck
  private lazy val v = B.extract()
  private def entries: List[Json] = v(B.Op)
    .hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def treeV(e: Json): Int =
    e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("version.ergoTree"))
  private def rows(id: String): List[Json] = entries.filter(e => str(e, "script").startsWith(s"[$id] "))
  private def at(id: String, tv: Int): Json = rows(id).find(treeV(_) == tv).getOrElse(fail(s"$id v$tv"))
  private def bigValue(e: Json): BigInteger = {
    val c = e.hcursor.downField("expected").downField("value")
    assertEquals(c.get[String]("kind").toOption, Some("BigInt"), s"${str(e, "name")} kind")
    new BigInteger(c.get[String]("value").toOption.getOrElse(fail("value")))
  }

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def oracle(body: String, tv: Int, activated: Byte) =
    EvalCore.evalApplied(B.treeFor(body, tv), input, activated = activated)._2
  private def refusal(body: String, tv: Int): String = oracle(body, tv, B.V3) match {
    case Left(err) => err.toString
    case Right(r)  => fail(s"expected a refusal, got $r")
  }

  private val TwoTo255 = BigInteger.ONE.shiftLeft(255)

  test("12 rows: G0/G1 straddle the gate, G2 returns at both, G3-G5 refuse at both") {
    assertEquals(entries.size, 12)
    Seq("G0", "G1").foreach { id =>
      assert(errored(at(id, 3)), s"$id must refuse at v3")
      assert(!errored(at(id, 0)), s"$id must RETURN at v0")
      assertEquals(str(at(id, 3), "tree_bytes_hex").drop(4), str(at(id, 0), "tree_bytes_hex").drop(2),
        s"$id: the pair must be the SAME body, one header byte apart")
    }
    rows("G2").foreach(e => assert(!errored(e), "G2 must RETURN"))
    Seq("G3", "G4", "G5").foreach(id => rows(id).foreach(e => assert(errored(e), s"$id must refuse")))
    assertEquals(entries.count(errored), 8)
  }

  test("THE NUMBERS: G1 at v0 is 2^255, OUT of range; G0 at v0 is 2^255 - 1, IN it") {
    val g1 = bigValue(at("G1", 0))
    assertEquals(g1, TwoTo255)
    assertEquals(g1.bitLength, 256, "G1 must be OUTSIDE the 256-bit signed range — the family's point")
    assertEquals(cost(at("G1", 0)), Some(35L))
    val g0 = bigValue(at("G0", 0))
    assertEquals(g0, TwoTo255.subtract(BigInteger.ONE))
    assertEquals(g0.bitLength, 255, "G0 must be back IN range")
    assertEquals(cost(at("G0", 0)), Some(60L))
    rows("G2").foreach { e =>
      assertEquals(bigValue(e), BigInteger.valueOf(-7))
      assertEquals(cost(e), Some(60L), "the twin costs what G0 costs at v0: magnitude is free")
    }
  }

  test("the gate is the TREE version: every v0 row answers identically at activated 2") {
    Seq(B.G0, B.G1, B.G2, B.G3, B.G4, B.G5).foreach { body =>
      val a3 = oracle(body, 0, B.V3)
      val a2 = oracle(body, 0, VersionContext2)
      assertEquals(a2.map(_.toString), a3.map(_.toString), s"v0 body $body: activated 2 vs 3")
    }
  }
  private def VersionContext2: Byte = sigma.VersionContext.JitActivationVersion

  test("THE MECHANISMS, from the oracle's own strings") {
    Seq(("G0", B.G0), ("G1", B.G1)).foreach { case (id, body) =>
      val r = refusal(body, 3)
      assert(r.contains("ArithmeticException") && r.contains("Too big bigint value"),
        s"$id at v3 must fail in CBigInt's CONSTRUCTOR check, got: ${r.take(100)}")
    }
    Seq(0, 3).foreach { tv =>
      val g3 = refusal(B.G3, tv)
      assert(g3.contains("BigInteger out of 256 bit range"),
        s"G3 at v$tv must fail negate's unconditional check, got: ${g3.take(100)}")
      Seq(("G4", B.G4), ("G5", B.G5)).foreach { case (id, body) =>
        val r = refusal(body, tv)
        assert(r.contains("modulus not positive"), s"$id at v$tv must fail BigInteger.mod, got: ${r.take(100)}")
      }
    }
  }

  test("every row's declared ergoTree matches its own tree header byte") {
    entries.foreach { e =>
      val expected = str(e, "tree_bytes_hex").take(2) match {
        case "0b" => 3
        case "00" => 0
        case other => fail(s"${str(e, "name")}: unexpected header $other")
      }
      assertEquals(treeV(e), expected, s"${str(e, "name")}: header disagrees with version.ergoTree")
    }
  }

  test("envelope: the 6.0.7 stamp") {
    assertEquals(v(B.Op).hcursor.get[String]("blessed_by").toOption, Some("jvm:sigma-state-6.0.7"))
  }

  test("write staging files") {
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    assert(java.nio.file.Files.exists(outDir.resolve(s"${B.Op}.json")))
  }
}
