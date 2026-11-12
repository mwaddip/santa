package santa

import java.math.BigInteger

import io.circe.Json

/** Anchors the three BigInt wide-value families (sigma-rust's santa-probe-bigint-range-2). `SpecExtract`
  * already fails loud if an accept does not evaluate or a reject does; pinned here is what would rot
  * silently while still grading green:
  *
  *  - each tree is the sigma-rust session's own encoding where it gave one, re-built from its AST;
  *  - every returned value and cost, the twins' costs equal to their rows';
  *  - each refusal's MECHANISM, from the oracle's own string: the constant writer's "doesn't fit into
  *    256 bits" (S1/S5), the constructor's "Too big bigint value" (M1 at v3), `shiftLeft`'s
  *    "BigInteger out of 256 bit range" (H1-H3);
  *  - the v0 rows answer identically at activated 2: the gate is the tree version.
  */
class AuthoredEvalBigIntWideValuesTest extends munit.FunSuite {
  private val B = AuthoredEvalBigIntWideValues
  private lazy val v = B.extract()
  private def entries(op: String): List[Json] =
    v(op).hcursor.downField("entries").as[List[Json]].fold(e => fail(s"entries: $e"), identity)
  private def all: List[Json] = List(B.OpSubst, B.OpExp, B.OpShift).flatMap(entries)
  private def str(e: Json, f: String): String = e.hcursor.get[String](f).toOption.getOrElse(fail(f))
  private def treeV(e: Json): Int = e.hcursor.downField("version").get[Int]("ergoTree").toOption.getOrElse(fail("v"))
  private def id(e: Json): String = str(e, "script").takeWhile(_ != ']').drop(1)
  private def at(op: String, i: String, tv: Int): Json =
    entries(op).find(e => id(e) == i && treeV(e) == tv).getOrElse(fail(s"no row $i at v$tv"))
  private def errored(e: Json): Boolean =
    e.hcursor.downField("expected").get[String]("error").toOption.contains("errored")
  private def cost(e: Json): Option[Long] = e.hcursor.downField("expected").get[Long]("cost").toOption
  private def value(e: Json) = e.hcursor.downField("expected").downField("value")
  private def bytesOf(e: Json): String = value(e).downField("items").as[List[Json]].fold(_ => fail("items"), identity)
    .map(_.hcursor.get[Int]("value").toOption.getOrElse(fail("byte"))).map(b => f"${b & 0xff}%02x").mkString
  private def point(e: Json): String = value(e).get[String]("bytes_hex").toOption.getOrElse(fail("bytes_hex"))
  private def big(e: Json): BigInteger = new BigInteger(value(e).get[String]("value").toOption.getOrElse(fail("value")))

  private val input = Json.obj("kind" -> Json.fromString("Int"), "value" -> Json.fromInt(0))
  private def oracle(e: Json, activated: Byte) = EvalCore.evalApplied(str(e, "tree_bytes_hex"), input, activated)._2
  private def refusal(e: Json): String = oracle(e, B.V3) match {
    case Left(err) => err
    case Right(r)  => fail(s"${str(e, "name")}: expected a refusal, got $r")
  }

  test("21 rows in three files; 7 / 5 / 9, with 2 / 1 / 3 refusals") {
    assertEquals(entries(B.OpSubst).size, 7)
    assertEquals(entries(B.OpExp).size, 5)
    assertEquals(entries(B.OpShift).size, 9)
    assertEquals(entries(B.OpSubst).count(errored), 2)
    assertEquals(entries(B.OpExp).count(errored), 1)
    assertEquals(entries(B.OpShift).count(errored), 3)
    all.foreach(e => assertEquals(e.hcursor.downField("version").get[Int]("activated").toOption, Some(3)))
  }

  test("each tree is built from its AST, and is the probe's own encoding where it gave one") {
    B.Rows.foreach { r =>
      val e = at(r.op, r.id, r.treeV)
      val built = B.body(r.ast(), r.treeV)
      assertEquals(str(e, "tree_bytes_hex"), B.tree(built, r.treeV), s"${r.id} v${r.treeV}")
      if (r.requestHex.nonEmpty) assertEquals(built, r.requestHex, s"${r.id}: AST vs the probe's hex")
    }
  }

  test("SubstConstants: t unchanged @265 where X is not written, the -6 tree @300 where Y is first") {
    Seq("S2", "S3", "S2t").foreach { i =>
      val e = at(B.OpSubst, i, 0)
      assertEquals(bytesOf(e), B.T, i); assertEquals(cost(e), Some(265L), i)
    }
    Seq("S4", "S4t").foreach { i =>
      val e = at(B.OpSubst, i, 0)
      assertEquals(bytesOf(e), "10010601fad1939f82730082", i); assertEquals(cost(e), Some(300L), i)
    }
    Seq("S1", "S5").foreach { i =>
      val err = refusal(at(B.OpSubst, i, 0))
      assert(err.startsWith("IllegalArgumentException") && err.contains("doesn't fit into 256 bits"),
        s"$i must fail the constant writer's range requirement, got: ${err.take(160)}")
    }
  }

  test("GroupElement.exp: the method call returns g^(2^255) @949 at v0, the node's point @945; X throws at v3") {
    val gX = "02b23790a42be63e1b251ad6c94fdef07271ec0aada31db6c3e8bd32043f8be384"
    val gY = "02fff97bd5755eeea420453a14355235d382f6472f8568a18b2f057a1460297556"
    assertEquals(point(at(B.OpExp, "M1", 0)), gX); assertEquals(cost(at(B.OpExp, "M1", 0)), Some(949L))
    assertEquals(point(at(B.OpExp, "E1", 0)), gX); assertEquals(cost(at(B.OpExp, "E1", 0)), Some(945L))
    Seq(0, 3).foreach { tv =>
      assertEquals(point(at(B.OpExp, "M2", tv)), gY); assertEquals(cost(at(B.OpExp, "M2", tv)), Some(949L))
    }
    val err = refusal(at(B.OpExp, "M1", 3))
    assert(err.startsWith("ArithmeticException") && err.contains("Too big bigint value"),
      s"M1 at v3 must fail X's construction, got: ${err.take(160)}")
  }

  test("shifts and inverse at v3: shiftLeft past the range throws, the edges stay in it") {
    Seq("H1", "H2", "H3").foreach { i =>
      val err = refusal(at(B.OpShift, i, 3))
      assert(err.startsWith("ArithmeticException") && err.contains("BigInteger out of 256 bit range"),
        s"$i must fail shiftLeft's result check, got: ${err.take(160)}")
    }
    def is(i: String, n: BigInteger, c: Long): Unit = {
      val e = at(B.OpShift, i, 3)
      assertEquals(big(e), n, i); assertEquals(cost(e), Some(c), i)
    }
    is("H4", BigInteger.ONE.shiftLeft(254), 19)
    is("H5", B.Min, 19)
    is("R1", BigInteger.ONE.negate(), 19)
    is("R2", BigInteger.ZERO, 19)
    is("I1", B.Max, 14)
    is("I2", B.Min, 14)
  }

  test("the v0 rows answer identically at activated 2: the gate is the TREE version") {
    all.filter(treeV(_) == 0).foreach { e =>
      assertEquals(oracle(e, 2.toByte), oracle(e, B.V3), str(e, "name"))
    }
  }

  test("envelope + write staging files") {
    List(B.OpSubst, B.OpExp, B.OpShift).foreach { op =>
      assertEquals(v(op).hcursor.get[String]("blessed_by").toOption, Some(B.Stamp))
      assertEquals(v(op).hcursor.get[String]("source").toOption, Some(B.Source))
    }
    val outDir = java.nio.file.Paths.get("target", "eval-authored")
    B.writeVectors(outDir)
    List(B.OpSubst, B.OpExp, B.OpShift).foreach(op => assert(java.nio.file.Files.exists(outDir.resolve(s"$op.json"))))
  }
}
